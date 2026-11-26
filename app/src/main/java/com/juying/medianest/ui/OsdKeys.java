package com.juying.medianest.ui;

/**
 * 播放器 OSD 的遥控器按键策略。纯 Java（不依赖 Android SDK），便于独立编译与验证。
 *
 * 规则：
 * <ul>
 *   <li>OSD 收起：上 / 下 / 确认键只唤醒 OSD，不触发任何控件、不暂停播放；</li>
 *   <li>OSD 收起：左 / 右键才分别快退 / 快进；</li>
 *   <li>OSD 展开：上 / 下 / 左 / 右键只移动焦点，左右键绝不触发快退 / 快进；</li>
 *   <li>OSD 展开：确认键只触发当前焦点控件；</li>
 *   <li>焦点只在「仍存在且可见」的控件间循环，已移除或隐藏的控件不参与。</li>
 * </ul>
 */
public final class OsdKeys {

    /** 从最后一次操作起计时，5 秒无操作自动收起 OSD（保持播放状态）。 */
    public static final long TIMEOUT_MS = 5000L;

    // 数值与 android.view.KeyEvent 的 KEYCODE_* 保持一致
    public static final int KEY_DPAD_UP = 19;
    public static final int KEY_DPAD_DOWN = 20;
    public static final int KEY_DPAD_LEFT = 21;
    public static final int KEY_DPAD_RIGHT = 22;
    public static final int KEY_DPAD_CENTER = 23;
    public static final int KEY_SPACE = 62;
    public static final int KEY_ENTER = 66;
    public static final int KEY_MEDIA_PLAY_PAUSE = 85;
    public static final int KEY_MEDIA_REWIND = 89;
    public static final int KEY_MEDIA_FAST_FORWARD = 90;
    public static final int KEY_BUTTON_A = 96;
    public static final int KEY_MEDIA_PLAY = 126;
    public static final int KEY_MEDIA_PAUSE = 127;

    /** 交给 Activity 默认处理（返回、菜单等）。 */
    public static final int ACT_NONE = 0;
    /** 只唤醒 OSD，不改变播放状态、不触发控件。 */
    public static final int ACT_WAKE = 1;
    public static final int ACT_FOCUS_PREV = 2;
    public static final int ACT_FOCUS_NEXT = 3;
    /** 只触发当前有效焦点控件。 */
    public static final int ACT_ACTIVATE = 4;
    public static final int ACT_SEEK_BACK = 5;
    public static final int ACT_SEEK_FORWARD = 6;
    public static final int ACT_PLAY_PAUSE = 7;

    public static boolean isOk(int code) {
        return code == KEY_DPAD_CENTER || code == KEY_ENTER || code == KEY_BUTTON_A
                || code == KEY_SPACE;
    }

    public static boolean isDirection(int code) {
        return code == KEY_DPAD_UP || code == KEY_DPAD_DOWN
                || code == KEY_DPAD_LEFT || code == KEY_DPAD_RIGHT;
    }

    public static boolean isSeekLeft(int code) {
        return code == KEY_DPAD_LEFT || code == KEY_MEDIA_REWIND;
    }

    public static boolean isSeekRight(int code) {
        return code == KEY_DPAD_RIGHT || code == KEY_MEDIA_FAST_FORWARD;
    }

    public static boolean isPlayPause(int code) {
        return code == KEY_MEDIA_PLAY_PAUSE || code == KEY_MEDIA_PLAY
                || code == KEY_MEDIA_PAUSE;
    }

    /**
     * 依据 OSD 当前状态解析按键动作。播放器只负责执行这个动作，不再散落判断。
     */
    public static int action(boolean osdVisible, int code) {
        if (!osdVisible) {
            if (code == KEY_DPAD_UP || code == KEY_DPAD_DOWN || isOk(code)) {
                return ACT_WAKE;
            }
            if (isSeekLeft(code)) {
                return ACT_SEEK_BACK;
            }
            if (isSeekRight(code)) {
                return ACT_SEEK_FORWARD;
            }
            if (isPlayPause(code)) {
                return ACT_PLAY_PAUSE;
            }
            return ACT_NONE;
        }
        if (isOk(code)) {
            return ACT_ACTIVATE;
        }
        if (code == KEY_DPAD_UP || code == KEY_DPAD_LEFT) {
            return ACT_FOCUS_PREV;
        }
        if (code == KEY_DPAD_DOWN || code == KEY_DPAD_RIGHT) {
            return ACT_FOCUS_NEXT;
        }
        if (isPlayPause(code)) {
            return ACT_PLAY_PAUSE;
        }
        return ACT_NONE;
    }

    /** 该动作是否属于「用户操作」，需要重算 5 秒收起计时。 */
    public static boolean isUserOperation(int code) {
        return isDirection(code) || isOk(code) || isPlayPause(code);
    }

    /**
     * 焦点循环：只在 visible=true 的位置之间跳转。
     *
     * @param visible 每个位置对应的控件是否仍存在且可见
     * @param current 当前焦点下标；-1 或指向不可见位置时按方向取第一个 / 最后一个可见项
     * @param dir     +1 下一步，-1 上一步
     * @return 下一个可见下标；没有任何可见控件时返回 -1
     */
    public static int nextVisible(boolean[] visible, int current, int dir) {
        if (visible == null || visible.length == 0) {
            return -1;
        }
        int n = visible.length;
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (visible[i]) {
                count++;
            }
        }
        if (count == 0) {
            return -1;
        }
        int i;
        if (current < 0) {
            i = dir >= 0 ? 0 : n - 1;
        } else if (current >= n || !visible[current]) {
            // 焦点落在已隐藏 / 越界控件上：从该位置沿方向找最近的有效控件
            i = (Math.max(0, Math.min(current, n - 1)) + dir + n) % n;
        } else {
            i = (current + dir + n) % n;
        }
        for (int step = 0; step < n; step++) {
            if (visible[i]) {
                return i;
            }
            i = (i + dir + n) % n;
        }
        return -1;
    }

    private OsdKeys() {
    }
}
