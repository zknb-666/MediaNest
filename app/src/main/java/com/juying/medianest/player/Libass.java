package com.juying.medianest.player;

import android.graphics.Bitmap;

import java.nio.charset.Charset;

/**
 * libass 字幕渲染封装（纯 Java 壳 + 自编译 JNI）。
 *
 * <p>底层是 libass.so（静态包含 freetype/fribidi/harfbuzz，.note.android.ident=API 16，
 * 只依赖 z/m/dl/c），通过 jni/ass_jni.c 直接调 C API，绕开 ass-kt 的 Kotlin 层与
 * libGLESv3.so（TCL Android 4.2.1 设备没有 GLESv3）。
 *
 * <p>libass 原生只吃 ASS/SSA 脚本；SRT / WebVTT 由 {@link SubtitleText}（ExoPlayer
 * 的纯 Java 解析器）解析后转成最小 ASS，再交给 native。ASS/SSA 原文直通，
 * 特效（\pos、\k、绘图、逐句字体）全部由 libass 自己解释。
 *
 * <p>线程：native 句柄非线程安全，调用方需保证同一时刻只有一个线程渲染
 * （PlayerActivity 的进度 ticker 在 UI 线程调用即可）。
 */
public final class Libass {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    static {
        // 顺序重要：libassjni.so NEEDED libass.so，Android 会自动按 soname 加载，
        // 但显式先加载 libass 可在老 linker 上更稳妥。
        System.loadLibrary("ass");
        System.loadLibrary("assjni");
    }

    /** 一帧渲染结果：只包含有字幕的脏矩形区域，避免全屏位图上传拖慢老设备。 */
    public static final class Frame {
        /** 字幕位图（尺寸 == rect 的宽高）；empty=true 时无意义。 */
        public Bitmap bitmap;
        /** 在画布中的位置与大小；宽高为 0 表示当前时刻没有字幕。 */
        public final android.graphics.Rect rect = new android.graphics.Rect();
        public boolean empty;
    }

    private long handle;
    private int[] pixels;
    private Bitmap bitmap;
    private int width;
    private int height;
    private final int[] rectBuf = new int[4];
    private final Frame frame = new Frame();

    public Libass() {
        handle = nativeCreate();
        if (handle != 0L) {
            registerSystemFonts();
        }
    }

    /**
     * 设备上的 libass.so 未编译 fontconfig，ass_set_fonts_dir 不会被扫描，
     * 字形只能来自 ass_add_font。把系统字体文件注册进去，否则中文 ASS
     * 渲染结果为空白（实测 libass 不报错，只是没有任何 image）。
     */
    private void registerSystemFonts() {
        // 顺序即优先级：先注册中文字体，ASCII 缺字时再回落到 DroidSans。
        String[] files = {"DroidSansFallback.ttf", "DroidSans.ttf", "DroidSans-Bold.ttf"};
        boolean any = false;
        for (int i = 0; i < files.length; i++) {
            byte[] data = readFile("/system/fonts/" + files[i]);
            if (data == null || data.length == 0) {
                continue;
            }
            any |= nativeAddFont(handle, files[i], data);
        }
        if (any) {
            nativeSetDefaultFont(handle, "Droid Sans Fallback");
        }
    }

