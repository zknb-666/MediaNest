package com.juying.medianest.ui;

import android.view.KeyEvent;

/** 遥控器按键归一化：方向键、OK、媒体键、返回、菜单。 */
public final class TvKeys {

    public static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_SPACE;
    }

    public static boolean isBack(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
    }

    public static boolean isMenu(int code) {
        return code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BUTTON_Y
                || code == KeyEvent.KEYCODE_INFO;
    }

    /** 左右键的快进/快退秒数；其它键返回 0。 */
    public static int seekDelta(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            return 10;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND) {
            return -10;
        }
        return 0;
    }

    public static boolean isPlayPause(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    private TvKeys() {
    }
}
