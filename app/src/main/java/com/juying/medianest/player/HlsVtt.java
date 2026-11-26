package com.juying.medianest.player;

import com.juying.medianest.core.util.L;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NAS 转码会话里的 WebVTT 字幕 rendition（{@code subtitle.m3u8} + 每 2 秒一个 .vtt）。
 *
 * <p>背景（真机实测 2026-11-20，4K《超时空辉夜姬》内嵌 ASS 轨）：
 * <ul>
 *   <li>{@code /v/api/v1/subtitle/dl/<guid>} 对内嵌轨道一律 404，客户端拿不到原文；</li>
 *   <li>NAS 也不会把字幕烧进画面，但 {@code POST /play/play} 带上 {@code subtitle_guid}
 *       后会在会话目录里额外生成 {@code subtitle.m3u8}，与视频分片同编号（2 秒一段）；</li>
 *   <li>VTT 不依赖对应的 .ts 已请求：实测在新会话目录里直接拉 250.vtt 也能 200，
 *       因此可以提前铺开预取；但每个会话目录都要重新生成，冷启动/转码争用时会
 *       偶发挂起 6~20 秒（超过代理 6 秒读超时就报 502）。</li>
 * </ul>
 *
 * <p>cue 时间戳是整片绝对时间（与 /subtitle/dl 导出的 ASS 一致），
 * 解析后直接交给 libass 渲染即可，不需要额外时间偏移。
 *
 * <p>文本解析统一交给 {@link SubtitleText}（ExoPlayer 2.19.1 的纯 Java
 * WebvttDecoder），这里只负责拉取、去重、窗口裁剪。
 *
 * <p>并发与预取：单分片请求会卡住 NAS 的按需生成（实测 01356.vtt 挂 45s 时，
 * 后续 01357/01358 全被队头阻塞），因此严格串行、只发 1 路。为了不让
 * 「偶发挂起 6~20 秒」变成画面上的字幕空白，预取窗口拉到当前段 +30（60 秒）：
 * 播放到某一段时它的 VTT 早已在内存里，卡顿只会拖慢后面的预取，不影响出字。
 *
 * <p>线程：{@link #tick} / {@link #windowVtt} 在 UI 线程调用；下载在线程池里进行。
 */
public final class HlsVtt {

    /** 分片时长（NAS 的 HLS 固定 2 秒一片）。 */
    private static final long SEG_MS = 2000L;
    /** 失败后多久允许重试同一分片。 */
    private static final long RETRY_MS = 2000L;
    /**
     * 失败分片在「窗口滑过」后仍可补拉的时间。
     *
     * <p>实测（串行请求后）VTT 正常 30~100ms 返回，偶发挂起是 6 秒超时；
     * 一次失败后下一轮（6 秒）通常就能成功。12 秒足够覆盖两次重试，
     * 又不至于让早已播过的分片反复占用唯一的请求槽位（旧实现 45 秒时，
     * 一个落后 16 段的 01571 每 6 秒重试一次，纯粹浪费 NAS 的字幕生成队列）。</p>
     */
    private static final long LATE_RETRY_MS = 12000L;
    /** 最多记住多少个待补拉分片，防止 NAS 长时间异常时无限增长。 */
    private static final int MAX_RETRY = 48;
    /** 字幕窗口：当前时刻往前保留一点，往后铺 60 秒。 */
    private static final long WINDOW_BACK_MS = 2500L;
    private static final long WINDOW_AHEAD_MS = 60000L;
    /**
     * 同时只发 1 个 VTT 请求。
     *
     * <p>实测 NAS 的 VTT 生成是单队列的：续期时 5 个分片并行请求，每个都要等
     * 3~7 秒，超过代理 6 秒超时后被判失败，重试又继续占队列，形成活锁——
     * 日志里 00921~00924 连续几分钟超时就是这个原因。串行化之后单次请求
     * 约 1 秒就能返回（11:57:34 的补拉成功耗时 927ms），当前段也能稳定拿到。</p>
     */
    private static final int MAX_PARALLEL = 1;
    /**
     * 预取范围：当前段前 1 段（cue 跨段）到后 14 段（30 秒）。
     *
     * <p>真机对比（4K 片源 + 每 40 秒会话续期）：预取只有 2 段时，
     * 30 分钟里有 26 次 VTT 请求读超时（代理 6 秒），每次都要等下一轮重试，
     * 最长字幕空白 21 秒；直连 NAS 顺序拉取 23 个未来分片只花 0.4 秒，
     * 说明提前铺开几乎不增加负担，却能把偶发卡顿完全挡在画面之外。</p>
     */
    private static final int PREFETCH_BEHIND = 1;
    private static final int PREFETCH_AHEAD = 14;

    private final Object lock = new Object();
    private volatile long generation = 0L;
    private final List<SubtitleText.TextCue> cues = new ArrayList<SubtitleText.TextCue>();
    private final Set<String> cueKeys = new HashSet<String>();
    /** 分片序号 → -1 表示已成功；其它值表示最近一次尝试的时间。 */
    private final Map<Integer, Long> attempts = new HashMap<Integer, Long>();
    /**
     * 分片连续失败次数：失败的远段必须退避，不能每 2 秒就再占 6 秒超时，
     * 否则一个卡住的分片会把唯一的请求槽位占掉 3/4 时间，后面的预取永远铺不开。
     */
    private final Map<Integer, Integer> fails = new HashMap<Integer, Integer>();
    /** 正在下载的分片序号（最多 {@link #MAX_PARALLEL} 个）。 */
    private final Set<Integer> inFlight = new HashSet<Integer>();
    /**
     * 在途请求的连接句柄。seek 后旧位置的请求往往还要挂满 10 秒超时，
     * 而新位置的当前段马上就要出字；必要时直接 disconnect 让出唯一槽位，
     * 比干等超时快一个数量级。
     */
    private final Map<Integer, HttpURLConnection> conns =
            new HashMap<Integer, HttpURLConnection>();
    /** 拉取失败、但仍在 {@link #LATE_RETRY_MS} 补拉期内的分片（按序号升序补拉）。 */
    private final java.util.TreeSet<Integer> retry = new java.util.TreeSet<Integer>();
    /** 上一次 tick 的分片号：用来识别 seek/续期造成的跳转，跳转后不盲目回补上一段。 */
    private int lastTickSeg = -1;
    /** 当前正在播放的分片号，用于下载优先级判定（可被子线程读取）。 */
    private volatile int currentSeg = -1;
    /** 最近一次 tick 的播放位置，用来量 cue 迟到：cue 开始时间早于它多少毫秒。 */
    private volatile long lastTickSubMs = 0L;
    /** 诊断计数：迟到 >= 800ms 的 cue 条数与最大迟到。 */
    private volatile int statCueLate = 0;
    private volatile long statMaxCueLate = 0L;
    /**
     * 抢修窗口截止时间。seek / 会话续期后，旧位置的在途请求可能还要挂 10 秒，
     * 而新位置马上就要出字幕；这个窗口内允许「旧请求 + 当前段」并行，
     * 不让已经过期的请求继续堵住唯一的槽位。
     */
    private volatile long boostUntilMs = 0L;
    private volatile String base = "";
    private volatile boolean stopped = false;
    private java.util.concurrent.ExecutorService pool;
    private int loadedSegments = 0;
    /** 诊断计数：成功 / 失败 / 补拉成功 / 超期放弃。 */
    private volatile int statOk = 0;
    private volatile int statFail = 0;
    private volatile int statLateOk = 0;
    private volatile int statGiveUp = 0;

    /**
     * 更新会话目录（/v/media/&lt;hash&gt;/）。续期只换目录、内容相同，
     * 因此保留「已成功」标记避免整窗重拉；失败记录清掉，让新目录重新尝试。
     */
    public void setBase(String newBase) {
        if (newBase == null) {
            newBase = "";
        }
        if (newBase.equals(base)) {
            return;
        }
        base = newBase;
        resetForSessionRenewal();
        if (newBase.length() > 0) {
            L.i("HlsVtt", "会话目录 " + newBase);
        }
    }

    private List<HttpURLConnection> clearStateLocked() {
        List<HttpURLConnection> cancel = new ArrayList<HttpURLConnection>(conns.values());
        cues.clear();
        cueKeys.clear();
        attempts.clear();
        fails.clear();
        inFlight.clear();
        conns.clear();
        retry.clear();
        lastTickSeg = -1;
        currentSeg = -1;
        loadedSegments = 0;
        return cancel;
    }

    private void resetForSeek() {
        boostUntilMs = System.currentTimeMillis() + 12000L;
        List<HttpURLConnection> cancel;
        synchronized (lock) {
            generation++;
            cancel = clearStateLocked();
        }
        for (int i = 0; i < cancel.size(); i++) {
            try {
                cancel.get(i).disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private void resetForSessionRenewal() {
        boostUntilMs = System.currentTimeMillis() + 12000L;
        List<HttpURLConnection> cancel = new ArrayList<HttpURLConnection>();
        synchronized (lock) {
            generation++;
            cancel.addAll(conns.values());
            conns.clear();
            inFlight.clear();
            fails.clear();
            retry.clear();
            Iterator<Map.Entry<Integer, Long>> it = attempts.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().longValue() != -1L) {
                    it.remove();
                }
            }
        }
        for (int i = 0; i < cancel.size(); i++) {
            try {
                cancel.get(i).disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 开启抢修窗口：清掉旧位置的待补队列，短时间内允许当前段与在途旧请求并行。
     * 只在 seek 这种「旧请求已经没意义、新位置马上要出字」时调用。
     */
    private void boost(String reason) {
        boost(reason, true);
    }

    /**
     * @param clearRetry 是否清空待补队列。seek 时要清（旧位置的分片补了也没用）；
     *                   当前段失败时不清，否则会把还能用的待补项一起丢掉。
     */
    private void boost(String reason, boolean clearRetry) {
        boostUntilMs = System.currentTimeMillis() + 12000L;
        int dropped = 0;
        int killed = 0;
        List<HttpURLConnection> cancel = new ArrayList<HttpURLConnection>();
        synchronized (lock) {
            if (clearRetry) {
                dropped = retry.size();
                retry.clear();
            }
            // 只放弃「落在当前段之前」的在途请求：它们对 seek 后的新位置已无用，
            // 但窗口内的请求仍可能马上出字（比如 seek 只跳了 10 秒）。
            int cur = currentSeg;
            if (cur >= 0) {
                Iterator<Map.Entry<Integer, HttpURLConnection>> it = conns.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, HttpURLConnection> e = it.next();
                    if (e.getKey().intValue() < cur - 1) {
                        cancel.add(e.getValue());
                        it.remove();
                        // 被主动放弃的段按「已连续失败 3 次」退避，避免下一轮
                        // 立刻又抢回槽位把当前段挤出窗口。
                        fails.put(e.getKey(), Integer.valueOf(3));
                        killed++;
                    }
                }
            }
        }
        for (int i = 0; i < cancel.size(); i++) {
            try {
                cancel.get(i).disconnect();
            } catch (Throwable ignored) {
            }
        }
        L.i("HlsVtt", "抢修窗口 12 秒（" + reason + "）"
                + (clearRetry ? "，清空待补 " + dropped + " 段" : "")
                + (killed > 0 ? "，放弃旧位置在途请求 " + killed + " 个" : ""));
    }

    /** 播放器 seek 后立即调用：新位置的 VTT 优先，不被旧位置的超时请求堵住。 */
    public void onSeek() {
        if (!stopped) {
            resetForSeek();
        }
    }

    /** 会话只换目录、媒体时间轴不变：作废旧请求但保留已加载字幕。 */
    public void onSessionRenewed() {
        if (!stopped) {
            resetForSessionRenewal();
        }
    }

    /** 当前允许的并发数：正常 1 路，抢修窗口内 2 路。 */
    private int parallelLimit() {
        return System.currentTimeMillis() < boostUntilMs ? MAX_PARALLEL + 1 : MAX_PARALLEL;
    }

    /** UI 线程按当前播放位置触发：确保当前段附近的字幕分片都在路上。 */
    public void tick(long subTimeMs) {
        if (stopped || base.length() == 0) {
            return;
        }
        if (subTimeMs < 0) {
            subTimeMs = 0;
        }
        int seg = (int) (subTimeMs / SEG_MS);
        int prev = lastTickSeg;
        if (prev >= 0 && Math.abs(seg - prev) >= 3) {
            resetForSeek();
            prev = -1;
        }
        lastTickSeg = seg;
        currentSeg = seg;
        lastTickSubMs = subTimeMs;
        // 当前段优先级最高；然后是还没播到的后段（补拉一个已经播过的段，
        // 最多只能看到半句；预取后段能保证后面整句完整）；最后才是补拉。
        request(seg);
        for (int i = 1; i <= PREFETCH_AHEAD; i++) {
            request(seg + i);
        }
        // 只有顺序播放时才回补上一段：seek/续期后落点前一段往往根本
        // 没被转码，请求它只会白等超时（实测 00475.vtt 连续 4 次 6 秒超时）。
        if (prev >= 0 && seg - prev <= 2) {
            for (int i = 1; i <= PREFETCH_BEHIND; i++) {
                request(seg - i);
            }
        }
        // 最后补拉失败过的分片：它们已经滑出窗口，抢不到唯一的请求槽位
        // 也不会影响当前/后段字幕的完整性。
        retryStale(subTimeMs);
    }

    /**
     * 补拉已经滑出预取窗口、但失败时间还在 {@link #LATE_RETRY_MS} 内的分片。
     *
     * <p>只清「早已过期」的项，不因为一次失败就放弃：NAS 把 .ts 转码完成后
     * 才会生成对应 VTT，慢一拍是常态。</p>
     */
    private void retryStale(long subTimeMs) {
        if (retry.isEmpty()) {
            return;
        }
        synchronized (lock) {
            // 从最接近当前段的开始补：旧的升序会把「已经播过去很久」的分片
            // 排在最前，一个 6 秒超时就占满补拉槽位，拖住真正还来得及的段。
            Iterator<Integer> it = retry.descendingIterator();
            while (it.hasNext()) {
                int s = it.next().intValue();
                long segEnd = (s + 1L) * SEG_MS;
                if (segEnd < subTimeMs - LATE_RETRY_MS) {
                    it.remove(); // 已经过去太久，补上也没意义
                    statGiveUp++;
                    L.i("HlsVtt", "放弃补拉 分片 "
                            + String.format(java.util.Locale.US, "%05d.vtt", Integer.valueOf(s))
                            + "（已落后 " + (subTimeMs - segEnd) + "ms）");
                    continue;
                }
                if (inFlight.size() >= parallelLimit()) {
                    return; // 槽位占满，下一轮 tick 再补
                }
                attempt(s);
            }
        }
    }

    /** 生成当前时刻附近的 ASS 文档（libass 直接消费）；没有 cue 时返回空串。 */
    public String windowVtt(long subTimeMs) {
        synchronized (lock) {
            return SubtitleText.assDocument(cues,
                    subTimeMs - WINDOW_BACK_MS, subTimeMs + WINDOW_AHEAD_MS);
        }
    }

    public int cueCount() {
        synchronized (lock) {
            return cues.size();
        }
    }

    int segmentCount() {
        return loadedSegments;
    }

    /** 切换片源/关闭字幕时调用；之后必须先 setBase 才能继续工作。 */
    void reset() {
        List<HttpURLConnection> cancel;
        synchronized (lock) {
            generation++;
            cancel = clearStateLocked();
        }
        for (int i = 0; i < cancel.size(); i++) {
            try {
                cancel.get(i).disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    public void stop() {
        stopped = true;
        List<HttpURLConnection> cancel = new ArrayList<HttpURLConnection>();
        synchronized (lock) {
            generation++;
            attempts.clear();
            fails.clear();
            inFlight.clear();
            cancel.addAll(conns.values());
            conns.clear();
            retry.clear();
        }
        // 关掉活跃连接：阻塞在 socket 上的读线程不响应 interrupt，只能 disconnect。
        for (int i = 0; i < cancel.size(); i++) {
            try {
                cancel.get(i).disconnect();
            } catch (Throwable ignored) {
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void request(final int seg) {
        if (seg < 0) {
            return;
        }
        synchronized (lock) {
            attempt(seg);
        }
    }

    /** 是否可以再发起一次下载（持有 {@link #lock} 时调用）：可以则入队并登记时间。 */
    private void attempt(final int seg) {
        if (seg < 0) {
            return;
        }
        final String dir = base;
        if (dir.length() == 0) {
            return;
        }
        Long last = attempts.get(Integer.valueOf(seg));
        long now = System.currentTimeMillis();
        int cur = currentSeg;
        boolean urgent = cur >= 0 && seg >= cur - 1 && seg <= cur + 2;
        if (last != null) {
            if (last.longValue() < 0L) {
                return; // 已成功
            }
            int n = fails.containsKey(Integer.valueOf(seg))
                    ? fails.get(Integer.valueOf(seg)).intValue() : 0;
            // 当前/临近段照常 2 秒重试（马上要出画）；远段指数退避，
            // 让卡住的分片别把唯一槽位一直占住。
            long wait = urgent ? RETRY_MS : (RETRY_MS << Math.min(n, 3));
            if (now - last.longValue() < wait) {
                return;
            }
        }
        if (inFlight.contains(Integer.valueOf(seg))) {
            return;
        }
        boolean isCurrent = cur >= 0 && seg == cur;
        // 抢修窗口内不做过期/后段的限流：此时旧请求已无意义，当前段必须能插队。
        if (!isCurrent && cur >= 0 && System.currentTimeMillis() >= boostUntilMs) {
            // 续期后 NAS 还有一堆旧段在超时重试；它们每个都要占槽位 6 秒。
            // 这里限制「过期段最多 1 路」「当前段尚未到达时后段最多 1 路」，
            // 保证当前段永远有槽位，不再被补拉请求队头阻塞。
            int staleN = 0;
            int aheadN = 0;
            for (Iterator<Integer> it = inFlight.iterator(); it.hasNext(); ) {
                int s = it.next().intValue();
                if (s < cur - 1) {
                    staleN++;
                } else if (s > cur + 1) {
                    aheadN++;
                }
            }
            if (seg < cur - 1 && staleN >= 1) {
                return;
            }
            if (seg > cur + 1 && aheadN >= 1 && !isLoadedLocked(cur)) {
                return;
            }
        }
        // NAS 的 VTT 生成是单队列：并发只会让所有请求都超时，串行反而更快。
        // 只有 seek/续期后的抢修窗口例外（旧请求已经没意义，不能继续堵路）。
        if (inFlight.size() >= parallelLimit()) {
            return;
        }
        attempts.put(Integer.valueOf(seg), Long.valueOf(now));
        inFlight.add(Integer.valueOf(seg));
        if (pool == null) {
            // 多留 1 个线程：串行请求下也避免调度器和网络读阻塞彼此等待。
            pool = java.util.concurrent.Executors.newFixedThreadPool(MAX_PARALLEL + 1,
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "fntv-hls-vtt");
                            t.setDaemon(true);
                            return t;
                        }
                    });
        }
        final long requestGeneration = generation;
        pool.execute(new Runnable() {
            public void run() {
                fetch(dir, seg, requestGeneration);
            }
        });
    }

    /** 该分片是否已成功拉到（持有 {@link #lock} 时调用）。 */
    private boolean isLoadedLocked(int seg) {
        Long v = attempts.get(Integer.valueOf(seg));
        return v != null && v.longValue() < 0L;
    }

    /** 诊断：一条可读的拉取统计，随 SubSync 日志周期性输出。 */
    public String stats() {
        synchronized (lock) {
            return "vtt[ok=" + statOk + " fail=" + statFail + " late=" + statLateOk
                    + " giveup=" + statGiveUp + " pending=" + retry.size()
                    + " miss=" + missingLocked() + " cueLate=" + statCueLate
                    + "/" + statMaxCueLate + "ms cues=" + cues.size() + "]";
        }
    }

    /**
     * 该播放位置所在分片是否已成功拿到。
     *
     * <p>画面没字幕时用它区分两种情况：分片已在内存里 = 这一句本来就没有字幕；
     * 分片还没拿到 = 真的缺料，需要等补拉或修预取。
     */
    public boolean hasSegmentAt(long subTimeMs) {
        if (subTimeMs < 0) {
            subTimeMs = 0;
        }
        int seg = (int) (subTimeMs / SEG_MS);
        synchronized (lock) {
            Long a = attempts.get(Integer.valueOf(seg));
            return a != null && a.longValue() < 0L;
        }
    }

    /**
     * 当前播放位置到预取窗口末尾之间，还有多少分片没有成功拿到。
     *
     * <p>用来区分「字幕消失」和「本来就没人说话」：画面没有字幕但 miss=0，
     * 说明这一段的 VTT 已经在内存里、只是没有 cue；miss&gt;0 才是真的缺料。
     */
    private int missingLocked() {
        int cur = currentSeg;
        if (cur < 0) {
            return 0;
        }
        int miss = 0;
        for (int s = Math.max(0, cur - 1); s <= cur + PREFETCH_AHEAD; s++) {
            Long a = attempts.get(Integer.valueOf(s));
            if (a == null || a.longValue() >= 0L) {
                miss++;
            }
        }
        return miss;
    }

    private void fetch(String dir, int seg, long requestGeneration) {
        String name = String.format(java.util.Locale.US, "%05d.vtt", Integer.valueOf(seg));
        long t0 = System.currentTimeMillis();
        Fetched res = httpGet(seg, dir + name, requestGeneration);
        long cost = System.currentTimeMillis() - t0;
        if (requestGeneration != generation) {
            return;
        }
        byte[] data = res.data;
        if (data == null) {
            int cur = currentSeg;
            boolean urgent = cur >= 0 && seg >= cur - 1 && seg <= cur + 2;
            synchronized (lock) {
                if (requestGeneration != generation) {
                    return;
                }
                statFail++;
                inFlight.remove(Integer.valueOf(seg));
                Integer f = fails.get(Integer.valueOf(seg));
                fails.put(Integer.valueOf(seg), Integer.valueOf(f == null ? 1 : f.intValue() + 1));
                // 关键：失败的分片必须留在补拉队列里。窗口只有 6 秒左右，
                // 一次慢请求就会把它挤出去；不留记录就等于永久丢这段字幕。
                if (!stopped) {
                    retry.add(Integer.valueOf(seg));
                    while (retry.size() > MAX_RETRY) {
                        retry.pollFirst();
                    }
                }
            }
            L.d("HlsVtt", "分片 " + name + " 拉取失败 status=" + res.status
                    + " 耗时=" + cost + "ms 当前段=" + currentSeg);
            // 当前段/马上要播的段失败时开抢修窗口（不清待补队列）：
            // 一次 10 秒挂起已经把预取挡住，再接一个 10 秒就真的要缺字幕了。
            if (urgent && !stopped) {
                boost("当前段 " + seg + " 失败", false);
            }
            return;
        }
        // 统一走 BOM 识别（UTF-8 / UTF-16 LE / BE），NAS 正常下发 UTF-8
        String text = com.juying.medianest.core.util.NetUtil.decodeText(data);
        List<SubtitleText.TextCue> parsed = parse(text);
        boolean late = false;
        // cue 迟到 = 拿到这段 VTT 时，cue 的开始时间已经过去多久。
        // 这是「字幕落后于画面」的直接证据：> 0 说明是拉取滞后，不是时间轴偏移。
        long nowSub = lastTickSubMs;
        long maxCueLate = 0L;
        int lateCues = 0;
        synchronized (lock) {
            if (requestGeneration != generation) {
                return;
            }
            for (int i = 0; i < parsed.size(); i++) {
                SubtitleText.TextCue c = parsed.get(i);
                String key = c.startMs + "|" + c.endMs + "|" + c.assText();
                if (cueKeys.add(key)) {
                    cues.add(c);
                    long d = nowSub - c.startMs;
                    if (d >= 800L) {
                        lateCues++;
                        if (d > maxCueLate) {
                            maxCueLate = d;
                        }
                    }
                }
            }
            java.util.Collections.sort(cues, new java.util.Comparator<SubtitleText.TextCue>() {
                public int compare(SubtitleText.TextCue a, SubtitleText.TextCue b) {
                    if (a.startMs != b.startMs) {
                        return a.startMs < b.startMs ? -1 : 1;
                    }
                    return 0;
                }
            });
            attempts.put(Integer.valueOf(seg), Long.valueOf(-1L));
            fails.remove(Integer.valueOf(seg));
            inFlight.remove(Integer.valueOf(seg));
            late = retry.remove(Integer.valueOf(seg));
        }
        statOk++;
        if (late) {
            statLateOk++;
        }
        loadedSegments++;
        if (lateCues > 0) {
            statCueLate += lateCues;
            if (maxCueLate > statMaxCueLate) {
                statMaxCueLate = maxCueLate;
            }
        }
        L.i("HlsVtt", (late ? "补拉成功 " : "") + "分片 " + name + " cues=" + parsed.size()
                + " 累计=" + cueCount() + " 分片=" + loadedSegments
                + " 耗时=" + cost + "ms 当前段=" + currentSeg
                + (lateCues > 0 ? " 迟到cue=" + lateCues + " 最大迟到=" + maxCueLate + "ms" : ""));
    }

    /** 一次 VTT 请求的结果：status=-1 表示连接/读取异常（超时等）。 */
    private static final class Fetched {
        byte[] data;
        int status;
    }

    /** VTT 很小，读超时 12 秒（比代理的 10 秒长，让代理先如实收尾）。 */
    private Fetched httpGet(int seg, String url, long requestGeneration) {
        HttpURLConnection conn = null;
        Fetched out = new Fetched();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            // 必须比代理的 VTT 读超时（10 秒）长：代理负责先收尾并如实返回，
            // 客户端提前 2 秒放弃只会让代理继续等、自己再排一次队。
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Connection", "close");
            synchronized (lock) {
                if (requestGeneration != generation) {
                    out.status = -1;
                    return out;
                }
                conns.put(Integer.valueOf(seg), conn);
            }
            out.status = conn.getResponseCode();
            if (out.status != 200) {
                return out;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > 512 * 1024) {
                    break;
                }
            }
            in.close();
            out.data = buf.toByteArray();
            return out;
        } catch (Throwable t) {
            out.status = -1;
            return out;
        } finally {
            synchronized (lock) {
                if (conns.get(Integer.valueOf(seg)) == conn) {
                    conns.remove(Integer.valueOf(seg));
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 解析一段 WebVTT（HLS 分片）：交给 ExoPlayer 的 WebvttDecoder，
     * X-TIMESTAMP-MAP 偏移在 {@link SubtitleText} 里折算。
     */
    static List<SubtitleText.TextCue> parse(String text) {
        return SubtitleText.parse(text);
    }

}
