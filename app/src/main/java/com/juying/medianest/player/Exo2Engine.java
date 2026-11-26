package com.juying.medianest.player;

import android.content.Context;
import android.view.SurfaceHolder;

import com.juying.medianest.core.util.L;
import com.juying.medianest.core.model.FailureReason;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * API 16-20 的 ExoPlayer 2.19.1 内核。
 *
 * <p>HLS 播放列表解析、分片加载与重试、缓冲水位、ABR、错误分类全部交给内核；
 * 代理地址与鉴权头通过 DefaultHttpDataSource 注入，仍走 AuthProxy。
 * 字幕轨道被显式禁用：字幕由 HlsVtt + libass 统一渲染，避免两套渲染互相叠加。
 */
public final class Exo2Engine implements PlayerEngine {

    private final Context context;
    private ExoPlayer player;
    private Listener listener;
    private SurfaceHolder holder;
    private boolean preparedNotified;
    private int videoWidth;
    private int videoHeight;
    /** 每次 open/release 递增：旧实例的迟到回调据此丢弃。 */
    private int generation;

    /** 最近一次查询 MediaCodec 能力的时间，以及是否正在查询。
     *  老设备上一次查询可能要 20 秒，上层看门狗用它区分「内核正在枚举解码器」
     *  和「内核初始化失败卡死」。 */
    private static volatile long lastCodecQueryMs = 0L;
    private static volatile boolean codecQueryInProgress = false;

