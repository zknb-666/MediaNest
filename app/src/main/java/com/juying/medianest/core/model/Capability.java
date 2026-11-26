package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首次启动检测出来的设备播放能力快照。
 * 检测结果会被序列化保存，之后启动直接读取，不再重复探测。
 */
public final class Capability {

    public static final int SCHEMA = 1;

    public int schemaVersion = SCHEMA;
    public int sdkInt = 1;
    public String device = "";
    public String model = "";
    public String manufacturer = "";
    public String brand = "";

    /** 可用播放方式：MEDIA_PLAYER / VIDEO_VIEW / MEDIA_CODEC / EXO_PLAYER / EXTERNAL_INTENT / SOFTWARE_DECODE */
    public Set<String> playbackMethods = new LinkedHashSet<String>();
    /** 可用传输协议：http / https / hls / dash / rtsp */
    public Set<String> protocols = new LinkedHashSet<String>();
    /** 支持的容器：mp4 / mkv / ts / avi / flv / hls ... */
    public Set<String> containers = new LinkedHashSet<String>();
    /** 支持的视频编码短名：h264 / hevc / vp9 / av1 ... */
    public Set<String> videoCodecs = new LinkedHashSet<String>();
    /** 支持的音频编码短名：aac / mp3 / flac / ac3 ... */
    public Set<String> audioCodecs = new LinkedHashSet<String>();

    public List<DecoderInfo> decoders = new ArrayList<DecoderInfo>();
    public int maxWidth = 640;
    public int maxHeight = 480;
    public int maxFps = 30;
    public int maxBitrateKbps = 2000;
    public int maxBitDepth = 8;
    /**
     * maxWidth/maxHeight/maxVideoProfile/maxVideoLevel 是否来自解码器的真实报告
     * （MediaCodecList 的 VideoCapabilities，或 media_codecs.xml 的 Limit）。
     * false 表示只是平台版本基线，选流时必须走已验证的保守上限。
     */
    public boolean videoLimitReported = false;
    /**
     * maxBitrateKbps 是否来自解码器的真实报告。只有它为 true 时，
     * 转码码率才能按报告上限的比例封顶；否则沿用 1080 档 8Mbps 的保守值。
     */
    public boolean bitrateReported = false;
    /** 解码器报告的最高 H.264 profile（baseline/main/high），空串表示未报告。 */
    public String maxVideoProfile = "";
    /** 解码器报告的最高 H.264 level×10（如 40/51），0 表示未报告。 */
    public int maxVideoLevel = 0;
    /**
     * 音频输出/解码可接受的声道数上限。真机（API 17）实测：设备虽然有
     * OMX.MS.AAC.Decoder，但 5.1ch AAC 会 audio decoder open fail(8207)，
     * 只有 downmix 到 2ch 的 AC3 / MP3 能打开 AudioSink。老设备一律按 2 处理。
     */
    public int maxAudioChannels = 2;
    public boolean hdr = false;
    public boolean tls12 = false;
    public boolean hasExternalPlayer = false;
    public boolean hasSoftwareDecoder = false;
    /**
     * framework MediaCodec 是否可用（`media.codec` 服务存在且枚举成功）。
     * 真机 151（MStar 4.2.1）只有 `media.player`，没有 `media.codec`，因此
     * ExoPlayer 在该机上也拿不到解码器，只能由 MediaPlayer 走厂商 OMX。
     */
    public boolean hasMediaCodec = false;
    /** 解码能力的来源：api（MediaCodecList）/ xml（media_codecs.xml）/ heuristic（版本启发式）。 */
    public String codecSource = "heuristic";
    /** 原流优先：只要能力匹配就直接拉原始流，不经过 NAS 转码。 */
    public boolean preferOriginal = true;
    public boolean detectionOk = false;
    public String probeNotes = "";
    public long detectedAt = 0L;

    public static final class DecoderInfo {
        public String name = "";
        public String mime = "";
        public boolean hardware = false;
        public int maxWidth = 0;
        public int maxHeight = 0;

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("mime", mime);
            m.put("hardware", Boolean.valueOf(hardware));
            m.put("maxWidth", Integer.valueOf(maxWidth));
            m.put("maxHeight", Integer.valueOf(maxHeight));
            return m;
        }

