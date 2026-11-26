package com.juying.medianest.core.model;

/** Provider 无关的转码与起播请求。 */
public final class PlaybackRequest {
    public int width = 1920;
    public int height = 1080;
    public int bitrateBps;
    public String videoCodec = "h264";
    public String audioCodec = "ac3";
    public int audioChannels = 2;
    public boolean transcodeAudio = true;
    public String container = "ts";
    public String protocol = "hls";
    public int segmentLengthSeconds = 3;
    public long startPositionMs;
    public int playlistWindow;
    public boolean fnosLongSegments = true;
    public boolean forceTranscode;

    public PlaybackRequest copy() {
        PlaybackRequest r = new PlaybackRequest();
        r.width = width;
        r.height = height;
        r.bitrateBps = bitrateBps;
        r.videoCodec = videoCodec;
        r.audioCodec = audioCodec;
        r.audioChannels = audioChannels;
        r.transcodeAudio = transcodeAudio;
        r.container = container;
        r.protocol = protocol;
        r.segmentLengthSeconds = segmentLengthSeconds;
        r.startPositionMs = startPositionMs;
        r.playlistWindow = playlistWindow;
        r.fnosLongSegments = fnosLongSegments;
        r.forceTranscode = forceTranscode;
        return r;
    }

    public String resolution() {
        if (height >= 2160) return "4k";
        if (height >= 1080) return "1080";
        if (height >= 720) return "720";
        if (height >= 540) return "540";
        if (height >= 480) return "480";
        return "360";
    }

    public static int targetWidth(String resolution, int fallback) {
        if ("4k".equalsIgnoreCase(resolution)) return 3840;
        if ("1080".equalsIgnoreCase(resolution) || "1080p".equalsIgnoreCase(resolution)) return 1920;
        if ("720".equalsIgnoreCase(resolution) || "720p".equalsIgnoreCase(resolution)) return 1280;
        if ("540".equalsIgnoreCase(resolution) || "540p".equalsIgnoreCase(resolution)) return 960;
        if ("480".equalsIgnoreCase(resolution) || "480p".equalsIgnoreCase(resolution)) return 854;
        if ("360".equalsIgnoreCase(resolution) || "360p".equalsIgnoreCase(resolution)) return 640;
        return fallback > 0 ? fallback : 1920;
    }

    public static int targetHeight(String resolution, int fallback) {
        if ("4k".equalsIgnoreCase(resolution)) return 2160;
        if ("1080".equalsIgnoreCase(resolution) || "1080p".equalsIgnoreCase(resolution)) return 1080;
        if ("720".equalsIgnoreCase(resolution) || "720p".equalsIgnoreCase(resolution)) return 720;
        if ("540".equalsIgnoreCase(resolution) || "540p".equalsIgnoreCase(resolution)) return 540;
        if ("480".equalsIgnoreCase(resolution) || "480p".equalsIgnoreCase(resolution)) return 480;
        if ("360".equalsIgnoreCase(resolution) || "360p".equalsIgnoreCase(resolution)) return 360;
        return fallback > 0 ? fallback : 1080;
    }

    public static int segmentLengthFor(MediaItem item) {
        int seconds = item == null ? 0 : Math.max(0, item.runtimeMinutes) * 60;
        if (seconds <= 0) return 3;
        return Math.max(3, (seconds + 1499) / 1500);
    }

    /** 旧 Provider 适配桥；返回副本，不修改账号配置。 */
    public ServerConfig applyTo(ServerConfig source) {
        ServerConfig cfg = source.copy();
        cfg.forceTranscode = forceTranscode;
        cfg.transcodeResolution = resolution();
        cfg.transcodeBitrateBps = bitrateBps;
        cfg.transcodeVideoEncoder = videoCodec;
        cfg.transcodeAudioEncoder = transcodeAudio ? audioCodec : "";
        cfg.transcodeAudioChannels = audioChannels;
        return cfg;
    }
}
