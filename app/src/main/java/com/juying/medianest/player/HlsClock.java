package com.juying.medianest.player;

import com.juying.medianest.core.util.L;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * HLS 播放列表时间轴 ↔ 分片真实 PTS 的偏差测量器。
 *
 * 背景（实测）：NAS 转码出来的 preset.m3u8 每个分片固定标 #EXTINF:6.000000，
 * 但分片真实时长由源关键帧决定（23.976fps × 144 帧 = 6.006s）。MediaPlayer
 * 按播放列表时间推进 getCurrentPosition()，与画面真实 PTS 会差到 1~2 秒
 * （实测最大 -1.9s，且随分片缓慢变化）。表现就是「字幕整体偏早，越放越对不上」。
 *
 * 这里不猜：直接取当前分片的 head 解析第一个视频 PES 的 PTS，与播放列表给它的
 * 起始时间相减，得到偏差 skew；字幕渲染时用 pos - skew，回到画面真实时间。
 */
public final class HlsClock {

    private final Object lock = new Object();
    /** 探测读多少字节：先读 16K 找第一帧（绝大多数分片够用），没有再增量读到上限。 */
    private static final int MIN_PROBE_BYTES = 16 * 1024;
    private static final int MAX_PROBE_BYTES = 96 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** 偏差小于这个值时当作测量噪声，不修正。 */
    private static final long DEADBAND_MS = 200L;
    /**
     * 周期采样间隔。播放列表标称时长与分片真实 PTS 的偏差会在分片边界跳变
     * （实测偶发约 1.8s），间隔越短跳变后的失准窗口越小；配合「换分片立刻采样」
     * 把窗口压到一次网络往返。
     */
    private static final long SAMPLE_INTERVAL_MS = 5000L;
    private static final long MAX_SKEW_AGE_MS = 10000L;

    private volatile long skewMs = 0L;
    private volatile long epoch = 0L;
    private volatile long lastSampleAt = 0L;
    private volatile int lastSegIndex = -1;
    private volatile int lastPtsMs = -1;
    private volatile int lastSegStartMs = -1;
    /** 播放列表分片起始时间（毫秒），index 与 uri 一一对应。 */
    private volatile int[] starts = null;
    private volatile String[] uris = null;
    private volatile String baseDir = "";
    private volatile boolean sampling = false;
    private volatile boolean stopped = false;
    private volatile long lastAttemptAt = 0L;
    /** 起播阶段先让出带宽给视频分片，禁止采样（避免再拉一份 115KB 播放列表）。 */
    private volatile long holdUntilMs = 0L;
    private long lastLoadAt = 0L;

    /** 起播/续播后调用：一段时间内不做播放列表与分片探测。 */
    public void holdOff(long ms) {
        holdUntilMs = Math.max(holdUntilMs, System.currentTimeMillis() + ms);
    }

    /**
     * 会话续期/降档重建、seek 之后调用：作废分片定位与采样，
     * 但<b>保留上次 skew</b>。新会话/新分片的真实偏差通常一秒内就能测到，
     * 期间沿用旧值比归零更准（实测 JOJO 的偏差有 1.8 秒，归零会让字幕
     * 在每次续期后先错 1~2 秒再跳回来）。
     */
    public void invalidateSegment() {
        synchronized (lock) {
            epoch++;
            sampling = false;
            lastSegIndex = -1;
            lastPtsMs = -1;
            lastSegStartMs = -1;
            starts = null;
            uris = null;
            baseDir = "";
            lastLoadAt = 0L;
            lastAttemptAt = 0L;
        }
    }

    public void stop() {
        stopped = true;
        invalidateSegment();
    }

    /** 当前生效的修正量（毫秒）。 */
    public long skewMs() {
        if (lastSampleAt <= 0L
                || System.currentTimeMillis() - lastSampleAt > MAX_SKEW_AGE_MS) {
            return 0L;
        }
        return Math.abs(skewMs) < DEADBAND_MS ? 0L : skewMs;
    }

    boolean hasMeasurement() {
        return lastSampleAt > 0L
                && System.currentTimeMillis() - lastSampleAt <= MAX_SKEW_AGE_MS;
    }

