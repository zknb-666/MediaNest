package com.juying.medianest.player;

import com.juying.medianest.core.util.L;

import android.graphics.Color;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.Subtitle;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 文本字幕解析：交给 ExoPlayer 2.19.1 的纯 Java 解析器
 * （WebvttDecoder / SubripDecoder，随 exoplayer-core 传递的 extractor 模块提供），
 * 再把 ExoPlayer 的 {@link Cue} 模型转成 libass 直接吃的 ASS Dialogue。
 *
 * <p>为什么不自己解析：原实现有两套手写 VTT 解析（HlsVtt 内一份、Libass.toAss 一份）
 * 加一份 ECL-2.0 的 Opencast 解析器，三者在「缺空行 / 非规范时间戳 / 带设置行的
 * cue」上行为各不相同。ExoPlayer 的解析器已经覆盖这些边界，且 cue 样式被解析成
 * Android 的 span，可以直接映射成 ASS override 标签。
 *
 * <p>ASS/SSA 输入不走这里：libass 原生解析才能保住 \pos、\k 卡拉 OK、绘图、逐句
 * 字体等特效；ExoPlayer 的 SsaDecoder 只保留文本和粗粒度对齐，转一道反而变差。
 */
public final class SubtitleText {

    /** 解析后的一条字幕；正文已经转义并带 span 样式标签，换行是 \N。 */
    public static final class TextCue {
        public long startMs;
        public long endMs;
        /** 事件级 override（\an / \pos / \fs）；同 base 的事件才允许合并。 */
        String assBase;
        /** 已转义、已带样式标签的正文。 */
        String assBody;

        /** 完整的事件文本，用于去重键与日志。 */
        public String assText() {
            return "{" + assBase + "}" + assBody;
        }
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** 与 Libass 里手写实现一致的脚本分辨率（libass 会按视频缩放）。 */
    private static final int PLAY_W = 1920;
    private static final int PLAY_H = 1080;

    private static final String ASS_HEAD =
            "[Script Info]\nScriptType: v4.00+\nPlayResX: " + PLAY_W + "\nPlayResY: " + PLAY_H + "\n\n"
            + "[V4+ Styles]\n"
            + "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
            + "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, "
            + "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
            + "Alignment, MarginL, MarginR, MarginV, Encoding\n"
            // PrimaryColour=&H00FFFFFF（白），OutlineColour=&H00000000（黑描边），Alignment=2 底部居中
            + "Style: Default,sans-serif,54,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,"
            + "0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1\n\n"
            + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";

    private SubtitleText() {
    }

    /**
     * 是否是 ASS/SSA 原文。这类文本必须直通 libass，不经过 ExoPlayer。
     */
    public static boolean isAss(String text) {
        return text != null && (text.indexOf("[Script Info]") >= 0
                || text.indexOf("Dialogue:") >= 0
                || text.indexOf("[V4+ Styles]") >= 0);
    }

