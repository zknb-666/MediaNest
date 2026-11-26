package com.juying.medianest.core.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页端「筛选 / 排序 / 布局」按页面类型区分的配置与自适应列数计算。
 *
 * <p>实测依据（trimemedia-web，1280x720 16:9，2026-11-19）：
 * <ul>
 *   <li>混合库 / 全部：筛选 9 组，排序「添加日期 ⇅ ▾」，布局「竖幅海报 / 横幅海报」。</li>
 *   <li>电影 / 电视节目：无「影视分类」组，其余 8 组，排序「添加日期」。</li>
 *   <li>电视直播 / IPTV 库：筛选只有「类型」一组，排序「标题」（无字段下拉），
 *       布局额外有「海报墙 / 列表」视图切换。</li>
 *   <li>其他视频库：只有分辨率 / 动态范围 / 音频规格 / 是否已观看，排序「标题」。</li>
 *   <li>收藏页：Tab 6 个，排序「收藏时间」，<b>没有布局按钮</b>。</li>
 *   <li>所有页面右侧都有「共 N 项」。</li>
 * </ul>
 *
 * <p>网页端各库下拉之所以不同，是因为 {@code GET /v/api/v1/tag/list?&lt;scope&gt;}
 * 按范围返回不同的可用维度（genres / resolutions / color_range / audio_type /
 * locate / decades / recognition_status），空维度不渲染对应分组。
 */
public final class ViewRules {

    public enum Kind { MIXED, MOVIE, TV, LIVE, VIDEO, FAVORITE, SEARCH }

    // ---- 布局视图 ----
    public static final int VIEW_VERTICAL = 0;
    public static final int VIEW_HORIZONTAL = 1;
    public static final int VIEW_LIST = 2;
    /** 用户未指定列数：按屏幕可用宽度自动计算。 */
    public static final int COLUMNS_AUTO = 0;
    /** 网格列间/行间留白（dp）：TV 远距离观看需要比网页端更宽松的间距。 */
    public static final int GRID_GAP_DP = 20;

    // ---- 筛选维度 id（与 tag/list 返回的字段名一致）----
    public static final String G_CATEGORY = "category";
    public static final String G_GENRE = "genres";
    public static final String G_RESOLUTION = "resolutions";
    public static final String G_COLOR = "color_range";
    public static final String G_AUDIO = "audio_type";
    public static final String G_COUNTRY = "locate";
    public static final String G_YEAR = "decades";
    public static final String G_MATCH = "recognition_status";
    public static final String G_WATCHED = "watched";

    public static final String ALL = "全部";

    // ---- 排序字段 ----
    public static final String SORT_ADDED = "添加日期";
    public static final String SORT_RELEASE = "发行日期";
    public static final String SORT_TITLE = "标题";
    public static final String SORT_RATING = "评分";
    public static final String SORT_FAVORITE = "收藏时间";

    private static final String[] GROUPS_MIXED = {
            G_CATEGORY, G_GENRE, G_RESOLUTION, G_COLOR, G_AUDIO,
            G_COUNTRY, G_YEAR, G_MATCH, G_WATCHED};
    private static final String[] GROUPS_SINGLE = {
            G_GENRE, G_RESOLUTION, G_COLOR, G_AUDIO,
            G_COUNTRY, G_YEAR, G_MATCH, G_WATCHED};
    private static final String[] GROUPS_LIVE = {G_GENRE};
    private static final String[] GROUPS_VIDEO = {
            G_RESOLUTION, G_COLOR, G_AUDIO, G_WATCHED};

    private static final String[] SORTS_MEDIA = {
            SORT_ADDED, SORT_RELEASE, SORT_TITLE, SORT_RATING};
    private static final String[] SORTS_TITLE_ONLY = {SORT_TITLE};
    private static final String[] SORTS_FAVORITE = {
            SORT_FAVORITE, SORT_ADDED, SORT_RELEASE, SORT_TITLE, SORT_RATING};

    private static final int[] VIEWS_POSTER = {VIEW_VERTICAL, VIEW_HORIZONTAL};
    private static final int[] VIEWS_LIVE = {VIEW_VERTICAL, VIEW_HORIZONTAL, VIEW_LIST};
    private static final int[] VIEWS_NONE = {};

    /** 某个页面类型的完整筛选栏配置。 */
    public static final class Spec {
        public final Kind kind;
        public final boolean showFilter;
        public final boolean showSort;
        public final boolean showLayout;
        public final boolean showCount;
        /** 排序按钮右侧是否有独立的字段下拉（直播/其他视频只有一个字段）。 */
        public final boolean sortFieldDropdown;
        public final String defaultSort;
        public final String[] sortFields;
        public final String[] filterGroups;
        public final int[] layoutViews;
        /** 布局下拉里是否显示「海报墙 / 列表」视图模式切换。 */
        public final boolean viewModeSwitch;

        private Spec(Kind kind, String defaultSort, String[] sortFields,
                     boolean sortFieldDropdown, String[] filterGroups,
                     boolean showLayout, int[] layoutViews, boolean viewModeSwitch) {
            this.kind = kind;
            this.showFilter = true;
            this.showSort = true;
            this.showLayout = showLayout;
            this.showCount = true;
            this.sortFieldDropdown = sortFieldDropdown;
            this.defaultSort = defaultSort;
            this.sortFields = sortFields;
            this.filterGroups = filterGroups;
            this.layoutViews = layoutViews;
            this.viewModeSwitch = viewModeSwitch;
        }
    }

