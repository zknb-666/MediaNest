package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;
import com.juying.medianest.core.util.L;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.Toast;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 官方飞牛 TV 1.5.1「设置」页复刻：左侧「播放偏好 / 外观」，
 * 右侧按官方 1920×1080 实测坐标渲染两个面板（FN Connect 相关设置已按需求移除）。
 *
 * 焦点：左栏项聚焦时放大 1.04 变白色胶囊；选项行聚焦时变白、上下各扩 2px、文本左移。
 * 选项先写 SharedPreferences("official_settings") 作为本地缓存，再回写服务端：
 * 首选语言走 GET/POST /user/info 的 lan、audio_lan，卡片显示项走 /user/getData|setData 的
 * list:card:setting。进入页面时以服务端为准回读；服务端不可达则沿用本地值。
 */
public final class OfficialSettingsActivity extends Activity {

    private static final String PREFS = "official_settings";
    /** 设置变更后置位，首页 onResume 据此重建卡片（如「已观看」标记）。 */
    static boolean settingsChanged = false;
    private static final String KEY_SUBTITLE_LANG = "subtitle_lang";
    private static final String KEY_AUDIO_LANG = "audio_lang";
    private static final String KEY_SCORE = "score";
    private static final String KEY_WATCHED = "watched";
    private static final String KEY_RESOLUTION = "resolution";

    /** 账号级配置键：列表卡片上的评分/分辨率/已观看显示项，网页端设置页共用同一份数据。 */
    private static final String USER_DATA_LIST_CARD = "list:card:setting";
    /** 语言选项索引 0/1/2 对应的服务端取值；空串即「任意语言」。 */
    private static final String[] LANG_CODES = {"", "zh-CN", "en"};

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_CARD = 0xFF1E1F24;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ICON = 0xFFC9CACC;
    private static final int COLOR_MUTED = 0xFF8E8F92;
    private static final int COLOR_FOCUS_TEXT = 0xFF15171A;

    private float scale;
    private Typeface icons;
    private SharedPreferences prefs;
    private ConfigRepository store;
    private int nextViewId = 0x30000;

    /** 开关与它联动显示的行内标记，回读服务端后原地刷新。 */
    private final Map<String, ToggleView> switchViews = new HashMap<String, ToggleView>();
    private final Map<String, View[]> switchLinked = new HashMap<String, View[]>();
    /** 本次进入页面后被用户改过的键：回包晚到时不能覆盖这些刚改的值。 */
    private final Set<String> userTouched = new HashSet<String>();

