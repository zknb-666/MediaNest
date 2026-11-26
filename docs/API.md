# 飞牛影视私有 API 适配说明

飞牛影视没有公开 API 文档。本文记录客户端当前使用的端点、签名与播放会话约定；固件升级后需要重新核对。

所有端点都在 `ServerConfig` 中可配置。仓库不包含默认签名密钥；登录页会从所选服务器的影视网页端自动提取当前 authx 参数，无需手动填写。

## 1. 服务地址

默认端口为 `5666`，API 前缀为 `/v/api/v1`。客户端支持 HTTP 与 HTTPS，地址由用户在设置页输入。

## 2. authx 签名

```text
bodyHash = GET  ? md5(decodeURIComponent(排序后的 query))
                : md5(紧凑 JSON 请求体)

nonce     = 6 位随机数
timestamp = 毫秒时间戳
sign      = md5(secret + "_" + pathname + "_" + nonce + "_"
                 + timestamp + "_" + bodyHash + "_" + apiKey)
authx     = "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + sign
```

业务接口还需要 `Authorization: <token>`。登录令牌由应用运行时获取并保存在私有配置中，不得写入源码、文档或提交历史。

免签前缀：

- `/v/api/v1/sys/img/`
- `/v/api/v1/img/`
- `/v/api/v1/concat/`
- `/v/api/v1/media/`

## 3. 主要端点

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v/api/v1/login` | 账号登录，返回 token |
| GET | `/v/api/v1/server/info` | 服务器信息与转码能力 |
| GET | `/v/api/v1/mdb/list` | 媒体库列表 |
| POST | `/v/api/v1/item/list` | 媒体条目分页 |
| GET | `/v/api/v1/play/list` | 继续观看 |
| POST | `/v/api/v1/play/info` | 播放信息 |
| GET | `/v/api/v1/stream/list/{item_guid}` | 视频、音频与字幕流 |
| POST | `/v/api/v1/play/play` | 创建播放会话 |
| POST | `/v/api/v1/play/quality` | 可用转码画质 |
| POST | `/v/api/v1/item/watched` | 设置已观看 |
| DELETE | `/v/api/v1/item/watched` | 清除已观看 |

常见返回码：

| code | 含义 |
|---|---|
| 0 | 成功 |
| -2 | 登录态失效 |
| -15 | 账号或密码错误 |
| 5000 | 签名无效 |
| 1001 | 播放服务繁忙 |

## 4. 播放会话

`play/play` 请求需要同时提供条目、媒体、视频和音频 GUID。缺少视频或音频 GUID 时，服务端可能返回 `System busy`。

创建转码会话时：

- `video_encoder` 决定视频是否真正转码。
- `audio_encoder` 决定音频是否真正转码。
- `resolution` 只调整输出分辨率，不能替代编码器参数。

播放列表中的 `CODECS` 可能是模板值，不能作为真实音频编码的唯一依据。客户端会读取首个 TS 分片的 PMT 进行校验。

## 5. 会话续期与私有流鉴权

`/v/media/<hash>/` 会话具有绝对寿命，持续拉流不会自动续期。客户端在到期前重建会话，并透明重映射旧分片路径。

私有 HLS/TS 请求需要 `Authorization` 头。外部播放器无法方便地附加该请求头，因此应用通过本机回环代理补充鉴权后转发。

## 6. 固件升级检查

1. 核对 `/v/api/v1` 下的端点路径。
2. 核对 authx 签名算法与协议常量。
3. 核对 `play/play` 的视频、音频转码参数。
4. 核对媒体、季、集、人物与观看状态字段。
5. 更新 `ServerConfig` 与对应适配器，并重新验证浏览、同步与播放流程。