    /** key 是 App 内部的分类 key（"all"/"movie"/"lib:&lt;guid&gt;"…），category 是库的 category 字段。 */
    public static Kind kindOf(String key, String libraryCategory) {
        if (key == null) {
            return Kind.MIXED;
        }
        if ("favorite".equals(key)) {
            return Kind.FAVORITE;
        }
        if ("search".equals(key)) {
            return Kind.SEARCH;
        }
        if ("live".equals(key)) {
            return Kind.LIVE;
        }
        if ("movie".equals(key)) {
            return Kind.MOVIE;
        }
        if ("tv".equals(key)) {
            return Kind.TV;
        }
        if ("other".equals(key)) {
            return Kind.VIDEO;
        }
        if (key.startsWith("lib:")) {
            return kindOfLibrary(libraryCategory);
        }
        return Kind.MIXED; // home / all
    }

    /** mediadb/list 的 category：Mix / Movie / TV / Others / IPTV。 */
    public static Kind kindOfLibrary(String category) {
        if (category == null) {
            return Kind.MIXED;
        }
        String s = category.toLowerCase(java.util.Locale.US);
        if (s.indexOf("iptv") >= 0 || s.indexOf("live") >= 0) {
            return Kind.LIVE;
        }
        if (s.indexOf("other") >= 0 || s.indexOf("video") >= 0) {
            return Kind.VIDEO;
        }
        if ("movie".equals(s)) {
            return Kind.MOVIE;
        }
        if ("tv".equals(s)) {
            return Kind.TV;
        }
        return Kind.MIXED; // Mix / 混合影片
    }

    public static Spec specOf(Kind kind) {
        if (kind == null) {
            kind = Kind.MIXED;
        }
        switch (kind) {
            case FAVORITE:
                return new Spec(kind, SORT_FAVORITE, SORTS_FAVORITE, true,
                        GROUPS_MIXED, false, VIEWS_NONE, false);
            case LIVE:
                return new Spec(kind, SORT_TITLE, SORTS_TITLE_ONLY, false,
                        GROUPS_LIVE, true, VIEWS_LIVE, true);
            case VIDEO:
                return new Spec(kind, SORT_TITLE, SORTS_TITLE_ONLY, false,
                        GROUPS_VIDEO, true, VIEWS_POSTER, false);
            case MOVIE:
            case TV:
                return new Spec(kind, SORT_ADDED, SORTS_MEDIA, true,
                        GROUPS_SINGLE, true, VIEWS_POSTER, false);
            case SEARCH:
            case MIXED:
            default:
                return new Spec(kind, SORT_ADDED, SORTS_MEDIA, true,
                        GROUPS_MIXED, true, VIEWS_POSTER, false);
        }
    }

    public static String groupLabel(String id) {
        if (G_CATEGORY.equals(id)) return "影视分类";
        if (G_GENRE.equals(id)) return "类型";
        if (G_RESOLUTION.equals(id)) return "分辨率";
        if (G_COLOR.equals(id)) return "视频动态范围";
        if (G_AUDIO.equals(id)) return "音频规格";
        if (G_COUNTRY.equals(id)) return "国家/地区";
        if (G_YEAR.equals(id)) return "发行年份";
        if (G_MATCH.equals(id)) return "匹配状态";
        if (G_WATCHED.equals(id)) return "是否已观看";
        return id;
    }

    public static String viewLabel(int view) {
        if (view == VIEW_HORIZONTAL) return "横幅海报";
        if (view == VIEW_LIST) return "列表";
        return "竖幅海报";
    }

    /** 单个卡片的目标宽度（dp），列数由可用宽度除以它自动得出。 */
    public static int cardWidthDp(int view) {
        if (view == VIEW_HORIZONTAL) return 320;
        if (view == VIEW_LIST) return Integer.MAX_VALUE;
        return 170;
    }

    /**
     * 列数 = 用户选择优先；未选择时按屏幕可用宽度 / 卡片目标宽度自动计算。
     * 列表视图固定 1 列。返回值钳制在 [2, 8]（列表视图除外）。
     */
    public static int columnsFor(int contentWidthDp, int view, int userColumns) {
        if (view == VIEW_LIST) {
            return 1;
        }
        if (userColumns > 0) {
            return clampColumns(userColumns);
        }
        int target = cardWidthDp(view);
        if (contentWidthDp <= 0 || target <= 0) {
            return 2;
        }
        return clampColumns(contentWidthDp / target);
    }

    public static int clampColumns(int n) {
        if (n < 2) return 2;
        if (n > 8) return 8;
        return n;
    }

    /**
     * 列数确定后，把内容区可用宽度均分给每列，保证整行铺满且列间只留 gapPx 间距。
     * 返回单张卡片宽度（px）；返回值满足 columns*w + (columns-1)*gap <= availablePx。
     */
    public static int cardWidthPx(int availablePx, int columns, int gapPx) {
        int c = columns < 1 ? 1 : columns;
        int g = gapPx < 0 ? 0 : gapPx;
        int w = (availablePx - (c - 1) * g) / c;
        return w < 1 ? 1 : w;
    }

    /** 便于布局下拉展示的可选列数（自动 + 2..8）。 */
    public static List<Integer> columnChoices() {
        List<Integer> out = new ArrayList<Integer>();
        out.add(Integer.valueOf(COLUMNS_AUTO));
        for (int i = 2; i <= 8; i++) {
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    /** tag/list 的 scope 参数：按类型/库构造查询串。 */
    public static String tagScope(Kind kind, String categoryKey, boolean favorite) {
        if (favorite) {
            return "is_favorite=1";
        }
        if (categoryKey != null && categoryKey.startsWith("lib:")) {
            return "ancestor_guid=" + categoryKey.substring(4);
        }
        if (kind == Kind.LIVE) return "type=LiveChannel";
        if (kind == Kind.MOVIE) return "type=Movie";
        if (kind == Kind.TV) return "type=TV";
        if (kind == Kind.VIDEO) return "type=Video";
        return "";
    }

    private ViewRules() {
    }
}
