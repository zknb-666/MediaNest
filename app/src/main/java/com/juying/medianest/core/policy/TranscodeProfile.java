package com.juying.medianest.core.policy;

import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.util.CodecNames;


import java.util.ArrayList;
import java.util.List;

/**
 * 播放前的「设备能力 → NAS 转码参数」决策，纯 Java，可在 JVM 上单测。
 *
 * 真机实测（TCL Android 4.2.1 / API 17 / 2026-11-18）：
 *  1) 4K HEVC Main10 原片若只带 video_encoder=h264，NAS 只转视频，音频仍把
 *     原始 E-AC-3 5.1 透传进 TS（PMT stream_type=0x87）。设备没有 EAC3 解码器，
 *     播放器不创建音轨 → 无声；音频时钟缺失还会拖住 AVSync，最终表现为
 *     [OMX.MS.AVC.Decoder] Timed out waiting for output buffers: 0/4 卡死。
 *  2) POST /v/api/v1/play/play 里只有 "video_encoder" / "audio_encoder" 能触发
 *     真转码；master playlist 的 CODECS="...,mp4a.40.34" 是硬编码模板，会谎报 AAC，
 *     不能作为能力判据（真实编码要看 TS 的 PMT）。
 *  3) 音频编码矩阵实测：EAC3 透传 / AAC(5.1) 都打不开音频解码器；
 *     AC3 与 MP3 都能打开 AudioSink（48kHz / 2ch）并产生活动音轨。
 *     因此老设备优先 AC3，连续降级时退回 MP3。
 *
 * 结论：只要走 NAS 解码流，就必须同时显式下发 video_encoder 与 audio_encoder。
 */
public final class TranscodeProfile {

    public boolean needTranscode = false;
    /** "" 或 "h264"。 */
    public String videoEncoder = "";
    /** "" 或 "aac" / "ac3" / "mp3"。 */
    public String audioEncoder = "";
    /** 本次请求的音频声道数；首次起播取源轨与解码器支持的最高值，降级时回到立体声。 */
    public int audioChannels = 2;
    /** "" 或 "4k"/"1080"/"720"/"540"/"480"/"360"。 */
    public String resolution = "";
    /**
     * 主动请求的目标视频码率，单位 bps（NAS 接口原样使用，客户端不做换算），0 表示不干预。
     */
    public int bitrateBps = 0;
    /** 给 OSD / 日志看的中文说明。 */
    public String note = "";

    /** 原音轨是否确实因为「音频能力不足」而需要转换。 */
    public boolean audioUnsupported = false;
    /** 视频转码时是否还需要转音频；false 表示保留源音频编码。 */
    public boolean needAudioTranscode;

    /** 兼容旧调用：未提供 NAS 画质挡位时按设备能力推导。 */
    public static TranscodeProfile pick(Capability cap, MediaItem item,
                                        boolean forceTranscode, int degradeLevel) {
        return pick(cap, item, forceTranscode, degradeLevel, null);
    }

    /**
     * NAS 可用画质挡位由 {@link NasLevels#resolutionValues} 提供时，降级阶梯完全
     * 按服务端返回的挡位逐档下移，客户端不再内置 4K/1080P/720P 之类固定表。
     */
    public static TranscodeProfile pick(Capability cap, MediaItem item,
                                        boolean forceTranscode, int degradeLevel,
                                        List<String> nasResolutionValues) {
        return pick(cap, item, forceTranscode, degradeLevel, nasResolutionValues, "");
    }

