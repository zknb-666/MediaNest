package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选流结果：一个按优先级排好序的候选列表。
 * 播放时从头开始尝试，失败交给 PlaybackStateMachine 决定下一条。
 */
public final class PlaybackPlan {

    public List<StreamCandidate> candidates = new ArrayList<StreamCandidate>();
    /** 没有任何可用候选时，说明「为什么不能播」。 */
    public FailureReason terminalReason = FailureReason.NONE;
    public String terminalDetail = "";
    /** 决策说明，会显示在播放器 OSD / 设置页，方便排查。 */
    public String note = "";
    public boolean directPlayable = false;
    public boolean transcodeUsed = false;

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    public StreamCandidate first() {
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        List<Object> arr = new ArrayList<Object>();
        for (int i = 0; i < candidates.size(); i++) {
            arr.add(candidates.get(i).toJson());
        }
        m.put("candidates", arr);
        m.put("terminalReason", terminalReason.name());
        m.put("terminalDetail", terminalDetail);
        m.put("note", note);
        m.put("directPlayable", Boolean.valueOf(directPlayable));
        m.put("transcodeUsed", Boolean.valueOf(transcodeUsed));
        return m;
    }

    public static PlaybackPlan fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        PlaybackPlan p = new PlaybackPlan();
        List<Object> arr = Json.list(m.get("candidates"));
        for (int i = 0; i < arr.size(); i++) {
            p.candidates.add(StreamCandidate.fromJson(arr.get(i)));
        }
        try {
            p.terminalReason = FailureReason.valueOf(Json.str(m, "terminalReason", "NONE"));
        } catch (IllegalArgumentException ex) {
            p.terminalReason = FailureReason.NONE;
        }
        p.terminalDetail = Json.str(m, "terminalDetail", "");
        p.note = Json.str(m, "note", "");
        p.directPlayable = Json.bool(m, "directPlayable", false);
        p.transcodeUsed = Json.bool(m, "transcodeUsed", false);
        return p;
    }
}
