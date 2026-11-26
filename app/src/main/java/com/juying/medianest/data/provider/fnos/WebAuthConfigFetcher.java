package com.juying.medianest.data.provider.fnos;

import com.juying.medianest.domain.model.AuthConfig;
import com.juying.medianest.data.api.HttpStack;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.util.NetUtil;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebAuthConfigFetcher {

    private static final Pattern JS_ASSET = Pattern.compile(
            "(?:src|href)=[\"']([^\"']+\\.js)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile("`([A-Za-z0-9_-]{20,64})`");
    private static final Pattern BYTE = Pattern.compile("\\d+");

    private WebAuthConfigFetcher() {
    }

    public static AuthConfig fetch(String baseUrl) throws FnException {
        if (baseUrl == null || baseUrl.length() == 0) {
            throw new FnException(FailureReason.CONNECTION_FAILED, "服务器地址为空");
        }
        HttpStack.Response index = HttpStack.request("GET", NetUtil.join(baseUrl, "/v/"),
                null, null, 5000, 8000);
        if (!index.ok()) {
            throw new FnException(FailureReason.SERVER_ERROR,
                    "读取影视网页失败 HTTP " + index.status);
        }
        Matcher assets = JS_ASSET.matcher(index.body);
        while (assets.find()) {
            String path = assets.group(1);
            HttpStack.Response script;
            try {
                script = HttpStack.request("GET", new URL(new URL(baseUrl + "/v/"), path).toString(),
                        null, null, 5000, 8000);
            } catch (Exception e) {
                continue;
            }
            if (!script.ok()) continue;
            AuthConfig config = parse(script.body);
            if (config != null) return config;
        }
        throw new FnException(FailureReason.SERVER_ERROR, "网页端未找到 authx 签名参数");
    }

    private static AuthConfig parse(String text) {
        if (text == null || text.length() == 0) return null;
        int authx = text.indexOf("authx");
        int join = text.lastIndexOf(".join(`_`)", authx >= 0 ? authx : text.length());
        if (join < 0) {
            join = text.lastIndexOf(".join('_')", authx >= 0 ? authx : text.length());
        }
        String secret = "";
        if (join >= 0) {
            Matcher values = SECRET.matcher(text.substring(Math.max(0, join - 1200), join));
            while (values.find()) secret = values.group(1);
        }

        int xor = text.indexOf("^169");
        if (xor < 0) xor = text.indexOf("^ 169");
        String apiKey = "";
        if (xor >= 0) {
            int start = text.lastIndexOf("new Uint8Array([", xor);
            if (start >= 0) {
                int end = text.indexOf("])", start);
                if (end > start) {
                    Matcher bytes = BYTE.matcher(text.substring(start + "new Uint8Array([".length(), end));
                    StringBuilder key = new StringBuilder();
                    while (bytes.find()) {
                        key.append((char) (Integer.parseInt(bytes.group()) ^ 169));
                    }
                    apiKey = key.toString();
                }
            }
        }
        if (secret.length() < 16 || apiKey.length() < 16) return null;
        return new AuthConfig(secret, apiKey);
    }
}
