package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** 聚映 MediaNest 关于页，布局参考官方 TV 客户端。 */
public final class AboutActivity extends Activity {

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_TITLE = 0xCCFFFFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DESC = 0xA6FFFFFF;

    private static final String INTRO =
            "聚映 MediaNest 是一款面向电视大屏的第三方流媒体客户端，当前已适配飞牛影视与 Jellyfin，"
            + "支持媒体库浏览、剧集详情、收藏与已观看同步、直播、字幕及智能选流。"
            + "后续计划接入 Emby 等服务。";

    private float scale;
    /** 官方内置正文字体 assets/NotoFZ-Medium-final.ttf，用于标题/介绍/版权行以对齐官方字形。 */
    private Typeface textFont;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        textFont = Typeface.createFromAsset(getAssets(), "noto_fz.ttf");
        setContentView(build());
    }

    private int s(int designPx) {
        return Math.round(designPx * scale);
    }

    private float ts(int designSp) {
        return designSp * 1.3f * scale;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        root.setClipChildren(false);

        TextView title = new TextView(this);
        title.setText("关于聚映");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(48));
        title.setTextColor(COLOR_TITLE);
        title.setTypeface(textFont, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = s(114);
        titleLp.topMargin = s(74);
        root.addView(title, titleLp);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(s(309), s(100));
        logoLp.leftMargin = s(114);
        logoLp.topMargin = s(243);
        root.addView(logo, logoLp);

        TextView intro = new TextView(this);
        intro.setText(INTRO);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(36));
        intro.setTextColor(COLOR_TEXT);
        intro.setLineSpacing(s(45), 1f);
        FrameLayout.LayoutParams introLp = new FrameLayout.LayoutParams(s(1492), s(291));
        introLp.leftMargin = s(114);
        introLp.topMargin = s(382);
        root.addView(intro, introLp);

        TextView footer = new TextView(this);
        footer.setText("聚映 MediaNest · 第三方客户端 · 与飞牛官方无关");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(24));
        footer.setTextColor(COLOR_DESC);
        footer.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(s(1692), s(42));
        footerLp.leftMargin = s(114);
        footerLp.topMargin = s(1010);
        root.addView(footer, footerLp);
        return root;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
