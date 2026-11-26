package com.juying.medianest.player;

import com.juying.medianest.core.util.L;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.util.CodecNames;
import com.juying.medianest.player.MediaCodecProbe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;

/**
 * 首次启动的设备能力探测。
 *
 * 解码能力按三级来源判定（minSdk 16 起不再为 API 9 做兼容）：
 *  1. MediaCodecList / MediaCodecInfo：有 `media.codec` 服务时最准（181）；
 *  2. 解析 `/vendor/etc/media_codecs.xml`、`/system/etc/media_codecs.xml`：
 *     151 这类无 `media.codec` 的设备，厂商 OMX 解码器只写在该文件里；
 *  3. 版本启发式：前两者都拿不到时的兜底。
 * 任何一步抛异常都只记日志，绝不阻塞进入主界面。
 */
public final class DeviceProbe {

    private DeviceProbe() {
    }

    public static Capability probe(Context context) {
        Capability c = Capability.baseline();
        List<String> notes = new ArrayList<String>();
        c.detectedAt = System.currentTimeMillis();
        c.sdkInt = Sdk.level();
        c.device = Build.DEVICE;
        c.model = Build.MODEL;
        c.brand = Build.BRAND;
        c.manufacturer = staticStringField(Build.class, "MANUFACTURER", Build.BRAND);

        // ---- 屏幕尺寸 ----
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            notes.add("屏幕 " + dm.widthPixels + "x" + dm.heightPixels);
        } catch (Throwable t) {
            notes.add("屏幕信息不可用");
        }

        // ---- 保底播放方式 ----
        c.playbackMethods.add("MEDIA_PLAYER");
        c.playbackMethods.add("VIDEO_VIEW");
        c.playbackMethods.add("EXTERNAL_INTENT");

