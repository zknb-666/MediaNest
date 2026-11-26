# 架构与主要流程

## 分层与依赖方向

```text
ui → domain ← data
        ↑
       core

app 作为组合根连接各层；player 与 JNI 为 Android 播放基础设施
```

| 层 | 职责 |
|---|---|
| `ui` | Android TV Activity、View、焦点、图片展示与用户交互；通过领域用例和仓库端口访问系统 |
| `domain` | `MediaApi` 端口、`MediaUseCases`、仓库契约、播放编排与回退决策；不依赖具体 Provider 或 UI |
| `data` | 实现领域端口：fnOS/Jellyfin 适配器、HTTP、发现、SharedPreferences 仓库 |
| `core` | 共享模型、策略与通用工具（含 Android 日志封装） |
| `player` | ExoPlayer/MediaPlayer 内核、设备探测、鉴权代理、会话保活、HLS 与字幕渲染基础设施 |
| `app` | `Application` 组合根，创建仓库、发现服务、媒体用例和本地鉴权代理 |
| `jni` | libass JNI 桥接与原生构建 |

媒体调用的依赖方向统一为 `ui → MediaUseCases → MediaApi → Provider`。`ui` 不直接构造 Provider；`App` 根据 `ServerConfig.apiMode` 通过 `MediaApiFactory` 选择 `FnNasApi` 或 `JellyfinApi`，再交给 `MediaUseCases`。仓库实现位于 `data/store`，界面只依赖 `domain/repository` 中定义的端口。

## 启动与能力探测

```text
BootstrapActivity
  ├─ ConfigRepository.load()
  ├─ 读取 Capability 快照
  ├─ 快照缺失时调用 player.DeviceProbe
  ├─ 保存能力与首启状态
  └─ 按凭据进入 MainActivity 或服务器选择页
```

`App.onCreate()` 同时初始化 `EngineSelector`：先读取上次持久化的内核结论，再后台复核。能力探测失败会降级为保守基线，不阻塞启动。

## 发现、添加与登录

```text
OfficialScanDeviceActivity
  ├─ DiscoveryService
  │    ├─ 种子地址
  │    ├─ UDP / SSDP
  │    └─ 本机网段扫描
  └─ 已保存账号
       ↓
OfficialAddServerActivity → App.probeJellyfin() / 保存待登录配置
       ↓
OfficialLoginActivity
  ├─ fnOS：App.fetchAuthConfig() 获取网页签名参数
  ├─ MediaApiFactory.create()
  ├─ MediaUseCases.verify()
  └─ ConfigRepository + AccountRepository 持久化
```

`DefaultDiscoveryService` 只做无需账号的服务指纹识别；正式凭据在登录页验证成功后才落盘。

## 媒体浏览与状态同步

```text
MainActivity / DetailActivity / OfficialSearchActivity
  → MediaUseCases
  → MediaApi
  → FnNasApi 或 JellyfinApi
```

 Provider 适配器负责协议、端点和字段差异；统一模型与用例负责媒体库、详情、人物、搜索、收藏、已观看、字幕、画质线路和进度等操作。`PrefsConfigStore` 与 `PrefsAccountRepository` 负责本地配置和账号持久化。

## 播放与回退

```text
PlayerActivity
  ├─ PlaybackCoordinator
  │    ├─ TranscodeProfile
  │    ├─ MediaApi.getStreams()
  │    └─ StreamSelector
  ├─ EngineFactory → Exo2Engine / MediaPlayerEngine
  ├─ AuthProxy / LiveProxy
  ├─ PlaySessionKeeper
  ├─ HlsClock / HlsVtt / Libass
  └─ FallbackPlayback → PlaybackStateMachine
```

`PlaybackCoordinator` 根据源规格、设备能力和降级档位生成 `PlaybackRequest`，由 Provider 返回候选，再由 `StreamSelector` 排序。`PlayerActivity` 只绑定实际起播候选的会话，并协调双内核、字幕、OSD、直播换线和进度上报。`FallbackPlayback` 与 `PlaybackStateMachine` 对格式、解码、网络和鉴权失败执行有限重试、换流或终止。

## 配置与安全边界

`ConfigRepository` 与 `AccountRepository` 的实现把数据写入应用私有 SharedPreferences；解析失败会回退到默认值，损坏配置不会导致启动崩溃。账号、密码、令牌与签名参数不会进入版本库，分享诊断信息前应移除这些敏感值。
