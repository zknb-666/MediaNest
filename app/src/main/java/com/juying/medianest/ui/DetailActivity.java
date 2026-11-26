package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;
import com.juying.medianest.core.util.L;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.juying.medianest.core.model.AudioTrack;
import com.juying.medianest.ui.ImageLoader;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.Person;
import com.juying.medianest.core.policy.QualityBadges;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.SubtitleTrack;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 详情页：按官方 181 客户端（com.trim.tv 1.5.1）逐块复刻，坐标取自 1920×1080 实机 dump。
 *
 * <p>三种形态共用一套「全屏背景 + 滚动内容」骨架：
 * <ul>
 *   <li>剧集详情：标题字图 → 元信息（分级/年份/国家/流派）→ 角标行 → 简介 → 播放/收藏/已看
 *       → 「共 N 季」→ 季卡片网格（6 列）。</li>
 *   <li>季详情：左竖版海报 + 右侧剧名/季名/评分/简介/按钮 → 「选集」+ 视图切换
 *       → 集数按钮（默认）或集缩略图卡片。</li>
 *   <li>电影/直播：同剧集头部，多出「剩余 X 小时 Y 分钟」进度行；电影还有字幕/音频下拉。</li>
 * </ul>
 *
 * <p>官方没有的不加：无返回按钮（遥控器 BACK 退出）、无「更多」菜单、无右侧大封面。
 * 背景整屏铺满横版 backdrops，季详情没有 backdrops 时按网页端规则用竖版海报低分辨率放大。
 */
public final class DetailActivity extends Activity {

    // ---- 官方配色（取自 181 截图取色） ----
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0xB3FFFFFF;
    private static final int C_MUTED = 0x8AFFFFFF;
    private static final int C_ACCENT = 0xFF3373D9;
    private static final int C_GOLD = 0xFFFFC94D;
    private static final int C_HEART = 0xFFBF0023;
    private static final int C_DARK_TEXT = 0xFF17181B;
    private static final int C_EP_BG = 0xFF23252A;
    private static final float DIM_ALPHA = 0.58f;

    // ---- 官方 1920 设计稿尺寸 ----
    private static final int LOGO_HEIGHT = 165;
    private static final int META_HEIGHT = 69;
    private static final int BTN_HEIGHT = 80;
    private static final int PILL_RADIUS = 40;
    private static final int CARD_W = 260;
    private static final int CARD_POSTER_H = 406;
    private static final int CARD_GAP = 30;
    private static final int CARD_COLUMNS = 6;
    private static final int SEASON_POSTER_W = 316;
    private static final int SEASON_POSTER_H = 474;
    private static final int EP_BTN_W = 96;
    private static final int EP_BTN_H = 64;
    private static final int EP_BTN_GAP = 22;
    private static final int EP_CARD_W = 544;
    private static final int EP_CARD_IMG_H = 308;
    private static final int EP_CARD_GAP = 40;
    private static final int DROPDOWN_W = 300;
    private static final int DROPDOWN_H = 73;

    private ConfigRepository store;
    private ConfigCodec.ConfigBundle bundle;
    private ServerConfig cfg;
    private MediaUseCases api;
    private ImageLoader imageLoader;
    private MediaItem item;

    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout body;
    private ImageView cover;
    private View blurScrim;
    private View dim;
    private ImageView posterView;

    private ImageView logo;
    private TextView titleText;
    private TextView subtitleText;
    private LinearLayout metaRow;
    private LinearLayout badgeRow;
    private TextView overview;
    private LinearLayout progressRow;
    private View progressFill;
    private TextView progressLabel;

    private LinearLayout buttonsRow;
    private LinearLayout playPill;
    private TextView playLabelView;
    private ImageView playIcon;
    private FrameLayout collectButton;
    private ImageView collectIcon;
    private FrameLayout watchButton;
    private ImageView watchIcon;

    private TextView subValue;
    private TextView audioValue;
    private LinearLayout selectorRow;
    private List<SubtitleTrack> subTracks = new ArrayList<SubtitleTrack>();
    private List<AudioTrack> audioTracks = new ArrayList<AudioTrack>();

    private List<MediaItem> seasons = new ArrayList<MediaItem>();
    private List<MediaItem> episodes = new ArrayList<MediaItem>();
    private MediaItem currentEpisode;
    private int currentEpNumber = 0;
    private int episodeViewMode = 0;

    private boolean gridFocused = false;
    private long lastOpenAt = 0L;

    /** 收藏状态变化标记：返回首页时收藏页需要重新拉 item/list 才能看到新收藏。 */
    public static boolean favoriteChanged = false;
    /** 详情页改过已看状态，返回首页需要重建卡片才会出现「已观看」图标。 */
    public static boolean watchedChanged = false;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = App.from(this).config();
        bundle = store.load();
        cfg = bundle.server;
        api = App.from(this).useCases(cfg);
        imageLoader = new ImageLoader(this);
        item = MediaItem.fromJson(Json.parseObject(getIntent().getStringExtra("item")));

