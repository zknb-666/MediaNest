package com.juying.medianest.core.policy;

import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.RouteOption;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.util.QualityNames;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NAS 画质 / 线路挡位解析。纯 Java，便于独立编译和复用。
 *
 * 网页端源码依据：
 *  - 「清晰度」挡位来自 GET /tag/list?&lt;scope&gt; 的 resolutions，
 *    以及 GET /stream/list/&lt;guid&gt; 的 video_streams[].resolution_type；
 *  - 直播「线路」挡位来自 POST /play/info 的 live_channels
 *    （网页端 _r() 用 media_guid 定位当前线路，lines 就是整个数组）。
 *
 * 客户端不再内置 4K / 1080P / 720P 之类的挡位表，也不再假设直播有几条线路；
 * 所有挡位、名称、顺序与数量都以 NAS 返回为准。画质挡位变化时会触发线路重同步。
 */
public final class NasLevels {

    /** 把 NAS 的画质原值解析成像素高度；无法解析返回 0。不依赖任何挡位枚举表。 */
    public static int heightOf(String raw) {
        if (raw == null) {
            return 0;
        }
        String v = raw.trim().toLowerCase();
        if (v.length() == 0) {
            return 0;
        }
        // "4k" / "8k" 这类以 K 为单位的档位
        if (v.endsWith("k")) {
            String num = digits(v.substring(0, v.length() - 1));
            if (num.length() > 0) {
                return Integer.parseInt(num) * 1000;
            }
        }
        // "1080p" / "720" / "1080i" / "540p" 这类以行为单位的档位
        String num = digits(v);
        if (num.length() > 0) {
            int n = Integer.parseInt(num);
            return n > 100 ? n : n * 1000;
        }
        return 0;
    }

    /**
     * 档位对应的 16:9 实际像素行数，用于与设备解码上限比较：
     * NAS 的 "4k"/"8k" 是档位名（{@link #heightOf} 按 4000/8000 排序），
     * 实际是 3840x2160 / 7680x4320，所以 4k≈2160、8k≈4320。
     */
    public static int tierHeightOf(String raw) {
        int h = heightOf(raw);
        String v = raw == null ? "" : raw.trim().toLowerCase();
        if (h >= 1000 && v.endsWith("k")) {
            return (h / 1000) * 540;
        }
        return h;
    }

    /**
     * 按设备解码上限过滤可选档位：高于上限的档位（151 上的 4k）不出现在菜单里。
     * 无法解析高度的档位保留（没有依据判断它是否可解）；上限未知时全部保留。
     * 过滤只针对「输出档位」本身，NAS 侧仍可用更高档源转码到设备能解的档位。
     */
    public static List<QualityLevel> supported(List<QualityLevel> levels, int maxHeight) {
        List<QualityLevel> out = new ArrayList<QualityLevel>();
        if (levels == null) {
            return out;
        }
        for (int i = 0; i < levels.size(); i++) {
            QualityLevel q = levels.get(i);
            if (q == null) {
                continue;
            }
            int h = tierHeightOf(q.value);
            if (maxHeight <= 0 || h <= 0 || h <= maxHeight) {
                out.add(q);
            }
        }
        return out;
    }

    /** 通用格式化：4k→4K、1080p→1080P、SD→SD。不枚举具体挡位。 */
    public static String labelOf(String raw) {
        return QualityNames.labelOf(raw);
    }

