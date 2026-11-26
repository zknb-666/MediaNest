/*
 * libass JNI 桥接层（纯 C，直接调用 libass.so 的 C API）。
 *
 * 设计目标：
 *  - 不依赖 ass-kt 的 Kotlin 层与 libGLESv3.so（TCL Android 4.2.1 设备没有 GLESv3）；
 *  - libass.so 自带 freetype/fribidi/harfbuzz（静态链接），只依赖 z/m/dl/c，
 *    其 .note.android.ident = API 16，匹配应用最低版本；
 *  - 把 ASS_Image 链表合成为 RGBA8888 像素缓冲，通过 JNI 直接写入 Java 的
 *    int[] / DirectBuffer，Java 层只负责把像素画到 Bitmap，不解析任何字幕文本。
 *
 * 合成公式（本仓库内置的 libass 0.17+）：
 *   ASS_Image.color 是 0xRRGGBBAA（alpha 在低字节，0 = 不透明），
 *   alpha = (0xFF - (color & 0xFF)) * bitmap[y * stride + x] / 0xFF。
 * 注意：旧文档写的是 0xAABBGGRR，按那个解析会把白色填充（0xFFFFFF00）
 * 误判为全透明而丢掉，画面只剩描边 —— 这是「字幕只有描边」的根因。
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <ass/ass.h>

#define ASS_LOG_TAG "libass"
#define ASS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ASS_LOG_TAG, __VA_ARGS__)
#define ASS_LOGW(...) __android_log_print(ANDROID_LOG_WARN, ASS_LOG_TAG, __VA_ARGS__)

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int frame_w;
    int frame_h;
    int storage_w;
    int storage_h;
} AssEngine;

/* libass 日志：只放行 error/warning（0=致命 1=错误 2=警告），
   用于确认字体查找失败、脚本解析失败这类「渲染为空」的真实原因。 */
static void msg_callback(int level, const char *fmt, va_list va, void *data) {
    (void)data;
    if (level > 2) return;
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, va);
    if (level <= 1) ASS_LOGE("%s", buf); else ASS_LOGW("%s", buf);
}

JNIEXPORT jlong JNICALL
Java_com_juying_medianest_player_Libass_nativeCreate(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    AssEngine *e = (AssEngine *)calloc(1, sizeof(AssEngine));
    if (!e) return 0;
    e->library = ass_library_init();
    if (!e->library) { free(e); return 0; }
    ass_set_message_cb(e->library, msg_callback, NULL);
    /* 这份 libass.so 编译时关闭了 fontconfig，宿主系统里没有 /etc/fonts 可查。
       无 fontconfig 时 ass_set_fonts_dir 不会被扫描，字形只能来自
       ass_add_font 注册的字体 —— Java 层会把 /system/fonts 里的
       DroidSansFallback 等 TTF 通过 nativeAddFont 送进来。 */
    ass_set_fonts_dir(e->library, "/system/fonts");
    e->renderer = ass_renderer_init(e->library);
    if (!e->renderer) { ass_library_done(e->library); free(e); return 0; }
    /* 先给一个默认族；nativeSetDefaultFont 会在字体注册后再调一次。 */
    ass_set_fonts(e->renderer, NULL, "sans-serif", 0, NULL, 0);
    e->frame_w = 0;
    e->frame_h = 0;
    e->storage_w = 0;
    e->storage_h = 0;
    return (jlong)(intptr_t)e;
}

