cmake_minimum_required(VERSION 3.10)
project(libass_android C CXX)

# 手工生成 config.h（autotools configure 在本机无 native gcc 时跑不了）。
# 目标平台 API16 / armv7-a，都是 32 位标准 C 库，宏取值固定。
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(PFX ${CMAKE_CURRENT_SOURCE_DIR}/install)

set(HB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_SUBSET OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GLIB OFF CACHE BOOL "" FORCE)
set(HB_HAVE_ICU OFF CACHE BOOL "" FORCE)
set(HB_HAVE_FREETYPE OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GRAPHITE2 OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GOBJECT OFF CACHE BOOL "" FORCE)
add_subdirectory(${SRC}/harfbuzz-2.6.4 ${CMAKE_CURRENT_BINARY_DIR}/harfbuzz EXCLUDE_FROM_ALL)

# ---------- fribidi ----------
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/fribidi-config.h "
#ifndef FRIBIDI_CONFIG_H
#define FRIBIDI_CONFIG_H
#define FRIBIDI \"fribidi\"
#define FRIBIDI_NAME \"GNU FriBidi\"
#define FRIBIDI_BUGREPORT \"fribidi-dev@lists.freedesktop.org\"
#define FRIBIDI_VERSION \"1.0.8\"
#define FRIBIDI_MAJOR_VERSION 1
#define FRIBIDI_MINOR_VERSION 0
#define FRIBIDI_MICRO_VERSION 8
#define FRIBIDI_INTERFACE_VERSION 1
#define FRIBIDI_INTERFACE_VERSION_STRING \"1\"
#define FRIBIDI_SIZEOF_INT 4
#endif
")
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/fribidi_common_config.h "
#ifndef FRIBIDI_COMMON_CONFIG_H
#define FRIBIDI_COMMON_CONFIG_H
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1
#endif
")
# fribidi 源码 include <config.h>；把这个目录放到首位，用生成的 config.h 满足它。
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/config.h "
#ifndef CONFIG_H
#define CONFIG_H
/* fribidi 主 config.h：源文件用 HAVE_* 做可选优化，全部关掉即可。 */
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1
#define HAVE_STRDUP 1
#define HAVE_STRINGIZE 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIMES_H 1
#define STDC_HEADERS 1
#define SIZEOF_INT 4
#define SIZEOF_SHORT 2
#define SIZEOF_VOID_P 4
#define SIZEOF_WCHAR_T 4
#define RETSIGTYPE void
/* const 不 undef：现代 C 编译器支持 */
#endif
")

file(GLOB FRIBIDI_SRCS ${SRC}/fribidi-1.0.8/lib/*.c)
add_library(fribidi STATIC ${FRIBIDI_SRCS})
target_include_directories(fribidi PUBLIC
    ${CMAKE_CURRENT_BINARY_DIR}
    ${SRC}/fribidi-1.0.8/lib
    ${SRC}/fribidi-1.0.8)
target_compile_definitions(fribidi PRIVATE HAVE_CONFIG_H=1)

# ---------- libass（保留 fribidi + freetype + harfbuzz，禁 fontconfig）----------
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/ass_config.h "
#ifndef ASS_CONFIG_H
#define ASS_CONFIG_H
#define CONFIG_FRIBIDI 1
#define CONFIG_FREETYPE 1
/* 启用 harfbuzz；libass 0.17.4 的渲染头文件已无条件依赖它。 */
#define CONFIG_HARFBUZZ 1
#undef CONFIG_FONTCONFIG
#undef CONFIG_CORETEXT
#undef CONFIG_DIRECTWRITE
#undef CONFIG_ASM
#undef CONFIG_ICONV
#undef CONFIG_LIBPNG
#define CONFIG_LARGE_TILES 1
#define CONFIG_SOURCEVERSION \"tarball: 0.17.4\"
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STDBOOL_H 1
#define HAVE_LIBM 1
#define STDC_HEADERS 1
#define PACKAGE \"libass\"
#define PACKAGE_NAME \"libass\"
#define PACKAGE_VERSION \"0.17.4\"
#define PACKAGE_STRING \"libass 0.17.4\"
#define VERSION \"0.17.4\"
#endif
")

# libass 源码 include "config.h"（本地）+ <fribidi.h>（fribidi/lib）。
# 需要独立的 config.h 给 libass，不能和 fribidi 共用；用单独目录。
file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/asscfg)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/asscfg/config.h "
#ifndef CONFIG_H
#define CONFIG_H
#define CONFIG_FRIBIDI 1
#define CONFIG_FREETYPE 1
#define CONFIG_HARFBUZZ 1
#undef CONFIG_FONTCONFIG
#undef CONFIG_CORETEXT
#undef CONFIG_DIRECTWRITE
#undef CONFIG_ASM
#undef CONFIG_ICONV
#undef CONFIG_LIBPNG
#define CONFIG_LARGE_TILES 1
#define CONFIG_SOURCEVERSION \"tarball: 0.17.4\"
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STDBOOL_H 1
#define HAVE_LIBM 1
#define STDC_HEADERS 1
#define PACKAGE \"libass\"
#define PACKAGE_NAME \"libass\"
#define PACKAGE_VERSION \"0.17.4\"
#define PACKAGE_STRING \"libass 0.17.4\"
#define VERSION \"0.17.4\"
#endif
")

file(GLOB ASS_SRCS ${SRC}/libass-0.17.4/libass/*.c)
file(GLOB ASS_PORTABLE_SRCS ${SRC}/libass-0.17.4/libass/c/*.c)
list(APPEND ASS_SRCS ${ASS_PORTABLE_SRCS})
# 排除用不到的平台专用文件（configure 也不会编它们）
list(REMOVE_ITEM ASS_SRCS
    ${SRC}/libass-0.17.4/libass/ass_coretext.c
    ${SRC}/libass-0.17.4/libass/ass_directwrite.c
    ${SRC}/libass-0.17.4/libass/ass_fontconfig.c)
add_library(ass STATIC ${ASS_SRCS})
target_include_directories(ass PUBLIC
    ${CMAKE_CURRENT_BINARY_DIR}/asscfg
    ${CMAKE_CURRENT_BINARY_DIR}
    ${SRC}/libass-0.17.4/libass
    ${SRC}/fribidi-1.0.8/lib
    ${SRC}/harfbuzz-2.6.4/src
    ${PFX}/include/freetype2)
target_compile_definitions(ass PRIVATE HAVE_CONFIG_H=1)
# libass 0.17 使用 C99，并保留纯 C 光栅化实现以兼容 armeabi-v7a。
target_compile_options(ass PRIVATE -std=c99)
target_compile_options(fribidi PRIVATE -std=c99)

# ---------- 最终 libass.so ----------
# 静态链接 ass + fribidi + freetype + harfbuzz，只动态依赖 z/m/dl/c
add_library(ass_full SHARED jni_stub.c)
target_include_directories(ass_full PRIVATE ${SRC}/libass-0.17.4/libass)
target_link_libraries(ass_full
    -Wl,--whole-archive ass fribidi harfbuzz
    ${PFX}/lib/libfreetype.a
    -Wl,--no-whole-archive
    z m dl)
set_target_properties(ass_full PROPERTIES OUTPUT_NAME ass)
target_link_options(ass_full PRIVATE -Wl,--hash-style=both)
