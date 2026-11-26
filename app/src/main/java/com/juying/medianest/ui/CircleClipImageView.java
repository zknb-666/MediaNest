package com.juying.medianest.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * 圆形头像：官方演职人员头像 200×200 正圆 + 1px 白色描边（RoundImageView
 * riv_borderColor=text_title）。API 17 的硬件画布不支持 clipPath，这里强制软件层。
 */
final class CircleClipImageView extends ImageView {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float ringWidth = 1f;

    CircleClipImageView(Context context) {
        this(context, null);
    }

    CircleClipImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
    }

    /** 聚焦时加粗描边（官方 RoundImageView 聚焦高亮）。 */
    void setRingWidth(float width) {
        if (ringWidth != width) {
            ringWidth = width;
            invalidate();
        }
    }

    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - ringWidth / 2f;
        if (r <= 0) {
            super.onDraw(canvas);
            return;
        }
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        super.onDraw(canvas);
        canvas.restore();
        ring.setStrokeWidth(ringWidth);
        canvas.drawCircle(cx, cy, r, ring);
    }
}
