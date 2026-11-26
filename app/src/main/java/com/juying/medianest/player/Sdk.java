package com.juying.medianest.player;

import android.os.Build;

/**
 * 安全读取系统版本。
 *
 * <p>minSdk 16 起 {@link Build.VERSION#SDK_INT} 一定存在（API 4 加入），
 * 不再解析 {@code Build.VERSION.SDK} 字符串——旧实现会在非数字版本号上
 * 静默退回 API 1，把整机能力判断错。
 */
public final class Sdk {

    private Sdk() {
    }

    public static int level() {
        return Build.VERSION.SDK_INT;
    }

    public static boolean atLeast(int api) {
        return Build.VERSION.SDK_INT >= api;
    }
}
