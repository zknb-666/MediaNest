package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.core.util.L;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.juying.medianest.core.model.Library;
import com.juying.medianest.ui.ImageLoader;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.policy.QualityBadges;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 官方飞牛 TV 1.5.1 首页渲染器；尺寸以 1920×1080 参考机的 UIAutomator 实测值为基准。 */
final class OfficialHomeRenderer {

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_CARD = 0xFF23242B;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xCCFFFFFF;
    private static final int COLOR_BORDER = 0x14FFFFFF;
    private static final float FOCUS_SCALE = 1.10f;

    private final MainActivity activity;
    private final LinearLayout contentList;
    private final ScrollView contentScroll;
    private final ImageLoader imageLoader;
    private final float scale;
    private final Handler clockHandler = new Handler();
    private final TextView clock;
    private boolean clockRunning;
    private View firstFocus;
    /** 首页每个横排的卡片（按显示顺序），用于建立显式方向键焦点链。 */
    private final List<List<View>> focusRows = new ArrayList<List<View>>();
    private static final int FOCUS_ID_BASE = 0x3A000000;
    private int focusIdSeq = FOCUS_ID_BASE;

    private final Runnable clockTick = new Runnable() {
        public void run() {
            if (!clockRunning) {
                return;
            }
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            clockHandler.postDelayed(this, 1000L);
        }
    };

    OfficialHomeRenderer(MainActivity activity, LinearLayout contentList,
                         ScrollView contentScroll, ImageLoader imageLoader) {
        this.activity = activity;
        this.contentList = contentList;
        this.contentScroll = contentScroll;
        this.imageLoader = imageLoader;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        this.scale = Math.max(0.1f, width / 1920f);
        this.clock = (TextView) activity.findViewById(R.id.official_clock);
        configureChrome();
    }

    int s(int designPx) {
        return Math.round(designPx * scale);
    }

    private float sf(int designPx) {
        return designPx * scale;
    }

    void onResume() {
        clockRunning = true;
        clockHandler.removeCallbacks(clockTick);
        clockTick.run();
    }

    void onPause() {
        clockRunning = false;
        clockHandler.removeCallbacks(clockTick);
    }

    /** 非首页暂时复用既有筛选和网格，只应用官方主题的页面留白。 */
    void prepareList() {
        contentList.setPadding(s(80), s(9), s(80), s(40));
    }

    /** 官方首页加载中的静态骨架：标题条 + 4 张 410×220 卡片，共三屏。 */
    void showSkeleton() {
        contentList.removeAllViews();
        contentList.setPadding(0, s(9), 0, 0);
        contentScroll.scrollTo(0, 0);
        focusRows.clear();
        focusIdSeq = FOCUS_ID_BASE;
        addSkeletonSection(10);
        addSkeletonSection(44);
        addSkeletonSection(44);
    }

