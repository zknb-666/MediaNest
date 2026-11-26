package com.juying.medianest.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.View;
import android.widget.FrameLayout;

/** minSdk 16 没有 clipToOutline，用 Path 裁剪圆角海报与卡片图。 */
final class RoundedClipLayout extends FrameLayout {
    private final float radius;
    private final Path path = new Path();

    RoundedClipLayout(Context context, float radius) {
        super(context);
        this.radius = radius;
        // API 17 的硬件画布不支持 clipPath，强制这层走软件绘制
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    protected void dispatchDraw(Canvas canvas) {
        // 不用 Path.addRoundRect：R8 在该 ROM 上解析到了缺失的重载
        float r = Math.min(radius, Math.min(getWidth(), getHeight()) / 2f);
        float w = getWidth();
        float h = getHeight();
        path.reset();
        path.moveTo(r, 0);
        path.lineTo(w - r, 0);
        path.quadTo(w, 0, w, r);
        path.lineTo(w, h - r);
        path.quadTo(w, h, w - r, h);
        path.lineTo(r, h);
        path.quadTo(0, h, 0, h - r);
        path.lineTo(0, r);
        path.quadTo(0, 0, r, 0);
        path.close();
        canvas.save();
        canvas.clipPath(path);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
