package com.juying.medianest.domain.usecase;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.ServerConfig;

import java.util.List;

/** 局域网媒体服务器发现端口。 */
public interface DiscoveryService {
    interface Callback {
        void onProgress(String text);
        void onFound(ConfigCodec.ServerInfo server);
        void onDone(List<ConfigCodec.ServerInfo> result);
    }

    void discover(ServerConfig cfg, List<ConfigCodec.ServerInfo> known, Callback callback);
    void cancel();
}