JNIEXPORT jboolean JNICALL
Java_com_juying_medianest_player_Libass_nativeAddFont(JNIEnv *env, jclass cls, jlong handle,
                                       jstring jname, jbyteArray data) {
    (void)cls;
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (!e || !e->library || !data || !jname) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return JNI_FALSE;
    char *buf = (char *)malloc((size_t)len);
    if (!buf) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    /* ass_add_font 内部会复制一份字体数据，调用后即可释放缓冲。 */
    ass_add_font(e->library, name ? name : "font", buf, (int)len);
    __android_log_print(ANDROID_LOG_INFO, ASS_LOG_TAG, "font added: %s (%d bytes)",
                        name ? name : "font", (int)len);
    if (name) (*env)->ReleaseStringUTFChars(env, jname, name);
    free(buf);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_juying_medianest_player_Libass_nativeSetDefaultFont(JNIEnv *env, jclass cls, jlong handle,
                                              jstring jfont) {
    (void)cls;
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (!e || !e->renderer || !jfont) return;
    const char *font = (*env)->GetStringUTFChars(env, jfont, NULL);
    if (!font) return;
    /* update=1：重新挑选字体提供者/刷新字体索引，让刚注册的字体生效。 */
    ass_set_fonts(e->renderer, font, font, 0, NULL, 1);
    __android_log_print(ANDROID_LOG_INFO, ASS_LOG_TAG, "default font family: %s", font);
    (*env)->ReleaseStringUTFChars(env, jfont, font);
}

JNIEXPORT void JNICALL
Java_com_juying_medianest_player_Libass_nativeSetSize(JNIEnv *env, jclass cls, jlong handle,
                                       jint w, jint h, jint storageW, jint storageH) {
    (void)env; (void)cls;
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (!e || !e->renderer || w <= 0 || h <= 0) return;
    e->frame_w = w;
    e->frame_h = h;
    e->storage_w = storageW > 0 ? storageW : w;
    e->storage_h = storageH > 0 ? storageH : h;
    ass_set_frame_size(e->renderer, w, h);
    ass_set_storage_size(e->renderer, e->storage_w, e->storage_h);
    /* 字幕不渲染到画面外的安全区：老 TV 过扫描会裁掉边缘，留 2% 边距。 */
    int mx = w / 50;
    int my = h / 50;
    ass_set_margins(e->renderer, my, my, mx, mx);
    ass_set_use_margins(e->renderer, 1);
}

/* 加载字幕：直接把 ASS 原文喂给 libass（支持 SSA/ASS；SRT/VTT 由 Java 侧转 ASS）。 */
JNIEXPORT jboolean JNICALL
Java_com_juying_medianest_player_Libass_nativeLoad(JNIEnv *env, jclass cls, jlong handle,
                                    jbyteArray data, jint len) {
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (!e || !e->library || !data || len <= 0) return JNI_FALSE;
    /* libass 要求 NUL 结尾的缓冲；ass_read_memory 会自行创建并返回 ASS_Track。 */
    char *buf = (char *)malloc((size_t)len + 1);
    if (!buf) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);
    buf[len] = '\0';
    if (e->track) { ass_free_track(e->track); e->track = NULL; }
    e->track = ass_read_memory(e->library, buf, (int)len, (char *)"UTF-8");
    free(buf);
    /* ASS_Track 在本头文件里是不透明句柄（避免跨版本结构差异），
       事件数由 Java 侧检查文本里是否含 Dialogue: 来判定。 */
    return e->track ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_juying_medianest_player_Libass_nativeClear(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (e && e->track) { ass_free_track(e->track); e->track = NULL; }
}

/*
 * 渲染一帧：只把「有字幕的区域」（所有 ASS_Image 的并集）合成为位图。
 *
 * 老 TV 上把 1920x1080 全屏位图每帧 setPixels + 全屏贴图会把 GPU/UI 线程压满，
 * 字幕随之掉帧、越来越滞后。这里返回脏矩形，Java 只上传/显示这一小块。
 *
 * 返回：
 *   -1 = 无字幕引擎/尚未加载
 *    0 = 画面未变化（Java 沿用上一帧）
 *   >0 = 有变化：rect[0..3] = {x, y, w, h}（w/h 为 0 表示当前时刻无字幕），
 *        pixels 中按 stride = w 存放该矩形区域的 ARGB8888（未覆盖处为 0）
 */
JNIEXPORT jint JNICALL
Java_com_juying_medianest_player_Libass_nativeRender(JNIEnv *env, jclass cls, jlong handle,
                                      jlong timeMs, jintArray pixels,
                                      jintArray rect) {
    (void)cls;
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (!e || !e->renderer || !e->track || e->frame_w <= 0 || !rect) return -1;

    int changed = 0;
    ASS_Image *img = ass_render_frame(e->renderer, e->track, (long long)timeMs, &changed);
    if (changed == 0) return 0;

    /* 第一遍：求所有可见 image 的并集，同时裁剪到画布内。 */
    int x0 = e->frame_w, y0 = e->frame_h, x1 = -1, y1 = -1;
    ASS_Image *it;
    for (it = img; it; it = it->next) {
        if (!it->bitmap || it->w <= 0 || it->h <= 0) continue;
        if ((0xFF - (int)(it->color & 0xFF)) == 0) continue;
        int ax0 = it->dst_x, ay0 = it->dst_y;
        int ax1 = ax0 + it->w, ay1 = ay0 + it->h;
        if (ax0 < 0) ax0 = 0;
        if (ay0 < 0) ay0 = 0;
        if (ax1 > e->frame_w) ax1 = e->frame_w;
        if (ay1 > e->frame_h) ay1 = e->frame_h;
        if (ax1 <= ax0 || ay1 <= ay0) continue;
        if (ax0 < x0) x0 = ax0;
        if (ay0 < y0) y0 = ay0;
        if (ax1 > x1) x1 = ax1;
        if (ay1 > y1) y1 = ay1;
    }

    jint *out = (*env)->GetIntArrayElements(env, pixels, NULL);
    jint *rout = (*env)->GetIntArrayElements(env, rect, NULL);
    if (!out || !rout) {
        if (out) (*env)->ReleaseIntArrayElements(env, pixels, out, JNI_ABORT);
        if (rout) (*env)->ReleaseIntArrayElements(env, rect, rout, JNI_ABORT);
        return -1;
    }

    if (x1 <= x0 || y1 <= y0) {
        /* 当前时刻没有字幕：告诉 Java 清空覆盖层。 */
        rout[0] = 0; rout[1] = 0; rout[2] = 0; rout[3] = 0;
        (*env)->ReleaseIntArrayElements(env, rect, rout, 0);
        (*env)->ReleaseIntArrayElements(env, pixels, out, JNI_ABORT);
        return 1;
    }

    int rw = x1 - x0, rh = y1 - y0;
    int i, total = rw * rh;
    for (i = 0; i < total; i++) out[i] = 0;

    for (it = img; it; it = it->next) {
        if (!it->bitmap || it->w <= 0 || it->h <= 0) continue;
        uint32_t color = it->color;
        // alpha 在低字节且是 ASS 的「反 alpha」：0=完全不透明。
        int a_mul = 0xFF - (int)(color & 0xFF);
        if (a_mul == 0) continue;
        int r = (int)((color >> 24) & 0xFF);
        int g = (int)((color >> 16) & 0xFF);
        int b = (int)((color >> 8) & 0xFF);
        int bw = it->w, bh = it->h, stride = it->stride;
        for (int y = 0; y < bh; y++) {
            int py = it->dst_y + y;
            if (py < y0 || py >= y1) continue;
            unsigned char *row = it->bitmap + y * stride;
            for (int x = 0; x < bw; x++) {
                int alpha8 = row[x];
                if (alpha8 == 0) continue;
                int px = it->dst_x + x;
                if (px < x0 || px >= x1) continue;
                int a = (a_mul * alpha8) / 0xFF;
                out[(py - y0) * rw + (px - x0)] =
                    (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    rout[0] = x0; rout[1] = y0; rout[2] = rw; rout[3] = rh;
    (*env)->ReleaseIntArrayElements(env, rect, rout, 0);
    (*env)->ReleaseIntArrayElements(env, pixels, out, 0);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_juying_medianest_player_Libass_nativeRelease(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    AssEngine *e = (AssEngine *)(intptr_t)handle;
    if (!e) return;
    if (e->track) { ass_free_track(e->track); e->track = NULL; }
    if (e->renderer) { ass_renderer_done(e->renderer); e->renderer = NULL; }
    if (e->library) { ass_library_done(e->library); e->library = NULL; }
    free(e);
}
