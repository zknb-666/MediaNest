package com.juying.medianest.data;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.data.provider.fnos.FnNasApi;
import com.juying.medianest.data.provider.jellyfin.JellyfinApi;
import com.juying.medianest.domain.port.MediaApi;

/** 根据配置的 apiMode 选择飞牛或 Jellyfin 数据源。 */
public final class MediaApiFactory {

    public static MediaApi create(ServerConfig cfg) {
        if (cfg != null && "JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            return new JellyfinApi();
        }
        return new FnNasApi();
    }

    public static ConfigCodec.ServerInfo probeJellyfin(String baseUrl) {
        return JellyfinApi.probe(baseUrl);
    }

    private MediaApiFactory() {
    }
}