    public Exo2Engine(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 距上次 MediaCodec 能力查询的毫秒数；未查询过返回 Long.MAX_VALUE。 */
    public static long codecQueryAgeMs() {
        long t = lastCodecQueryMs;
        return t == 0L ? Long.MAX_VALUE : (android.os.SystemClock.elapsedRealtime() - t);
    }

    /** 内核是否正卡在 MediaCodec 能力枚举里（老设备单次可达 20 秒以上）。 */
    public static boolean isCodecQueryInProgress() {
        return codecQueryInProgress;
    }

    @Override
    public String name() {
        return "exoplayer2";
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setVideoSurfaceHolder(SurfaceHolder h) {
        this.holder = h;
        if (player != null && h != null) {
            player.setVideoSurfaceHolder(h);
        }
    }

    @Override
    public void open(String url, Map<String, String> headers, long startPositionMs) {
        release();
        preparedNotified = false;
        final int gen = generation;

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("FnTvClient/1.0 (Android; ExoPlayer2)")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) {
            http.setDefaultRequestProperties(headers);
        }

        MediaSource source = new HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(url));

        // 强制硬解：关闭解码器回落（默认即 false，这里显式声明），并只把硬件解码器
        // 交给内核。硬件初始化失败/格式不支持时，ExoPlayer 直接抛 DECODER 错误，
        // 由 FallbackPlayback 走降档/换内核，而不会静默转软解。
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(false)
                .setMediaCodecSelector(new HardwareOnlyCodecSelector());

        ExoPlayer p = new ExoPlayer.Builder(context, renderers).build();
        player = p;
        if (holder != null) {
            p.setVideoSurfaceHolder(holder);
        }
        // 重建内核后保持用户选择的倍速
        if (speed != 1f) {
            p.setPlaybackParameters(new PlaybackParameters(speed));
        }
        p.setAudioAttributes(AudioAttributes.DEFAULT, true);
        // 字幕渲染由 libass 覆盖层负责：禁用内核文本轨，避免重复字幕与额外解码开销
        TrackSelectionParameters tsp = new TrackSelectionParameters.Builder(context)
                .setDisabledTrackTypes(Collections.singleton(C.TRACK_TYPE_TEXT))
                .build();
        p.setTrackSelectionParameters(tsp);
        p.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (gen != generation) {
                    return;
                }
                L.d("Player", "exoplayer2 state=" + state + " buffering="
                        + (state == Player.STATE_BUFFERING));
                Listener l = listener;
                if (state == Player.STATE_READY) {
                    if (!preparedNotified) {
                        preparedNotified = true;
                        if (l != null) {
                            l.onEnginePrepared();
                        }
                    }
                    if (l != null) {
                        l.onEngineBuffering(false);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (l != null) {
                        l.onEngineBuffering(true);
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (l != null) {
                        l.onEngineCompletion();
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (gen != generation) {
                    return;
                }
                L.w("Player", "exoplayer2 error code=" + error.errorCode + " " + error.getMessage());
                Listener l = listener;
                if (l != null) {
                    l.onEngineError(mapError(error), error.errorCode, 0, error.getMessage());
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (gen != generation) {
                    return;
                }
                videoWidth = size.width;
                videoHeight = size.height;
                Listener l = listener;
                if (l != null) {
                    l.onEngineVideoSize(size.width, size.height);
                }
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition, int reason) {
                if (gen != generation) {
                    return;
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    Listener l = listener;
                    if (l != null) {
                        l.onEngineSeekComplete();
                    }
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                L.d("Player", "exoplayer2 tracks changed groups=" + tracks.getGroups().size());
            }
        });
        p.setMediaSource(source, Math.max(0L, startPositionMs));
        p.setPlayWhenReady(true);
        p.prepare();
        L.i("Player", "exoplayer2 prepare url=" + url + " start=" + startPositionMs + "ms");
    }

    private static FailureReason mapError(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return FailureReason.TIMEOUT;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
                return FailureReason.NETWORK_INTERRUPTED;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                return FailureReason.UNSUPPORTED_FORMAT;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                return FailureReason.DECODER_ERROR;
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED:
                return FailureReason.AUDIO_DECODER_ERROR;
            case PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED:
            case PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED:
                return FailureReason.UNSUPPORTED_FORMAT;
            default:
                return FailureReason.PLAYBACK_FAILED;
        }
    }

    @Override
    public void play() {
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    public void pause() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (player != null) {
            player.seekTo(Math.max(0L, positionMs));
        }
    }

    @Override
    public void release() {
        final ExoPlayer p;
        generation++;
        p = player;
        player = null;
        preparedNotified = false;
        if (p == null) {
            return;
        }
        // ExoPlayer.release() 会 join 播放线程；老设备上播放线程可能正卡在
        // MediaCodec 能力查询（单次可达 20 秒以上），放主线程会 ANR。
        EngineTeardown.post(new Runnable() {
            public void run() {
                try {
                    p.stop();
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
        return player == null ? 0L : player.getCurrentPosition();
    }

    @Override
    public long getDurationMs() {
        if (player == null) {
            return -1L;
        }
        long d = player.getDuration();
        return d == C.TIME_UNSET ? -1L : d;
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public int getVideoWidth() {
        return videoWidth;
    }

    @Override
    public int getVideoHeight() {
        return videoHeight;
    }

    @Override
    public float getVideoFrameRate() {
        ExoPlayer p = player;
        if (p == null) {
            return 0f;
        }
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSelected(i)) {
                    continue;
                }
                float fps = g.getTrackFormat(i).frameRate;
                return fps > 0f ? fps : 0f;
            }
        }
        return 0f;
    }

    @Override
    public int getAudioSampleRate() {
        ExoPlayer p = player;
        if (p == null) {
            return 0;
        }
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSelected(i)) {
                    continue;
                }
                return g.getTrackFormat(i).sampleRate;
            }
        }
        return 0;
    }

    @Override
    public boolean needsHlsClockCorrection() {
        return false;
    }

    private float speed = 1f;

    @Override
    public List<String> audioTrackLabels() {
        List<String> out = new ArrayList<String>();
        ExoPlayer p = player;
        if (p == null) {
            return out;
        }
        int flat = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                out.add(describeAudio(g.getTrackFormat(i), ++flat));
            }
        }
        return out;
    }

    /** 官方音频行文案：语言 + 编码 + 声道（如「日语 · aac · stereo」）。 */
    private static String describeAudio(com.google.android.exoplayer2.Format f, int ordinal) {
        StringBuilder sb = new StringBuilder();
        String lang = f.language;
        sb.append(lang == null || lang.length() == 0 || "und".equals(lang)
                ? "音轨 " + ordinal : lang);
        if (f.sampleMimeType != null && f.sampleMimeType.startsWith("audio/")) {
            sb.append(" · ").append(f.sampleMimeType.substring(6));
        }
        if (f.channelCount > 1) {
            sb.append(" · ").append(f.channelCount).append("ch");
        }
        return sb.toString();
    }

    @Override
    public int currentAudioTrack() {
        ExoPlayer p = player;
        if (p == null) {
            return -1;
        }
        int flat = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSelected(i)) {
                    return flat;
                }
                flat++;
            }
        }
        return -1;
    }

    @Override
    public void selectAudioTrack(int index) {
        ExoPlayer p = player;
        if (p == null || index < 0) {
            return;
        }
        int flat = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                if (flat++ != index) {
                    continue;
                }
                TrackSelectionParameters.Builder b = p.getTrackSelectionParameters().buildUpon();
                b.setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
                p.setTrackSelectionParameters(b.build());
                return;
            }
        }
    }

    @Override
    public boolean supportsSpeed() {
        return true;
    }

    @Override
    public boolean supportsAudioTrackSelection() {
        return true;
    }

    @Override
    public boolean supportsSoftwareDecode() {
        // 解码器选择固定为 HardwareOnlyCodecSelector，不提供软解
        return false;
    }

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
        ExoPlayer p = player;
        if (p != null) {
            p.setPlaybackParameters(new PlaybackParameters(s));
        }
    }

    /**
     * 只保留硬件解码器（ExoPlayer 的 MediaCodecInfo.softwareOnly=false，且不是 OMX.google./c2.android.），
     * 排在最前的即为内核实际使用的硬解。若设备确实没有任何硬解（模拟器等），
     * 才退回原始列表以免完全不可用；真机上不会走到这一步，因此不会出现软解实例。
     */
    private static final class HardwareOnlyCodecSelector implements MediaCodecSelector {

        /** 按 mime+安全/隧道模式缓存筛选结果：老设备一次枚举 20 秒，避免重复付出。 */
        private static final Map<String, List<MediaCodecInfo>> CACHE =
                new java.util.HashMap<String, List<MediaCodecInfo>>();

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                    boolean requiresTunnelingDecoder) {
            String key = mimeType + '|' + requiresSecureDecoder + '|' + requiresTunnelingDecoder;
            synchronized (CACHE) {
                List<MediaCodecInfo> cached = CACHE.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            codecQueryInProgress = true;
            try {
                lastCodecQueryMs = android.os.SystemClock.elapsedRealtime();
                List<MediaCodecInfo> all;
                try {
                    all = MediaCodecSelector.DEFAULT
                            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                } catch (com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException e) {
                    throw new IllegalStateException(e);
                }
                List<MediaCodecInfo> hardware = new ArrayList<MediaCodecInfo>(all.size());
                for (MediaCodecInfo info : all) {
                    if (!info.softwareOnly && !info.name.startsWith("OMX.google.")
                            && !info.name.startsWith("c2.android.")) {
                        hardware.add(info);
                    }
                }
                List<MediaCodecInfo> result;
                if (hardware.isEmpty()) {
                    L.w("Player", "exo2 无硬件解码器 mime=" + mimeType + "，退回默认列表");
                    result = all;
                } else {
                    L.i("Player", "exo2 强制硬解 mime=" + mimeType + " 首选=" + hardware.get(0).name);
                    result = hardware;
                }
                synchronized (CACHE) {
                    CACHE.put(key, result);
                }
                return result;
            } finally {
                lastCodecQueryMs = android.os.SystemClock.elapsedRealtime();
                codecQueryInProgress = false;
            }
        }
    }
}
