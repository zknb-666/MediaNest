package com.juying.medianest.core.policy;

/**
 * 飞牛影视 HLS 播放会话的续期策略（纯 Java，不依赖 Android，可在 JVM 上单测）。
 *
 * 真机实测（2026-11-18 / 飞牛影视 nas.example.com:5666 + TCL Android 4.2.1）：
 *
 * <pre>
 * POST /v/api/v1/play/play -> data.play_link = /v/media/&lt;hash&gt;/preset.m3u8
 * main.m3u8 是 PLAYLIST-TYPE:VOD，4262 个 2 秒分片（00000.ts ...），有 ENDLIST。
 *
 * t=0   新会话 00000.ts / 00314.ts / 00999.ts 全部 200（不是分片窗口问题）
 * t=20s 每 5 秒拉一个分片保活，仍然 200
 * t=62s 同一个会话所有路径（含 preset.m3u8）统一返回 410 Gone，body 长度 0
 * 重新 POST /play/play -> 新 hash，preset/main/ts 全部恢复 200
 * </pre>
 *
 * 结论：仅持续拉流不会延长会话；网页端通过 30 秒 media.transcodeStatis 心跳保活。
 * 客户端跟随网页端使用心跳，真实 410/403 时才强制换 hash，并回收旧会话。
 *
 * 由于 main.m3u8 是确定性的 VOD 列表（分片编号与时长跨会话一致），续期后只要把
 * 请求路径里的旧 hash 换成新 hash、保留后面的文件名，播放器就能无缝继续拉分片，
 * 无需重新 seek、也不会出画卡顿。这对「无法附加 Authorization 头、也无法自己
 * 换 URL」的外部系统播放器尤其关键。
 */
public final class SessionPolicy {

    /** 410 可能由多个并发分片请求同时触发，避免续期风暴。 */
    public static final long MIN_FORCE_INTERVAL_MS = 3000L;

    private static final String MEDIA_PREFIX = "/v/media/";

    private SessionPolicy() {
    }

    /** 是否允许再发起一次「被动强制续期」（针对并发 410 去重）。 */
    public static boolean allowForceRenew(long nowMs, long lastForceMs) {
        return lastForceMs <= 0L || nowMs - lastForceMs >= MIN_FORCE_INTERVAL_MS;
    }

    /** 410 Gone / 403 Forbidden 都表示旧会话不再可用，需要强制续期。 */
    public static boolean isSessionGone(int status) {
        return status == 410 || status == 403;
    }

    /**
     * 从 play_link、绝对 URL 或代理请求路径中提取 "/v/media/&lt;hash&gt;" 目录。
     * 提取不到时返回 ""（例如原始流 /v/api/v1/media/range/* 不需要续期）。
     */
    public static String mediaDirOf(String urlOrPath) {
        if (urlOrPath == null) {
            return "";
        }
        int p = urlOrPath.indexOf(MEDIA_PREFIX);
        if (p < 0) {
            return "";
        }
        int start = p + MEDIA_PREFIX.length();
        int end = urlOrPath.length();
        int slash = urlOrPath.indexOf('/', start);
        int question = urlOrPath.indexOf('?', start);
        if (slash > 0) {
            end = slash;
        }
        if (question > 0 && question < end) {
            end = question;
        }
        String hash = urlOrPath.substring(start, end);
        return hash.length() > 0 ? MEDIA_PREFIX + hash : "";
    }

    /**
     * 把请求路径里的旧会话目录换成新目录，保留后面的文件名与 query。
     * 例：/v/media/OLD/00314.ts + NEW -> /v/media/NEW/00314.ts
     */
    public static String remap(String path, String oldDir, String newDir) {
        if (path == null || oldDir == null || newDir == null) {
            return path;
        }
        if (oldDir.length() == 0 || newDir.length() == 0 || oldDir.equals(newDir)) {
            return path;
        }
        int p = path.indexOf(oldDir);
        if (p < 0) {
            return path;
        }
        return path.substring(0, p) + newDir + path.substring(p + oldDir.length());
    }

    /**
     * 代理回给播放器的 HTTP 状态行文本。
     *
     * 旧实现把所有非 206 状态一律写成 "200 OK"，410 Gone 就变成一个
     * Content-Length: 0 的「成功」响应；MediaPlayer 既不 onError 也不 onCompletion，
     * 只会静默卡死并触发 [OMX.MS.AVC.Decoder] Timed out waiting for output buffers。
     * 这里如实转发错误状态，让播放器能快速失败并进入恢复链。
     */
    public static String statusLine(int status) {
        if (status == 206) {
            return "206 Partial Content";
        }
        if (status >= 200 && status < 300) {
            return "200 OK";
        }
        if (status == 401) {
            return "401 Unauthorized";
        }
        if (status == 403) {
            return "403 Forbidden";
        }
        if (status == 404) {
            return "404 Not Found";
        }
        if (status == 410) {
            return "410 Gone";
        }
        if (status == 500) {
            return "500 Internal Server Error";
        }
        if (status == 502) {
            return "502 Bad Gateway";
        }
        if (status == 503) {
            return "503 Service Unavailable";
        }
        if (status <= 0) {
            return "502 Bad Gateway";
        }
        return status + " Proxy Error";
    }

    /** 是否应该把上游的错误状态如实回给播放器（而不是伪装成 200）。 */
    public static boolean isErrorStatus(int status) {
        return status < 200 || status >= 400;
    }
}
