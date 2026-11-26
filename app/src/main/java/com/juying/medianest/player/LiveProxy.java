package com.juying.medianest.player;

import com.juying.medianest.core.util.L;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 直播线路本地代理。
 *
 * <p>背景（旧电视真机实测）：飞牛返回的直播线路全部是第三方 CDN 的 https 地址，
 * 入口地址没有 .m3u8 后缀、需要 302 跳转后才拿到真正的 media playlist。
 * TCL/MStar Android 4.2.1 的厂商播放器拿到 https 地址会在 libmmp 的 android_http_play 里
 * native 崩溃（media.player 服务直接 died）；而系统 Java 的 TLS 栈只支持 CBC/3DES/RC4，
 * 与 CDN 的现代 TLS 握手失败。
 *
 * <p>所以直播统一改走本机回环 HTTP：本代理用内置 Conscrypt（BoringSSL）完成 TLS，
 * 跟随 302，把 master 拍平成 media playlist，并把分片/密钥 URI 全部改写为
 * http://127.0.0.1:port 下的本地地址，厂商播放器只需要处理普通 HTTP HLS。
 *
 * <p>注意两点实现约束：
 * <ul>
 *   <li>厂商 M3U8 解析器只等约 2 秒，所以注册线路后立刻后台预取 playlist，
 *       请求时直接返回缓存并异步刷新，避免回源握手耗时把起播拖死；</li>
 *   <li>不用 HttpURLConnection —— 4.2 上它配合自定义 SSL 工厂会卡死不返回，
 *       这里用原始 socket 自己发 HTTP/1.1 请求（与真机验证过的探测脚本一致）。</li>
 * </ul>
 *
 * <p>只用于公开的直播流（令牌本身就是 CDN 访问凭据），不涉及 NAS 鉴权；
 * NAS 的 Authorization/token 仍由 AuthProxy 负责，互不影响。
 */
public final class LiveProxy {

    private static final String TAG = "LiveProxy";
    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.Charset.forName("UTF-8");
    private static final java.nio.charset.Charset ISO_8859_1 =
            java.nio.charset.Charset.forName("ISO-8859-1");
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_TRACKED_URLS = 4000;
    private static final long CACHE_FRESH_MS = 10000L;
    /** 最短回源间隔：厂商播放器会每秒轮询 playlist，太频繁会被 CDN 限流（实测 429/重置）。 */
    private static final long MIN_REFRESH_INTERVAL_MS = 5000L;
    private static final int PLAYLIST_WAIT_MS = 3500;
    /**
     * 302 后的 media playlist 地址复用时长。
     * 实测同一个入口每次 302 会随机落到 EdgeOne / Cloudflare 两个 CDN：播放中途换了 CDN，
     * 下一个分片就要重新握手+重新建会话，经常直接超时（Downloader: DOWNLOAD_TIMEOUT）。
     * 所以一条线路一旦定下来就一直用这个直连地址，只有它真的失效才回入口重新 302。
     */
    private static final long MEDIA_URL_TTL_MS = 30 * 60 * 1000L;
    /** playlist 回源失败重试：EdgeOne 首次请求会 429 并下发 acw_tc cookie，带上 cookie 立刻重试才会通过。 */
    private static final int REFRESH_ATTEMPTS = 3;
    private static final long REFRESH_BACKOFF_MS = 800L;

    // 分片下载：实测该 CDN 单连接只有 30~150KB/s（远低于 3.7Mbps 的直播码率），
    // 但支持 Range，并行拉取可以线性提速。
    // 关键约束来自厂商播放器：日志 "flip first frame timeout (4 sec)" —— 起播后
    // 4 秒内必须拿到可解码的首帧，否则直接报 error(1,-5003)。
    // 4.7MB 的分片要在 4 秒内送完，需要 1.2MB/s 以上，所以多路并行、块压到 256KB。
    // 并发太高会把 CDN 打到 429（实测 12 路 + 多线路残留叠加时大面积 429/超时），
    // 这里压到 8 路，再用全局信号量把所有线路的总上游连接数也限在 8。
    private static final int SEG_FIRST_BYTES = 64 * 1024;
    private static final int SEG_CHUNK_BYTES = 256 * 1024;
    private static final int SEG_PARALLEL = 8;
    private static final long SEG_CHUNK_WAIT_MS = 10000L;
    /** 全局上游连接数上限（公平）：防止多线路预热/残留下载叠加触发 CDN 429。 */
    private static final java.util.concurrent.Semaphore SEG_CONN =
            new java.util.concurrent.Semaphore(8, true);
    /** 起播前把当前 playlist 的首分片整段预取到内存，瞬时吐给播放器，避开 MStar 4 秒首帧窗口。 */
    private static final long SEG_CACHE_TTL_MS = 30 * 1000L;
    private static final int SEG_CACHE_MAX_BYTES = 8 * 1024 * 1024;

    private static LiveProxy instance;

    public static synchronized LiveProxy get() {
        if (instance == null) {
            instance = new LiveProxy();
        }
        return instance;
    }

    private final Map<Integer, Channel> channels = new HashMap<Integer, Channel>();
    /** CDN 的会话 cookie（入口 302 下发），不带回去会被 EdgeOne 限流或重置连接。 */
    private final Map<String, String> cookieJar = new HashMap<String, String>();
    private final Object lock = new Object();
    private int nextId = 1;
    private ServerSocket server;
    private SSLSocketFactory sslFactory;
    private long lastFailLogAt = 0L;
    /** EdgeOne 分片连续失败次数：>=2 后新的 playlist 优先重新 302 挑 Cloudflare 节点。 */
    private volatile int eoFailCount = 0;

    private LiveProxy() {
    }

