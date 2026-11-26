package com.juying.medianest.core.policy;

/** 字幕 UI 能力策略。 */
public final class SubtitlePolicy {
    private SubtitlePolicy() {
    }

    public static boolean supportsPositionAdjustment(String format) {
        return format != null && "vtt".equalsIgnoreCase(format.trim());
    }
}
