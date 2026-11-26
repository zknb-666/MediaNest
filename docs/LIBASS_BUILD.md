# libass 与 JNI 构建说明

## 当前结构

```text
app/src/main/jni/
├── CMakeLists.txt
├── ass_jni.c
├── include/ass/ass.h
└── libass-build/
    ├── CMakeLists.txt
    ├── toolchain-android.cmake
    └── jni_stub.c

app/src/main/jniLibs/armeabi-v7a/
└── libass.so
```

`libassjni.so` 由 Android Studio 在构建 APK 时通过 CMake 编译；`libass.so` 使用 NDK 16 从源码交叉编译后提交到 `jniLibs`。

## 固定工具链

| 组件 | 版本 |
|---|---|
| Android NDK | 16.1.4479499（r16b） |
| CMake | 3.22.1 |
| libass | 0.17.4 |
| FreeType | 2.9.1 |
| FriBidi | 1.0.8 |
| HarfBuzz | 2.6.4 |
| 目标 ABI | `armeabi-v7a` |
| 目标 API | 16 |

NDK 16 是支持 API 16 的旧工具链。AGP 9 会拒绝 `minSdk < 21` 的原生配置，项目通过 `android.ndk.suppressMinSdkVersionError=21` 抑制该校验，但 `ndkVersion` 与 CMake 输出仍固定为 NDK 16/API 16。

## 准备源码

将以下源码解压到 `app/src/main/jni/libass-build/src/`：

```text
src/
├── libass-0.17.4/
├── freetype-2.9.1/
├── fribidi-1.0.8/
└── harfbuzz-2.6.4/
```

FriBidi 必须使用包含 `*.tab.i` 与 `fribidi-unicode-version.h` 生成表的官方发布包，不能使用缺少生成文件的 Git 标签快照。

`src/`、`build-*` 与 `install/` 已加入 `.gitignore`，不会提交到仓库。

## 编译 libass.so

设置 NDK 路径后执行：

```bash
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/16.1.4479499
CMAKE=/path/to/android-sdk/cmake/3.22.1/bin/cmake
NINJA=/path/to/android-sdk/cmake/3.22.1/bin/ninja
ROOT=app/src/main/jni/libass-build

"$CMAKE" -S "$ROOT/src/freetype-2.9.1" -B "$ROOT/build-ft" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$ROOT/toolchain-android.cmake" \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_INSTALL_PREFIX="$ROOT/install"
"$NINJA" -C "$ROOT/build-ft"
"$CMAKE" --install "$ROOT/build-ft"

"$CMAKE" -S "$ROOT" -B "$ROOT/build-ass" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$ROOT/toolchain-android.cmake" \
  -DCMAKE_BUILD_TYPE=Release
"$NINJA" -C "$ROOT/build-ass"
```

生成 `libass.so` 后 strip 并替换：

```bash
"$ANDROID_NDK_HOME/toolchains/arm-linux-androideabi-4.9/prebuilt/windows-x86_64/bin/arm-linux-androideabi-strip.exe" \
  --strip-unneeded "$ROOT/build-ass/libass.so"
cp "$ROOT/build-ass/libass.so" app/src/main/jniLibs/armeabi-v7a/libass.so
```

## 构建 JNI 与 APK

```bash
./gradlew assembleDebug
```

JNI 导出符号与 `com.juying.medianest.player.Libass` 一致。CMake 同时生成 GNU_HASH 与 DT_HASH，以兼容旧 Android linker。

## 构建产物核对

```bash
llvm-readelf -d app/src/main/jniLibs/armeabi-v7a/libass.so
llvm-readelf -n app/src/main/jniLibs/armeabi-v7a/libass.so
llvm-readelf -Ws app/src/main/jniLibs/armeabi-v7a/libass.so
```

需要确认：

- `.note.android.ident` 为 API 16，NDK 字符串包含 `r16b`。
- 同时存在 `GNU_HASH` 与 `HASH`。
- `NEEDED` 只包含 `libz.so`、`libm.so`、`libdl.so`、`libc.so`。
- 导出 `ass_library_init`、`ass_read_memory`、`ass_render_frame` 等 libass API。
- APK 内 `lib/armeabi-v7a/` 同时包含 `libass.so` 与 `libassjni.so`。
- 在最低系统版本上完成一次 ASS 加载与非空帧渲染。