    /**
     * SRT/VTT 原文 → ASS 文档。解析失败时返回空串，调用方按「无字幕」处理。
     */
    public static String toAss(String raw) {
        List<TextCue> cues = parse(raw);
        if (cues.isEmpty()) {
            return "";
        }
        return assDocument(cues, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * 解析 SRT/VTT 原文。HLS 分片的 X-TIMESTAMP-MAP 偏移在这里折算，
     * 保证 cue 时间仍然落在整片绝对时间轴上（与 HlsClock 的 skew 口径一致）。
     */
    public static List<TextCue> parse(String raw) {
        List<TextCue> out = new ArrayList<TextCue>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        String t = raw;
        if (t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        t = t.replace("\r\n", "\n").replace('\r', '\n');
        String mime = detectMime(t);
        if (mime == null) {
            return out;
        }
        long offset = timestampMapOffset(t);
        try {
            out = decode(mime, t.getBytes(UTF_8), offset);
            // 空分片（只有 WEBVTT 头）是正常的，不告警
            if (out.isEmpty() && t.indexOf("-->") >= 0) {
                L.w("SubtitleText", "ExoPlayer 解析无有效 cue（mime=" + mime + "）");
            }
        } catch (Throwable e) {
            L.w("SubtitleText", "ExoPlayer 解析失败 mime=" + mime + "：" + e);
        }
        return out;
    }

    /** WEBVTT → text/vtt；含时间轴的其它文本按 SRT 处理；ASS/SSA 返回 null。 */
    private static String detectMime(String t) {
        if (isAss(t)) {
            return null;
        }
        String head = t.length() > 64 ? t.substring(0, 64) : t;
        if (head.indexOf("WEBVTT") >= 0) {
            return MimeTypes.TEXT_VTT;
        }
        if (t.indexOf("-->") >= 0) {
            return MimeTypes.APPLICATION_SUBRIP;
        }
        return null;
    }

    /** X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000 → 毫秒偏移（HLS 封装扩展）。 */
    private static long timestampMapOffset(String text) {
        String[] lines = text.split("\n", 16);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.indexOf("-->") >= 0) {
                break;
            }
            if (line.startsWith("X-TIMESTAMP-MAP")) {
                return Libass.timestampMapOffset(line);
            }
        }
        return 0L;
    }

    /**
     * 用 ExoPlayer 的解码器把整段文本解析成 Subtitle，并在输出缓冲仍有效时
     * 立即拷成 TextCue（缓冲一 release 就会被回收）。
     *
     * <p>输入缓冲按 ExoPlayer 自己的用法：写满 → flip → queue；解析器按
     * 「一个缓冲就是一份完整文本」处理，所以一次输入就能拿到全部 cue。
     */
    private static final int DECODE_ATTEMPTS = 2;
    private static final long DECODE_POLL_MS = 1500L;

    private static List<TextCue> decode(String mime, byte[] bytes, long offset) throws Exception {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= DECODE_ATTEMPTS; attempt++) {
            try {
                return decodeOnce(mime, bytes, offset);
            } catch (IllegalStateException e) {
                if (e.getMessage() == null || e.getMessage().indexOf("解析器没有输出") < 0) {
                    throw e;
                }
                last = e;
                if (attempt < DECODE_ATTEMPTS) {
                    L.i("SubtitleText", "解析器未输出，重建后重试 " + attempt + "/"
                            + DECODE_ATTEMPTS + " bytes=" + bytes.length);
                    Thread.sleep(50L);
                }
            }
        }
        throw last;
    }

    private static List<TextCue> decodeOnce(String mime, byte[] bytes, long offset) throws Exception {
        Format format = new Format.Builder().setSampleMimeType(mime).build();
        SubtitleDecoder decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(format);
        if (decoder == null) {
            throw new IllegalStateException("没有可用的解析器：" + mime);
        }
        SubtitleOutputBuffer output = null;
        try {
            SubtitleInputBuffer in = decoder.dequeueInputBuffer();
            if (in == null) {
                throw new IllegalStateException("解析器没有输入缓冲");
            }
            in.ensureSpaceForWrite(bytes.length);
            in.data.put(bytes);
            in.data.flip();
            in.timeUs = 0L;
            in.setFlags(0);
            decoder.queueInputBuffer(in);
            // SimpleDecoder 在自己的线程里异步解析，冷启动时 CPU/IO 竞争可能超过 600ms。
            output = poll(decoder, DECODE_POLL_MS);
            if (output == null) {
                // 解析器要求结束标记才吐最后一个缓冲
                SubtitleInputBuffer end = decoder.dequeueInputBuffer();
                if (end != null) {
                    end.setFlags(com.google.android.exoplayer2.C.BUFFER_FLAG_END_OF_STREAM);
                    end.timeUs = 0L;
                    decoder.queueInputBuffer(end);
                }
                output = poll(decoder, DECODE_POLL_MS);
            }
            if (output == null) {
                throw new IllegalStateException("解析器没有输出");
            }
            List<TextCue> out = new ArrayList<TextCue>();
            collect(output, offset, out);
            return out;
        } finally {
            if (output != null) {
                output.release();
            }
            decoder.release();
        }
    }