    /**
     * 带用户选定挡位的入口：requestedResolution 非空时按该档请求 NAS 转码
     * （超出设备上限会被夹到设备能解的最高档），降级仍从该档继续下移。
     */
    public static TranscodeProfile pick(Capability cap, MediaItem item,
                                        boolean forceTranscode, int degradeLevel,
                                        List<String> nasResolutionValues,
                                        String requestedResolution) {
        TranscodeProfile p = new TranscodeProfile();
        if (cap == null) {
            cap = Capability.baseline();
        }
        if (item == null) {
            item = new MediaItem();
        }

        // ---- 视频能力 ----
        String vc = CodecNames.video(item.videoCodec);
        boolean vUnknown = vc.length() == 0;
        boolean vUnsupported = !vUnknown && !cap.videoCodecs.contains(vc);
        boolean tooDeep = item.bitDepth > cap.maxBitDepth;
        boolean tooLarge = (item.width > 0 && item.width > cap.maxWidth)
                || (item.height > 0 && item.height > cap.maxHeight);
        String container = CodecNames.container(item.container);
        boolean containerUnsupported = container.length() > 0 && !cap.containers.contains(container);

        // ---- 音频能力 ----
        String ac = CodecNames.audio(item.audioCodec);
        boolean aUnknown = ac.length() == 0;
        boolean aUnsupported = !aUnknown && !cap.audioCodecs.contains(ac);
        int sourceChannelLimit = maxAudioChannels(cap, ac);
        boolean tooManyChannels = item.audioChannels > 0 && item.audioChannels > sourceChannelLimit;
        // 老电视的 AAC 硬解常常只吃立体声：编码名支持不代表 5.1 能打开解码器。
        boolean legacyMultiChannelAac = "aac".equals(ac) && item.audioChannels > 2 && cap.sdkInt < 21;
        boolean legacyHdAudio = cap.sdkInt < 21 && isHdAudio(ac);
        p.audioUnsupported = aUnknown || aUnsupported || tooManyChannels
                || legacyMultiChannelAac || legacyHdAudio;

        boolean needVideo = forceTranscode || vUnknown || vUnsupported || tooDeep
                || tooLarge || containerUnsupported || degradeLevel > 0;
        // 关键：即使视频本身可直连，只要音轨不能解，也必须走 NAS 解码流转音频。
        p.needTranscode = needVideo || p.audioUnsupported || degradeLevel > 0;
        if (!p.needTranscode) {
            p.audioEncoder = ac;
            p.audioChannels = Math.max(2, Math.min(sourceChannelLimit,
                    item.audioChannels > 0 ? item.audioChannels : sourceChannelLimit));
            p.note = "原片编码与设备能力匹配，直连播放";
            return p;
        }

        p.videoEncoder = "h264";
        p.needAudioTranscode = p.audioUnsupported || degradeLevel >= 2;
        p.audioEncoder = p.needAudioTranscode ? pickAudioEncoder(cap, degradeLevel) : ac;
        p.audioChannels = p.needAudioTranscode
                ? pickAudioChannels(cap, item, p.audioEncoder, degradeLevel)
                : Math.max(2, Math.min(sourceChannelLimit,
                        item.audioChannels > 0 ? item.audioChannels : sourceChannelLimit));
        p.resolution = pickResolution(cap, item, degradeLevel, nasResolutionValues, requestedResolution);
        p.bitrateBps = targetBitrateBps(cap, p.resolution);

        StringBuilder sb = new StringBuilder();
        sb.append("原片 ").append(vc.length() > 0 ? vc.toUpperCase() : "未知")
                .append(' ').append(item.width).append('x').append(item.height)
                .append(' ').append(item.bitDepth).append("bit");
        sb.append(" / 音轨 ").append(ac.length() > 0 ? ac.toUpperCase() : "未知");
        if (item.audioChannels > 0) {
            sb.append(' ').append(item.audioChannels).append("ch");
        }
        sb.append(" → 请求 NAS 输出 H.264 ").append(NasLevels.labelOf(p.resolution))
                .append(" / ").append(p.audioEncoder.toUpperCase());
        if (p.bitrateBps > 0) {
            sb.append(" / ").append(p.bitrateBps / 1000000).append("Mbps");
        }
        if (p.audioUnsupported) {
            sb.append("（音轨不被支持：");
            if (aUnsupported) sb.append("无 ").append(ac.toUpperCase()).append(" 解码器");
            else if (tooManyChannels) sb.append("超过设备 ").append(sourceChannelLimit).append(" 声道上限");
            else if (legacyMultiChannelAac) sb.append("老设备解不了多声道 AAC");
            else if (legacyHdAudio) sb.append("老设备不支持高清音轨");
            else sb.append("音轨信息未知");
            sb.append('）');
        }
        if (degradeLevel > 0) {
            sb.append(" [降级档 ").append(degradeLevel).append(']');
        }
        p.note = sb.toString();
        return p;
    }

    /**
     * 目标音频编码按「设备真实解码器」挑选。
     * 现代设备优先 AAC；API<21 的老电视优先 AC3（真机可把 5.1 downmix 成 2ch 出声），
     * 连续解码失败降级时强制 MP3（API 1 起就保证可解）。
     */
    public static String pickAudioEncoder(Capability cap, int degradeLevel) {
        if (cap == null) {
            return "mp3";
        }
        if (degradeLevel >= 2) {
            return "mp3";
        }
        if (cap.sdkInt >= 21 && cap.audioCodecs.contains("aac")) {
            return "aac";
        }
        if (cap.audioCodecs.contains("ac3")) {
            return "ac3";
        }
        if (cap.audioCodecs.contains("mp3")) {
            return "mp3";
        }
        if (cap.audioCodecs.contains("aac")) {
            return "aac";
        }
        return "mp3";
    }

