package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;
import com.juying.medianest.core.util.L;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.juying.medianest.core.model.Capability;
import com.juying.medianest.ui.ImageLoader;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.policy.FilterEngine;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.Library;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.policy.QualityBadges;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.policy.ViewRules;
import com.juying.medianest.domain.usecase.MediaUseCases;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页：对齐飞牛影视网页端布局与交互。
 *
 * 结构：左侧固定导航（首页/收藏/媒体库/分类）+ 顶部栏（标题 + 搜索/用户/设置）
 *      + 内容区（媒体库卡片、继续观看横卡、影片海报网格）。
 * 遥控器：方向键在系统 LinearLayout 焦点链上移动，OK 由 setOnClickListener + onKeyDown 兜底，
 *        返回键弹确认框，菜单键进设置。焦点态由 poster_card_selector / nav_item_selector 高亮描边。
 *
 * minSdk=1：只用 API 1 就有的 LinearLayout / HorizontalScrollView / ImageView，
 *          图片加载走自研 ImageLoader（线程池 + LRU），无 RecyclerView/Glide 依赖。
 */
public final class MainActivity extends Activity {

    private static final String[] CAT_KEYS = {"all", "movie", "tv", "live", "other"};
    private static final int[] CAT_LABELS = {
            R.string.cat_all, R.string.cat_movie, R.string.cat_tv, R.string.cat_live, R.string.cat_other
    };

    private ConfigRepository store;
    private ConfigCodec.ConfigBundle bundle;
    private ServerConfig cfg;
    private MediaUseCases api;
    private ImageLoader imageLoader;

    private TextView topTitle;
    private TextView topServer;
    private LinearLayout navList;
    private LinearLayout contentList;
    private ScrollView contentScroll;
    private OfficialHomeRenderer officialRenderer;

    private final List<View> navItems = new ArrayList<View>();
    private String currentCategory = "home"; // home/favorite/lib:<guid>/all/movie/tv/live/other
    private int currentNavIndex = 0;
    private List<MediaItem> allItems = new ArrayList<MediaItem>();
    /** 网页端「媒体库」分组是动态的：mdb/list 每次返回当下所有库，新增/删除即时同步。 */
    private List<Library> libraries = new ArrayList<Library>();
    /** 库 guid 列表签名，只有真的变化时才重建导航，避免刷新时丢焦点。 */
    private String librarySignature = "";
    /** 服务端 mediadb/sum 的真实计数（total=352 / live=334 / 各库 guid）。 */
    private Map<String, Integer> serverCounts = new LinkedHashMap<String, Integer>();
    private boolean loading = false;
    private boolean redirected = false;
    private long lastOpenAt = 0L;

    // ---- 筛选 / 排序 / 布局：按页面类型区分，布局偏好按分类持久化 ----
    private static final String VIEW_PREFS = "fntv_view_prefs";
    /** 当前范围可用的筛选维度，来自 tag/list（不同库返回的维度不同）。 */
    private FilterEngine.TagSet filterTags = new FilterEngine.TagSet();
    /** 用户在筛选面板里选中的值；key 为维度 id，值为「全部」时等同于未选。 */
    private Map<String, String> filterSelection = new LinkedHashMap<String, String>();
    /** 筛选以独立弹窗呈现：方向键只在弹窗内循环，BACK / 「关闭」退出。 */
    private AlertDialog filterDialog = null;
    private View filterDialogRoot = null;
    private boolean searchMode = false;
    private String searchQuery = "";
    /** 动态创建的筛选控件没有 XML id，用独立号段分配 id 以建立显式方向键焦点链。 */
    private int focusIdCounter = 0x10000;
    private final List<View> filterBarViews = new ArrayList<View>();
    private final List<View> filterRowFirstViews = new ArrayList<View>();
    /** 网格/列表页按行收集的卡片，用于建立显式方向键焦点链。 */
    private final List<List<View>> gridRows = new ArrayList<List<View>>();
    /** null 表示使用该类型的默认排序字段。 */
    private String sortField = null;
    private boolean sortAscending = false;
    /** onResume 会多次调用（从详情/播放/设置返回），只在首次初始化分类，避免被重置回首页。 */
    private static final String STATE_CATEGORY = "fntv.category";
    private boolean categoryInitialized = false;
    private String restoredCategory = null;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoredCategory = savedInstanceState == null ? null
                : savedInstanceState.getString(STATE_CATEGORY);
        setContentView(R.layout.activity_main_official);
        store = App.from(this).config();
        topTitle = (TextView) findViewById(R.id.top_title);
        topServer = (TextView) findViewById(R.id.top_server);
        navList = (LinearLayout) findViewById(R.id.nav_list);
        contentList = (LinearLayout) findViewById(R.id.content_list);
        contentScroll = (ScrollView) findViewById(R.id.content_scroll);
        imageLoader = new ImageLoader(this);
        officialRenderer = new OfficialHomeRenderer(this, contentList, contentScroll, imageLoader);

