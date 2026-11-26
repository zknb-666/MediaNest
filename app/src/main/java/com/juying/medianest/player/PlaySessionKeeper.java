package com.juying.medianest.player;

import com.juying.medianest.core.util.L;

import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.policy.SessionPolicy;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.util.HashSet;
import java.util.Set;

/**
 * 播放会话续期器：给 {@link AuthProxy} 在会话到期前重新 POST /play/play。
 *
 * 设计要点：只持有 ServerConfig、MediaItem 与 MediaUseCases，不持有 Activity / View / Context，
 * 因此 PlayerActivity 被销毁或切到后台（外部系统播放器在前台）时，本地回环代理
 * 仍能继续续期，不会因 Activity 生命周期而失效，也不会造成 Activity 泄漏。
 *
 * 续期请求必须复用首播时的转码参数（cfg.transcodeVideoEncoder /
 * transcodeResolution / transcodeAudioEncoder），否则新会话的音视频编码可能变化，
 * 重映射后播放器会突然收到不同规格的分片。
 */
public final class PlaySessionKeeper implements AuthProxy.SessionKeeper, AuthProxy.TokenSource {

    private final ServerConfig cfg;
    private final MediaItem item;
    private final MediaUseCases api;
    private final Object lock = new Object();
    private final Object renewLock = new Object();
    private final Object authLock = new Object();
    private final Set<String> endedSessions = new HashSet<String>();

    private int renewals = 0;
    private long lastExpiredLogMs = 0L;
    /** 可选的 UI 侧兜底：续期彻底失败时让播放器重建方案；销毁时必须清空。 */
    private volatile Runnable expiredHook;
    private volatile Runnable renewedHook;
    /** 当前完整 play_link，供 30 秒心跳与退出回收使用。 */
    private volatile String currentPlayLink = "";
    private volatile PlaybackRequest playbackRequest;
    private volatile long currentPositionMs = -1L;
    private volatile Thread heartbeat;
    /** 网页端 Xp = 30 * 1000，transcodeStatis 的轮询间隔。 */
    private static final long HEARTBEAT_MS = 30000L;

    public PlaySessionKeeper(ServerConfig cfg, MediaItem item, MediaUseCases api) {
        this.cfg = cfg;
        this.item = item;
        this.api = api;
    }

    public void setPlaybackRequest(PlaybackRequest request) {
        playbackRequest = request == null ? null : request.copy();
    }

    public void updatePosition(long positionMs) {
        currentPositionMs = positionMs;
    }

    public String refreshSession() {
        synchronized (renewLock) {
            try {
                PlaybackRequest request = playbackRequest == null ? null : playbackRequest.copy();
                if (request != null && currentPositionMs >= 0L) {
                    request.startPositionMs = currentPositionMs;
                }
                String link = request == null
                        ? api.refreshPlayLink(cfg, item) : api.refreshPlayLink(cfg, item, request);
                String dir = SessionPolicy.mediaDirOf(link);
                if (dir.length() > 0) {
                    renewals++;
                    L.i("PlaySessionKeeper", "重建播放会话 第 " + renewals + " 次 → " + dir
                            + "（video=" + (request == null ? cfg.transcodeVideoEncoder : request.videoCodec)
                            + " audio=" + (request == null ? cfg.transcodeAudioEncoder : request.audioCodec)
                            + " resolution=" + (request == null ? cfg.transcodeResolution : request.resolution())
                            + " start=" + (request == null ? -1L : request.startPositionMs) + "ms）");
                } else {
                    L.w("PlaySessionKeeper", "重建会话返回的 play_link 无法解析目录");
                }
                return link;
            } catch (FnException ex) {
                L.w("PlaySessionKeeper", "会话续期失败：" + ex.detail);
            } catch (Throwable t) {
                L.w("PlaySessionKeeper", "会话续期异常：" + t);
            }
            return "";
        }
    }

    /**
     * 媒体流返回「200 + code:-2 Auth Failed」时由 {@link AuthProxy} 调用：
     * 用配置里的账号密码重新登录，并把新 token 写回 cfg（代理随即使用新值）。
     * 只持有 ServerConfig，不碰 Activity，因此切到外部系统播放器后依然有效。
     */
    public String refreshToken() {
        synchronized (authLock) {
            try {
                api.refreshToken(cfg);
                String authorization = api.authorizationHeader(cfg);
                if (authorization != null && authorization.length() > 0) {
                    L.i("PlaySessionKeeper", "媒体流鉴权失败，已重新登录并更新 token");
                    return authorization;
                }
            } catch (FnException ex) {
                L.w("PlaySessionKeeper", "重新登录失败：" + ex.detail);
            } catch (Throwable t) {
                L.w("PlaySessionKeeper", "重新登录异常：" + t);
            }
            return "";
        }
    }

