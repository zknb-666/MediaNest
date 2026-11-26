package com.juying.medianest.player;

import android.view.SurfaceHolder;

import com.juying.medianest.core.model.FailureReason;

import java.util.Map;

/**
 * 播放内核抽象层。
 *
 * <p>两套实现由 {@link EngineFactory} 按 SDK_INT 选择：
 * <ul>
 *   <li>API 16+：ExoPlayer 2.19.1（{@code Exo2Engine}）</li>
 *   <li>保底/无 media.codec：系统 MediaPlayer（{@code MediaPlayerEngine}）</li>
 * </ul>
 *
 * <p>只暴露 PlayerActivity 真正需要的动作，HLS 播放列表解析、分片重试、缓冲策略、
 * ABR、解码器选择全部由内核完成。字幕链路不在接口内：仍然由 HlsVtt 按需拉取分片、
 * libass 渲染，避免与刚修复的字幕对齐逻辑分叉。
 */
public interface PlayerEngine {

    interface Listener {
        /** 已进入可播放状态（内核完成 prepare，并且已开始播放）。 */
        void onEnginePrepared();

        void onEngineSeekComplete();

        /** @param reason 已按语义归类，上层直接交给回退状态机 */
        void onEngineError(FailureReason reason, int what, int extra, String detail);

        void onEngineCompletion();

        void onEngineVideoSize(int width, int height);

        void onEngineBuffering(boolean buffering);
    }

    /** 日志用内核名：mediaplayer / exoplayer2。 */
    String name();

    void setListener(Listener listener);

    void setVideoSurfaceHolder(SurfaceHolder holder);

    /**
     * 建链并起播。
     *
     * @param headers         鉴权头（AuthProxy 之前的 NAS token 头）
     * @param startPositionMs &gt;0 时在起播后定位到该位置
     */
    void open(String url, Map<String, String> headers, long startPositionMs);

    void play();

    void pause();

    void seekTo(long positionMs);

    void release();

    long getPositionMs();

    /** 直播/未知时长返回 -1。 */
    long getDurationMs();

    boolean isPlaying();

    /** 视频原始宽高（用于 ASS 的 PlayRes → 屏幕坐标映射）；未知返回 0。 */
    int getVideoWidth();

    int getVideoHeight();

    /** 视频帧率（Hz）；内核未上报返回 0。播放信息浮层使用。 */
    float getVideoFrameRate();

    /** 音频采样率（Hz）；未知返回 0。播放信息浮层使用。 */
    int getAudioSampleRate();

    /**
     * 是否使用「HLS 播放列表时间轴」作为位置基准（仅系统 MediaPlayer）。
     * 为 true 时字幕时间需要 HlsClock 的分片 PTS 偏差校正；
     * ExoPlayer 的位置本身就是媒体时间轴，不能再叠加校正。
     */
    boolean needsHlsClockCorrection();

    /** 可切换音轨标签；空列表表示内核还没暴露音轨信息（未 prepare 或单音轨）。 */
    java.util.List<String> audioTrackLabels();

    /** 当前音轨在本列表中的序号；未知返回 -1。 */
    int currentAudioTrack();

    /** 选择音轨；越界或不支持时忽略。 */
    void selectAudioTrack(int index);

    /** 内核是否支持变速播放。 */
    boolean supportsSpeed();

    /** 内核是否支持音轨枚举与切换（系统 MediaPlayer 需 API 16 的 getTrackInfo/selectTrack）。 */
    boolean supportsAudioTrackSelection();

    /** 内核是否支持软件解码；不支持的引擎不应在设置页暴露「软解」。 */
    boolean supportsSoftwareDecode();

    /** 当前倍速，默认 1.0f。 */
    float getSpeed();

    /** 设置倍速；不支持时忽略。 */
    void setSpeed(float speed);
}