    /** 供日志输出。 */
    public String describe() {
        return "seg=" + lastSegIndex + " segStart=" + lastSegStartMs
                + " pts=" + lastPtsMs + " skew=" + skewMs;
    }

    /**
     * 异步采样：posMs 用来定位当前分片；playlistUrl 是 MediaPlayer 正在播的 m3u8。
     * 每次采样都会重新拉一次播放列表与会话续期后的分片地址，避免会话重建后失效。
     */
    public void sampleAsync(final String playlistUrl, final int posMs) {
        if (stopped || playlistUrl == null || playlistUrl.length() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < holdUntilMs) {
            return;
        }
        final long sampleEpoch;
        synchronized (lock) {
            if (sampling) {
                return;
            }
            int[] st = starts;
            // 播放列表还没拿到时快速重试；正常播放每 5 秒采样一次。
            long interval = st == null ? 1200L : SAMPLE_INTERVAL_MS;
            if (now - lastAttemptAt < interval) {
                return;
            }
            lastAttemptAt = now;
            sampling = true;
            sampleEpoch = epoch;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    sample(playlistUrl, posMs, sampleEpoch);
                } catch (Throwable t) {
                    L.w("SubSync", "分片时间基准采样失败：" + t);
                } finally {
                    synchronized (lock) {
                        if (sampleEpoch == epoch) {
                            sampling = false;
                        }
                    }
                }
            }
        }, "hls-clock").start();
    }

    private void sample(String playlistUrl, int posMs, long sampleEpoch) throws Exception {
        if (stopped || sampleEpoch != epoch) {
            return;
        }
        int[] st = starts;
        String[] ur = uris;
        if (st == null || ur == null || st.length == 0) {
            loadPlaylist(playlistUrl, sampleEpoch);
            if (sampleEpoch != epoch) {
                return;
            }
            st = starts;
            ur = uris;
            if (st == null || ur == null || st.length == 0) {
                return;
            }
        }
        int idx = segmentOf(st, posMs);
        if (idx < 0 || idx >= ur.length) {
            return;
        }
        String segUrl = ur[idx];
        int[] probe = probePts(segUrl);
        int pts = probe[0];
        if (pts < 0) {
            L.w("SubSync", "分片 " + idx + " 未解析到视频 PTS（" + probe[1] + " 字节）");
            return;
        }
        if (sampleEpoch != epoch) {
            return;
        }
        int segStart = st[idx];
        long skew = (long) segStart - pts;
        // 只接受合理范围，防止解析到音频/异常值把字幕整体拉飞
        if (skew < -5000L || skew > 5000L) {
            L.w("SubSync", "分片 " + idx + " 偏差异常（而是 " + skew + "ms），忽略");
            return;
        }
        synchronized (lock) {
            if (sampleEpoch != epoch) {
                return;
            }
            lastSegIndex = idx;
            lastSegStartMs = segStart;
            lastPtsMs = pts;
            skewMs = skew;
            lastSampleAt = System.currentTimeMillis();
            L.i("SubSync", "pos=" + posMs + "ms " + describe()
                    + " sub=" + (posMs - skew) + "ms 分片=" + probe[1] + "B");
        }
    }

    private void loadPlaylist(String playlistUrl, long sampleEpoch) throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastLoadAt < 5000L && starts != null) {
            return;
        }
        lastLoadAt = now;
        loadPlaylist(playlistUrl, 0, sampleEpoch);
    }

    private void loadPlaylist(String playlistUrl, int depth, long sampleEpoch) throws Exception {
        String text = new String(readAll(playlistUrl), UTF_8);
        List<Integer> st = new ArrayList<Integer>();
        List<String> us = new ArrayList<String>();
        int cumulative = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String num = comma > 8 ? line.substring(8, comma) : line.substring(8);
                double sec;
                try {
                    sec = Double.parseDouble(num.trim());
                } catch (Throwable t) {
                    sec = 6.0;
                }
                st.add(cumulative);
                cumulative += (int) Math.round(sec * 1000.0);
            } else if (line.length() > 0 && !line.startsWith("#")) {
                us.add(resolve(playlistUrl, line));
            }
        }
        // 续期/seek 之后 NAS 可能返回 master 播放列表（只有一行变体地址），跟进去
        if (st.isEmpty() && us.size() == 1 && depth < 2) {
            loadPlaylist(us.get(0), depth + 1, sampleEpoch);
            return;
        }
        if (st.size() != us.size() || st.isEmpty()) {
            String head = text.length() > 120 ? text.substring(0, 120) : text;
            L.w("SubSync", "播放列表解析失败：seg=" + st.size() + " uri=" + us.size()
                    + " body=" + head.replace('\n', ' '));
            return;
        }
        int[] a = new int[st.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = st.get(i);
        }
        synchronized (lock) {
            if (sampleEpoch != epoch) {
                return;
            }
            starts = a;
            uris = us.toArray(new String[us.size()]);
            baseDir = playlistUrl;
            L.i("SubSync", "播放列表已加载：" + a.length + " 个分片，末段起点=" + a[a.length - 1] + "ms");
        }
    }

    private static String resolve(String base, String uri) {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return uri;
        }
        if (uri.startsWith("/")) {
            int p = base.indexOf("://");
            int slash = p < 0 ? -1 : base.indexOf('/', p + 3);
            return slash < 0 ? uri : base.substring(0, slash) + uri;
        }
        int q = base.lastIndexOf('/');
        return q < 0 ? uri : base.substring(0, q + 1) + uri;
    }

    private static int segmentOf(int[] starts, int posMs) {
        int lo = 0;
        int hi = starts.length - 1;
        if (posMs < starts[0]) {
            return 0;
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= posMs) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * 取分片开头并解析第一个视频 PES 的 PTS：读到 MIN_PROBE_BYTES 就先试一次，
     * 命中即断开（多数分片只花 16K 流量），未命中继续读到 MAX_PROBE_BYTES。
     *
     * @return {ptsMs, 实际读取字节数}；未解析到时 ptsMs = -1。
     */
    private static int[] probePts(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Range", "bytes=0-" + (MAX_PROBE_BYTES - 1));
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(MIN_PROBE_BYTES);
            byte[] buf = new byte[8192];
            int n;
            while (out.size() < MAX_PROBE_BYTES && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() >= MIN_PROBE_BYTES) {
                    int pts = firstVideoPtsMs(out.toByteArray());
                    if (pts >= 0) {
                        in.close();
                        return new int[]{pts, out.size()};
                    }
                }
            }
            in.close();
            return new int[]{-1, out.size()};
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** 从 TS 里找第一个带 PTS 的视频 PES，返回毫秒。 */
    static int firstVideoPtsMs(byte[] b) {
        int i = 0;
        while (i + 188 <= b.length) {
            if (b[i] != 0x47) {
                i++;
                continue;
            }
            int pusi = b[i + 1] & 0x40;
            int afc = (b[i + 3] >> 4) & 0x3;
            int off = i + 4;
            if ((afc & 2) != 0) {
                off += 1 + (b[i + 4] & 0xFF);
            }
            if (pusi != 0 && off + 14 <= b.length
                    && b[off] == 0 && b[off + 1] == 0 && b[off + 2] == 1) {
                int sid = b[off + 3] & 0xFF;
                if (sid >= 0xE0 && sid <= 0xEF) {
                    int flags = b[off + 7] & 0xFF;
                    if ((flags & 0x80) != 0) {
                        long p0 = b[off + 9] & 0xFF;
                        long p1 = b[off + 10] & 0xFF;
                        long p2 = b[off + 11] & 0xFF;
                        long p3 = b[off + 12] & 0xFF;
                        long p4 = b[off + 13] & 0xFF;
                        long pts = ((p0 >> 1) & 0x07) << 30
                                | (p1 << 22)
                                | ((p2 >> 1) & 0x7F) << 15
                                | (p3 << 7)
                                | (p4 >> 1);
                        return (int) (pts / 90L);
                    }
                }
            }
            i += 188;
        }
        return -1;
    }
}
