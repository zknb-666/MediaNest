package com.juying.medianest.ui;

import com.juying.medianest.app.BuildConfig;
import com.juying.medianest.app.R;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 官方飞牛 TV 1.5.1「更多」页复刻（activity_more_layout）。
 *
 * 官方 181 实测（UIAutomator bounds，1920×1080）：
 * 标题「更多」[114,80][238,163]（48sp 粗体，sp 按 1.3 倍渲染）；
 * 卡片按 348×340、水平间距 100、垂直间距 80 自动排列，最多四列；
 * 卡片内容顶部 66、图标 90sp（ink 约 118 高）、图标下 33 为 32sp 标题、副标题再下 8 为 20sp；
 * 「关于飞牛」图标 75×75、顶部 88。底部版本行 26sp #59FFFFFF、y=1003。
 * 卡片描边见 {@link CardBorder}。
 */
public final class OfficialMoreActivity extends Activity {

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_TEXT = 0xCCFFFFFF;
    private static final int COLOR_ICON = 0xA6FFFFFF;
    private static final int COLOR_LABEL = 0xFFFFFFFF;
    private static final int COLOR_SUB = 0xA6FFFFFF;
    private static final int COLOR_FOOTER = 0x59FFFFFF;

    private float scale;
    private Typeface icons;
    private Typeface textFont;
    private View firstCard;
    private int nextViewId = 0x20000;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        icons = Typeface.createFromAsset(getAssets(), "official_icons.ttf");
        textFont = Typeface.createFromAsset(getAssets(), "noto_fz.ttf");
        setContentView(build());
        if (firstCard != null) {
            firstCard.requestFocus();
        }
    }

    private int s(int designPx) {
        return Math.round(designPx * scale);
    }

    /** 文本按官方 sp × 1.3 渲染（官方 181 fontScale=1.3，density=1）。 */
    private float ts(int designSp) {
        return designSp * 1.3f * scale;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        root.setClipChildren(false);

        TextView title = new TextView(this);
        title.setText("更多");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(48));
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(textFont, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = s(114);
        titleLp.topMargin = s(74);
        root.addView(title, titleLp);

        ArrayList<View> cards = new ArrayList<View>();
        View settings = card("\uEB28", 0, "设置", null, 66);
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(OfficialMoreActivity.this,
                        OfficialSettingsActivity.class));
            }
        });
        firstCard = settings;
        cards.add(settings);

        View update = card("\uEA24", 0, "版本更新",
                "已是最新版本 V" + BuildConfig.VERSION_NAME, 66);
        update.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ui.toast(OfficialMoreActivity.this,
                        "已是最新版本 V" + BuildConfig.VERSION_NAME);
            }
        });
        cards.add(update);

        View about = card(null, R.drawable.ic_about_medianest, "关于聚映", null, 88);
        about.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(OfficialMoreActivity.this, AboutActivity.class));
            }
        });
        cards.add(about);
        layoutCards(root, cards);

        TextView footer = new TextView(this);
        footer.setText("聚映 MediaNest 版本号 " + BuildConfig.VERSION_NAME);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(26));
        footer.setTextColor(COLOR_FOOTER);
        footer.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(s(1692), s(46));
        footerLp.leftMargin = s(114);
        footerLp.topMargin = s(1001);
        root.addView(footer, footerLp);
        return root;
    }

    private void layoutCards(FrameLayout root, ArrayList<View> cards) {
        int availableWidth = getResources().getDisplayMetrics().widthPixels - s(114) * 2;
        int columnStep = s(448);
        int columns = Math.max(1, 1 + (availableWidth - s(348)) / columnStep);
        for (int i = 0; i < cards.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            place(root, cards.get(i), 114 + column * 448, 203 + row * 420);
            cards.get(i).setNextFocusLeftId(column > 0
                    ? cards.get(i - 1).getId() : View.NO_ID);
            cards.get(i).setNextFocusRightId(column + 1 < columns && i + 1 < cards.size()
                    ? cards.get(i + 1).getId() : View.NO_ID);
            cards.get(i).setNextFocusUpId(i >= columns
                    ? cards.get(i - columns).getId() : View.NO_ID);
            cards.get(i).setNextFocusDownId(i + columns < cards.size()
                    ? cards.get(i + columns).getId() : View.NO_ID);
        }
    }

    private void place(FrameLayout root, View view, int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(348), s(340));
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        root.addView(view, lp);
    }

    /**
     * 卡片：图标（90sp 字体图标，或关于页 75×75 原图）→ 33 → 标题 32sp → 8 → 副标题 20sp；
     * 内容从卡片顶部 topPadding 开始，与官方 ConstraintLayout packed chain 的表现一致。
     */
    private View card(String glyph, int iconRes, String text, String subtitle, int topPadding) {
        final FrameLayout card = new FrameLayout(this);
        card.setId(nextViewId++);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(cardBackground(false));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, s(topPadding), 0, 0);
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (glyph != null) {
            TextView icon = new TextView(this);
            icon.setTypeface(icons);
            icon.setText(glyph);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(90));
            icon.setTextColor(COLOR_ICON);
            icon.setGravity(Gravity.CENTER);
            icon.setIncludeFontPadding(false);
            content.addView(icon, new LinearLayout.LayoutParams(s(118), s(118)));
        } else {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            content.addView(icon, new LinearLayout.LayoutParams(s(75), s(75)));
        }

        TextView name = new TextView(this);
        name.setText(text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(32));
        name.setTextColor(COLOR_LABEL);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = s(33);
        content.addView(name, nameLp);

        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(20));
            sub.setTextColor(COLOR_SUB);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = s(8);
            content.addView(sub, subLp);
        }

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(cardBackground(hasFocus));
                // 聚焦描边超出卡片边界，需重绘父布局才能擦除旧焦点。
                v.getRootView().invalidate();
            }
        });
        return card;
    }

    private Drawable cardBackground(boolean focused) {
        return new CardBorder(focused);
    }

    /**
     * 官方卡片描边（像素实测）：
     * 常态 圆角 24、2px #26FFFFFF（贴卡片边界内侧）；
     * 聚焦 圆角 26、4.4px #FFFFFF，路径中心在卡片外 8.4（左右）/ 8.05（上下）像素处
     * （外沿实测 103..472 / 192..553）。
     */
    private final class CardBorder extends Drawable {
        private final boolean focused;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CardBorder(boolean focused) {
            this.focused = focused;
            paint.setStyle(Paint.Style.STROKE);
        }

        public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            if (focused) {
                b.left -= sf(8.4f);
                b.top -= sf(8.05f);
                b.right += sf(8.4f);
                b.bottom += sf(8.05f);
                paint.setStrokeWidth(sf(4.4f));
                paint.setColor(0xFFFFFFFF);
                canvas.drawRoundRect(b, sf(26), sf(26), paint);
            } else {
                float inset = sf(1);
                b.inset(inset, inset);
                paint.setStrokeWidth(sf(2));
                paint.setColor(0x26FFFFFF);
                canvas.drawRoundRect(b, sf(23), sf(23), paint);
            }
        }

        public void setAlpha(int alpha) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private float sf(float designPx) {
        return designPx * scale;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
