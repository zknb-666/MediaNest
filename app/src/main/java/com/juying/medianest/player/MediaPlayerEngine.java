package com.juying.medianest.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.view.SurfaceHolder;

import com.juying.medianest.domain.playback.FallbackPlayback;
import com.juying.medianest.core.util.L;
import com.juying.medianest.core.model.FailureReason;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API &lt; 16 的系统 MediaPlayer 内核。
 *
 * <p>保留老设备所需的全部行为：带鉴权头的 setDataSource 反射调用、老固件退回无头
 * URL、建链放工作线程、HLS 播放列表时间轴（因此字幕需要 HlsClock 的 PTS 偏差校正）。
 */
public final class MediaPlayerEngine implements PlayerEngine {

    private final Context context;
    private final Object lock = new Object();

    private MediaPlayer mp;
    private Listener listener;
    private SurfaceHolder holder;
    private long startPositionMs;
    /** 每次 open/release 递增：旧实例的迟到回调据此丢弃。 */
    private int generation;
    /**
     * 处于 setDataSource → onPrepared 之间。MStar 的 mstplayer 在这个阶段收到
     * setVideoSurfaceTexture 会返回 -22 并内部 reset，紧接着 prepare 变成
     * [AVP] no input（status=0x80000000）——真机 logcat 实测。所以准备期间只记下
     * SurfaceHolder，等 onPrepared 里再挂。
     */
    private volatile boolean preparing;

    public MediaPlayerEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "mediaplayer";
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setVideoSurfaceHolder(SurfaceHolder h) {
        this.holder = h;
        synchronized (lock) {
            if (mp != null && h != null && !preparing) {
                attachSurfaceLocked(mp, h);
            }
        }
    }

    /** 把视频输出挂到 SurfaceView 上；失败只记日志，不抛。 */
    private void attachSurfaceLocked(MediaPlayer p, SurfaceHolder h) {
        try {
            p.setDisplay(h);
            p.setScreenOnWhilePlaying(true);
        } catch (Throwable t) {
            L.w("Player", "MediaPlayer.setDisplay 失败：" + t);
        }
    }