    /** 每条线路分配独立的 media-sequence 偏移：MStar mediaserver 是跨 MediaPlayer 实例的
     *  共享进程，会记住上一条线路的 iLastMediaSequence；换线后若新线路序列与之相等，
     *  MStar 会判定「已处理过」而拒绝下载分片（实测 mediaSequence == iLastMediaSequence 后缓冲只减不增）。
     *  给不同线路错开序列号空间即可让 MStar 把新线路当新流处理。 */
    private static final java.util.concurrent.atomic.AtomicLong sSeqBase =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long SEQ_OFFSET_STRIDE = 10_000_000L;

    private static final class Channel {
        final String upstream;
        final Map<String, String> urlToKey = new HashMap<String, String>();
        final Map<String, String> keyToUrl = new HashMap<String, String>();
        int nextKey = 1;
        volatile byte[] playlist;
        volatile long playlistAt;
        volatile long lastRefreshAt;
        volatile boolean refreshing;
        /** 302 之后的真实 CDN 主机，用于起播前判断线路质量。 */
        volatile String finalHost = "";
        /** 上一次解析出的 media playlist（master 拍平结果），刷新时优先复用以少一跳。 */
        String mediaUrl = "";
        long mediaUrlAt;
        /** 最近一次成功回源 playlist 的时刻；播放前用它判断线路是否迟迟拿不到列表。 */
        volatile long playlistOkAt;
        /** playlist 连续回源失败次数；达到阈值即可放弃这条线路，不必干等。 */
        volatile int playlistFailStreak;
        /** 分片连续回源失败次数（任一段成功即归零）。 */
        volatile int segFailStreak;
        /** 最近一次向播放器交付分片字节的时刻；用于判断「线路卡住了」而不是「起播慢」。 */
        volatile long lastByteAt = System.currentTimeMillis();
        /** 起播前预取的首分片缓存（每条线路一个槽位），命中即整段瞬时下发。 */
        volatile byte[] segCacheBytes;
        volatile String segCacheUrl;
        volatile long segCacheAt;
        /** 本线路 media-sequence 专属偏移，使不同线路序列号空间不重叠。 */
        final long seqOffset;

        Channel(String upstream) {
            this.upstream = upstream;
            this.seqOffset = sSeqBase.getAndIncrement() * SEQ_OFFSET_STRIDE;
        }

        synchronized String keyOf(String url) {
            String k = urlToKey.get(url);
            if (k != null) {
                return k;
            }
            if (urlToKey.size() > MAX_TRACKED_URLS) {
                // 直播分片地址是滑动窗口，清空后重新登记即可，不影响正在下载的请求
                urlToKey.clear();
                keyToUrl.clear();
            }
            k = String.valueOf(nextKey++);
            urlToKey.put(url, k);
            keyToUrl.put(k, url);
            return k;
        }

        synchronized String urlOf(String key) {
            return keyToUrl.get(key);
        }
    }

    public String baseUrl() {
        ServerSocket s;
        synchronized (lock) {
            s = server;
        }
        return s == null ? "" : "http://127.0.0.1:" + s.getLocalPort();
    }

    /** 该本地地址对应的 playlist 是否已经预热完成（播放器起播前用）。 */
    public boolean isPlaylistReady(String localUrl) {
        Channel ch = channelOf(localUrl);
        return ch != null && ch.playlist != null;
    }

    /**
     * playlist 是否已经确定拿不到（连续多次回源失败）。
     * 真机实测：EdgeOne 线路的分片几乎必然 ECONNRESET，CF 线路偶尔整段超时；
     * 这种情况继续等只会让用户看着「正在连接」发呆，直接换下一条更快。
     */
    public boolean hasPlaylistFailed(String localUrl) {
        Channel ch = channelOf(localUrl);
        return ch != null && ch.playlist == null && ch.playlistFailStreak >= 3;
    }

    /** 分片是否连续失败（线路已判定不可用）。 */
    public boolean isLineDead(String localUrl) {
        Channel ch = channelOf(localUrl);
        return ch != null && ch.segFailStreak >= 3;
    }

    /** 首分片是否已预取到内存（非 EO 线路起播前等它，命中即可瞬时出画，绕开 4 秒首帧窗口）。 */
    public boolean isFirstSegmentReady(String localUrl) {
        Channel ch = channelOf(localUrl);
        return ch != null && ch.segCacheBytes != null
                && System.currentTimeMillis() - ch.segCacheAt < SEG_CACHE_TTL_MS;
    }

    /** 这条线路是否落在已知必挂的 EdgeOne 上：不必等首分片，直接起播让它快速失败换线。 */
    public boolean isEdgeOneLine(String localUrl) {
        Channel ch = channelOf(localUrl);
        return ch != null && isEdgeOne(ch.finalHost);
    }

    /** 距离上一次成功交付分片字节过去了多久；-1 表示没有该线路。 */
    public long idleSinceLastByteMs(String localUrl) {
        Channel ch = channelOf(localUrl);
        return ch == null ? -1L : System.currentTimeMillis() - ch.lastByteAt;
    }

    /**
     * 播放器开始使用这条线路：把「最近活动」重置到现在。
     * 否则一条线路 playlist 早就预热好了、播放器几分钟后才轮到它，
     * 会被误判成「长时间无数据」而直接判死（真机实测换线时就会这样）。
     */
    public void markPlaying(String localUrl) {
        Channel ch = channelOf(localUrl);
        if (ch != null) {
            ch.lastByteAt = System.currentTimeMillis();
        }
    }

