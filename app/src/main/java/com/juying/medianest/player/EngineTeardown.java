package com.juying.medianest.player;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 播放内核的销毁线程池。
 *
 * <p>老设备上 {@code MediaPlayer.reset()/release()} 会通过 binder 等 mediaserver，
 * 解码器卡住时能阻塞几十秒；{@code ExoPlayer.release()} 会 join 播放线程，而播放线程
 * 可能正卡在 MediaCodec 能力查询里。这两件事一旦发生在主线程上就是 ANR，因此统一切到
 * 后台线程做，调用方只负责把实例摘掉。
 */
final class EngineTeardown {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private int index;

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "fn-tv-teardown-" + (++index));
            t.setDaemon(true);
            return t;
        }
    });

    private static final Object LOCK = new Object();
    /** 已提交但尚未执行完的销毁任务数。 */
    private static int pending;

    private EngineTeardown() {
    }

    static void post(Runnable task) {
        synchronized (LOCK) {
            pending++;
        }
        EXECUTOR.execute(new Runnable() {
            public void run() {
                try {
                    task.run();
                } finally {
                    synchronized (LOCK) {
                        pending--;
                        LOCK.notifyAll();
                    }
                }
            }
        });
    }

    /**
     * 等已提交的销毁任务全部结束。
     *
     * <p>MStar 老固件里 mstplayer 的 STM/DM 线程是进程级的：上一个实例的
     * {@code reset()} 还没走完 {@code uninit}，新实例 {@code prepareAsync} 就会
     * 直接返回 {@code [AVP] no input}（status=0x80000000）。重建播放器前先等旧实例
     * 真正销毁完，避免这种自伤。
     *
     * @return true 表示已清空；false 表示超时仍有任务在跑
     */
    static boolean awaitIdle(long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (LOCK) {
            while (pending > 0) {
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0L) {
                    return false;
                }
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