    private static int pickAudioChannels(Capability cap, MediaItem item, String encoder,
                                          int degradeLevel) {
        int limit = degradeLevel >= 2 || "mp3".equals(encoder)
                ? 2 : maxAudioChannels(cap, encoder);
        int sourceChannels = item.audioChannels > 0 ? item.audioChannels : limit;
        return Math.max(2, Math.min(limit, sourceChannels));
    }

    private static int maxAudioChannels(Capability cap, String codec) {
        if ("ac3".equals(codec) || "eac3".equals(codec) || "dts".equals(codec)) {
            for (int i = 0; i < cap.decoders.size(); i++) {
                String mime = cap.decoders.get(i).mime;
                if (mime.indexOf(codec) >= 0
                        || ("eac3".equals(codec) && mime.indexOf("ac3") >= 0)) {
                    return 6;
                }
            }
        }
        if (cap.sdkInt < 21) return 2;
        return Math.max(2, cap.maxAudioChannels);
    }

    /** 1080 档默认主动请求的码率封顶，单位 bps。首次起播从最高档开始，失败后由控制器降码率。 */
    public static final int BITRATE_1080_BPS = 12000000;

    /**
     * 主动请求的目标码率（bps）。分两条路径：
     * <ul>
     *   <li><b>有解码器报告</b>（181 的 MediaCodecList / 声明了 Limit 的 media_codecs.xml）：
     *       以该档位的合理目标码率为基准，再按报告上限的 80% 封顶；</li>
     *   <li><b>无报告</b>（151 的 media_codecs.xml 只有编解码器名）：使用已验证的保守值，
     *       1080 档首次从 12Mbps 开始，卡顿时逐级降码率
     *       不因无法探测而常驻低档。</li>
     * </ul>
     */
    public static int targetBitrateBps(Capability cap, String resolution) {
        int nominal = nominalBitrateBps(resolution);
        if (nominal <= 0) {
            return 0;
        }
        if (cap == null || !cap.bitrateReported || cap.maxBitrateKbps <= 0) {
            return nominal;
        }
        int reportedCap = cap.maxBitrateKbps * 1000 * 4 / 5;
        int target = Math.min(nominal, reportedCap);
        return Math.max(1000000, target);
    }

    /** 兼容旧调用：无能力快照时按保守路径。 */
    public static int targetBitrateBps(String resolution) {
        return targetBitrateBps(null, resolution);
    }

    /** 各档位的目标码率（bps）：设备有报告时的基准，也是无报告时的保守值。 */
    private static int nominalBitrateBps(String resolution) {
        int h = tierPixelHeight(resolution);
        if (h <= 0) {
            return 0;
        }
        if (h >= 2160) {
            return 12000000;
        }
        if (h >= 1080) {
            return BITRATE_1080_BPS;
        }
        if (h >= 720) {
            return 4000000;
        }
        if (h >= 540) {
            return 2500000;
        }
        if (h >= 480) {
            return 2000000;
        }
        return 1200000;
    }

    /** 兼容旧调用：无 NAS 挡位数据。 */
    public static String pickResolution(Capability cap, int degradeLevel) {
        return pickResolution(cap, degradeLevel, null);
    }

    /**
     * 有 NAS 画质挡位时按服务端列表逐档降级；没有时才按设备能力推导。
     * 无论哪条路径，客户端都不枚举具体挡位表。
     */
    public static String pickResolution(Capability cap, int degradeLevel,
                                        List<String> nasResolutionValues) {
        return pickResolution(cap, degradeLevel, nasResolutionValues, "");
    }

    /**
     * 选请求分辨率：用户显式选了挡位就按该挡位（夹到设备上限内），
     * 否则按「设备能解的最高档」；降级从选定档继续下移。
     */
    public static String pickResolution(Capability cap, int degradeLevel,
                                        List<String> nasResolutionValues,
                                        String requestedResolution) {
        return pickResolution(cap, null, degradeLevel, nasResolutionValues, requestedResolution);
    }

