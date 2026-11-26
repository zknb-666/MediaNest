package com.juying.medianest.core.policy;

/** 根据网络吞吐与播放稳定性，在设备和源片上限内逐步调整转码码率。 */
public final class BitrateController {

    private static final long STABLE_MS = 15000L;
    private static final long COOLDOWN_MS = 20000L;
    private static final int MIN_BPS = 1000000;
    private static final int MAX_UPGRADES = 8;

    private final int deviceCeilingBps;
    private final int sourceCeilingBps;
    private int currentBps;
    private long stableSinceMs = -1L;
    private long lastChangeMs = -COOLDOWN_MS;
    private int upgrades;

    public BitrateController(int initialBps, int deviceCeilingBps, int sourceCeilingBps) {
        this.deviceCeilingBps = positiveOrUnlimited(deviceCeilingBps);
        this.sourceCeilingBps = sourceCeilingBps > 0 ? sourceCeilingBps : positiveOrUnlimited(initialBps);
        int ceiling = Math.min(this.deviceCeilingBps, this.sourceCeilingBps);
        this.currentBps = Math.max(MIN_BPS, Math.min(positiveOrUnlimited(initialBps), ceiling));
    }

    public int currentBps() {
        return currentBps;
    }

    public int capCurrent(int ceilingBps) {
        if (ceilingBps > 0 && currentBps > ceilingBps) {
            currentBps = ceilingBps;
        }
        return currentBps;
    }

    public int sample(int networkKbps, boolean stable, long nowMs) {
        if (networkKbps > 0) {
            long networkCeiling = (long) networkKbps * 1000L * 80L / 100L;
            if (networkCeiling > 0 && networkCeiling < currentBps * 80L / 100L) {
                currentBps = (int) Math.max(MIN_BPS, Math.min(Integer.MAX_VALUE, networkCeiling));
                lastChangeMs = nowMs;
                stableSinceMs = -1L;
                return currentBps;
            }
        }
        if (!stable) {
            stableSinceMs = -1L;
            return currentBps;
        }
        if (stableSinceMs < 0L) {
            stableSinceMs = nowMs;
        }
        if (nowMs - stableSinceMs < STABLE_MS || nowMs - lastChangeMs < COOLDOWN_MS
                || upgrades >= MAX_UPGRADES) {
            return currentBps;
        }
        int ceiling = Math.min(deviceCeilingBps, sourceCeilingBps);
        if (networkKbps > 0) {
            long networkCeiling = (long) networkKbps * 1000L * 80L / 100L;
            ceiling = (int) Math.min(ceiling, Math.min(Integer.MAX_VALUE, networkCeiling));
        }
        if (ceiling <= currentBps) {
            return currentBps;
        }
        currentBps = Math.min(ceiling, roundStep(currentBps * 125 / 100));
        lastChangeMs = nowMs;
        stableSinceMs = nowMs;
        upgrades++;
        return currentBps;
    }

    public int onStall(long nowMs) {
        currentBps = Math.max(MIN_BPS, currentBps * 75 / 100);
        lastChangeMs = nowMs;
        stableSinceMs = -1L;
        return currentBps;
    }

    private static int roundStep(int bps) {
        return ((bps + 249999) / 250000) * 250000;
    }

    private static int positiveOrUnlimited(int value) {
        return value > 0 ? value : Integer.MAX_VALUE;
    }
}
