package com.juying.medianest.core.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯 Java 字幕解析：WebVTT / SRT / ASS，保留 ASS 的定位与样式，支持双语与花式字幕。
 *
 * 网页端用 AssRenderer + wasm 渲染 ASS；App 用 TextView 复刻主要视觉：
 * 同一时刻可有多条 cue（中文 + 日文、画面中央的 Screen 花字），每条带自己的
 * 坐标、对齐、字号、颜色、旋转、淡入淡出。PlayResX/Y 来自 [Script Info]。
 */
public final class SubtitleRenderer {

    public static final class Style {
        public String name = "";
        public double fontSize = 48;
        public int color = 0xFFFFFFFF;
        public int align = 2;      // ASS numpad 对齐：2=底部居中，8=顶部居中，5=正中
        public int marginV = 10;
        public boolean bold = false;
    }

    public static final class Cue {
        public long startMs;
        public long endMs;
        public String text = "";
        /** ASS \pos 坐标（PlayRes 坐标系）；-1 表示未指定，按 align + marginV 布局。 */
        public double posX = -1;
        public double posY = -1;
        public int align = 2;
        public int marginV = 10;
        public double fontSize = 48;
        public int color = 0xFFFFFFFF;
        public float rotation = 0f;
        public boolean bold = false;
        public long fadeInMs = 0;
        public long fadeOutMs = 0;
        public String styleName = "";

        public boolean positioned() {
            return posX >= 0 && posY >= 0;
        }
    }

    public static final class Script {
        public List<Cue> cues = new ArrayList<Cue>();
        public double playResX = 1920;
        public double playResY = 1080;
    }

    private SubtitleRenderer() {
    }

    public static List<Cue> parse(String content) {
        return parseScript(content).cues;
    }

    public static Script parseScript(String content) {
        Script script = new Script();
        if (content == null || content.length() == 0) {
            return script;
        }
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        if (text.indexOf("[Script Info]") >= 0 || text.indexOf("[V4+ Styles]") >= 0
                || text.indexOf("[Events]") >= 0) {
            parseAss(text, script);
        } else {
            parseVttOrSrt(text, script.cues);
        }
        Collections.sort(script.cues, new Comparator<Cue>() {
            public int compare(Cue a, Cue b) {
                return a.startMs < b.startMs ? -1 : (a.startMs > b.startMs ? 1 : 0);
            }
        });
        return script;
    }

    // ------------------------------------------------------------------
    // WebVTT / SRT：无定位信息，双语靠「同一时间段多条 cue」表达
    // ------------------------------------------------------------------