    /** 轮询解析结果；SimpleDecoder 的解码线程与调用线程是分开的。 */
    private static SubtitleOutputBuffer poll(SubtitleDecoder decoder, long timeoutMs)
            throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        SubtitleOutputBuffer out = decoder.dequeueOutputBuffer();
        while (out == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(2L);
            out = decoder.dequeueOutputBuffer();
        }
        return out;
    }

    /** 把 Subtitle 的 cue 拆成带起止时间的 TextCue。 */
    private static boolean collect(Subtitle sub, long offset, List<TextCue> out) {
        int n = sub.getEventTimeCount();
        if (n == 0) {
            return false;
        }
        long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            times[i] = sub.getEventTime(i);
        }
        java.util.Arrays.sort(times);
        IdentityHashMap<Cue, long[]> live = new IdentityHashMap<Cue, long[]>();
        long last = times[0];
        for (int i = 0; i < n; i++) {
            long t = times[i];
            last = t;
            List<Cue> active = sub.getCues(t);
            // 已经不在活动列表里的 cue 在这个边界结束
            Iterator<java.util.Map.Entry<Cue, long[]>> it = live.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Cue, long[]> e = it.next();
                if (!containsIdentity(active, e.getKey())) {
                    e.getValue()[1] = t;
                    addCue(out, e.getValue()[0], t, e.getKey(), offset);
                    it.remove();
                }
            }
            for (int k = 0; k < active.size(); k++) {
                Cue c = active.get(k);
                if (!live.containsKey(c)) {
                    live.put(c, new long[] {t, -1L});
                }
            }
        }
        // 最后一个边界之后仍未结束的 cue：给 2 秒兜底（单文件末尾常见）
        Iterator<java.util.Map.Entry<Cue, long[]>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Cue, long[]> e = it.next();
            long end = last + 2000000L; // ExoPlayer 的事件时间是微秒
            addCue(out, e.getValue()[0], end, e.getKey(), offset);
        }
        return !out.isEmpty();
    }

    private static boolean containsIdentity(List<Cue> list, Cue target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return true;
            }
        }
        return false;
    }

    /** @param startUs / @param endUs ExoPlayer 的微秒时间；@param offsetMs HLS 的毫秒偏移。 */
    private static void addCue(List<TextCue> out, long startUs, long endUs, Cue cue, long offsetMs) {
        long start = startUs / 1000L + offsetMs;
        long end = endUs / 1000L + offsetMs;
        if (start < 0) {
            start = 0L;
        }
        if (end <= start) {
            return;
        }
        TextCue c = new TextCue();
        c.startMs = start;
        c.endMs = end;
        if (!fillText(c, cue)) {
            return;
        }
        out.add(c);
    }

    /**
     * 生成完整的 ASS 文档；[from, to) 之外的 cue 丢掉（HLS 只喂窗口内的）。
     *
     * <p>同一时刻只能有一个 Dialogue 事件，否则 libass 会把多条事件画在同一基线上，
     * 屏幕上叠成看不清的一团（实测 NAS 分片 00611/00612：00:20:23.170「钱包余额：
     * 452日元」、00:20:24.340「（较昨日：-124400日元）」、00:20:25.590「最近的
     * 使用情况 【设备殿堂】」三条 cue 互相重叠，原实现会各输出一条 Dialogue）。
     * 这里按事件级样式（\an/\pos）分组，组内把重叠的时间轴切成若干区间，每个区间
     * 只输出一个事件、多条正文用 \N 连接；不同定位的 cue 仍保持独立，顶部标牌与
     * 底部对白可以同时存在。
     */
    public static String assDocument(List<TextCue> cues, long fromMs, long toMs) {
        StringBuilder sb = new StringBuilder(ASS_HEAD);
        java.util.LinkedHashMap<String, List<TextCue>> groups =
                new java.util.LinkedHashMap<String, List<TextCue>>();
        for (int i = 0; i < cues.size(); i++) {
            TextCue c = cues.get(i);
            if (c.endMs <= fromMs || c.startMs >= toMs) {
                continue;
            }
            List<TextCue> group = groups.get(c.assBase);
            if (group == null) {
                group = new ArrayList<TextCue>();
                groups.put(c.assBase, group);
            }
            group.add(c);
        }
        for (java.util.Iterator<java.util.Map.Entry<String, List<TextCue>>> it =
                groups.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, List<TextCue>> e = it.next();
            appendMerged(sb, e.getKey(), e.getValue());
        }
        return sb.toString();
    }

    /** 同一 override 组内按时间轴切段，保证任一时刻最多一个 Dialogue。 */
    private static void appendMerged(StringBuilder sb, String base, List<TextCue> list) {
        // ASS 时间精度是 10ms，先把所有起止点对齐到 10ms，避免切出零长度事件。
        // 注意不要用窗口边界裁剪：窗口每 tick 都在前移，裁剪会让文档每 tick 都变，
        // 触发不必要的 libass 重新加载。
        java.util.TreeSet<Long> points = new java.util.TreeSet<Long>();
        for (int i = 0; i < list.size(); i++) {
            TextCue c = list.get(i);
            points.add(Long.valueOf(Math.max(0L, c.startMs / 10L * 10L)));
            points.add(Long.valueOf(c.endMs / 10L * 10L));
        }
        Long[] ts = points.toArray(new Long[points.size()]);
        long prevStart = -1L;
        long prevEnd = -1L;
        String prevText = null;
        for (int i = 0; i + 1 < ts.length; i++) {
            long t0 = ts[i].longValue();
            long t1 = ts[i + 1].longValue();
            if (t1 <= t0) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (int k = 0; k < list.size(); k++) {
                TextCue c = list.get(k);
                // 区间端点取自所有起止时间，覆盖整个区间的 cue 就是这一段的全部内容
                if (c.startMs <= t0 && c.endMs >= t1 && seen.add(c.assBody)) {
                    if (body.length() > 0) {
                        body.append("\\N{\\r").append(base).append('}');
                    }
                    body.append(c.assBody);
                }
            }
            if (body.length() == 0) {
                continue;
            }
            String text = body.toString();
            if (prevText != null && prevText.equals(text) && prevEnd == t0) {
                prevEnd = t1; // 正文没变，直接把上一段延长到这一段的结尾
                continue;
            }
            if (prevText != null) {
                sb.append(dialogueLine(prevStart, prevEnd, base, prevText));
            }
            prevStart = t0;
            prevEnd = t1;
            prevText = text;
        }
        if (prevText != null) {
            sb.append(dialogueLine(prevStart, prevEnd, base, prevText));
        }
    }

    private static String dialogueLine(long start, long end, String base, String body) {
        return "Dialogue: 0," + assTime(start) + ',' + assTime(end)
                + ",Default,,0,0,0,,{" + base + "}" + body + '\n';
    }

    // ------------------------------------------------------------------
    // ExoPlayer Cue → ASS
    // ------------------------------------------------------------------

    private static boolean fillText(TextCue out, Cue cue) {
        if (cue == null || cue.text == null) {
            return false;
        }
        int an = alignmentOf(cue);
        // VTT 的 position/line 是百分比：换算到 PlayRes 坐标，交给 libass 绝对定位。
        // line 百分比 0=画面顶部、100=底部，必须把纵向锚点并进 \an（顶部 7/8/9、
        // 中部 4/5/6、底部 1/2/3），否则 \an2 配 y=0 会把整句顶到画面外（line:0%
        // 实测不可见）。0%/100% 再向内收 6%，避免贴边被过扫描吃掉。
        boolean lineFraction = cue.lineType == Cue.LINE_TYPE_FRACTION
                && cue.line != Cue.DIMEN_UNSET;
        int y = -1;
        if (lineFraction) {
            float f = Math.max(0f, Math.min(1f, cue.line));
            an += f < 0.33f ? 6 : (f > 0.66f ? 0 : 3);
            int pad = Math.round(PLAY_H * 0.06f);
            y = Math.max(pad, Math.min(PLAY_H - pad, Math.round(f * PLAY_H)));
        }
        StringBuilder base = new StringBuilder();
        base.append("\\an").append(an);
        if (cue.position != Cue.DIMEN_UNSET) {
            int x = (int) Math.round(cue.position * PLAY_W);
            base.append(String.format(java.util.Locale.US, "\\pos(%d,%d)", Integer.valueOf(x),
                    Integer.valueOf(y >= 0 ? y : PLAY_H - 60)));
        } else if (y >= 0) {
            // 实测 align:center + line:15% 的 cue 也带 position≈50%，走的是上一个分支；
            // 这里兜底「只有 line」的情况，显式 \pos 才能让 6% 安全边距生效。
            base.append(String.format(java.util.Locale.US, "\\pos(%d,%d)",
                    Integer.valueOf(PLAY_W / 2), Integer.valueOf(y)));
        }
        if (cue.textSizeType == Cue.TEXT_SIZE_TYPE_ABSOLUTE && cue.textSize != Cue.DIMEN_UNSET) {
            base.append("\\fs").append(Math.round(cue.textSize));
        }
        String baseText = base.toString();
        String body = styledText(cue.text, baseText);
        if (body.length() == 0) {
            return false;
        }
        out.assBase = baseText;
        out.assBody = body;
        return true;
    }

    /** 9 宫格对齐：默认底部居中，横向按 textAlignment 分左右。 */
    private static int alignmentOf(Cue cue) {
        Layout.Alignment a = cue.textAlignment;
        if (a == Layout.Alignment.ALIGN_NORMAL) {
            return 1;
        }
        if (a == Layout.Alignment.ALIGN_OPPOSITE) {
            return 3;
        }
        return 2;
    }

    /**
     * 把 ExoPlayer 解析出的 span（<i>/<b>/<u>/颜色/字号/字体）逐字符映射成
     * ASS override 标签；跨行换行转成 \N，双语句序保持不变。
     *
     * <p>同时清洗源文本：ASS 风格的 {@code {\...}} override 块是样式而不是台词，
     * 必须整块丢掉；{@code \{} 和 {@code \}} 还原成字面花括号；{@code \N}、{@code \h}
     * 分别是换行和硬空格。原实现只把花括号换成全角、正文原样保留，任何从 ASS
     * 派生的 VTT/SRT（NAS 转换或本地外挂）只要残留这种块，就会在画面上看到
     * {@code {\blue}} 这类标记。
     */
    private static String styledText(CharSequence cs, String base) {
        Spanned sp = cs instanceof Spanned ? (Spanned) cs : null;
        StringBuilder sb = new StringBuilder(cs.length() + 32);
        String current = "";
        int n = cs.length();
        int i = 0;
        while (i < n) {
            char ch = cs.charAt(i);
            // {\...}：ASS override 块，整块跳过（源文本残留的样式标记，不该显示）
            if (ch == '{' && i + 1 < n && cs.charAt(i + 1) == '\\') {
                i += 2;
                while (i < n && cs.charAt(i) != '}') {
                    i++;
                }
                if (i < n) {
                    i++; // 跳过 }
                }
                continue;
            }
            if (ch == '\\' && i + 1 < n) {
                char nx = cs.charAt(i + 1);
                if (nx == '{' || nx == '}') {
                    sb.append(nx == '{' ? '\uFF5B' : '\uFF5D');
                    i += 2;
                    continue;
                }
                if (nx == 'N' || nx == 'n') {
                    sb.append("\\N{\\r").append(base).append('}');
                    current = "";
                    i += 2;
                    continue;
                }
                if (nx == 'h') {
                    sb.append(' ');
                    i += 2;
                    continue;
                }
            }
            if (ch == '\n') {
                // ASS 的 override 会跨 \N 延续到下一行，先 \r 回到样式默认再补回
                // 本 cue 的 \an/\pos，避免「第一行斜体、第二行正文」时第二行也被斜体。
                sb.append("\\N{\\r").append(base).append('}');
                current = "";
                i++;
                continue;
            }
            if (sp != null) {
                String tags = tagsAt(sp, i);
                if (!tags.equals(current)) {
                    sb.append('{').append(tags).append('}');
                    current = tags;
                }
            }
            sb.append(escapeChar(ch));
            i++;
        }
        return sb.toString();
    }

    private static String tagsAt(Spanned sp, int i) {
        StringBuilder b = new StringBuilder();
        StyleSpan[] styles = sp.getSpans(i, i + 1, StyleSpan.class);
        for (int k = 0; k < styles.length; k++) {
            int s = styles[k].getStyle();
            if (s == android.graphics.Typeface.BOLD || s == android.graphics.Typeface.BOLD_ITALIC) {
                b.append("\\b1");
            }
            if (s == android.graphics.Typeface.ITALIC || s == android.graphics.Typeface.BOLD_ITALIC) {
                b.append("\\i1");
            }
        }
        if (sp.getSpans(i, i + 1, UnderlineSpan.class).length > 0) {
            b.append("\\u1");
        }
        if (sp.getSpans(i, i + 1, StrikethroughSpan.class).length > 0) {
            b.append("\\s1");
        }
        ForegroundColorSpan[] fg = sp.getSpans(i, i + 1, ForegroundColorSpan.class);
        if (fg.length > 0) {
            b.append("\\1c&H").append(bgr(fg[fg.length - 1].getForegroundColor())).append("&");
        }
        AbsoluteSizeSpan[] abs = sp.getSpans(i, i + 1, AbsoluteSizeSpan.class);
        if (abs.length > 0) {
            b.append("\\fs").append(Math.max(1, abs[abs.length - 1].getSize()));
        }
        RelativeSizeSpan[] rel = sp.getSpans(i, i + 1, RelativeSizeSpan.class);
        if (rel.length > 0) {
            int pct = Math.round(rel[rel.length - 1].getSizeChange() * 100f);
            b.append("\\fscx").append(pct).append("\\fscy").append(pct);
        }
        TypefaceSpan[] tf = sp.getSpans(i, i + 1, TypefaceSpan.class);
        if (tf.length > 0) {
            String family = tf[tf.length - 1].getFamily();
            if (family != null && family.length() > 0) {
                b.append("\\fn").append(family.replace("\\", ""));
            }
        }
        return b.toString();
    }

    /** ASS 颜色是 BGR 的十六进制（不含 #）。 */
    private static String bgr(int argb) {
        return String.format(java.util.Locale.US, "%02X%02X%02X",
                Integer.valueOf(Color.blue(argb)),
                Integer.valueOf(Color.green(argb)),
                Integer.valueOf(Color.red(argb)));
    }

    private static String escapeChar(char ch) {
        if (ch == '{') {
            return "\uFF5B"; // 全角花括号，避免被当成 override 块
        }
        if (ch == '}') {
            return "\uFF5D";
        }
        return String.valueOf(ch);
    }

    static String assTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long cs = ms / 10;
        long h = cs / 360000;
        long m = (cs / 6000) % 60;
        long s = (cs / 100) % 60;
        long c = cs % 100;
        return h + ":" + two(m) + ":" + two(s) + "." + two(c);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
