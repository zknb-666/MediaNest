package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NAS 返回的单个可用画质挡位。挡位值完全来自服务端，客户端不内置任何枚举表。
 */
public final class QualityLevel {

    /** NAS 原值，例如 "4k" / "1080p" / "720p" / "sd"。 */
    public String value = "";
    /** 由原值通用格式化出的展示文案，例如 "4K" / "1080P"。 */
    public String label = "";
    /** 从原值解析出的像素高度，用于排序与降级；无法解析时为 0。 */
    public int height = 0;

    public QualityLevel() {
    }

    public QualityLevel(String value, String label, int height) {
        this.value = value;
        this.label = label;
        this.height = height;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("value", value);
        m.put("label", label);
        m.put("height", Integer.valueOf(height));
        return m;
    }

    public static QualityLevel fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        return new QualityLevel(Json.str(m, "value", ""), Json.str(m, "label", ""),
                Json.integer(m, "height", 0));
    }
}