    private static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        String out = sb.toString();
        return out.length() > 6 ? out.substring(0, 6) : out;
    }

    /** 画质挡位列表的指纹，用来判断 NAS 返回的挡位是否发生变化。 */
    public static String signature(List<QualityLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(levels.get(i).value.toLowerCase());
        }
        return sb.toString();
    }

    /** 画质挡位变化时，线路挡位需要重新从 NAS 拉取并同步。 */
    public static boolean needsRouteSync(String previousSignature, String currentSignature) {
        String a = previousSignature == null ? "" : previousSignature;
        String b = currentSignature == null ? "" : currentSignature;
        return !a.equals(b);
    }

    /**
     * 合并 NAS 两处画质数据生成可用挡位：tag/list 的 resolutions（当前范围可用）
     * 与 stream/list 的 video_streams[].resolution_type（原片真实画质）。
     * 去重后按高度降序；没有任何数据时返回空列表，UI 不会补写死档位。
     */
    public static List<QualityLevel> parseQualities(List<Object> tagResolutions,
                                                    List<Object> videoStreams) {
        Set<String> seen = new LinkedHashSet<String>();
        List<QualityLevel> out = new ArrayList<QualityLevel>();
        addQualities(out, seen, tagResolutions, null);
        if (videoStreams != null) {
            List<Object> vals = new ArrayList<Object>();
            for (int i = 0; i < videoStreams.size(); i++) {
                Map<String, Object> vs = Json.map(videoStreams.get(i));
                Object r = vs.get("resolution_type");
                if (r == null) {
                    r = vs.get("resolution");
                }
                if (r != null) {
                    vals.add(r);
                }
            }
            addQualities(out, seen, vals, null);
        }
        Collections.sort(out, new Comparator<QualityLevel>() {
            public int compare(QualityLevel a, QualityLevel b) {
                if (a.height != b.height) {
                    return b.height - a.height;
                }
                return a.value.compareTo(b.value);
            }
        });
        return out;
    }

    private static void addQualities(List<QualityLevel> out, Set<String> seen,
                                     List<Object> raw, String unused) {
        if (raw == null) {
            return;
        }
        for (int i = 0; i < raw.size(); i++) {
            String v = String.valueOf(raw.get(i)).trim();
            if (v.length() == 0 || "null".equalsIgnoreCase(v)) {
                continue;
            }
            String key = v.toLowerCase();
            if (!seen.add(key)) {
                continue;
            }
            out.add(new QualityLevel(v, labelOf(v), heightOf(v)));
        }
    }

    /** 供 TranscodeProfile 使用的 NAS 画质原值列表（已按高度降序）。 */
    public static List<String> resolutionValues(List<QualityLevel> levels) {
        List<String> out = new ArrayList<String>();
        if (levels == null) {
            return out;
        }
        for (int i = 0; i < levels.size(); i++) {
            out.add(levels.get(i).value);
        }
        return out;
    }

    /**
     * 解析直播线路挡位：优先 play/info.live_channels，为空时退回 stream/list.files。
     * 名称、数量、顺序全部来自 NAS；按 sort_num 升序，同序保持服务端原顺序。
     */
    public static List<RouteOption> parseRoutes(List<Object> liveChannels, List<Object> streamFiles) {
        List<Object> src = liveChannels;
        if (src == null || src.isEmpty()) {
            src = streamFiles;
        }
        List<RouteOption> out = new ArrayList<RouteOption>();
        if (src == null) {
            return out;
        }
        for (int i = 0; i < src.size(); i++) {
            Map<String, Object> f = Json.map(src.get(i));
            String path = Json.str(f, "path", "");
            String guid = Json.str(f, "guid", "");
            if (path.length() == 0) {
                continue;
            }
            RouteOption r = new RouteOption();
            r.path = path;
            r.guid = guid;
            // 名称完全取 NAS 的 file_name；缺失时用 guid 前缀，不写死「线路 N」
            r.name = Json.str(f, "file_name", "");
            if (r.name.length() == 0) {
                r.name = guid.length() > 8 ? guid.substring(0, 8) : guid;
            }
            r.sortNum = Json.integer(f, "sort_num", i);
            r.canPlay = Json.bool(f, "can_play", true);
            String q = Json.str(f, "resolution", "");
            if (q.length() == 0) {
                q = Json.str(f, "quality", "");
            }
            r.quality = q;
            out.add(r);
        }
        Collections.sort(out, new Comparator<RouteOption>() {
            public int compare(RouteOption a, RouteOption b) {
                return a.sortNum - b.sortNum;
            }
        });
        return out;
    }

    /**
     * 按 NAS 当前画质挡位同步线路挡位：
     * 线路本身仍以 NAS 返回为准，但当 NAS 未给线路单独标注画质时，
     * 用当前生效的画质挡位补齐展示信息；画质挡位为空时不附加任何标注。
     */
    public static List<RouteOption> syncRoutes(List<RouteOption> nasRoutes,
                                               List<QualityLevel> qualities,
                                               String activeQuality) {
        List<RouteOption> out = new ArrayList<RouteOption>();
        if (nasRoutes == null) {
            return out;
        }
        String active = activeQuality == null ? "" : activeQuality;
        if (active.length() == 0 && qualities != null && !qualities.isEmpty()) {
            active = qualities.get(0).value;
        }
        for (int i = 0; i < nasRoutes.size(); i++) {
            RouteOption src = nasRoutes.get(i);
            RouteOption r = new RouteOption();
            r.guid = src.guid;
            r.path = src.path;
            r.name = src.name;
            r.sortNum = src.sortNum;
            r.canPlay = src.canPlay;
            r.quality = src.quality.length() > 0 ? src.quality : active;
            out.add(r);
        }
        return out;
    }

    /** 在 NAS 线路里定位当前生效线路；找不到时返回第一条（与网页端 _r() 一致）。 */
    public static int activeRouteIndex(List<RouteOption> routes, String mediaGuid) {
        if (routes == null || routes.isEmpty()) {
            return -1;
        }
        if (mediaGuid != null && mediaGuid.length() > 0) {
            for (int i = 0; i < routes.size(); i++) {
                if (mediaGuid.equals(routes.get(i).guid)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private NasLevels() {
    }
}
