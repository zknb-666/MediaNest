package com.juying.medianest.core.util;

/** 纯画质名称格式化，不依赖 NAS 策略或模型层。 */
public final class QualityNames {

    /** 通用格式化：4k→4K、1080p→1080P、SD→SD。不枚举具体挡位。 */
    public static String labelOf(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "";
        }
        String v = raw.trim();
        String low = v.toLowerCase();
        if (low.endsWith("k")) {
            return v.substring(0, v.length() - 1).toUpperCase() + "K";
        }
        if (low.endsWith("p") || low.endsWith("i")) {
            return v.substring(0, v.length() - 1).toUpperCase() + low.substring(low.length() - 1).toUpperCase();
        }
        if (digits(v).length() > 0) {
            return v.toUpperCase() + "P";
        }
        return v.toUpperCase();
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

    private QualityNames() {
    }
}
