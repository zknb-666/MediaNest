package com.juying.medianest.player;

import com.juying.medianest.core.util.L;
import com.juying.medianest.core.policy.SessionPolicy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地回环 HTTP 代理，同时解决两件事：
 *
 * <p>1) 飞牛私有流「只认 Authorization 请求头」。
 * 真机实测（2026-11-18 / TCL Android 4.2.1 + nas.example.com:5666）：
 * <pre>
 *   GET /v/media/&lt;hash&gt;/preset.m3u8  不带 Authorization → 200 但内容是 {"code":-2,"msg":"Auth Failed"}
 *   ?token= / ?playlink= / Cookie / Play-Link 头 全部无效，只有 Authorization 有效。
 * </pre>
 * ACTION_VIEW 交给系统播放器时无法附加请求头，于是把流改写成
 * http://127.0.0.1:&lt;port&gt;/&lt;原路径&gt;，由本代理补头后转发。
 *
 * <p>2) 播放会话约 60 秒后统一 410 Gone（详见 {@link SessionPolicy}）。
 * 本代理绑定当前 /v/media/&lt;hash&gt; 目录，在到期前通过 SessionKeeper
 * 重新 POST /play/play 换一个新 hash；之后把旧路径里的 hash 透明替换成新 hash，
 * 保留 00000.ts / main.m3u8 等文件名。VOD 分片编号跨会话一致，因此内嵌
 * MediaPlayer 与外部系统播放器都能无感续播，无需重新 seek。
 *
 * <p>只用 API 1 就有的 ServerSocket / HttpURLConnection，不引入任何第三方库。
 */
public final class AuthProxy {

    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.Charset.forName("UTF-8");
    private static final java.nio.charset.Charset US_ASCII =
            java.nio.charset.Charset.forName("US-ASCII");

    /**
     * 单次代理响应的最大缓冲。真机（Android 4.2.1 / TCL TV）堆很小，
     * 旧值 32MB 在「转码服务不可用 → 直连 1.8GB 原始 MKV」时，
     * ByteArrayOutputStream 扩容 + toByteArray() 复制会瞬间占用 64MB，
     * 触发 OutOfMemoryError 并把整个 App 崩掉。HLS 分片通常只有几 MB，
     * 4MB 足够覆盖 m3u8 / TS 头部，同时把并发缓冲控制在老 TV 可承受范围。
     */
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    /**
     * 会话续期回调。实现方只持有 ServerConfig / MediaItem，不持有 Activity，
     * 因此 PlayerActivity 销毁后（例如切到外部系统播放器）续期仍然有效。
     */
    public interface SessionKeeper {
        /** 重新创建播放会话，返回新的绝对 play_link；失败返回 ""。 */
        String refreshSession();

        /** 网页端 media.transcodeStatis：周期性心跳，避免会话被服务端回收。 */
        void touchSession(String playLink);

        /** 绑定/更换会话后通知续期器，供心跳与探测使用。 */
        void onSessionBound(String playLink);

        void onSessionRenewed(String oldDir, String newDir);

        void stopHeartbeat();

        void endSessionAsync(String playLink);

        void onSessionExpired(String path, int status);
    }

    /**
     * token 续登回调。真机实测（2026-11-20）：
     * <pre>
     *   GET /v/api/v1/media/range/&lt;guid&gt;  带过期 Authorization → HTTP 200
     *   body = {"msg":"Auth Failed","code":-2,"data":null}  （仅 43 字节）
     *   ?playlink= / ?token= 全被忽略，只有 Authorization 头参与鉴权。
     * </pre>
     * 旧实现把这个 JSON 当视频流原样回给播放器，分片请求永远带死 token，
     * 会话续期也无济于事（续期只换 hash，Authorization 还是旧的）。
     * 实现方用配置里的账号密码重新登录，返回新 token；失败返回 ""。
     */
    public interface TokenSource {
        String refreshToken();
    }

    private final String upstreamBase;
    /** 当前使用的 token。播放中允许被重新登录刷新，不能是 final 快照。 */
    private volatile String token;
    private volatile TokenSource tokens;
    /** 串行化续登，避免 8 个 worker 同时触发 /login 风暴。 */
    private final Object authLock = new Object();
    private ServerSocket server;
    private ExecutorService pool;
    private Thread acceptor;
    private volatile Thread externalIdleWatcher;
    private volatile boolean running = false;
    private int port = 0;

    /** 会话锁：保证并发分片请求只会触发一次续期。 */
    private final Object sessionLock = new Object();
    private final AtomicInteger sessionEpoch = new AtomicInteger();
    private volatile SessionKeeper keeper;
    private volatile String mediaDir = "";
    /** 完整 play_link 路径，用于会话心跳与退出回收。 */
    private volatile String playLink = "";
    /** 上一次真正发起 /play/play 的时刻，用于给续期失败/并发 410 做 3 秒退避。 */
    private volatile long lastAttemptMs = 0L;

    // ------------------------------------------------------------------
    // 起播观测：给播放器的 prepare 看门狗提供「NAS 是不是还在出片」的证据，
    // 避免再用「8 秒没 prepared 就判死」这种一刀切，把转码预热误判成不可播。
    // ------------------------------------------------------------------
    private final AtomicInteger mediaInFlight = new AtomicInteger(0);
    private volatile long mediaWaitingSinceMs = 0L;
    private volatile long mediaLastActivityMs = 0L;
    private volatile int mediaRequests = 0;
    private volatile int mediaFailures = 0;
    private final Object networkLock = new Object();
    private final Deque<NetworkSample> networkSamples = new ArrayDeque<NetworkSample>();
    private volatile int networkPeakKbps;
    private volatile TsMetadata tsMetadata;
    private volatile String jellyfinSegmentQuery = "";
    private static final long NETWORK_WINDOW_MS = 5000L;
    /** 客户端断连日志节流时间戳。 */
    private volatile long lastClientAbortLogAt = 0L;

    // ------------------------------------------------------------------
    // 分片本地预缓存：用户实测要点——大影片转码首片要 8 秒左右才出，
    // 播放器自己等不住会放弃连接（EPIPE），多个请求同时打 NAS 又会加剧拥塞。
    // 这里用「单线程预取 + 落盘 + 播放器请求加入同一下载」把起播阶段收敛成
    // 一条顺序的上游连接：先缓存首段若干分片，等 NAS 转完再顺序喂给播放器。
    // 只在 /v/media/<hash>/NNNNN.ts 且请求不带 Range 时启用；其它一律直通。
    // ------------------------------------------------------------------
    private static final int SEG_MAX_FILES = 16;
    private static final long SEG_MAX_BYTES = 40L * 1024 * 1024;
    /** 播放列表里预取前几个分片（1.2MB/片时约 7MB，跑完即停）。 */
    private static final int SEG_PREFETCH_COUNT = 6;
    private static final int SEEK_PREFETCH_COUNT = 3;
    private static final int FNOS_PLAYLIST_WINDOW_SEGMENTS = 2000;
    private static final int FNOS_LONG_GROUP_SIZE = 5;
    private static final long FNOS_LONG_GROUP_MS = 10000L;
    /** 播放器等待预取分片的最长时间，覆盖大跨度 seek 的冷转码预热。 */
    private static final long SEG_WAIT_WARM_MS = 25000L;
    /** 预取失败（NAS 还没转出该片）时的重试窗口与退避。 */
    private static final long SEG_RETRY_WARM_MS = 30000L;
    private static final long SEG_RETRY_MS = 10000L;

    private volatile File cacheRoot;
    private volatile File segDir;
    /** 起播/续播位置（毫秒），决定播放列表预取的起点分片。 */
    private volatile long startPositionMs = 0L;
    /** 已按播放列表预取过的会话 tag，避免 HlsClock 再拉一次播放列表时重复预取。 */
    private volatile String prefetchSeededDir = "";
    private volatile String lastMediaPlaylistPath = "";
    private volatile byte[] lastMediaPlaylistPayload;
    /** 最近一次真正喂给播放器的分片序号，用于「淘汰时别删马上要播的片」。 */
    private volatile int lastServedIndex = -1;
    private final Object segLock = new Object();
    private final Map<String, SegTask> segTasks = new HashMap<String, SegTask>();
    private ExecutorService segPool;

    /** m3u8 短缓存：播放器与字幕对时器经常在同一秒内重复拉同一个播放列表。 */
    private static final long PLAYLIST_TTL_MS = 4000L;
    private static final int PLAYLIST_CACHE_MAX = 4;
    private final Object playlistLock = new Object();
    private final Map<String, PlaylistHit> playlistCache = new HashMap<String, PlaylistHit>();
    private final Object virtualLock = new Object();
    private final Map<String, SegTask> virtualTasks = new HashMap<String, SegTask>();
    private ExecutorService virtualPool;
    private volatile FnosPlaylist fnosPlaylist;
    private String fnosOriginalPlaylistPath = "";
    private byte[] fnosOriginalPlaylistPayload;
    private volatile boolean virtualModeFailed;

    /** 供播放器看门狗判断「转码预热慢」还是「真的不可播」。 */
    public static final class MediaStatus {
        public final int inFlight;
        public final long idleMs;
        public final long waitingMs;
        public final int requests;
        public final int failures;
        public final int networkKbps;

        MediaStatus(int inFlight, long idleMs, long waitingMs, int requests, int failures,
                    int networkKbps) {
            this.inFlight = inFlight;
            this.idleMs = idleMs;
            this.waitingMs = waitingMs;
            this.requests = requests;
            this.failures = failures;
            this.networkKbps = networkKbps;
        }
    }

    public static final class TsMetadata {
        public final boolean videoPresent;
        public final boolean audioPresent;
        public final String videoCodec;
        public final String audioCodec;

        TsMetadata(boolean videoPresent, boolean audioPresent,
                   String videoCodec, String audioCodec) {
            this.videoPresent = videoPresent;
            this.audioPresent = audioPresent;
            this.videoCodec = videoCodec == null ? "" : videoCodec;
            this.audioCodec = audioCodec == null ? "" : audioCodec;
        }
    }

    public TsMetadata mediaMetadata() {
        return tsMetadata;
    }

    private static final class SegTask {
        String name;
        File file;
        int index = -1;
        volatile boolean done;
        volatile boolean failed;
        volatile boolean cancelled;
        long requestedAt;
    }

    private static final class PlaylistHit {
        byte[] payload;
        int status;
        long atMs;
    }

    private static final class FnosPlaylist {
        String basePath;
        final List<String> refs = new ArrayList<String>();
        final List<Integer> durationsMs = new ArrayList<Integer>();
        final List<Integer> startsMs = new ArrayList<Integer>();

        int groupCount() {
            return (refs.size() + FNOS_LONG_GROUP_SIZE - 1) / FNOS_LONG_GROUP_SIZE;
        }

        int groupStart(int group) {
            return group * FNOS_LONG_GROUP_SIZE;
        }

        int groupEnd(int group) {
            return Math.min(refs.size(), (group + 1) * FNOS_LONG_GROUP_SIZE);
        }

        int groupDurationMs(int group) {
            int start = groupStart(group);
            int end = groupEnd(group);
            return start < end ? startsMs.get(end) - startsMs.get(start) : 0;
        }

