package com.juying.medianest.core.policy;

import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.PlaybackPlan;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.StreamSource;
import com.juying.medianest.core.util.CodecNames;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 核心选流逻辑（纯 Java，可在 JVM 上单测）。
 *
 * 规则：
 *  1) 先把 NAS 提供的所有「原始流」逐条与设备能力比对；能力匹配的按分数排序，作为优先候选。
 *  2) 若没有任何原始流能直接播（编码/容器/位深/分辨率/协议不匹配），
 *     则生成一条「NAS 解码后的视频流」候选，目标编码取设备支持的最兼容组合。
 *  3) 再补上本地软解、外部播放器等兜底候选。
 *  4) 一条都给不出时，返回明确的失败原因，交给 UI 提示而不是静默黑屏。
 */
public final class StreamSelector {

    public static final class Match {
        public boolean ok = false;
        public String detail = "";
        public FailureReason reason = FailureReason.NONE;
        public int score = 0;
    }

    private StreamSelector() {
    }

    public static Match match(Capability cap, StreamCandidate s) {
        Match m = new Match();
        List<String> problems = new ArrayList<String>();

        if (cap == null || s == null || s.url == null || s.url.length() == 0) {
            m.detail = "候选流地址为空";
            m.reason = FailureReason.NO_ORIGINAL_STREAM;
            return m;
        }

        // NAS 解码流已经是按设备能力生成的，只需校验协议是否可播
        if (s.source == StreamSource.EXTERNAL_PLAYER) {
            m.ok = cap.hasExternalPlayer;
            if (!m.ok) {
                m.detail = "系统里没有可用的外部播放器";
            }
            m.score = 1;
            return m;
        }
        if (s.source == StreamSource.NAS_TRANSCODE) {
            if (!cap.protocols.contains(s.protocol) && !"http".equals(s.protocol)) {
                m.detail = "设备不支持 " + s.protocol + " 传输协议";
                m.reason = FailureReason.UNSUPPORTED_FORMAT;
                return m;
            }
            m.ok = true;
            m.score = 500;
            return m;
        }

        // ---- 原始流 / 软解流的逐项能力比对 ----
        if (!cap.protocols.contains(s.protocol)) {
            problems.add("协议 " + s.protocol + " 不支持");
        }
        if (s.container.length() > 0 && !cap.containers.contains(s.container)) {
            problems.add("容器 " + s.container.toUpperCase() + " 不支持");
        }
        if (s.videoCodec.length() == 0) {
            problems.add("视频编码未知");
        } else if (!cap.videoCodecs.contains(s.videoCodec)) {
            problems.add("视频编码 " + s.videoCodec.toUpperCase() + " 无解码器");
        }
        if (s.audioCodec.length() == 0) {
            problems.add("音频编码未知");
        } else if (!cap.audioCodecs.contains(s.audioCodec)) {
            problems.add("音频编码 " + s.audioCodec.toUpperCase() + " 不支持");
        }
        if (s.audioChannels > 0 && s.audioChannels > cap.maxAudioChannels) {
            problems.add("音频 " + s.audioChannels + "ch 超过设备上限 " + cap.maxAudioChannels + "ch");
        }
        if (s.bitDepth > cap.maxBitDepth) {
            problems.add(s.bitDepth + "bit 位深超过设备上限 " + cap.maxBitDepth + "bit");
        }
        if (s.hdr && !cap.hdr) {
            problems.add("HDR /杜比视界不支持");
        }
        if (s.width > 0 && s.width > cap.maxWidth) {
            problems.add("宽 " + s.width + " 超过设备上限 " + cap.maxWidth);
        }
        if (s.height > 0 && s.height > cap.maxHeight) {
            problems.add("高 " + s.height + " 超过设备上限 " + cap.maxHeight);
        }

        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < problems.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(problems.get(i));
            }
            m.detail = sb.toString();
            m.reason = FailureReason.UNSUPPORTED_FORMAT;
            return m;
        }

        m.ok = true;
        int score = 1000;
        if (s.hardwareDecodable) score += 120;
        if ("mp4".equals(s.container)) score += 40;   // 拖动进度更快
        if (s.height > 0) score += s.height / 10;
        if (s.bitrateKbps > 0) score += s.bitrateKbps / 2000;
        if (s.source == StreamSource.CLIENT_SOFTWARE) score -= 800;
        m.score = score;
        return m;
    }

    public static PlaybackPlan select(Capability cap, MediaItem item,
                                     List<StreamCandidate> available, boolean nasCanTranscode) {
        PlaybackPlan plan = new PlaybackPlan();
        if (cap == null) {
            cap = Capability.baseline();
        }
        if (item == null) {
            item = new MediaItem();
        }
        if (available == null) {
            available = new ArrayList<StreamCandidate>();
        }

        List<String> rejectNotes = new ArrayList<String>();
        List<StreamCandidate> playableOriginals = new ArrayList<StreamCandidate>();
        List<StreamCandidate> providedTranscode = new ArrayList<StreamCandidate>();

        // 直播频道是第三方 m3u8 直链，没有本地编码元数据，也不允许走 NAS 转码会话
        // （实测 POST /play/play 对 LiveChannel 返回 -6 Not Found）。
        boolean liveStream = !available.isEmpty();
        for (int i = 0; i < available.size(); i++) {
            StreamCandidate s = available.get(i);
            if (s.id == null || !s.id.startsWith("live:")) {
                liveStream = false;
                break;
            }
        }

        for (int i = 0; i < available.size(); i++) {
            StreamCandidate s = available.get(i);
            if (s.source == StreamSource.NAS_TRANSCODE) {
                providedTranscode.add(s);
                continue;
            }
            if (s.source != StreamSource.ORIGINAL) {
                continue;
            }
            if (liveStream) {
                s.score = 1000;
                s.hardwareDecodable = true;
                playableOriginals.add(s);
                continue;
            }
            // NAS 转码服务不可用（1001 System busy）时的直连回退候选。
            // 仍必须做设备能力比对：无脑回退到 4K HEVC 10bit 原片时，
            // 只能解 H.264 1080p 的老电视会「有声无画」整屏黑，
            // 比明确报不可播更难定位（实测 151 / MStar）。
            if (s.fallbackOnly || (s.id != null && s.id.startsWith("direct:"))) {
                s.container = CodecNames.container(s.container);
                s.videoCodec = CodecNames.video(s.videoCodec);
                s.audioCodec = CodecNames.audio(s.audioCodec);
                Match dm = match(cap, s);
                if (dm.ok) {
                    s.score = 900;
                    s.hardwareDecodable = hasHardwareDecoder(cap, s.videoCodec);
                    playableOriginals.add(s);
                    plan.note = "NAS 转码服务不可用，改用 /media/range 直连原始流";
                } else {
                    rejectNotes.add(s.label + "：" + dm.detail);
                }
                continue;
            }
            s.container = CodecNames.container(s.container);
            s.videoCodec = CodecNames.video(s.videoCodec);
            s.audioCodec = CodecNames.audio(s.audioCodec);
            Match m = match(cap, s);
            if (m.ok) {
                s.score = m.score;
                s.hardwareDecodable = hasHardwareDecoder(cap, s.videoCodec);
                playableOriginals.add(s);
            } else {
                rejectNotes.add(s.label + "：" + m.detail);
                // 若设备有软解能力，保留一份「本地软解」候选作为低成本兜底
                if (cap.hasSoftwareDecoder && isSoftwareDecodable(cap, s)) {
                    StreamCandidate soft = s.copy();
                    soft.id = "software:" + s.id;
                    soft.source = StreamSource.CLIENT_SOFTWARE;
                    soft.label = "本地软解 · " + s.label;
                    soft.score = 200;
                    plan.candidates.add(soft);
                }
            }
        }

        Collections.sort(playableOriginals, new Comparator<StreamCandidate>() {
            public int compare(StreamCandidate a, StreamCandidate b) {
                int byHeight = b.height - a.height;
                if (byHeight != 0) return byHeight;
                int byBitrate = b.bitrateKbps - a.bitrateKbps;
                if (byBitrate != 0) return byBitrate;
                return b.score - a.score;
            }
        });

        if (!playableOriginals.isEmpty()) {
            plan.candidates.addAll(0, playableOriginals);
            plan.directPlayable = true;
            plan.note = "已匹配到 " + playableOriginals.size() + " 条与设备能力相符的原始流，优先直连播放";
        } else {
            plan.note = available.isEmpty()
                    ? "NAS 未返回原始视频流"
                    : "原始流与设备能力不匹配：" + join(rejectNotes);
        }

        // ---- 关键回退：没有可直连的原始流 → 立即切换为 NAS 解码后的视频流 ----
        // 即使有可直连的原始流，也把 NAS 解码流排在队尾，
        // 这样运行时解码器突然报错时仍有兜底，不会直接黑屏。
        boolean transcodeFirst = !plan.directPlayable || !cap.preferOriginal;
        if (nasCanTranscode && !liveStream) {
            StreamCandidate template = buildTranscodeCandidate(cap, item);
            List<StreamCandidate> txList = new ArrayList<StreamCandidate>();
            if (providedTranscode.isEmpty()) {
                txList.add(template); // 占位符，由 FnNasApi 解析成真实转码地址
            } else {
                // NAS 已经给出了转码后的画质列表：直接采用，比自己拼 URL 可靠
                for (int i = 0; i < providedTranscode.size(); i++) {
                    StreamCandidate p = providedTranscode.get(i).copy();
                    if (p.transcodeParams.isEmpty()) {
                        p.transcodeParams.putAll(template.transcodeParams);
                    }
                    if (p.videoCodec.length() == 0) p.videoCodec = template.videoCodec;
                    if (p.audioCodec.length() == 0) p.audioCodec = template.audioCodec;
                    if (p.container.length() == 0) p.container = template.container;
                    txList.add(p);
                }
            }
            int insertAt = transcodeFirst ? 0 : plan.candidates.size();
            plan.candidates.addAll(insertAt, txList);
            plan.transcodeUsed = true;
            plan.note += transcodeFirst
                    ? "；已切换为 NAS 解码流（" + txList.get(0).videoCodec.toUpperCase() + " " + txList.get(0).height + "p）"
                    : "；队尾保留 NAS 解码流作为播放失败时的兜底";
        } else if (!plan.directPlayable && plan.candidates.isEmpty()) {
            plan.terminalReason = FailureReason.TRANSCODE_UNAVAILABLE;
            plan.terminalDetail = "NAS 未提供转码能力（server/info 中 gpu_acc / cpu_allow_decoding 均为关闭）";
        }

        // 最后兜底：交给外部播放器
        if (cap.hasExternalPlayer && !plan.candidates.isEmpty()) {
            StreamCandidate first = plan.candidates.get(0);
            StreamCandidate ext = first.copy();
            ext.id = "external:" + first.id;
            ext.source = StreamSource.EXTERNAL_PLAYER;
            ext.label = "调用外部播放器 · " + first.label;
            ext.score = 1;
            plan.candidates.add(ext);
        }

        if (plan.candidates.isEmpty()) {
            if (plan.terminalReason == FailureReason.NONE) {
                plan.terminalReason = FailureReason.UNSUPPORTED_FORMAT;
                plan.terminalDetail = plan.note;
            }
        }
        return plan;
    }

    private static StreamCandidate buildTranscodeCandidate(Capability cap, MediaItem item) {
        String targetVideo = "h264";
        if (!cap.videoCodecs.contains("h264")) {
            if (cap.videoCodecs.contains("mpeg4")) targetVideo = "mpeg4";
            else if (cap.videoCodecs.contains("hevc")) targetVideo = "hevc";
            else if (!cap.videoCodecs.isEmpty()) targetVideo = cap.videoCodecs.iterator().next();
        }
        // 与 TranscodeProfile 共用一套音频目标判定：老电视的 5.1ch AAC 打不开
        // 解码器，优先 AC3 / MP3；现代设备才用 AAC。
        String targetAudio = TranscodeProfile.pickAudioEncoder(cap, 0);
        // 能力探测里有 hls 协议就走 HLS，否则退回渐进式 MP4 over HTTP
        boolean hls = cap.protocols.contains("hls");
        String container = hls ? "ts" : "mp4";
        String protocol = hls ? "hls" : "http";

        int width = item.width > 0 ? Math.min(item.width, cap.maxWidth) : cap.maxWidth;
        if (width <= 0) width = 640;
        int height = item.height > 0 ? Math.min(item.height, cap.maxHeight) : (width * 9 / 16);
        if (height <= 0) height = 360;

        StreamCandidate s = StreamCandidate.transcode("__TRANSCODE_URL__", cap, targetVideo, targetAudio,
                container, width, height);
        s.protocol = protocol;
        return s;
    }

    /** 占位符 URL 由 FnNasApi 用真实转码端点替换（核心层不依赖网络实现）。 */
    public static boolean needsUrlResolution(StreamCandidate s) {
        return "__TRANSCODE_URL__".equals(s.url);
    }

    private static boolean hasHardwareDecoder(Capability cap, String codec) {
        String mime = CodecNames.mime(codec);
        for (int i = 0; i < cap.decoders.size(); i++) {
            Capability.DecoderInfo d = cap.decoders.get(i);
            if (d.hardware && (d.mime.equals(mime) || d.mime.indexOf(codec) >= 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSoftwareDecodable(Capability cap, StreamCandidate s) {
        // 软解只可能救「容器/位深/分辨率」问题，救不了「根本没有该编码解码器」
        return cap.videoCodecs.contains(s.videoCodec) && cap.audioCodecs.contains(s.audioCodec);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() && i < 3; i++) {
            if (i > 0) sb.append("；");
            sb.append(parts.get(i));
        }
        if (parts.size() > 3) sb.append(" 等 ").append(parts.size()).append(" 项");
        return sb.toString();
    }
}