    public static String pickResolution(Capability cap, MediaItem item, int degradeLevel,
                                        List<String> nasResolutionValues,
                                        String requestedResolution) {
        List<String> ladder = new ArrayList<String>();
        if (nasResolutionValues != null) {
            for (int i = 0; i < nasResolutionValues.size(); i++) {
                String v = nasResolutionValues.get(i);
                if (v != null && v.trim().length() > 0) {
                    ladder.add(v.trim());
                }
            }
        }
        String requested = requestedResolution == null ? "" : requestedResolution.trim();
        if (!ladder.isEmpty()) {
            // NAS 的挡位列表是「原片可用画质」降序（如 4k|1080|720|480|360），
            // 首档不一定等于设备能解码的上限；4K 片源直接按首档请求会让
            // 4K H.264 送到只有 1080P 解码能力的电视上，表现为 error(1,-5003)。
            int start = 0;
            int maxH = cap == null ? 0 : cap.maxHeight;
            if (item != null && item.height > 0) {
                maxH = maxH > 0 ? Math.min(maxH, item.height) : item.height;
            }
            if (maxH > 0) {
                int found = -1;
                for (int i = 0; i < ladder.size(); i++) {
                    int h = tierPixelHeight(ladder.get(i));
                    if (h > 0 && h <= maxH) {
                        found = i;
                        break;
                    }
                }
                // 所有挡位都高于设备上限时取最后一档（列表按高度降序，即最低）。
                start = found >= 0 ? found : ladder.size() - 1;
            }
            int base = start;
            if (requested.length() > 0) {
                int pick = ladder.indexOf(requested);
                if (pick >= 0) {
                    base = pick;
                }
            }
            // 用户选的档位若高于设备上限，夹回设备可解范围（从该档往后找）。
            if (maxH > 0) {
                while (base < ladder.size() - 1 && tierPixelHeight(ladder.get(base)) > maxH) {
                    base++;
                }
            }
            int idx = Math.min(ladder.size() - 1, Math.max(0, base + Math.max(0, degradeLevel)));
            return ladder.get(idx);
        }
        int h = cap == null ? 480 : cap.maxHeight;
        if (item != null && item.height > 0) {
            h = h > 0 ? Math.min(h, item.height) : item.height;
        }
        String base;
        if (h >= 2160) base = "4k";
        else if (h >= 1080) base = "1080";
        else if (h >= 720) base = "720";
        else if (h >= 540) base = "540";
        else if (h >= 480) base = "480";
        else base = "360";
        if (requested.length() > 0 && tierPixelHeight(requested) > 0
                && tierPixelHeight(requested) <= h) {
            base = requested;
        }
        if (degradeLevel <= 0) {
            return base;
        }
        // 无 NAS 数据时的设备能力兜底阶梯（不是对外可选挡位表）
        String[] fallbackLadder = {"4k", "1080", "720", "480", "360"};
        int current = 0;
        for (int i = 0; i < fallbackLadder.length; i++) {
            if (fallbackLadder[i].equals(base)) {
                current = i + 1;
                break;
            }
        }
        if (current == 0) {
            current = 2; // 未知档按 1080 起降
        }
        int idx = Math.min(fallbackLadder.length - 1,
                Math.max(0, current - 1 + degradeLevel));
        return fallbackLadder[idx];
    }

    /**
     * NAS 的 "4k"/"8k" 是档位名而不是像素行数（{@link NasLevels#heightOf} 按 4000/8000 排序）。
     * 与设备 maxHeight 比较时要换算成 16:9 的实际行数：4k≈2160、8k≈4320，
     * 否则 2160p 的 4K 电视会被误判为「解不了 4k」而退到 1080p。
     */
    private static int tierPixelHeight(String value) {
        return NasLevels.tierHeightOf(value);
    }

    /** 高清 / 无损音轨：老设备即使名义上有解码器，也常因多声道或高码率打不开。 */
    public static boolean isHdAudio(String codec) {
        return "eac3".equals(codec) || "truehd".equals(codec)
                || "dts".equals(codec) || "flac".equals(codec)
                || "alac".equals(codec) || "pcm".equals(codec);
    }

    /** 把 TS PMT 的 stream_type 映射成内部音频编码短名；非音频返回空串。 */
    public static String audioFromStreamType(int type) {
        switch (type) {
            case 0x03:
            case 0x04:
                return "mp3";
            case 0x0F:
            case 0x11:
                return "aac";
            case 0x81:
                return "ac3";
            case 0x87:
            case 0x84:
                return "eac3";
            case 0x82:
            case 0x06:
                return "dts";
            case 0x83:
                return "truehd";
            default:
                return "";
        }
    }

    private TranscodeProfile() {
    }
}