    private static void parseVttOrSrt(String text, List<Cue> out) {
        String[] lines = text.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            int arrow = line.indexOf("-->");
            if (arrow < 0) {
                i++;
                continue;
            }
            long start = parseTime(line.substring(0, arrow));
            String right = line.substring(arrow + 3).trim();
            int cut = firstWhitespace(right);
            long end = parseTime(cut < 0 ? right : right.substring(0, cut));
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < lines.length) {
                String body = lines[i].trim();
                if (body.length() == 0) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(clean(body));
                i++;
            }
            String cueText = sb.toString().trim();
            if (end > start && cueText.length() > 0) {
                Cue c = new Cue();
                c.startMs = start;
                c.endMs = end;
                c.text = cueText;
                out.add(c);
            }
        }
    }

    // ------------------------------------------------------------------
    // ASS：[V4+ Styles] 样式表 + Dialogue 覆盖标签
    // ------------------------------------------------------------------

    private static void parseAss(String text, Script script) {
        String[] lines = text.split("\n");
        Map<String, Style> styles = new LinkedHashMap<String, Style>();
        int section = 0; // 0=其它 1=Styles 2=Events
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (line.equalsIgnoreCase("[V4+ Styles]")) {
                    section = 1;
                } else if (line.equalsIgnoreCase("[Events]")) {
                    section = 2;
                } else {
                    section = 0;
                }
                continue;
            }
            if (line.startsWith("PlayResX:")) {
                script.playResX = parseDouble(line.substring(9));
            } else if (line.startsWith("PlayResY:")) {
                script.playResY = parseDouble(line.substring(9));
            }
            if (section == 1 && line.startsWith("Style:")) {
                Style st = parseStyleLine(line.substring(6).trim());
                if (st != null) {
                    styles.put(st.name, st);
                }
                continue;
            }
            if (section == 2 && line.startsWith("Format:")) {
                continue; // 标准 ASS 列顺序固定
            }
            if (section != 2 || !line.startsWith("Dialogue:")) {
                continue;
            }
            String[] f = line.substring(9).trim().split(",", 10);
            if (f.length < 10) {
                continue;
            }
            long start = parseTime(f[1]);
            long end = parseTime(f[2]);
            String styleName = f[3].trim();
            Style st = styles.get(styleName);
            if (st == null) {
                st = styles.get("Default");
            }
            Cue c = new Cue();
            c.startMs = start;
            c.endMs = end;
            c.styleName = styleName;
            c.align = st == null ? 2 : st.align;
            c.marginV = st == null ? 10 : st.marginV;
            c.fontSize = st == null ? 48 : st.fontSize;
            c.color = st == null ? 0xFFFFFFFF : st.color;
            c.bold = st != null && st.bold;
            String body = f[9];
            // {\p1} 是 ASS 矢量绘图（边框/装饰图形），文本是路径指令而非台词，
            // 渲染出来会是一串乱码，直接跳过。
            if (isDrawing(body)) {
                continue;
            }
            applyOverrideTags(body, c);
            c.text = cleanAss(body);
            if (end > start && c.text.length() > 0) {
                script.cues.add(c);
            }
        }
        if (script.playResX <= 0) {
            script.playResX = 1920;
        }
        if (script.playResY <= 0) {
            script.playResY = 1080;
        }
    }

    private static Style parseStyleLine(String s) {
        String[] f = s.split(",", 23);
        if (f.length < 18) {
            return null;
        }
        Style st = new Style();
        st.name = f[0].trim();
        st.fontSize = parseDouble(f[2]);
        if (st.fontSize <= 0) {
            st.fontSize = 48;
        }
        st.color = assColor(f[3]);
        st.bold = "-1".equals(f[7].trim()) || "1".equals(f[7].trim());
        if (f.length > 18) {
            st.align = (int) parseDouble(f[18]);
            if (st.align < 1 || st.align > 9) {
                st.align = 2;
            }
        }
        if (f.length > 21) {
            st.marginV = (int) parseDouble(f[21]);
        }
        return st;
    }

    /** ASS 颜色是 &HAABBGGRR&（BGR 序），转成 Android ARGB。 */
    public static int assColor(String raw) {
        if (raw == null) {
            return 0xFFFFFFFF;
        }
        String s = raw.trim();
        int start = s.indexOf('&');
        int end = s.lastIndexOf('&');
        if (start < 0 || end <= start) {
            return 0xFFFFFFFF;
        }
        String hex = s.substring(start + 1, end).replace("H", "").replace("h", "");
        try {
            long v = Long.parseLong(hex, 16);
            int a = (int) ((v >> 24) & 0xFF);
            int b = (int) ((v >> 16) & 0xFF);
            int g = (int) ((v >> 8) & 0xFF);
            int r = (int) (v & 0xFF);
            // ASS 的 alpha 0 = 不透明，255 = 全透明，与 Android 相反
            int alpha = 255 - a;
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        } catch (NumberFormatException ex) {
            return 0xFFFFFFFF;
        }
    }

    /** 解析 {\\pos \\an \\fs \\c \\frz \\fad \\b ...} 覆盖标签（可多段 {}）。 */
    private static void applyOverrideTags(String body, Cue c) {
        int i = 0;
        while (i < body.length()) {
            int open = body.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = body.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String block = body.substring(open + 1, close);
            i = close + 1;
            if (!block.startsWith("\\")) {
                continue;
            }
            String[] tags = block.split("\\\\");
            for (int k = 1; k < tags.length; k++) {
                String t = tags[k];
                if (t.length() == 0) {
                    continue;
                }
                String lower = t.toLowerCase(Locale.US);
                if (lower.startsWith("pos(")) {
                    String[] xy = inner(t).split(",");
                    if (xy.length >= 2) {
                        c.posX = parseDouble(xy[0]);
                        c.posY = parseDouble(xy[1]);
                    }
                } else if (lower.startsWith("an") && t.length() > 2
                        && Character.isDigit(t.charAt(2))) {
                    int a = (int) parseDouble(t.substring(2, 3));
                    if (a >= 1 && a <= 9) {
                        c.align = a;
                    }
                } else if (lower.startsWith("fs") && t.length() > 2
                        && Character.isDigit(t.charAt(2))) {
                    double fs = parseDouble(t.substring(2));
                    if (fs > 0) {
                        c.fontSize = fs;
                    }
                } else if (lower.startsWith("frz")) {
                    c.rotation = (float) parseDouble(t.substring(3));
                } else if (lower.startsWith("fad(")) {
                    String[] p = inner(t).split(",");
                    if (p.length >= 2) {
                        c.fadeInMs = (long) parseDouble(p[0]);
                        c.fadeOutMs = (long) parseDouble(p[1]);
                    }
                } else if (lower.startsWith("c&") || lower.startsWith("1c&")) {
                    int amp = t.indexOf('&');
                    if (amp >= 0) {
                        c.color = assColor(t.substring(amp));
                    }
                } else if (lower.equals("b1") || lower.equals("b700")) {
                    c.bold = true;
                } else if (lower.startsWith("b0")) {
                    c.bold = false;
                }
            }
        }
    }

    /** ASS 绘图模式：{\p1} / {\p2} 等，正文是 m/l/b 路径指令。 */
    private static boolean isDrawing(String body) {
        int p = body.toLowerCase(Locale.US).indexOf("\\p");
        if (p < 0) {
            return false;
        }
        int d = p + 2;
        return d < body.length() && Character.isDigit(body.charAt(d))
                && body.charAt(d) != '0';
    }

    private static String inner(String tag) {
        int p = tag.indexOf('(');
        int q = tag.lastIndexOf(')');
        return p >= 0 && q > p ? tag.substring(p + 1, q) : "";
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 时间轴
    // ------------------------------------------------------------------

    /** "h:mm:ss.cc" / "mm:ss.mmm" / "h:mm:ss,mmm" → 毫秒。 */
    public static long parseTime(String raw) {
        String s = raw == null ? "" : raw.trim().replace(',', '.');
        if (s.length() == 0) {
            return -1L;
        }
        int sp = firstWhitespace(s);
        if (sp > 0) {
            s = s.substring(0, sp);
        }
        String[] parts = s.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return -1L;
        }
        try {
            double h = parts.length == 3 ? Double.parseDouble(parts[0]) : 0d;
            double m = Double.parseDouble(parts[parts.length - 2]);
            double sec = Double.parseDouble(parts[parts.length - 1]);
            return (long) ((h * 3600 + m * 60 + sec) * 1000d);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /** 返回 posMs 时刻所有应显示的 cue（双语/花式字幕会同时命中多条）。 */
    public static List<Cue> activeAt(List<Cue> cues, long posMs) {
        List<Cue> out = new ArrayList<Cue>();
        if (cues == null || cues.isEmpty() || posMs < 0) {
            return out;
        }
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            if (c.startMs > posMs) {
                break; // 已按开始时间排序
            }
            if (posMs < c.endMs) {
                out.add(c);
            }
        }
        return out;
    }

    /** 兼容旧用例：返回第一条命中行的文本。 */
    public static String textAt(List<Cue> cues, long posMs) {
        List<Cue> active = activeAt(cues, posMs);
        return active.isEmpty() ? "" : active.get(0).text;
    }

    /** 淡入淡出：返回 0..1 的 alpha，未配置则恒 1。 */
    public static float alphaAt(Cue c, long posMs) {
        if (c == null) {
            return 0f;
        }
        float a = 1f;
        long elapsed = posMs - c.startMs;
        long remain = c.endMs - posMs;
        if (c.fadeInMs > 0 && elapsed < c.fadeInMs) {
            a = Math.min(a, (float) elapsed / c.fadeInMs);
        }
        if (c.fadeOutMs > 0 && remain < c.fadeOutMs) {
            a = Math.min(a, (float) remain / c.fadeOutMs);
        }
        return Math.max(0f, Math.min(1f, a));
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String clean(String s) {
        String v = s.replaceAll("(?i)<br\\s*/?>", "\n");
        v = v.replaceAll("<[^>]+>", "");
        v = v.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return v.trim().toLowerCase(Locale.US).startsWith("note:") ? "" : v.trim();
    }

    private static String cleanAss(String s) {
        String v = s.replaceAll("\\{[^}]*\\}", "");
        v = v.replaceAll("(?i)\\\\[Nn]", "\n");
        v = v.replace("\\h", " ");
        return clean(v);
    }
}