    private static byte[] readFile(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] buf = new byte[(int) new java.io.File(path).length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return buf;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public boolean available() {
        return handle != 0L;
    }

    /** 设置画布与视频原始尺寸；storage 用原片分辨率，frame 用显示 View 尺寸。 */
    public void setSize(int w, int h, int storageW, int storageH) {
        if (handle == 0L || w <= 0 || h <= 0) {
            return;
        }
        if (w != width || h != height) {
            width = w;
            height = h;
            pixels = new int[w * h];
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        nativeSetSize(handle, w, h, storageW, storageH);
    }

    /** 加载字幕原文；ASS/SSA 直接用，SRT/VTT 自动转 ASS。返回是否含有效事件。 */
    public boolean load(String subtitleText) {
        if (handle == 0L || subtitleText == null) {
            return false;
        }
        String ass = toAss(subtitleText);
        // 原生层不再访问 ASS_Track 内部字段，事件是否存在由这里判定。
        if (ass.indexOf("Dialogue:") < 0) {
            return false;
        }
        // 显式 UTF-8：平台默认编码在部分定制固件上不是 UTF-8，会导致字幕乱码
        byte[] bytes = ass.getBytes(UTF_8);
        return nativeLoad(handle, bytes, bytes.length);
    }

    public void clear() {
        if (handle != 0L) {
            nativeClear(handle);
        }
    }

    /**
     * 把 posMs 时刻的字幕渲染成「脏矩形」位图。
     * 返回 null 表示画面未变化，调用方沿用上一帧；
     * 返回的 Frame.empty=true 表示此刻没有字幕，调用方应清空覆盖层。
     */
    public Frame render(long posMs) {
        if (handle == 0L || bitmap == null || pixels == null) {
            return null;
        }
        int changed = nativeRender(handle, posMs, pixels, rectBuf);
        if (changed <= 0) {
            return null;
        }
        frame.empty = rectBuf[2] <= 0 || rectBuf[3] <= 0;
        frame.rect.set(rectBuf[0], rectBuf[1], rectBuf[0] + rectBuf[2], rectBuf[1] + rectBuf[3]);
        if (frame.empty) {
            frame.bitmap = null;
            return frame;
        }
        int rw = rectBuf[2];
        int rh = rectBuf[3];
        if (bitmap.getWidth() != rw || bitmap.getHeight() != rh) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        }
        // 只上传脏区（rw*rh），不再是整屏 1920x1080。
        bitmap.setPixels(pixels, 0, rw, 0, 0, rw, rh);
        frame.bitmap = bitmap;
        return frame;
    }

    public void release() {
        if (handle != 0L) {
            nativeRelease(handle);
            handle = 0L;
        }
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = null;
        pixels = null;
    }

    // ------------------------------------------------------------------
    // SRT / WebVTT -> 最小 ASS
    // ------------------------------------------------------------------

    /** 依据内容判断格式；含 [Script Info]/Dialogue: 视为原生 ASS，否则交给 SubtitleText。 */
    private static String toAss(String text) {
        if (text == null) {
            return "";
        }
        String t = text;
        // UTF-8 BOM 会让第一行（[Script Info] 或 WEBVTT）识别失败
        if (t.length() > 0 && t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        t = t.replace("\r\n", "\n").replace('\r', '\n');
        // ASS/SSA 必须原样直通：\pos、\k、绘图、逐句字体等特效只有 libass 能解释
        if (SubtitleText.isAss(t)) {
            return t;
        }
        return SubtitleText.toAss(t);
    }

    /**
     * 提取所有字幕事件的开始时间（毫秒），仅用于同步诊断日志。
     * 传入的是交给 libass 的最终文本（SRT/VTT 已被转成 ASS）。
     */
    public static long[] cueStarts(String text) {
        if (text == null) {
            return null;
        }
        String assText = toAss(text);
        java.util.ArrayList<Long> list = new java.util.ArrayList<Long>();
        for (String line : assText.split("\n")) {
            if (!line.startsWith("Dialogue:")) {
                continue;
            }
            String[] f = line.split(",", 10);
            if (f.length < 3) {
                continue;
            }
            long t = parseTime(f[1].trim());
            if (t >= 0) {
                list.add(t);
            }
        }
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        // 源文件里的 cue 顺序不保证递增（分片合并、手工文件都可能乱序），
        // 同步诊断用二分查找，必须先排序。
        java.util.Arrays.sort(out);
        return out;
    }

    /** "h:mm:ss.cc" / "mm:ss.mmm" / "h:mm:ss,mmm" -> 毫秒。 */
    static long parseTime(String raw) {
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
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000 → 毫秒偏移。
     * 语义：LOCAL 时间对应 MPEGTS（90kHz）时间，播出时间 = cue 时间 - LOCAL + MPEGTS/90。
     */
    public static long timestampMapOffset(String line) {
        int m = line.indexOf("MPEGTS:");
        int l = line.indexOf("LOCAL:");
        if (m < 0 || l < 0) {
            return 0L;
        }
        try {
            int p = m + 7;
            int q = p;
            while (q < line.length() && (Character.isDigit(line.charAt(q)) || line.charAt(q) == '-')) {
                q++;
            }
            long mpegts = Long.parseLong(line.substring(p, q));
            long local = parseTime(line.substring(l + 6));
            if (local < 0) {
                local = 0L;
            }
            long offset = mpegts / 90 - local;
            // 超过 12 小时的偏移说明不是给这条媒体用的，忽略
            return Math.abs(offset) > 12L * 3600L * 1000L ? 0L : offset;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // JNI 声明（与 jni/ass_jni.c 一一对应）
    // ------------------------------------------------------------------
    private static native long nativeCreate();

    /** 注册一份字体数据（TTF）到 libass；无 fontconfig 时这是唯一的字形来源。 */
    private static native boolean nativeAddFont(long handle, String name, byte[] data);

    /** 字体注册完成后指定默认字体族（ASS 样式里缺失字体时的兜底）。 */
    private static native void nativeSetDefaultFont(long handle, String family);

    private static native void nativeSetSize(long handle, int w, int h, int storageW, int storageH);

    private static native boolean nativeLoad(long handle, byte[] data, int len);

    private static native void nativeClear(long handle);

    /** 渲染脏区：rect 输出 {x, y, w, h}，pixels 按 stride=w 存放该区域像素。 */
    private static native int nativeRender(long handle, long timeMs, int[] pixels, int[] rect);

    private static native void nativeRelease(long handle);
}