    /**
     * 网页端每 30 秒上报 media.transcodeStatis 保活会话；
     * 文章里飞牛音乐对应 /track/transcode/heartbeat。心跳让服务端不回收会话，
     * 从而几乎不需要重建 —— 也就不会因重建把播放位置拉回滞后的 ts。
     */
    public void onSessionBound(String playLink) {
        synchronized (lock) {
            currentPlayLink = playLink == null ? "" : playLink;
            if (currentPlayLink.length() == 0 || heartbeat != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(HEARTBEAT_MS);
                        } catch (InterruptedException ex) {
                            return;
                        }
                        String link = currentPlayLink;
                        if (link.length() == 0) {
                            continue;
                        }
                        try {
                            api.touchPlaySession(cfg, link);
                        } catch (Throwable ignored) {
                            // 心跳失败不打断播放；真失效时由 410 走重建链路
                        }
                    }
                }
            }, "fntv-play-heartbeat");
            t.setDaemon(true);
            heartbeat = t;
            t.start();
            L.i("PlaySessionKeeper", "已启动 30 秒播放会话心跳（media.transcodeStatis）");
        }
    }

    public void touchSession(String playLink) {
        try {
            api.touchPlaySession(cfg, playLink);
        } catch (Throwable ignored) {
        }
    }

    public void onSessionRenewed(String oldDir, String newDir) {
        Runnable hook = renewedHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable t) {
                L.w("PlaySessionKeeper", "会话续期回调异常：" + t);
            }
        }
    }

    public void setRenewedHook(Runnable hook) {
        renewedHook = hook;
    }

    public void clearRenewedHook() {
        renewedHook = null;
    }

    /** 停止心跳（Activity 真正退出或切档重建时调用，避免后台空转）。 */
    public void stopHeartbeat() {
        synchronized (lock) {
            Thread t = heartbeat;
            if (t != null) {
                t.interrupt();
                heartbeat = null;
            }
        }
    }

    public String currentPlayLink() {
        return currentPlayLink;
    }

    /**
     * 结束当前播放会话：停心跳并通知 NAS 回收转码任务（会发 HTTP，必须在后台线程调用）。
     *
     * 切档/重建前必须调用：旧会话被 30 秒心跳一直续命时，NAS 的同片转码槽不会释放，
     * 新会话虽然能拿到 play_link，但分片迟迟不产出（实测全部 SocketTimeoutException，
     * 表现为「黑屏但有声音」）。
     */
    private boolean claimSessionEnd(String playLink) {
        if (playLink == null || playLink.length() == 0) {
            return false;
        }
        String key = SessionPolicy.mediaDirOf(playLink);
        if (key.length() == 0) {
            key = playLink;
        }
        synchronized (endedSessions) {
            return endedSessions.add(key);
        }
    }

    public void quitSession() {
        stopHeartbeat();
        String link;
        synchronized (lock) {
            link = currentPlayLink;
            currentPlayLink = "";
        }
        if (link.length() == 0 || !claimSessionEnd(link)) {
            return;
        }
        try {
            api.quitPlaySession(cfg, link);
        } catch (Throwable t) {
            // 回收失败不影响本地播放，旧会话最迟由服务端超时回收
            L.w("PlaySessionKeeper", "结束播放会话失败（忽略）：" + t);
        }
    }

    /** 退出播放页用的异步版本：不阻塞主线程，失败只记日志。 */
    public void endSessionAsync() {
        stopHeartbeat();
        String link;
        synchronized (lock) {
            link = currentPlayLink;
            currentPlayLink = "";
        }
        endSessionAsync(link);
    }

    public void endSessionAsync(final String playLink) {
        if (playLink == null || playLink.length() == 0 || !claimSessionEnd(playLink)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    api.quitPlaySession(cfg, playLink);
                } catch (Throwable ex) {
                    L.w("PlaySessionKeeper", "结束播放会话失败（忽略）：" + ex);
                }
            }
        }, "fntv-session-quit");
        t.setDaemon(true);
        t.start();
    }

    public void onSessionExpired(String path, int status) {
        long now = System.currentTimeMillis();
        // 多个并发分片会同时 410，日志与恢复动作按 5 秒去重，避免恢复风暴。
        if (now - lastExpiredLogMs < 5000L) {
            return;
        }
        lastExpiredLogMs = now;
        L.w("PlaySessionKeeper", "会话过期且续期未成功 HTTP " + status + " " + path
                + "（播放器将收到真实错误并重建播放方案）");
        Runnable hook = expiredHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable t) {
                L.w("PlaySessionKeeper", "过期回调异常：" + t);
            }
        }
    }

    /** 由 PlayerActivity 设置；Activity 销毁时必须 clearExpiredHook() 防止泄漏。 */
    public void setExpiredHook(Runnable hook) {
        this.expiredHook = hook;
    }

    public void clearExpiredHook() {
        this.expiredHook = null;
    }

    public int renewals() {
        return renewals;
    }
}