        buildNavigation();
        bindTopButtons();
    }

    protected void onResume() {
        super.onResume();
        if (officialRenderer != null) {
            officialRenderer.onResume();
        }
        bundle = store.load();
        if (bundle.needsDetection()) {
            Intent i = new Intent(this, BootstrapActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
            return;
        }
        cfg = bundle.server;
        Capability cap = bundle.capability;
        topServer.setText(cfg.serverName.length() > 0 ? cfg.serverName : cfg.displayLabel());
        if (officialRenderer != null) {
            officialRenderer.setAccountName(cfg.username);
        }
        if (!redirected && !cfg.hasCredentials()) {
            redirected = true;
            // 未登录时先选择服务器，再由服务器选择页进入账号登录。
            startActivity(new Intent(this, OfficialScanDeviceActivity.class));
            return;
        }
        api = App.from(this).useCases(cfg);
        if (!categoryInitialized) {
            categoryInitialized = true;
            // 进程内从详情页/播放页返回保留原分类；Activity 被重建时用保存的分类恢复
            selectCategory(restoredCategory != null ? restoredCategory : "home");
        } else if (DetailActivity.favoriteChanged || DetailActivity.watchedChanged) {
            boolean watchedChanged = DetailActivity.watchedChanged;
            DetailActivity.favoriteChanged = false;
            DetailActivity.watchedChanged = false;
            if (watchedChanged) {
                // 已看状态影响所有卡片的「已观看」图标，重建当前页并重拉服务端状态
                selectCategory(currentCategory);
            } else if ("favorite".equals(currentCategory)) {
                loadData("favorite");
            }
        } else if (OfficialSettingsActivity.settingsChanged) {
            OfficialSettingsActivity.settingsChanged = false;
            // 设置里切换「已观看」标记后返回，需要重建卡片才会重新读取开关
            selectCategory(currentCategory);
        }
    }

    protected void onPause() {
        if (officialRenderer != null) {
            officialRenderer.onPause();
        }
        super.onPause();
    }

    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CATEGORY, currentCategory);
    }

    /** 屏幕尺寸/密度变化后按新可用宽度重算自动列数（用户指定的固定列数不受影响）。 */
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!loading && !allItems.isEmpty() && currentCategory != null
                && !"home".equals(currentCategory)) {
            refreshContent();
        }
    }

    // ---------------- 左侧导航 ----------------

    private void buildNavigation() {
        // 官方主题无左侧导航栏（顶栏/内容区导航由 OfficialHomeRenderer 绘制），
        // 仅清空容器，避免旧主题残留。
        navList.removeAllViews();
        navItems.clear();
    }

    private int indexOfNav(String key) {
        String tag = "nav:" + key;
        for (int i = 0; i < navItems.size(); i++) {
            if (tag.equals(navItems.get(i).getTag())) {
                return i;
            }
        }
        return 0;
    }

    private String signatureOf(List<Library> libs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < libs.size(); i++) {
            sb.append(libs.get(i).guid).append('|');
        }
        return sb.toString();
    }

    /**
     * 网页端实测：未选中图标线条约 #A9A9A9、文字更亮，选中/悬停统一品牌蓝。
     * TV 上焦点等同悬停。图标是网页端提取的白色透明 PNG，染色只改变颜色不改形状。
     */
    private void colorNavItem(View v, boolean active) {
        if (v == null) {
            return;
        }
        int iconColor = active ? getResources().getColor(R.color.web_blue)
                : getResources().getColor(R.color.web_text_secondary);
        int labelColor = active ? getResources().getColor(R.color.web_blue)
                : getResources().getColor(R.color.web_text);
        ImageView icon = (ImageView) v.findViewById(R.id.nav_icon);
        TextView label = (TextView) v.findViewById(R.id.nav_label);
        if (icon != null) {
            icon.setColorFilter(iconColor);
        }
        if (label != null) {
            label.setTextColor(labelColor);
        }
    }

    private void updateNavSelection() {
        for (int i = 0; i < navItems.size(); i++) {
            View v = navItems.get(i);
            String key = (String) v.getTag();
            boolean selected = ("nav:" + currentCategory).equals(key);
            v.setSelected(selected);
            colorNavItem(v, selected || v.hasFocus());
        }
    }

    private void setNavCount(String key, int count) {
        // nav_count 是 nav_item 内部的子 View，不是 navList 的直接子节点；
        // 之前按 navList.getChildAt() 遍历永远匹配不到，计数一直是空的。
        String tag = "nav:" + key;
        for (int i = 0; i < navItems.size(); i++) {
            View v = navItems.get(i);
            if (!tag.equals(v.getTag())) {
                continue;
            }
            TextView c = (TextView) v.findViewById(R.id.nav_count);
            if (c != null) {
                c.setText(count > 0 ? String.valueOf(count) : "");
            }
        }
    }

    void selectCategory(String key) {
        searchMode = false;
        closeFilterDialogQuietly();
        if (officialRenderer != null) {
            officialRenderer.prepareList();
        }
        if (!key.equals(currentCategory)) {
            // 切页重置筛选与排序；布局偏好按分类独立保留（readView 从 SharedPreferences 读取）
            filterSelection.clear();
            sortField = null;
            sortAscending = false;
        }
        currentCategory = key;
        currentNavIndex = indexOfNav(key);
        updateNavSelection();
        contentList.removeAllViews();
        topTitle.setText(titleOf(key));
        if ("favorite".equals(key)) {
            renderFavorite();
        } else {
            renderHomeOrList(key);
        }
    }

    private String titleOf(String key) {
        if (key.startsWith("lib:")) {
            String guid = key.substring(4);
            for (int i = 0; i < libraries.size(); i++) {
                if (libraries.get(i).guid.equals(guid)) {
                    return libraries.get(i).name;
                }
            }
            return getString(R.string.nav_library);
        }
        if ("home".equals(key)) return getString(R.string.nav_home);
        if ("favorite".equals(key)) return getString(R.string.nav_favorite);
        if ("movie".equals(key)) return getString(R.string.cat_movie);
        if ("tv".equals(key)) return getString(R.string.cat_tv);
        if ("live".equals(key)) return getString(R.string.cat_live);
        if ("other".equals(key)) return getString(R.string.cat_other);
        return getString(R.string.cat_all);
    }

    // ---------------- 顶部按钮 ----------------

    private void bindTopButtons() {
        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (officialRenderer != null) {
                    startActivity(new Intent(MainActivity.this, OfficialSearchActivity.class));
                } else {
                    showSearchDialog();
                }
            }
        });
        findViewById(R.id.btn_user).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, OfficialAccountActivity.class));
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (officialRenderer != null) {
                    startActivity(new Intent(MainActivity.this, OfficialMoreActivity.class));
                } else {
                    startActivity(new Intent(MainActivity.this, OfficialScanDeviceActivity.class));
                }
            }
        });
        View refresh = findViewById(R.id.btn_refresh);
        if (refresh != null) {
            refresh.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    loadData(currentCategory);
                }
            });
        }
    }

    // ---------------- 视图规则 / 屏幕自适应 ----------------

    /** 按当前分类 key 与媒体库 category 判定页面类型，决定筛选/排序/布局配置。 */
    private ViewRules.Kind currentKind() {
        String cat = "";
        if (currentCategory.startsWith("lib:")) {
            String guid = currentCategory.substring(4);
            for (int i = 0; i < libraries.size(); i++) {
                if (libraries.get(i).guid.equals(guid)) {
                    cat = libraries.get(i).category;
                    break;
                }
            }
        }
        return ViewRules.kindOf(currentCategory, cat);
    }

    private String prefsKey() {
        return currentCategory == null ? "home" : currentCategory;
    }

    private SharedPreferences viewPrefs() {
        return getSharedPreferences(VIEW_PREFS, MODE_PRIVATE);
    }

    private int savedView() {
        int v = viewPrefs().getInt(prefsKey() + ".view", ViewRules.VIEW_VERTICAL);
        return (v < 0 || v > ViewRules.VIEW_LIST) ? ViewRules.VIEW_VERTICAL : v;
    }

    private int savedColumns() {
        return viewPrefs().getInt(prefsKey() + ".columns", ViewRules.COLUMNS_AUTO);
    }

    private void saveView(int view) {
        SharedPreferences.Editor e = viewPrefs().edit();
        e.putInt(prefsKey() + ".view", view);
        e.commit();
    }

    private void saveColumns(int columns) {
        SharedPreferences.Editor e = viewPrefs().edit();
        e.putInt(prefsKey() + ".columns", columns);
        e.commit();
    }

    /** 内容区可用宽度（dp）：实际测量值优先，首帧未测量时按屏幕宽度减去 200dp 导航。 */
    private int contentWidthDp() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density > 0 ? dm.density : 1f;
        int w = contentList.getWidth();
        if (w <= 0) {
            w = dm.widthPixels - (officialRenderer != null
                    ? officialRenderer.s(160) : (int) (200f * density));
        }
        w -= contentList.getPaddingLeft() + contentList.getPaddingRight();
        return Math.max(0, (int) (w / density));
    }

    private int currentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private String effectiveSortField(ViewRules.Spec spec) {
        return sortField == null ? spec.defaultSort : sortField;
    }

    private boolean hasActiveFilter() {
        for (Map.Entry<String, String> e : filterSelection.entrySet()) {
            String v = e.getValue();
            if (v != null && v.length() > 0 && !ViewRules.ALL.equals(v)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 筛选控件的遥控器支持 ----------------

    /**
     * 统一给筛选按钮/选项附加：清晰的聚焦背景与描边、聚焦文字色、OK 键确认、独立 id。
     * 用 OnKeyListener 消费 OK 键（ACTION_UP），避免框架再触发一次 click。
     */
    private int attachRemoteFocus(final View v, View.OnClickListener click) {
        int id = focusIdCounter++;
        v.setId(id);
        v.setFocusable(true);
        // 触摸（含触屏遥控器）后系统进入 touch mode，普通 focusable 的
        // View 会丢焦点；TV 上必须保持可聚焦，否则方向键无法继续操作筛选控件。
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        v.setBackgroundResource(R.drawable.filter_btn_selector);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean hasFocus) {
                FocusEffects.focusScale(view, hasFocus);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(getResources().getColor(
                            hasFocus ? R.color.web_text
                                    : (activeForTag(view) ? R.color.web_blue : R.color.web_text)));
                }
            }
        });
        v.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_UP) {
                    return false;
                }
                if (TvKeys.isOk(keyCode)) {
                    view.performClick();
                    return true;
                }
                // 弹窗式筛选：任何控件上按 BACK 都直接退出筛选，不需要靠方向键切出
                if (TvKeys.isBack(keyCode) && filterDialog != null) {
                    dismissFilterDialog();
                    return true;
                }
                return false;
            }
        });
        // focusableInTouchMode 的 View 第一次触摸默认只获焦点、不触发 click，
        // 这里显式在 ACTION_UP 触发一次，保证触摸/遥控器确认都只执行一次动作。
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent ev) {
                if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                    view.requestFocus();
                    view.performClick();
                }
                return true;
            }
        });
        return id;
    }

    /**
     * 同一行内建立左右焦点链。弹窗内部用 loopEnds=true 自环；筛选栏用 false，
     * 首尾不设强制目标，系统才能把 LEFT 焦点切回左侧导航、RIGHT 切到海报网格。
     */
    private void linkHorizontal(List<View> views, boolean loopEnds) {
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            if (i > 0) {
                v.setNextFocusLeftId(views.get(i - 1).getId());
            } else {
                v.setNextFocusLeftId(loopEnds ? v.getId() : View.NO_ID);
            }
            if (i + 1 < views.size()) {
                v.setNextFocusRightId(views.get(i + 1).getId());
            } else {
                v.setNextFocusRightId(loopEnds ? v.getId() : View.NO_ID);
            }
        }
    }

    /** 弹窗内部：各组第一个选项与底部按钮之间建立上下链，首尾自环（不跨窗口）。 */
    private void linkDialogFocus() {
        for (int i = 0; i < filterRowFirstViews.size(); i++) {
            View v = filterRowFirstViews.get(i);
            v.setNextFocusUpId(i == 0 ? v.getId() : filterRowFirstViews.get(i - 1).getId());
            v.setNextFocusDownId(i + 1 < filterRowFirstViews.size()
                    ? filterRowFirstViews.get(i + 1).getId() : v.getId());
        }
    }

    /** 按 tag 判断该选项当前是否为生效条件，供聚焦/失焦时恢复颜色。 */
    private boolean activeForTag(View v) {
        Object t = v.getTag();
        if (!(t instanceof String)) {
            return false;
        }
        String tag = (String) t;
        if (tag.startsWith("bar:")) {
            return false;
        }
        int c = tag.indexOf(':');
        if (c <= 0) {
            return false;
        }
        String gid = tag.substring(0, c);
        String value = tag.substring(c + 1);
        String cur = filterSelection.get(gid);
        return ViewRules.ALL.equals(value)
                ? (cur == null || ViewRules.ALL.equals(cur))
                : value.equals(cur);
    }

    /** 弹窗内改选条件后，原地刷新所有选项的 ✓ 与颜色，不重建弹窗。 */
    private void updateDialogSelections() {
        if (filterDialogRoot == null) {
            return;
        }
        updateSelectionView(filterDialogRoot);
    }

    private void updateSelectionView(View v) {
        if (v instanceof TextView && v.getTag() instanceof String
                && !((String) v.getTag()).startsWith("bar:")) {
            String tag = (String) v.getTag();
            int c = tag.indexOf(':');
            if (c > 0) {
                String value = tag.substring(c + 1);
                boolean active = activeForTag(v);
                ((TextView) v).setText(active ? "✓ " + value : value);
                if (!v.hasFocus()) {
                    ((TextView) v).setTextColor(getResources().getColor(
                            active ? R.color.web_blue : R.color.web_text));
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                updateSelectionView(g.getChildAt(i));
            }
        }
    }

    /** 不重新请求网络、只按当前筛选/排序/布局偏好重绘内容区。 */
    private void refreshContent() {
        // 内容区会整体重建，先记下当前焦点控件的 tag，重建后把焦点还回去
        View focused = getCurrentFocus();
        final Object anchor = focused == null ? null : focused.getTag();
        if (searchMode) {
            renderSearchResults();
        } else if ("favorite".equals(currentCategory)) {
            renderFavorite();
        } else {
            renderContent(currentCategory, allItems, new ArrayList<MediaItem>());
        }
        contentList.post(new Runnable() {
            public void run() {
                // 筛选弹窗打开时，焦点属于弹窗而不是内容区，优先还原到弹窗内原选项
                if (filterDialog != null && filterDialogRoot != null && anchor != null) {
                    View inDialog = filterDialogRoot.findViewWithTag(anchor);
                    if (inDialog != null && inDialog.isFocusable()) {
                        inDialog.requestFocus();
                        return;
                    }
                }
                View target = anchor == null ? null : contentList.findViewWithTag(anchor);
                if (target == null && !filterBarViews.isEmpty()) {
                    target = filterBarViews.get(0);
                }
                if (target != null && target.isFocusable()) {
                    target.requestFocus();
                }
            }
        });
    }

    // ---------------- 内容渲染 ----------------

    /** 首页或分类列表：先加载数据，再渲染媒体库/继续观看/海报网格。 */
    private void renderHomeOrList(final String key) {
        loadData(key);
    }

    private void loadData(final String key) {
        if (loading) {
            return;
        }
        loading = true;
        showLoading("正在连接 " + cfg.baseUrl + " …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    ConfigCodec.ServerInfo info = api.verify(cfg);
                    // 多媒体库后条目会变多，一次拉全，避免新增库的内容被截断
                    final List<MediaItem> items = api.listMedia(cfg, true, 200);
                    final List<Library> libs = new ArrayList<Library>();
                    try {
                        libs.addAll(api.listLibraries(cfg));
                    } catch (Throwable t) {
                        // 老固件可能没有 mdb/list，退回「全部」视图，不影响主流程
                        L.w("MainActivity", "媒体库列表加载失败，退回单库视图：" + t.getMessage());
                    }
                    // 直播频道：网页端用 tags.type=["LiveChannel"] 查询，必须与影视条目合并，
                    // 否则「电视直播」分类和「自定义直播源」库会是空的。
                    final List<MediaItem> lives = new ArrayList<MediaItem>();
                    try {
                        // 直播频道必须拉全（Nas 实测 125 个），截断会表现为分组/频道显示不全
                        lives.addAll(api.listLiveChannels(cfg, Integer.MAX_VALUE));
                    } catch (Throwable t) {
                        L.w("MainActivity", "直播频道加载失败：" + t.getMessage());
                    }
                    items.addAll(lives);
                    // 服务端真实计数（total=352 / live=334 / 各库 guid），比本地已加载条数准确
                    final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
                    try {
                        counts.putAll(api.libraryCounts(cfg));
                    } catch (Throwable t) {
                        L.w("MainActivity", "库计数加载失败：" + t.getMessage());
                    }
                    final List<MediaItem> cont = new ArrayList<MediaItem>();
                    if ("home".equals(key)) {
                        try {
                            cont.addAll(api.listContinue(cfg));
                        } catch (Throwable ignored) {
                            L.w("MainActivity", "继续观看加载失败，跳过该行");
                        }
                    }
                    bundle.server = cfg;
                    store.save(bundle);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            allItems = items;
                            serverCounts = counts;
                            loading = false;
                            String sig = signatureOf(libs);
                            boolean navChanged = !sig.equals(librarySignature);
                            if (navChanged) {
                                libraries = libs;
                                librarySignature = sig;
                                buildNavigation();
                                currentNavIndex = indexOfNav(currentCategory);
                                updateNavSelection();
                            }
                            setNavCount("all", countOf(counts, "total", items.size()));
                            setNavCount("movie", countOf(counts, "movie", countType(items, "Movie")));
                            setNavCount("tv", countOf(counts, "tv", countType(items, "TV")));
                            setNavCount("live", countOf(counts, "live", countType(items, "LiveChannel")));
                            setNavCount("other", countOf(counts, "video", countType(items, "Video")));
                            for (int i = 0; i < libraries.size(); i++) {
                                Library lib = libraries.get(i);
                                int n = countOf(counts, lib.guid,
                                        filterByLibrary(items, lib.guid).size());
                                setNavCount("lib:" + lib.guid, n);
                            }
                            if ("favorite".equals(key)) {
                                renderFavorite();
                            } else {
                                renderContent(key, items, cont);
                            }
                        }
                    });

                    // 首屏已渲染；筛选维度与条目元数据异步补齐，不阻塞画面。
                    // tag/list 按范围返回不同的可用维度，这正是各库下拉项不同的来源。
                    String libCat = "";
                    if (key.startsWith("lib:")) {
                        String guid = key.substring(4);
                        for (int i = 0; i < libs.size(); i++) {
                            if (libs.get(i).guid.equals(guid)) {
                                libCat = libs.get(i).category;
                                break;
                            }
                        }
                    }
                    final ViewRules.Kind metaKind = ViewRules.kindOf(key, libCat);
                    final String metaScope = ViewRules.tagScope(metaKind, key,
                            "favorite".equals(key));
                    new Thread(new Runnable() {
                        public void run() {
                            final FilterEngine.TagSet tags = new FilterEngine.TagSet();
                            try {
                                tags.putAll(api.filterTags(cfg, metaScope));
                            } catch (Throwable t) {
                                L.w("MainActivity", "筛选维度加载失败：" + t.getMessage());
                            }
                            try {
                                api.enrichFilterMeta(cfg, items);
                            } catch (Throwable t) {
                                L.w("MainActivity", "筛选元数据补齐失败：" + t.getMessage());
                            }
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    filterTags = tags;
                                    // 弹窗打开时只更新数据、不重建弹窗，避免打断用户正在做的
                                    // 方向键/确认操作；下次打开自然会带上新的筛选维度。
                                    if (filterDialog == null
                                            && !"home".equals(currentCategory)
                                            && savedView() != ViewRules.VIEW_VERTICAL) {
                                        // 横幅/列表视图才需要横版剧照，到位后重绘一次；
                                        // 竖幅默认视图不刷新，避免无谓的界面跳动
                                        refreshContent();
                                    }
                                }
                            });
                        }
                    }, "fntv-filter-meta").start();
                } catch (final FnException ex) {
                    L.e("MainActivity", "加载片库失败：" + ex.getMessage());
                    runOnUiThread(new Runnable() {
                        public void run() {
                            loading = false;
                            showError("加载失败：" + ex.reason.title());
                            Ui.error(MainActivity.this, ex, new Ui.Action() {
                                public void run() {
                                    renderHomeOrList(key);
                                }
                            });
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            loading = false;
                            showError("加载异常");
                            Ui.error(MainActivity.this,
                                    new FnException(FailureReason.UNKNOWN, t.toString()), null);
                        }
                    });
                }
            }
        }, "fntv-library").start();
    }

    private int countType(List<MediaItem> items, String type) {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (type.equalsIgnoreCase(items.get(i).mediaType)) {
                n++;
            }
        }
        return n;
    }

    /** 优先用服务端 mediadb/sum 的真实计数，拿不到才退回本地已加载条数。 */
    private int countOf(Map<String, Integer> counts, String key, int fallback) {
        Integer v = counts.get(key);
        return v != null ? v.intValue() : fallback;
    }

    private void renderContent(String key, List<MediaItem> items, List<MediaItem> cont) {
        gridRows.clear();
        if (officialRenderer != null && "home".equals(key)) {
            officialRenderer.renderHome(cfg, api, items, libraries, serverCounts, cont);
            return;
        }
        if (officialRenderer != null) {
            officialRenderer.prepareList();
        }
        contentList.removeAllViews();
        filterBarViews.clear();
        List<MediaItem> shown = filterByCategory(key, items);
        if ("home".equals(key)) {
            addSection(getString(R.string.section_library), false, null);
            addLibraryRow();
            if (!cont.isEmpty()) {
                addSection(getString(R.string.section_continue), false, null);
                addContinueRow(cont);
            }
            // 网页端首页按媒体库分节：每个库一行海报，标题即库名，不再写死「Media」
            if (libraries.isEmpty()) {
                addSection(getString(R.string.section_all), true, null);
                addPosterRow(shown);
            } else {
                for (int i = 0; i < libraries.size(); i++) {
                    final Library lib = libraries.get(i);
                    addSection(lib.name, true, new View.OnClickListener() {
                        public void onClick(View v) {
                            selectCategory("lib:" + lib.guid);
                        }
                    });
                    addPosterRow(filterByLibrary(items, lib.guid));
                }
            }
        } else {
            ViewRules.Spec spec = ViewRules.specOf(currentKind());
            addSection(titleOf(key), false, null);
            // 筛选与排序都在本地真实执行，不重新请求网络
            shown = FilterEngine.apply(shown, filterSelection, currentYear());
            FilterEngine.sort(shown, effectiveSortField(spec), sortAscending);
            int realCount = shown.size();
            // 未筛选时用 mediadb/sum 的服务端真实总数；一旦筛选，网页端「共 N 项」变为结果数
            if (!hasActiveFilter()) {
                if ("all".equals(key)) realCount = countOf(serverCounts, "total", realCount);
                else if ("movie".equals(key)) realCount = countOf(serverCounts, "movie", realCount);
                else if ("tv".equals(key)) realCount = countOf(serverCounts, "tv", realCount);
                else if ("live".equals(key)) realCount = countOf(serverCounts, "live", realCount);
                else if ("other".equals(key)) realCount = countOf(serverCounts, "video", realCount);
                else if (key.startsWith("lib:")) {
                    realCount = countOf(serverCounts, key.substring(4), realCount);
                }
            }
            addFilterBar(realCount, spec);
            addPosterGrid(shown);
        }
        if (!shown.isEmpty() || !cont.isEmpty()) {
            View first = gridRows.isEmpty() ? contentList.getChildAt(0)
                    : gridRows.get(0).get(0);
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    private List<MediaItem> filterByCategory(String key, List<MediaItem> items) {
        if (key.startsWith("lib:")) {
            return filterByLibrary(items, key.substring(4));
        }
        if ("movie".equals(key)) {
            return filterType(items, "Movie");
        }
        if ("tv".equals(key)) {
            return filterType(items, "TV");
        }
        if ("live".equals(key)) {
            return filterType(items, "LiveChannel");
        }
        if ("other".equals(key)) {
            return filterType(items, "Video");
        }
        return new ArrayList<MediaItem>(items);
    }

    /** 按 ancestor_guid 归属过滤，新增媒体库的内容会自动出现在对应导航项里。 */
    private List<MediaItem> filterByLibrary(List<MediaItem> items, String libraryGuid) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < items.size(); i++) {
            if (libraryGuid.equalsIgnoreCase(items.get(i).libraryGuid)) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    private List<MediaItem> filterType(List<MediaItem> items, String type) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < items.size(); i++) {
            if (type.equalsIgnoreCase(items.get(i).mediaType)) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    private void addSection(String title, boolean more, View.OnClickListener moreClick) {
        LinearLayout s = (LinearLayout) LayoutInflater.from(this).inflate(R.layout.row_section, contentList, false);
        ((TextView) s.findViewById(R.id.section_title)).setText(title);
        TextView moreView = (TextView) s.findViewById(R.id.section_more);
        if (more) {
            moreView.setVisibility(View.VISIBLE);
            if (moreClick != null) {
                moreView.setOnClickListener(moreClick);
                FocusEffects.attach(moreView);
            }
        }
        contentList.addView(s);
    }

    /**
     * 筛选栏按页面类型生成：影视页 3 个按钮；直播/其他视频页排序没有字段下拉；
     * 收藏页没有布局按钮；所有页面右侧都有「共 N 项」。
     */
    private void addFilterBar(int count, final ViewRules.Spec spec) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        // 底部留白统一由与网格相同的 bottomMargin 控制，避免控制按钮贴住第一行卡片
        bar.setPadding(0, 4, 0, 0);

        if (spec.showFilter) {
            filterBarViews.add(addFilterButton(bar, getString(R.string.filter_label) + " ▾",
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            showFilterDialog(spec);
                        }
                    }));
        }
        if (spec.showSort) {
            String label = effectiveSortField(spec) + (sortAscending ? " ↑" : " ↓")
                    + (spec.sortFieldDropdown ? " ▾" : "");
            filterBarViews.add(addFilterButton(bar, label, new View.OnClickListener() {
                public void onClick(View v) {
                    showSortDialog(spec);
                }
            }));
        }
        if (spec.showLayout) {
            filterBarViews.add(addFilterButton(bar, getString(R.string.layout_label) + " ▾",
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            showLayoutDialog(spec);
                        }
                    }));
        }
        // 筛选栏首尾不强制自环，LEFT/RIGHT 交给系统默认（弹窗由 BACK 退出）
        linkHorizontal(filterBarViews, false);

        TextView countView = new TextView(this);
        countView.setText(getString(R.string.total_items, count));
        countView.setTextSize(14);
        countView.setTextColor(getResources().getColor(R.color.text_muted));
        countView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        countView.setLayoutParams(cp);
        countView.setPadding(16, 0, 0, 0);
        bar.addView(countView);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = (int) (ViewRules.GRID_GAP_DP
                * getResources().getDisplayMetrics().density);
        contentList.addView(bar, barLp);
    }

    private View addFilterButton(LinearLayout parent, String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTag("bar:" + text);
        b.setTextSize(14);
        b.setTextColor(getResources().getColor(R.color.web_text));
        b.setPadding(20, 10, 20, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 12;
        b.setLayoutParams(lp);
        attachRemoteFocus(b, click);
        parent.addView(b);
        return b;
    }

    /**
     * 筛选改为独立弹窗：分组来自 tag/list 的可用维度，选项点击后真实过滤。
     * 方向键只在弹窗内循环（行内左右、跨组上下自环），退出统一用 BACK 或「关闭」。
     */
    private void showFilterDialog(final ViewRules.Spec spec) {
        if (filterDialog != null) {
            return;
        }
        List<MediaItem> unfiltered = searchMode
                ? new ArrayList<MediaItem>(allItems)
                : filterByCategory(currentCategory, allItems);
        filterRowFirstViews.clear();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF232323);
        panel.setPadding(20, 16, 20, 12);

        List<FilterEngine.Group> groups =
                FilterEngine.groups(spec.kind, filterTags, unfiltered, currentYear());
        if (groups.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("正在读取可用筛选条件…");
            hint.setTextSize(14);
            hint.setTextColor(getResources().getColor(R.color.text_muted));
            panel.addView(hint);
        }
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < groups.size(); i++) {
            final FilterEngine.Group g = groups.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, 6, 0, 6);

            TextView label = new TextView(this);
            label.setText(g.label);
            label.setTextSize(14);
            label.setTextColor(getResources().getColor(R.color.text_muted));
            label.setWidth((int) (130f * density));
            line.addView(label);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setFocusable(false); // 焦点必须落在选项按钮上，而不是滚动容器
            LinearLayout opts = new LinearLayout(this);
            opts.setOrientation(LinearLayout.HORIZONTAL);
            List<String> values = new ArrayList<String>();
            values.add(ViewRules.ALL);
            values.addAll(g.values);
            List<View> rowViews = new ArrayList<View>();
            for (int k = 0; k < values.size(); k++) {
                final String value = values.get(k);
                String cur = filterSelection.get(g.id);
                boolean active = ViewRules.ALL.equals(value)
                        ? (cur == null || ViewRules.ALL.equals(cur))
                        : value.equals(cur);
                TextView b = new TextView(this);
                b.setText(active ? "✓ " + value : value);
                b.setTag(g.id + ":" + value);
                b.setTextSize(14);
                b.setTextColor(getResources().getColor(
                        active ? R.color.web_blue : R.color.web_text));
                b.setPadding(16, 8, 16, 8);
                attachRemoteFocus(b, new View.OnClickListener() {
                    public void onClick(View v) {
                        filterSelection.put(g.id, value);
                        updateDialogSelections(); // 原地更新 ✓，不重建弹窗
                        refreshContent();         // 背后列表同步刷新，焦点仍留在弹窗内
                    }
                });
                opts.addView(b);
                rowViews.add(b);
            }
            linkHorizontal(rowViews, true);
            if (!rowViews.isEmpty()) {
                filterRowFirstViews.add(rowViews.get(0));
            }
            hsv.addView(opts);
            line.addView(hsv, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            panel.addView(line);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, 10, 0, 2);
        List<View> footerViews = new ArrayList<View>();
        footerViews.add(addFilterButton(footer, "清空筛选", new View.OnClickListener() {
            public void onClick(View v) {
                filterSelection.clear();
                updateDialogSelections();
                refreshContent();
            }
        }));
        footerViews.add(addFilterButton(footer, "关闭", new View.OnClickListener() {
            public void onClick(View v) {
                dismissFilterDialog();
            }
        }));
        linkHorizontal(footerViews, true);
        filterRowFirstViews.add(footerViews.get(0));
        panel.addView(footer);
        linkDialogFocus();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        filterDialogRoot = scroll;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("筛选 · " + titleOf(currentCategory));
        builder.setView(scroll);
        filterDialog = builder.create();
        filterDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                // 系统 BACK 直接 dismiss 时走这里；手动关闭已提前置空，避免重复重绘
                if (filterDialog != null) {
                    filterDialog = null;
                    filterDialogRoot = null;
                    refreshContent();
                }
            }
        });
        filterDialog.show();
        android.view.Window w = filterDialog.getWindow();
        if (w != null) {
            w.setLayout((int) (contentWidthDp() * density) - 32, (int) (560f * density));
        }
        if (!filterRowFirstViews.isEmpty()) {
            filterRowFirstViews.get(0).requestFocus();
        }
    }

    private void dismissFilterDialog() {
        if (filterDialog == null) {
            return;
        }
        AlertDialog d = filterDialog;
        // 先置空，避免系统 BACK 触发的 onDismiss 与手动关闭路径各重绘一次
        filterDialog = null;
        filterDialogRoot = null;
        d.setOnDismissListener(null);
        d.dismiss();
        refreshContent();
    }

    /** 切页/搜索时静默关闭弹窗，不触发 onDismiss 的重绘（避免与切页渲染打架）。 */
    private void closeFilterDialogQuietly() {
        if (filterDialog == null) {
            return;
        }
        filterDialog.setOnDismissListener(null);
        filterDialog.dismiss();
        filterDialog = null;
        filterDialogRoot = null;
    }

    /** 筛选元数据异步到位后，若弹窗仍打开就原地重建一次，让新出现的维度可见。 */
    private void reopenFilterDialog() {
        if (filterDialog == null) {
            return;
        }
        filterDialog.setOnDismissListener(null);
        filterDialog.dismiss();
        filterDialog = null;
        filterDialogRoot = null;
        showFilterDialog(ViewRules.specOf(currentKind()));
    }

    /** 排序下拉：字段按类型给定；只有一个字段的页面点击直接切换升降序。 */
    private void showSortDialog(final ViewRules.Spec spec) {
        if (!spec.sortFieldDropdown) {
            sortAscending = !sortAscending;
            refreshContent();
            Ui.toast(MainActivity.this, "排序：" + effectiveSortField(spec)
                    + (sortAscending ? " 升序" : " 降序"));
            return;
        }
        final String[] fields = spec.sortFields;
        String[] items = new String[fields.length + 1];
        for (int i = 0; i < fields.length; i++) {
            items[i] = fields[i] + (fields[i].equals(effectiveSortField(spec)) ? " ✓" : "");
        }
        items[fields.length] = sortAscending ? "切换为降序 ↓" : "切换为升序 ↑";
        new AlertDialog.Builder(this)
                .setTitle("排序")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == fields.length) {
                            sortAscending = !sortAscending;
                        } else {
                            sortField = fields[which];
                        }
                        refreshContent();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 布局下拉：视图模式按类型给定（直播多「列表」），列数可自动或用户指定，均持久化。 */
    private void showLayoutDialog(final ViewRules.Spec spec) {
        final List<String> labels = new ArrayList<String>();
        final List<Integer> kinds = new ArrayList<Integer>(); // 1=视图 2=列数
        final List<Integer> values = new ArrayList<Integer>();
        int curView = savedView();
        int curCols = savedColumns();
        for (int i = 0; i < spec.layoutViews.length; i++) {
            int v = spec.layoutViews[i];
            String prefix = "";
            if (spec.viewModeSwitch) {
                prefix = v == ViewRules.VIEW_LIST ? "[列表] " : "[海报墙] ";
            }
            labels.add(prefix + ViewRules.viewLabel(v) + (v == curView ? " ✓" : ""));
            kinds.add(Integer.valueOf(1));
            values.add(Integer.valueOf(v));
        }
        List<Integer> choices = ViewRules.columnChoices();
        for (int i = 0; i < choices.size(); i++) {
            int c = choices.get(i).intValue();
            String text = c == ViewRules.COLUMNS_AUTO
                    ? "列数：自动（按屏幕 "
                            + ViewRules.columnsFor(contentWidthDp(), curView, 0) + " 列）"
                    : "列数：" + c + " 列";
            labels.add(text + (c == curCols ? " ✓" : ""));
            kinds.add(Integer.valueOf(2));
            values.add(Integer.valueOf(c));
        }
        new AlertDialog.Builder(this)
                .setTitle("布局")
                .setItems(labels.toArray(new String[labels.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                int v = values.get(which).intValue();
                                if (kinds.get(which).intValue() == 1) {
                                    saveView(v);
                                } else {
                                    saveColumns(v);
                                }
                                refreshContent();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 媒体库行（拼接海报） ----------------

    private void addLibraryRow() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (libraries.isEmpty()) {
            // mdb/list 失败时也不伪造一个「媒体库」卡，给可点的重试提示即可
            TextView hint = new TextView(this);
            hint.setText("媒体库列表加载失败，点击重试");
            hint.setTextSize(16);
            hint.setTextColor(getResources().getColor(R.color.text_muted));
            hint.setBackgroundResource(R.drawable.filter_btn_selector);
            hint.setPadding(16, 48, 16, 48);
            hint.setFocusable(true);
            hint.setClickable(true);
            hint.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    loadData(currentCategory);
                }
            });
            FocusEffects.attach(hint);
            row.addView(hint);
        } else {
            // 网页端：每个媒体库一张四宫格拼图卡，新增库即时出现在这里
            int gap = (int) (ViewRules.GRID_GAP_DP * getResources().getDisplayMetrics().density);
            for (int i = 0; i < libraries.size(); i++) {
                Library lib = libraries.get(i);
                View card = buildLibraryCard(lib, "lib:" + lib.guid);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.rightMargin = i + 1 < libraries.size() ? gap : 0;
                row.addView(card, lp);
            }
        }
        hsv.addView(row);
        contentList.addView(hsv);
    }

    private View buildLibraryCard(final Library lib, final String key) {
        View card = LayoutInflater.from(this).inflate(R.layout.card_library, contentList, false);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selectCategory(key);
            }
        });
        FocusEffects.attach(card);
        ImageView[] imgs = {
                (ImageView) card.findViewById(R.id.lib_img1),
                (ImageView) card.findViewById(R.id.lib_img2),
                (ImageView) card.findViewById(R.id.lib_img3),
                (ImageView) card.findViewById(R.id.lib_img4)
        };
        // 优先用 mdb/list 返回的库海报；不足时从该库条目里补齐
        List<String> paths = new ArrayList<String>(lib.posters);
        List<MediaItem> inLib = filterByLibrary(allItems, lib.guid);
        for (int i = 0; i < inLib.size() && paths.size() < imgs.length; i++) {
            String p = inLib.get(i).posterPath;
            if (p != null && p.length() > 0 && !paths.contains(p)) {
                paths.add(p);
            }
        }
        for (int i = 0; i < imgs.length && i < paths.size(); i++) {
            imageLoader.load(api.imageUrl(cfg, paths.get(i), 300), imgs[i], api.authorizationHeader(cfg));
        }
        ((TextView) card.findViewById(R.id.lib_name)).setText(lib.name);
        return card;
    }

    // ---------------- 继续观看行 ----------------

    private void addContinueRow(List<MediaItem> cont) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        float density = getResources().getDisplayMetrics().density;
        int gap = (int) (ViewRules.GRID_GAP_DP * density);
        // 进度条宽度 = 卡片宽度 - 左右各 8dp 内边距
        int maxPx = Math.max(1, getResources().getDimensionPixelSize(R.dimen.continue_w)
                - (int) (16f * density));
        for (int i = 0; i < cont.size(); i++) {
            final MediaItem item = cont.get(i);
            View card = LayoutInflater.from(this).inflate(R.layout.card_continue, contentList, false);
            ImageView img = (ImageView) card.findViewById(R.id.continue_image);
            imageLoader.load(api.imageUrl(cfg, item.posterPath, 640), img, api.authorizationHeader(cfg));
            String sub = "";
            if (item.seasonCount > 0 || item.episodeCount > 0) {
                sub = getString(R.string.season_label, item.seasonCount) + " · "
                        + getString(R.string.episode_label, item.episodeCount);
            }
            ((TextView) card.findViewById(R.id.continue_title)).setText(item.title);
            ((TextView) card.findViewById(R.id.continue_subtitle)).setText(sub);
            View progress = card.findViewById(R.id.continue_progress);
            // 父容器是 FrameLayout，不支持 weight；用固定像素宽度表达百分比
            int fillPx = maxPx * item.progressPercent / 100;
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    fillPx, FrameLayout.LayoutParams.MATCH_PARENT);
            progress.setLayoutParams(p);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 继续观看是「接着上次看」的入口：直接起播并续到 play/list 的 ts，
                    // 不再先进详情页。播放器按 item.resumeSeconds 定位。
                    openPlayer(item);
                }
            });
            FocusEffects.attach(card);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.rightMargin = i + 1 < cont.size() ? gap : 0;
            row.addView(card, lp);
        }
        hsv.addView(row);
        contentList.addView(hsv);
    }

    // ---------------- 海报行 / 网格 ----------------

    private void addPosterRow(List<MediaItem> items) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 与横版行共用同一套间距常量（GRID_GAP_DP），竖版海报不再紧贴
        int gap = (int) (ViewRules.GRID_GAP_DP * getResources().getDisplayMetrics().density);
        for (int i = 0; i < items.size(); i++) {
            View card = buildPosterCard(items.get(i));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.rightMargin = i + 1 < items.size() ? gap : 0;
            row.addView(card, lp);
        }
        if (items.isEmpty()) {
            row.addView(buildEmptyView());
        }
        hsv.addView(row);
        contentList.addView(hsv);
    }

    private void addPosterGrid(List<MediaItem> items) {
        // 列数按屏幕可用宽度（或用户指定）确定，再把宽度均分给每列，让整行铺满
        int view = savedView();
        int columns = ViewRules.columnsFor(contentWidthDp(), view, savedColumns());
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density > 0 ? dm.density : 1f;
        // 列间与行间用同一个留白值，避免卡片上下贴在一起
        int gap = (int) (ViewRules.GRID_GAP_DP * density);
        int available = contentList.getWidth()
                - contentList.getPaddingLeft() - contentList.getPaddingRight();
        if (available <= 0) {
            available = dm.widthPixels - (officialRenderer != null
                    ? officialRenderer.s(160) : (int) (200f * density))
                    - contentList.getPaddingLeft() - contentList.getPaddingRight();
        }
        int cardW = ViewRules.cardWidthPx(available, columns, gap);
        gridRows.clear();

        if (view == ViewRules.VIEW_LIST) {
            for (int i = 0; i < items.size(); i++) {
                View rowView = buildListRow(items.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = gap;
                rowView.setLayoutParams(lp);
                contentList.addView(rowView);
                List<View> one = new ArrayList<View>();
                one.add(rowView);
                gridRows.add(one);
            }
        } else {
            for (int i = 0; i < items.size(); i += columns) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                List<View> rowCards = new ArrayList<View>();
                for (int j = 0; j < columns && i + j < items.size(); j++) {
                    View c = view == ViewRules.VIEW_HORIZONTAL
                            ? buildHorizontalCard(items.get(i + j), cardW)
                            : buildPosterCard(items.get(i + j), cardW);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            cardW, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = (j + 1 < columns && i + j + 1 < items.size()) ? gap : 0;
                    c.setLayoutParams(lp);
                    row.addView(c);
                    rowCards.add(c);
                }
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = gap; // 行间距：此前完全没有，卡片上下紧贴
                contentList.addView(row, rowLp);
                if (!rowCards.isEmpty()) {
                    gridRows.add(rowCards);
                }
            }
        }
        if (items.isEmpty()) {
            contentList.addView(buildEmptyView());
        } else {
            bindGridFocus();
        }
    }

    /**
     * 网格方向键焦点链（对齐官方实测）：
     * 行内左右相邻，行尾按右换到下一行首、行首按左回到上一行尾；
     * 上下行按同列移动；首行向上进筛选栏，末行向下自环。
     * 这样可避免系统默认搜索在长列表里把焦点甩到别的行、甚至滚出视口。
     */
    private void bindGridFocus() {
        for (int r = 0; r < gridRows.size(); r++) {
            List<View> row = gridRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                // card_poster 的根布局带 @+id/card_root，多张卡片会共用同一个 id，
                // 直接把 setId 当成「没 id 才分配」会让焦点链全部指回第一张卡。
                final View card = row.get(i);
                card.setId(focusIdCounter++);
                card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    public void onFocusChange(View v, boolean hasFocus) {
                        FocusEffects.focusScale(v, hasFocus);
                        if (hasFocus) {
                            centerGridFocus(v);
                        }
                    }
                });
            }
        }
        for (int r = 0; r < gridRows.size(); r++) {
            List<View> row = gridRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                View v = row.get(i);
                View left;
                if (i > 0) {
                    left = row.get(i - 1);
                } else if (r > 0) {
                    List<View> prev = gridRows.get(r - 1);
                    left = prev.get(prev.size() - 1);
                } else {
                    left = v;
                }
                View right;
                if (i + 1 < row.size()) {
                    right = row.get(i + 1);
                } else if (r + 1 < gridRows.size()) {
                    right = gridRows.get(r + 1).get(0);
                } else {
                    right = v;
                }
                v.setNextFocusLeftId(left.getId());
                v.setNextFocusRightId(right.getId());
            }
        }
        View barFirst = filterBarViews.isEmpty() ? null : filterBarViews.get(0);
        for (int r = 0; r < gridRows.size(); r++) {
            List<View> row = gridRows.get(r);
            List<View> above = r > 0 ? gridRows.get(r - 1) : null;
            List<View> below = r + 1 < gridRows.size() ? gridRows.get(r + 1) : null;
            for (int i = 0; i < row.size(); i++) {
                View v = row.get(i);
                if (above != null) {
                    v.setNextFocusUpId(above.get(Math.min(i, above.size() - 1)).getId());
                } else if (barFirst != null) {
                    v.setNextFocusUpId(barFirst.getId());
                }
                if (below != null) {
                    v.setNextFocusDownId(below.get(Math.min(i, below.size() - 1)).getId());
                } else {
                    v.setNextFocusDownId(v.getId());
                }
            }
        }
        if (barFirst != null && !gridRows.isEmpty()) {
            int firstId = gridRows.get(0).get(0).getId();
            for (int i = 0; i < filterBarViews.size(); i++) {
                filterBarViews.get(i).setNextFocusDownId(firstId);
            }
        }
    }

    /** 焦点卡纵向落点：只在卡片贴近视口边缘时重定位到 45% 处，避免下行后焦点一直卡在屏幕最底部。 */
    private void centerGridFocus(final View focused) {
        contentScroll.post(new Runnable() {
            public void run() {
                int viewport = contentScroll.getHeight();
                if (viewport <= 0 || contentList.getHeight() <= 0) {
                    return;
                }
                int centerY = focused.getHeight() / 2;
                View current = focused;
                while (current != contentList && current.getParent() instanceof View) {
                    centerY += current.getTop();
                    current = (View) current.getParent();
                }
                if (current != contentList) {
                    return;
                }
                int scrollY = contentScroll.getScrollY();
                int top = centerY - focused.getHeight() / 2;
                int bottom = top + focused.getHeight();
                if (top >= scrollY + viewport / 20 && bottom <= scrollY + viewport * 17 / 20) {
                    return;
                }
                int target = centerY - Math.round(viewport * 0.45f);
                int max = Math.max(0, contentList.getHeight() - viewport);
                target = Math.max(0, Math.min(target, max));
                if (Math.abs(scrollY - target) > 1) {
                    contentScroll.smoothScrollTo(0, target);
                }
            }
        });
    }

    /** 横幅海报视图（16:9 横卡），仅影视类页面的布局下拉里出现。 */
    private View buildHorizontalCard(final MediaItem item, int widthPx) {
        float d = getResources().getDisplayMetrics().density;
        int w = widthPx > 0 ? widthPx : (int) (320f * d);
        int pad = (int) (8f * d);
        int imgW = Math.max(1, w - 2 * pad);
        int h = (int) (imgW * 9f / 16f);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.poster_card_selector);
        card.setPadding(8, 8, 8, 8);
        card.setFocusable(true);
        card.setClickable(true);
        ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        // 切换卡片方向时不再把竖版海报 centerCrop 成横条：
        // 优先用 16:9 横版剧照；没有剧照就完整显示竖版封面（两侧留深色底）。
        String backdrop = item.backdropPath == null ? "" : item.backdropPath;
        boolean hasBackdrop = backdrop.length() > 0;
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFF1A1A1A);
        img.setContentDescription(hasBackdrop ? "横幅剧照" : "完整封面");
        imageLoader.load(api.imageUrl(cfg, hasBackdrop ? backdrop : item.posterPath, 640),
                img, api.authorizationHeader(cfg));
        card.addView(wrapWithBadges(img, item,
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setTextColor(getResources().getColor(R.color.text));
        card.addView(title);
        TextView sub = new TextView(this);
        sub.setText(posterSubtitle(item));
        sub.setTextSize(13);
        sub.setTextColor(getResources().getColor(R.color.text_muted));
        card.addView(sub);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openItem(item);
            }
        });
        FocusEffects.attach(card);
        return card;
    }

    /** 列表视图（直播页「海报墙 / 列表」切换后的单行条目）。 */
    private View buildListRow(final MediaItem item) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setBackgroundResource(R.drawable.poster_card_selector);
        row.setPadding(12, 8, 12, 8);
        row.setFocusable(true);
        row.setClickable(true);
        ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams((int) (96f * d), (int) (54f * d)));
        // 直播台标多为宽幅图片，适配显示完整台标而不是裁掉上下
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFF1A1A1A);
        img.setContentDescription("完整台标");
        imageLoader.load(api.imageUrl(cfg, item.posterPath, 240), img, api.authorizationHeader(cfg));
        int thumbW = (int) (96f * d);
        int thumbH = (int) (54f * d);
        row.addView(wrapWithBadges(img, item, thumbW, thumbH));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding((int) (16f * d), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextSize(17);
        title.setTextColor(getResources().getColor(R.color.text));
        text.addView(title);
        TextView sub = new TextView(this);
        sub.setText(posterSubtitle(item));
        sub.setTextSize(13);
        sub.setTextColor(getResources().getColor(R.color.text_muted));
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openItem(item);
            }
        });
        FocusEffects.attach(row);
        return row;
    }

    /** 按网页端规则在封面右下角渲染最多 2 个清晰度角标；设置里关掉分辨率时不画。 */
    private void addQualityBadges(LinearLayout box, MediaItem item) {
        box.removeAllViews();
        if (!displayPref("resolution", true)) {
            return;
        }
        List<String> badges = QualityBadges.from(item.resolutions);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < badges.size(); i++) {
            String label = badges.get(i);
            TextView b = new TextView(this);
            b.setText(label);
            b.setTextSize(11);
            boolean inverted = QualityBadges.inverted(label);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setColor(inverted ? 0xFFF5F5F5 : 0xDD101010);
            gd.setCornerRadius(4f * d);
            b.setBackground(gd);
            b.setTextColor(inverted ? 0xFF101010 : 0xFFF5F5F5);
            b.setPadding((int) (7f * d), (int) (2f * d), (int) (7f * d), (int) (2f * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i > 0 ? (int) (5f * d) : 0;
            box.addView(b, lp);
        }
        box.setVisibility(badges.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 横幅/列表视图给封面图套一层容器，把角标叠在右下角。 */
    private View wrapWithBadges(ImageView img, MediaItem item, int widthPx, int heightPx) {
        FrameLayout box = new FrameLayout(this);
        box.setLayoutParams(new LinearLayout.LayoutParams(widthPx, heightPx));
        box.addView(img, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.bottomMargin = (int) (6f * getResources().getDisplayMetrics().density);
        lp.rightMargin = lp.bottomMargin;
        box.addView(badges, lp);
        addQualityBadges(badges, item);
        return box;
    }

    private String posterSubtitle(MediaItem item) {
        if (item.seasonCount > 0) {
            return getString(R.string.seasons_label, item.seasonCount);
        }
        if (item.year != null && item.year.length() >= 4) {
            return item.year.substring(0, 4);
        }
        return "";
    }

    /** 首页横向海报行用固定尺寸；网格页由 addPosterGrid 传入均分后的列宽。 */
    private View buildPosterCard(final MediaItem item) {
        return buildPosterCard(item, -1);
    }

    private View buildPosterCard(final MediaItem item, int widthPx) {
        View card = LayoutInflater.from(this).inflate(R.layout.card_poster, contentList, false);
        if (widthPx > 0) {
            // 卡片有 8dp 内边距，海报实际宽度要扣掉；否则 2:3 比例偏大导致轻微裁切
            float d = getResources().getDisplayMetrics().density;
            int imgW = Math.max(1, widthPx - (int) (16f * d));
            View frame = (View) ((ImageView) card.findViewById(R.id.card_image)).getParent();
            ViewGroup.LayoutParams fp = frame.getLayoutParams();
            fp.height = (int) (imgW * 3f / 2f);
            frame.setLayoutParams(fp);
        }
        ImageView img = (ImageView) card.findViewById(R.id.card_image);
        // 竖版海报按 2:3 完整填充；直播台标等横图也不裁掉左右，居中留深色底
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFF1A1A1A);
        img.setContentDescription("完整竖版封面");
        imageLoader.load(api.imageUrl(cfg, item.posterPath, 400), img, api.authorizationHeader(cfg));
        ((TextView) card.findViewById(R.id.card_title)).setText(item.title);
        // 网页端剧集海报副标题是「共 N 季」，集数要进季详情才显示
        ((TextView) card.findViewById(R.id.card_subtitle)).setText(posterSubtitle(item));
        TextView rating = (TextView) card.findViewById(R.id.card_rating);
        if (item.voteAverage > 0 && displayPref("score", true)) {
            rating.setVisibility(View.VISIBLE);
            rating.setText(String.format(java.util.Locale.US, "%.1f", item.voteAverage));
        }
        addQualityBadges((LinearLayout) card.findViewById(R.id.quality_badges), item);
        addWatchedMark(img, item);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openItem(item);
            }
        });
        FocusEffects.attach(card);
        return card;
    }

    /** 设置页「外观」里的封面显示开关：评分 / 分辨率 / 已观看标记。 */
    private boolean displayPref(String key, boolean def) {
        return getSharedPreferences("official_settings", MODE_PRIVATE).getBoolean(key, def);
    }

    /** 设置里开启「已观看」标记后，在封面左下角画 ic_watched_filled 图标；关闭则不画。 */
    private void addWatchedMark(ImageView img, MediaItem item) {
        if (!item.watched) {
            return;
        }
        if (!displayPref("watched", false)) {
            return;
        }
        if (!(img.getParent() instanceof FrameLayout)) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_watched_filled);
        mark.setColorFilter(0xFF15171A);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(0xE6FFFFFF);
        mark.setBackground(gd);
        int size = (int) (22f * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin = (int) (6f * d);
        lp.bottomMargin = (int) (6f * d);
        ((FrameLayout) img.getParent()).addView(mark, lp);
    }

    private View buildEmptyView() {
        TextView t = new TextView(this);
        t.setText(R.string.no_data);
        t.setTextSize(20);
        t.setTextColor(getResources().getColor(R.color.text_muted));
        t.setPadding(0, 80, 0, 80);
        return t;
    }

    // ---------------- 收藏 / 媒体库 ----------------

    private String favoriteTab = "全部";

    private void renderFavorite() {
        if (officialRenderer != null) {
            officialRenderer.prepareList();
        }
        contentList.removeAllViews();
        filterBarViews.clear();
        addSection(R.string.nav_favorite, false, null);
        // 网页端收藏页 Tab：全部/电影/电视节目/电视直播/单集/人物
        addFavoriteTabs();
        List<MediaItem> fav = new ArrayList<MediaItem>();
        for (int i = 0; i < allItems.size(); i++) {
            MediaItem it = allItems.get(i);
            if (!it.favorite) {
                continue;
            }
            String t = it.mediaType == null ? "" : it.mediaType.toLowerCase();
            boolean match = "全部".equals(favoriteTab)
                    || ("电影".equals(favoriteTab) && t.indexOf("movie") >= 0)
                    || ("电视节目".equals(favoriteTab) && t.indexOf("tv") >= 0)
                    || ("电视直播".equals(favoriteTab) && t.indexOf("live") >= 0)
                    || ("单集".equals(favoriteTab) && t.indexOf("episode") >= 0);
            if (match) {
                fav.add(it);
            }
        }
        ViewRules.Spec spec = ViewRules.specOf(ViewRules.Kind.FAVORITE);
        fav = FilterEngine.apply(fav, filterSelection, currentYear());
        FilterEngine.sort(fav, effectiveSortField(spec), sortAscending);
        addFilterBar(fav.size(), spec); // 收藏页 spec.showLayout=false，网页端也没有布局按钮
        addPosterGrid(fav);
        if (allItems.isEmpty()) {
            loadData("favorite");
        }
    }

    private void addFavoriteTabs() {
        String[] tabs = {"全部", "电影", "电视节目", "电视直播", "单集", "人物"};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < tabs.length; i++) {
            final String tab = tabs[i];
            TextView t = new TextView(this);
            t.setText(tab);
            t.setTextSize(17);
            t.setPadding(18, 10, 18, 10);
            boolean active = tab.equals(favoriteTab);
            t.setTextColor(getResources().getColor(active ? R.color.web_blue : R.color.text_muted));
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    favoriteTab = tab;
                    renderFavorite();
                }
            });
            FocusEffects.attach(t);
            row.addView(t);
        }
        contentList.addView(row);
    }

    private void addSection(int labelRes, boolean more, View.OnClickListener moreClick) {
        addSection(getString(labelRes), more, moreClick);
    }

    // ---------------- 加载 / 错误状态 ----------------

    private void showLoading(String msg) {
        if (officialRenderer != null) {
            officialRenderer.showSkeleton();
            return;
        }
        contentList.removeAllViews();
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(20);
        t.setTextColor(getResources().getColor(R.color.accent));
        t.setPadding(0, 100, 0, 0);
        contentList.addView(t);
    }

    private void showError(String msg) {
        if (officialRenderer != null) {
            officialRenderer.prepareList();
        }
        contentList.removeAllViews();
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(20);
        t.setTextColor(getResources().getColor(R.color.danger));
        t.setPadding(0, 100, 0, 0);
        contentList.addView(t);
    }

    // ---------------- 搜索 ----------------

    private void showSearchDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 24, 32, 12);
        final EditText input = new EditText(this);
        input.setHint(R.string.search_hint);
        input.setTextSize(20);
        input.setSingleLine(true);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.search_hint)
                .setView(box)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String q = input.getText().toString().trim();
                        showSearchResults(q);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSearchResults(String query) {
        searchQuery = query;
        searchMode = true;
        closeFilterDialogQuietly();
        filterSelection.clear();
        sortField = null;
        sortAscending = false;
        renderSearchResults();
    }

    private void renderSearchResults() {
        List<MediaItem> result = new ArrayList<MediaItem>();
        if (searchQuery.length() > 0) {
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i).title.toLowerCase().contains(searchQuery.toLowerCase())) {
                    result.add(allItems.get(i));
                }
            }
        }
        currentCategory = "home";
        updateNavSelection();
        topTitle.setText(R.string.search_hint);
        if (officialRenderer != null) {
            officialRenderer.prepareList();
        }
        contentList.removeAllViews();
        filterBarViews.clear();
        ViewRules.Spec spec = ViewRules.specOf(ViewRules.Kind.SEARCH);
        result = FilterEngine.apply(result, filterSelection, currentYear());
        FilterEngine.sort(result, effectiveSortField(spec), sortAscending);
        addSection("搜索结果", false, null);
        addFilterBar(result.size(), spec);
        addPosterGrid(result);
    }

    // ---------------- 跳转 ----------------

    /** 网页端：影视卡片进详情页；直播卡片点击/确认直接起播（/v/live/&lt;guid&gt;）。 */
    void openItem(MediaItem item) {
        String t = item == null || item.mediaType == null ? "" : item.mediaType.toLowerCase();
        if (t.indexOf("live") >= 0 || t.indexOf("channel") >= 0) {
            openPlayer(item);
        } else {
            openDetail(item);
        }
    }

    private void openDetail(MediaItem item) {
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < 500L) {
            return;
        }
        lastOpenAt = now;
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("item", com.juying.medianest.core.util.Json.stringify(item.toJson()));
        startActivity(i);
    }

    void openPlayer(MediaItem item) {
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < 600L) {
            return;
        }
        lastOpenAt = now;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("item", com.juying.medianest.core.util.Json.stringify(item.toJson()));
        store.saveLastItem(com.juying.medianest.core.util.Json.stringify(item.toJson()));
        startActivity(i);
    }

    // ---------------- 遥控器 ----------------

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TvKeys.isMenu(keyCode)) {
            startActivity(new Intent(this, OfficialScanDeviceActivity.class));
            return true;
        }
        if (TvKeys.isBack(keyCode)) {
            // 状态流转：弹窗（含筛选弹窗）由系统先消费；兜底再检查一次，
            // 只有回到普通列表态才弹退出确认，避免误退出。
            if (filterDialog != null) {
                dismissFilterDialog();
                return true;
            }
            if (searchMode || !"home".equals(currentCategory)) {
                selectCategory("home");
                return true;
            }
            Ui.confirm(this, "退出", "确定退出聚映 MediaNest？", new Ui.Action() {
                public void run() {
                    finish();
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void onDestroy() {
        super.onDestroy();
    }
}
