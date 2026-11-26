package com.juying.medianest.core.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 把 ffprobe / MediaCodec / 文件扩展名里的各种别名收敛成内部统一的短名。 */
public final class CodecNames {

    private static final Map<String, String> VIDEO = new HashMap<String, String>();
    private static final Map<String, String> AUDIO = new HashMap<String, String>();
    private static final Map<String, String> CONTAINER = new HashMap<String, String>();

    static {
        put(VIDEO, "h264", "h264", "avc", "avc1", "x264", "mpeg4_avc", "h.264");
        put(VIDEO, "hevc", "hevc", "h265", "x265", "hvc1", "hev1", "h.265");
        put(VIDEO, "vp8", "vp8", "vp08");
        put(VIDEO, "vp9", "vp9", "vp09");
        put(VIDEO, "av1", "av1", "av01");
        put(VIDEO, "mpeg4", "mpeg4", "mp4v", "divx", "xvid", "dx50");
        put(VIDEO, "mpeg2", "mpeg2video", "mpeg2", "mpg2");
        put(VIDEO, "mpeg1", "mpeg1video", "mpeg1");
        put(VIDEO, "vc1", "vc1", "wmv3");
        put(VIDEO, "wmv", "wmv1", "wmv2");
        put(VIDEO, "h263", "h263", "s263");
        put(VIDEO, "theora", "theora");

        put(AUDIO, "aac", "aac", "aac_latm", "mp4a");
        put(AUDIO, "mp3", "mp3", "mp3float", "mpeg_audio");
        put(AUDIO, "ac3", "ac3", "ac-3", "a52");
        put(AUDIO, "eac3", "eac3", "ec-3", "e-ac-3");
        put(AUDIO, "dts", "dts", "dca", "dts-hd", "dtshd");
        put(AUDIO, "truehd", "truehd", "mlp");
        put(AUDIO, "flac", "flac");
        put(AUDIO, "opus", "opus");
        put(AUDIO, "vorbis", "vorbis");
        put(AUDIO, "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm", "lpcm");
        put(AUDIO, "wma", "wma", "wmav2", "wmapro");
        put(AUDIO, "alac", "alac");

        put(CONTAINER, "mp4", "mp4", "m4v", "mov", "3gp", "3g2", "ismv", "fmp4");
        put(CONTAINER, "mkv", "mkv", "matroska", "mka", "webm");
        put(CONTAINER, "ts", "ts", "mpegts", "m2ts", "mts", "m2tss");
        put(CONTAINER, "avi", "avi");
        put(CONTAINER, "flv", "flv", "f4v");
        put(CONTAINER, "hls", "m3u8", "hls");
        put(CONTAINER, "dash", "mpd", "dash");
        put(CONTAINER, "wmv", "wmv", "asf");
        put(CONTAINER, "mpg", "mpg", "mpeg", "vob");
    }

    private static void put(Map<String, String> target, String canonical, String... aliases) {
        for (int i = 0; i < aliases.length; i++) {
            target.put(aliases[i], canonical);
        }
        target.put(canonical, canonical);
    }

    private static String lookup(Map<String, String> table, String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.US);
        // "video/avc", "audio/mp4a-latm" 之类 MIME
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dash = s.indexOf('-');
        if (dash > 0 && table.get(s) == null) {
            s = s.substring(0, dash);
        }
        String hit = table.get(s);
        return hit == null ? s : hit;
    }

    public static String video(String raw) {
        return lookup(VIDEO, raw);
    }

    public static String audio(String raw) {
        return lookup(AUDIO, raw);
    }

    public static String container(String raw) {
        return lookup(CONTAINER, raw);
    }

    /** 由文件扩展名推断容器；无法识别时返回空串。 */
    public static String containerFromPath(String path) {
        if (path == null) {
            return "";
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return container(path.substring(dot + 1));
    }

    public static String mime(String videoCodec) {
        String c = video(videoCodec);
        if ("h264".equals(c)) return "video/avc";
        if ("hevc".equals(c)) return "video/hevc";
        if ("vp8".equals(c)) return "video/x-vnd.on2.vp8";
        if ("vp9".equals(c)) return "video/x-vnd.on2.vp9";
        if ("av1".equals(c)) return "video/av01";
        if ("mpeg4".equals(c)) return "video/mp4v-es";
        if ("mpeg2".equals(c)) return "video/mpeg2";
        return "";
    }

    private CodecNames() {
    }
}
