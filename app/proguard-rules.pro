# 核心层大量使用反射（MediaCodecList / MediaPlayer.setDataSource），保留类名
-keep class com.juying.medianest.core.** { *; }
-keep class com.juying.medianest.player.DeviceProbe { *; }
-keepnames class android.media.** { *; }
-dontwarn android.media.**

# 播放器引擎按 SDK 由工厂反射/直接实例化，保留全部引擎实现与 JNI 入口
-keep class com.juying.medianest.player.** { *; }
-keep class com.juying.medianest.player.Libass { *; }
-keep class com.juying.medianest.ui.PlayerActivity { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 混淆保留问题定位能力：崩溃栈与日志里的类名/方法名需要可读
-dontobfuscate
# 只做 shrink（把两套内核压回单 dex），不做 optimize。
# 实测 AGP 9 + minify 的 debug 构建里，R8 的分类合并会破坏
# ExoPlayer 内部的类名假设，运行期抛 NoClassDefFoundError。
# 关掉 optimize 后类名保持稳定，APK 仍能维持单 dex。
-dontoptimize

# ExoPlayer 2.x 内部的反射与可选依赖
-dontwarn com.google.android.exoplayer2.**
-dontwarn com.google.common.**
-dontwarn androidx.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn android.support.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# Conscrypt：大量类由 JNI/反射引用，必须整体保留
-keep class org.conscrypt.** { *; }
-dontnote libcore.io.Libcore
-dontnote org.apache.harmony.xnet.provider.jsse.OpenSSLRSAPrivateKey
-dontnote org.apache.harmony.security.utils.AlgNameMapper
-dontnote sun.security.x509.AlgorithmId
-dontwarn dalvik.system.BlockGuard
-dontwarn dalvik.system.BlockGuard$Policy
-dontwarn dalvik.system.CloseGuard
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
