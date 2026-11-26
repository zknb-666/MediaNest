package com.juying.medianest.core.policy;

import com.juying.medianest.core.model.MediaItem;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 筛选面板：按 {@link ViewRules.Spec} 生成分组、从服务端 tag/list + 本地条目
 * 汇总出可用取值，并在本地真实执行过滤与排序。
 *
 * <p>网页端的行为是「哪个维度在当前范围里没有取值，就不渲染那一组」，所以这里
 * 也会跳过空组 —— 这正是各媒体库下拉项不一致的原因。
 */
public final class FilterEngine {

    /** tag/list 返回的可用取值，值已由 tag/genres、tag/iso3166 映射为中文显示文本。 */
    public static final class TagSet {
        private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();

        public void put(String dimension, List<String> vals) {
            if (dimension == null || vals == null) {
                return;
            }
            values.put(dimension, new ArrayList<String>(vals));
        }

        public List<String> get(String dimension) {
            List<String> v = values.get(dimension);
            return v == null ? Collections.<String>emptyList() : v;
        }

        public Set<String> dimensions() {
            return values.keySet();
        }

        public void putAll(TagSet other) {
            if (other != null) {
                values.putAll(other.values);
            }
        }
    }

    public static final class Group {
        public final String id;
        public final String label;
        /** 不含「全部」；已经过滤空串并排好序。 */
        public final List<String> values;

        public Group(String id, String label, List<String> values) {
            this.id = id;
            this.label = label;
            this.values = values;
        }
    }

    public static List<Group> groups(ViewRules.Kind kind, TagSet tags,
                                     List<MediaItem> items, int currentYear) {
        ViewRules.Spec spec = ViewRules.specOf(kind);
        List<Group> out = new ArrayList<Group>();
        for (int i = 0; i < spec.filterGroups.length; i++) {
            String id = spec.filterGroups[i];
            Set<String> vals = new LinkedHashSet<String>();
            if (tags != null) {
                addAll(vals, tags.get(id));
            }
            if (items != null) {
                for (int k = 0; k < items.size(); k++) {
                    addAll(vals, valuesOf(items.get(k), id, currentYear));
                }
            }
            vals.remove("");
            vals.remove(null);
            if (vals.isEmpty()) {
                continue; // 该范围下没有可用值：与网页端一致，不渲染这一组
            }
            List<String> sorted = new ArrayList<String>(vals);
            Collections.sort(sorted);
            out.add(new Group(id, ViewRules.groupLabel(id), sorted));
        }
        return out;
    }

    /** 条目在某个筛选维度上的取值（可能多值，如一部剧有多个流派）。 */
    public static List<String> valuesOf(MediaItem item, String groupId, int currentYear) {
        List<String> out = new ArrayList<String>();
        if (item == null || groupId == null) {
            return out;
        }
        if (ViewRules.G_CATEGORY.equals(groupId)) {
            out.add(categoryLabel(item.mediaType));
        } else if (ViewRules.G_GENRE.equals(groupId)) {
            addAll(out, item.genres);
        } else if (ViewRules.G_RESOLUTION.equals(groupId)) {
            for (int i = 0; i < item.resolutions.size(); i++) {
                out.add(normalizeResolution(item.resolutions.get(i)));
            }
            if (out.isEmpty() && item.height > 0) {
                out.add(item.height >= 2160 ? "4k" : (item.height >= 1080 ? "1080P" : item.height + "P"));
            }
        } else if (ViewRules.G_COLOR.equals(groupId)) {
            for (int i = 0; i < item.colorRanges.size(); i++) {
                out.add(normalizeColor(item.colorRanges.get(i)));
            }
        } else if (ViewRules.G_AUDIO.equals(groupId)) {
            for (int i = 0; i < item.audioTypes.size(); i++) {
                out.add(normalizeAudio(item.audioTypes.get(i)));
            }
        } else if (ViewRules.G_COUNTRY.equals(groupId)) {
            addAll(out, item.countries);
        } else if (ViewRules.G_YEAR.equals(groupId)) {
            String b = yearBucket(item.year, currentYear);
            if (b.length() > 0) {
                out.add(b);
            }
        } else if (ViewRules.G_MATCH.equals(groupId)) {
            if (item.matchState == 2) out.add("读取 NFO");
            else if (item.matchState == 1) out.add("已匹配");
            else out.add("未匹配");
        } else if (ViewRules.G_WATCHED.equals(groupId)) {
            out.add(item.watched || item.progressPercent >= 95 ? "已观看" : "未观看");
        }
        return out;
    }