    private final View[] panels = new View[2];
    private final View[] railItems = new View[2];
    private final TextView[] railIcons = new TextView[2];
    private final TextView[] railTitles = new TextView[2];

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        icons = Typeface.createFromAsset(getAssets(), "official_icons.ttf");
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        store = App.from(this).config();
        setContentView(build());
        selectTab(0);
        railItems[0].requestFocus();
        syncFromServer();
    }

    private int s(int designPx) {
        return Math.round(designPx * scale);
    }

    private float sf(int designPx) {
        return designPx * scale;
    }

    private int newId() {
        return nextViewId++;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        root.setClipChildren(false);

        TextView title = text("设置", 60, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = s(60);
        titleLp.topMargin = s(80);
        root.addView(title, titleLp);

        buildRail(root);

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.leftMargin = s(610);
        contentLp.rightMargin = s(80);
        root.addView(content, contentLp);

        panels[0] = buildPlaybackPanel();
        panels[1] = buildAppearancePanel();
        for (int i = 0; i < panels.length; i++) {
            content.addView(panels[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            panels[i].setVisibility(View.GONE);
        }
        return root;
    }

    private void buildRail(FrameLayout root) {
        String[] glyphs = {"\uEA85", "\uE8CD"};
        String[] labels = {"播放偏好", "外观"};
        int[] tops = {213, 313};
        for (int i = 0; i < 2; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(s(36), 0, s(36), 0);
            item.setFocusable(true);
            item.setClickable(true);
            item.setBackground(railBackground(false));

            TextView icon = text(glyphs[i], 52, COLOR_ICON);
            icon.setTypeface(icons);
            icon.setIncludeFontPadding(false);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(s(52), s(52)));

            TextView label = text(labels[i], 36, COLOR_TEXT);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    s(296), ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.leftMargin = s(20);
            label.setMaxLines(2);
            item.addView(label, labelLp);

            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(index);
                }
            });
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                    lp.leftMargin = s(hasFocus ? 51 : 60);
                    lp.topMargin = s(tops[index] + (hasFocus ? -2 : 0));
                    lp.width = s(hasFocus ? 458 : 440);
                    lp.height = s(hasFocus ? 84 : 80);
                    v.setLayoutParams(lp);
                    v.setBackground(railBackground(hasFocus));
                    railIcons[index].setTextColor(hasFocus ? COLOR_FOCUS_TEXT : COLOR_ICON);
                    railTitles[index].setTextColor(hasFocus ? COLOR_FOCUS_TEXT : COLOR_TEXT);
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(440), s(80));
            lp.leftMargin = s(60);
            lp.topMargin = s(tops[i]);
            root.addView(item, lp);

            railItems[i] = item;
            railIcons[i] = icon;
            railTitles[i] = label;
        }
    }

    private GradientDrawable railBackground(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : COLOR_CARD);
        bg.setCornerRadius(sf(8));
        return bg;
    }

    private void selectTab(int tab) {
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
        }
    }

    // ---------------- 播放偏好 ----------------

    private View buildPlaybackPanel() {
        return panel(new PanelBuilder() {
            void fill(FrameLayout p) {
                int subtitle = prefs.getInt(KEY_SUBTITLE_LANG, 1);
                int audio = prefs.getInt(KEY_AUDIO_LANG, 1);

                p.addView(heading("首选字幕语言"), at(0, 80));
                p.addView(description("优先下载及使用首选语言的字幕，播放时可切换至其他语言。"),
                        atFixed(0, 145, 1230));
                addLanguageRows(p, 207, KEY_SUBTITLE_LANG, subtitle);

                p.addView(heading("首选音频语言"), at(0, 607));
                p.addView(description("优先使用首选语言的音频，播放时可切换至其他语言。"),
                        atFixed(0, 672, 1230));
                addLanguageRows(p, 734, KEY_AUDIO_LANG, audio);
            }
        });
    }

    private void addLanguageRows(FrameLayout panel, int top, final String key, int selected) {
        String[] values = {"任意语言", "简体中文", "English"};
        for (int i = 0; i < values.length; i++) {
            final int value = i;
            View row = optionRow(values[i], selected == i, key, value);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.edit().putInt(key, value).commit();
                    refreshCheckRows((ViewGroup) v.getParent(), key);
                    userTouched.add(key);
                    syncLanguageToServer();
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(1230), s(100));
            lp.topMargin = s(top + i * 120);
            panel.addView(row, lp);
        }
    }

    private void refreshCheckRows(ViewGroup panel, String key) {
        int selected = prefs.getInt(key, 1);
        for (int i = 0; i < panel.getChildCount(); i++) {
            Object tag = panel.getChildAt(i).getTag();
            if (tag instanceof OptionTag) {
                OptionTag option = (OptionTag) tag;
                if (key.equals(option.key)) {
                    option.check.setVisibility(option.value == selected
                            ? View.VISIBLE : View.INVISIBLE);
                }
            }
        }
    }

    // ---------------- 服务端同步 ----------------

    /** 进入页面时回读服务端为准；任一接口不可达时该部分保留本地值。 */
    private void syncFromServer() {
        final ServerConfig cfg = currentConfig();
        if (cfg == null || "JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            return;
        }
        final MediaUseCases nas = App.from(this).useCases(cfg);
        new Thread(new Runnable() {
            public void run() {
                String lan = null;
                String audioLan = null;
                String card = null;
                boolean reachable = false;
                try {
                    Map<String, String> info = nas.fetchUserPrefs(cfg);
                    lan = info.get("lan");
                    audioLan = info.get("audio_lan");
                    reachable = true;
                } catch (FnException ex) {
                    L.w("OfficialSettings", "读取首选语言失败，沿用本地：" + ex.getMessage());
                }
                try {
                    card = nas.fetchUserData(cfg, USER_DATA_LIST_CARD);
                    reachable = true;
                } catch (FnException ex) {
                    L.w("OfficialSettings", "读取卡片显示配置失败，沿用本地：" + ex.getMessage());
                }
                final String fLan = lan;
                final String fAudioLan = audioLan;
                final String fCard = card;
                final boolean showOffline = !reachable;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        applyServerPrefs(fLan, fAudioLan, fCard);
                        if (showOffline) {
                            Toast.makeText(OfficialSettingsActivity.this,
                                    "无法连接服务器，已使用本地设置", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** 服务端值覆盖本地缓存并原地刷新控件；用户本次刚改过的键不覆盖（防止回包晚到冲掉操作）。 */
    private void applyServerPrefs(String lan, String audioLan, String cardJson) {
        boolean changed = false;
        boolean languageChanged = false;
        if (lan != null && !userTouched.contains(KEY_SUBTITLE_LANG)) {
            int index = langIndex(lan);
            if (prefs.getInt(KEY_SUBTITLE_LANG, 1) != index) {
                prefs.edit().putInt(KEY_SUBTITLE_LANG, index).commit();
                changed = true;
            }
            languageChanged = true;
        }
        if (audioLan != null && !userTouched.contains(KEY_AUDIO_LANG)) {
            int index = langIndex(audioLan);
            if (prefs.getInt(KEY_AUDIO_LANG, 1) != index) {
                prefs.edit().putInt(KEY_AUDIO_LANG, index).commit();
                changed = true;
            }
            languageChanged = true;
        }
        if (languageChanged) {
            refreshCheckRows((ViewGroup) panels[0], KEY_SUBTITLE_LANG);
            refreshCheckRows((ViewGroup) panels[0], KEY_AUDIO_LANG);
        }
        if (cardJson != null && cardJson.length() > 0) {
            Map<String, Object> card;
            try {
                card = Json.parseObject(cardJson);
            } catch (RuntimeException ex) {
                L.w("OfficialSettings", "服务端卡片配置不是合法 JSON：" + cardJson);
                card = null;
            }
            if (card != null) {
                changed |= applyToggle(KEY_SCORE,
                        Json.bool(card, "score", prefs.getBoolean(KEY_SCORE, true)));
                changed |= applyToggle(KEY_RESOLUTION,
                        Json.bool(card, "resolution", prefs.getBoolean(KEY_RESOLUTION, true)));
                changed |= applyToggle(KEY_WATCHED,
                        Json.bool(card, "watched", prefs.getBoolean(KEY_WATCHED, false)));
            }
        }
        if (changed) {
            settingsChanged = true;
        }
    }

    /** 把服务端开关值落到本地并刷新联动标记；返回本地值是否变化。 */
    private boolean applyToggle(String key, boolean value) {
        ToggleView toggle = switchViews.get(key);
        if (toggle == null || userTouched.contains(key)) {
            return false;
        }
        if (prefs.getBoolean(key, !value) == value && toggle.isChecked() == value) {
            return false;
        }
        prefs.edit().putBoolean(key, value).commit();
        toggle.setChecked(value);
        View[] linked = switchLinked.get(key);
        if (linked != null) {
            for (View view : linked) {
                view.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
            }
        }
        return true;
    }

    /** 首选字幕/音频语言：两个字段一起提交（与网页端一致），失败只保留本地。 */
    private void syncLanguageToServer() {
        final ServerConfig cfg = currentConfig();
        if (cfg == null || "JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            return;
        }
        final String lan = langCode(prefs.getInt(KEY_SUBTITLE_LANG, 1));
        final String audioLan = langCode(prefs.getInt(KEY_AUDIO_LANG, 1));
        final MediaUseCases nas = App.from(this).useCases(cfg);
        new Thread(new Runnable() {
            public void run() {
                try {
                    nas.updateUserPrefs(cfg, lan, audioLan);
                    L.i("OfficialSettings", "首选语言已同步 lan=" + lan + " audio_lan=" + audioLan);
                } catch (FnException ex) {
                    L.w("OfficialSettings", "首选语言写入失败，保留本地：" + ex.getMessage());
                }
            }
        }).start();
    }

    /** 评分/分辨率/已观看显示项：整份 list:card:setting JSON 回写服务端。 */
    private void syncListSettingToServer() {
        final ServerConfig cfg = currentConfig();
        if (cfg == null || "JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            return;
        }
        Map<String, Object> card = new LinkedHashMap<String, Object>();
        card.put("score", Boolean.valueOf(prefs.getBoolean(KEY_SCORE, true)));
        card.put("resolution", Boolean.valueOf(prefs.getBoolean(KEY_RESOLUTION, true)));
        card.put("watched", Boolean.valueOf(prefs.getBoolean(KEY_WATCHED, false)));
        final String json = Json.stringify(card);
        final MediaUseCases nas = App.from(this).useCases(cfg);
        new Thread(new Runnable() {
            public void run() {
                try {
                    nas.saveUserData(cfg, USER_DATA_LIST_CARD, json);
                    L.i("OfficialSettings", "卡片显示配置已同步 " + json);
                } catch (FnException ex) {
                    L.w("OfficialSettings", "卡片显示配置写入失败，保留本地：" + ex.getMessage());
                }
            }
        }).start();
    }

    /** 已配置服务器时返回账号配置副本；纯本地运行（无地址/无凭据）返回 null。 */
    private ServerConfig currentConfig() {
        if (store == null) {
            return null;
        }
        ConfigCodec.ConfigBundle bundle = store.load();
        if (bundle == null || bundle.server == null) {
            return null;
        }
        ServerConfig cfg = bundle.server.copy();
        if (cfg.baseUrl == null || cfg.baseUrl.length() == 0) {
            return null;
        }
        if ((cfg.token == null || cfg.token.length() == 0) && !cfg.hasCredentials()) {
            return null;
        }
        return cfg;
    }

    private static String langCode(int index) {
        return (index >= 0 && index < LANG_CODES.length) ? LANG_CODES[index] : "";
    }

    /** 服务端语言码 → 本地索引：zh* → 简体中文，en* → English，空/any/未知 → 任意语言。 */
    private static int langIndex(String code) {
        if (code == null || code.length() == 0 || "any".equalsIgnoreCase(code)) {
            return 0;
        }
        String lower = code.toLowerCase();
        if (lower.startsWith("zh")) {
            return 1;
        }
        if (lower.startsWith("en")) {
            return 2;
        }
        return 0;
    }

    // ---------------- 外观 ----------------

    private View buildAppearancePanel() {
        return panel(new PanelBuilder() {
            void fill(FrameLayout p) {
                p.addView(heading("卡片样式"), at(0, 80));

                FrameLayout preview = new FrameLayout(OfficialSettingsActivity.this);
                preview.setBackground(roundRect(0xFF23242B, 8));
                preview.setClipChildren(true);
                ImageView poster = new ImageView(OfficialSettingsActivity.this);
                poster.setImageResource(R.drawable.official_video_placeholder);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.addView(poster, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                final TextView score = text("8.8", 26, 0xFF1A1A1A);
                score.setTypeface(Typeface.DEFAULT_BOLD);
                score.setGravity(Gravity.CENTER);
                score.setBackground(roundRect(0xFFFFB800, 4));
                score.setVisibility(prefs.getBoolean(KEY_SCORE, true)
                        ? View.VISIBLE : View.INVISIBLE);
                FrameLayout.LayoutParams scoreLp = new FrameLayout.LayoutParams(s(56), s(40));
                scoreLp.leftMargin = s(12);
                scoreLp.topMargin = s(12);
                preview.addView(score, scoreLp);

                final TextView fourK = qualityBadge("4K");
                FrameLayout.LayoutParams kLp = new FrameLayout.LayoutParams(s(52), s(30));
                kLp.leftMargin = s(132);
                kLp.topMargin = s(348);
                preview.addView(fourK, kLp);
                final TextView hd = qualityBadge("1080");
                FrameLayout.LayoutParams hdLp = new FrameLayout.LayoutParams(s(68), s(30));
                hdLp.leftMargin = s(188);
                hdLp.topMargin = s(348);
                preview.addView(hd, hdLp);
                if (!prefs.getBoolean(KEY_RESOLUTION, true)) {
                    fourK.setVisibility(View.INVISIBLE);
                    hd.setVisibility(View.INVISIBLE);
                }

                // 官方预览卡左下角的「已观看」图标，随开关实时显示/隐藏
                final View watched = watchedMark();
                watched.setVisibility(prefs.getBoolean(KEY_WATCHED, false)
                        ? View.VISIBLE : View.GONE);
                FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(s(36), s(36));
                wLp.leftMargin = s(16);
                wLp.topMargin = s(345);
                preview.addView(watched, wLp);

                FrameLayout.LayoutParams previewLp = new FrameLayout.LayoutParams(s(260), s(390));
                previewLp.topMargin = s(157);
                p.addView(preview, previewLp);

                addSwitchRow(p, 178, "评分", KEY_SCORE, true, score);
                addSwitchRow(p, 302, "“已观看”标记", KEY_WATCHED, false, watched);
                addSwitchRow(p, 426, "分辨率", KEY_RESOLUTION, true, fourK, hd);
            }
        });
    }

    /** 官方预览卡左下角的「已观看」图标：白色圆底 + ic_watched_filled 深色对勾（181 实测 36×37，left 16 / top 345）。 */
    private ImageView watchedMark() {
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_watched_filled);
        mark.setColorFilter(0xFF15171A);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xE6FFFFFF);
        mark.setBackground(circle);
        return mark;
    }

    /** 与首页角标同一套样式：4K/8K 白底黑字，其余黑底白字并带白描边。 */
    private TextView qualityBadge(String value) {
        boolean inverted = com.juying.medianest.core.policy.QualityBadges.inverted(value);
        TextView badge = text(value, 20, inverted ? 0xFF15171A : COLOR_TEXT);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(inverted ? 0xEEFFFFFF : 0x99101113);
        bg.setCornerRadius(sf(3));
        bg.setStroke(Math.max(1, s(1)), inverted ? 0xEEFFFFFF : 0xCCFFFFFF);
        badge.setBackground(bg);
        return badge;
    }

    private void addSwitchRow(FrameLayout panel, int top, String label,
                              final String key, boolean def) {
        addSwitchRow(panel, top, label, key, def, new View[0]);
    }

    /** linked 不为空时随开关切换显示（预览卡上的评分 / 已观看 / 分辨率标记）。 */
    private void addSwitchRow(FrameLayout panel, int top, String label,
                              final String key, boolean def, final View... linked) {
        final ToggleView toggle = new ToggleView(this);
        toggle.setChecked(prefs.getBoolean(key, def));
        switchViews.put(key, toggle);
        switchLinked.put(key, linked);

        final View row = switchRow(label, toggle, 56, 36);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean next = !toggle.isChecked();
                toggle.setChecked(next);
                prefs.edit().putBoolean(key, next).commit();
                settingsChanged = true;
                userTouched.add(key);
                for (View view : linked) {
                    // 预览卡上的标记用 INVISIBLE 隐藏，保持原有占位与排版
                    view.setVisibility(next ? View.VISIBLE : View.INVISIBLE);
                }
                syncListSettingToServer();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(930), s(100));
        lp.leftMargin = s(300);
        lp.topMargin = s(top);
        panel.addView(row, lp);
    }

    // ---------------- 公共控件 ----------------

    private abstract class PanelBuilder {
        abstract void fill(FrameLayout panel);
    }

    private FrameLayout panel(PanelBuilder builder) {
        FrameLayout panel = new FrameLayout(this);
        panel.setClipChildren(false);
        builder.fill(panel);
        return panel;
    }

    private FrameLayout.LayoutParams at(int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        return lp;
    }

    private FrameLayout.LayoutParams atFixed(int left, int top, int width) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                s(width), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        return lp;
    }

    private TextView heading(String value) {
        return text(value, 42, COLOR_TEXT);
    }

    private TextView description(String value) {
        TextView view = text(value, 30, COLOR_MUTED);
        view.setLineSpacing(s(4), 1f);
        return view;
    }

    private View optionRow(String value, boolean selected, String key, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(s(56), 0, s(36), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(rowBackground(false));

        TextView label = text(value, 40, COLOR_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = text("\uEAC5", 52, COLOR_TEXT);
        check.setTypeface(icons);
        check.setIncludeFontPadding(false);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(s(52), s(52)));

        row.setTag(new OptionTag(key, index, check));
        attachRowFocus(row, label, check, 56, 36);
        return row;
    }

    private View switchRow(String value, ToggleView toggle, int left, int right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(s(left), 0, s(right), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(rowBackground(false));

        TextView label = text(value, 40, COLOR_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle, new LinearLayout.LayoutParams(s(96), s(48)));

        attachRowFocus(row, label, null, left, right);
        return row;
    }

    private void attachRowFocus(final View row, final TextView label, final TextView check,
                                final int baseLeft, final int baseRight) {
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                int delta = Math.max(1, s(2));
                ((FrameLayout.LayoutParams) lp).topMargin += hasFocus ? -delta : delta;
                lp.height = s(hasFocus ? 104 : 100);
                v.setLayoutParams(lp);
                v.setBackground(rowBackground(hasFocus));
                v.setPadding(s(hasFocus ? baseLeft - 22 : baseLeft), 0, s(baseRight), 0);
                label.setTextColor(hasFocus ? COLOR_FOCUS_TEXT : COLOR_TEXT);
                if (check != null) {
                    check.setTextColor(hasFocus ? COLOR_FOCUS_TEXT : COLOR_TEXT);
                }
            }
        });
    }

    private GradientDrawable rowBackground(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xFFFFFFFF : COLOR_CARD);
        bg.setCornerRadius(sf(8));
        return bg;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(sf(radiusDp));
        return bg;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(size));
        view.setTextColor(color);
        return view;
    }

    private static final class OptionTag {
        final String key;
        final int value;
        final TextView check;

        OptionTag(String key, int value, TextView check) {
            this.key = key;
            this.value = value;
            this.check = check;
        }
    }

    /** 官方开关：96×48 圆角轨道 + 白色圆点，开启蓝色、关闭灰色。 */
    private static final class ToggleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean checked;

        ToggleView(Context context) {
            super(context);
            setFocusable(false);
            setClickable(false);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean value) {
            checked = value;
            invalidate();
        }

        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = h / 2f;
            paint.setColor(checked ? 0xFF2C6BF0 : 0xFF46474E);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, radius, radius, paint);

            float knob = h - 8;
            float left = checked ? w - knob - 4 : 4;
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(left + knob / 2f, h / 2f, knob / 2f, paint);
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
