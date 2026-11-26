package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.LinkedHashMap;
import java.util.Map;

/** 一个可尝试播放的具体 URL 及其媒体特征。 */
public final class StreamCandidate {

    public String id = "";
    public String url = "";
    public StreamSource source = StreamSource.ORIGINAL;
    public String label = "";
    public String container = "";
    public String videoCodec = "";
    public String audioCodec = "";
    public int audioChannels = 0;
    public String mediaSourceId = "";
    public String protocol = "http";
    public int width = 0;
    public int height = 0;
    public int bitrateKbps = 0;
    public int bitDepth = 8;
    public boolean hdr = false;
    public boolean hardwareDecodable = false;
    public boolean fallbackOnly;
    /** 转码目标参数，仅 NAS_TRANSCODE 使用；会作为 query 参数附加到转码地址。 */
    public Map<String, String> transcodeParams = new LinkedHashMap<String, String>();
    public PlaybackSession session;
    public int score = 0;

    public static StreamCandidate original(String url, MediaItem item) {
        StreamCandidate s = new StreamCandidate();
        s.id = "original:" + item.guid;
        s.url = url;
        s.source = StreamSource.ORIGINAL;
        s.label = "原始流 · " + item.title;
        s.container = item.container;
        s.videoCodec = item.videoCodec;
        s.audioCodec = item.audioCodec;
        s.audioChannels = item.audioChannels;
        s.mediaSourceId = item.mediaGuid;
        s.width = item.width;
        s.height = item.height;
        s.bitrateKbps = item.bitrateKbps;
        s.bitDepth = item.bitDepth;
        s.hdr = item.hdr;
        s.protocol = "http";
        return s;
    }

    public static StreamCandidate transcode(String url, Capability cap, String videoCodec, String audioCodec,
                                            String container, int width, int height) {
        StreamCandidate s = new StreamCandidate();
        s.id = "transcode:" + videoCodec + ":" + width;
        s.url = url;
        s.source = StreamSource.NAS_TRANSCODE;
        s.label = "NAS 解码流 · " + videoCodec.toUpperCase() + " " + height + "p";
        s.container = container;
        s.videoCodec = videoCodec;
        s.audioCodec = audioCodec;
        s.audioChannels = cap.maxAudioChannels;
        s.protocol = "hls";
        s.width = width;
        s.height = height;
        s.bitDepth = 8;
        s.hdr = false;
        s.transcodeParams.put("vcodec", videoCodec);
        s.transcodeParams.put("acodec", audioCodec);
        s.transcodeParams.put("container", container);
        s.transcodeParams.put("maxWidth", String.valueOf(width));
        s.transcodeParams.put("maxHeight", String.valueOf(height));
        s.transcodeParams.put("maxBitrate", String.valueOf(cap.maxBitrateKbps));
        s.transcodeParams.put("pix_fmt", "yuv420p");
        return s;
    }

    public StreamCandidate copy() {
        StreamCandidate s = new StreamCandidate();
        s.id = id;
        s.url = url;
        s.source = source;
        s.label = label;
        s.container = container;
        s.videoCodec = videoCodec;
        s.audioCodec = audioCodec;
        s.audioChannels = audioChannels;
        s.mediaSourceId = mediaSourceId;
        s.protocol = protocol;
        s.width = width;
        s.height = height;
        s.bitrateKbps = bitrateKbps;
        s.bitDepth = bitDepth;
        s.hdr = hdr;
        s.hardwareDecodable = hardwareDecodable;
        s.fallbackOnly = fallbackOnly;
        s.transcodeParams.putAll(transcodeParams);
        s.session = session;
        s.score = score;
        return s;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("url", url);
        m.put("source", source.name());
        m.put("label", label);
        m.put("container", container);
        m.put("videoCodec", videoCodec);
        m.put("audioCodec", audioCodec);
        m.put("audioChannels", Integer.valueOf(audioChannels));
        m.put("mediaSourceId", mediaSourceId);
        m.put("protocol", protocol);
        m.put("width", Integer.valueOf(width));
        m.put("height", Integer.valueOf(height));
        m.put("bitrateKbps", Integer.valueOf(bitrateKbps));
        m.put("bitDepth", Integer.valueOf(bitDepth));
        m.put("hdr", Boolean.valueOf(hdr));
        m.put("fallbackOnly", Boolean.valueOf(fallbackOnly));
        m.put("score", Integer.valueOf(score));
        if (session != null) {
            m.put("sessionId", session.id);
            m.put("sessionMediaSourceId", session.mediaSourceId);
            m.put("sessionMediaDir", session.mediaDir);
        }
        return m;
    }

    public static StreamCandidate fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        StreamCandidate s = new StreamCandidate();
        s.id = Json.str(m, "id", "");
        s.url = Json.str(m, "url", "");
        try {
            s.source = StreamSource.valueOf(Json.str(m, "source", "ORIGINAL"));
        } catch (IllegalArgumentException ex) {
            s.source = StreamSource.ORIGINAL;
        }
        s.label = Json.str(m, "label", "");
        s.container = Json.str(m, "container", "");
        s.videoCodec = Json.str(m, "videoCodec", "");
        s.audioCodec = Json.str(m, "audioCodec", "");
        s.audioChannels = Json.integer(m, "audioChannels", 0);
        s.mediaSourceId = Json.str(m, "mediaSourceId", "");
        s.protocol = Json.str(m, "protocol", "http");
        s.width = Json.integer(m, "width", 0);
        s.height = Json.integer(m, "height", 0);
        s.bitrateKbps = Json.integer(m, "bitrateKbps", 0);
        s.bitDepth = Json.integer(m, "bitDepth", 8);
        s.hdr = Json.bool(m, "hdr", false);
        s.fallbackOnly = Json.bool(m, "fallbackOnly", false);
        s.score = Json.integer(m, "score", 0);
        String sessionId = Json.str(m, "sessionId", "");
        String sessionDir = Json.str(m, "sessionMediaDir", "");
        if (s.url.length() > 0 && (sessionId.length() > 0 || sessionDir.length() > 0)) {
            s.session = new PlaybackSession(s.url, sessionId,
                    Json.str(m, "sessionMediaSourceId", ""), sessionDir);
        }
        return s;
    }
}
