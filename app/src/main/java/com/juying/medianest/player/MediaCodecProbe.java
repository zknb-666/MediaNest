package com.juying.medianest.player;

import android.os.Build;
import android.os.IBinder;

import com.juying.medianest.core.util.L;

import java.lang.reflect.Method;

/**
 * MediaCodec 内核可用性探测。
 *
 * <p>老 OEM 平台（MStar/UMP 等）上 MediaCodec 的 alloc 会在厂商 gralloc 里直接
 * 报 -22，ExoPlayer 起不来说明书要 40 秒以上才肯失败，期间还会卡住进程级静态锁。
 * 与其「先播一遍等报错」，不如起播前花几毫秒问系统一句：媒体框架的编解码服务
 * 在不在、能不能 ping 通。
 *
 * <p>判据：
 *  - {@code media.codec} 服务存在且 pingBinder 成功 → 可用；
 *  - 否则 API 21+ 只看 {@code media.player} 是否存活（部分 5.x/6.x 的 media.codec
 *    仍由 mediaserver 内部持有、不单独注册，而 MediaCodec 是平台保证能力）；
 *  - 都拿不到 → 不可用，直接走平台 MediaPlayer。
 *
 * <p>结论只用于决定是否启用 ExoPlayer，绝不做机型/SoC 预判；真实播放
 * 错误仍由 PlayerActivity 的错误回落兜底（每片一次、保留进度/字幕/OSD）。
 * 只允许后台线程调用 {@link #probeNow()}，结论由 {@link EngineSelector} 缓存并
 * 持久化，起播路径不探测、不等待。
 */
public final class MediaCodecProbe {

    private static volatile Result cached;

    private MediaCodecProbe() {
    }

    /** 探测结果快照，字段原样保留原始信号，便于日志/现场排查对账。 */
    public static final class Result {
        public boolean available;
        public String mediaCodec = "n/a";
        public String mediaPlayer = "n/a";
        public String svcMedia = "n/a";
        public String svcMediaCodec = "n/a";
        public String note = "";
        public long costMs = 0L;
    }

    /**
     * 立即探测一次（正常约十几毫秒）并缓存结论。只允许后台线程调用，
     * 由 {@link EngineSelector} 在启动/失败复核时驱动；起播路径只读缓存结论。
     *
     * <p>探测本身不加锁：mediaserver 卡死时 pingBinder 可能永远不返回，
     * 不能让一个卡住的探测把后续复核全部堵死。
     */
    public static Result probeNow() {
        Result r = probe();
        cached = r;
        return r;
    }

    /** 最近一次探测结论；从未探测过返回 null（调用方不得在此路径上等待）。 */
    public static Result lastResult() {
        return cached;
    }

    private static Result probe() {
        long start = System.currentTimeMillis();
        Result r = new Result();

        r.svcMedia = prop("init.svc.media");
        r.svcMediaCodec = prop("init.svc.mediacodec");

        Object codecBinder = checkService("media.codec");
        Object playerBinder = checkService("media.player");
        boolean codecPing = ping(codecBinder);
        boolean playerPing = ping(playerBinder);
        r.mediaCodec = describe(codecBinder, codecPing);
        r.mediaPlayer = describe(playerBinder, playerPing);

        if (codecBinder != null && codecPing) {
            r.available = true;
            r.note = "media.codec 可连接";
        } else if (Build.VERSION.SDK_INT >= 21 && playerBinder != null && playerPing) {
            // API21+ 的 MediaCodec 是平台保证能力；media.codec 在 5.x/6.x 上不一定
            // 单独注册（由 mediaserver 内部持有），此时以媒体主服务存活为准。
            r.available = true;
            r.note = "media.codec 缺失，但 API" + Build.VERSION.SDK_INT
                    + " 平台保证 MediaCodec，且 media.player 可连接";
        } else if (codecBinder == null && playerBinder == null) {
            r.available = false;
            r.note = "media.codec/media.player 均不可获取"
                    + (reflectOk() ? "" : "（ServiceManager 反射不可用）");
        } else {
            r.available = false;
            r.note = "media.codec 缺失，init.svc.media=" + r.svcMedia;
        }
        r.costMs = System.currentTimeMillis() - start;
        if (r.note.length() == 0) {
            r.note = "默认判定";
        }
        L.i("CodecProbe", "探测结论=" + (r.available ? "可用" : "不可用")
                + " sdk=" + Build.VERSION.SDK_INT
                + " media.codec=" + r.mediaCodec
                + " media.player=" + r.mediaPlayer
                + " init.svc.media=" + r.svcMedia
                + " init.svc.mediacodec=" + r.svcMediaCodec
                + " 依据=" + r.note + " 耗时=" + r.costMs + "ms");
        return r;
    }

    private static String describe(Object binder, boolean ping) {
        if (binder == null) {
            return "null";
        }
        return "present,ping=" + ping;
    }

    private static boolean ping(Object binder) {
        if (!(binder instanceof IBinder)) {
            return false;
        }
        try {
            return ((IBinder) binder).pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** ServiceManager.checkService：服务没注册时立即返回 null，不做等待，不会卡主线程。 */
    private static Object checkService(String name) {
        try {
            Class<?> cls = Class.forName("android.os.ServiceManager");
            Method m = cls.getMethod("checkService", String.class);
            return m.invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean reflectOk() {
        try {
            Class.forName("android.os.ServiceManager").getMethod("checkService", String.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** SystemProperties.get(key)（无默认值版本），失败返回 ""。 */
    private static String prop(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method m = cls.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }
}