        public static DecoderInfo fromJson(Object o) {
            Map<String, Object> m = Json.map(o);
            DecoderInfo d = new DecoderInfo();
            d.name = Json.str(m, "name", "");
            d.mime = Json.str(m, "mime", "");
            d.hardware = Json.bool(m, "hardware", false);
            d.maxWidth = Json.integer(m, "maxWidth", 0);
            d.maxHeight = Json.integer(m, "maxHeight", 0);
            return d;
        }
    }

    /** 探测完全失败时的保守基线：H.264 Baseline + AAC + MP4，480p。 */
    public static Capability baseline() {
        Capability c = new Capability();
        c.sdkInt = 1;
        c.playbackMethods.add("MEDIA_PLAYER");
        c.playbackMethods.add("VIDEO_VIEW");
        c.protocols.add("http");
        c.containers.add("mp4");
        c.containers.add("3gp");
        c.videoCodecs.add("h264");
        c.videoCodecs.add("mpeg4");
        c.videoCodecs.add("h263");
        c.audioCodecs.add("aac");
        c.audioCodecs.add("mp3");
        c.maxWidth = 640;
        c.maxHeight = 480;
        c.maxFps = 30;
        c.maxBitrateKbps = 2000;
        c.maxBitDepth = 8;
        c.maxAudioChannels = 2;
        c.detectionOk = false;
        c.probeNotes = "能力探测不可用，已回退到保守基线";
        return c;
    }

    public boolean hasMethod(String method) {
        return playbackMethods.contains(method);
    }

    public String summary() {
        return "API " + sdkInt + " · " + maxWidth + "x" + maxHeight +
                " · " + videoCodecs.size() + " 种视频编码 / " + audioCodecs.size() + " 种音频编码" +
                (detectionOk ? "" : "（基线）");
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("schemaVersion", Integer.valueOf(schemaVersion));
        m.put("sdkInt", Integer.valueOf(sdkInt));
        m.put("device", device);
        m.put("model", model);
        m.put("manufacturer", manufacturer);
        m.put("brand", brand);
        m.put("playbackMethods", Json.toList(playbackMethods));
        m.put("protocols", Json.toList(protocols));
        m.put("containers", Json.toList(containers));
        m.put("videoCodecs", Json.toList(videoCodecs));
        m.put("audioCodecs", Json.toList(audioCodecs));
        List<Object> ds = new ArrayList<Object>();
        for (int i = 0; i < decoders.size(); i++) {
            ds.add(decoders.get(i).toJson());
        }
        m.put("decoders", ds);
        m.put("maxWidth", Integer.valueOf(maxWidth));
        m.put("maxHeight", Integer.valueOf(maxHeight));
        m.put("maxFps", Integer.valueOf(maxFps));
        m.put("maxBitrateKbps", Integer.valueOf(maxBitrateKbps));
        m.put("maxBitDepth", Integer.valueOf(maxBitDepth));
        m.put("videoLimitReported", Boolean.valueOf(videoLimitReported));
        m.put("bitrateReported", Boolean.valueOf(bitrateReported));
        m.put("maxVideoProfile", maxVideoProfile);
        m.put("maxVideoLevel", Integer.valueOf(maxVideoLevel));
        m.put("maxAudioChannels", Integer.valueOf(maxAudioChannels));
        m.put("hdr", Boolean.valueOf(hdr));
        m.put("tls12", Boolean.valueOf(tls12));
        m.put("hasExternalPlayer", Boolean.valueOf(hasExternalPlayer));
        m.put("hasSoftwareDecoder", Boolean.valueOf(hasSoftwareDecoder));
        m.put("hasMediaCodec", Boolean.valueOf(hasMediaCodec));
        m.put("codecSource", codecSource);
        m.put("preferOriginal", Boolean.valueOf(preferOriginal));
        m.put("detectionOk", Boolean.valueOf(detectionOk));
        m.put("probeNotes", probeNotes);
        m.put("detectedAt", Long.valueOf(detectedAt));
        return m;
    }

    public static Capability fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        if (m.isEmpty()) {
            return baseline();
        }
        Capability c = new Capability();
        c.schemaVersion = Json.integer(m, "schemaVersion", SCHEMA);
        c.sdkInt = Json.integer(m, "sdkInt", 1);
        c.device = Json.str(m, "device", "");
        c.model = Json.str(m, "model", "");
        c.manufacturer = Json.str(m, "manufacturer", "");
        c.brand = Json.str(m, "brand", "");
        c.playbackMethods = Json.toStringSet(m.get("playbackMethods"));
        c.protocols = Json.toStringSet(m.get("protocols"));
        c.containers = Json.toStringSet(m.get("containers"));
        c.videoCodecs = Json.toStringSet(m.get("videoCodecs"));
        c.audioCodecs = Json.toStringSet(m.get("audioCodecs"));
        List<Object> ds = Json.list(m.get("decoders"));
        for (int i = 0; i < ds.size(); i++) {
            c.decoders.add(DecoderInfo.fromJson(ds.get(i)));
        }
        c.maxWidth = Json.integer(m, "maxWidth", 640);
        c.maxHeight = Json.integer(m, "maxHeight", 480);
        c.maxFps = Json.integer(m, "maxFps", 30);
        c.maxBitrateKbps = Json.integer(m, "maxBitrateKbps", 2000);
        c.maxBitDepth = Json.integer(m, "maxBitDepth", 8);
        c.videoLimitReported = Json.bool(m, "videoLimitReported", false);
        c.bitrateReported = Json.bool(m, "bitrateReported", false);
        c.maxVideoProfile = Json.str(m, "maxVideoProfile", "");
        c.maxVideoLevel = Json.integer(m, "maxVideoLevel", 0);
        // 老配置文件没有该字段时保持保守值 2，避免误判多声道可播导致无声
        c.maxAudioChannels = Json.integer(m, "maxAudioChannels", 2);
        c.hdr = Json.bool(m, "hdr", false);
        c.tls12 = Json.bool(m, "tls12", false);
        c.hasExternalPlayer = Json.bool(m, "hasExternalPlayer", false);
        c.hasSoftwareDecoder = Json.bool(m, "hasSoftwareDecoder", false);
        c.hasMediaCodec = Json.bool(m, "hasMediaCodec", false);
        c.codecSource = Json.str(m, "codecSource", "heuristic");
        c.preferOriginal = Json.bool(m, "preferOriginal", true);
        c.detectionOk = Json.bool(m, "detectionOk", false);
        c.probeNotes = Json.str(m, "probeNotes", "");
        c.detectedAt = Json.longer(m, "detectedAt", 0L);
        // 老配置缺字段时补上必备项，避免选流永远失败
        if (c.containers.isEmpty()) c.containers.add("mp4");
        if (c.videoCodecs.isEmpty()) c.videoCodecs.add("h264");
        if (c.audioCodecs.isEmpty()) c.audioCodecs.add("aac");
        if (c.protocols.isEmpty()) c.protocols.add("http");
        if (c.playbackMethods.isEmpty()) c.playbackMethods.add("VIDEO_VIEW");
        return c;
    }
}