    @Override
    public void open(final String url, final Map<String, String> headers, long startPositionMs) {
        release();
        this.startPositionMs = Math.max(0L, startPositionMs);
        final int gen;
        synchronized (lock) {
            gen = ++generation;
        }
        final Map<String, String> headers0 = headers == null
                ? new HashMap<String, String>() : new HashMap<String, String>(headers);
        // 真机实测：setDataSource 会对 HLS 做同步打开/探测（约 0.7 秒），
        // 放在主线程上直接跳 42 帧。建链整体挪到工作线程，UI 只等回调。
        new Thread(new Runnable() {
            public void run() {
                // 老固件的 mstplayer 把 STM/DM 放在进程级线程里：上一个实例的
                // reset 还没走完 uninit，新实例 prepareAsync 就会直接返回
                // [AVP] no input（status=0x80000000）。因此连 new MediaPlayer()
                // 都必须等旧实例销毁完再做——否则原生对象被复用，reset 会打到
                // 刚建好的实例上。
                if (!EngineTeardown.awaitIdle(10000L)) {
                    L.w("Player", "上一个播放器销毁超过 10 秒，继续建链");
                }
                if (gen != generation) {
                    return;
                }
                final MediaPlayer p;
                try {
                    p = new MediaPlayer();
                } catch (Throwable t) {
                    L.e("Player", "MediaPlayer 创建失败", t);
                    fail(gen, null, t.toString());
                    return;
                }
                synchronized (lock) {
                    if (gen != generation) {
                        p.release();
                        return;
                    }
                    mp = p;
                }
                try {
                    p.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    synchronized (lock) {
                        if (gen != generation || mp != p) {
                            return;
                        }
                        if (holder != null) {
                            p.setDisplay(holder);
                            // 必须在 setDisplay 之后：没有 SurfaceHolder 时
                            // setScreenOnWhilePlaying 在真机上会刷 "ineffective without a SurfaceHolder"
                            p.setScreenOnWhilePlaying(true);
                        }
                    }
                    p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        public void onPrepared(MediaPlayer player) {
                            if (!isCurrent(gen, player)) {
                                return;
                            }
                            preparing = false;
                            // 真机：MStar 的视频层必须在 start 之前挂好，否则解码正常但
                            // 画面永远是黑的（VO 只 ck_keep 不 flip）。准备期间被跳过的
                            // 挂载在这里补上。
                            if (holder != null) {
                                attachSurfaceLocked(player, holder);
                            }
                            p.start();
                            long seek = MediaPlayerEngine.this.startPositionMs;
                            MediaPlayerEngine.this.startPositionMs = 0L;
                            if (seek > 0L) {
                                try {
                                    p.seekTo((int) Math.min(seek, Integer.MAX_VALUE));
                                } catch (Throwable ignored) {
                                }
                            }
                            Listener l = listener;
                            if (l != null) {
                                l.onEnginePrepared();
                            }
                        }
                    });
                    p.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                        public void onSeekComplete(MediaPlayer player) {
                            if (!isCurrent(gen, player)) {
                                return;
                            }
                            Listener l = listener;
                            if (l != null) {
                                l.onEngineSeekComplete();
                            }
                        }
                    });
                    p.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                        public boolean onInfo(MediaPlayer player, int what, int extra) {
                            if (!isCurrent(gen, player)) {
                                return false;
                            }
                            Listener l = listener;
                            if (l != null
                                    && what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                l.onEngineBuffering(true);
                            } else if (l != null
                                    && what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                                l.onEngineBuffering(false);
                            }
                            return true;
                        }
                    });
                    p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        public boolean onError(MediaPlayer player, int what, int extra) {
                            if (!isCurrent(gen, player)) {
                                return true; // 已释放播放器的迟到错误，吞掉
                            }
                            preparing = false;
                            L.w("Player", "MediaPlayer error what=" + what + " extra=" + extra);
                            Listener l = listener;
                            if (l != null) {
                                l.onEngineError(FallbackPlayback.fromPlayerError(what, extra),
                                        what, extra, "what=" + what + " extra=" + extra);
                            }
                            return true; // 已消费，避免系统弹默认错误框
                        }
                    });
                    p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        public void onCompletion(MediaPlayer player) {
                            if (!isCurrent(gen, player)) {
                                return;
                            }
                            Listener l = listener;
                            if (l != null) {
                                l.onEngineCompletion();
                            }
                        }
                    });
                    p.setOnVideoSizeChangedListener(new VideoSizeForwarder(gen, p));
                } catch (Throwable t) {
                    L.e("Player", "MediaPlayer 初始化失败", t);
                    fail(gen, p, t.toString());
                    return;
                }
                if (!isCurrent(gen, p)) {
                    return;
                }
                final String playUrl = platformUrl(url);
                L.i("Player", "mediaplayer 打开 url=" + playUrl + " start=" + startPositionMs + "ms");
                preparing = true;
                try {
                    setDataSource(p, playUrl, headers0);
                } catch (Throwable t) {
                    preparing = false;
                    L.e("Player", "打开流失败", t);
                    fail(gen, p, t.toString());
                    return;
                }
                if (!isCurrent(gen, p)) {
                    preparing = false;
                    return;
                }
                try {
                    p.prepareAsync();
                    L.d("Player", "prepareAsync 已发出（工作线程）");
                } catch (Throwable t) {
                    preparing = false;
                    L.e("Player", "prepareAsync 调用失败", t);
                    fail(gen, p, t.toString());
                }
            }
        }, "fntv-prepare").start();
    }

    /**
     * MStar 4.2.1 的 HLS 解复用器（DataSource_M3U8）只认媒体播放列表：
     * 拿到带 #EXT-X-MEDIA / #EXT-X-STREAM-INF 的 master 列表时，解析完
     * 直接报 {@code [AVP] no input}（status=0x80000000），连变体列表都不请求。
     *
     * 早期 NAS 把 preset.m3u8 做成 master、main.m3u8 做成媒体列表，这里曾把
     * preset 改写成 main；2026-11-23 实测 NAS 已改为 preset.m3u8 直接返回
     * 媒体列表（VOD、2 秒分片），同目录 main.m3u8 一律 404。因此播放器直接
     * 使用 play_link；若上游仍是 master，由 AuthProxy 在转发层跟随首个变体。
     */
    private static String platformUrl(String url) {
        return url;
    }

    private boolean isCurrent(int gen, MediaPlayer player) {
        synchronized (lock) {
            return gen == generation && player == mp;
        }
    }

    private void fail(final int gen, final MediaPlayer p, final String detail) {
        if (!isCurrent(gen, p)) {
            return;
        }
        Listener l = listener;
        if (l != null) {
            l.onEngineError(FailureReason.PLAYBACK_FAILED, 0, 0, detail);
        }
    }

    /** API 14+ 起可直接使用带请求头的 setDataSource（minSdk 16）。 */
    private void setDataSource(MediaPlayer player, String url, Map<String, String> headers)
            throws Exception {
        if (headers == null || headers.size() == 0) {
            player.setDataSource(url);
            return;
        }
        try {
            player.setDataSource(context, Uri.parse(url), headers);
            L.d("Player", "使用带鉴权头的 setDataSource");
        } catch (Throwable t) {
            // 个别固件的重载实现有缺陷，退回无头地址（token 已在 query 里）
            player.setDataSource(url);
            L.d("Player", "带头部 setDataSource 失败，退回 URL 内联凭据: " + t);
        }
    }

    @Override
    public void play() {
        synchronized (lock) {
            if (mp != null) {
                mp.start();
            }
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            if (mp != null) {
                mp.pause();
            }
        }
    }

    @Override
    public void seekTo(long positionMs) {
        synchronized (lock) {
            if (mp != null) {
                mp.seekTo((int) Math.min(Math.max(0L, positionMs), Integer.MAX_VALUE));
            }
        }
    }

    @Override
    public void release() {
        final MediaPlayer p;
        synchronized (lock) {
            generation++;
            preparing = false;
            p = mp;
            mp = null;
        }
        if (p == null) {
            return;
        }
        try {
            p.setOnErrorListener(null);
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(null);
            p.setOnSeekCompleteListener(null);
            p.setOnInfoListener(null);
            p.setOnVideoSizeChangedListener(null);
        } catch (Throwable ignored) {
        }
        // reset()/release() 在老固件上可能卡在 mediaserver 上几十秒，绝不能占用主线程。
        EngineTeardown.post(new Runnable() {
            public void run() {
                try {
                    // 真机实测：直播换线时上一台实例刚播放过，直接 reset 会让新实例
                    // setVideoSurfaceTexture 报 -22 并被 mstplayer 内部 reset（0.5 秒
                    // prepareAsync 失败）。先解绑显示再 reset，把 Surface 交还出来。
                    p.setDisplay(null);
                } catch (Throwable ignored) {
                }
                try {
                    p.reset();
                } catch (Throwable ignored) {
                }
                try {
                    p.release();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public long getPositionMs() {
        synchronized (lock) {
            if (mp == null) {
                return 0L;
            }
            try {
                return mp.getCurrentPosition();
            } catch (Throwable t) {
                return 0L;
            }
        }
    }

    @Override
    public long getDurationMs() {
        synchronized (lock) {
            if (mp == null) {
                return -1L;
            }
            try {
                return mp.getDuration();
            } catch (Throwable t) {
                return -1L;
            }
        }
    }

    @Override
    public boolean isPlaying() {
        synchronized (lock) {
            return mp != null && mp.isPlaying();
        }
    }

    @Override
    public int getVideoWidth() {
        synchronized (lock) {
            try {
                return mp == null ? 0 : mp.getVideoWidth();
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    @Override
    public int getVideoHeight() {
        synchronized (lock) {
            try {
                return mp == null ? 0 : mp.getVideoHeight();
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    /** 系统 MediaPlayer 不暴露帧率。 */
    @Override
    public float getVideoFrameRate() {
        return 0f;
    }

    /** API 19+ 可通过 TrackInfo 格式取采样率；更老系统返回 0（播放信息里不显示）。 */
    @Override
    public int getAudioSampleRate() {
        if (android.os.Build.VERSION.SDK_INT < 19) {
            return 0;
        }
        synchronized (lock) {
            try {
                if (mp == null) {
                    return 0;
                }
                android.media.MediaPlayer.TrackInfo[] tracks = mp.getTrackInfo();
                if (tracks == null) {
                    return 0;
                }
                for (int i = 0; i < tracks.length; i++) {
                    if (tracks[i] == null
                            || tracks[i].getTrackType() != android.media.MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                        continue;
                    }
                    android.media.MediaFormat f = tracks[i].getFormat();
                    if (f != null && f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                        return f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    }
                }
            } catch (Throwable ignored) {
            }
            return 0;
        }
    }

    @Override
    public boolean needsHlsClockCorrection() {
        return true;
    }

    /** 已选中的绝对音轨下标（getTrackInfo 的数组下标），-1 表示从未选过。 */
    private int selectedAudioAbs = -1;
    private int selectedAudioFlat;

    @Override
    public List<String> audioTrackLabels() {
        List<String> out = new ArrayList<String>();
        MediaPlayer p;
        synchronized (lock) {
            p = mp;
        }
        if (p == null) {
            return out;
        }
        try {
            MediaPlayer.TrackInfo[] infos = p.getTrackInfo();
            if (infos == null) {
                return out;
            }
            int n = 0;
            for (int i = 0; i < infos.length; i++) {
                MediaPlayer.TrackInfo t = infos[i];
                if (t == null || t.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    continue;
                }
                n++;
                String lang = "";
                if (Build.VERSION.SDK_INT >= 19) {
                    try {
                        lang = t.getLanguage();
                    } catch (Throwable ignored) {
                    }
                }
                out.add(lang == null || lang.length() == 0 ? "音轨 " + n : lang);
            }
        } catch (Throwable t) {
            L.w("Player", "getTrackInfo 失败：" + t);
        }
        return out;
    }

    @Override
    public int currentAudioTrack() {
        List<String> labels = audioTrackLabels();
        return labels.isEmpty() ? -1 : selectedAudioFlat;
    }

    @Override
    public void selectAudioTrack(int index) {
        MediaPlayer p;
        synchronized (lock) {
            p = mp;
        }
        if (p == null || index < 0) {
            return;
        }
        try {
            MediaPlayer.TrackInfo[] infos = p.getTrackInfo();
            if (infos == null) {
                return;
            }
            int flat = 0;
            int abs = -1;
            for (int i = 0; i < infos.length; i++) {
                MediaPlayer.TrackInfo t = infos[i];
                if (t == null || t.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    continue;
                }
                if (flat++ == index) {
                    abs = i;
                    break;
                }
            }
            if (abs < 0) {
                return;
            }
            if (selectedAudioAbs >= 0 && selectedAudioAbs != abs) {
                p.deselectTrack(selectedAudioAbs);
            }
            p.selectTrack(abs);
            selectedAudioAbs = abs;
            selectedAudioFlat = index;
        } catch (Throwable t) {
            L.w("Player", "切换音轨失败：" + t);
        }
    }

    @Override
    public boolean supportsSpeed() {
        // MediaPlayer 变速要 API 23 的 setPlaybackParams，老系统不支持
        return Build.VERSION.SDK_INT >= 23;
    }

    @Override
    public boolean supportsAudioTrackSelection() {
        // getTrackInfo / selectTrack 需要 API 16，minSdk 已是 16
        return true;
    }

    @Override
    public boolean supportsSoftwareDecode() {
        // 平台 MediaPlayer 不暴露解码器选择，只能用厂商硬解
        return false;
    }

    private float speed = 1f;

    @Override
    public float getSpeed() {
        return speed;
    }

    @Override
    public void setSpeed(float s) {
        if (s <= 0f) {
            return;
        }
        speed = s;
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        MediaPlayer p;
        synchronized (lock) {
            p = mp;
        }
        if (p == null) {
            return;
        }
        try {
            // PlaybackParams 类在 API 23 才存在，老系统上直接引用会触发类校验失败，
            // 因此整条调用链走反射，且仅在 SDK_INT>=23 分支执行。
            Class<?> cls = Class.forName("android.media.PlaybackParams");
            Object params = cls.getConstructor().newInstance();
            cls.getMethod("setSpeed", float.class).invoke(params, s);
            Method m = MediaPlayer.class.getMethod("setPlaybackParams", cls);
            m.invoke(p, params);
        } catch (Throwable t) {
            L.w("Player", "setPlaybackParams 失败：" + t);
        }
    }

    /** MediaPlayer 没有视频尺寸回调接口，用 onVideoSizeChangedListener 转发。 */
    private final class VideoSizeForwarder implements MediaPlayer.OnVideoSizeChangedListener {
        private final int gen;
        private final MediaPlayer owner;

        VideoSizeForwarder(int gen, MediaPlayer owner) {
            this.gen = gen;
            this.owner = owner;
        }

        public void onVideoSizeChanged(MediaPlayer player, int width, int height) {
            if (!isCurrent(gen, owner)) {
                return;
            }
            Listener l = listener;
            if (l != null) {
                l.onEngineVideoSize(width, height);
            }
        }
    }
}
