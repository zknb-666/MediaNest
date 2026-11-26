package com.juying.medianest.domain.model;

/** 飞牛影视网页入口提供的鉴权签名参数。 */
public final class AuthConfig {
    public final String secret;
    public final String apiKey;

    public AuthConfig(String secret, String apiKey) {
        this.secret = secret;
        this.apiKey = apiKey;
    }
}
