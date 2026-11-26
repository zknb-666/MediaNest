package com.juying.medianest.core.util;

import java.nio.charset.Charset;

/** 地址归一化与 URL 拼装。用户可能只填 IP、也可能填完整 URL，这里统一处理。 */
public final class NetUtil {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset UTF_16LE = Charset.forName("UTF-16LE");
    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");

    public static final int DEFAULT_PORT = 5666;
    public static final int[] DEFAULT_PORTS = {5666, 8005, 8096, 80, 5667, 8080};

    private NetUtil() {
    }

    /**
     * "nas.example.com" / "nas.example.com:8005" / "http://x:5666/" / "https://nas.example.com/v"
     * -> "http://nas.example.com:5666"（去掉尾部斜杠与 /v 路径）
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return "";
        }
        boolean https = false;
        if (s.startsWith("https://")) {
            https = true;
            s = s.substring(8);
        } else if (s.startsWith("http://")) {
            s = s.substring(7);
        } else if (s.indexOf("://") > 0) {
            return ""; // 不支持的协议
        }
        // 去掉用户误填的路径
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        // IPv6 字面量不做处理
        if (s.startsWith("[")) {
            return (https ? "https://" : "http://") + s;
        }
        int colon = s.lastIndexOf(':');
        if (colon < 0) {
            s = s + ":" + (https ? 5667 : DEFAULT_PORT);
        } else {
            String port = s.substring(colon + 1);
            if (!isNumeric(port)) {
                s = s + ":" + (https ? 5667 : DEFAULT_PORT);
            }
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return (https ? "https://" : "http://") + s;
    }

    public static String hostOf(String baseUrl) {
        String s = stripScheme(baseUrl);
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }

    public static int portOf(String baseUrl) {
        String s = stripScheme(baseUrl);
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            String p = s.substring(colon + 1);
            if (isNumeric(p)) {
                try {
                    return Integer.parseInt(p);
                } catch (NumberFormatException ignored) {
                    return DEFAULT_PORT;
                }
            }
        }
        return baseUrl != null && baseUrl.startsWith("https") ? 5667 : DEFAULT_PORT;
    }

    public static String withPort(String host, int port) {
        return "http://" + host + ":" + port;
    }

    public static String join(String baseUrl, String path) {
        if (baseUrl == null) baseUrl = "";
        if (path == null) path = "";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl + path;
    }

    public static String appendQuery(String url, String query) {
        if (query == null || query.length() == 0) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    /**
     * 文本解码：识别 UTF-8 / UTF-16 LE / UTF-16 BE 的 BOM，其余按 UTF-8。
     * 字幕文件常见带 BOM，直接按 UTF-8 解会把 BOM 变成 \uFEFF 混进首行
     * （WEBVTT / [Script Info] 识别失败），UTF-16 文件则整篇乱码。
     */
    public static String decodeText(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, UTF_16BE);
        }
        // 无 BOM 一律按 UTF-8；不能回退到平台默认编码（部分固件是 GBK，字幕会乱码）
        return new String(data, UTF_8);
    }

    /** 目标是否属于私有局域网地址（发现阶段只扫内网段）。 */
    public static boolean isLan(String host) {
        if (host == null) return false;
        return host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("172.16.") || host.startsWith("172.17.")
                || host.startsWith("172.18.") || host.startsWith("172.19.")
                || host.startsWith("172.2") || host.startsWith("169.254.");
    }

    private static String stripScheme(String s) {
        if (s == null) return "";
        String t = s;
        if (t.startsWith("http://")) t = t.substring(7);
        else if (t.startsWith("https://")) t = t.substring(8);
        int slash = t.indexOf('/');
        return slash >= 0 ? t.substring(0, slash) : t;
    }

    private static boolean isNumeric(String s) {
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }
}
