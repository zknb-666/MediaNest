package com.juying.medianest.core.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 网页端海报卡片右下角清晰度角标的规则（2026-11-19 从 trimemedia-web 逆向）：
 *
 * <pre>
 * Ile = e =&gt; { if(!e) return [];
 *   let t = e.filter(x =&gt; !x.includes('480'));
 *   return Array.from(new Set(t)).slice(0,2)
 *       .map(x =&gt; ({resolution:x, image:iT[x]})).filter(x =&gt; !!x.image)
 * }
 * iT = {"8k":…, "4k":…, "1080p":…, "720p":…, "480p":…, "1080i":1080p, "720i":720p, "480i":480p}
 * </pre>
 *
 * 即：过滤 480 及以下、过滤无效值、去重、最多 2 个、只认映射表内的值；
 * 角标位于卡片右下角横向排列。文案实测为 8K / 4K / 1080 / 720 / 480。
 */
public final class QualityBadges {

    /** 最多同时显示的角标数量。 */
    public static final int MAX = 2;

    /** 把 item/list 的 media_stream.resolutions 转成要显示的角标文案。 */
    public static List<String> from(List<String> resolutions) {
        List<String> out = new ArrayList<String>();
        if (resolutions == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < resolutions.size() && out.size() < MAX; i++) {
            String raw = resolutions.get(i);
            if (raw == null) {
                continue;
            }
            String s = raw.trim().toLowerCase(Locale.US);
            if (s.length() == 0 || s.indexOf("未查询") >= 0 || s.indexOf("not query") >= 0) {
                continue;
            }
            if (s.indexOf("480") >= 0) {
                continue; // 网页端明确排除 480 及以下
            }
            String label = label(s);
            if (label.length() == 0 || seen.contains(label)) {
                continue; // 未知值不显示；重复值去重
            }
            seen.add(label);
            out.add(label);
        }
        return out;
    }

    /** 归一化到网页端角标文案；不在映射表内返回空串。 */
    public static String label(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.equals("8k")) return "8K";
        if (s.equals("4k") || s.equals("2160p") || s.equals("uhd")) return "4K";
        if (s.equals("1080p") || s.equals("1080i")) return "1080";
        if (s.equals("720p") || s.equals("720i")) return "720";
        if (s.equals("480p") || s.equals("480i")) return "480";
        return "";
    }

    /** 4K/8K 用白底黑字，其余用黑底白字（与网页端角标图片一致）。 */
    public static boolean inverted(String label) {
        return "4K".equals(label) || "8K".equals(label);
    }

    private QualityBadges() {
    }
}
