package com.juying.medianest.domain.playback;

import com.juying.medianest.core.util.L;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.PlaybackPlan;
import com.juying.medianest.core.policy.PlaybackStateMachine;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.StreamSource;

/**
 * 把「选流计划 + 回退状态机」接到真实播放器上。
 * 它本身不碰 Android UI，所有反馈通过 Host 回调，方便单测与替换播放器。
 */
public final class FallbackPlayback {

    public interface Host {
        void play(StreamCandidate candidate);

        void launchExternal(StreamCandidate candidate);

        void notice(String text);

        void fatal(FailureReason reason, String detail);
    }

    private final PlaybackPlan plan;
    private final PlaybackStateMachine state;
    private final Host host;
    private boolean started = false;

    public FallbackPlayback(PlaybackPlan plan, Host host) {
        this.plan = plan == null ? new PlaybackPlan() : plan;
        this.host = host;
        this.state = new PlaybackStateMachine(this.plan);
    }

    public PlaybackPlan plan() {
        return plan;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (plan.isEmpty()) {
            FailureReason r = plan.terminalReason == FailureReason.NONE
                    ? FailureReason.UNSUPPORTED_FORMAT : plan.terminalReason;
            host.fatal(r, plan.terminalDetail.length() > 0 ? plan.terminalDetail : plan.note);
            return;
        }
        if (plan.note.length() > 0) {
            host.notice(plan.note);
        }
        state.beginAttempt();
        dispatch(state.current());
    }

    /** 播放成功（onPrepared）后调用，避免后续误判继续换流。 */
    public void onPlaybackStarted() {
        state.onSuccess();
    }

    /** 播放成功之后又检测到解码停滞：重新激活状态机，直接升级到其它候选。 */
    public void onDecoderStall(String detail) {
        state.resumeAfterStall();
        onFailure(FailureReason.DECODER_TIMEOUT, detail);
    }

    public void onFailure(FailureReason reason, String detail) {
        L.w("FallbackPlayback", "失败 " + reason + " / " + detail + " / " + state.describe());
        PlaybackStateMachine.Decision d = state.onFailure(reason, detail);
        if (d.notice.length() > 0) {
            host.notice(d.notice);
        }
        if (d.action == PlaybackStateMachine.Action.ABORT) {
            host.fatal(d.finalReason, d.finalDetail);
            return;
        }
        if (d.action == PlaybackStateMachine.Action.RETRY_SAME) {
            state.beginAttempt();
        } else {
            state.beginAttempt();
        }
        dispatch(d.next);
    }

    private void dispatch(StreamCandidate c) {
        if (c == null) {
            host.fatal(FailureReason.PLAYBACK_FAILED, "没有可尝试的播放地址");
            return;
        }
        if (c.source == StreamSource.EXTERNAL_PLAYER) {
            host.launchExternal(c);
        } else {
            host.play(c);
        }
    }

    /** 把 MediaPlayer 的 what/extra 翻译成语义化失败原因。 */
    public static FailureReason fromPlayerError(int what, int extra) {
        // MEDIA_ERROR_TIMED_OUT：老设备 OMX 解码器长时间无响应，属于解码类失败
        if (extra == -110 || what == -110) {
            return FailureReason.DECODER_TIMEOUT;
        }
        // 部分电视 ROM 的自定义错误码：拿到无法解析的编码时立刻退出
        if (extra == -5003) {
            return FailureReason.DECODER_ERROR;
        }
        if (extra == -1010 || extra == -1004 || what == -1010) { // MEDIA_ERROR_UNSUPPORTED / IO
            return FailureReason.DECODER_ERROR;
        }
        if (what == 1 || what == 100 || extra == -1004 || extra == -110) {
            return FailureReason.NETWORK_INTERRUPTED;
        }
        if (what == 200) {
            return FailureReason.UNSUPPORTED_FORMAT;
        }
        return FailureReason.PLAYBACK_FAILED;
    }

    /** 用于 OSD 展示当前策略，便于用户理解「为什么画质变了」。 */
    public static String describeChoice(PlaybackPlan plan, MediaItem item) {
        if (plan == null || plan.first() == null) {
            return "无可用播放方案";
        }
        return item.displayTitle() + " · " + plan.first().source.label() + " · " + plan.note;
    }
}
