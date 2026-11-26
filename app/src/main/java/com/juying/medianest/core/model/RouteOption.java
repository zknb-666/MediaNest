package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.util.QualityNames;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NAS 返回的单个直播线路挡位。数量、名称、顺序全部由服务端决定，客户端不写死档位。
 */
public final class RouteOption {

    public String guid = "";
    public String path = "";
    /** NAS 的 file_name，例如「线路1」「高清源」。 */
    public String name = "";
    /** NAS 的 sort_num，用于保持服务端顺序。 */
    public int sortNum = 0;
    public boolean canPlay = true;
    /** NAS 若在该线路上给出画质档位则记录，用于按画质同步展示。 */
    public String quality = "";

    public RouteOption() {
    }

    /** 在 OSD 菜单里展示的名称；画质由 NAS 提供时附上，否则只用 NAS 原名称。 */
    public String displayName() {
        if (quality.length() == 0) {
            return name;
        }
        return name + " · " + QualityNames.labelOf(quality);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("guid", guid);
        m.put("path", path);
        m.put("name", name);
        m.put("sortNum", Integer.valueOf(sortNum));
        m.put("canPlay", Boolean.valueOf(canPlay));
        m.put("quality", quality);
        return m;
    }

    public static RouteOption fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        RouteOption r = new RouteOption();
        r.guid = Json.str(m, "guid", "");
        r.path = Json.str(m, "path", "");
        r.name = Json.str(m, "name", "");
        r.sortNum = Json.integer(m, "sortNum", 0);
        r.canPlay = Json.bool(m, "canPlay", true);
        r.quality = Json.str(m, "quality", "");
        return r;
    }
}
