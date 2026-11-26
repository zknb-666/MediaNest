package com.juying.medianest.data.provider.fnos;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 飞牛影视 /v/api/v1 的 authx 请求签名（逆向自影视 Web 前端，2026-11 实测通过）。
 *
 * authx = nonce=&lt;6位随机数&gt;&amp;timestamp=&lt;毫秒&gt;&amp;sign=&lt;md5&gt;
 * sign  = md5( secret + "_" + pathname + "_" + nonce + "_" + timestamp + "_" + bodyHash + "_" + apiKey )
 *
 * bodyHash:
 *   GET  -> md5( decodeURIComponent( 排序后的 queryString ) )   // 空 query 即 md5("")
 *   其它 -> md5( JSON.stringify(请求体) )                        // 紧凑、保持插入顺序
 *
 * 注意：/media/ /img/ /concat/ /sys/img/ 以及 /play/record 等路径在前端被列入免签白名单，
 * 视频流地址本身不需要 authx，但需要 Authorization 或 playlink。
 *
 * 签名参数不随源码分发。登录页会从所选服务器的影视网页端自动提取
 * 当前 secret 与 apiKey；固件升级后重新登录即可刷新。
 */
public final class CseSigner {

    public static final String DEFAULT_SECRET = "";
    public static final String DEFAULT_API_KEY = "";
    public static final String API_PREFIX = "/v/api/v1";

    private static final String[] UNSIGNED_PREFIXES = {
            API_PREFIX + "/sys/img/",
            API_PREFIX + "/img/",
            API_PREFIX + "/concat/",
            API_PREFIX + "/media/"
    };
    private static final String[] UNSIGNED_EXACT = {
            API_PREFIX + "/task/running",
            API_PREFIX + "/play/record",
            API_PREFIX + "/subtitle/upload"
    };

    private CseSigner() {
    }

    public static boolean isUnsignedPath(String path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < UNSIGNED_PREFIXES.length; i++) {
            if (path.startsWith(UNSIGNED_PREFIXES[i])) {
                return true;
            }
        }
        for (int i = 0; i < UNSIGNED_EXACT.length; i++) {
            if (path.equals(UNSIGNED_EXACT[i])) {
                return true;
            }
        }
        return false;
    }

    /** 与前端 pu() 等价：键排序、URLSearchParams 编码、空格用 %20 而非 +。 */
    public static String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = params.get(k);
            if (v == null || "undefined".equals(v) || "null".equals(v)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(k)).append('=').append(encode(v));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            // UTF-8 是 JVM 强制实现，绝不会走到这里；旧实现回退到平台默认编码，
            // 在 GBK 固件上会和 md5 的 UTF-8 字节不一致，直接失败更安全。
            throw new IllegalStateException("UTF-8 不可用", ex);
        }
    }

    public static String bodyHashForGet(String query) {
        if (query == null || query.length() == 0) {
            return md5("");
        }
        try {
            return md5(URLDecoder.decode(query, "UTF-8"));
        } catch (Exception ex) {
            return md5(query);
        }
    }

    public static String bodyHashForBody(String jsonBody) {
        return md5(jsonBody == null ? "" : jsonBody);
    }

    /** 自动生成 nonce 与毫秒时间戳并签名。 */
    public static String sign(String method, String pathname, Map<String, String> params,
                              String jsonBody, String secret, String apiKey) {
        long nonce = 100000L + (long) (Math.random() * 900000L);
        return signInternal(String.valueOf(nonce), String.valueOf(System.currentTimeMillis()),
                method, pathname, params, jsonBody, secret, apiKey);
    }

    private static String signInternal(String nonce, String timestamp, String method, String pathname,
                                       Map<String, String> params, String jsonBody,
                                       String secret, String apiKey) {
        boolean isGet = "GET".equalsIgnoreCase(method);
        String bodyHash;
        if (isGet) {
            bodyHash = bodyHashForGet(buildQuery(params));
        } else {
            bodyHash = bodyHashForBody(jsonBody);
        }
        if (secret == null || secret.length() == 0
                || apiKey == null || apiKey.length() == 0) {
            throw new IllegalArgumentException("无法获取 authx 签名参数，请检查服务器网页端");
        }
        String raw = secret + "_" + pathname + "_" + nonce + "_" +
                timestamp + "_" + bodyHash + "_" + apiKey;
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + md5(raw);
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            // 显式 UTF-8（Charset 常量复用，避免每次查表与 UnsupportedEncodingException）
            byte[] bytes = s.getBytes(UTF_8);
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < digest.length; i++) {
                String hex = Integer.toHexString(digest[i] & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // 所有 Android 版本都带 MD5；真缺了就退化为不稳定占位，调用方会得到 invalid sign
            return "00000000000000000000000000000000";
        }
    }
}