        // ---- 外部播放器是否存在 ----
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("http://127.0.0.1/probe.mp4"), "video/mp4");
            if (context.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                c.hasExternalPlayer = true;
                notes.add("检测到系统/第三方播放器");
            }
        } catch (Throwable t) {
            notes.add("外部播放器查询失败");
        }

        // ---- TLS 1.2（老设备默认只支持 TLS 1.0）----
        try {
            SSLContext.getInstance("TLSv1.2");
            c.tls12 = true;
        } catch (Throwable t) {
            c.tls12 = false;
            notes.add("TLS 1.2 不可用，HTTPS 连接可能失败");
        }

        // ---- 按系统版本给出容器 / 协议 / 分辨率基线 ----
        applySdkHeuristics(c, notes);

        // ---- 解码能力：MediaCodecList → media_codecs.xml → 版本启发式 ----
        boolean apiOk = false;
        if (MediaCodecProbe.probeNow().available) {
            apiOk = enumerateDecoders(c, notes);
        } else {
            // 部分 OEM ROM 即使没有 media.codec 服务也会列出 OMX 解码器，但查询
            // getCapabilitiesForType() 会卡在厂商 Binder 实现里；此时只读 XML。
            notes.add("MediaCodec 框架不可用，跳过 MediaCodecList 查询");
        }
        if (apiOk) {
            c.hasMediaCodec = true;
            c.codecSource = "api";
            c.detectionOk = true;
        } else {
            // 151 这类设备没有 media.codec 服务，MediaCodecList 为空，
            // 但厂商 OMX 解码器仍写在 media_codecs.xml 里。
            boolean xmlOk = parseMediaCodecsXml(c, notes);
            if (xmlOk) {
                c.hasMediaCodec = false;
                c.codecSource = "xml";
                c.detectionOk = true;
            } else {
                c.hasMediaCodec = false;
                c.codecSource = "heuristic";
                c.detectionOk = false;
                // 没有解码器清单时才用版本启发式补 AC3，
                // 避免把「有解码器」误报给无该能力的设备
                c.audioCodecs.add("ac3");
                notes.add("MediaCodecList 与 media_codecs.xml 均不可用，使用版本启发式能力表");
            }
        }
        applyAudioChannelLimit(c);

        c.hasSoftwareDecoder = c.sdkInt >= 21;
        if (c.hasSoftwareDecoder) {
            c.playbackMethods.add("SOFTWARE_DECODE");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append("；");
            sb.append(notes.get(i));
        }
        c.probeNotes = sb.toString();
        L.i("DeviceProbe", "探测完成 " + c.summary() + " [" + c.probeNotes + "]");
        return c;
    }

    /** 检测线程异常卡死时使用的保守能力，不调用任何厂商媒体服务。 */
    public static Capability fallback() {
        Capability c = Capability.baseline();
        List<String> notes = new ArrayList<String>();
        c.sdkInt = Sdk.level();
        c.device = Build.DEVICE;
        c.model = Build.MODEL;
        c.brand = Build.BRAND;
        c.manufacturer = staticStringField(Build.class, "MANUFACTURER", Build.BRAND);
        c.detectedAt = System.currentTimeMillis();
        applySdkHeuristics(c, notes);
        applyAudioChannelLimit(c);
        c.hasSoftwareDecoder = c.sdkInt >= 21;
        if (c.hasSoftwareDecoder) {
            c.playbackMethods.add("SOFTWARE_DECODE");
        }
        c.probeNotes = "设备检测超时，已回退到保守能力基线";
        return c;
    }

    private static void applySdkHeuristics(Capability c, List<String> notes) {
        int sdk = c.sdkInt;
        c.protocols.clear();
        c.protocols.add("http");
        c.protocols.add("https");
        c.protocols.add("hls");
        c.protocols.add("rtsp");
        c.containers.add("ts");
        c.videoCodecs.add("h264");

        if (sdk >= 21) {
            c.containers.add("mkv");
            c.containers.add("webm");
            c.videoCodecs.add("hevc");
            c.videoCodecs.add("vp8");
            c.videoCodecs.add("vp9");
            c.audioCodecs.add("opus");
            c.audioCodecs.add("flac");
            c.audioCodecs.add("eac3");
            c.maxWidth = 3840;
            c.maxHeight = 2160;
            c.maxFps = 60;
            c.maxBitrateKbps = 120000;
            notes.add("API21+ 支持 HEVC/VP9/MKV/FLAC");
        } else {
            c.maxWidth = 1920;
            c.maxHeight = 1080;
            c.maxFps = 30;
            c.maxBitrateKbps = 20000;
            notes.add("API16+ 支持 1080p H.264");
        }
        if (sdk >= 29) {
            c.videoCodecs.add("av1");
        }
        if (sdk >= 24) {
            c.hdr = c.videoCodecs.contains("hevc");
        }
    }

    /**
     * 声道上限：真机（API 17 / TCL）虽然枚举到 OMX.MS.AAC.Decoder，但 5.1ch AAC
     * 会报 audio decoder open fail(8207)，只有 downmix 到 2ch 的 AC3 / MP3 能出声。
     * 因此 API<21 一律按立体声处理；新系统且确实有 AC3/EAC3 解码器才放宽到 6。
     */
    private static void applyAudioChannelLimit(Capability c) {
        boolean hasSurroundDecoder = false;
        for (int i = 0; i < c.decoders.size(); i++) {
            String mime = c.decoders.get(i).mime;
            if (mime.indexOf("eac3") >= 0 || mime.indexOf("ac3") >= 0
                    || mime.indexOf("dts") >= 0) {
                hasSurroundDecoder = true;
                break;
            }
        }
        c.maxAudioChannels = (c.sdkInt >= 21 && hasSurroundDecoder) ? 6 : 2;
    }

    private static boolean enumerateDecoders(Capability c, List<String> notes) {
        try {
            // minSdk 16：MediaCodecList / MediaCodecInfo 都是公开 API，直接调用。
            // 调用前已由 MediaCodecProbe 确认框架服务可用；老 OEM ROM 不得进入这里。
            int count = android.media.MediaCodecList.getCodecCount();
            int decoders = 0;
            boolean sawHevc10 = false;
            int maxW = 0;
            int maxH = 0;
            int maxAvcBitrateBps = 0;
            int maxAvcLevel = 0;
            String maxAvcProfile = "";

            for (int i = 0; i < count; i++) {
                android.media.MediaCodecInfo info =
                        android.media.MediaCodecList.getCodecInfoAt(i);
                if (info == null || info.isEncoder()) {
                    continue;
                }
                String name = info.getName();
                boolean hardware = looksHardware(name);
                if (Build.VERSION.SDK_INT >= 29) {
                    hardware = info.isHardwareAccelerated();
                }

                String[] types;
                try {
                    types = info.getSupportedTypes();
                } catch (Throwable t) {
                    continue;
                }
                for (int j = 0; j < types.length; j++) {
                    String mime = types[j].toLowerCase(Locale.US);
                    Capability.DecoderInfo d = new Capability.DecoderInfo();
                    d.name = name;
                    d.mime = mime;
                    d.hardware = hardware;
                    // API 21+ 的 VideoCapabilities 给出该 MIME 的最大宽高
                    int[] size = videoCapSize(info, types[j]);
                    if (size != null) {
                        d.maxWidth = size[0];
                        d.maxHeight = size[1];
                        if (size[0] > maxW) maxW = size[0];
                        if (size[1] > maxH) maxH = size[1];
                    }
                    // H.264 硬解额外读取码率上限与 profile/level（VideoCapabilities 是 API 21+）
                    if ("video/avc".equals(mime) && hardware) {
                        int[] avc = avcCapability(info, types[j]);
                        if (avc != null) {
                            if (avc[0] > maxAvcBitrateBps) maxAvcBitrateBps = avc[0];
                            if (avc[1] > maxAvcLevel) {
                                maxAvcLevel = avc[1];
                                maxAvcProfile = profileName(avc[2]);
                            }
                        }
                    }
                    c.decoders.add(d);
                    decoders++;

                    if (mime.startsWith("video/")) {
                        String codec = CodecNames.video(mime);
                        if (codec.length() > 0 && !"unknown".equals(codec)) {
                            c.videoCodecs.add(codec);
                            if ("hevc".equals(codec) && hardware && c.sdkInt >= 21) {
                                sawHevc10 = true;
                            }
                        }
                    } else if (mime.startsWith("audio/")) {
                        String codec = CodecNames.audio(mime);
                        if (codec.length() > 0 && !"unknown".equals(codec)) {
                            c.audioCodecs.add(codec);
                        }
                    }
                }
            }
            if (sawHevc10) {
                c.maxBitDepth = 10;
                c.hdr = c.sdkInt >= 24;
            }
            // 框架服务可用但枚举结果为 0 时，继续尝试厂商 media_codecs.xml。
            // VideoCapabilities 是权威值：报告多少就按多少，不能被 API 版本启发式抬高。
            if (maxW > 0 && maxH > 0) {
                c.maxWidth = maxW;
                c.maxHeight = maxH;
                c.videoLimitReported = true;
            }
            if (maxAvcBitrateBps > 0) {
                c.maxBitrateKbps = maxAvcBitrateBps / 1000;
                c.bitrateReported = true;
            }
            if (maxAvcLevel > 0) {
                c.maxVideoLevel = maxAvcLevel;
                c.videoLimitReported = true;
            }
            if (maxAvcProfile.length() > 0) {
                c.maxVideoProfile = maxAvcProfile;
            }
            if (decoders > 0) {
                c.playbackMethods.add("MEDIA_CODEC");
            }
            notes.add("MediaCodecList 枚举 " + decoders + " 个解码器"
                    + (maxW > 0 ? "，上限 " + maxW + "x" + maxH : "")
                    + (maxAvcBitrateBps > 0 ? "，H.264 码率上限 " + (maxAvcBitrateBps / 1000) + "kbps" : "")
                    + (maxAvcLevel > 0 ? "，最高 level " + (maxAvcLevel / 10.0f) : ""));
            return decoders > 0;
        } catch (Throwable t) {
            L.w("DeviceProbe", "MediaCodecList 不可用: " + t.getClass().getName());
            return false;
        }
    }

    /** 反射读取 MediaCodecInfo 对该 MIME 的最大宽高（VideoCapabilities 是 API 21+）。 */
    private static int[] videoCapSize(Object info, String type) {
        try {
            Object caps = info.getClass().getMethod("getCapabilitiesForType", String.class)
                    .invoke(info, type);
            if (caps == null) {
                return null;
            }
            Object video = caps.getClass().getMethod("getVideoCapabilities").invoke(caps);
            if (video == null) {
                return null;
            }
            Object wr = video.getClass().getMethod("getSupportedWidths").invoke(video);
            Object hr = video.getClass().getMethod("getSupportedHeights").invoke(video);
            if (wr == null || hr == null) {
                return null;
            }
            int w = ((Integer) wr.getClass().getMethod("getUpper").invoke(wr)).intValue();
            int h = ((Integer) hr.getClass().getMethod("getUpper").invoke(hr)).intValue();
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new int[]{w, h};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 反射读取 H.264 硬解的码率上限与最高 profile/level（VideoCapabilities 是 API 21+）。
     * 返回 {bitrateBps, level, profileCode}；系统不报告时返回 null。
     */
    private static int[] avcCapability(Object info, String type) {
        try {
            Object caps = info.getClass().getMethod("getCapabilitiesForType", String.class)
                    .invoke(info, type);
            if (caps == null) {
                return null;
            }
            int bitrate = 0;
            Object video = caps.getClass().getMethod("getVideoCapabilities").invoke(caps);
            if (video != null) {
                Object range = video.getClass().getMethod("getBitrateRange").invoke(video);
                if (range != null) {
                    Object upper = range.getClass().getMethod("getUpper").invoke(range);
                    if (upper instanceof Integer) {
                        bitrate = ((Integer) upper).intValue();
                    }
                }
            }
            int level = 0;
            int profile = 0;
            try {
                // CodecCapabilities.profileLevels 是公开字段，CodecProfileLevel 也是公开字段。
                Object[] pls = (Object[]) caps.getClass().getField("profileLevels").get(caps);
                if (pls != null) {
                    for (int i = 0; i < pls.length; i++) {
                        if (pls[i] == null) {
                            continue;
                        }
                        int p = ((Integer) pls[i].getClass().getField("profile")
                                .get(pls[i])).intValue();
                        int l = ((Integer) pls[i].getClass().getField("level")
                                .get(pls[i])).intValue();
                        // 1..128 是 AVC 的基线/主/扩展/High 系列。
                        if (p >= 1 && p <= 128 && l > level) {
                            level = l;
                            profile = p;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (bitrate <= 0 && level <= 0) {
                return null;
            }
            return new int[]{bitrate, level, profile};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String profileName(int code) {
        switch (code) {
            case 1:
            case 65536:
                return "baseline";
            case 2:
                return "main";
            case 4:
                return "extended";
            case 8:
            case 524288:
                return "high";
            case 16:
                return "high10";
            case 32:
                return "high422";
            case 64:
                return "high444";
            default:
                return "";
        }
    }

    /**
     * 解析厂商 media_codecs.xml。
     * 151（MStar 4.2.1）没有 `media.codec` 服务，MediaCodecList 枚举不到任何解码器，
     * 但 OMX.MS.* 解码器都登记在 `/system/etc/media_codecs.xml` 里，这是该机唯一的
     * 可靠能力来源。只读 `<Decoders>` 段，避免把同名编码器算进来。
     */
    private static boolean parseMediaCodecsXml(Capability c, List<String> notes) {
        String[] paths = {"/vendor/etc/media_codecs.xml", "/system/etc/media_codecs.xml"};
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        int found = 0;
        for (int i = 0; i < paths.length; i++) {
            if (!new java.io.File(paths[i]).canRead()) {
                continue;
            }
            try {
                found += parseOneMediaCodecsXml(paths[i], c, seen);
            } catch (Throwable t) {
                notes.add("解析 " + paths[i] + " 失败: " + t.getClass().getSimpleName());
            }
        }
        if (found > 0) {
            notes.add("media_codecs.xml 解析 " + found + " 个解码器（framework MediaCodec 不可用）");
            if (!c.videoLimitReported) {
                notes.add("media_codecs.xml 未声明 size/bitrate 上限，码率按保守值");
            }
            return true;
        }
        return false;
    }

    private static int parseOneMediaCodecsXml(String path, Capability c,
                                              java.util.Set<String> seen) throws Exception {
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        java.io.FileInputStream in = new java.io.FileInputStream(path);
        int decoders = 0;
        try {
            p.setInput(in, "UTF-8");
            int event = p.getEventType();
            boolean inDecoders = false;
            Capability.DecoderInfo current = null;
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("Decoders".equals(tag)) {
                        inDecoders = true;
                    } else if (inDecoders && "MediaCodec".equals(tag)) {
                        String name = attr(p, "name");
                        String type = attr(p, "type");
                        current = new Capability.DecoderInfo();
                        current.name = name;
                        current.hardware = looksHardware(name);
                        if (type.length() > 0) {
                            current.mime = type.toLowerCase(Locale.US);
                            decoders += addDecoder(c, current, seen);
                        }
                    } else if (inDecoders && "Type".equals(tag) && current != null) {
                        // 新格式：<MediaCodec name=...><Type name="video/avc"/></MediaCodec>
                        String type = attr(p, "name");
                        if (type.length() > 0) {
                            Capability.DecoderInfo d = new Capability.DecoderInfo();
                            d.name = current.name;
                            d.hardware = current.hardware;
                            d.mime = type.toLowerCase(Locale.US);
                            decoders += addDecoder(c, d, seen);
                        }
                    } else if (inDecoders && "Limit".equals(tag)
                            && current != null && current.mime.startsWith("video/")) {
                        // 只认视频解码器自己的 Limit；音频码率上限（如 640kbps）不能当视频上限。
                        String limit = attr(p, "name");
                        if ("size".equals(limit)) {
                            applySizeLimit(c, attr(p, "max"));
                        } else if ("bitrate".equals(limit)) {
                            applyBitrateLimit(c, attr(p, "max"));
                        }
                    }
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    String tag = p.getName();
                    if ("Decoders".equals(tag)) {
                        inDecoders = false;
                    } else if ("MediaCodec".equals(tag)) {
                        current = null;
                    }
                }
                event = p.next();
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return decoders;
    }

    private static int addDecoder(Capability c, Capability.DecoderInfo d,
                                  java.util.Set<String> seen) {
        if (!seen.add(d.name + "|" + d.mime)) {
            return 0;
        }
        c.decoders.add(d);
        if (d.mime.startsWith("video/")) {
            String codec = CodecNames.video(d.mime);
            if (codec.length() > 0 && !"unknown".equals(codec)) {
                c.videoCodecs.add(codec);
            }
        } else if (d.mime.startsWith("audio/")) {
            String codec = CodecNames.audio(d.mime);
            if (codec.length() > 0 && !"unknown".equals(codec)) {
                c.audioCodecs.add(codec);
            }
        }
        return 1;
    }

    private static void applySizeLimit(Capability c, String max) {
        int x = max.indexOf('x');
        if (x <= 0) {
            return;
        }
        try {
            int w = Integer.parseInt(max.substring(0, x).trim());
            int h = Integer.parseInt(max.substring(x + 1).trim());
            c.videoLimitReported = true;
            if (w > c.maxWidth) {
                c.maxWidth = w;
            }
            if (h > c.maxHeight) {
                c.maxHeight = h;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** media_codecs.xml 的 bitrate Limit 单位是 kbps，只有真实声明时才标记为已报告。 */
    private static void applyBitrateLimit(Capability c, String max) {
        try {
            int kbps = Integer.parseInt(max.trim());
            if (kbps <= 0) {
                return;
            }
            c.maxBitrateKbps = kbps;
            c.bitrateReported = true;
        } catch (NumberFormatException ignored) {
        }
    }

    private static String attr(org.xmlpull.v1.XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? "" : v.trim();
    }

    private static boolean looksHardware(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        if (n.indexOf("google") >= 0 || n.indexOf("software") >= 0 ||
                n.indexOf("ffmpeg") >= 0 || n.indexOf("sw") == 0) {
            return false;
        }
        return n.indexOf("omx.") >= 0 || n.indexOf("c2.") >= 0 || n.indexOf("qcom") >= 0 ||
                n.indexOf("mtk") >= 0 || n.indexOf("exynos") >= 0 || n.indexOf("nvidia") >= 0 ||
                n.indexOf("intel") >= 0 || n.indexOf("amlogic") >= 0 || n.indexOf("hisilicon") >= 0;
    }

    private static String staticStringField(Class<?> cls, String field, String def) {
        try {
            Field f = cls.getField(field);
            Object v = f.get(null);
            return v == null ? def : String.valueOf(v);
        } catch (Throwable t) {
            return def;
        }
    }
}
