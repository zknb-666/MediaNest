package com.juying.medianest.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.juying.medianest.core.util.L;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 播放内核选择器：进入应用时就定内核，起播阶段只读结论。
 *
 * <p>API16+ 且框架 MediaCodec 探测可用时优先 ExoPlayer 2.19.1；否则使用系统
 * {@code android.media.MediaPlayer}。ExoPlayer 不可用时由 {@code EngineFactory}
 * 回退到厂商 MediaPlayer，真实播放阶段的硬解码/渲染错误仍由 PlayerActivity 兜底。
 *
 * <p>流程：
 *  1. {@link #init(Context)} 在 Application.onCreate 同步读上次持久化的探测结论，
 *     打印「本次选用内核 = xxx（依据）」；
 *  2. 后台线程重新探测一次 media.codec / media.player 服务（约十几毫秒），
 *     结论变化时更新内存与持久化记录，供回落判断使用；
 *  3. 持久化记录带设备指纹（机型/系统版本/device），换机或刷机后自动作废；
 *  4. 内核真实播放失败（{@link #onEngineBroken}）时再复核一次，用于 mediaserver /
 *     media.codec 重启后修正结论。
 *
 * <p>起播路径永不等待探测：缓存缺失时先按主通路（MediaPlayer）走。
 */
public final class EngineSelector {

    public static final String MEDIA_PLAYER = "mediaplayer";
    public static final String EXO_PLAYER2 = "exoplayer2";
    /** 旧版本曾持久化的内核名；Media3 已移除，读到时迁移为 exoplayer2。 */
    private static final String LEGACY_MEDIA3 = "media3";

    private static final String PREF_NAME = "fntv_engine";
    private static final String K_FP = "fp";
    private static final String K_AVAILABLE = "available";
    private static final String K_CODEC = "codec";
    private static final String K_PLAYER = "player";
    private static final String K_SVC_MEDIA = "svc_media";
    private static final String K_SVC_CODEC = "svc_codec";
    private static final String K_ENGINE = "engine";
    private static final String K_AT = "at";

    private static volatile String preferred = MEDIA_PLAYER;
    /** 框架 MediaCodec 是否可用；只决定厂商内核失败后能否回落 ExoPlayer。 */
    private static volatile boolean codecUsable = false;
    private static volatile String reason = "尚未探测";
    private static volatile Context appContext;
    private static volatile boolean refreshing = false;
    private static volatile long refreshStartedAt = 0L;
    private static volatile long lastProbeAt = 0L;
    /** 起播时顺手复核的节流间隔：mediaserver 重启后能在一个播放周期内纠正结论。 */
    private static final long STALE_MS = 60000L;

    private EngineSelector() {
    }

    /** Application.onCreate 调用：立即定内核 + 后台复核。 */
    public static synchronized void init(Context ctx) {
        appContext = ctx.getApplicationContext();
        int sdk = Build.VERSION.SDK_INT;
        SharedPreferences sp = prefs();
        boolean available;
        String basis;
        if (fingerprint().equals(sp.getString(K_FP, ""))) {
            available = sp.getBoolean(K_AVAILABLE, false);
            basis = "持久化结论 " + (available ? "MediaCodec 可用" : "MediaCodec 不可用")
                    + "（media.codec=" + sp.getString(K_CODEC, "?")
                    + "，记录于 " + stamp(sp.getLong(K_AT, 0L)) + "）";
        } else {
            available = false;
            basis = "本机无持久化结论，先按安全默认";
        }
        codecUsable = available;
        preferred = engineFor(sdk, available);
        reason = basis;
        migrateLegacyEngine(sp, available);
        L.i("Engine", "本次选用内核 = " + preferred + "（" + basis + "）sdk=" + sdk
                + " 机型=" + Build.MODEL);
        refreshAsync("启动复核");
    }

    /**
     * 旧版本在 fntv_engine.xml 里持久化过 engine=media3；该内核已移除，
     * 这里把旧值改写为当前内核，避免下次读日志/排障时误判。
     */
    private static void migrateLegacyEngine(SharedPreferences sp, boolean codecAvailable) {
        if (!LEGACY_MEDIA3.equals(sp.getString(K_ENGINE, ""))) {
            return;
        }
        String migrated = engineFor(Build.VERSION.SDK_INT, codecAvailable);
        sp.edit().putString(K_ENGINE, migrated).commit();
        L.i("Engine", "旧持久化内核 media3 已迁移为 " + migrated);
    }

    /** 起播时同步读取，不探测、不等待；返回启动探测选出的主内核。 */
    public static String engine() {
        return preferred;
    }

    /**
     * 厂商内核失败后能否回落 ExoPlayer：
     * 需要 SDK 16+ 且探测到框架 MediaCodec 真正可用。151 没有 media.codec 服务，
     * 回落 Exo 只会卡在 MediaCodec 初始化，这里返回 false，直接走降档。
     */
    public static boolean canFallbackToExo() {
        return Build.VERSION.SDK_INT >= 16 && codecUsable;
    }

    public static String reason() {
        return reason;
    }

    /**
     * 内核真实播放失败时调用：可能是 mediaserver / media.codec 刚重启或刚挂掉，
     * 后台重新探测并修正结论（本进程仍由 EngineFactory 的 broken 名单兜底）。
     */
    public static void onEngineBroken(String engineName, String why) {
        L.i("Engine", "内核 " + engineName + " 播放失败，后台复核服务状态：" + why);
        refreshAsync("失败复核");
    }

    /** 起播前顺手触发一次复核（不等待、不阻塞起播）：服务重启后能尽快纠正结论。 */
    public static void refreshIfStale() {
        if (System.currentTimeMillis() - lastProbeAt >= STALE_MS) {
            refreshAsync("起播复核");
        }
    }

    /** 后台复核一次探测，结论变化时更新内存与持久化记录。 */
    static void refreshAsync(final String trigger) {
        final Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 若上一次探测卡在 binder 上（mediaserver 死锁），超时后允许重新探测。
        if (refreshing && now - refreshStartedAt < 10000L) {
            return;
        }
        refreshing = true;
        refreshStartedAt = now;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    MediaCodecProbe.Result r = MediaCodecProbe.probeNow();
                    apply(ctx, r, trigger);
                } catch (Throwable t) {
                    L.w("Engine", "后台复核异常：" + t.getClass().getName());
                } finally {
                    refreshing = false;
                }
            }
        }, "engine-probe");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void apply(Context ctx, MediaCodecProbe.Result r, String trigger) {
        int sdk = Build.VERSION.SDK_INT;
        String chosen = engineFor(sdk, r.available);
        boolean changed = !chosen.equals(preferred);
        String old = preferred;
        codecUsable = r.available;
        preferred = chosen;
        reason = trigger + "：" + r.note;
        lastProbeAt = System.currentTimeMillis();
        save(ctx, r, chosen);
        if (changed) {
            L.i("Engine", trigger + "：内核结论变化 " + old + " → " + chosen
                    + "（" + r.note + " media.codec=" + r.mediaCodec
                    + " media.player=" + r.mediaPlayer + " 耗时=" + r.costMs + "ms）");
        } else {
            L.i("Engine", trigger + "：结论一致 " + chosen
                    + "（" + r.note + " media.codec=" + r.mediaCodec
                    + " 耗时=" + r.costMs + "ms），起播仍零探测");
        }
    }

    /**
     * API16+ 且框架 MediaCodec 可用时优先 ExoPlayer；探测不可用或版本过低时
     * 使用厂商 MediaPlayer。ExoPlayer 被标记不可用后的回退由 EngineFactory 处理。
     */
    private static String engineFor(int sdk, boolean codecAvailable) {
        return sdk >= 16 && codecAvailable ? EXO_PLAYER2 : MEDIA_PLAYER;
    }

    private static void save(Context ctx, MediaCodecProbe.Result r, String engine) {
        SharedPreferences.Editor e = prefs().edit();
        e.putString(K_FP, fingerprint());
        e.putBoolean(K_AVAILABLE, r.available);
        e.putString(K_CODEC, r.mediaCodec);
        e.putString(K_PLAYER, r.mediaPlayer);
        e.putString(K_SVC_MEDIA, r.svcMedia);
        e.putString(K_SVC_CODEC, r.svcMediaCodec);
        e.putString(K_ENGINE, engine);
        e.putLong(K_AT, System.currentTimeMillis());
        e.commit();
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String fingerprint() {
        return Build.MODEL + "|" + Build.VERSION.SDK_INT + "|" + Build.DEVICE;
    }

    private static String stamp(long at) {
        if (at <= 0L) {
            return "无";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at));
    }
}
