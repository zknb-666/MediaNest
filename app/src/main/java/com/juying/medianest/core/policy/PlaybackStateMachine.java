package com.juying.medianest.core.policy;

import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.PlaybackPlan;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.StreamSource;


import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 播放回退状态机：吃进一条失败原因，决定「重试 / 换下一条流 / 放弃」。
 *
 * 关键策略：
 *  - 解码/格式类失败 → 不再重试同一条，也不再试同编码的其它原始流，直接跳到 NAS 解码流。
 *  - 网络抖动 → 同一条流重试一次，再往后换。
 *  - 鉴权/签名失败 → 立即终止，换流没意义，必须让用户改配置。
 *  - 每个候选最多 2 次，整轮最多 6 次，避免死循环把 NAS 转码任务打爆。
 *  - 直播线路（候选全是 live:*）例外：每条线路只试一次，逐条换源直到全部失败，
 *    因为线路之间是互为备份的独立源，重试同一条只会白白拖延换源。
 */
public final class PlaybackStateMachine {

    public enum Action { RETRY_SAME, NEXT, ABORT }

    public static final class Decision {
        public Action action = Action.ABORT;
        public StreamCandidate next = null;
        public String notice = "";
        public FailureReason finalReason = FailureReason.NONE;
        public String finalDetail = "";
    }

    private static final int MAX_PER_CANDIDATE = 2;
    private static final int MAX_TOTAL = 6;

    private final PlaybackPlan plan;
    /** 直播计划：候选全部是直播线路，换源策略按「每条只试一次」执行。 */
    private final boolean livePlan;
    private final int perCandidateMax;
    private final int maxTotal;
    private int index = 0;
    private int attemptsOnCurrent = 0;
    private int totalAttempts = 0;
    private boolean finished = false;
    private final Set<String> poisonedCodecs = new LinkedHashSet<String>();

    public PlaybackStateMachine(PlaybackPlan plan) {
        this.plan = (plan == null) ? new PlaybackPlan() : plan;
        boolean live = !this.plan.candidates.isEmpty();
        for (int i = 0; i < this.plan.candidates.size(); i++) {
            String id = this.plan.candidates.get(i).id;
            if (id == null || !id.startsWith("live:")) {
                live = false;
                break;
            }
        }
        this.livePlan = live;
        this.perCandidateMax = live ? 1 : MAX_PER_CANDIDATE;
        this.maxTotal = live ? this.plan.candidates.size() : MAX_TOTAL;
    }

    public StreamCandidate current() {
        if (index < 0 || index >= plan.candidates.size()) {
            return null;
        }
        return plan.candidates.get(index);
    }

    public int currentIndex() {
        return index;
    }

    public int totalCandidates() {
        return plan.candidates.size();
    }

    public boolean isFinished() {
        return finished;
    }

    public void onSuccess() {
        finished = true;
    }

    /**
     * 播放成功后又发生解码停滞（OMX output buffer 超时等）时重新激活状态机。
     * 把 attemptsOnCurrent 顶到上限，避免再重试同一条已经卡死的流，
     * 让下一次 onFailure 直接跳到 NAS 解码流 / 外部播放器。
     */
    public void resumeAfterStall() {
        finished = false;
        attemptsOnCurrent = perCandidateMax;
    }

    /** 供调用方在真正开始播放一条候选之前登记一次尝试。 */
    public void beginAttempt() {
        totalAttempts++;
        attemptsOnCurrent++;
    }

    public Decision onFailure(FailureReason reason, String detail) {
        Decision d = new Decision();
        if (reason == null) {
            reason = FailureReason.UNKNOWN;
        }
        String text = (detail == null) ? "" : detail;

        if (finished) {
            d.action = Action.ABORT;
            d.finalReason = reason;
            d.finalDetail = text;
            return d;
        }

        // 1. 配置类错误：重试/换流都无意义
        if (reason == FailureReason.AUTH_FAILED || reason == FailureReason.SIGN_FAILED
                || reason == FailureReason.DNS_FAILED) {
            finished = true;
            d.action = Action.ABORT;
            d.finalReason = reason;
            d.finalDetail = text;
            d.notice = reason.title();
            return d;
        }

        // 2. 解码/格式类错误：把当前编码拉黑，直接跳到 NAS 解码流。
        //    直播没有转码流，一条线路解码失败只说明这个源不可用，继续换下一条。
        if (!livePlan && (reason.isCodecFailure() || reason == FailureReason.DECODER_ERROR)) {
            StreamCandidate cur = current();
            if (cur != null && cur.videoCodec.length() > 0) {
                poisonedCodecs.add(cur.videoCodec);
            }
            int target = findNextEligible(index + 1, true);
            if (target >= 0) {
                index = target;
                attemptsOnCurrent = 0;
                d.action = Action.NEXT;
                d.next = current();
                d.notice = "原始流无法解码，正在切换为 " + d.next.source.label() + "…";
                return d;
            }
            return abort(d, FailureReason.UNSUPPORTED_FORMAT, text);
        }

        // 3. 网络/播放类错误：先重试一次同一条流（直播每条线路只试一次）
        if (attemptsOnCurrent < perCandidateMax && totalAttempts < maxTotal) {
            d.action = Action.RETRY_SAME;
            d.next = current();
            d.notice = reason == FailureReason.NETWORK_INTERRUPTED
                    ? "网络中断，正在重连…"
                    : "播放异常，正在重试当前流…";
            return d;
        }

        // 4. 重试无效 → 换下一条
        int target = findNextEligible(index + 1, false);
        if (target >= 0 && totalAttempts < maxTotal) {
            index = target;
            attemptsOnCurrent = 0;
            d.action = Action.NEXT;
            d.next = current();
            d.notice = (livePlan ? "线路异常，已切换至 " : "已切换至 ")
                    + d.next.source.label() + "：" + d.next.label;
            return d;
        }

        return abort(d, reason, text);
    }

    private int findNextEligible(int from, boolean preferNonOriginal) {
        // 优先找 NAS 解码流 / 软解 / 外部播放器
        if (preferNonOriginal) {
            for (int i = from; i < plan.candidates.size(); i++) {
                StreamCandidate s = plan.candidates.get(i);
                if (s.source != StreamSource.ORIGINAL) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = from; i < plan.candidates.size(); i++) {
            StreamCandidate s = plan.candidates.get(i);
            if (s.source == StreamSource.ORIGINAL && poisonedCodecs.contains(s.videoCodec)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private Decision abort(Decision d, FailureReason reason, String detail) {
        finished = true;
        d.action = Action.ABORT;
        if (plan.candidates.isEmpty() && plan.terminalReason != FailureReason.NONE) {
            d.finalReason = plan.terminalReason;
            d.finalDetail = plan.terminalDetail;
        } else {
            d.finalReason = (reason == FailureReason.NONE) ? FailureReason.PLAYBACK_FAILED : reason;
            d.finalDetail = detail.length() > 0 ? detail : "已尝试全部 " + plan.candidates.size() + " 条播放方案";
        }
        d.notice = d.finalReason.title();
        return d;
    }

    public String describe() {
        return "第 " + (index + 1) + "/" + plan.candidates.size() + " 条候选，累计尝试 " + totalAttempts + " 次";
    }
}