    private void addSkeletonSection(int titleTopGap) {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(s(80), 0, 0, 0);

        View title = new View(activity);
        GradientDrawable titleBg = new GradientDrawable();
        titleBg.setColor(0xFF1C1E21);
        titleBg.setCornerRadius(sf(8));
        title.setBackground(titleBg);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(s(141), s(44));
        titleLp.topMargin = s(titleTopGap);
        section.addView(title, titleLp);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, s(220));
        rowLp.topMargin = s(24);
        section.addView(row, rowLp);
        for (int i = 0; i < 4; i++) {
            View card = new View(activity);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xFF1C1E21);
            cardBg.setCornerRadius(sf(8));
            card.setBackground(cardBg);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(s(410), s(220));
            if (i > 0) {
                cardLp.leftMargin = s(40);
            }
            row.addView(card, cardLp);
        }
        contentList.addView(section);
    }

    private void configureChrome() {
        View topbar = activity.findViewById(R.id.official_topbar);
        ViewGroup.LayoutParams topLp = topbar.getLayoutParams();
        topLp.height = s(171);
        topbar.setLayoutParams(topLp);
        topbar.setBackgroundColor(COLOR_BG);
        FrameLayout.LayoutParams scrollLp = (FrameLayout.LayoutParams) contentScroll.getLayoutParams();
        scrollLp.topMargin = s(171);
        contentScroll.setLayoutParams(scrollLp);

        ImageView logo = (ImageView) activity.findViewById(R.id.official_logo);
        RelativeLayout.LayoutParams logoLp = (RelativeLayout.LayoutParams) logo.getLayoutParams();
        logoLp.width = s(310);
        logoLp.height = s(85);
        logoLp.leftMargin = s(80);
        logoLp.topMargin = s(60);
        logo.setLayoutParams(logoLp);

        LinearLayout actions = (LinearLayout) activity.findViewById(R.id.official_top_actions);
        RelativeLayout.LayoutParams actionsLp = (RelativeLayout.LayoutParams) actions.getLayoutParams();
        actionsLp.height = s(60);
        actionsLp.topMargin = s(72);
        actionsLp.rightMargin = s(263);
        actions.setLayoutParams(actionsLp);

        Typeface icons = Typeface.createFromAsset(activity.getAssets(), "official_icons.ttf");
        setupAction(R.id.btn_search, R.id.search_icon, R.id.search_label, "\uEB23", icons);
        setupAction(R.id.btn_refresh, R.id.refresh_icon, R.id.refresh_label, "\uEB1C", icons);
        setupAction(R.id.btn_user, R.id.user_icon, R.id.user_label, "\uEBA5", icons);
        setupAction(R.id.btn_settings, R.id.settings_icon, R.id.settings_label, "\uE9F1", icons);

        View divider = activity.findViewById(R.id.official_divider);
        RelativeLayout.LayoutParams dividerLp = (RelativeLayout.LayoutParams) divider.getLayoutParams();
        dividerLp.width = Math.max(1, s(1));
        dividerLp.height = s(26);
        dividerLp.leftMargin = s(1713);
        dividerLp.topMargin = s(89);
        divider.setLayoutParams(dividerLp);

        RelativeLayout.LayoutParams clockLp = (RelativeLayout.LayoutParams) clock.getLayoutParams();
        clockLp.width = s(106);
        clockLp.height = s(57);
        clockLp.leftMargin = s(1734);
        clockLp.topMargin = s(74);
        clock.setLayoutParams(clockLp);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(36));

        contentScroll.setClipChildren(false);
        contentScroll.setClipToPadding(false);
        contentList.setClipChildren(false);
    }

    /**
     * 官方顶栏按钮：未聚焦 70×60 只显示图标；聚焦时展开成「图标 + 文字」白色胶囊。
     * 按钮组整体右对齐 1657，所以聚焦项变宽会让整组向左展开，右侧边缘保持不变。
     */
    private void setupAction(int buttonId, int iconId, int labelId, String glyph, Typeface icons) {
        final View button = activity.findViewById(buttonId);
        final TextView icon = (TextView) activity.findViewById(iconId);
        final TextView label = (TextView) activity.findViewById(labelId);
        icon.setTypeface(icons);
        icon.setText(glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(42));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(36));

        ViewGroup.LayoutParams lp = button.getLayoutParams();
        lp.width = s(70);
        lp.height = s(60);
        button.setLayoutParams(lp);
        button.setPadding(s(14), 0, s(14), 0);
        button.setBackground(null);
        icon.setTextColor(COLOR_TEXT_SECONDARY);
        label.setVisibility(View.GONE);

        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                ViewGroup.LayoutParams buttonLp = button.getLayoutParams();
                buttonLp.width = hasFocus
                        ? ViewGroup.LayoutParams.WRAP_CONTENT : s(70);
                button.setLayoutParams(buttonLp);
                if (hasFocus) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xFFFFFFFF);
                    bg.setCornerRadius(sf(30));
                    button.setBackground(bg);
                    icon.setTextColor(0xFF15171A);
                    label.setTextColor(0xFF15171A);
                    label.setVisibility(View.VISIBLE);
                } else {
                    button.setBackground(null);
                    icon.setTextColor(COLOR_TEXT_SECONDARY);
                    label.setVisibility(View.GONE);
                }
            }
        });
    }

    /** 顶栏账号按钮的文字取自当前登录账号（官方显示用户名）。 */
    void setAccountName(String name) {
        TextView label = (TextView) activity.findViewById(R.id.user_label);
        if (label == null) {
            return;
        }
        if (name == null || name.length() == 0) {
            label.setText("");
            label.setVisibility(View.GONE);
        } else {
            label.setText(name);
        }
    }

    void renderHome(ServerConfig cfg, MediaUseCases api, List<MediaItem> items,
                    List<Library> libraries, Map<String, Integer> counts,
                    List<MediaItem> continuing) {
        contentList.removeAllViews();
        contentList.setPadding(0, s(9), 0, s(40));
        contentScroll.scrollTo(0, 0);
        firstFocus = null;
        focusRows.clear();
        focusIdSeq = FOCUS_ID_BASE;
        boolean firstSection = true;

        if (!continuing.isEmpty()) {
            addSection("继续播放", firstSection);
            firstSection = false;
            List<View> cards = new ArrayList<View>();
            for (int i = 0; i < continuing.size(); i++) {
                cards.add(buildContinueCard(continuing.get(i), cfg, api));
            }
            addRow(cards, 14);
        }

        addSection("媒体库", firstSection);
        boolean mediaFirst = firstSection;
        firstSection = false;
        List<View> libraryCards = new ArrayList<View>();
        for (int i = 0; i < libraries.size(); i++) {
            libraryCards.add(buildLibraryCard(libraries.get(i), items, cfg, api));
        }
        if (libraryCards.isEmpty()) {
            libraryCards.add(buildEmptyCard("暂无媒体库", 410, 298));
        }
        addRow(libraryCards, mediaFirst ? 14 : 30);

        addSection("分类", false);
        List<View> categoryCards = new ArrayList<View>();
        boolean jellyfin = "JELLYFIN".equalsIgnoreCase(cfg.apiMode);
        categoryCards.add(buildCategoryCard("收藏", "favorite", counts, items,
                R.drawable.official_type_favorite));
        categoryCards.add(buildCategoryCard("全部", "all", counts, items,
                R.drawable.official_type_all));
        categoryCards.add(buildCategoryCard("电影", "movie", counts, items,
                R.drawable.official_type_movie));
        categoryCards.add(buildCategoryCard("电视节目", "tv", counts, items,
                R.drawable.official_type_tv));
        if (shouldShowLiveCategory(cfg, counts, items)) {
            categoryCards.add(buildCategoryCard("电视直播", "live", counts, items,
                    R.drawable.official_type_live));
        }
        if (!jellyfin) {
            categoryCards.add(buildCategoryCard("其他视频", "other", counts, items,
                    R.drawable.official_type_other));
        }
        addRow(categoryCards, 30);

        if (libraries.isEmpty()) {
            addSection("全部", false);
            addPosterRow(items, cfg, api);
        } else {
            for (int i = 0; i < libraries.size(); i++) {
                Library lib = libraries.get(i);
                addSection(lib.name, false);
                addPosterRow(filterByLibrary(items, lib.guid), cfg, api);
            }
        }

        bindFocusGraph();

        if (firstFocus != null) {
            contentList.post(new Runnable() {
                public void run() {
                    firstFocus.requestFocus();
                }
            });
        }
    }

    /** 官方分区标题：首屏标题高 70，其余 54，并与上一行间隔 52。 */
    private void addSection(String title, boolean first) {
        TextView view = new TextView(activity);
        view.setText(title);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(COLOR_TEXT);
        view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setPadding(s(80), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, s(first ? 70 : 54));
        if (!first) {
            lp.topMargin = s(52);
        }
        contentList.addView(view, lp);
    }

    private void addRow(List<View> cards) {
        addRow(cards, 30);
    }

    private void addRow(List<View> cards, int topGap) {
        HorizontalScrollView hsv = new HorizontalScrollView(activity);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setFocusable(false);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(s(80), 0, s(80), 0);
        List<View> focusables = new ArrayList<View>();
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            ViewGroup.LayoutParams raw = card.getLayoutParams();
            LinearLayout.LayoutParams lp;
            if (raw instanceof LinearLayout.LayoutParams) {
                lp = (LinearLayout.LayoutParams) raw;
            } else {
                lp = new LinearLayout.LayoutParams(raw == null
                        ? LinearLayout.LayoutParams.WRAP_CONTENT : raw.width,
                        raw == null ? LinearLayout.LayoutParams.WRAP_CONTENT : raw.height);
            }
            lp.rightMargin = i + 1 < cards.size() ? s(40) : 0;
            row.addView(card, lp);
            if (card.isFocusable()) {
                focusables.add(card);
                if (firstFocus == null) {
                    firstFocus = card;
                }
            }
        }
        if (!focusables.isEmpty()) {
            focusRows.add(focusables);
        }
        hsv.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = s(topGap);
        contentList.addView(hsv, rowLp);
    }

    private View buildContinueCard(final MediaItem item, ServerConfig cfg, MediaUseCases api) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(s(398),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        RoundedFrameLayout frame = new RoundedFrameLayout(activity, sf(8));
        frame.setBackgroundColor(COLOR_CARD);
        ImageView image = new ImageView(activity);
        boolean live = isLive(item);
        image.setScaleType(live ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(COLOR_CARD);
        frame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        loadImage(image, live ? item.posterPath : firstPath(item.backdropPath, item.posterPath),
                live ? R.drawable.official_live_placeholder : R.drawable.official_video_placeholder,
                640, cfg, api);

        if (item.progressPercent > 0) {
            FrameLayout track = new FrameLayout(activity);
            track.setBackgroundColor(0x40FFFFFF);
            FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, s(6));
            trackLp.gravity = Gravity.BOTTOM;
            trackLp.leftMargin = s(16);
            trackLp.rightMargin = s(16);
            trackLp.bottomMargin = s(16);
            frame.addView(track, trackLp);
            View fill = new View(activity);
            fill.setBackgroundColor(0xFF4A91FF);
            int width = s(366) * Math.min(100, item.progressPercent) / 100;
            FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(width,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            fillLp.gravity = Gravity.LEFT;
            track.addView(fill, fillLp);
        }
        View border = makeBorder(8, false);
        frame.addView(border, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.addView(frame, new LinearLayout.LayoutParams(s(398), s(224)));

        TextView title = text(item.title, 30, COLOR_TEXT, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(s(398), s(49));
        titleLp.topMargin = s(12);
        card.addView(title, titleLp);
        TextView subtitle = text(continueSubtitle(item), 26, COLOR_TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER);
        card.addView(subtitle, new LinearLayout.LayoutParams(s(398), s(42)));

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                activity.openPlayer(item);
            }
        });
        // 官方长按继续观看卡片弹 dialog_play_list_menu
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showPlayListMenu(item, cfg, api);
                return true;
            }
        });
        attachFocus(card, border, 8, false, null, null);
        return card;
    }

    /** 官方 dialog_play_list_menu：504×565、标题 32 + 6 个 28sp 菜单项。 */
    private void showPlayListMenu(final MediaItem item, final ServerConfig cfg, final MediaUseCases api) {
        final Dialog dialog = new Dialog(activity,
                android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0x99000000);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2F3035);
        bg.setCornerRadius(sf(48));
        bg.setStroke(Math.max(2, s(2)), 0x33FFFFFF);
        panel.setBackgroundDrawable(bg);
        panel.setPadding(s(32), 0, s(32), 0);

        TextView title = text(item.title, 32, 0xCCFFFFFF, true);
        title.setMaxLines(1);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = s(48);
        panel.addView(title, titleLp);

        addMenuRow(panel, "从继续观看移除", 43, 66, new Runnable() {
            public void run() {
                dialog.dismiss();
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            // 网页端移除继续观看：POST /play/record ts=0
                            api.reportProgress(cfg, item, 0, false);
                        } catch (Throwable t) {
                            L.w("Home", "移除继续观看失败：" + t.getMessage());
                        }
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                activity.selectCategory("home");
                            }
                        });
                    }
                }, "fntv-remove-continue").start();
            }
        });
        addMenuRow(panel, "查看详情", 0, 66, new Runnable() {
            public void run() {
                dialog.dismiss();
                activity.openItem(item);
            }
        });
        addMenuRow(panel, "从头播放", 0, 68, new Runnable() {
            public void run() {
                dialog.dismiss();
                MediaItem copy = MediaItem.fromJson(item.toJson());
                copy.resumeSeconds = 0;
                copy.playbackPositionSec = 0;
                activity.openPlayer(copy);
            }
        });
        addMenuRow(panel, "标记为已看", 0, 68, new Runnable() {
            public void run() {
                dialog.dismiss();
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            api.setWatched(cfg, item.guid, true);
                        } catch (Throwable t) {
                            L.w("Home", "标记已看失败：" + t.getMessage());
                        }
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                activity.selectCategory("home");
                            }
                        });
                    }
                }, "fntv-mark-watched").start();
            }
        });
        addMenuRow(panel, item.favorite ? "取消收藏" : "收藏", 0, 68, new Runnable() {
            public void run() {
                dialog.dismiss();
                final boolean target = !item.favorite;
                new Thread(new Runnable() {
                    public void run() {
                        String msg = target ? "已收藏" : "已取消收藏";
                        try {
                            api.setFavorite(cfg, item.guid, target);
                        } catch (Throwable t) {
                            msg = "操作失败：" + t.getMessage();
                        }
                        final String text = msg;
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }, "fntv-favorite").start();
            }
        });
        // 官方布局里「取消」默认 GONE，靠返回键关闭
        root.addView(panel, new FrameLayout.LayoutParams(s(504), s(565), Gravity.CENTER));
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.show();
        if (panel.getChildCount() > 1) {
            panel.getChildAt(1).requestFocus();
        }
    }

    private TextView addMenuRow(LinearLayout panel, String label, int topMargin, int height,
                                final Runnable action) {
        final TextView row = text(label, 28, COLOR_TEXT, true);
        row.setGravity(Gravity.CENTER);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFFFFFFFF);
        focused.setCornerRadius(sf(33));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        normal.setCornerRadius(sf(33));
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        row.setBackgroundDrawable(states);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                row.setTextColor(hasFocus ? 0xFF000000 : COLOR_TEXT);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, s(height));
        lp.topMargin = s(topMargin);
        lp.bottomMargin = s(16);
        panel.addView(row, lp);
        return row;
    }

    private View buildLibraryCard(final Library lib, List<MediaItem> items,
                                  ServerConfig cfg, MediaUseCases api) {
        final RoundedFrameLayout card = new RoundedFrameLayout(activity, sf(8));
        card.setBackgroundColor(COLOR_CARD);
        card.setLayoutParams(new LinearLayout.LayoutParams(s(410), s(298)));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        card.addView(column, columnLp);

        LinearLayout posters = new LinearLayout(activity);
        posters.setOrientation(LinearLayout.HORIZONTAL);
        posters.setGravity(Gravity.CENTER_HORIZONTAL);
        List<ImageView> posterViews = new ArrayList<ImageView>();
        List<String> paths = new ArrayList<String>(lib.posters);
        List<MediaItem> inLib = filterByLibrary(items, lib.guid);
        for (int i = 0; i < inLib.size() && paths.size() < 3; i++) {
            String path = inLib.get(i).posterPath;
            if (path != null && path.length() > 0 && !paths.contains(path)) {
                paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            ImageView empty = new ImageView(activity);
            empty.setScaleType(ImageView.ScaleType.FIT_CENTER);
            empty.setImageResource(R.drawable.official_live_placeholder);
            empty.setBackgroundColor(COLOR_CARD);
            posters.addView(empty, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            posterViews.add(empty);
        } else {
            for (int i = 0; i < 3; i++) {
                ReflectionImageView image = new ReflectionImageView(activity);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(COLOR_CARD);
                if (i < paths.size()) {
                    loadImage(image, paths.get(i), R.drawable.official_video_placeholder,
                            300, cfg, api);
                }
                LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(
                        s(130), LinearLayout.LayoutParams.MATCH_PARENT);
                if (i > 0) {
                    posterLp.leftMargin = s(2);
                }
                posters.addView(image, posterLp);
                posterViews.add(image);
            }
        }
        LinearLayout.LayoutParams postersLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, s(222));
        postersLp.leftMargin = s(8);
        postersLp.rightMargin = s(8);
        postersLp.topMargin = s(8);
        column.addView(posters, postersLp);

        final TextView name = text(lib.name, 30, COLOR_TEXT, false);
        name.setGravity(Gravity.CENTER);
        FrameLayout nameArea = new FrameLayout(activity);
        if (!paths.isEmpty()) {
            LinearLayout reflection = new LinearLayout(activity);
            reflection.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < posterViews.size(); i++) {
                ReflectionView mirror = new ReflectionView(activity, posterViews.get(i));
                ((ReflectionImageView) posterViews.get(i)).mirror = mirror;
                LinearLayout.LayoutParams mirrorLp = new LinearLayout.LayoutParams(
                        s(130), LinearLayout.LayoutParams.MATCH_PARENT);
                if (i > 0) {
                    mirrorLp.leftMargin = s(2);
                }
                reflection.addView(mirror, mirrorLp);
            }
            nameArea.addView(reflection, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        nameArea.addView(name, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, s(60));
        nameLp.leftMargin = s(8);
        nameLp.rightMargin = s(8);
        nameLp.bottomMargin = s(8);
        column.addView(nameArea, nameLp);

        View border = makeBorder(8, true);
        card.addView(border, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                activity.selectCategory("lib:" + lib.guid);
            }
        });
        attachFocus(card, border, 8, true, name, null);
        return card;
    }

    private View buildCategoryCard(String title, final String key,
                                   Map<String, Integer> counts, List<MediaItem> items,
                                   int backgroundRes) {
        RoundedFrameLayout card = new RoundedFrameLayout(activity, sf(8));
        card.setBackgroundColor(COLOR_CARD);
        card.setLayoutParams(new LinearLayout.LayoutParams(s(410), s(180)));
        ImageView background = new ImageView(activity);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setImageResource(backgroundRes);
        card.addView(background, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_HORIZONTAL);
        labels.setPadding(0, s(31), 0, 0);
        TextView titleView = text(title, 40, COLOR_TEXT, true);
        titleView.setGravity(Gravity.CENTER);
        labels.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, s(70)));
        int count = categoryCount(key, counts, items);
        TextView countView = text(String.valueOf(count), 28, COLOR_TEXT_SECONDARY, false);
        countView.setGravity(Gravity.CENTER);
        labels.addView(countView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, s(49)));
        card.addView(labels, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View border = makeBorder(8, false);
        card.addView(border, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                activity.selectCategory(key);
            }
        });
        attachFocus(card, border, 8, false, null, null);
        return card;
    }

    private void addPosterRow(List<MediaItem> items, ServerConfig cfg, MediaUseCases api) {
        List<View> cards = new ArrayList<View>();
        for (int i = 0; i < items.size(); i++) {
            cards.add(buildPosterCard(items.get(i), cfg, api));
        }
        if (cards.isEmpty()) {
            cards.add(buildEmptyCard("暂无内容", 260, 390));
        }
        addRow(cards);
    }

    private View buildPosterCard(final MediaItem item, ServerConfig cfg, MediaUseCases api) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(s(260),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        RoundedFrameLayout frame = new RoundedFrameLayout(activity, sf(8));
        frame.setBackgroundColor(COLOR_CARD);
        ImageView image = new ImageView(activity);
        boolean live = isLive(item);
        image.setScaleType(live ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(COLOR_CARD);
        frame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        loadImage(image, item.posterPath,
                live ? R.drawable.official_live_placeholder : R.drawable.official_video_placeholder,
                400, cfg, api);

        GradientDrawable shadow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0015171A, 0xB515171A});
        View bottomShadow = new View(activity);
        bottomShadow.setBackground(shadow);
        FrameLayout.LayoutParams shadowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, s(106));
        shadowLp.gravity = Gravity.BOTTOM;
        frame.addView(bottomShadow, shadowLp);

        if (item.voteAverage > 0 && prefBool("score", true)) {
            TextView score = text(String.format(Locale.US, "%.1f", item.voteAverage),
                    28, 0xFFE5A064, true);
            score.setShadowLayer(s(2), 0, s(1), 0x80000000);
            FrameLayout.LayoutParams scoreLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, s(35));
            scoreLp.leftMargin = s(15);
            frame.addView(score, scoreLp);
        }
        addQualityBadges(frame, item);
        addWatchedMark(frame, item);
        View border = makeBorder(8, false);
        frame.addView(border, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.addView(frame, new LinearLayout.LayoutParams(s(260), s(390)));

        TextView title = text(item.title, 30, COLOR_TEXT, false);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(s(260), s(49));
        titleLp.topMargin = s(12);
        card.addView(title, titleLp);
        TextView subtitle = text(posterSubtitle(item), 26, COLOR_TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER);
        card.addView(subtitle, new LinearLayout.LayoutParams(s(260), s(32)));

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                activity.openItem(item);
            }
        });
        attachFocus(card, border, 8, false, null, title);
        return card;
    }

    /** 设置页「外观」里的显示开关：评分 / “已观看”标记 / 分辨率。 */
    private boolean prefBool(String key, boolean def) {
        return activity.getSharedPreferences("official_settings", Context.MODE_PRIVATE)
                .getBoolean(key, def);
    }

    /** 「已观看」图标：白色圆底 + ic_watched_filled 深色对勾，贴在封面左下角。 */
    private void addWatchedMark(FrameLayout frame, MediaItem item) {
        if (!item.watched) {
            return;
        }
        if (!prefBool("watched", false)) {
            return;
        }
        ImageView mark = new ImageView(activity);
        mark.setImageResource(R.drawable.ic_watched_filled);
        mark.setColorFilter(0xFF15171A);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE6FFFFFF);
        mark.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(36), s(36));
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin = s(16);
        lp.bottomMargin = s(8);
        frame.addView(mark, lp);
    }

    private void addQualityBadges(FrameLayout frame, MediaItem item) {
        if (!prefBool("resolution", true)) {
            return;
        }
        List<String> labels = QualityBadges.from(item.resolutions);
        if (labels.isEmpty()) {
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            TextView badge = text(label, 20, COLOR_TEXT, false);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(s(6), s(1), s(6), s(1));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(QualityBadges.inverted(label) ? 0xEEFFFFFF : 0x99101113);
            bg.setCornerRadius(sf(3));
            bg.setStroke(Math.max(1, s(1)), QualityBadges.inverted(label)
                    ? 0xEEFFFFFF : 0xCCFFFFFF);
            badge.setBackground(bg);
            badge.setTextColor(QualityBadges.inverted(label) ? 0xFF15171A : COLOR_TEXT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, s(40));
            if (i > 0) {
                lp.leftMargin = s(5);
            }
            box.addView(badge, lp);
        }
        box.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, s(48));
        boxLp.gravity = Gravity.BOTTOM;
        boxLp.rightMargin = s(8);
        boxLp.bottomMargin = s(6);
        frame.addView(box, boxLp);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(size));
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private View makeBorder(int radius, boolean normalVisible) {
        View border = new View(activity);
        updateBorder(border, radius, false, normalVisible);
        return border;
    }

    private void updateBorder(View border, int radius, boolean focused, boolean normalVisible) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(sf(radius));
        int width = focused ? Math.max(2, s(3)) : (normalVisible ? Math.max(1, s(1)) : 0);
        bg.setStroke(width, focused ? COLOR_TEXT : COLOR_BORDER);
        if (border.getParent() instanceof FrameLayout) {
            border.setVisibility(View.GONE);
            ((FrameLayout) border.getParent()).setForeground(bg);
        } else {
            border.setBackground(bg);
        }
    }

    private void attachFocus(final View root, final View border, final int radius,
                             final boolean normalBorder, final TextView label,
                             final TextView selectedTitle) {
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setClickable(true);
        updateBorder(border, radius, false, normalBorder);
        root.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    rememberRowFocus(root);
                }
                updateBorder(border, radius, hasFocus, normalBorder);
                if (label != null) {
                    label.setBackgroundColor(hasFocus ? COLOR_TEXT : 0xE615171A);
                    label.setTextColor(hasFocus ? COLOR_BG : COLOR_TEXT);
                }
                if (selectedTitle != null) {
                    selectedTitle.setSelected(hasFocus);
                }
                if (hasFocus) {
                    root.post(new Runnable() {
                        public void run() {
                            if (root.hasFocus()) {
                                centerFocusedVertically(root);
                                centerFocusedHorizontally(root);
                            }
                        }
                    });
                }
                // minSdk 16：ViewPropertyAnimator 是 API 11 起的能力，直接使用，
                // 不再为 API 9 保留 ScaleAnimation 分支（两者混用还会残留缩放状态）。
                v.animate().scaleX(hasFocus ? FOCUS_SCALE : 1f)
                        .scaleY(hasFocus ? FOCUS_SCALE : 1f)
                        .setDuration(120L)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
            }
        });
    }

    /**
     * 建立方向键焦点链（对齐官方实测）：
     * 1. 行内左右相邻；行首/行尾自环——官方在行尾按右会停在原卡片，不会跳到别的行；
     * 2. 行间上下默认进入相邻行的第一张卡，之后由 rememberRowFocus 改成该行最后一次聚焦的卡片，
     *    这样「下行→上行」能回到原卡片，而不是按屏幕坐标落到别的列。
     */
    private void bindFocusGraph() {
        for (int r = 0; r < focusRows.size(); r++) {
            List<View> row = focusRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                row.get(i).setId(focusIdSeq++);
            }
            for (int i = 0; i < row.size(); i++) {
                View card = row.get(i);
                card.setNextFocusLeftId(i > 0 ? row.get(i - 1).getId() : card.getId());
                card.setNextFocusRightId(i + 1 < row.size() ? row.get(i + 1).getId() : card.getId());
            }
        }
        for (int r = 0; r + 1 < focusRows.size(); r++) {
            View belowFirst = focusRows.get(r + 1).get(0);
            View aboveFirst = focusRows.get(r).get(0);
            for (int i = 0; i < focusRows.get(r).size(); i++) {
                focusRows.get(r).get(i).setNextFocusDownId(belowFirst.getId());
            }
            for (int i = 0; i < focusRows.get(r + 1).size(); i++) {
                focusRows.get(r + 1).get(i).setNextFocusUpId(aboveFirst.getId());
            }
        }
    }

    /** 记录某行最后聚焦的卡片，作为上下行切换时的落点（官方行记忆行为）。 */
    private void rememberRowFocus(View card) {
        for (int r = 0; r < focusRows.size(); r++) {
            List<View> row = focusRows.get(r);
            if (row.indexOf(card) < 0) {
                continue;
            }
            if (r > 0) {
                List<View> above = focusRows.get(r - 1);
                for (int i = 0; i < above.size(); i++) {
                    above.get(i).setNextFocusDownId(card.getId());
                }
            }
            if (r + 1 < focusRows.size()) {
                List<View> below = focusRows.get(r + 1);
                for (int i = 0; i < below.size(); i++) {
                    below.get(i).setNextFocusUpId(card.getId());
                }
            }
            return;
        }
    }

    private void centerFocusedVertically(View focused) {
        int centerY = focused.getHeight() / 2;
        View current = focused;
        while (current != contentList && current.getParent() instanceof View) {
            centerY += current.getTop();
            current = (View) current.getParent();
        }
        if (current != contentList || contentScroll.getHeight() <= 0) {
            return;
        }
        int target = centerY - Math.round(contentScroll.getHeight() * 0.45f);
        int max = Math.max(0, contentList.getHeight() - contentScroll.getHeight());
        target = Math.max(0, Math.min(target, max));
        if (Math.abs(contentScroll.getScrollY() - target) > 1) {
            contentScroll.smoothScrollTo(0, target);
        }
    }

    private void centerFocusedHorizontally(View focused) {
        View current = focused;
        int centerX = focused.getWidth() / 2;
        while (current.getParent() instanceof View) {
            centerX += current.getLeft();
            current = (View) current.getParent();
            if (current instanceof HorizontalScrollView) {
                break;
            }
        }
        if (!(current instanceof HorizontalScrollView)) {
            return;
        }
        HorizontalScrollView scroller = (HorizontalScrollView) current;
        View content = scroller.getChildAt(0);
        if (content == null || scroller.getWidth() <= 0) {
            return;
        }
        int target = centerX - scroller.getWidth() / 2;
        int max = Math.max(0, content.getWidth() - scroller.getWidth());
        target = Math.max(0, Math.min(target, max));
        if (Math.abs(scroller.getScrollX() - target) > 1) {
            scroller.smoothScrollTo(target, 0);
        }
    }

    private View buildEmptyCard(String message, int width, int height) {
        TextView empty = text(message, 26, COLOR_TEXT_SECONDARY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setBackgroundColor(COLOR_CARD);
        empty.setLayoutParams(new LinearLayout.LayoutParams(s(width), s(height)));
        return empty;
    }

    private void loadImage(ImageView image, String path, int placeholder,
                           int width, ServerConfig cfg, MediaUseCases api) {
        image.setScaleType(placeholder == R.drawable.official_live_placeholder
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(placeholder);
        if (path != null && path.length() > 0) {
            imageLoader.load(api.imageUrl(cfg, path, width), image, api.authorizationHeader(cfg), placeholder);
        }
    }

    private static String firstPath(String first, String second) {
        return first != null && first.length() > 0 ? first : second;
    }

    private static boolean isLive(MediaItem item) {
        String type = item.mediaType == null ? "" : item.mediaType.toLowerCase(Locale.US);
        return type.indexOf("live") >= 0 || type.indexOf("channel") >= 0;
    }

    private static String continueSubtitle(MediaItem item) {
        if (isLive(item)) {
            return "直播";
        }
        List<String> parts = new ArrayList<String>();
        if (item.seasonCount > 0) {
            parts.add("第 " + item.seasonCount + " 季");
        }
        if (item.episodeCount > 0) {
            parts.add("第 " + item.episodeCount + " 集");
        }
        return join(parts);
    }

    private static String posterSubtitle(MediaItem item) {
        List<String> parts = new ArrayList<String>();
        if (item.isSeason() && item.seasonCount > 0) {
            parts.add("第 " + item.seasonCount + " 季");
        } else if (item.episodeCount > 0 && "TV".equalsIgnoreCase(item.mediaType)) {
            parts.add("共 " + item.episodeCount + " 集");
        } else if (item.seasonCount > 0 && "TV".equalsIgnoreCase(item.mediaType)) {
            parts.add("共 " + item.seasonCount + " 季");
        }
        if (item.year != null && item.year.length() >= 4) {
            parts.add(item.year.substring(0, 4));
        }
        return join(parts);
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(" · ");
            }
            out.append(parts.get(i));
        }
        return out.toString();
    }

    static boolean shouldShowLiveCategory(ServerConfig cfg, Map<String, Integer> counts,
                                           List<MediaItem> items) {
        if (!"JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            return true;
        }
        if (categoryCount("live", counts, items) > 0) {
            return true;
        }
        for (int i = 0; i < items.size(); i++) {
            if (isLive(items.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static List<MediaItem> filterByLibrary(List<MediaItem> items, String guid) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < items.size(); i++) {
            if (guid.equalsIgnoreCase(items.get(i).libraryGuid)) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    private static int categoryCount(String key, Map<String, Integer> counts,
                                     List<MediaItem> items) {
        String countKey = "other".equals(key) ? "video" : key;
        if ("all".equals(key)) {
            countKey = "total";
        }
        Integer server = counts.get(countKey);
        if (server != null) {
            return server.intValue();
        }
        if ("all".equals(key)) {
            return items.size();
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            if ("favorite".equals(key) && item.favorite) {
                count++;
            } else if ("movie".equals(key) && "Movie".equalsIgnoreCase(item.mediaType)) {
                count++;
            } else if ("tv".equals(key) && "TV".equalsIgnoreCase(item.mediaType)) {
                count++;
            } else if ("live".equals(key) && isLive(item)) {
                count++;
            } else if ("other".equals(key) && "Video".equalsIgnoreCase(item.mediaType)) {
                count++;
            }
        }
        return count;
    }

    /** 官方媒体库卡片底部的海报镜像反射，向下渐隐到卡片底色。 */
    private static final class ReflectionView extends View {
        private final ImageView source;
        private final Paint fade = new Paint();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private int lastWidth;
        private int lastHeight;

        ReflectionView(Context context, ImageView source) {
            super(context);
            this.source = source;
            fade.setDither(true);
        }

        protected void onDraw(Canvas canvas) {
            Drawable d = source.getDrawable();
            if (d == null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            int sh = source.getHeight();
            if (w <= 0 || h <= 0 || iw <= 0 || ih <= 0 || sh <= 0) {
                return;
            }
            // 与 ImageView 的 CENTER_CROP 保持一致后再上下翻转，只露出底部。
            float sx = Math.max((float) w / iw, (float) sh / ih);
            float dw = iw * sx;
            float dh = ih * sx;
            float dx = (w - dw) / 2f;
            float dy = (sh - dh) / 2f;
            matrix.setScale(sx, -sx);
            matrix.postTranslate(dx, sh - dy);
            int save = canvas.save();
            canvas.clipRect(0, 0, w, h);
            d.setBounds(0, 0, iw, ih);
            canvas.concat(matrix);
            d.draw(canvas);
            canvas.restoreToCount(save);

            if (w != lastWidth || h != lastHeight) {
                lastWidth = w;
                lastHeight = h;
                fade.setShader(new LinearGradient(0, 0, 0, h,
                        0x0023242B, 0xFF23242B, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, fade);
        }
    }

    /** 图片加载完成后通知镜像重绘。 */
    private static final class ReflectionImageView extends ImageView {
        View mirror;

        ReflectionImageView(Context context) {
            super(context);
        }

        public void setImageDrawable(Drawable drawable) {
            super.setImageDrawable(drawable);
            if (mirror != null) {
                mirror.invalidate();
            }
        }
    }

    /** 老版本 Android 也可用的圆角裁剪容器，避免海报焦点白框仍是直角。 */
    private static final class RoundedFrameLayout extends FrameLayout {
        private final Path clipPath = new Path();
        private final RectF clipRect = new RectF();
        private final float radius;

        RoundedFrameLayout(Context context, float radius) {
            super(context);
            this.radius = radius;
            setClipChildren(false);
        }

        protected void dispatchDraw(Canvas canvas) {
            clipRect.set(0, 0, getWidth(), getHeight());
            clipPath.reset();
            clipPath.addRoundRect(clipRect, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clipPath);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
