package com.juying.medianest.player;

import android.app.Activity;
import android.os.Build;

import com.juying.medianest.core.util.L;

/**
 * 按 SDK_INT 选择播放内核。低版本设备永远不会加载 ExoPlayer 的类。
 *
 * <p>API16+ 且启动探测确认框架 MediaCodec 可用时优先 ExoPlayer，否则使用厂商
 * MediaPlayer；ExoPlayer 被判定不可用时回退 MediaPlayer。真实播放阶段的硬解码/
 * 渲染错误仍由 PlayerActivity 的错误回落兜底（每片一次、保留进度/字幕/OSD）。
 */
public final class EngineFactory {

    /** 本进程内已被证明起不来的内核。老 OEM 平台的 MediaCodec 能力枚举会卡在
     *  mediaserver 的 OMX 节点分配上（见 MStar/UMP 的 gralloc -22），且 ExoPlayer 的
     *  MediaCodecUtil 用的是进程级静态锁：一个实例卡住，后续实例全部排队。
     *  因此一旦某个内核被判定初始化失败，本进程不再创建它；厂商内核失败时
     *  是否切 ExoPlayer 由 {@link EngineSelector#canFallbackToExo()} 决定。 */
    private static volatile String brokenEngine = null;

    private EngineFactory() {
    }

    /** 由播放看门狗在「内核初始化失败」时调用；只在第一次记录原因。 */
    public static void markEngineBroken(String engineName, String reason) {
        if (brokenEngine == null) {
            brokenEngine = engineName;
            L.w("Player", "内核 " + engineName + " 判定为不可用，本进程后续不再创建它：" + reason);
            // mediaserver / media.codec 可能刚重启或刚挂掉：后台重新探测修正结论，
            // 供下一次冷启动（以及本进程解除 broken 后）使用。
            EngineSelector.onEngineBroken(engineName, reason);
        }
    }

    public static boolean isEngineBroken(String engineName) {
        return engineName != null && engineName.equals(brokenEngine);
    }

    public static PlayerEngine create(Activity activity) {
        int sdk = Build.VERSION.SDK_INT;
        // 启动时已定好的内核结论，这里同步读取，不再探测、不等待。
        // MediaCodec 可用时优先 ExoPlayer；否则走厂商 MediaPlayer。
        String chosen = EngineSelector.engine();
        // 距上次探测超过 1 分钟才后台复核（mediaserver/media.codec 重启后可自愈）。
        EngineSelector.refreshIfStale();
        if (EngineSelector.MEDIA_PLAYER.equals(chosen)) {
            if (isEngineBroken(EngineSelector.MEDIA_PLAYER) && sdk >= 16
                    && !isEngineBroken(EngineSelector.EXO_PLAYER2)
                    && EngineSelector.canFallbackToExo()) {
                L.i("Player", "播放内核=exoplayer2（厂商 MediaPlayer 本进程已判定不可用）sdk=" + sdk);
                return new Exo2Engine(activity);
            }
            L.i("Player", "播放内核=mediaplayer sdk=" + sdk + "（厂商管线优先）");
            return new MediaPlayerEngine(activity);
        }
        if (EngineSelector.EXO_PLAYER2.equals(chosen) && sdk >= 16) {
            if (isEngineBroken(EngineSelector.EXO_PLAYER2)) {
                L.i("Player", "播放内核=mediaplayer（本进程 exoplayer2 已判定不可用）sdk=" + sdk);
                return new MediaPlayerEngine(activity);
            }
            L.i("Player", "播放内核=exoplayer2 sdk=" + sdk + "（启动时已定）");
            return new Exo2Engine(activity);
        }
        L.i("Player", "播放内核=mediaplayer sdk=" + sdk + "（启动时已定）");
        return new MediaPlayerEngine(activity);
    }
}
