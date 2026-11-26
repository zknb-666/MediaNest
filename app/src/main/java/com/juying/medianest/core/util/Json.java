package com.juying.medianest.core.util;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 编解码器。
 *
 * <b>解析</b>由第三方 minimal-json 0.9.5 承担（纯 Java 5 字节码、零 Android API、
 * 23 个类，兼容 Android 2.3 / 4.2）；它正确处理 Unicode 代理对、各类转义与
 * 深层嵌套。若运行时该类不可用，自动回退到下方保留的手写解析器，保证不破坏功能。
 *
 * <b>序列化</b>仍为手写，且<b>不可替换</b>：飞牛影视的 authx 签名对请求体做 MD5，
 * 必须精确复刻前端 JSON.stringify 的紧凑格式、Map 插入顺序与非 ASCII 原样输出。
 * 第三方库会把中文转义成 Unicode 转义序列或调整数字格式，直接替换会让全部签名请求失败。
 */
public final class Json {

    /** 第三方解析器是否曾加载失败；失败后不再反复尝试。 */
    private static volatile boolean minimalJsonUnavailable = false;

    private Json() {
    }

    /** 紧凑序列化，保留 Map 的插入顺序（用于签名请求体）。 */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false);
        return sb.toString();
    }

    /** 键排序的紧凑序列化（用于配置持久化，保证输出稳定）。 */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, boolean sortKeys) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String) {
            writeString(sb, (String) value);
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
            return;
        }
        if (value instanceof Map) {
            Map map = (Map) value;
            List<Object> keys = new ArrayList<Object>(map.keySet());
            if (sortKeys) {
                Collections.sort(keys, StringComparator.INSTANCE);
            }
            sb.append('{');
            boolean first = true;
            for (int i = 0; i < keys.size(); i++) {
                Object key = keys.get(i);
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(key));
                sb.append(':');
                write(sb, map.get(key), sortKeys);
            }
            sb.append('}');
            return;
        }
        if (value instanceof Collection) {
            sb.append('[');
            Iterator it = ((Collection) value).iterator();
            boolean first = true;
            while (it.hasNext()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, it.next(), sortKeys);
            }
            sb.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, java.lang.reflect.Array.get(value, i), sortKeys);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(value));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        // 与 JS JSON.stringify 一致：非 ASCII 字符原样输出（UTF-8）
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("json is null");
        }
        if (!minimalJsonUnavailable) {
            try {
                return fromMinimal(com.eclipsesource.json.Json.parse(text));
            } catch (NoClassDefFoundError err) {
                // 第三方 jar 未随 APK 打包时静默回退，核心层不依赖 app 层日志
                minimalJsonUnavailable = true;
            } catch (RuntimeException ex) {
                // 解析失败不回退：第三方与手写对同一非法输入都该报错，
                // 否则会掩盖真实的 NAS 响应问题
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
        }
        Parser p = new Parser(text);
        Object v = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("trailing characters in json at " + p.pos);
        }
        return v;
    }

    /** 把 minimal-json 的树转换成核心层约定的 Map/List/String/Number/Boolean/null。 */
    private static Object fromMinimal(JsonValue v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isObject()) {
            JsonObject o = v.asObject();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (JsonObject.Member member : o) {
                m.put(member.getName(), fromMinimal(member.getValue()));
            }
            return m;
        }
        if (v.isArray()) {
            JsonArray a = v.asArray();
            List<Object> out = new ArrayList<Object>(a.size());
            for (JsonValue item : a) {
                out.add(fromMinimal(item));
            }
            return out;
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.isBoolean()) {
            return Boolean.valueOf(v.asBoolean());
        }
        if (v.isNumber()) {
            // 与手写解析器保持同一数值类型：整数 -> Long，带小数点/指数 -> Double
            String raw = v.toString();
            if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                return Double.valueOf(raw);
            }
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException ex) {
                return Double.valueOf(raw);
            }
        }
        return null;
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        throw new IllegalArgumentException("json root is not an object");
    }

    public static Map<String, Object> map(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    public static List<Object> list(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return new ArrayList<Object>();
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        return String.valueOf(v);
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static double doubleNum(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static long longer(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        }
        return def;
    }

    /** 容错地把字符串集合写进 Map（内部以 List 存储）。 */
    public static List<Object> toList(Collection<String> values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            out.addAll(values);
        }
        return out;
    }

    public static java.util.Set<String> toStringSet(Object value) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        List<Object> arr = list(value);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) != null) {
                out.add(String.valueOf(arr.get(i)));
            }
        }
        return out;
    }

    private static final class StringComparator implements java.util.Comparator<Object> {
        static final StringComparator INSTANCE = new StringComparator();

        public int compare(Object a, Object b) {
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            return readNumber();
        }

        private void expect(String word) {
            if (!s.regionMatches(pos, word, 0, word.length())) {
                throw new IllegalArgumentException("bad literal at " + pos);
            }
            pos += word.length();
        }

        private Map<String, Object> readObject() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWs();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("expected object key at " + pos);
                }
                String key = readString();
                skipWs();
                if (atEnd() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + pos);
                }
                pos++;
                m.put(key, readValue());
                skipWs();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
                }
            }
        }

        private List<Object> readArray() {
            ArrayList<Object> out = new ArrayList<Object>();
            pos++; // [
            skipWs();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(readValue());
                skipWs();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
                }
            }
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("bad escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object readNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.length() == 0) {
                throw new IllegalArgumentException("bad value at " + start);
            }
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.valueOf(num);
            }
            try {
                return Long.valueOf(num);
            } catch (NumberFormatException ex) {
                return Double.valueOf(num);
            }
        }
    }
}