    /** value 为「全部」或空时该组不参与过滤。 */
    public static List<MediaItem> apply(List<MediaItem> items,
                                        Map<String, String> selected, int currentYear) {
        List<MediaItem> out = new ArrayList<MediaItem>();
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            boolean keep = true;
            if (selected != null) {
                for (Map.Entry<String, String> e : selected.entrySet()) {
                    String value = e.getValue();
                    if (value == null || value.length() == 0 || ViewRules.ALL.equals(value)) {
                        continue;
                    }
                    if (!valuesOf(item, e.getKey(), currentYear).contains(value)) {
                        keep = false;
                        break;
                    }
                }
            }
            if (keep) {
                out.add(item);
            }
        }
        return out;
    }

    /** 原地排序；添加日期/收藏时间没有对应字段，用服务端原始顺序（降序为默认）。 */
    public static void sort(List<MediaItem> items, final String field, final boolean ascending) {
        if (items == null || items.size() < 2) {
            return;
        }
        final String f = field == null ? ViewRules.SORT_ADDED : field;
        final int sign = ascending ? 1 : -1;
        if (ViewRules.SORT_ADDED.equals(f) || ViewRules.SORT_FAVORITE.equals(f)) {
            if (ascending) {
                Collections.reverse(items);
            }
            return;
        }
        Collections.sort(items, new Comparator<MediaItem>() {
            public int compare(MediaItem a, MediaItem b) {
                int c;
                if (ViewRules.SORT_TITLE.equals(f)) {
                    c = safe(a.title).compareTo(safe(b.title));
                } else if (ViewRules.SORT_RELEASE.equals(f)) {
                    c = safe(a.year).compareTo(safe(b.year));
                } else if (ViewRules.SORT_RATING.equals(f)) {
                    c = Double.compare(a.voteAverage, b.voteAverage);
                } else {
                    c = 0;
                }
                return c * sign;
            }
        });
    }

    public static String yearBucket(String year, int currentYear) {
        int y = leadingYear(year);
        if (y <= 0) {
            return "";
        }
        if (y == currentYear) {
            return "今年";
        }
        if (y >= 2020 && y <= 2029) {
            return "2020年代";
        }
        if (y >= 2010 && y <= 2019) {
            return "2010年代";
        }
        return "其他";
    }

    public static String categoryLabel(String mediaType) {
        String t = mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.US);
        if (t.indexOf("movie") >= 0) return "电影";
        if (t.indexOf("live") >= 0 || t.indexOf("channel") >= 0) return "电视直播";
        if (t.equals("tv") || t.indexOf("series") >= 0) return "电视节目";
        if (t.indexOf("episode") >= 0 || t.indexOf("season") >= 0) return "单集";
        return "其他";
    }

    public static String normalizeResolution(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(java.util.Locale.US);
        if (s.equals("4k") || s.equals("2160p") || s.equals("uhd")) return "4k";
        if (s.equals("1080p") || s.equals("1080i")) return "1080P";
        if (s.equals("720p")) return "720P";
        if (s.equals("480p") || s.equals("sd")) return "480P";
        return raw.trim();
    }

    public static String normalizeColor(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(java.util.Locale.US);
        if (s.indexOf("DOLBY") >= 0) return "Dolby Vision";
        if (s.indexOf("HDR") >= 0) return "HDR";
        if (s.indexOf("SDR") >= 0) return "SDR";
        return raw.trim();
    }

    public static String normalizeAudio(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(java.util.Locale.US).replace(" ", "").replace("_", "");
        if (s.indexOf("atmos") >= 0) return "杜比全景声";
        if (s.indexOf("stereo") >= 0) return "立体声";
        if (s.indexOf("other") >= 0) return "其他";
        return raw.trim();
    }

    private static int leadingYear(String s) {
        if (s == null || s.length() < 4) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void addAll(Set<String> set, List<String> vals) {
        if (vals == null) return;
        for (int i = 0; i < vals.size(); i++) {
            String v = vals.get(i);
            if (v != null && v.trim().length() > 0) {
                set.add(v.trim());
            }
        }
    }

    private static void addAll(List<String> out, List<String> vals) {
        if (vals == null) return;
        for (int i = 0; i < vals.size(); i++) {
            String v = vals.get(i);
            if (v != null && v.trim().length() > 0) {
                out.add(v.trim());
            }
        }
    }

    private FilterEngine() {
    }
}