        FnosPlaylist withBasePath(String newBasePath) {
            FnosPlaylist copy = new FnosPlaylist();
            copy.basePath = newBasePath;
            copy.refs.addAll(refs);
            copy.durationsMs.addAll(durationsMs);
            copy.startsMs.addAll(startsMs);
            return copy;
        }
    }

    private static final class NetworkSample {
        final long atMs;
        final int bytes;

        NetworkSample(long atMs, int bytes) {
            this.atMs = atMs;
            this.bytes = bytes;
        }
    }

    public AuthProxy(String baseUrl, String token) {
        String b = baseUrl == null ? "" : baseUrl;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.upstreamBase = b;
        this.token = token == null ? "" : token;
    }

    /** 只按服务器地址匹配：token 刷新时复用同一个代理，避免端口变化打断在播的播放器。 */
    public boolean matches(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return upstreamBase.equals(b);
    }

    /** 更新当前 token（首页重新登录后由 App 调用，播放器无需换端口）。 */
    public void setToken(String value) {
        this.token = value == null ? "" : value;
    }

    /** 注册续登器（由 PlayerActivity 在生成播放方案时调用）。 */
    public void setTokenSource(TokenSource source) {
        this.tokens = source;
    }

    public synchronized String start() throws IOException {
        if (running) {
            return baseUrl();
        }
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        pool = Executors.newFixedThreadPool(8, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fntv-proxy-worker");
                t.setDaemon(true);
                return t;
            }
        });
        running = true;
        acceptor = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "fntv-auth-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
        L.i("AuthProxy", "已启动 " + baseUrl() + " → " + upstreamBase);
        return baseUrl();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** 把 NAS 的绝对 URL 改写成回环代理 URL；占位符/非 http 地址原样返回。 */
    public String rewrite(String url) {
        if (!running || url == null) {
            return url;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url;
        }
        // 直播源是第三方 m3u8 直链，不能改写成回环代理（否则域名丢失，必然 404）
        if (upstreamBase != null && !url.startsWith(upstreamBase)) {
            return url;
        }
        int scheme = url.indexOf("://") + 3;
        int slash = url.indexOf('/', scheme);
        if (slash < 0) {
            return baseUrl() + "/";
        }
        String path = url.substring(slash);
        String lower = path.toLowerCase(java.util.Locale.US);
        if (lower.indexOf("/videos/") >= 0 || lower.indexOf("/livetv/") >= 0) {
            path = withoutQueryParameter(path, "ApiKey");
        }
        return baseUrl() + path;
    }

    private static String withoutQueryParameter(String path, String name) {
        int at = path.indexOf('?');
        if (at < 0) return path;
        String resource = path.substring(0, at);
        StringBuilder query = new StringBuilder();
        String[] pairs = path.substring(at + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equalsIgnoreCase(key)) continue;
            if (query.length() > 0) query.append('&');
            query.append(pair);
        }
        return resource + (query.length() > 0 ? "?" + query : "");
    }

    /** 注册续期器（由 PlayerActivity 在生成播放方案时调用）。 */
    public void setSessionKeeper(SessionKeeper k) {
        this.keeper = k;
    }

    /** 内部播放退出时断开会话，残留请求不能再续期或重启心跳。 */
    public void detachSession() {
        Thread watcher;
        synchronized (sessionLock) {
            sessionEpoch.incrementAndGet();
            keeper = null;
            tokens = null;
            mediaDir = "";
            playLink = "";
            lastAttemptMs = 0L;
            watcher = externalIdleWatcher;
            externalIdleWatcher = null;
        }
        if (watcher != null) {
            watcher.interrupt();
        }
        cancelPrefetchTasks();
    }

    /** 外部播放器接管后，即使 Activity 被系统回收，也按网络空闲回收会话。 */
    public void releaseSessionWhenIdle(final long idleMs) {
        final long startedAt = System.currentTimeMillis();
        final int requestsAtStart = mediaRequests;
        final SessionKeeper k;
        synchronized (sessionLock) {
            k = keeper;
            if (k == null || externalIdleWatcher != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (running) {
                        try {
                            Thread.sleep(15000L);
                        } catch (InterruptedException ex) {
                            return;
                        }
                        MediaStatus status = mediaStatus();
                        if (status.inFlight > 0) {
                            continue;
                        }
                        if (status.requests <= requestsAtStart
                                && System.currentTimeMillis() - startedAt < 120000L) {
                            continue;
                        }
                        if (status.idleMs < idleMs) {
                            continue;
                        }
                        String link;
                        synchronized (sessionLock) {
                            if (keeper != k) {
                                return;
                            }
                            link = playLink;
                            sessionEpoch.incrementAndGet();
                            playLink = "";
                            mediaDir = "";
                            keeper = null;
                            tokens = null;
                            externalIdleWatcher = null;
                        }
                        k.stopHeartbeat();
                        k.endSessionAsync(link);
                        cancelPrefetchTasks();
                        return;
                    }
                }
            }, "fntv-external-idle");
            t.setDaemon(true);
            externalIdleWatcher = t;
            t.start();
        }
    }

    private static String mediaPlayLink(String urlOrPath) {
        if (urlOrPath == null) {
            return "";
        }
        int at = urlOrPath.indexOf("/v/media/");
        return at < 0 ? urlOrPath : urlOrPath.substring(at);
    }

    /** 绑定本次播放使用的 /v/media/&lt;hash&gt; 目录。 */
    public void bindSession(String playLinkOrUrl) {
        String dir = SessionPolicy.mediaDirOf(playLinkOrUrl);
        if (dir.length() == 0) {
            return;
        }
        String fullPlayLink = mediaPlayLink(playLinkOrUrl);
        synchronized (sessionLock) {
            sessionEpoch.incrementAndGet();
            mediaDir = dir;
            // 网页端 checkPlayLink / transcodeStatis 传的是完整播放列表路径
            playLink = fullPlayLink;
            lastAttemptMs = 0L;
            if (keeper != null) {
                try {
                    keeper.onSessionBound(fullPlayLink);
                } catch (Throwable ignored) {
                }
            }
        }
        L.i("AuthProxy", "绑定播放会话 " + dir + "（30 秒心跳保活，410 时续期）");
    }

    /**
     * 返回本次请求实际应该使用的会话目录。
     *
     * @param requested 请求路径里的目录（可能是过期会话的旧 hash）
     * @param force     true 表示上游刚返回 410/403，需要强制续期
     */
    private String resolveDir(String requested, boolean force) {
        String cur;
        SessionKeeper k;
        int epoch;
        synchronized (sessionLock) {
            cur = mediaDir;
            if (cur.length() == 0) {
                return requested;
            }
            long now = System.currentTimeMillis();
            boolean staleHash = !cur.equals(requested);

            // 与网页端一致：30 秒 transcodeStatis 心跳负责保活，不按固定年龄重建会话。
            // 只有当前 hash 真实返回 410/403 才换 play_link；旧 hash 的在途请求直接重映射。
            if (!force || staleHash) {
                return cur;
            }
            k = keeper;
            // 无论主动还是被动，3 秒内只允许一次真正的续期请求，避免 410 风暴。
            if (!SessionPolicy.allowForceRenew(now, lastAttemptMs)) {
                return cur;
            }
            lastAttemptMs = now;
            epoch = sessionEpoch.get();
            if (k == null) {
                return cur;
            }
        }

        String fresh = "";
        try {
            fresh = k.refreshSession();
        } catch (Throwable t) {
            L.w("AuthProxy", "会话续期异常：" + t);
        }
        String next = SessionPolicy.mediaDirOf(fresh);
        if (next.length() == 0) {
            L.w("AuthProxy", "410 强制续期失败，暂时沿用旧会话 " + cur);
            return cur;
        }

        synchronized (sessionLock) {
            if (epoch != sessionEpoch.get() || keeper != k) {
                k.endSessionAsync(fresh);
                return requested;
            }
            String old = cur;
            String oldPlayLink = playLink;
            if (fnosPlaylist != null) {
                synchronized (virtualLock) {
                    fnosPlaylist = fnosPlaylist.withBasePath(
                            SessionPolicy.remap(fnosPlaylist.basePath, old, next));
                }
            }
            if (fnosOriginalPlaylistPath.length() > 0) {
                fnosOriginalPlaylistPath = SessionPolicy.remap(fnosOriginalPlaylistPath, old, next);
            }
            String cachedPlaylist = lastMediaPlaylistPath;
            if (cachedPlaylist.length() > 0) {
                lastMediaPlaylistPath = SessionPolicy.remap(cachedPlaylist, old, next);
            }
            lastMediaPlaylistPayload = null;
            mediaDir = next;
            playLink = mediaPlayLink(fresh);
            // 旧会话的预取分片不能复用（编号相同但内容可能不同），立即放弃，
            // 让单线程预取器马上去服务新会话，避免续期瞬间卡住起播。
            cancelPrefetchTasks();
            try {
                k.onSessionBound(playLink);
                k.onSessionRenewed(old, next);
                if (oldPlayLink.length() > 0) {
                    k.endSessionAsync(oldPlayLink);
                }
            } catch (Throwable ignored) {
            }
            L.i("AuthProxy", "410 强制续期成功 " + old + " → " + next);
            return next;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = server.accept();
                pool.execute(new Runnable() {
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (IOException ex) {
                if (running) {
                    L.w("AuthProxy", "accept 失败：" + ex.getMessage());
                }
            } catch (Throwable ignored) {
                // 线程池关闭时会走到这里
            }
        }
    }

    /** 一次上游请求的结果（先缓冲再回写，避免透传错误的 Content-Length）。 */
    private static final class Upstream {
        int status;
        String contentRange;
        int declaredLength;
        byte[] payload;
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            // 包头逐行读取：必须缓冲，否则每个字节一次 syscall（老播放器的请求头很长）
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            // 老播放器的 HTTP 栈可能先发 HEAD 探测；只回 GET 会让连接被直接关闭。
            final boolean isHead = requestLine.startsWith("HEAD ");
            if (!isHead && !requestLine.startsWith("GET ")) {
                return;
            }
            int prefix = isHead ? 5 : 4;
            int sp = requestLine.indexOf(' ', prefix);
            String path = sp > 0 ? requestLine.substring(prefix, sp) : "/";
            String range = null;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }

            String requestedDir = SessionPolicy.mediaDirOf(path);
            String effectivePath = path;
            boolean fnosLong = isFnosLongRequest(path);
            if (fnosLong) {
                effectivePath = withoutLongQuery(path);
            }
            if (requestedDir.length() > 0) {
                String freshDir = resolveDir(requestedDir, false);
                if (freshDir.length() > 0 && !freshDir.equals(requestedDir)) {
                    effectivePath = SessionPolicy.remap(path, requestedDir, freshDir);
                }
            }

            if (isMediaBody(path)) {
                mediaRequests++;
                mediaInFlight.incrementAndGet();
                if (mediaInFlight.get() == 1) {
                    mediaWaitingSinceMs = System.currentTimeMillis();
                }
                try {
                    String virtualName = fnosLong ? virtualSegmentName(path) : null;
                    if (virtualName != null) {
                        File cached = cachedVirtualSegment(virtualName);
                        if (cached == null) {
                            SegTask task = requestVirtualSegment(virtualName);
                            if (task != null && waitSegment(task, SEG_WAIT_WARM_MS * 2L)) {
                                cached = task.file;
                            }
                            if (cached == null && task != null) {
                                task = requestVirtualSegment(virtualName);
                                if (task != null && waitSegment(task, SEG_WAIT_WARM_MS * 2L)) {
                                    cached = task.file;
                                }
                            }
                            if (cached == null) {
                                cached = cachedVirtualSegment(virtualName);
                            }
                        }
                        if (cached != null && cached.exists() && cached.length() > 0) {
                            captureTsMetadata(cached);
                            int group = virtualSegmentIndex(virtualName);
                            lastServedIndex = group * FNOS_LONG_GROUP_SIZE;
                            serveSegment(out, cached, path, range);
                            prefetchVirtualAhead(group + 1);
                            return;
                        }
                    }
                    // 起播/播放中的分片优先走「本地预缓存」：同一个分片只允许一条
                    // 上游连接（预取线程与播放器请求合并），起播阶段就只剩顺序下载，
                    // NAS 转码再慢也不会被并发请求拖成拥塞。Range 请求由缓存文件切片。
                    if (!isHead && segmentName(path) != null && !isHeadProbeRange(range)) {
                        File cached = cachedSegment(effectivePath, path);
                        if (cached == null) {
                            SegTask task = requestSegment(effectivePath, path);
                            if (task != null && waitSegment(task, SEG_WAIT_WARM_MS)) {
                                cached = task.file;
                            }
                        }
                        if (cached != null && cached.exists() && cached.length() > 0) {
                            captureTsMetadata(cached);
                            int servedIndex = segIndex(segmentName(path));
                            if (servedIndex >= 0) {
                                lastServedIndex = servedIndex;
                            }
                            serveSegment(out, cached, path, range);
                            prefetchAhead(effectivePath, path);
                            return;
                        }
                    }
                    MediaStream m = openMedia(effectivePath, range, token);
                    if (m != null) {
                        markMediaActivity();
                    }
                    if (m != null && (m.status == -2 || m.status == 401)) {
                        String fresh = refreshToken(token);
                        if (fresh.length() > 0) {
                            L.w("AuthProxy", "媒体流鉴权失败，已重新登录，用新 token 重试 " + safePath(path));
                            m = openMedia(effectivePath, range, fresh);
                        }
                    }
                    if (m != null && (m.status == -2 || m.status == 401)) {
                        mediaFailures++;
                        L.w("AuthProxy", "媒体流鉴权失败且无法自动续登 " + safePath(path) + "，如实回 401");
                        out.write(("HTTP/1.1 401 Unauthorized\r\nContent-Type: "
                                + contentType(path) + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(US_ASCII));
                        out.flush();
                        return;
                    }
                    if (m != null) {
                        try {
                            L.d("AuthProxy", "GET " + safePath(effectivePath) + " → 流式 " + m.status
                                    + " 声明长度=" + m.length);
                            StringBuilder mh = new StringBuilder();
                            mh.append("HTTP/1.1 ").append(SessionPolicy.statusLine(m.status)).append("\r\n");
                            mh.append("Content-Type: ").append(contentType(path)).append("\r\n");
                            if (m.contentRange != null && m.status == 206) {
                                mh.append("Content-Range: ").append(m.contentRange).append("\r\n");
                            }
                            if (m.length >= 0) {
                                mh.append("Content-Length: ").append(m.length).append("\r\n");
                            }
                            mh.append("Accept-Ranges: bytes\r\n");
                            mh.append("Connection: close\r\n\r\n");
                            out.write(mh.toString().getBytes(US_ASCII));
                            if (!isHead) {
                                out.write(m.head);
                                byte[] buf = new byte[32 * 1024];
                                int n;
                                while ((n = m.body.read(buf)) > 0) {
                                    out.write(buf, 0, n);
                                    markMediaActivity();
                                    markNetworkBytes(n);
                                }
                            }
                            out.flush();
                        } catch (IOException ex) {
                            // 播放器提前放弃连接（EPIPE/ECONNRESET）：起播超时或 seek
                            // 换分片时很常见，不是故障，静默收尾即可。
                            if (!isClientAbort(ex)) {
                                throw ex;
                            }
                            logClientAbort(path, ex);
                        } finally {
                            try {
                                m.body.close();
                            } catch (Throwable ignored) {
                            }
                            try {
                                m.conn.disconnect();
                            } catch (Throwable ignored) {
                            }
                        }
                        return;
                    }
                    mediaFailures++;
                } finally {
                    if (mediaInFlight.decrementAndGet() == 0) {
                        mediaWaitingSinceMs = 0L;
                    }
                }
            }

            Upstream r = null;
            boolean isPlaylist = isPlaylist(path) && (range == null || range.length() == 0);
            String playlistCacheKey = isPlaylist ? playlistCacheKey(effectivePath) : effectivePath;
            if (isPlaylist) {
                r = cachedPlaylist(playlistCacheKey);
                if (r != null) {
                    r = sliceFnosPlaylist(effectivePath, r);
                }
            }
            if (r == null) {
                r = fetch(effectivePath, range);
                if (isPlaylist && r.status == 200 && r.payload != null && r.payload.length > 0) {
                    r = followMaster(effectivePath, r);
                    if (fnosLong) {
                        byte[] original = r.payload;
                        r.payload = buildFnosVirtualPlaylist(effectivePath, original);
                        storePlaylistPayload(playlistCacheKey, r.status, original);
                        prefetchFnosLong(effectivePath);
                    } else {
                        r = sliceFnosPlaylist(effectivePath, r);
                        storePlaylist(playlistCacheKey, r);
                        prefetchFromPlaylist(effectivePath, r.payload);
                    }
                }
            } else if (fnosLong && r.status == 200 && r.payload != null && r.payload.length > 0) {
                byte[] original = r.payload;
                r.payload = buildFnosVirtualPlaylist(effectivePath, original);
                prefetchFnosLong(effectivePath);
            }
            if (SessionPolicy.isSessionGone(r.status) && requestedDir.length() > 0) {
                // 用「实际请求出去的目录」判定谁死了：若当前会话自己 410，
                // resolveDir 会立刻强制换 hash；若只是旧 hash 的在途请求，
                // 会直接重映射到已续期的目录，不重复请求 NAS。
                String usedDir = SessionPolicy.mediaDirOf(effectivePath);
                String forcedDir = resolveDir(usedDir, true);
                if (forcedDir.length() > 0 && !forcedDir.equals(usedDir)) {
                    effectivePath = SessionPolicy.remap(effectivePath, usedDir, forcedDir);
                    playlistCacheKey = isPlaylist ? playlistCacheKey(effectivePath) : effectivePath;
                    r = fetch(effectivePath, range);
                    if (isPlaylist && r.status == 200 && r.payload != null && r.payload.length > 0) {
                        r = followMaster(effectivePath, r);
                        if (fnosLong) {
                            byte[] original = r.payload;
                            r.payload = buildFnosVirtualPlaylist(effectivePath, original);
                            storePlaylistPayload(playlistCacheKey, r.status, original);
                            prefetchFnosLong(effectivePath);
                        } else {
                            r = sliceFnosPlaylist(effectivePath, r);
                            storePlaylist(playlistCacheKey, r);
                            prefetchFromPlaylist(effectivePath, r.payload);
                        }
                    }
                }
            }
            String mapped = effectivePath.equals(path) ? "" : " [重映射→" + safePath(effectivePath) + "]";
            if (SessionPolicy.isErrorStatus(r.status)) {
                SessionKeeper k = keeper;
                if (k != null && SessionPolicy.isSessionGone(r.status)) {
                    try {
                        k.onSessionExpired(path, r.status);
                    } catch (Throwable ignored) {
                    }
                }
                L.w("AuthProxy", "GET " + safePath(path) + mapped + " → 上游 HTTP " + r.status
                        + "（如实回给播放器，不再伪装成 200）");
            } else {
                L.d("AuthProxy", "GET " + safePath(path) + mapped + " → 上游 " + r.status
                        + " 声明长度=" + r.declaredLength + " 实收=" + r.payload.length);
            }

            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(SessionPolicy.statusLine(r.status)).append("\r\n");
            head.append("Content-Type: ").append(contentType(path)).append("\r\n");
            if (r.contentRange != null && r.status == 206) {
                head.append("Content-Range: ").append(r.contentRange).append("\r\n");
            }
            head.append("Content-Length: ").append(r.payload.length).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(US_ASCII));
            if (!isHead) {
                out.write(r.payload);
            }
            out.flush();
        } catch (Throwable t) {
            // 播放器放弃连接时会抛 EPIPE / ENOTCONN / ECONNRESET。旧实现按 warn
            // 打印，一次起播失败就能刷出几十条「转发失败」，掩盖真正的问题。
            if (isClientAbort(t)) {
                logClientAbort("", t);
            } else {
                L.w("AuthProxy", "转发失败：" + t);
            }
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 播放列表若仍是 master（含 #EXT-X-STREAM-INF），在代理层跟随首个变体，
     * 只把媒体列表回给播放器：MStar 4.2.1 的 HLS 解复用器不认 master，
     * 会直接报 no input。NAS 新版本 preset.m3u8 已是媒体列表，这里只在
     * 老版本/直连场景下触发，正常播放零额外请求。
     */
    private Upstream followMaster(String path, Upstream r) {
        if (r.status != 200 || r.payload == null || r.payload.length == 0
                || r.payload.length > 256 * 1024) {
            return r;
        }
        String text = new String(r.payload, UTF_8);
        if (path.toLowerCase(java.util.Locale.US).indexOf("/videos/") >= 0) {
            jellyfinSegmentQuery = segmentProfileQuery(path);
            tsMetadata = null;
        }
        if (text.indexOf("#EXT-X-STREAM-INF") < 0) {
            return r;
        }
        String ref = "";
        boolean afterStreamInfo = false;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.length() == 0) {
                continue;
            }
            if (s.startsWith("#EXT-X-STREAM-INF")) {
                afterStreamInfo = true;
                continue;
            }
            if (afterStreamInfo) {
                if (s.charAt(0) == '#') {
                    continue;
                }
                ref = s;
                break;
            }
        }
        if (ref.length() == 0) {
            return r;
        }
        String variantPath = resolvePath(path, ref);
        if (variantPath.length() == 0 || variantPath.equals(path)) {
            return r;
        }
        Upstream v = fetch(variantPath, null);
        if (v.status != 200 || v.payload == null || v.payload.length == 0) {
            L.w("AuthProxy", "master 跟随失败 " + safePath(variantPath) + " → " + v.status);
            return r;
        }
        // 变体在子目录时，媒体列表里的相对分片名要相对子目录解析；
        // 播放器按原路径解析，因此把子目录前缀补到分片行上。
        String baseDir = dirOf(path);
        String varDir = dirOf(variantPath);
        String prefix = "";
        if (varDir.length() > baseDir.length() && varDir.startsWith(baseDir)) {
            prefix = varDir.substring(baseDir.length());
        }
        String body = new String(v.payload, UTF_8);
        if (path.toLowerCase(java.util.Locale.US).indexOf("/videos/") >= 0) {
            body = compactJellyfinPlaylist(body);
        }
        StringBuilder sb = new StringBuilder(body.length() + 64);
        String[] bodyLines = body.split("\r?\n", -1);
        for (int i = 0; i < bodyLines.length; i++) {
            String s = bodyLines[i].trim();
            if (s.length() == 0) {
                sb.append('\n');
            } else if (s.charAt(0) == '#') {
                sb.append(s).append('\n');
            } else {
                sb.append(prefix).append(s).append('\n');
            }
        }
        v.payload = sb.toString().getBytes(UTF_8);
        L.i("AuthProxy", "master 列表跟随首个变体 " + safePath(ref));
        return v;
    }

    private static boolean isFnosLongRequest(String path) {
        if (path == null || path.indexOf("/v/media/") < 0) {
            return false;
        }
        int queryAt = path.indexOf('?');
        return queryAt >= 0 && "1".equals(queryParameter(
                path.substring(queryAt + 1), "long"));
    }

    private static String withoutLongQuery(String path) {
        if (path == null) {
            return "";
        }
        int queryAt = path.indexOf('?');
        if (queryAt < 0) {
            return path;
        }
        String[] pairs = path.substring(queryAt + 1).split("&");
        StringBuilder out = new StringBuilder(path.length());
        out.append(path, 0, queryAt);
        boolean first = true;
        for (int i = 0; i < pairs.length; i++) {
            if (pairs[i].startsWith("long=")) {
                continue;
            }
            out.append(first ? '?' : '&').append(pairs[i]);
            first = false;
        }
        return out.toString();
    }

    private static String virtualSegmentName(String path) {
        if (path == null) {
            return null;
        }
        int queryAt = path.indexOf('?');
        String p = queryAt >= 0 ? path.substring(0, queryAt) : path;
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        if (!name.startsWith("v") || !name.endsWith(".ts")) {
            return null;
        }
        String number = name.substring(1, name.length() - 3);
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) {
                return null;
            }
        }
        return name;
    }

    private static int virtualSegmentIndex(String name) {
        try {
            return Integer.parseInt(name.substring(1, name.lastIndexOf('.')));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String virtualSegmentName(int group) {
        return String.format(java.util.Locale.US, "v%05d.ts", Integer.valueOf(group));
    }

    private FnosPlaylist parseFnosPlaylist(String basePath, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        String text = new String(payload, UTF_8);
        if (text.indexOf("#EXT-X-KEY") >= 0 || text.indexOf("#EXT-X-BYTERANGE") >= 0
                || text.indexOf("#EXT-X-MAP") >= 0) {
            return null;
        }
        FnosPlaylist playlist = new FnosPlaylist();
        playlist.basePath = basePath;
        int duration = 2000;
        int cumulative = 0;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String number = comma > 8 ? line.substring(8, comma) : line.substring(8);
                try {
                    duration = Math.max(1, (int) Math.round(Double.parseDouble(number.trim()) * 1000.0));
                } catch (Throwable t) {
                    duration = 2000;
                }
                continue;
            }
            if (line.charAt(0) == '#' || segmentName(line) == null) {
                continue;
            }
            playlist.refs.add(line);
            playlist.durationsMs.add(Integer.valueOf(duration));
            playlist.startsMs.add(Integer.valueOf(cumulative));
            cumulative += duration;
        }
        playlist.startsMs.add(Integer.valueOf(cumulative));
        return playlist.refs.isEmpty() ? null : playlist;
    }

    private byte[] buildFnosVirtualPlaylist(String basePath, byte[] original) {
        FnosPlaylist parsed = parseFnosPlaylist(basePath, original);
        if (parsed == null) {
            virtualModeFailed = true;
            L.w("AuthProxy", "飞牛长分片模式不可用，回退兼容播放列表");
            return "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(UTF_8);
        }
        synchronized (virtualLock) {
            fnosPlaylist = parsed;
            fnosOriginalPlaylistPath = basePath;
            fnosOriginalPlaylistPayload = original;
        }
        StringBuilder out = new StringBuilder(original.length + 128);
        out.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:")
                .append((FNOS_LONG_GROUP_MS + 999L) / 1000L)
                .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n\n");
        int groups = parsed.groupCount();
        for (int group = 0; group < groups; group++) {
            out.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f",
                    parsed.groupDurationMs(group) / 1000.0)).append(",\n")
                    .append(virtualSegmentName(group)).append("?long=1\n");
        }
        out.append("#EXT-X-ENDLIST\n");
        L.i("AuthProxy", "飞牛虚拟长分片 每片5段 共" + groups + "片");
        return out.toString().getBytes(UTF_8);
    }

    private void prefetchFnosLong(String basePath) {
        FnosPlaylist playlist = fnosPlaylist;
        if (playlist == null || segDir == null) {
            return;
        }
        lastMediaPlaylistPath = basePath;
        lastMediaPlaylistPayload = fnosOriginalPlaylistPayload;
        String tag = sessionTag(basePath);
        if (tag.equals(prefetchSeededDir)) {
            return;
        }
        prefetchSeededDir = tag;
        int group = (int) (Math.max(0L, startPositionMs) / FNOS_LONG_GROUP_MS);
        requestVirtualSegment(virtualSegmentName(group));
        L.i("AuthProxy", "预取虚拟分片 " + group + "（" + (startPositionMs / 1000L) + "s 起）");
    }

    private File cachedVirtualSegment(String name) {
        FnosPlaylist playlist = fnosPlaylist;
        File dir = segDir;
        if (playlist == null || dir == null || name == null) {
            return null;
        }
        File file = new File(dir, segCacheKey(playlist.basePath, name));
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        return null;
    }

    private SegTask requestVirtualSegment(String name) {
        FnosPlaylist playlist = fnosPlaylist;
        File dir = segDir;
        int group = virtualSegmentIndex(name);
        if (playlist == null || dir == null || group < 0 || group >= playlist.groupCount()) {
            return null;
        }
        String key = segCacheKey(playlist.basePath, name);
        synchronized (virtualLock) {
            SegTask task = virtualTasks.get(key);
            if (task != null) {
                return task;
            }
            File file = new File(dir, key);
            if (file.exists() && file.length() > 0) {
                return null;
            }
            task = new SegTask();
            task.name = key;
            task.file = file;
            task.index = group;
            task.requestedAt = System.currentTimeMillis();
            virtualTasks.put(key, task);
            ensureVirtualPool();
            final FnosPlaylist source = playlist;
            final SegTask pending = task;
            if (virtualPool != null) {
                try {
                    virtualPool.execute(new Runnable() {
                        public void run() {
                            downloadVirtualSegment(source, pending);
                        }
                    });
                } catch (Throwable ignored) {
                    pending.failed = true;
                }
            }
            return task;
        }
    }

    private void ensureVirtualPool() {
        if (virtualPool != null) {
            return;
        }
        virtualPool = Executors.newFixedThreadPool(1, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "fntv-virtual-segment");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private void downloadVirtualSegment(FnosPlaylist playlist, SegTask task) {
        File tmp = new File(task.file.getPath() + "." + task.requestedAt + ".part");
        FileOutputStream out = null;
        boolean ok = false;
        int[] continuity = new int[8192];
        boolean[] seen = new boolean[8192];
        try {
            out = new FileOutputStream(tmp);
            String base = playlist.basePath.substring(0, playlist.basePath.lastIndexOf('/') + 1);
            int start = playlist.groupStart(task.index);
            int end = playlist.groupEnd(task.index);
            for (int i = start; i < end; i++) {
                if (task.cancelled || !running) {
                    throw new IOException("virtual segment cancelled");
                }
                String ref = playlist.refs.get(i);
                File source = cachedSegment(base + ref, ref);
                SegTask pending = null;
                if (source == null) {
                    pending = requestSegment(base + ref, segmentName(ref));
                    if (pending != null) {
                        waitSegment(pending, SEG_WAIT_WARM_MS);
                    }
                    source = pending != null ? pending.file : cachedSegment(base + ref, ref);
                }
                if (source == null || !source.exists() || source.length() == 0
                        || pendingFailed(pending)) {
                    throw new IOException("source segment unavailable " + ref);
                }
                appendRewrittenTs(source, out, continuity, seen);
            }
            if (task.cancelled || !running) {
                throw new IOException("virtual segment cancelled");
            }
            out.close();
            out = null;
            if (task.file.exists() && !task.file.delete()) {
                throw new IOException("cannot replace virtual segment");
            }
            ok = tmp.renameTo(task.file);
            if (!ok) {
                ok = copyFile(tmp, task.file);
                tmp.delete();
            }
        } catch (Throwable t) {
            String message = t.getMessage();
            if (message != null && (message.indexOf("TS sync") >= 0
                    || message.indexOf("TS packet") >= 0)) {
                virtualModeFailed = true;
            }
            L.w("AuthProxy", "虚拟分片 " + task.name + " 合并失败：" + message);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (!ok) {
                tmp.delete();
            }
            synchronized (task) {
                task.done = ok;
                task.failed = !ok;
                task.notifyAll();
            }
            synchronized (virtualLock) {
                if (virtualTasks.get(task.name) == task) {
                    virtualTasks.remove(task.name);
                }
            }
            if (ok) {
                captureTsMetadata(task.file);
                evictSegments();
            }
        }
    }

    private static boolean pendingFailed(SegTask task) {
        return task != null && task.failed;
    }

    private static void appendRewrittenTs(File source, OutputStream out,
                                          int[] continuity, boolean[] seen) throws IOException {
        FileInputStream in = new FileInputStream(source);
        try {
            byte[] buffer = new byte[188 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                int offset = 0;
                while (offset + 188 <= count) {
                    if ((buffer[offset] & 0xFF) != 0x47) {
                        throw new IOException("invalid TS sync byte");
                    }
                    int pid = ((buffer[offset + 1] & 0x1F) << 8)
                            | (buffer[offset + 2] & 0xFF);
                    int adaptation = (buffer[offset + 3] >> 4) & 0x03;
                    boolean hasPayload = adaptation == 1 || adaptation == 2;
                    if (hasPayload && pid != 0x1FFF) {
                        int next = seen[pid] ? (continuity[pid] + 1) & 0x0F : 0;
                        buffer[offset + 3] = (byte) ((buffer[offset + 3] & 0xF0) | next);
                        continuity[pid] = next;
                        seen[pid] = true;
                    }
                    offset += 188;
                }
                if (offset != count) {
                    throw new IOException("TS packet is not 188-byte aligned");
                }
                out.write(buffer, 0, count);
            }
        } finally {
            in.close();
        }
    }

    private void prefetchVirtualAhead(int group) {
        if (fnosPlaylist != null && group >= 0 && group < fnosPlaylist.groupCount()) {
            requestVirtualSegment(virtualSegmentName(group));
        }
    }

    public boolean virtualModeFailed() {
        return virtualModeFailed;
    }

    private static String playlistCacheKey(String path) {
        if (path == null) {
            return "";
        }
        int queryAt = path.indexOf('?');
        if (queryAt < 0) {
            return path;
        }
        String window = queryParameter(path.substring(queryAt + 1), "window");
        return window.length() == 0 ? path : path + "#window=" + window;
    }

    private Upstream sliceFnosPlaylist(String path, Upstream r) {
        if (r.status != 200 || r.payload == null || r.payload.length == 0
                || path.indexOf("/v/media/") < 0) {
            return r;
        }
        int queryAt = path.indexOf('?');
        String windowValue = queryParameter(queryAt < 0 ? "" : path.substring(queryAt + 1),
                "window");
        if (windowValue.length() == 0) {
            return r;
        }
        int window;
        try {
            window = Integer.parseInt(windowValue);
        } catch (NumberFormatException e) {
            return r;
        }
        if (window < 0) {
            return r;
        }
        String[] lines = new String(r.payload, UTF_8).split("\r?\n", -1);
        int total = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0 && line.charAt(0) != '#') {
                total++;
            }
        }
        if (total <= FNOS_PLAYLIST_WINDOW_SEGMENTS) {
            return r;
        }
        int begin = window * FNOS_PLAYLIST_WINDOW_SEGMENTS;
        int end = Math.min(total, begin + FNOS_PLAYLIST_WINDOW_SEGMENTS);
        if (begin >= total) {
            r.status = 404;
            r.payload = new byte[0];
            r.declaredLength = 0;
            return r;
        }
        StringBuilder out = new StringBuilder(r.payload.length + 64);
        int index = 0;
        String pendingExtinf = null;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                pendingExtinf = line;
                continue;
            }
            if (line.length() == 0) {
                out.append('\n');
                continue;
            }
            if (line.charAt(0) == '#') {
                out.append(line).append('\n');
                continue;
            }
            if (index >= begin && index < end) {
                if (pendingExtinf != null) {
                    out.append(pendingExtinf).append('\n');
                }
                out.append(line).append('\n');
            }
            index++;
            pendingExtinf = null;
        }
        r.payload = out.toString().getBytes(UTF_8);
        r.declaredLength = r.payload.length;
        L.i("AuthProxy", "播放列表分段 window=" + window + " 分片=" + begin
                + "~" + (end - 1) + " 总数=" + total);
        return r;
    }

    /** 取路径的目录部分（含结尾斜杠），如 /v/media/h/p.m3u8 → /v/media/h/。 */
    private static String dirOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    static TsMetadata parseTsMetadata(byte[] data) {
        if (data == null || data.length < 188) return null;
        int limit = Math.min(data.length, 2048);
        for (int at = 0; at + 188 <= limit; at += 188) {
            if ((data[at] & 0xff) != 0x47) continue;
            int adaptation = (data[at + 3] & 0xff) & 0x30;
            int payload = 4;
            if (adaptation == 0x20 || adaptation == 0x30) {
                if (payload >= 188) continue;
                payload = 5 + (data[payload] & 0xff);
            }
            if (payload >= 188) continue;
            if ((data[at + 3] & 0x40) != 0) {
                payload += 1 + (data[payload] & 0xff);
            } else if (payload + 3 < 188 && (data[at + payload] & 0xff) == 0
                    && (data[at + payload + 1] & 0xff) == 2
                    && (data[at + payload + 2] & 0xff) == 0xb0) {
                payload++;
            }
            if (payload + 4 > 188 || (data[at + payload] & 0xff) != 2
                    || (data[at + payload + 1] & 0xff) != 0xb0) {
                continue;
            }
            int sectionAt = at + payload;
            int sectionLength = ((data[sectionAt + 1] & 0x0f) << 8)
                    | (data[sectionAt + 2] & 0xff);
            int sectionEnd = Math.min(at + 188, sectionAt + 3 + sectionLength);
            if (sectionAt + 12 > sectionEnd) continue;
            int programInfoLength = ((data[sectionAt + 10] & 0x0f) << 8)
                    | (data[sectionAt + 11] & 0xff);
            int streamAt = sectionAt + 12 + programInfoLength;
            boolean video = false;
            boolean audio = false;
            String videoCodec = "";
            String audioCodec = "";
            while (streamAt + 5 <= sectionEnd) {
                int type = data[streamAt] & 0xff;
                int infoLength = ((data[streamAt + 3] & 0x0f) << 8)
                        | (data[streamAt + 4] & 0xff);
                if (isVideoStreamType(type)) {
                    video = true;
                    if (videoCodec.length() == 0) videoCodec = videoCodecOf(type);
                } else if (isAudioStreamType(type)) {
                    audio = true;
                    if (audioCodec.length() == 0) audioCodec = audioCodecOf(type);
                }
                streamAt += 5 + infoLength;
            }
            if (video || audio) return new TsMetadata(video, audio, videoCodec, audioCodec);
        }
        return null;
    }

    private static boolean isVideoStreamType(int type) {
        return type == 0x01 || type == 0x02 || type == 0x10 || type == 0x1b
                || type == 0x1e || type == 0x20 || type == 0x24 || type == 0x42;
    }

    private static boolean isAudioStreamType(int type) {
        return type == 0x03 || type == 0x04 || type == 0x0f || type == 0x11
                || type == 0x81 || type == 0x82 || type == 0x83 || type == 0x86
                || type == 0x87 || type == 0x8a;
    }

    private static String videoCodecOf(int type) {
        if (type == 0x1b) return "h264";
        if (type == 0x1e || type == 0x24) return "hevc";
        if (type == 0x20) return "mvc";
        if (type == 0x10) return "mpeg4";
        if (type == 0x42) return "avs";
        return "mpeg2";
    }

    private static String audioCodecOf(int type) {
        if (type == 0x81) return "ac3";
        if (type == 0x82) return "dts";
        if (type == 0x83 || type == 0x87) return "eac3";
        if (type == 0x0f || type == 0x11) return "aac";
        if (type == 0x86) return "truehd";
        if (type == 0x8a) return "dts";
        return "mp3";
    }

    static String compactJellyfinPlaylist(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\r?\n");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.charAt(0) == '#') {
                out.append(line).append('\n');
                continue;
            }
            int queryAt = line.indexOf('?');
            if (queryAt < 0) {
                out.append(line).append('\n');
                continue;
            }
            String resource = line.substring(0, queryAt);
            String runtime = "";
            String length = "";
            String[] pairs = line.substring(queryAt + 1).split("&");
            for (int j = 0; j < pairs.length; j++) {
                String pair = pairs[j];
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                if ("runtimeTicks".equalsIgnoreCase(key) || "rt".equalsIgnoreCase(key)) {
                    runtime = value;
                } else if ("actualSegmentLengthTicks".equalsIgnoreCase(key)
                        || "sl".equalsIgnoreCase(key)) {
                    length = value;
                }
            }
            out.append(resource);
            boolean hasQuery = false;
            if (runtime.length() > 0) {
                out.append("?rt=").append(runtime);
                hasQuery = true;
            }
            if (length.length() > 0) {
                out.append(hasQuery ? "&sl=" : "?sl=").append(length);
            }
            out.append('\n');
        }
        return out.toString();
    }

    static String sanitizeJellyfinSegmentPath(String path) {
        return sanitizeJellyfinSegmentPath(path, null);
    }

    static String sanitizeJellyfinSegmentPath(String path, String masterQuery) {
        if (path == null) {
            return null;
        }
        int queryAt = path.indexOf('?');
        String resource = queryAt < 0 ? path : path.substring(0, queryAt);
        String lower = resource.toLowerCase(java.util.Locale.US);
        if (lower.indexOf("/videos/") < 0 || lower.indexOf("/hls") < 0
                || !lower.endsWith(".ts")) {
            return path;
        }
        String sourceId = "";
        int sourceAt = lower.indexOf("/videos/") + "/videos/".length();
        int sourceEnd = lower.indexOf('/', sourceAt);
        if (sourceEnd > sourceAt) {
            sourceId = resource.substring(sourceAt, sourceEnd);
        }
        StringBuilder kept = new StringBuilder(resource);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        boolean hasSourceId = false;
        String[] pairs = queryAt < 0 ? new String[0] : path.substring(queryAt + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            if ("mediaSourceId".equalsIgnoreCase(key)) {
                kept.append(kept.indexOf("?") >= 0 ? '&' : '?').append(pair);
                hasSourceId = true;
                seen.add("mediasourceid");
            } else if ("runtimeTicks".equalsIgnoreCase(key) || "rt".equalsIgnoreCase(key)) {
                kept.append(kept.indexOf("?") >= 0 ? '&' : '?')
                        .append("runtimeTicks=").append(value);
                seen.add("runtimeticks");
            } else if ("actualSegmentLengthTicks".equalsIgnoreCase(key)
                    || "sl".equalsIgnoreCase(key)) {
                kept.append(kept.indexOf("?") >= 0 ? '&' : '?')
                        .append("actualSegmentLengthTicks=").append(value);
                seen.add("actualsegmentlengthticks");
            } else if ("ApiKey".equalsIgnoreCase(key)) {
                kept.append(kept.indexOf("?") >= 0 ? '&' : '?').append(pair);
                seen.add("apikey");
            }
        }
        if (!hasSourceId && sourceId.length() > 0) {
            String masterSourceId = queryParameter(masterQuery, "mediaSourceId");
            String selectedSourceId = masterSourceId.length() > 0 ? masterSourceId : sourceId;
            kept.append(kept.indexOf("?") >= 0 ? '&' : '?')
                    .append("mediaSourceId=").append(selectedSourceId);
            seen.add("mediasourceid");
        }
        if (masterQuery != null && masterQuery.length() > 0) {
            String[] profile = masterQuery.split("&");
            for (int i = 0; i < profile.length; i++) {
                String pair = profile[i];
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                String normalized = key.toLowerCase(java.util.Locale.US);
                if (isSegmentProfileKey(normalized) && !seen.contains(normalized)) {
                    kept.append(kept.indexOf("?") >= 0 ? '&' : '?').append(pair);
                    seen.add(normalized);
                }
            }
        }
        return kept.toString();
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.length() == 0) return "";
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equalsIgnoreCase(key)) {
                return equals < 0 ? "" : pair.substring(equals + 1);
            }
        }
        return "";
    }

    private static String segmentProfileQuery(String path) {
        int at = path == null ? -1 : path.indexOf('?');
        if (at < 0) return "";
        StringBuilder out = new StringBuilder();
        String[] pairs = path.substring(at + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (isSegmentProfileKey(key.toLowerCase(java.util.Locale.US))) {
                if (out.length() > 0) out.append('&');
                out.append(pair);
            }
        }
        return out.toString();
    }

    private static boolean isSegmentProfileKey(String key) {
        return key.length() > 0 && !"starttimeticks".equals(key)
                && !"runtimeticks".equals(key) && !"actualsegmentlengthticks".equals(key)
                && !"rt".equals(key) && !"sl".equals(key);
    }

    /** 用 http 基准解析播放列表内的相对引用，保留路径与查询参数。 */
    private static String resolvePath(String basePath, String ref) {
        try {
            URL u = new URL(new URL("http://proxy" + basePath), ref);
            String f = u.getFile();
            if (f == null || f.length() == 0) {
                return "";
            }
            return f.startsWith("/") ? f : "/" + f;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 带 Authorization 头转发给 NAS；不直接透传上游 Content-Length。 */
    private Upstream fetch(String path, String range) {
        String used = token;
        Upstream r = fetch(path, range, used);
        if (!isAuthFailed(r)) {
            return r;
        }
        // 真机实测：token 失效时 /v/media/* 与 /media/range/* 都返回
        // HTTP 200 + {"code":-2,"msg":"Auth Failed"}。这里必须重新登录，
        // 否则 43 字节 JSON 会被播放器当成分片内容，表现为「授权掉了/播放中断」。
        String fresh = refreshToken(used);
        if (fresh.length() > 0 && !fresh.equals(used)) {
            L.w("AuthProxy", "媒体流鉴权失败，已重新登录，用新 token 重试 " + safePath(path));
            r = fetch(path, range, fresh);
        }
        if (isAuthFailed(r)) {
            L.w("AuthProxy", "鉴权失败且无法自动续登 " + safePath(path) + "，如实回 401");
            r.status = 401;
        }
        return r;
    }

    /**
     * 用 {@link TokenSource} 重新登录。并发时只让第一个失败请求真正登录，
     * 其余线程直接复用刷新后的 token。
     */
    private String refreshToken(String stale) {
        synchronized (authLock) {
            if (!token.equals(stale)) {
                return token;
            }
            TokenSource src = tokens;
            if (src == null) {
                return "";
            }
            String fresh = "";
            try {
                fresh = src.refreshToken();
            } catch (Throwable t) {
                L.w("AuthProxy", "重新登录异常：" + t);
            }
            if (fresh != null && fresh.length() > 0) {
                token = fresh;
            }
            return fresh == null ? "" : fresh;
        }
    }

    /** 服务端以 200 + 极短 JSON 表达鉴权失败，不能只看状态码。 */
    private static boolean isAuthFailed(Upstream r) {
        if (r == null) {
            return false;
        }
        if (r.status == 401) {
            return true;
        }
        if (r.status != 200 || r.payload == null || r.payload.length > 512) {
            return false;
        }
        String body = new String(r.payload, UTF_8);
        return body.indexOf("\"code\":-2") >= 0 || body.indexOf("\"code\": -2") >= 0;
    }

    /**
     * 读超时按资源大小分层。
     *
     * <p>WebVTT 分片由 NAS 按需生成，正常几十毫秒；但换会话/转码争用时会挂到
     * 6~12 秒（真机对比：6 秒超时导致 30 分钟里 26 个分片补拉失败，
     * 其中 2 个永久丢失）。客户端已经把预取拉到 60 秒前，这里放宽到 10 秒，
     * 让偶发慢请求直接成功，而不是被砍掉后再排队重试。</p>
     */
    private static int readTimeoutFor(String path) {
        String p = path == null ? "" : path.toLowerCase();
        if (p.indexOf(".vtt") >= 0) {
            return 10000;
        }
        if (p.indexOf(".m3u8") >= 0) {
            return 12000;
        }
        return 20000;
    }

    private HttpURLConnection open(String path, String range, String tokenValue) throws IOException {
        path = sanitizeJellyfinSegmentPath(path, jellyfinSegmentQuery);
        HttpURLConnection conn = (HttpURLConnection) new URL(upstreamBase + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeoutFor(path));
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Authorization", tokenValue == null ? "" : tokenValue);
        conn.setRequestProperty("Connection", "close");
        if (range != null && range.length() > 0) {
            conn.setRequestProperty("Range", range);
        }
        return conn;
    }

    /** 直接解析原始头，避开 HttpURLConnection.getContentLength() 的 int 截断（>2GB 返回 -1）。 */
    private static long contentLengthOf(HttpURLConnection conn) {
        String v = conn.getHeaderField("Content-Length");
        if (v == null) {
            return -1L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 媒体数据流：只有分片/原始流走这里，边读边写，不再受 {@link #MAX_PAYLOAD} 限制。
     * 真机实测《JOJO》1080p 直连分片 4.2~5.5MB，旧实现截断到 4MB 后
     * MediaPlayer 播几秒就卡住（日志「播放进度未推进」），并非授权问题。
     */
    private static final class MediaStream {
        int status;
        long length;
        String contentRange;
        byte[] head;
        InputStream body;
        HttpURLConnection conn;
    }

    private static boolean isMediaBody(String path) {
        String p = path.toLowerCase();
        if (p.indexOf(".m3u8") >= 0) {
            return false;
        }
        // HLS 的 WebVTT 分片只有几百字节，且只由客户端 HlsVtt 独占请求。
        // 走缓冲路径（fetch）可以一次拿到完整结果：超时 6 秒就干净收尾，
        // 不会像流式转发那样在客户端放弃后继续向 NAS 占用连接 20 秒。
        if (p.indexOf(".vtt") >= 0) {
            return false;
        }
        return p.indexOf("/v/media/") >= 0 || p.indexOf("/media/range/") >= 0
                || p.indexOf("/videos/") >= 0 || p.indexOf("/livetv/") >= 0;
    }

    // ------------------------------------------------------------------
    // 起播观测 / 分片预缓存 / 播放列表去重
    // ------------------------------------------------------------------

    /** App 启动时传入缓存根目录（通常是 getCacheDir()/segments）。 */
    public void setCacheRoot(File root) {
        this.cacheRoot = root;
    }

    /** 建方案时告知起播/续播位置，预取从对应分片开始而不是从 0 开始。 */
    public void setStartPositionMs(long ms) {
        this.startPositionMs = ms < 0 ? 0L : ms;
    }

    /** 换片时切换缓存命名空间；同一部影片的会话续期（hash 变化）不会清缓存。 */
    public void setCacheNamespace(String key) {
        File root = cacheRoot;
        if (root == null) {
            return;
        }
        String safe = sanitize(key);
        File dir = new File(root, safe.length() == 0 ? "default" : safe);
        File old;
        ExecutorService oldPool;
        synchronized (segLock) {
            old = segDir;
            if (dir.equals(old)) {
                return;
            }
            for (SegTask task : segTasks.values()) {
                task.cancelled = true;
            }
            segTasks.clear();
            oldPool = segPool;
            segPool = null;
            segDir = dir;
            tsMetadata = null;
            jellyfinSegmentQuery = "";
            lastMediaPlaylistPath = "";
            lastMediaPlaylistPayload = null;
            prefetchSeededDir = "";
            synchronized (networkLock) {
                networkSamples.clear();
                networkPeakKbps = 0;
            }
        }
        if (oldPool != null) {
            oldPool.shutdownNow();
        }
        dir.mkdirs();
        if (old != null && !old.equals(dir)) {
            deleteRecursive(old);
        }
        L.i("AuthProxy", "分片预缓存目录 " + dir);
    }

    /** 退出播放器时清理：避免缓存文件长期占着电视存储。 */
    public void clearSegmentCache() {
        cancelPrefetchTasks();
        File dir;
        synchronized (segLock) {
            dir = segDir;
            segDir = null;
            prefetchSeededDir = "";
            lastMediaPlaylistPath = "";
            lastMediaPlaylistPayload = null;
            synchronized (networkLock) {
                networkSamples.clear();
                networkPeakKbps = 0;
            }
        }
        synchronized (virtualLock) {
            fnosPlaylist = null;
            fnosOriginalPlaylistPath = "";
            fnosOriginalPlaylistPayload = null;
            virtualModeFailed = false;
        }
        if (dir != null) {
            deleteRecursive(dir);
        }
    }

    /** prepare 看门狗用的实时状态：有没有在途媒体请求、最近一次收到字节过了多久。 */
    public MediaStatus mediaStatus() {
        long now = System.currentTimeMillis();
        long idle = mediaLastActivityMs == 0L ? Long.MAX_VALUE : now - mediaLastActivityMs;
        long waiting = mediaWaitingSinceMs == 0L ? 0L : now - mediaWaitingSinceMs;
        return new MediaStatus(mediaInFlight.get(), idle, waiting, mediaRequests, mediaFailures,
                networkKbps(now));
    }

    private void markMediaActivity() {
        mediaLastActivityMs = System.currentTimeMillis();
    }

    private void markNetworkBytes(int bytes) {
        if (bytes <= 0) {
            return;
        }
        synchronized (networkLock) {
            long now = System.currentTimeMillis();
            while (!networkSamples.isEmpty()
                    && now - networkSamples.peekFirst().atMs > NETWORK_WINDOW_MS) {
                networkSamples.removeFirst();
            }
            networkSamples.addLast(new NetworkSample(now, bytes));
        }
    }

    private int networkKbps(long now) {
        synchronized (networkLock) {
            while (!networkSamples.isEmpty()
                    && now - networkSamples.peekFirst().atMs > NETWORK_WINDOW_MS) {
                networkSamples.removeFirst();
            }
            if (networkSamples.isEmpty()) {
                return 0;
            }
            long bytes = 0L;
            for (NetworkSample sample : networkSamples) {
                bytes += sample.bytes;
            }
            int current = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                    bytes * 8L / NETWORK_WINDOW_MS));
            return current;
        }
    }

    /** 客户端断连日志做 10 秒节流：播放器换分片时每次都会主动断连，不能刷屏。 */
    private void logClientAbort(String path, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastClientAbortLogAt < 10000L) {
            return;
        }
        lastClientAbortLogAt = now;
        L.d("AuthProxy", "客户端提前断开 " + safePath(path) + "（"
                + (t == null ? "" : t.getMessage()) + "），静默收尾");
    }

    /** 播放器提前放弃连接（EPIPE/ECONNRESET/Socket closed），不是代理故障。 */
    private static boolean isClientAbort(Throwable t) {
        if (t instanceof SocketException) {
            return true;
        }
        String m = t.getMessage();
        if (m == null) {
            return false;
        }
        m = m.toLowerCase(java.util.Locale.US);
        return m.indexOf("epipe") >= 0 || m.indexOf("broken pipe") >= 0
                || m.indexOf("connection reset") >= 0 || m.indexOf("socket closed") >= 0
                || m.indexOf("enotconn") >= 0;
    }

    private static boolean isPlaylist(String path) {
        return path != null && path.toLowerCase().indexOf(".m3u8") >= 0;
    }

    private static boolean isHeadProbeRange(String range) {
        if (range == null || !range.startsWith("bytes=0-")) {
            return false;
        }
        int dash = range.indexOf('-', 6);
        if (dash < 0) {
            return false;
        }
        try {
            long end = Long.parseLong(range.substring(dash + 1).trim());
            return end >= 0L && end < 96L * 1024L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 只有 /v/media/<hash>/NNNNN.ts 这类纯数字分片才进预缓存。 */
    private static String segmentName(String path) {
        if (path == null) {
            return null;
        }
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !name.substring(dot).equalsIgnoreCase(".ts")) {
            return null;
        }
        for (int i = 0; i < dot; i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return null;
            }
        }
        return name;
    }

    private static int segIndex(String name) {
        try {
            String number = name.substring(0, name.lastIndexOf('.'));
            if (number.startsWith("v")) {
                number = number.substring(1);
            }
            return Integer.parseInt(number);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 缓存文件名形如 &lt;sessionTag&gt;_00024.ts，取出分片序号。 */
    private static int cacheIndexOf(String fileName) {
        int us = fileName.lastIndexOf('_');
        return segIndex(us >= 0 ? fileName.substring(us + 1) : fileName);
    }

    /**
     * 缓存文件名带会话目录 tag：会话续期后即使分片编号相同，内容也可能不同
     * （实测旧会话 00024.ts 1.97MB、新会话 1.65MB），绝不能按纯文件名复用。
     */
    private static String sessionTag(String effectivePath) {
        String dir = SessionPolicy.mediaDirOf(effectivePath);
        String tag;
        if (dir.length() == 0) {
            tag = "root";
        } else {
            int slash = dir.lastIndexOf('/');
            tag = dir.substring(slash + 1);
        }
        int query = effectivePath.indexOf('?');
        if (query >= 0) {
            tag += "_" + Integer.toHexString(effectivePath.substring(query).hashCode());
        }
        return tag;
    }

    private static String segCacheKey(String effectivePath, String name) {
        return sessionTag(effectivePath) + "_" + name;
    }

    private File cachedSegment(String effectivePath, String path) {
        String name = segmentName(path);
        File dir = segDir;
        if (name == null || dir == null) {
            return null;
        }
        File f = new File(dir, segCacheKey(effectivePath, name));
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    private SegTask requestSegment(String effectivePath, String path) {
        String name = segmentName(path);
        File dir = segDir;
        if (name == null || dir == null) {
            return null;
        }
        String key = segCacheKey(effectivePath, name);
        synchronized (segLock) {
            SegTask t = segTasks.get(key);
            if (t != null) {
                if (!t.done || (t.file.exists() && t.file.length() > 0)) {
                    return t;
                }
                segTasks.remove(key);
            }
            File f = new File(dir, key);
            if (f.exists() && f.length() > 0) {
                return null;
            }
            t = new SegTask();
            t.name = key;
            t.file = f;
            t.index = segIndex(name);
            t.requestedAt = System.currentTimeMillis();
            segTasks.put(key, t);
            ensureSegPool();
            final SegTask task = t;
            final String up = effectivePath;
            if (segPool != null) {
                try {
                    segPool.execute(new Runnable() {
                        public void run() {
                            downloadSegment(up, task);
                        }
                    });
                } catch (Throwable ignored) {
                    // 线程池已被关闭（换片/退出）：让请求退回直通路径
                }
            }
            return t;
        }
    }

    /** 会话续期后旧会话的预取已无意义：立刻放弃，别占着单线程队列拖住新会话。 */
    private void cancelPrefetchTasks() {
        ExecutorService p;
        ExecutorService vp;
        synchronized (segLock) {
            for (SegTask t : segTasks.values()) {
                t.cancelled = true;
            }
            segTasks.clear();
            p = segPool;
            segPool = null;
        }
        synchronized (virtualLock) {
            for (SegTask task : virtualTasks.values()) {
                task.cancelled = true;
            }
            virtualTasks.clear();
            vp = virtualPool;
            virtualPool = null;
        }
        if (p != null) {
            p.shutdownNow();
        }
        if (vp != null) {
            vp.shutdownNow();
        }
    }

    private void ensureSegPool() {
        if (segPool != null) {
            return;
        }
        segPool = Executors.newFixedThreadPool(1, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fntv-seg-prefetch");
                t.setDaemon(true);
                return t;
            }
        });
    }

    private boolean waitSegment(SegTask task, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (task) {
            while (!task.done && !task.failed && !task.cancelled) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    break;
                }
                try {
                    task.wait(Math.min(remain, 500L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return task.done && !task.cancelled && task.file.exists() && task.file.length() > 0;
    }

    /** 单线程顺序下载一个分片；NAS 还没转出来就按指数退避重试，直到预热窗口结束。 */
    private void downloadSegment(String path, SegTask task) {
        final File tmp = new File(task.file.getPath() + ".part");
        long deadline = System.currentTimeMillis()
                + (task.index >= 0 && task.index < SEG_PREFETCH_COUNT ? SEG_RETRY_WARM_MS : SEG_RETRY_MS);
        long backoff = 800L;
        boolean ok = false;
        L.d("AuthProxy", "开始预取分片 " + task.name);
        while (running && !task.cancelled) {
            HttpURLConnection conn = null;
            FileOutputStream fos = null;
            boolean authFailed = false;
            try {
                // 会话续期后旧 hash 会 410：每次尝试前先映射到当前会话目录
                String dir = SessionPolicy.mediaDirOf(path);
                if (dir.length() > 0) {
                    String fresh = resolveDir(dir, false);
                    if (fresh.length() > 0 && !fresh.equals(dir)) {
                        path = SessionPolicy.remap(path, dir, fresh);
                    }
                }
                conn = open(path, null, token);
                int st = conn.getResponseCode();
                if (st == 200 || st == 206) {
                    InputStream in = conn.getInputStream();
                    fos = new FileOutputStream(tmp);
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    long total = 0L;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        total += n;
                        markMediaActivity();
                        markNetworkBytes(n);
                    }
                    fos.close();
                    fos = null;
                    in.close();
                    if (total > 0 && total <= 1024) {
                        authFailed = isAuthJson(readSmall(tmp));
                    }
                    if (!authFailed && total > 0) {
                        if (task.file.exists()) {
                            task.file.delete();
                        }
                        ok = tmp.renameTo(task.file);
                        if (!ok) {
                            ok = copyFile(tmp, task.file);
                            tmp.delete();
                        }
                    }
                    if (ok) {
                        captureTsMetadata(task.file);
                        break;
                    }
                }
            } catch (Throwable t) {
                if (!isClientAbort(t)) {
                    L.d("AuthProxy", "预取分片 " + task.name + " 未就绪：" + t.getMessage());
                }
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (authFailed) {
                refreshToken(token);
            }
            if (!running || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(4000L, backoff * 2);
        }
        synchronized (task) {
            task.done = ok;
            task.failed = !ok;
            task.notifyAll();
        }
        synchronized (segLock) {
            if (segTasks.get(task.name) == task) {
                segTasks.remove(task.name);
            }
        }
        if (ok) {
            L.i("AuthProxy", "分片预缓存就绪 " + task.name + "（" + task.file.length() + "B）");
            evictSegments();
        } else if (running) {
            L.d("AuthProxy", "分片预取放弃 " + task.name + "（超过 NAS 预热窗口）");
        }
    }

    /** 顺序预取接下来两个分片，保持起播/播放时的滚动缓冲。 */
    private void prefetchAhead(String effectivePath, String path) {
        String name = segmentName(path);
        if (name == null || path.indexOf('?') >= 0) {
            return;
        }
        int dot = name.lastIndexOf('.');
        int idx = segIndex(name);
        if (idx < 0) {
            return;
        }
        String digits = name.substring(0, dot);
        int width = digits.length();
        String base = effectivePath.substring(0, effectivePath.lastIndexOf('/') + 1);
        for (int k = 1; k <= 2; k++) {
            String next = pad(idx + k, width);
            if (next == null) {
                continue;
            }
            requestSegment(base + next + ".ts", next + ".ts");
        }
    }

    /** 播放列表到手即开始缓存首段若干分片（只有相对分片名才处理）。 */
    private void prefetchFromPlaylist(String effectivePath, byte[] payload) {
        if (segDir == null || payload == null || payload.length == 0) {
            return;
        }
        int queryAt = effectivePath.indexOf('?');
        if (effectivePath.indexOf("/v/media/") >= 0
                && queryParameter(queryAt < 0 ? "" : effectivePath.substring(queryAt + 1),
                "window").length() == 0) {
            return;
        }
        String tag = sessionTag(effectivePath);
        lastMediaPlaylistPath = effectivePath;
        lastMediaPlaylistPayload = payload;
        if (tag.equals(prefetchSeededDir)) {
            return;
        }
        prefetchSeededDir = tag;
        prefetchPlaylist(effectivePath, payload, startPositionMs, SEG_PREFETCH_COUNT);
    }

    public void warmSeek(long positionMs) {
        if (fnosPlaylist != null) {
            int group = (int) (Math.max(0L, positionMs) / FNOS_LONG_GROUP_MS);
            requestVirtualSegment(virtualSegmentName(group));
            return;
        }
        String path = lastMediaPlaylistPath;
        byte[] payload = lastMediaPlaylistPayload;
        if (path == null || path.length() == 0 || payload == null || segDir == null) {
            return;
        }
        prefetchPlaylist(path, payload, Math.max(0L, positionMs), SEEK_PREFETCH_COUNT);
    }

    private void prefetchPlaylist(String effectivePath, byte[] payload, long hint, int count) {
        String text = new String(payload, UTF_8);
        List<Integer> starts = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        int cumulative = 0;
        int duration = 2000;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String num = comma > 8 ? line.substring(8, comma) : line.substring(8);
                try {
                    duration = (int) Math.round(Double.parseDouble(num.trim()) * 1000.0);
                } catch (Throwable t) {
                    duration = 2000;
                }
                continue;
            }
            if (line.startsWith("#") || line.indexOf("://") >= 0) {
                continue;
            }
            String name = segmentName(line);
            if (name == null) {
                continue;
            }
            starts.add(Integer.valueOf(cumulative));
            names.add(line);
            cumulative += duration;
        }
        if (names.isEmpty()) {
            return;
        }
        int begin = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i).intValue() <= hint) {
                begin = i;
            } else {
                break;
            }
        }
        String base = effectivePath.substring(0, effectivePath.lastIndexOf('/') + 1);
        int queued = 0;
        int end = Math.min(names.size(), begin + count);
        for (int i = begin; i < end; i++) {
            String ref = names.get(i);
            if (requestSegment(base + ref, segmentName(ref)) != null) {
                queued++;
            }
        }
        if (queued > 0) {
            L.i("AuthProxy", "预取分片 " + begin + "~" + (end - 1) + "（"
                    + (hint / 1000L) + "s 起，等 NAS 转码就绪）");
        }
    }

    private void serveSegment(OutputStream out, File f, String path, String range) throws IOException {
        long total = f.length();
        long start = 0L;
        long end = total - 1L;
        boolean partial = false;
        long[] r = parseRange(range, total);
        if (r != null) {
            start = r[0];
            end = r[1];
            partial = true;
        }
        long count = end - start + 1L;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: video/mp2t\r\n");
        h.append("Content-Length: ").append(count).append("\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n");
        }
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(US_ASCII));
        FileInputStream fis = new FileInputStream(f);
        try {
            long skipped = 0L;
            while (skipped < start) {
                long s = fis.skip(start - skipped);
                if (s <= 0) {
                    if (fis.read() < 0) {
                        break;
                    }
                    s = 1;
                }
                skipped += s;
            }
            byte[] buf = new byte[32 * 1024];
            long remain = count;
            while (remain > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, remain));
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                remain -= n;
            }
        } finally {
            try {
                fis.close();
            } catch (Throwable ignored) {
            }
        }
        out.flush();
        L.d("AuthProxy", "GET " + safePath(path) + " → 本地预缓存 " + count + "B"
                + (partial ? "（Range " + start + "-" + end + "/" + total + "）" : ""));
    }

    /** 解析 "bytes=start-end"，返回闭区间；无法解析返回 null（按整文件回 200）。 */
    private static long[] parseRange(String range, long total) {
        if (range == null || total <= 0) {
            return null;
        }
        String s = range.trim();
        if (!s.startsWith("bytes=")) {
            return null;
        }
        s = s.substring(6);
        int dash = s.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start = dash == 0 ? 0L : Long.parseLong(s.substring(0, dash).trim());
            String endText = s.substring(dash + 1).trim();
            long end = endText.length() == 0 ? total - 1L : Long.parseLong(endText);
            if (start < 0 || start >= total) {
                return null;
            }
            if (end >= total) {
                end = total - 1L;
            }
            if (end < start) {
                return null;
            }
            return new long[]{start, end};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 缓存文件数/体积上限，超出后按最久未使用淘汰。 */
    private void evictSegments() {
        File dir = segDir;
        if (dir == null) {
            return;
        }
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) {
            return;
        }
        long total = 0L;
        for (int i = 0; i < fs.length; i++) {
            total += fs[i].length();
        }
        if (fs.length <= SEG_MAX_FILES && total <= SEG_MAX_BYTES) {
            return;
        }
        Arrays.sort(fs, new Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x > y ? 1 : 0);
            }
        });
        long now = System.currentTimeMillis();
        int served = lastServedIndex;
        for (int i = 0; i < fs.length; i++) {
            if (fs.length - i <= SEG_MAX_FILES && total <= SEG_MAX_BYTES) {
                break;
            }
            if (fs[i].getName().endsWith(".part")) {
                continue;
            }
            // 刚写入的分片可能正被某个请求打开：10 秒内不淘汰，避免 FileNotFound
            if (now - fs[i].lastModified() < 10000L) {
                continue;
            }
            // 播放器当前/即将播放的窗口（已播 ±，预取领先 8 片）绝不能删：
            // 删了会迫使播放器再向 NAS 要一次，正好落在最吃紧的转码期。
            int idx = cacheIndexOf(fs[i].getName());
            if (served >= 0 && idx >= 0 && idx >= served - 1 && idx <= served + 8) {
                continue;
            }
            total -= fs[i].length();
            if (fs[i].delete()) {
                L.d("AuthProxy", "淘汰旧分片 " + fs[i].getName());
            }
        }
    }

    private Upstream cachedPlaylist(String effectivePath) {
        long now = System.currentTimeMillis();
        synchronized (playlistLock) {
            PlaylistHit h = playlistCache.get(effectivePath);
            if (h == null || now - h.atMs > PLAYLIST_TTL_MS) {
                if (h != null) {
                    playlistCache.remove(effectivePath);
                }
                return null;
            }
            Upstream r = new Upstream();
            r.status = h.status;
            r.payload = h.payload;
            r.declaredLength = h.payload.length;
            L.d("AuthProxy", "GET " + safePath(effectivePath) + " → 本地播放列表缓存 " + h.payload.length + "B");
            return r;
        }
    }

    private void storePlaylist(String effectivePath, Upstream r) {
        storePlaylistPayload(effectivePath, r.status, r.payload);
    }

    private void storePlaylistPayload(String effectivePath, int status, byte[] payload) {
        long now = System.currentTimeMillis();
        synchronized (playlistLock) {
            if (playlistCache.size() >= PLAYLIST_CACHE_MAX) {
                String oldest = null;
                long at = Long.MAX_VALUE;
                for (Map.Entry<String, PlaylistHit> e : playlistCache.entrySet()) {
                    if (e.getValue().atMs < at) {
                        at = e.getValue().atMs;
                        oldest = e.getKey();
                    }
                }
                if (oldest != null) {
                    playlistCache.remove(oldest);
                }
            }
            PlaylistHit h = new PlaylistHit();
            h.payload = payload;
            h.status = status;
            h.atMs = now;
            playlistCache.put(effectivePath, h);
        }
    }

    private void captureTsMetadata(File file) {
        if (tsMetadata != null || file == null || !file.isFile()) return;
        TsMetadata parsed = parseTsMetadata(readSmall(file));
        if (parsed == null) return;
        tsMetadata = parsed;
        L.i("AuthProxy", "TS 实际轨道 video=" + (parsed.videoPresent ? parsed.videoCodec : "无")
                + " audio=" + (parsed.audioPresent ? parsed.audioCodec : "无"));
    }

    private static byte[] readSmall(File f) {
        try {
            long len = Math.min(2048L, f.length());
            byte[] b = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            try {
                int got = 0;
                while (got < b.length) {
                    int n = in.read(b, got, b.length - got);
                    if (n <= 0) {
                        break;
                    }
                    got += n;
                }
                if (got < b.length) {
                    byte[] exact = new byte[got];
                    System.arraycopy(b, 0, exact, 0, got);
                    return exact;
                }
            } finally {
                in.close();
            }
            return b;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static boolean copyFile(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return dst.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String pad(int value, int width) {
        String s = String.valueOf(value);
        if (s.length() > width) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    private static String safePath(String path) {
        if (path == null) {
            return "";
        }
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    private static String sanitize(String key) {
        if (key == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length() && sb.length() < 48; i++) {
            char c = key.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (int i = 0; i < fs.length; i++) {
                    deleteRecursive(fs[i]);
                }
            }
        }
        f.delete();
    }

    /** 成功返回可流式转发的结果；状态 >=400 时返回 null，交给缓冲路径。 */
    private MediaStream openMedia(String path, String range, String tokenValue) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, range, tokenValue);
            int status = conn.getResponseCode();
            if (status == 401) {
                conn.disconnect();
                MediaStream denied = new MediaStream();
                denied.status = 401;
                return denied;
            }
            if (status != 200 && status != 206) {
                conn.disconnect();
                return null;
            }
            long len = contentLengthOf(conn);
            // 真机实测：直连原片 Content-Length=11982220379（>2GB），
            // HttpURLConnection.getContentLength() 是 int，直接返回 -1；
            // 而 NAS 对带 Range 的请求会回 206 + 完整长度，用它恢复真实大小。
            if (len <= 0 && (range == null || range.length() == 0)) {
                conn.disconnect();
                conn = open(path, "bytes=0-", tokenValue);
                status = conn.getResponseCode();
                if (status == 401) {
                    conn.disconnect();
                    MediaStream denied = new MediaStream();
                    denied.status = 401;
                    return denied;
                }
                if (status != 200 && status != 206) {
                    conn.disconnect();
                    return null;
                }
                len = contentLengthOf(conn);
            }
            InputStream in = conn.getInputStream();
            int probeLen = len > 0 ? (int) Math.min(512, len) : 512;
            byte[] probe = new byte[probeLen];
            int got = readFully(in, probe);
            markNetworkBytes(got);
            if (got < probe.length) {
                byte[] exact = new byte[got];
                System.arraycopy(probe, 0, exact, 0, got);
                probe = exact;
            }
            if ((len <= 0 || len <= 512) && isAuthJson(probe)) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                conn.disconnect();
                MediaStream denied = new MediaStream();
                denied.status = -2;
                return denied;
            }
            MediaStream m = new MediaStream();
            m.status = status;
            m.length = len;
            m.contentRange = conn.getHeaderField("Content-Range");
            m.head = probe;
            m.body = in;
            m.conn = conn;
            return m;
        } catch (Throwable t) {
            L.w("AuthProxy", "上游媒体流打开失败 " + safePath(path) + "：" + t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    private static boolean isAuthJson(byte[] body) {
        if (body == null || body.length == 0 || body.length > 512) {
            return false;
        }
        String text = new String(body, UTF_8);
        return text.indexOf("\"code\":-2") >= 0 || text.indexOf("\"code\": -2") >= 0;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n <= 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private Upstream fetch(String path, String range, String tokenValue) {
        Upstream r = new Upstream();
        r.status = 502;
        r.payload = new byte[0];
        HttpURLConnection conn = null;
        try {
            conn = open(path, range, tokenValue);
            r.status = conn.getResponseCode();
            r.contentRange = conn.getHeaderField("Content-Range");
            r.declaredLength = conn.getContentLength();
            InputStream src = null;
            // 真机实测 NAS 对 /v/media/* 返回 0（chunked/流式）；先缓冲再写实际字节数，
            // 否则播放器会认为响应没有 body 而立刻断连（MediaPlayer -5003 / ECONNRESET）。
            try {
                src = conn.getInputStream();
            } catch (IOException ex) {
                src = conn.getErrorStream();
            }
            r.payload = readAll(src);
            markNetworkBytes(r.payload.length);
        } catch (Throwable t) {
            L.w("AuthProxy", "上游请求失败 " + safePath(path) + "：" + t);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return r;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        // 直接读进一块固定缓冲，避免 ByteArrayOutputStream 翻倍扩容 +
        // toByteArray() 再复制一份造成的峰值内存（老 TV OOM 的主因）。
        // 绝大多数响应是 HLS 播放列表 / 小分片，只按实际长度裁剪返回。
        byte[] buf = new byte[64 * 1024];
        byte[] acc = new byte[Math.min(MAX_PAYLOAD, 256 * 1024)];
        int total = 0;
        try {
            int n;
            while (total < MAX_PAYLOAD && (n = in.read(buf)) > 0) {
                int take = Math.min(n, MAX_PAYLOAD - total);
                if (total + take > acc.length) {
                    int next = Math.min(MAX_PAYLOAD,
                            Math.max(total + take, acc.length * 2));
                    byte[] grown = new byte[next];
                    System.arraycopy(acc, 0, grown, 0, total);
                    acc = grown;
                }
                System.arraycopy(buf, 0, acc, total, take);
                total += take;
            }
        } finally {
            in.close();
        }
        if (total == acc.length) {
            return acc;
        }
        byte[] exact = new byte[total];
        System.arraycopy(acc, 0, exact, 0, total);
        return exact;
    }

    private static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.indexOf(".m3u8") >= 0) {
            return "application/vnd.apple.mpegurl";
        }
        if (p.indexOf(".ts") >= 0) {
            return "video/mp2t";
        }
        if (p.indexOf(".mp4") >= 0) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (c < 0 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), US_ASCII);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Throwable ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        String link;
        Thread watcher;
        synchronized (sessionLock) {
            link = playLink;
            sessionEpoch.incrementAndGet();
            playLink = "";
            mediaDir = "";
            SessionKeeper k = keeper;
            keeper = null;
            tokens = null;
            watcher = externalIdleWatcher;
            externalIdleWatcher = null;
            if (k != null) {
                k.stopHeartbeat();
                if (link.length() > 0) {
                    k.endSessionAsync(link);
                }
            }
        }
        if (watcher != null) {
            watcher.interrupt();
        }
        cancelPrefetchTasks();
        server = null;
        pool = null;
        acceptor = null;
    }
}
