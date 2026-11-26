package com.juying.medianest.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Toast;

import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.FnException;

/** 电视上可读的错误呈现：大字号、明确原因、可执行的下一步按钮。 */
public final class Ui {

    public interface Action {
        void run();
    }

    private Ui() {
    }

    public static void toast(Context ctx, String text) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }

    public static void info(Context ctx, String title, String message) {
        info(ctx, title, message, null);
    }

    /**
     * 带「知道了」回调的提示框，返回 dialog 引用。
     * 调用方必须在 onDestroy 里 dismiss —— 否则 Activity 被 finish 时
     * 对话框仍挂在 WindowManager 上，会抛 WindowLeaked（真机实测 API 17 复现）。
     */
    public static AlertDialog info(Context ctx, String title, String message, final Action onOk) {
        return new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (onOk != null) {
                            onOk.run();
                        }
                    }
                })
                .show();
    }

    /** 统一异常入口：连接失败 / 格式不支持 / 播放失败都走这里。 */
    public static AlertDialog error(Context ctx, FnException ex, final Action retry) {
        FailureReason reason = ex == null ? FailureReason.UNKNOWN : ex.reason;
        String message = ex == null ? FailureReason.UNKNOWN.friendly("") : ex.userMessage();
        return new AlertDialog.Builder(ctx)
                .setTitle(reason.title())
                .setMessage(message)
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (retry != null) {
                            retry.run();
                        }
                    }
                })
                .setNegativeButton("去设置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        ctx.startActivity(new android.content.Intent(ctx, OfficialScanDeviceActivity.class));
                    }
                })
                .show();
    }

    public static AlertDialog fatal(Context ctx, FailureReason reason, String detail, final Action retry) {
        return new AlertDialog.Builder(ctx)
                .setTitle(reason.title())
                .setMessage(reason.friendly(detail))
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (retry != null) {
                            retry.run();
                        }
                    }
                })
                .setNegativeButton("返回", null)
                .show();
    }

    public static void confirm(Context ctx, String title, String message, final Action yes) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (yes != null) {
                            yes.run();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
