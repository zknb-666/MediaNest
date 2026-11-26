package com.juying.medianest.app;

import com.juying.medianest.core.util.L;
import com.juying.medianest.player.Sdk;

import android.app.Application;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.data.MediaApiFactory;
import com.juying.medianest.data.discovery.DefaultDiscoveryService;
import com.juying.medianest.data.provider.fnos.WebAuthConfigFetcher;
import com.juying.medianest.data.store.PrefsAccountRepository;
import com.juying.medianest.data.store.PrefsConfigStore;
import com.juying.medianest.domain.model.AuthConfig;
import com.juying.medianest.domain.repository.AccountRepository;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.domain.usecase.DiscoveryService;
import com.juying.medianest.domain.usecase.MediaUseCases;
import com.juying.medianest.player.AuthProxy;

import java.io.IOException;

/** 全局入口与组合根：持有配置仓库、本地鉴权代理，并创建领域媒体用例。 */
public final class App extends Application {

    private ConfigRepository configStore;
    private AuthProxy authProxy;

    public void onCreate() {
        super.onCreate();
        // 真机（Android 4.2.1）实测：旧版 HttpURLConnection 连接池会复用
        // 已被 NAS 提前关闭的 socket，局域网高频请求时偶发 EOFException。
        // 关闭连接复用，用一次建连换取稳定性（局域网延迟仅 2ms，代价可忽略）。
        System.setProperty("http.keepAlive", "false");
        configStore = new PrefsConfigStore(this);
        L.i("App", "启动，API " + Sdk.level());
        // 进入应用就定内核：同步读上次持久化结论，后台再复核一次（约十几毫秒），
        // 起播路径只读结论，不再探测、不等待。
        com.juying.medianest.player.EngineSelector.init(this);
    }

    public ConfigRepository config() {
        return configStore;
    }

    public AccountRepository accounts() {
        return new PrefsAccountRepository(this);
    }

    public DiscoveryService discovery(android.content.Context context) {
        return new DefaultDiscoveryService(context);
    }

    public AuthConfig fetchAuthConfig(String baseUrl) throws FnException {
        return WebAuthConfigFetcher.fetch(baseUrl);
    }

    public ConfigCodec.ServerInfo probeJellyfin(String baseUrl) {
        return MediaApiFactory.probeJellyfin(baseUrl);
    }

    public MediaUseCases useCases(ServerConfig cfg) {
        return new MediaUseCases(MediaApiFactory.create(cfg));
    }

    /**
     * 按需创建/复用本地鉴权代理。只按服务器地址复用：token 重新登录后代理
     * 在原地更新 Authorization，端口不变，正在播放的 URL 不会被换掉。
     */
    public synchronized AuthProxy proxy(String baseUrl, String authorization) {
        if (authProxy == null || !authProxy.matches(baseUrl)) {
            if (authProxy != null) {
                authProxy.stop();
            }
            AuthProxy p = new AuthProxy(baseUrl, authorization);
            // 大影片起播时按「预取首段若干分片到本地」的方式等 NAS 转码，
            // 缓存放应用私有 cache 目录，退出播放器时由 PlayerActivity 清理。
            p.setCacheRoot(new java.io.File(getCacheDir(), "segments"));
            try {
                p.start();
                authProxy = p;
            } catch (IOException ex) {
                L.e("App", "本地鉴权代理启动失败，退回原始地址", ex);
                authProxy = null;
            }
        } else {
            authProxy.setToken(authorization);
        }
        return authProxy;
    }

    public static App from(android.content.Context ctx) {
        return (App) ctx.getApplicationContext();
    }
}
