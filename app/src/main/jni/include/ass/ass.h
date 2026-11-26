/*
 * 最小 libass 头文件（仅本项目 JNI 用到的稳定 C API）。
 *
 * 设备侧的 libass.so 是预编译产物（无 fontconfig），仓库里没有它的头文件；
 * 这里只声明 ABI 稳定的函数与 ASS_Image 布局，不暴露 ASS_Track 内部字段，
 * 避免版本间结构差异。新增声明前请先确认 libass.so 是否导出同名符号：
 *   llvm-nm -D --defined-only libass.so | findstr ass_
 */
#ifndef FNT_ASS_MIN_H
#define FNT_ASS_MIN_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ass_library ASS_Library;
typedef struct ass_renderer ASS_Renderer;
/* 不透明句柄：不访问 n_events 等内部字段，跨版本安全。 */
typedef struct ass_track ASS_Track;

typedef struct ass_image {
    int w, h;
    int stride;
    uint8_t *bitmap;
    uint32_t color;
    int dst_x, dst_y;
    struct ass_image *next;
} ASS_Image;

typedef void (*ASS_MessageCallback)(int level, const char *fmt, va_list args, void *data);

ASS_Library *ass_library_init(void);
void ass_library_done(ASS_Library *priv);
void ass_set_message_cb(ASS_Library *priv, ASS_MessageCallback cb, void *data);
void ass_set_fonts_dir(ASS_Library *priv, const char *fonts_dir);
void ass_add_font(ASS_Library *priv, const char *name, const char *data, int size);

ASS_Renderer *ass_renderer_init(ASS_Library *library);
void ass_renderer_done(ASS_Renderer *priv);
void ass_set_fonts(ASS_Renderer *priv, const char *default_font,
                   const char *default_family, int fc, const char *config, int update);
void ass_set_frame_size(ASS_Renderer *priv, int w, int h);
void ass_set_storage_size(ASS_Renderer *priv, int w, int h);
void ass_set_margins(ASS_Renderer *priv, int t, int b, int l, int r);
void ass_set_use_margins(ASS_Renderer *priv, int use);

ASS_Track *ass_read_memory(ASS_Library *library, char *buf, size_t bufsize, char *fname);
void ass_free_track(ASS_Track *track);
ASS_Image *ass_render_frame(ASS_Renderer *priv, ASS_Track *track, long long now,
                            int *detect_change);

#ifdef __cplusplus
}
#endif

#endif /* FNT_ASS_MIN_H */
