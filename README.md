# 聚映 MediaNest

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="160" alt="聚映 MediaNest 应用图标" />
</p>

面向 Android TV 的第三方流媒体客户端。当前支持**飞牛影视（fnOS）**与 **Jellyfin**，后续计划接入 **Emby**。

聚映用于连接用户自己的媒体服务器，提供媒体库浏览、收藏与已观看同步、选流、播放、直播和字幕功能。本项目不包含影视内容，与飞牛官方无关。

## 状态

| 服务器 | 状态 |
|---|---|
| 飞牛影视 / fnOS | 已适配，私有协议可能随固件升级变化 |
| Emby | 计划中 |
| Jellyfin | 已适配 |

- 应用名：**聚映 MediaNest**
- applicationId / namespace：`com.juying.medianest.app`
- 源码目录：`com/juying/medianest/`
- 许可证：[MIT](LICENSE)

## 功能

- 局域网发现与手动添加媒体服务器。
- 多账号、多服务器配置管理。
- 首页、继续观看、收藏、媒体库、电影、剧集、直播与搜索。
- 服务端已观看状态同步。
- 按设备能力选择原始流，不兼容时请求服务器转码。
- ExoPlayer 与系统 MediaPlayer 双内核及播放失败回退。
- 直播线路、动态画质、音轨和倍速控制。
- SRT、WebVTT、ASS 字幕与 libass 原生渲染。
- 电视遥控器焦点、OSD、选集、加载动画与快进快退提示。

## 开源与安全说明

仓库不包含以下内容：

- 真实账号或密码。
- 内网服务器地址。
- 登录令牌、会话令牌或访问令牌。
- 飞牛影视 authx 默认签名密钥。
- 调试 keystore 或发布签名。

首次连接飞牛影视时，登录页会从所选服务器的影视网页端自动提取 authx `secret` 与 `api key`，无需手动填写，也不会在构建时注入 APK。Jellyfin 使用服务器账号密码登录，并由适配器管理认证令牌。

账号、密码和令牌由用户在应用内输入，并保存在应用私有配置中。分享日志、截图或诊断文件前，请务必移除敏感信息。

## 技术栈

| 项目 | 配置 |
|---|---|
| 构建 | Android Gradle Plugin / Gradle Wrapper |
| 语言 | Java 8 字节码 |
| compileSdk | 36 |
| minSdk | 16（Android 4.1） |
| targetSdk | 27 |
| ABI | `armeabi-v7a` |
| 网络 | `HttpURLConnection` |
| TLS | Conscrypt 2.5.3（Maven AAR） |
| 数据解析 | minimal-json |
| 播放 | ExoPlayer 2.19.1 / MediaPlayer |
| 字幕 | libass 0.17.4 + JNI + CMake |
| UI | 原生 Android View |

`targetSdk 27` 与明文流量配置用于兼容局域网 HTTP 服务，不等同于现代应用商店发布配置。第三方依赖与原生库保留各自许可证，重新分发前应核对对应许可义务。

## 构建

环境要求：

1. JDK 17 或更高版本。
2. Android SDK，包含 `platforms/android-36`、build-tools、CMake 3.22.1 与 NDK `16.1.4479499`。
3. 在本机 `local.properties` 中配置 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` 已被 Git 忽略。不要在构建脚本中注入账号、密码、令牌或签名密钥。

构建 Debug APK：

```bash
./gradlew assembleDebug
```

Windows：

```bat
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

Debug 使用 Android Studio 默认调试签名。Release 默认不签名，正式发布时请通过 Android Studio 或 CI 注入签名配置。

## 安装

使用 Android Studio 或设备上的软件包安装器安装已构建的 APK。

首次启动会探测设备播放能力，随后在应用内填写服务器地址、账号及所需协议参数。

## 项目结构

```text
app/
├── build.gradle
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/juying/medianest/
    │   ├── core/                共享模型、策略与通用工具
    │   ├── domain/              端口、用例、仓库契约与播放编排
    │   ├── data/                Provider 适配器、签名、发现、HTTP 与持久化
    │   ├── player/              双内核、鉴权代理、字幕与播放基础设施
    │   ├── ui/                  Activity、电视界面与焦点交互
    │   └── app/                 应用入口与组合根
    ├── jni/                     JNI 桥接层、CMake 与原生重建脚本
    ├── jniLibs/armeabi-v7a/      预编译 libass 原生库
    ├── res/                     布局、图标、主题与字符串
    └── assets/                  图标字体
docs/                            API、架构、兼容性与原生构建说明
gradle/                          Gradle Wrapper
```

## 适配层设计

上层界面依赖领域用例与统一 `MediaApi` 契约，不直接依赖具体服务器实现：

```text
UI → MediaUseCases → MediaApi → FnNasApi / JellyfinApi
```

新增服务器时应复用现有媒体模型、观看状态、选流与播放回退逻辑，只在新适配器中处理协议差异。

## 相关文档

- [飞牛 API 说明](docs/API.md)
- [架构与主要流程](docs/ARCHITECTURE.md)
- [设备兼容边界](docs/COMPATIBILITY.md)
- [libass 构建说明](docs/LIBASS_BUILD.md)

## 贡献

提交 Issue 或 Pull Request 前，请确保：

- 不包含真实账号、密码、令牌、签名密钥、内网地址或用户媒体信息。
- 不包含抓包文件、完整日志、真机配置快照或未经授权的官方客户端素材。
- 新功能说明使用通用地址与占位符。
- 涉及私有协议变更时，说明验证环境和兼容性影响。

## 使用边界

- 仅用于连接用户自己的 NAS 或媒体服务器。
- 不包含影视内容、抓流服务或绕过服务器权限的能力。
- 私有 API 与服务器行为可能随版本升级变化。
- 本项目与服务器厂商无隶属关系，相关商标与素材权利归原权利人。
