package com.juying.medianest.ui;

import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;

/**
 * 遥控器焦点动效：对齐网页端卡片 hover 时的轻微放大反馈。
 * 只用 API 1 就有的 ScaleAnimation（不依赖 ViewPropertyAnimator/stateListAnimator），
 * 老 TV（API 17 实测）可用；缩放以自身中心为轴，150ms 加减速。
 */
public final class FocusEffects {

    private static final float SCALE = 1.04f;
    private static final long DURATION = 150L;

    private FocusEffects() {
    }

    /** 给卡片附加「获得焦点放大 / 失去焦点还原」动画。 */
    public static void attach(final View view) {
        if (view == null) {
            return;
        }
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                focusScale(v, hasFocus);
            }
        });
    }

    /** 供自定义 OnFocusChangeListener 复用同一套缩放反馈。 */
    public static void focusScale(View v, boolean hasFocus) {
        if (v == null) {
            return;
        }
        animate(v, hasFocus ? 1.0f : SCALE, hasFocus ? SCALE : 1.0f);
    }

    private static void animate(View v, float from, float to) {
        ScaleAnimation sa = new ScaleAnimation(from, to, from, to,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        sa.setDuration(DURATION);
        sa.setFillEnabled(true);
        sa.setInterpolator(new AccelerateDecelerateInterpolator());
        v.startAnimation(sa);
    }
}