    private Channel channelOf(String localUrl) {
        if (localUrl == null || localUrl.length() == 0) {
            return null;
        }
        int at = localUrl.lastIndexOf("/live/");
        if (at < 0) {
            return null;
        }
        int from = at + "/live/".length();
        int dot = localUrl.indexOf('.', from);
        try {
            int id = Integer.parseInt(localUrl.substring(from, dot > from ? dot : localUrl.length()));
            synchronized (channels) {
                return channels.get(Integer.valueOf(id));
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    /** EdgeOne 节点：playlist 能取到、分片一律 ECONNRESET（真机 + PC 均复现）。 */
    private static boolean isEdgeOne(String urlOrHost) {
        return urlOrHost != null && urlOrHost.indexOf("darwinchow") >= 0;
    }

    /** 记录分片失败，并累计「这条 CDN 是否整段不可用」的证据。 */
    private void noteSegFail(Channel ch, String url) {
        ch.segFailStreak++;
        if (isEdgeOne(url)) {
            eoFailCount++;
        }
    }

    /** 分片成功：说明当前 CDN 可用，清掉 EdgeOne 的坏记录。 */
    private void noteSegOk(Channel ch) {
        ch.segFailStreak = 0;
        ch.lastByteAt = System.currentTimeMillis();
        if (ch.finalHost.length() > 0 && !isEdgeOne(ch.finalHost)) {
            eoFailCount = 0;
        }
    }

    /**
     * 把直播直链登记到本地代理，返回可交给播放器的本地 m3u8 地址。
     * 代理启动失败时返回原地址（保持原有行为，不阻塞播放）。
     */
    public String localize(String upstream) {
        if (upstream == null || !upstream.startsWith("http")) {
            return upstream;
        }
        if (!start()) {
            return upstream;
        }
        final Channel ch = new Channel(upstream);
        int id;
        synchronized (channels) {
            id = nextId++;
            channels.put(Integer.valueOf(id), ch);
        }
        final String local = baseUrl() + "/live/" + id + ".m3u8";
        L.i(TAG, "直播线路已接入本地代理 " + local + " ← " + upstream);
        // 注意：这里不立即回源。直播一个频道有多条候选线路，全部同时预热会把
        // 第三方 CDN 打到 429/超时（实测 8 条候选并发预热后 playlist 要 10~60 秒）。
        // 由播放器在真正要试这条线路前调用 {@link #warm} 预热。
        return local;
    }

    /**
     * 预热某条线路的 playlist（可重复调用）。厂商 M3U8 解析器只等 2~4 秒，
     * 必须先拿到 playlist 再交给它，否则本次起播必然失败。
     */
    public void warm(String localUrl) {
        final Channel ch = channelOf(localUrl);
        if (ch == null || ch.playlist != null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 6; i++) {
                    refresh(ch);
                    if (ch.playlist != null) {
                        return;
                    }
                    try {
                        Thread.sleep(1200L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "fntv-live-warm");
        t.setDaemon(true);
        t.start();
    }

    private boolean start() {
        synchronized (lock) {
            if (server != null) {
                return true;
            }
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                server = s;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        acceptLoop();
                    }
                }, "fntv-live-proxy");
                t.setDaemon(true);
                t.start();
                L.i(TAG, "直播代理已启动 " + baseUrl());
                return true;
            } catch (Throwable t) {
                L.w(TAG, "直播代理启动失败：" + t);
                return false;
            }
        }
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket s;
            synchronized (lock) {
                s = server;
            }
            if (s == null) {
                return;
            }
            try {
                final Socket client = s.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        handle(client);
                    }
                }, "fntv-live-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                synchronized (lock) {
                    if (server == null) {
                        return;
                    }
                }
                L.w(TAG, "直播代理 accept 异常：" + t.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(25000);
            // 包头逐行读取：缓冲后避免每个字节一次 syscall
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                closeQuietly(client);
                return;
            }
            String range = null;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0 && "Range".equalsIgnoreCase(line.substring(0, c).trim())) {
                    range = line.substring(c + 1).trim();
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respondError(client, 400, "bad request");
                return;
            }
            // 厂商播放器的 HTTP 栈会先发 HEAD 探测，也可能使用 absolute-form 请求行
            boolean headOnly = "HEAD".equalsIgnoreCase(parts[0]);
            if (!headOnly && !"GET".equalsIgnoreCase(parts[0])) {
                respondError(client, 400, "bad method");
                return;
            }
            String path = parts[1];
            int schemeAt = path.indexOf("://");
            if (schemeAt > 0) {
                int slash = path.indexOf('/', schemeAt + 3);
                path = slash > 0 ? path.substring(slash) : "/";
            }
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            // 厂商播放器每秒轮询 playlist + 每段多次重试：逐条打印会把 logcat 冲掉
            //（实测 1 分钟内前面的起播/换线日志就全被挤出去了），这里不再记录。
            if (path.startsWith("/live/") && path.endsWith(".m3u8")) {
                servePlaylist(client, path, headOnly);
            } else if (path.startsWith("/u/")) {
                String key = path.substring(3);
                int dot = key.lastIndexOf('.');
                if (dot > 0) {
                    key = key.substring(0, dot);
                }
                serveResource(client, key, range, headOnly);
            } else {
                L.w(TAG, "未匹配路径 " + path);
                respondError(client, 404, "not found");
            }
        } catch (Throwable t) {
            L.w(TAG, "直播代理请求异常：" + t);
            closeQuietly(client);
        }
    }

    /** /live/&lt;id&gt;.m3u8：优先返回缓存，过期时后台刷新，保证厂商解析器能立刻拿到内容。 */
    private void servePlaylist(Socket client, String path, boolean headOnly) throws IOException {
        int id;
        try {
            id = Integer.parseInt(path.substring("/live/".length(), path.length() - ".m3u8".length()));
        } catch (Throwable t) {
            respondError(client, 404, "bad channel");
            return;
        }
        Channel ch;
        synchronized (channels) {
            ch = channels.get(Integer.valueOf(id));
        }
        if (ch == null) {
            respondError(client, 404, "channel gone");
            return;
        }
        byte[] body = ch.playlist;
        long now = System.currentTimeMillis();
        if (body == null) {
            body = awaitPlaylist(ch, PLAYLIST_WAIT_MS);
        } else if (now - ch.playlistAt > CACHE_FRESH_MS) {
            refreshAsync(ch);
        }
        if (body == null) {
            respondError(client, 502, "playlist not ready");
            return;
        }
        writeHeaders(client.getOutputStream(), "200 OK", "application/vnd.apple.mpegurl", body.length, null);
        if (!headOnly) {
            OutputStream os = client.getOutputStream();
            os.write(body);
            os.flush();
        }
        closeQuietly(client);
    }

    private byte[] awaitPlaylist(Channel ch, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        synchronized (ch) {
            while (ch.playlist == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    break;
                }
                try {
                    ch.wait(remain);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return ch.playlist;
        }
    }

    private void refreshAsync(final Channel ch) {
        if (ch.refreshing || System.currentTimeMillis() - ch.lastRefreshAt < MIN_REFRESH_INTERVAL_MS) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                refresh(ch);
            }
        }, "fntv-live-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void refresh(Channel ch) {
        synchronized (ch) {
            if (ch.refreshing) {
                return;
            }
            if (ch.playlist != null
                    && System.currentTimeMillis() - ch.lastRefreshAt < MIN_REFRESH_INTERVAL_MS) {
                return;
            }
            ch.refreshing = true;
            ch.lastRefreshAt = System.currentTimeMillis();
        }
        try {
            long t0 = System.currentTimeMillis();
            boolean hadPlaylist = ch.playlist != null;
            String cached = ch.mediaUrl;
            boolean fresh = cached.length() > 0
                    && System.currentTimeMillis() - ch.mediaUrlAt < MEDIA_URL_TTL_MS;
            Fetch f = fetchWithRetry(fresh ? cached : ch.upstream);
            if ((f == null || f.code != 200) && fresh) {
                // 缓存的 media 地址过期（令牌/路径变动），回入口重新 302
                ch.mediaUrl = "";
                f = fetchWithRetry(ch.upstream);
            }
            if (f == null || f.code != 200) {
                ch.playlistFailStreak++;
                L.w(TAG, "直播 playlist 回源失败 code=" + (f == null ? "null" : String.valueOf(f.code))
                        + " 连续 " + ch.playlistFailStreak + " 次");
                return;
            }
            // 真机实测：EdgeOne 节点的 playlist 永远成功、分片永远 ECONNRESET；
            // 同一入口重新 302 有一半概率落到 Cloudflare。已知 EO 不可用时再挑一次，
            // 把这条线路直接挪到能出片的 CDN 上，比死等分片失败再换线路快得多。
            if (eoFailCount >= 2 && isEdgeOne(f.finalUrl)) {
                ch.mediaUrl = "";
                Fetch g = fetchWithRetry(ch.upstream);
                if (g != null && g.code == 200 && !isEdgeOne(g.finalUrl)) {
                    L.i(TAG, "入口重新 302 避开 EdgeOne：" + hostOf(g.finalUrl));
                    f = g;
                }
            }
            String text = new String(f.body, UTF_8);
            String base = f.finalUrl;
            if (text.indexOf("#EXT-X-STREAM-INF") >= 0) {
                // MStar 的 HLS 解复用器只认 media playlist，把 master 拍平成首条变体
                String variant = firstVariant(text, base);
                if (variant.length() == 0) {
                    L.w(TAG, "直播 master playlist 无可用变体");
                    return;
                }
                ch.mediaUrl = variant;
                ch.mediaUrlAt = System.currentTimeMillis();
                Fetch mf = fetchWithRetry(variant);
                if (mf == null || mf.code != 200) {
                    L.w(TAG, "直播变体 playlist 回源失败 code=" + (mf == null ? "null" : String.valueOf(mf.code)));
                    return;
                }
                text = new String(mf.body, UTF_8);
                base = mf.finalUrl;
            }
            ch.mediaUrl = base;
            ch.mediaUrlAt = System.currentTimeMillis();
            ch.finalHost = hostOf(base);
            String rewritten = rewritePlaylist(ch, text, base);
            ch.playlist = rewritten.getBytes(UTF_8);
            ch.playlistAt = System.currentTimeMillis();
            ch.playlistOkAt = ch.playlistAt;
            ch.playlistFailStreak = 0;
            // playlist 也是「线路还活着」的证据：慢线路（实测首次 30 秒）刚拿到列表时
            // 不能因为距注册时间太久被播放器误判为死线。只认第一次拿到列表：
            // 播放中每秒都在轮询 playlist，若每次都刷新这个时间戳，
            // 「分片一直不通」的线路就永远不会被判死。
            if (!hadPlaylist) {
                ch.lastByteAt = ch.playlistAt;
            }
            L.i(TAG, "直播 playlist 已刷新 分片 " + count(rewritten, "/u/") + " 条 耗时 "
                    + (ch.playlistAt - t0) + "ms 线路=" + ch.finalHost);
            // 首次拿到 playlist 时，把首分片整段预取到内存：MStar 起播后只给 4 秒拿首帧，
            // 网络慢时在线下载 4.6MB 分片必然超时（实测 flip first frame timeout -> -5003），
            // 提前下好交给播放器就能瞬时出画。只在首次预热触发，播放中的轮询不再预取，避免给 CDN 加负。
            if (!hadPlaylist) {
                prefetchFirstSegmentAsync(ch, firstSegmentUrl(text, base));
            }
        } catch (Throwable t) {
            L.w(TAG, "直播 playlist 刷新异常：" + t);
        } finally {
            ch.refreshing = false;
            synchronized (ch) {
                ch.notifyAll();
            }
        }
    }

    /** 带退避的 playlist 回源：429 的响应里带 acw_tc cookie，下一次请求带上就能通过。 */
    private Fetch fetchWithRetry(String url) {
        Fetch f = null;
        for (int i = 0; i < REFRESH_ATTEMPTS; i++) {
            long t0 = System.currentTimeMillis();
            f = fetch(url, null, true);
            // 真机排障证据：CF 节点偶尔单次超时 12 秒，日志里要能区分「慢」和「被拒」
            L.d(TAG, "playlist 回源第 " + (i + 1) + " 次 code="
                    + (f == null ? "null" : String.valueOf(f.code))
                    + " 用时 " + (System.currentTimeMillis() - t0) + "ms 线路=" + hostOf(url));
            if (f != null && f.code == 200) {
                return f;
            }
            if (i < REFRESH_ATTEMPTS - 1) {
                try {
                    Thread.sleep(REFRESH_BACKOFF_MS * (i + 1));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return f;
    }

    private void serveResource(Socket client, String key, String range, boolean headOnly) throws IOException {
        Channel ch;
        String url;
        synchronized (channels) {
            ch = channelWithKey(key);
            url = ch == null ? null : ch.urlOf(key);
        }
        if (url == null) {
            respondError(client, 404, "resource gone");
            return;
        }
        // 命中起播前预取的首分片缓存：整段已在内存，瞬时吐给播放器，绕开在线下载的首帧窗口。
        byte[] cached = url.equals(ch.segCacheUrl) ? ch.segCacheBytes : null;
        if (cached != null && System.currentTimeMillis() - ch.segCacheAt < SEG_CACHE_TTL_MS) {
            serveCached(client, ch, url, cached);
            return;
        }
        // 先用小 Range 探测：拿到分片总长，同时这一块数据可以立刻回吐给播放器。
        // 该 CDN 单连接限速严重，后续按块并行取，否则 10 秒的分片要下几十秒。
        Response probe = httpGet(url, "bytes=0-" + (SEG_FIRST_BYTES - 1), SEG_FIRST_BYTES);
        if (probe != null && probe.code == 206) {
            long total = totalOf(probe.contentRange);
            if (total > 0) {
                noteSegOk(ch);
                serveRanged(ch, client, url, probe, total, headOnly);
                return;
            }
        }
        if (probe != null && probe.code >= 400) {
            // 429/5xx 等错误响应体绝不能当分片数据写给厂商播放器（会被写进解复用器）
            noteSegFail(ch, url);
            L.w(TAG, "分片探测失败 code=" + probe.code + " 连续 " + ch.segFailStreak
                    + " 次，断开客户端连接 " + hostOf(url));
            closeQuietly(client);
            return;
        }
        // 上游不支持 Range（或探测异常）：退回整段拉取
        Fetch f = fetch(url, range, false);
        if (f == null || f.code >= 400) {
            // 注意：分片失败绝不能把错误文本当响应体写给厂商播放器，
            // 它会把 "upstream error" 直接当 TS 数据写进解复用器（实测花屏/卡死）。
            // 直接断开连接，让播放器按网络错误重试。
            noteSegFail(ch, url);
            L.w(TAG, "分片回源失败 连续 " + ch.segFailStreak + " 次，断开客户端连接 " + hostOf(url));
            closeQuietly(client);
            return;
        }
        noteSegOk(ch);
        OutputStream os = client.getOutputStream();
        writeHeaders(os, f.code == 206 ? "206 Partial Content" : "200 OK",
                contentTypeOf(f.contentType, url), f.body.length, f.contentRange);
        if (!headOnly) {
            os.write(f.body);
            os.flush();
        }
        closeQuietly(client);
    }

    /**
     * 分块并行下载分片：首块已由探测拿到，边写边取后面的块，按序输出。
     * 播放器读到的是连续的 TS 字节流，不需要等整段下载完。
     */
    private void serveRanged(Channel ch, Socket client, final String url, Response probe, final long total,
                             boolean headOnly) throws IOException {
        OutputStream os = client.getOutputStream();
        writeHeaders(os, "200 OK", contentTypeOf(probe.contentType, url),
                (int) Math.min(total, Integer.MAX_VALUE), null);
        if (headOnly) {
            closeQuietly(client);
            return;
        }
        long segStart = System.currentTimeMillis();
        try {
            os.write(probe.body);
            os.flush();
            final int first = probe.body.length;
            if (total > first) {
                final int count = (int) ((total - first + SEG_CHUNK_BYTES - 1) / SEG_CHUNK_BYTES);
                final byte[][] bufs = new byte[count][];
                final boolean[] bad = new boolean[count];
                final Object mon = new Object();
                int started = 0;
                int written = 0;
                synchronized (mon) {
                    while (started < Math.min(count, SEG_PARALLEL)) {
                        startChunk(url, first, total, bufs, bad, mon, started);
                        started++;
                    }
                }
                while (written < count) {
                    byte[] b;
                    synchronized (mon) {
                        long deadline = System.currentTimeMillis() + SEG_CHUNK_WAIT_MS;
                        while (bufs[written] == null && !bad[written]) {
                            long remain = deadline - System.currentTimeMillis();
                            if (remain <= 0) {
                                break;
                            }
                            try {
                                mon.wait(remain);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        b = bufs[written];
                        if (b != null) {
                            bufs[written] = null;
                        }
                    }
                    if (b == null) {
                        noteSegFail(ch, url);
                        L.w(TAG, "分片分块下载失败 连续 " + ch.segFailStreak + " 次，断开客户端连接 "
                                + hostOf(url));
                        closeQuietly(client);
                        return;
                    }
                    os.write(b);
                    os.flush();
                    ch.lastByteAt = System.currentTimeMillis();
                    written++;
                    synchronized (mon) {
                        if (started < count) {
                            startChunk(url, first, total, bufs, bad, mon, started);
                            started++;
                        }
                    }
                }
            }
            L.d(TAG, "分片已下发 " + total + "B 用时 " + (System.currentTimeMillis() - segStart) + "ms " + url);
        } catch (Throwable t) {
            L.w(TAG, "分片下发中断：" + t);
        }
        closeQuietly(client);
    }

    /** 命中预取缓存时整段瞬时下发。 */
    private void serveCached(Socket client, Channel ch, String url, byte[] data) throws IOException {
        noteSegOk(ch);
        L.d(TAG, "分片命中预取缓存 瞬时下发 " + data.length + "B 线路=" + ch.finalHost);
        OutputStream os = client.getOutputStream();
        writeHeaders(os, "200 OK", contentTypeOf("video/mp2t", url), data.length, null);
        try {
            os.write(data);
            os.flush();
        } catch (Throwable t) {
            L.w(TAG, "缓存分片下发中断：" + t);
        }
        closeQuietly(client);
    }

    /** 取 media playlist 里第一个非注释行对应的上游分片地址。 */
    private String firstSegmentUrl(String text, String base) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            return resolve(base, line);
        }
        return null;
    }

    /** 异步把首分片整段拉到内存缓存（仅供起播命中），失败静默。 */
    private void prefetchFirstSegmentAsync(final Channel ch, final String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        if (isEdgeOne(ch.finalHost)) {
            // EdgeOne 分片实测一律 ECONNRESET，预取纯属浪费一次请求
            return;
        }
        if (url.equals(ch.segCacheUrl) && ch.segCacheBytes != null
                && System.currentTimeMillis() - ch.segCacheAt < SEG_CACHE_TTL_MS) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                long t0 = System.currentTimeMillis();
                byte[] data = fetchFullSegment(url);
                if (data != null && data.length > 0) {
                    ch.segCacheUrl = url;
                    ch.segCacheBytes = data;
                    ch.segCacheAt = System.currentTimeMillis();
                    ch.lastByteAt = ch.segCacheAt;
                    L.d(TAG, "首分片已预取缓存 " + data.length + "B 用时 "
                            + (System.currentTimeMillis() - t0) + "ms 线路=" + hostOf(url));
                }
            }
        }, "fntv-live-prefetch");
        t.setDaemon(true);
        t.start();
    }

    /** 并行分块把整段分片拉成一个 byte[]（不写客户端），供预取缓存使用。 */
    private byte[] fetchFullSegment(String url) {
        try {
            Response probe = httpGet(url, "bytes=0-" + (SEG_FIRST_BYTES - 1), SEG_FIRST_BYTES);
            if (probe == null) {
                return null;
            }
            if (probe.code == 200 && probe.body.length > 0) {
                // 上游忽略 Range 直接回整段
                return probe.body.length <= SEG_CACHE_MAX_BYTES ? probe.body : null;
            }
            if (probe.code != 206) {
                return null;
            }
            long total = totalOf(probe.contentRange);
            if (total <= 0 || total > SEG_CACHE_MAX_BYTES) {
                return null;
            }
            byte[] out = new byte[(int) total];
            int first = Math.min(probe.body.length, (int) total);
            System.arraycopy(probe.body, 0, out, 0, first);
            if (total <= first) {
                return out;
            }
            int count = (int) ((total - first + SEG_CHUNK_BYTES - 1) / SEG_CHUNK_BYTES);
            final byte[][] bufs = new byte[count][];
            final boolean[] bad = new boolean[count];
            final Object mon = new Object();
            int started = 0;
            int done = 0;
            synchronized (mon) {
                while (started < Math.min(count, SEG_PARALLEL)) {
                    startChunk(url, first, total, bufs, bad, mon, started);
                    started++;
                }
            }
            while (done < count) {
                byte[] b;
                synchronized (mon) {
                    long deadline = System.currentTimeMillis() + SEG_CHUNK_WAIT_MS;
                    while (bufs[done] == null && !bad[done]) {
                        long remain = deadline - System.currentTimeMillis();
                        if (remain <= 0) {
                            break;
                        }
                        try {
                            mon.wait(remain);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    b = bufs[done];
                    if (b != null) {
                        bufs[done] = null;
                    }
                }
                if (b == null) {
                    return null;
                }
                long off = first + (long) done * SEG_CHUNK_BYTES;
                System.arraycopy(b, 0, out, (int) off, b.length);
                done++;
                synchronized (mon) {
                    if (started < count) {
                        startChunk(url, first, total, bufs, bad, mon, started);
                        started++;
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void startChunk(final String url, final int first, final long total,
                            final byte[][] bufs, final boolean[] bad, final Object mon, final int idx) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                // 轻微错峰即可（20ms）：播放器只给 4 秒拿首帧，错峰太久等于自废并发。
                if (idx > 0) {
                    try {
                        Thread.sleep(20L * idx);
                    } catch (InterruptedException ignored) {
                    }
                }
                try {
                    SEG_CONN.acquire();
                } catch (InterruptedException ie) {
                    return;
                }
                try {
                long start = first + (long) idx * SEG_CHUNK_BYTES;
                long end = Math.min(total - 1, start + SEG_CHUNK_BYTES - 1);
                int want = (int) (end - start + 1);
                String rng = "bytes=" + start + "-" + end;
                Response r = httpGet(url, rng, want);
                boolean ok = r != null && r.code == 206 && r.body.length == want;
                if (!ok) {
                    // 429/断流在高峰期很常见：单块再补一次，避免整段因为一块失败而作废
                    try {
                        Thread.sleep(1200L);
                    } catch (InterruptedException ignored) {
                    }
                    r = httpGet(url, rng, want);
                    ok = r != null && r.code == 206 && r.body.length == want;
                }
                if (!ok && r != null && r.code == 200 && r.body.length == total) {
                    // 个别节点忽略 Range 直接返回整段：截出需要的那一块
                    byte[] slice = new byte[want];
                    System.arraycopy(r.body, (int) start, slice, 0, want);
                    r.body = slice;
                    ok = true;
                }
                synchronized (mon) {
                    if (ok) {
                        bufs[idx] = r.body;
                    } else {
                        bad[idx] = true;
                    }
                    mon.notifyAll();
                }
                } finally {
                    SEG_CONN.release();
                }
            }
        }, "fntv-live-seg-" + idx);
        t.setDaemon(true);
        t.start();
    }

    private static long totalOf(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private Channel channelWithKey(String key) {
        for (Channel ch : channels.values()) {
            if (ch.urlOf(key) != null) {
                return ch;
            }
        }
        return null;
    }

    private String rewritePlaylist(Channel ch, String text, String base) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                // 给本线路的序列号加上专属偏移：换线后 MStar 才会把它当新流下载分片。
                int colon = raw.indexOf(':');
                if (colon >= 0) {
                    try {
                        long n = Long.parseLong(raw.substring(colon + 1).trim());
                        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(n + ch.seqOffset).append('\n');
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (line.startsWith("#")) {
                sb.append(rewriteUriAttribute(ch, raw, base)).append('\n');
                continue;
            }
            // 非注释行是 URI：master 的变体地址、media 的分片地址
            sb.append(localResourceUrl(ch, resolve(base, line))).append('\n');
        }
        return sb.toString();
    }

    /** 处理 EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA 里的 URI="..."。 */
    private String rewriteUriAttribute(Channel ch, String raw, String base) {
        int idx = raw.indexOf("URI=\"");
        if (idx < 0) {
            return raw;
        }
        int start = idx + 5;
        int end = raw.indexOf('"', start);
        if (end < 0) {
            return raw;
        }
        String uri = raw.substring(start, end);
        if (uri.length() == 0) {
            return raw;
        }
        return raw.substring(0, start) + localResourceUrl(ch, resolve(base, uri)) + raw.substring(end);
    }

    /**
     * 本地资源地址保留原扩展名（多数是 .ts）。厂商 M3U8 解析器习惯按后缀判断分片类型，
     * 纯数字路径虽然合法但兼容性更差。
     */
    private String localResourceUrl(Channel ch, String abs) {
        return baseUrl() + "/u/" + ch.keyOf(abs) + extensionOf(abs);
    }

    private static String extensionOf(String url) {
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot > 0 && dot < path.length() - 1) {
                String ext = path.substring(dot);
                if (ext.length() <= 6 && ext.indexOf('/') < 0) {
                    return ext.toLowerCase();
                }
            }
        } catch (Throwable ignored) {
        }
        return ".ts";
    }

    private static String firstVariant(String text, String base) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                for (int j = i + 1; j < lines.length; j++) {
                    String next = lines[j].trim();
                    if (next.length() == 0) {
                        continue;
                    }
                    if (next.startsWith("#")) {
                        break;
                    }
                    return resolve(base, next);
                }
            }
        }
        return "";
    }

    private static String resolve(String base, String uri) {
        try {
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                return uri;
            }
            return new URL(new URL(base), uri).toString();
        } catch (Throwable t) {
            return uri;
        }
    }

    private static String contentTypeOf(String upstream, String url) {
        if (upstream != null && upstream.length() > 0) {
            return upstream;
        }
        String lower = url.toLowerCase();
        if (lower.indexOf(".ts") > 0) {
            return "video/mp2t";
        }
        if (lower.indexOf(".m4s") > 0 || lower.indexOf(".mp4") > 0) {
            return "video/mp4";
        }
        if (lower.indexOf(".aac") > 0) {
            return "audio/aac";
        }
        return "application/octet-stream";
    }

    private static final class Fetch {
        int code;
        String finalUrl = "";
        String contentType = "";
        String contentRange = "";
        byte[] body = new byte[0];
    }

    /**
     * 原始 socket 版本的精简 HTTP 客户端：跟随 302、支持 chunked、透传 Range。
     * 不用 HttpURLConnection 是因为 Android 4.2 上它配合自定义 SSL 工厂会卡住不返回。
     */
    private Fetch fetch(String url, String range, boolean whole) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            int cap = whole ? 4 * 1024 * 1024 : 32 * 1024 * 1024;
            Response r = httpGet(current, range, cap);
            if (r == null) {
                return null;
            }
            if (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308) {
                if (r.location == null || r.location.length() == 0) {
                    return null;
                }
                current = resolve(current, r.location);
                continue;
            }
            Fetch f = new Fetch();
            f.code = r.code;
            f.finalUrl = current;
            f.contentType = r.contentType;
            f.contentRange = r.contentRange;
            f.body = r.body;
            return f;
        }
        return null;
    }

    private static final class Response {
        int code;
        String location = "";
        String contentType = "";
        String contentRange = "";
        byte[] body = new byte[0];
    }

    private Response httpGet(String url, String range, int cap) {
        Response r = null;
        for (int i = 0; i < 2; i++) {
            r = httpGetOnce(url, range, cap);
            if (r != null && (r.code == 200 || r.code == 206)) {
                return r;
            }
            if (i == 0) {
                // CDN 偶发 RST/429：稍等再试，EdgeOne 的 acw_tc cookie 也在第一次响应里已保存
                try {
                    Thread.sleep(450L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return r;
    }

    private Response httpGetOnce(String url, String range, int cap) {
        Socket socket = null;
        String host = "";
        String pathAndQuery = "/";
        boolean https = false;
        int port = 80;
        try {
            URL u = new URL(url);
            https = "https".equalsIgnoreCase(u.getProtocol());
            host = u.getHost();
            port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
            pathAndQuery = u.getPath();
            if (pathAndQuery == null || pathAndQuery.length() == 0) {
                pathAndQuery = "/";
            }
            if (u.getQuery() != null) {
                pathAndQuery += "?" + u.getQuery();
            }
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 8000);
            raw.setSoTimeout(12000);
            if (https) {
                SSLSocketFactory f = sslFactory();
                if (f == null) {
                    L.w(TAG, "没有可用的 TLS 实现，无法回源直播流");
                    return null;
                }
                SSLSocket ss = (SSLSocket) f.createSocket(raw, host, port, true);
                String[] supported = ss.getSupportedProtocols();
                java.util.ArrayList<String> want = new java.util.ArrayList<String>();
                for (int i = 0; i < supported.length; i++) {
                    String p = supported[i];
                    if ("TLSv1.2".equals(p) || "TLSv1.1".equals(p) || "TLSv1".equals(p)) {
                        want.add(p);
                    }
                }
                if (!want.isEmpty()) {
                    ss.setEnabledProtocols(want.toArray(new String[0]));
                }
                ss.startHandshake();
                socket = ss;
            } else {
                socket = raw;
            }
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("User-Agent: fntv-live/1.0\r\n");
            req.append("Accept: */*\r\n");
            String cookie = cookieOf(host);
            if (cookie.length() > 0) {
                req.append("Cookie: ").append(cookie).append("\r\n");
            }
            if (range != null) {
                req.append("Range: ").append(range).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes(UTF_8));
            os.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            String status = readLine(in);
            if (status == null || status.indexOf("HTTP/") < 0) {
                return null;
            }
            Response r = new Response();
            r.code = statusCode(status);
            String h;
            boolean chunked = false;
            long contentLength = -1L;
            while ((h = readLine(in)) != null && h.length() > 0) {
                int c = h.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String name = h.substring(0, c).trim();
                String value = h.substring(c + 1).trim();
                if ("Location".equalsIgnoreCase(name)) {
                    r.location = value;
                } else if ("Content-Type".equalsIgnoreCase(name)) {
                    r.contentType = value;
                } else if ("Content-Range".equalsIgnoreCase(name)) {
                    r.contentRange = value;
                } else if ("Transfer-Encoding".equalsIgnoreCase(name)) {
                    chunked = value.toLowerCase().indexOf("chunked") >= 0;
                } else if ("Content-Length".equalsIgnoreCase(name)) {
                    try {
                        contentLength = Long.parseLong(value);
                    } catch (Throwable ignored) {
                    }
                } else if ("Set-Cookie".equalsIgnoreCase(name)) {
                    saveCookie(host, value);
                }
            }
            if (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308) {
                return r;
            }
            r.body = chunked ? readChunked(in, cap) : readFixed(in, contentLength, cap);
            return r;
        } catch (Throwable t) {
            // 只用主机名 + 异常类型：分片地址带很长的 srcurl/token，完整打印会拖慢设备并冲掉日志。
            long now = System.currentTimeMillis();
            if (now - lastFailLogAt > 1000L) {
                lastFailLogAt = now;
                L.d(TAG, "回源失败 " + hostOf(url) + "：" + t.getClass().getSimpleName()
                        + " " + t.getMessage());
            }
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void saveCookie(String host, String setCookie) {
        int semi = setCookie.indexOf(';');
        String pair = (semi > 0 ? setCookie.substring(0, semi) : setCookie).trim();
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = pair.substring(0, eq).trim();
        synchronized (cookieJar) {
            String jar = cookieJar.get(host);
            if (jar == null || jar.length() == 0) {
                cookieJar.put(host, pair);
                return;
            }
            String[] parts = jar.split("; ");
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.startsWith(name + "=")) {
                    sb.append(pair);
                    replaced = true;
                } else {
                    sb.append(p);
                }
                sb.append("; ");
            }
            if (!replaced) {
                sb.append(pair).append("; ");
            }
            cookieJar.put(host, sb.toString());
        }
    }

    private String cookieOf(String host) {
        synchronized (cookieJar) {
            String jar = cookieJar.get(host);
            return jar == null ? "" : jar;
        }
    }

    private static int statusCode(String statusLine) {
        String[] p = statusLine.split(" ");
        if (p.length >= 2) {
            try {
                return Integer.parseInt(p[1]);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static byte[] readFixed(InputStream in, long length, int cap) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        if (length >= 0) {
            long remain = Math.min(length, cap);
            while (remain > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remain));
                if (n <= 0) {
                    break;
                }
                bo.write(buf, 0, n);
                remain -= n;
            }
        } else {
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() >= cap) {
                    break;
                }
            }
        }
        return bo.toByteArray();
    }

    private static byte[] readChunked(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi > 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (Throwable t) {
                break;
            }
            if (size <= 0) {
                readLine(in);
                break;
            }
            int remain = size;
            while (remain > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remain));
                if (n <= 0) {
                    return bo.toByteArray();
                }
                bo.write(buf, 0, n);
                remain -= n;
                if (bo.size() >= cap) {
                    return bo.toByteArray();
                }
            }
            readLine(in);
        }
        return bo.toByteArray();
    }

    private SSLSocketFactory sslFactory() {
        synchronized (lock) {
            if (sslFactory != null) {
                return sslFactory;
            }
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            try {
                // Conscrypt 自带 BoringSSL，可在 API17 上提供 TLS1.2 + AES-GCM
                SSLContext ctx = SSLContext.getInstance("TLS", conscryptProvider());
                ctx.init(null, trustAll, null);
                sslFactory = ctx.getSocketFactory();
                L.i(TAG, "直播回源使用 Conscrypt TLS");
            } catch (Throwable t) {
                L.w(TAG, "Conscrypt 不可用，回退系统 TLS：" + t);
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, trustAll, null);
                    sslFactory = ctx.getSocketFactory();
                } catch (Throwable ignored) {
                    sslFactory = null;
                }
            }
            return sslFactory;
        }
    }

    private static Provider conscryptProvider() throws Exception {
        try {
            return org.conscrypt.Conscrypt.newProvider();
        } catch (NoClassDefFoundError e) {
            throw new Exception("conscrypt missing", e);
        }
    }

    private static void writeHeaders(OutputStream os, String status, String contentType,
                                     int length, String contentRange) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Cache-Control: no-cache\r\n");
        if (contentRange != null && contentRange.length() > 0) {
            sb.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        os.write(sb.toString().getBytes(UTF_8));
    }

    private static void respondError(Socket client, int code, String msg) {
        try {
            byte[] body = msg.getBytes(UTF_8);
            OutputStream os = client.getOutputStream();
            writeHeaders(os, code + " Error", "text/plain", body.length, null);
            os.write(body);
            os.flush();
        } catch (Throwable ignored) {
        }
        closeQuietly(client);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bo.write(c);
            }
            if (bo.size() > 8192) {
                break;
            }
        }
        if (c < 0 && bo.size() == 0) {
            return null;
        }
        return new String(bo.toByteArray(), ISO_8859_1);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = -1;
        while ((i = text.indexOf(needle, i + 1)) >= 0) {
            n++;
        }
        return n;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
