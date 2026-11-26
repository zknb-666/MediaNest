package com.juying.medianest.core.util;

import android.util.Log;

/** 日志封装。所有网络/播放异常都会留下带环节的日志，便于电视上导出排查。 */
public final class L {

    private static final String TAG = "FnTV";

    public static void d(String where, String msg) {
        Log.d(TAG, "[" + where + "] " + msg);
    }

    public static void i(String where, String msg) {
        Log.i(TAG, "[" + where + "] " + msg);
    }

    public static void w(String where, String msg) {
        Log.w(TAG, "[" + where + "] " + msg);
    }

    public static void e(String where, String msg, Throwable t) {
        Log.e(TAG, "[" + where + "] " + msg, t);
    }

    public static void e(String where, String msg) {
        Log.e(TAG, "[" + where + "] " + msg);
    }

    private L() {
    }
}