        buildUi();
        applyHeader();
        loadImages();
        playPill.requestFocus();
        loadDetail();
    }

    // ---------------- 基础工具 ----------------

    private int px(int design) {
        return Math.round(design * getResources().getDisplayMetrics().widthPixels / 1920f);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if (bold) {
            v.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return v;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static GradientDrawable stroked(int color, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(stroke, 0xFFFFFFFF);
        return d;
    }

    private static GradientDrawable outlined(int fill, int strokeColor, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(stroke, strokeColor);
        return d;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }

    private boolean isTv() {
        return item.mediaType != null && item.mediaType.toLowerCase(Locale.US).indexOf("tv") >= 0;
    }

    private boolean isSeason() {
        return item.isSeason();
    }

    private boolean isLive() {
        String t = item.mediaType == null ? "" : item.mediaType.toLowerCase(Locale.US);
        return t.indexOf("live") >= 0 || t.indexOf("channel") >= 0;
    }

    private boolean isMovie() {
        return !isTv() && !isSeason() && !isLive();
    }

    private int margin() {
        return px(isTv() ? 104 : 80);
    }

    private int topInset() {
        if (isSeason()) {
            return px(96);
        }
        return px(isTv() ? 104 : 80);
    }

    // ---------------- 骨架 ----------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101114);
        root.setClipChildren(false);

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        blurScrim = new View(this);
        blurScrim.setBackgroundColor(0xB3000000);
        blurScrim.setVisibility(View.GONE);
        root.addView(blurScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dim = new View(this);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(margin(), topInset(), margin(), px(80));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (isSeason()) {
            buildSeasonLayout();
        } else {
            buildStandardLayout();
        }

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    /** 剧集 / 电影 / 直播：标题字图在上，元信息、角标、简介、进度、按钮依次排列。 */
    private void buildStandardLayout() {
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setVisibility(View.GONE);
        // 宽度铺满可用列：FIT_START 会按高度 165 等比缩放，官方电影 logo 1000×165、
        // 剧集 logo 313×165，都正好是等比结果；用 wrap 会把 430×71 的原图纵向拉高。
        content.addView(logo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(LOGO_HEIGHT)));

        titleText = text("", 76, C_TEXT, true);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleText.setVisibility(View.GONE);
        content.addView(titleText);

        subtitleText = text("", 44, C_SUB, false);
        subtitleText.setVisibility(View.GONE);

        metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.TOP);
        content.addView(metaRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                px(META_HEIGHT), 0, px(44)));

        badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(badgeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(49)));

        overview = buildOverview();
        if (isMovie()) {
            // 官方电影页 profile_view 固定在文字下方留 16 内边距
            overview.setPadding(0, 0, 0, px(16));
        }
        LinearLayout.LayoutParams ovp = new LinearLayout.LayoutParams(
                px(984), ViewGroup.LayoutParams.WRAP_CONTENT);
        ovp.topMargin = px(24);
        content.addView(overview, ovp);

        buildProgressRow();
        content.addView(progressRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                px(36), 0, px(isMovie() ? 26 : 16)));

        buttonsRow = buildButtonsRow();
        content.addView(buttonsRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                px(BTN_HEIGHT), 0, px(isMovie() ? 16 : 44)));
    }

    /** 季详情：左海报 + 右信息列（剧名/季名/评分/简介/按钮）。 */
    private void buildSeasonLayout() {
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setVisibility(View.GONE);
        content.addView(logo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(LOGO_HEIGHT)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);

        posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final RoundedClipLayout clip = new RoundedClipLayout(this, px(10));
        clip.addView(posterView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(clip, new LinearLayout.LayoutParams(px(SEASON_POSTER_W), px(SEASON_POSTER_H)));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = px(60);
        row.addView(column, cp);

        titleText = text("", 76, C_TEXT, false);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        // 官方季页 tv_title 高 98（181 实测），固定高度保证下方各行纵向对齐
        column.addView(titleText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(98)));

        subtitleText = text("", 44, C_TEXT, false);
        subtitleText.setSingleLine(true);
        subtitleText.setGravity(Gravity.CENTER_VERTICAL);
        subtitleText.setVisibility(View.GONE);
        column.addView(subtitleText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                px(57), 0, px(8)));

        metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.TOP);
        column.addView(metaRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                px(META_HEIGHT), 0, px(8)));

        overview = buildOverview();
        overview.setPadding(0, 0, 0, px(16));
        column.addView(overview, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(24)));

        buttonsRow = buildButtonsRow();
        column.addView(buttonsRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                px(BTN_HEIGHT), 0, px(28)));

        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView buildOverview() {
        TextView v = text("", 36, 0xFFD0D0D0, false);
        v.setMaxLines(4);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setLineSpacing(px(8), 1f);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                final String full = item.overview != null && item.overview.length() > 0
                        ? item.overview : "暂无简介";
                new AlertDialog.Builder(DetailActivity.this)
                        .setTitle(item.displayTitle())
                        .setMessage(full)
                        .setPositiveButton("关闭", null)
                        .show();
            }
        });
        return v;
    }

    private void buildProgressRow() {
        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setVisibility(View.GONE);

        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(rounded(0x33FFFFFF, px(3)));
        progressFill = new View(this);
        progressFill.setBackground(rounded(C_ACCENT, px(3)));
        bar.addView(progressFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        progressRow.addView(bar, new LinearLayout.LayoutParams(px(400), px(6)));

        progressLabel = text("", 28, C_SUB, false);
        progressRow.addView(progressLabel, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, px(8), 0));
    }

    // ---------------- 按钮 ----------------

    private LinearLayout buildButtonsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        playPill = new LinearLayout(this);
        playPill.setOrientation(LinearLayout.HORIZONTAL);
        playPill.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        playPill.setFocusable(true);
        playPill.setClickable(true);
        playPill.setPadding(px(36), 0, px(36), 0);
        // 官方按钮内容左对齐；电影「播放」宽 228，剧集/季「第 N 集」宽 270
        playPill.setMinimumWidth(px(isMovie() ? 228 : 270));
        playPill.setBackground(stroked(0x00000000, px(2), px(PILL_RADIUS)));

        playIcon = new ImageView(this);
        playIcon.setImageResource(R.drawable.ic_play);
        playPill.addView(playIcon, new LinearLayout.LayoutParams(px(36), px(36)));

        playLabelView = text("", 40, C_TEXT, true);
        playPill.addView(playLabelView, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, px(14), 0));

        playPill.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                refreshPill(hasFocus);
                if (hasFocus) {
                    setGridFocused(false);
                }
            }
        });
        playPill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playMain();
            }
        });
        row.addView(playPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(BTN_HEIGHT)));

        if (!isLive() && !isSeason()) {
            collectButton = circleButton(R.drawable.ic_favorite, C_HEART, true);
            collectButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toggleFavorite();
                }
            });
            row.addView(collectButton, lp(px(BTN_HEIGHT), px(BTN_HEIGHT), px(15), 0));
        }

        if (!isLive()) {
            watchButton = circleButton(R.drawable.ic_watched, C_TEXT, false);
            watchButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toggleWatched();
                }
            });
            row.addView(watchButton, lp(px(BTN_HEIGHT), px(BTN_HEIGHT),
                    px(isSeason() ? 15 : 28), 0));
        }
        return row;
    }

    private FrameLayout circleButton(int iconRes, final int tint, final boolean collect) {
        final FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(rounded(0x2EFFFFFF, px(40)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(tint);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(px(60), px(60));
        ip.gravity = Gravity.CENTER;
        c.addView(icon, ip);
        if (collect) {
            collectIcon = icon;
        } else {
            watchIcon = icon;
        }
        c.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                c.setBackground(rounded(hasFocus ? 0xFFFFFFFF : 0x2EFFFFFF, px(40)));
                if (collect) {
                    refreshCollectIcon(hasFocus);
                } else {
                    refreshWatchIcon(hasFocus);
                }
                if (hasFocus) {
                    setGridFocused(false);
                }
            }
        });
        return c;
    }

    private void refreshPill(boolean focused) {
        // 官方未聚焦是白描边透明底，聚焦为白底深色内容
        playPill.setBackground(focused
                ? rounded(0xFFFFFFFF, px(PILL_RADIUS))
                : stroked(0x00000000, px(2), px(PILL_RADIUS)));
        playLabelView.setTextColor(focused ? C_DARK_TEXT : C_TEXT);
        playIcon.setColorFilter(focused ? C_DARK_TEXT : C_TEXT);
    }

    private void refreshCollectIcon(boolean focused) {
        if (collectIcon == null) {
            return;
        }
        collectIcon.setImageResource(item.favorite
                ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        collectIcon.setColorFilter(item.favorite ? C_HEART : (focused ? C_DARK_TEXT : C_TEXT));
    }

    /** 已看/未看用 ic_watched 与 ic_watched_filled 两个图标区分，着色只跟随焦点，不再靠颜色表状态。 */
    private void refreshWatchIcon(boolean focused) {
        if (watchIcon == null) {
            return;
        }
        watchIcon.setImageResource(item.watched
                ? R.drawable.ic_watched_filled : R.drawable.ic_watched);
        watchIcon.setColorFilter(focused ? C_DARK_TEXT : C_TEXT);
    }

    private void updatePlayLabel() {
        String label;
        if (isLive()) {
            label = "播放直播";
        } else if (isSeason() || isTv()) {
            label = currentEpNumber > 0 ? getString(R.string.episode_label, currentEpNumber) : "播放";
        } else {
            label = "播放";
        }
        playLabelView.setText(label);
    }

    // ---------------- 头部内容 ----------------

    private void applyHeader() {
        if (isSeason()) {
            String show = item.showTitle.length() > 0 ? item.showTitle : item.displayTitle();
            boolean hasLogo = item.logoPath != null && item.logoPath.length() > 0;
            logo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
            titleText.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
            titleText.setText(show);
            String seasonName = item.title != null && !item.title.equals(show) ? item.title : "";
            subtitleText.setText(seasonName);
            subtitleText.setVisibility(seasonName.length() > 0 ? View.VISIBLE : View.GONE);
        } else {
            boolean hasLogo = item.logoPath != null && item.logoPath.length() > 0;
            logo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
            titleText.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
            titleText.setText(item.displayTitle());
        }
        renderMeta();
        renderBadges();
        overview.setText(item.overview != null && item.overview.length() > 0
                ? item.overview : "暂无简介");
        renderProgress();
        refreshCollectIcon(collectButton != null && collectButton.hasFocus());
        refreshWatchIcon(watchButton != null && watchButton.hasFocus());
        updatePlayLabel();
    }

    private void renderMeta() {
        metaRow.removeAllViews();
        boolean first = true;
        if (!isTv() && item.voteAverage > 0) {
            TextView score = text(String.format(Locale.US, "%.1f", item.voteAverage), 36, C_GOLD, true);
            metaRow.addView(score);
            TextView fen = text("分", 36, C_GOLD, false);
            metaRow.addView(fen, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, px(2), 0));
            first = false;
        }
        if (isSeason()) {
            addMetaSeparator(first);
            first = addMetaText(first, yearText(), C_TEXT);
            return;
        }
        if (item.contentRating != null && item.contentRating.length() > 0) {
            addMetaSeparator(first);
            first = addMetaText(first, item.contentRating, C_TEXT);
        }
        if (yearText().length() > 0) {
            addMetaSeparator(first);
            first = addMetaText(first, yearText(), C_TEXT);
        }
        if (!item.countries.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < item.countries.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(item.countries.get(i));
            }
            addMetaSeparator(first);
            first = addMetaText(first, sb.toString(), C_TEXT);
        }
        if (!item.genres.isEmpty()) {
            addMetaSeparator(first);
            int shown = Math.min(4, item.genres.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    metaRow.addView(text("·", 36, C_MUTED, false), lp(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            px(16), 0));
                }
                metaRow.addView(text(item.genres.get(i), 36, C_TEXT, false), lp(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        px(16), 0));
            }
            first = false;
            if (item.genres.size() > shown) {
                // 官方流派列表尾部是「· ...」，不是字段间的「/」
                metaRow.addView(text("·", 36, C_MUTED, false), lp(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        px(16), 0));
                metaRow.addView(text("...", 36, C_SUB, false), lp(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        px(16), 0));
            }
        }
    }

    private String yearText() {
        if (item.year != null && item.year.length() >= 4) {
            return item.year.substring(0, 4);
        }
        return "";
    }

    private boolean addMetaSeparator(boolean first) {
        if (!first) {
            metaRow.addView(text("/", 36, C_MUTED, false), lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    px(16), 0));
        }
        return false;
    }

    private boolean addMetaText(boolean first, String value, int color) {
        metaRow.addView(text(value, 36, color, false), lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                first ? 0 : px(16), 0));
        return false;
    }

    private void renderBadges() {
        if (badgeRow == null) {
            // 季详情版式没有库/角标行（官方季页同样不显示）
            return;
        }
        badgeRow.removeAllViews();
        badgeRow.setVisibility(isSeason() ? View.GONE : View.VISIBLE);
        if (isSeason()) {
            return;
        }
        // 官方 181 实测：剧集/季详情不显示清晰度、色域、音频角标，只剩媒体库行；
        // 电影详情才显示 4K/SDR/Dolby Atmos 并与库名同一行。
        if (isMovie()) {
            List<String> resolutions = QualityBadges.from(item.resolutions);
            for (int i = 0; i < resolutions.size(); i++) {
                addBadge(resolutions.get(i), QualityBadges.inverted(resolutions.get(i)), i == 0);
            }
            for (int i = 0; i < item.colorRanges.size() && i < 1; i++) {
                addBadge(item.colorRanges.get(i), false, false);
            }
            for (int i = 0; i < item.audioTypes.size() && i < 2; i++) {
                addAudioBadge(item.audioTypes.get(i), false);
            }
        }
        if (item.libraryName != null && item.libraryName.length() > 0) {
            if (isMovie() && badgeRow.getChildCount() > 0) {
                badgeRow.addView(text("/", 32, C_MUTED, false), lp(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        px(16), 0));
            }
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_library);
            icon.setColorFilter(0xCCFFFFFF);
            badgeRow.addView(icon, lp(px(32), px(32),
                    badgeRow.getChildCount() == 0 ? 0 : px(12), 0));
            badgeRow.addView(text(item.libraryName, 36, C_TEXT, false), lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    px(12), 0));
        }
    }

    /** 音频角标：官方把 Dolby Atmos 画成两行小字 logo（约 80 宽），其余值仍是普通描边角标。 */
    private void addAudioBadge(String raw, boolean first) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.US);
        if (s.indexOf("dolby") >= 0 || s.indexOf("atmos") >= 0) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView top = text("Dolby", 20, C_TEXT, true);
            top.setGravity(Gravity.CENTER);
            col.addView(top);
            TextView bottom = text("ATMOS", 11, C_TEXT, false);
            bottom.setGravity(Gravity.CENTER);
            col.addView(bottom);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(80), px(49));
            if (!first) {
                p.leftMargin = px(16);
            }
            badgeRow.addView(col, p);
            return;
        }
        addBadge(raw, false, first);
    }

    private void addBadge(String label, boolean inverted, boolean first) {
        TextView b = text(label, 22, inverted ? C_DARK_TEXT : C_TEXT, inverted);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(8), 0, px(8), 0);
        if (inverted) {
            b.setBackground(rounded(0xFFFFFFFF, px(4)));
        } else {
            b.setBackground(stroked(0x33000000, px(1), px(4)));
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
        if (!first) {
            p.leftMargin = px(16);
        }
        badgeRow.addView(b, p);
    }

    private void renderProgress() {
        if (progressRow == null) {
            return;
        }
        int runtimeSeconds = item.runtimeMinutes * 60;
        if (!isMovie() || item.watchedSeconds <= 0 || runtimeSeconds <= 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        progressRow.setVisibility(View.VISIBLE);
        int remaining = Math.max(0, runtimeSeconds - item.watchedSeconds);
        progressLabel.setText("剩余 " + durationText(remaining));
        float fraction = Math.min(1f, item.watchedSeconds / (float) runtimeSeconds);
        ViewGroup.LayoutParams p = progressFill.getLayoutParams();
        p.width = Math.round(px(400) * fraction);
        progressFill.setLayoutParams(p);
    }

    private static String durationText(int seconds) {
        int hours = seconds / 3600;
        int minutes = Math.max(1, (seconds % 3600) / 60);
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        return minutes + " 分钟";
    }

    // ---------------- 图片 ----------------

    private void loadImages() {
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (isSeason()) {
            if (item.posterPath != null && item.posterPath.length() > 0) {
                imageLoader.load(api.imageUrl(cfg, item.posterPath, 500), posterView,
                        api.authorizationHeader(cfg));
            }
            if (item.backdropPath != null && item.backdropPath.length() > 0) {
                imageLoader.load(api.imageUrl(cfg, item.backdropPath, 1600), cover,
                        api.authorizationHeader(cfg));
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xF7000000, 0xD2000000, 0x99000000, 0x4D000000});
                blurScrim.setBackground(g);
                blurScrim.setVisibility(View.VISIBLE);
            } else if (item.posterPath != null && item.posterPath.length() > 0) {
                // 官方季详情背景是同一张竖版海报低清放大，但蒙层更重（实测约 0xDD）。
                blurScrim.setBackgroundColor(0xDD000000);
                blurScrim.setVisibility(View.VISIBLE);
                imageLoader.load(api.imageUrl(cfg, item.posterPath, 800), cover,
                        api.authorizationHeader(cfg));
            }
            if (item.logoPath != null && item.logoPath.length() > 0) {
                imageLoader.load(api.imageUrl(cfg, item.logoPath, 0), logo,
                        api.authorizationHeader(cfg));
            }
            return;
        }
        if (item.backdropPath != null && item.backdropPath.length() > 0) {
            // 官方背景用原始 backdrop（清晰）+ 左深右浅的横向渐变蒙层
            imageLoader.load(api.imageUrl(cfg, item.backdropPath, 1600), cover, api.authorizationHeader(cfg));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xF7000000, 0xD2000000, 0x99000000, 0x4D000000});
            blurScrim.setBackground(g);
            blurScrim.setVisibility(View.VISIBLE);
        } else if (item.posterPath != null && item.posterPath.length() > 0) {
            blurScrim.setBackgroundColor(0xB3000000);
            blurScrim.setVisibility(View.VISIBLE);
            imageLoader.load(api.imageUrl(cfg, item.posterPath, 800), cover, api.authorizationHeader(cfg));
        }
        if (item.logoPath != null && item.logoPath.length() > 0) {
            // 带 w 参数会被转成无 alpha 的 webp（黑底），logo 必须取原图 PNG
            imageLoader.load(api.imageUrl(cfg, item.logoPath, 0), logo, api.authorizationHeader(cfg));
        }
    }

    // ---------------- 详情补齐 ----------------

    private void loadDetail() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    api.loadDetail(cfg, item);
                } catch (Throwable t) {
                    L.w("DetailActivity", "详情补齐失败，沿用列表快照：" + t.getMessage());
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        applyHeader();
                        loadImages();
                        loadContent();
                    }
                });
            }
        }, "fntv-detail").start();
    }

    private void loadContent() {
        if (isSeason()) {
            loadSeasonContent();
        } else if (isTv()) {
            loadTvContent();
        } else if (isMovie()) {
            loadMovieContent();
        } else {
            updatePlayLabel();
        }
    }

    // ---------------- 剧集：季卡片 ----------------

    private void loadTvContent() {
        body.removeAllViews();
        final TextView label = text("", 40, C_TEXT, false);
        body.addView(label, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(32)));
        label.setText(item.seasonCount > 0
                ? getString(R.string.seasons_label, item.seasonCount) : "");

        new Thread(new Runnable() {
            public void run() {
                List<MediaItem> list = new ArrayList<MediaItem>();
                int continueEp = 0;
                MediaItem playable = null;
                try {
                    list = api.listSeasons(cfg, item.guid);
                    MediaItem continueItem = continueEpisodeForShow(list);
                    if (continueItem != null) {
                        for (int i = 0; i < list.size() && playable == null; i++) {
                            MediaItem season = list.get(i);
                            boolean sameSeason = continueItem.seasonGuid.length() > 0
                                    && continueItem.seasonGuid.equals(season.guid);
                            if (!sameSeason && continueItem.seasonCount > 0) {
                                sameSeason = continueItem.seasonCount == season.seasonCount;
                            }
                            if (!sameSeason) {
                                continue;
                            }
                            List<MediaItem> eps = api.listEpisodes(cfg, season.guid);
                            for (int k = 0; k < eps.size(); k++) {
                                MediaItem ep = eps.get(k);
                                boolean sameEpisode = continueItem.guid.length() > 0
                                        && continueItem.guid.equals(ep.guid);
                                if (sameEpisode || ep.episodeCount == continueItem.episodeCount) {
                                    playable = ep;
                                    continueEp = ep.episodeCount;
                                    break;
                                }
                            }
                        }
                    }
                    if (playable == null && !list.isEmpty()) {
                        List<MediaItem> eps = api.listEpisodes(cfg, list.get(0).guid);
                        if (!eps.isEmpty()) {
                            playable = eps.get(0);
                            continueEp = playable.episodeCount;
                        }
                    }
                } catch (Throwable t) {
                    L.w("DetailActivity", "季列表加载失败：" + t.getMessage());
                }
                final List<MediaItem> seasonsFinal = list;
                final MediaItem playableFinal = playable;
                final int epFinal = continueEp;
                runOnUiThread(new Runnable() {
                    public void run() {
                        seasons = seasonsFinal;
                        currentEpisode = playableFinal;
                        currentEpNumber = epFinal;
                        renderSeasonGrid();
                        updatePlayLabel();
                    }
                });
            }
        }, "fntv-seasons").start();
    }

    /** 继续观看记录必须同时匹配剧名和所属季，避免跨季同集号被误选。 */
    private MediaItem continueEpisodeForShow(List<MediaItem> seasonList) {
        try {
            List<MediaItem> list = api.listContinue(cfg);
            for (int i = 0; i < list.size(); i++) {
                MediaItem c = list.get(i);
                boolean sameShow = c.title != null && (c.title.equalsIgnoreCase(item.title)
                        || (item.showTitle.length() > 0 && c.title.equalsIgnoreCase(item.showTitle)));
                if (!sameShow) {
                    continue;
                }
                for (int k = 0; k < seasonList.size(); k++) {
                    MediaItem season = seasonList.get(k);
                    if ((c.seasonGuid.length() > 0 && c.seasonGuid.equals(season.guid))
                            || (c.seasonCount > 0 && c.seasonCount == season.seasonCount)) {
                        return c;
                    }
                }
            }
        } catch (Throwable t) {
            L.w("DetailActivity", "继续观看读取失败：" + t.getMessage());
        }
        return null;
    }

    private void renderSeasonGrid() {
        body.removeAllViews();
        TextView label = text(item.seasonCount > 0
                ? getString(R.string.seasons_label, item.seasonCount) : "", 32, C_TEXT, false);
        body.addView(label, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(32)));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        body.addView(grid, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(32)));

        LinearLayout row = null;
        for (int i = 0; i < seasons.size(); i++) {
            if (i % CARD_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, i == 0 ? 0 : px(28)));
            }
            View card = buildSeasonCard(seasons.get(i), i == 0);
            row.addView(card, lp(px(CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT,
                    i % CARD_COLUMNS == 0 ? 0 : px(CARD_GAP), 0));
        }
        appendCastSection();
    }

    private View buildSeasonCard(final MediaItem season, final boolean first) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);
        card.setClipToPadding(false);

        RoundedClipLayout clip = new RoundedClipLayout(this, px(8));
        clip.setClipChildren(false);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clip.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (season.posterPath != null && season.posterPath.length() > 0) {
            imageLoader.load(api.imageUrl(cfg, season.posterPath, 400), poster, api.authorizationHeader(cfg));
        }
        card.addView(clip, new LinearLayout.LayoutParams(px(CARD_W), px(CARD_POSTER_H)));

        if (season.voteAverage > 0) {
            // 官方评分是左上角一块深色圆角底 + 金色数字（约 62×44，内缩 10）。
            TextView score = text(String.format(Locale.US, "%.1f", season.voteAverage), 36, C_GOLD, true);
            score.setGravity(Gravity.CENTER);
            score.setBackground(rounded(0xC0141414, px(6)));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(px(62), px(44));
            sp.leftMargin = px(10);
            sp.topMargin = px(10);
            clip.addView(score, sp);
        }
        String quality = qualityLabel(season);
        if (quality.length() > 0) {
            TextView badge = text(quality, 20, C_TEXT, false);
            badge.setBackground(stroked(0x66000000, px(1), px(3)));
            badge.setPadding(px(8), 0, px(8), 0);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
            bp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
            bp.rightMargin = px(12);
            bp.bottomMargin = px(12);
            clip.addView(badge, bp);
        }

        final View border = new View(this);
        border.setBackground(stroked(0x00000000, px(3), px(10)));
        border.setVisibility(View.GONE);
        clip.addView(border, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String title = season.seasonCount > 0
                ? getString(R.string.season_label, season.seasonCount)
                : (season.title == null ? "" : season.title);
        card.addView(text(title, 30, C_TEXT, false), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(13)));
        card.addView(text(seasonSubtitle(season), 26, C_SUB, false), lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, px(6)));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                animateCard(card, hasFocus, 1.05f);
                border.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
                setGridFocused(hasFocus);
                if (hasFocus) {
                    ensureVisible(card);
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openDetail(season);
            }
        });
        return card;
    }

    private String seasonSubtitle(MediaItem season) {
        StringBuilder sb = new StringBuilder();
        if (season.episodeCount > 0) {
            sb.append(getString(R.string.episodes_label, season.episodeCount));
        }
        if (season.year != null && season.year.length() >= 4) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(season.year.substring(0, 4));
        }
        return sb.toString();
    }

    private String qualityLabel(MediaItem m) {
        List<String> labels = QualityBadges.from(m.resolutions);
        if (!labels.isEmpty()) {
            return labels.get(0);
        }
        if (m.height >= 2160) {
            return "4K";
        }
        if (m.height >= 1080) {
            return "1080";
        }
        if (m.height >= 720) {
            return "720";
        }
        return "";
    }

    // ---------------- 季：选集 ----------------

    private void loadSeasonContent() {
        body.removeAllViews();
        new Thread(new Runnable() {
            public void run() {
                List<MediaItem> eps = new ArrayList<MediaItem>();
                int cur = 0;
                try {
                    eps = api.listEpisodes(cfg, item.guid);
                    List<MediaItem> cont = api.listContinue(cfg);
                    for (int i = 0; i < cont.size(); i++) {
                        if (item.guid.equals(cont.get(i).seasonGuid)) {
                            cur = cont.get(i).episodeCount;
                            break;
                        }
                    }
                } catch (Throwable t) {
                    L.w("DetailActivity", "集列表加载失败：" + t.getMessage());
                }
                MediaItem playable = null;
                for (int i = 0; i < eps.size(); i++) {
                    if (eps.get(i).episodeCount == cur) {
                        playable = eps.get(i);
                        break;
                    }
                }
                if (playable == null && !eps.isEmpty()) {
                    playable = eps.get(0);
                    cur = playable.episodeCount;
                }
                final List<MediaItem> epsFinal = eps;
                final MediaItem playableFinal = playable;
                final int curFinal = cur;
                runOnUiThread(new Runnable() {
                    public void run() {
                        episodes = epsFinal;
                        currentEpisode = playableFinal;
                        currentEpNumber = curFinal;
                        renderSeasonBody();
                        updatePlayLabel();
                    }
                });
            }
        }, "fntv-episodes").start();
    }

    private void renderSeasonBody() {
        body.removeAllViews();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("选集", 36, C_TEXT, false);
        header.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(buildViewSwitcher(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(72)));
        body.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(32)));

        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        body.addView(area, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(28)));
        if (episodeViewMode == 0) {
            renderEpisodeNumbers(area);
        } else {
            renderEpisodeCards(area);
        }
        appendCastSection();
    }

    private View buildViewSwitcher() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        // 官方是 124×72 的圆角分段开关：外框 1px 细边，选中段 0x3D 白底。
        wrap.setPadding(px(4), px(4), px(4), px(4));
        wrap.setBackground(outlined(0x1A000000, 0x33FFFFFF, px(1), px(12)));

        final FrameLayout cardIcon = switcherIcon(R.drawable.ic_view_monitor, episodeViewMode == 1);
        final FrameLayout listIcon = switcherIcon(R.drawable.ic_view_grid, episodeViewMode == 0);
        cardIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (episodeViewMode != 1) {
                    episodeViewMode = 1;
                    renderSeasonBody();
                }
            }
        });
        listIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (episodeViewMode != 0) {
                    episodeViewMode = 0;
                    renderSeasonBody();
                }
            }
        });
        wrap.addView(cardIcon, new LinearLayout.LayoutParams(px(58), px(59)));
        wrap.addView(listIcon, new LinearLayout.LayoutParams(px(58), px(59)));
        return wrap;
    }

    private FrameLayout switcherIcon(int iconRes, final boolean selected) {
        final FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(rounded(selected ? 0x3DFFFFFF : 0x00000000, px(10)));
        final ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(selected ? C_TEXT : 0xA6FFFFFF);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(px(38), px(38));
        p.gravity = Gravity.CENTER;
        box.addView(icon, p);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                box.setBackground(rounded(hasFocus ? 0xFFFFFFFF
                        : (selected ? 0x3DFFFFFF : 0x00000000), px(10)));
                icon.setColorFilter(hasFocus ? C_DARK_TEXT : (selected ? C_TEXT : 0xA6FFFFFF));
                if (hasFocus) {
                    setGridFocused(false);
                }
            }
        });
        return box;
    }

    private void renderEpisodeNumbers(LinearLayout area) {
        int perRow = 15;
        LinearLayout row = null;
        for (int i = 0; i < episodes.size(); i++) {
            final MediaItem ep = episodes.get(i);
            int number = ep.episodeCount > 0 ? ep.episodeCount : i + 1;
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                area.addView(row, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, i == 0 ? 0 : px(EP_BTN_GAP)));
            }
            final TextView b = text(String.valueOf(number), 30,
                    number == currentEpNumber ? C_ACCENT : C_TEXT, false);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setBackground(rounded(C_EP_BG, px(6)));
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    b.setBackground(rounded(hasFocus ? 0xFFFFFFFF : C_EP_BG, px(6)));
                    b.setTextColor(hasFocus ? C_DARK_TEXT
                            : (ep.episodeCount == currentEpNumber ? C_ACCENT : C_TEXT));
                    setGridFocused(hasFocus);
                    if (hasFocus) {
                        ensureVisible(b);
                    }
                }
            });
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openEpisode(ep);
                }
            });
            row.addView(b, lp(px(EP_BTN_W), px(EP_BTN_H), i % perRow == 0 ? 0 : px(EP_BTN_GAP), 0));
        }
    }

    private void renderEpisodeCards(LinearLayout area) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        area.addView(hs, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);

        for (int i = 0; i < episodes.size(); i++) {
            row.addView(buildEpisodeCard(episodes.get(i), i),
                    lp(px(EP_CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT,
                            i == 0 ? 0 : px(EP_CARD_GAP), 0));
        }
    }

    private View buildEpisodeCard(final MediaItem ep, int index) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);

        RoundedClipLayout clip = new RoundedClipLayout(this, px(10));
        ImageView still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clip.addView(still, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (ep.posterPath != null && ep.posterPath.length() > 0) {
            imageLoader.load(api.imageUrl(cfg, ep.posterPath, 600), still, api.authorizationHeader(cfg));
        }
        card.addView(clip, new LinearLayout.LayoutParams(px(EP_CARD_W), px(EP_CARD_IMG_H)));

        String quality = qualityLabel(ep);
        if (quality.length() > 0) {
            TextView badge = text(quality, 20, C_TEXT, false);
            badge.setBackground(stroked(0x66000000, px(1), px(3)));
            badge.setPadding(px(8), 0, px(8), 0);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
            bp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
            bp.rightMargin = px(12);
            bp.bottomMargin = px(12);
            clip.addView(badge, bp);
        }
        if (ep.progressPercent > 0) {
            FrameLayout bar = new FrameLayout(this);
            bar.setBackground(rounded(0x66000000, px(3)));
            View fill = new View(this);
            fill.setBackground(rounded(C_ACCENT, px(3)));
            int fillWidth = Math.round(px(EP_CARD_W - 32) * Math.min(100, ep.progressPercent) / 100f);
            bar.addView(fill, new FrameLayout.LayoutParams(fillWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(6));
            bp.gravity = Gravity.BOTTOM;
            bp.leftMargin = px(16);
            bp.rightMargin = px(16);
            bp.bottomMargin = px(12);
            clip.addView(bar, bp);
        }

        final View border = new View(this);
        border.setBackground(stroked(0x00000000, px(3), px(12)));
        border.setVisibility(View.GONE);
        clip.addView(border, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int number = ep.episodeCount > 0 ? ep.episodeCount : index + 1;
        String name = ep.subtitle != null && ep.subtitle.length() > 0 ? ep.subtitle
                : (ep.title != null && !ep.title.equals(item.title) ? ep.title : "");
        String title = name.length() > 0 ? number + "." + name : "第 " + number + " 集";
        card.addView(text(title, 32, C_TEXT, false), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(12)));
        if (ep.overview != null && ep.overview.length() > 0) {
            TextView desc = text(ep.overview, 26, C_SUB, false);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(desc, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(6)));
        }

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                animateCard(card, hasFocus, 1.05f);
                border.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
                setGridFocused(hasFocus);
                if (hasFocus) {
                    ensureVisible(card);
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openEpisode(ep);
            }
        });
        return card;
    }

    // ---------------- 电影：字幕 / 音频 ----------------

    private void loadMovieContent() {
        body.removeAllViews();
        selectorRow = new LinearLayout(this);
        selectorRow.setOrientation(LinearLayout.HORIZONTAL);
        selectorRow.setVisibility(View.GONE);
        body.addView(selectorRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(52)));

        final LinearLayout subtitleBlock = buildDropdown("字幕", true);
        selectorRow.addView(subtitleBlock, lp(px(DROPDOWN_W),
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));
        final LinearLayout audioBlock = buildDropdown("音频", false);
        selectorRow.addView(audioBlock, lp(px(DROPDOWN_W),
                ViewGroup.LayoutParams.WRAP_CONTENT, px(80), 0));

        new Thread(new Runnable() {
            public void run() {
                List<SubtitleTrack> subs = new ArrayList<SubtitleTrack>();
                List<AudioTrack> audios = new ArrayList<AudioTrack>();
                try {
                    subs = api.listSubtitles(cfg, item);
                } catch (Throwable t) {
                    L.w("DetailActivity", "字幕轨读取失败：" + t.getMessage());
                }
                try {
                    audios = api.listAudioTracks(cfg, item);
                } catch (Throwable t) {
                    L.w("DetailActivity", "音频轨读取失败：" + t.getMessage());
                }
                final List<SubtitleTrack> subsFinal = subs;
                final List<AudioTrack> audiosFinal = audios;
                runOnUiThread(new Runnable() {
                    public void run() {
                        subTracks = subsFinal;
                        audioTracks = audiosFinal;
                        subtitleBlock.setVisibility(subTracks.isEmpty() ? View.GONE : View.VISIBLE);
                        audioBlock.setVisibility(audioTracks.isEmpty() ? View.GONE : View.VISIBLE);
                        selectorRow.setVisibility(subTracks.isEmpty() && audioTracks.isEmpty()
                                ? View.GONE : View.VISIBLE);
                        refreshSubtitleValue();
                        refreshAudioValue();
                        if (!subTracks.isEmpty()) {
                            playPill.setNextFocusDownId(subtitleBlock.getId());
                        } else if (!audioTracks.isEmpty()) {
                            playPill.setNextFocusDownId(audioBlock.getId());
                        }
                        appendCastSection();
                    }
                });
            }
        }, "fntv-tracks").start();
    }

    private LinearLayout buildDropdown(String labelText, final boolean subtitle) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setId(subtitle ? R.id.detail_subtitle_dropdown : R.id.detail_audio_dropdown);
        block.addView(text(labelText, 36, C_SUB, false));

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setPadding(px(24), 0, px(24), 0);
        box.setBackground(rounded(0x26FFFFFF, px(8)));
        final TextView value = text(subtitle ? "关闭" : "", 36, C_TEXT, false);
        box.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final ChevronView chevron = new ChevronView(this, px(36), px(37), px(4));
        box.addView(chevron, new LinearLayout.LayoutParams(px(36), px(37)));
        if (subtitle) {
            subValue = value;
        } else {
            audioValue = value;
        }
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                box.setBackground(rounded(hasFocus ? 0xFFFFFFFF : 0x26FFFFFF, px(8)));
                value.setTextColor(hasFocus ? C_DARK_TEXT : C_TEXT);
                chevron.setStrokeColor(hasFocus ? C_DARK_TEXT : C_TEXT);
                if (hasFocus) {
                    setGridFocused(false);
                }
            }
        });
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showTrackDialog(subtitle);
            }
        });
        block.addView(box, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                px(DROPDOWN_H), 0, px(20)));
        return block;
    }

    private void refreshSubtitleValue() {
        if (subValue == null) {
            return;
        }
        SubtitleTrack chosen = null;
        for (int i = 0; i < subTracks.size(); i++) {
            SubtitleTrack t = subTracks.get(i);
            if (item.subtitleGuid.length() > 0 && item.subtitleGuid.equals(t.guid)) {
                chosen = t;
                break;
            }
        }
        if (chosen == null && item.subtitleGuid.length() == 0) {
            for (int i = 0; i < subTracks.size(); i++) {
                if (subTracks.get(i).isDefault) {
                    chosen = subTracks.get(i);
                    break;
                }
            }
        }
        if (chosen == null && item.subtitleGuid.length() == 0) {
            subValue.setText("关闭");
        } else if (chosen != null) {
            subValue.setText(chipName(chosen.title, chosen.language));
        } else {
            subValue.setText("关闭");
        }
    }

    private void refreshAudioValue() {
        if (audioValue == null) {
            return;
        }
        AudioTrack chosen = null;
        for (int i = 0; i < audioTracks.size(); i++) {
            AudioTrack t = audioTracks.get(i);
            if (item.audioGuid.length() > 0 && item.audioGuid.equals(t.guid)) {
                chosen = t;
                break;
            }
        }
        if (chosen == null) {
            for (int i = 0; i < audioTracks.size(); i++) {
                if (audioTracks.get(i).isDefault) {
                    chosen = audioTracks.get(i);
                    break;
                }
            }
        }
        if (chosen == null && !audioTracks.isEmpty()) {
            chosen = audioTracks.get(0);
        }
        audioValue.setText(chosen == null ? "" : chipName(chosen.title, chosen.language));
    }

    /** 下拉框收起态显示语言名：官方字幕轨标题「简体中文.SH」在收起态显示为「中文」。 */
    private static String chipName(String title, String language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.US);
        if (lang.equals("chi") || lang.equals("zho") || lang.equals("zh")) {
            return "中文";
        }
        if (lang.equals("jpn") || lang.equals("ja")) {
            return "日语";
        }
        if (lang.equals("eng") || lang.equals("en")) {
            return "英语";
        }
        return trackName(title, language);
    }

    private static String trackName(String title, String language) {
        if (title != null && title.length() > 0) {
            return title;
        }
        if ("chi".equals(language) || "zho".equals(language)) {
            return "中文";
        }
        if ("jpn".equals(language)) {
            return "日语";
        }
        if ("eng".equals(language)) {
            return "英语";
        }
        return language == null ? "" : language;
    }

    private void showTrackDialog(final boolean subtitle) {
        List<String> labels = new ArrayList<String>();
        if (subtitle) {
            labels.add("关闭");
            for (int i = 0; i < subTracks.size(); i++) {
                labels.add(trackName(subTracks.get(i).title, subTracks.get(i).language));
            }
        } else {
            for (int i = 0; i < audioTracks.size(); i++) {
                labels.add(trackName(audioTracks.get(i).title, audioTracks.get(i).language));
            }
        }
        if (labels.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(subtitle ? "字幕" : "音频")
                .setItems(labels.toArray(new CharSequence[labels.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (subtitle) {
                                    if (which == 0) {
                                        item.subtitleGuid = "";
                                    } else {
                                        item.subtitleGuid = subTracks.get(which - 1).guid;
                                    }
                                    refreshSubtitleValue();
                                } else {
                                    item.audioGuid = audioTracks.get(which).guid;
                                    refreshAudioValue();
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 状态与跳转 ----------------

    @Override
    protected void onResume() {
        super.onResume();
        refreshFavoriteFromServer();
        refreshWatchedFromServer();
        refreshCollectIcon(collectButton != null && collectButton.hasFocus());
        refreshWatchIcon(watchButton != null && watchButton.hasFocus());
    }

    private void refreshFavoriteFromServer() {
        if (isLive()) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    final boolean fav = api.isFavorite(cfg, item.guid);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (item.favorite != fav) {
                                item.favorite = fav;
                                refreshCollectIcon(collectButton != null && collectButton.hasFocus());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                    // 读回失败就保留列表快照，不打扰用户
                }
            }
        }, "fntv-favorite").start();
    }

    /** 网页端或其他设备改过已看状态后，回到详情页要看到最新值。 */
    private void refreshWatchedFromServer() {
        if (isLive()) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    final boolean seen = api.isWatched(cfg, item.guid);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (item.watched != seen) {
                                item.watched = seen;
                                watchedChanged = true;
                                refreshWatchIcon(watchButton != null && watchButton.hasFocus());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                    // 读回失败就保留列表快照，不打扰用户
                }
            }
        }, "fntv-watched-read").start();
    }

    private void toggleFavorite() {
        final boolean target = !item.favorite;
        collectButton.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    api.setFavorite(cfg, item.guid, target);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            item.favorite = target;
                            favoriteChanged = true;
                            collectButton.setEnabled(true);
                            refreshCollectIcon(collectButton.hasFocus());
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            collectButton.setEnabled(true);
                            Ui.toast(DetailActivity.this, "收藏失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "fntv-favorite-set").start();
    }

    private void toggleWatched() {
        final boolean target = !item.watched;
        watchButton.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    api.setWatched(cfg, item.guid, target);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            item.watched = target;
                            watchedChanged = true;
                            watchButton.setEnabled(true);
                            refreshWatchIcon(watchButton.hasFocus());
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            watchButton.setEnabled(true);
                            Ui.toast(DetailActivity.this, "标记失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "fntv-watched-set").start();
    }

    private void playMain() {
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < 500L) {
            return;
        }
        lastOpenAt = now;
        if (isSeason() || isTv()) {
            if (currentEpisode != null) {
                openPlayer(currentEpisode);
            } else {
                Ui.toast(this, "暂无可播放的集");
            }
            return;
        }
        openPlayer(item);
    }

    private void openEpisode(MediaItem ep) {
        currentEpisode = ep;
        if (ep.episodeCount > 0) {
            currentEpNumber = ep.episodeCount;
        }
        updatePlayLabel();
        openPlayer(ep);
    }

    private void openPlayer(MediaItem target) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("item", com.juying.medianest.core.util.Json.stringify(target.toJson()));
        startActivity(i);
    }

    private void openDetail(MediaItem target) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("item", com.juying.medianest.core.util.Json.stringify(target.toJson()));
        startActivity(i);
    }

    // ---------------- 演职人员（官方 item_actor 横向行） ----------------

    /**
     * 官方详情页底部「演职人员」：标题 32sp + 横向可滚动头像行。
     * item_actor 规格：200 宽、200×200 正圆头像（1px 白边）、姓名 28sp、角色 24sp。
     */
    private void appendCastSection() {
        if (isLive() || item == null || item.guid == null || item.guid.length() == 0) {
            return;
        }
        final LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.GONE);
        body.addView(section, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(56)));
        section.addView(text("演职人员", 32, 0xCCFFFFFF, false), lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        final String itemGuid = item.guid;
        new Thread(new Runnable() {
            public void run() {
                List<Person> people = new ArrayList<Person>();
                try {
                    people = api.listPersons(cfg, itemGuid);
                } catch (Throwable t) {
                    L.w("DetailActivity", "演职人员读取失败：" + t.getMessage());
                }
                final List<Person> peopleFinal = people;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (peopleFinal.isEmpty() || isFinishing()) {
                            return;
                        }
                        fillCastSection(section, peopleFinal);
                        section.setVisibility(View.VISIBLE);
                    }
                });
            }
        }, "fntv-persons").start();
    }

    private void fillCastSection(LinearLayout section, List<Person> people) {
        android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(24)));
        for (int i = 0; i < people.size(); i++) {
            row.addView(buildPersonCard(people.get(i)), lp(px(200),
                    ViewGroup.LayoutParams.WRAP_CONTENT, i == 0 ? 0 : px(24), 0));
        }
    }

    private View buildPersonCard(final Person p) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setFocusable(true);
        card.setClickable(true);

        final CircleClipImageView avatar = new CircleClipImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageResource(R.drawable.default_img_person);
        if (p.profilePath != null && p.profilePath.length() > 0) {
            imageLoader.load(api.imageUrl(cfg, p.profilePath, 200), avatar, api.authorizationHeader(cfg));
        }
        card.addView(avatar, new LinearLayout.LayoutParams(px(200), px(200)));

        TextView name = text(p.name == null ? "" : p.name, 28, C_TEXT, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, lp(px(200), ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(20)));

        TextView role = text(p.role == null ? "" : p.role, 24, 0xA6FFFFFF, false);
        role.setGravity(Gravity.CENTER);
        role.setSingleLine(true);
        role.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(role, lp(px(200), ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(4)));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                card.setBackground(hasFocus ? rounded(0x1AFFFFFF, px(12)) : null);
                avatar.setRingWidth(px(hasFocus ? 3 : 1));
                if (hasFocus) {
                    setGridFocused(false);
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PersonDetailActivity.start(DetailActivity.this, p);
            }
        });
        return card;
    }

    // ---------------- 焦点辅助 ----------------

    private void setGridFocused(boolean focused) {
        if (gridFocused == focused) {
            return;
        }
        gridFocused = focused;
        dim.animate().alpha(focused ? DIM_ALPHA : 0f).setDuration(180).start();
    }

    private void animateCard(View v, boolean focused, float scale) {
        v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f)
                .setDuration(150).start();
    }

    private void ensureVisible(View v) {
        v.post(new Runnable() {
            public void run() {
                View target = v;
                int[] a = new int[2];
                target.getLocationOnScreen(a);
                int[] b = new int[2];
                scroll.getLocationOnScreen(b);
                int sTop = b[1];
                int sBottom = b[1] + scroll.getHeight();
                int top = a[1];
                int bottom = top + target.getHeight();
                int delta = 0;
                if (bottom > sBottom - px(40)) {
                    delta = bottom - sBottom + px(140);
                } else if (top < sTop + px(100)) {
                    delta = top - sTop - px(140);
                }
                if (delta != 0) {
                    scroll.smoothScrollBy(0, delta);
                }
            }
        });
    }

    /** 下拉框右侧箭头：官方是 36×37、约 4px 圆头描边的折线，字符 ▾ 的线宽和尺寸都差太多。 */
    private static final class ChevronView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final float w;
        private final float h;
        private final float stroke;

        ChevronView(android.content.Context context, float width, float height, float stroke) {
            super(context);
            this.w = width;
            this.h = height;
            this.stroke = stroke;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setAntiAlias(true);
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        void setStrokeColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        protected void onDraw(Canvas canvas) {
            float left = stroke / 2f;
            float top = stroke / 2f;
            float bw = w - stroke;
            float bh = h - stroke;
            canvas.drawLine(left, top + bh * 0.28f, left + bw / 2f, top + bh * 0.72f, paint);
            canvas.drawLine(left + bw / 2f, top + bh * 0.72f, left + bw, top + bh * 0.28f, paint);
        }
    }
}
