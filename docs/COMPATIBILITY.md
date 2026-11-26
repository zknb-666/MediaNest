# 设备兼容边界

## 当前基线

- `minSdk 16`（Android 4.1）
- `targetSdk 27`
- `compileSdk 36`
- 原生 ABI：`armeabi-v7a`
- 播放内核：ExoPlayer 2.19.1 与系统 MediaPlayer

`minSdk` 是可安装声明，不代表所有设备都能解码所有格式。实际播放能力由 `DeviceProbe` 与 `StreamSelector` 按设备报告动态判断。

## 低版本实现约束

- 避免使用高于 minSdk 的 Java API，必要时通过反射调用。
- 系统 MediaPlayer 的倍速能力仅从 API 23 起声明。
- MediaCodec 枚举只从 API 16 起读取。
- 网络层使用 `HttpURLConnection`，不引入高于 minSdk 的第三方网络栈。
- Conscrypt 通过 `org.conscrypt:conscrypt-android:2.5.3` Maven AAR 引入，为旧系统提供现代 TLS 支持；2.5.3 是支持 API 16 的最新稳定版。
- 旧设备无法解码的片源优先请求服务器转码。

## 原生构建

原生构建固定使用 NDK `16.1.4479499` 与 CMake `3.22.1`，`libass.so` 和 JNI 桥接层均以 API 16 编译。AGP 9 会统一拒绝 `minSdk < 21` 的原生配置，因此项目显式抑制该校验，但实际编译器仍为 NDK 16。

预编译 `libass.so` 保留传统 `DT_HASH`。更换该库时必须同时检查 GNU_HASH、API note 和动态依赖，不能只用同版本 Maven AAR 替换。

## 网络与安全

- 为兼容家庭 NAS，应用允许 HTTP 明文流量，建议只在可信局域网使用。
- 账号、密码与令牌保存在应用私有配置中。
- 正式分发前应重新评估 targetSdk、签名、备份策略和敏感配置存储。

## 播放兼容策略

- 视频或音频编码不匹配时请求服务器转码。
- 转码会话必须显式指定视频与音频编码器。
- 播放列表的 `CODECS` 仅作参考，真实音频编码从 TS PMT 校验。
- 内核初始化或播放失败时按错误类型回退，不在同一坏内核上无限重试。
