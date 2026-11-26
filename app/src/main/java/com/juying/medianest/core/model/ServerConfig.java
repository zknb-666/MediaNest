package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器连接配置。所有接口路径都做成可配置项，
 * 因为飞牛影视是私有 API，版本升级后路径/签名常量随时可能变。
 *
 * 默认地址为空，由用户在设置页输入当前媒体服务器。
 * 凭据不会写死在源码里：由用户在设置页输入，构建时可选从 gitignore 的
 * local.properties 注入初值。
 */
public final class ServerConfig {

    public static final String DEFAULT_HOST = "";
    public static final String DEFAULT_APP_NAME = "trimemedia-web";

    public String baseUrl = DEFAULT_HOST;
    public String username = "";
    public String password = "";
    public String appName = DEFAULT_APP_NAME;
    public String token = "";
    public String userId = "";
    public String deviceId = "";

    /** authx 签名常量，登录时从所选服务器的影视网页端自动提取。 */
    public String signSecret = "";
    public String signApiKey = "";
    public boolean signEnabled = true;

    /** "FNOS" 飞牛 / "JELLYFIN" Jellyfin。 */
    public String apiMode = "FNOS";
    /** 服务器来源：MANUAL / SEED / SWEEP / UDP / SSDP。 */
    public String sourceMethod = "MANUAL";

    // ---- 端点模板（相对 baseUrl）----
    public String loginPath = "/v/api/v1/login";
    public String serverInfoPath = "/v/api/v1/server/info";
    public String libraryPath = "/v/api/v1/mdb/list";
    /**
     * 网页端每个请求都带这两个头；服务端按客户端版本过滤数据。
     * 实测不带时 mediadb/list 只有 5 个库、live=0，带上后才有 IPTV 虚拟库与 334 个直播频道。
     */
    public String trimClient = "web";
    public String trimClientVersion = "631";
    /** 网页端侧栏真正使用的库列表（含 category=IPTV 的直播源虚拟库）。 */
    public String mediaDbListPath = "/v/api/v1/mediadb/list";
    /** 各库条目数 + live/total，用于导航计数。 */
    public String librarySumPath = "/v/api/v1/mediadb/sum";
    public String mediaListPath = "/v/api/v1/item/list";
    /** 筛选面板可用维度（按 type / ancestor_guid / is_favorite 区分）。 */
    public String tagListPath = "/v/api/v1/tag/list";
    /** genre id → 中文名。 */
    public String tagGenresPath = "/v/api/v1/tag/genres";
    /** ISO3166 国家码 → 中文名。 */
    public String tagCountriesPath = "/v/api/v1/tag/iso3166";
    /** 详情接口前缀，拼上 guid 即 /v/api/v1/item/{guid}，返回 backdrops/posters/logos。 */
    public String itemInfoPath = "/v/api/v1/item/";
    /**
     * 收藏 / 取消收藏：网页端 PUT /item/favorite、DELETE /item/favorite，
     * body {item_guid}，成功返回 code=0；收藏状态体现在 item/list 的 is_favorite。
     */
    public String favoritePath = "/v/api/v1/item/favorite";
    /**
     * 标记已看 / 取消已看：官方客户端 POST|DELETE /item/watched，body {guid}；
     * 状态体现在 item/{guid} 的 is_watched 与 item/list 的 watched。
     */
    public String watchedPath = "/v/api/v1/item/watched";
    /** 网页端季详情的集列表前缀，data 直接是数组，用于精确统计每季集数。 */
    public String episodeListPath = "/v/api/v1/episode/list/";
    public String continueListPath = "/v/api/v1/play/list";
    public String streamListPath = "/v/api/v1/stream/list/";
    public String playInfoPath = "/v/api/v1/play/info";
    /**
     * 启动播放会话。真机实测：带 item_guid / media_guid / video_guid / audio_guid
     * 才会返回 data.play_link = "/v/media/&lt;hash&gt;/preset.m3u8"（免签名的真实 HLS）。
     * 少了 video_guid / audio_guid 会返回 code 1001 System busy。
     */
    public String playPlayPath = "/v/api/v1/play/play";
    /** 播放进度上报：POST {item_guid, media_guid, ts}，播放结束 ts=0。 */
    public String playRecordPath = "/v/api/v1/play/record";
    /** 原始流：后面拼 media_guid，可带 playlink 参数；该路径免 authx 签名。 */
    public String originalStreamPath = "/v/api/v1/media/range/";
    /** 旧固件的 HLS 代理占位符；实测当前固件 /wp/m3u8 返回 501，仅作最后兜底。 */
    public String transcodePath = "/v/api/v1/wp/m3u8";
    /** 转码控制接口（切换画质/请求转码）。 */
    public String transcodeControlPath = "/v/api/v1/play/quality";
    /** 外挂字幕文本：后面拼 subtitle_guid，带 fmt=ass/vtt；内嵌字幕无独立文件（404）。 */
    public String subtitleDlPath = "/v/api/v1/subtitle/dl/";
    /** 在线字幕搜索：POST {lan, media_guid} -> data.subtitles[]。 */
    public String subtitleSearchPath = "/v/api/v1/subtitle/search";
    /** 下载在线字幕：POST {media_guid, trim_id, sync_download:1}。 */
    public String subtitleDownloadPath = "/v/api/v1/subtitle/download";
    /** 预下载字幕：POST {media_guid, subtitle_guid}。 */
    public String subtitlePredownloadPath = "/v/api/v1/subtitle/predownload";
    /** 删除外挂字幕：DELETE subtitle_guid。 */
    public String subtitleDelPath = "/v/api/v1/subtitle/del";
    /** 标记 NAS 文件为字幕：PUT {media_guid, filepaths:[...]}。 */
    public String subtitleMarkPath = "/v/api/v1/subtitle/mark";
    /** NAS 文件浏览：POST {path} -> data[{filename,is_dir,file_size,trim_name,trim_id}]。 */
    public String serverPathPath = "/v/api/v1/server/path";
    /** 可浏览的授权目录：GET -> data.authDirList[]。 */
    public String authorizedDirPath = "/v/api/v1/server/getAppAuthorizedDir";
    /**
     * 网页端播放控制端点（POST /media/p + req 字段分派）：
     * media.checkPlayLink 探测会话是否仍有效、media.transcodeStatis 每 30 秒心跳保活、
     * media.quit 退出。网页端靠它避免无条件重建 /play/play 会话。
     */
    public String mediaControlPath = "/v/api/v1/media/p";
    /**
     * 账号级偏好：GET/POST /user/info。
     * 字段 lan = 首选字幕语言、audio_lan = 首选音频语言；空串表示「任意语言」。
     */
    public String userInfoPath = "/v/api/v1/user/info";
    /** 账号级通用配置读：POST {key} -> data.value（JSON 字符串）。 */
    public String userDataGetPath = "/v/api/v1/user/getData";
    /** 账号级通用配置写：POST {key, value}，value 为 JSON 字符串。 */
    public String userDataSetPath = "/v/api/v1/user/setData";

    // ---- 从 server/info 读到的 NAS 能力 ----
    public boolean nasTranscodeCapable = true;
    public boolean gpuAcceleration = false;
    public boolean cpuDecoding = true;
    public boolean directLinkEnabled = true;
    public String serverName = "";
    public long lastVerifiedAt = 0L;

    /** 用户手动强制走 NAS 解码流（兼容老旧电视）。 */
    public boolean forceTranscode = false;

    /**
     * 创建播放会话时要求 NAS 输出的目标视频编码，留空表示不干预。
     *
     * 真机实测（TCL Android 4.2.1 / 2026-11-18）：4K HEVC Main10 原片若不带此参数，
     * POST /play/play 返回的 HLS 只是「原流重新封装」，preset.m3u8 里 CODECS 仍是
     * hvc1.2.4.L150.B0，系统播放器立刻 error(1,-5003)；带 "video_encoder":"h264"
     * 才会真正转码成 avc1.640029 并能连续播放。
     */
    public String transcodeVideoEncoder = "";
    /** NAS 解码流目标分辨率档位："" / "4k" / "1080" / "720" / "540" / "480" / "360"。 */
    public String transcodeResolution = "";
    /**
     * 主动请求的目标视频码率，单位 bps（NAS 接口原样使用该单位，客户端不做换算）。
     * 0 表示不干预，由服务端按默认档输出（本片 1080 实测约 5.95Mbps）。
     * 默认 1080 档封顶 12000000；源码码率更低时由 BitrateController 封顶。
     * 运行时字段，不持久化。
     */
    public int transcodeBitrateBps = 0;

    /**
     * 创建播放会话时要求 NAS 输出的目标音频编码，留空表示不干预（= 原音轨透传）。
     *
     * 真机实测（TCL Android 4.2.1 / 2026-11-18）：NAS 对视频转码时默认把原始音轨
     * 透传，E-AC-3 5.1 进 TS 后老电视没有 EAC3 解码器 → 无声，且无音频时钟会拖住
     * AVSync，导致 [OMX.MS.AVC.Decoder] Timed out waiting for output buffers 卡死。
     * 只有 POST /play/play 带 "audio_encoder":"ac3"/"mp3"/"aac" 才会真转音频；
     * 老设备优先 ac3（能 downmix 到 2ch 出声），连续降级时退回 mp3。
     */
    public String transcodeAudioEncoder = "";
    /** 本次转码请求的音频声道数；0 表示由能力与源轨决定，运行时字段。 */
    public int transcodeAudioChannels = 0;

    public boolean autoDiscoverOnStart = true;
    public int connectTimeoutMs = 5000;
    public int readTimeoutMs = 10000;

    /**
     * 播放器专用深拷贝。
     *
     * ConfigStore.load() 返回的是进程级缓存实例，播放页在切档/降级时会把
     * transcodeResolution / forceTranscode / transcodeAudioEncoder / 码率就地写进去；
     * 若不拷贝，这些只对「本次播放」有效的覆盖会泄漏到退出后的下一次播放，
     * 出现「OSD 显示自动/1080，实际按上次失败后的低档转码」。
     */
    public ServerConfig copy() {
        return fromJson(toJson());
    }

    public String displayLabel() {
        return (serverName.length() > 0 ? serverName + " · " : "") + baseUrl;
    }

    public boolean hasCredentials() {
        return username.length() > 0 && password.length() > 0;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("baseUrl", baseUrl);
        m.put("username", username);
        m.put("password", password);
        m.put("appName", appName);
        m.put("token", token);
        m.put("userId", userId);
        m.put("deviceId", deviceId);
        m.put("signSecret", signSecret);
        m.put("signApiKey", signApiKey);
        m.put("signEnabled", Boolean.valueOf(signEnabled));
        m.put("apiMode", apiMode);
        m.put("sourceMethod", sourceMethod);
        m.put("loginPath", loginPath);
        m.put("serverInfoPath", serverInfoPath);
        m.put("libraryPath", libraryPath);
        m.put("trimClient", trimClient);
        m.put("trimClientVersion", trimClientVersion);
        m.put("mediaDbListPath", mediaDbListPath);
        m.put("librarySumPath", librarySumPath);
        m.put("mediaListPath", mediaListPath);
        m.put("tagListPath", tagListPath);
        m.put("tagGenresPath", tagGenresPath);
        m.put("tagCountriesPath", tagCountriesPath);
        m.put("itemInfoPath", itemInfoPath);
        m.put("favoritePath", favoritePath);
        m.put("watchedPath", watchedPath);
        m.put("episodeListPath", episodeListPath);
        m.put("continueListPath", continueListPath);
        m.put("streamListPath", streamListPath);
        m.put("playInfoPath", playInfoPath);
        m.put("playPlayPath", playPlayPath);
        m.put("playRecordPath", playRecordPath);
        m.put("originalStreamPath", originalStreamPath);
        m.put("transcodePath", transcodePath);
        m.put("transcodeControlPath", transcodeControlPath);
        m.put("subtitleDlPath", subtitleDlPath);
        m.put("subtitleSearchPath", subtitleSearchPath);
        m.put("subtitleDownloadPath", subtitleDownloadPath);
        m.put("subtitlePredownloadPath", subtitlePredownloadPath);
        m.put("subtitleDelPath", subtitleDelPath);
        m.put("subtitleMarkPath", subtitleMarkPath);
        m.put("serverPathPath", serverPathPath);
        m.put("authorizedDirPath", authorizedDirPath);
        m.put("mediaControlPath", mediaControlPath);
        m.put("userInfoPath", userInfoPath);
        m.put("userDataGetPath", userDataGetPath);
        m.put("userDataSetPath", userDataSetPath);
        m.put("nasTranscodeCapable", Boolean.valueOf(nasTranscodeCapable));
        m.put("gpuAcceleration", Boolean.valueOf(gpuAcceleration));
        m.put("cpuDecoding", Boolean.valueOf(cpuDecoding));
        m.put("directLinkEnabled", Boolean.valueOf(directLinkEnabled));
        m.put("serverName", serverName);
        m.put("lastVerifiedAt", Long.valueOf(lastVerifiedAt));
        m.put("forceTranscode", Boolean.valueOf(forceTranscode));
        m.put("transcodeVideoEncoder", transcodeVideoEncoder);
        m.put("transcodeResolution", transcodeResolution);
        m.put("transcodeAudioEncoder", transcodeAudioEncoder);
        m.put("autoDiscoverOnStart", Boolean.valueOf(autoDiscoverOnStart));
        m.put("connectTimeoutMs", Integer.valueOf(connectTimeoutMs));
        m.put("readTimeoutMs", Integer.valueOf(readTimeoutMs));
        return m;
    }

    public static ServerConfig fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        ServerConfig c = new ServerConfig();
        c.baseUrl = Json.str(m, "baseUrl", DEFAULT_HOST);
        c.username = Json.str(m, "username", "");
        c.password = Json.str(m, "password", "");
        c.appName = Json.str(m, "appName", DEFAULT_APP_NAME);
        c.token = Json.str(m, "token", "");
        c.userId = Json.str(m, "userId", "");
        c.deviceId = Json.str(m, "deviceId", "");
        c.signSecret = Json.str(m, "signSecret", "");
        c.signApiKey = Json.str(m, "signApiKey", "");
        c.signEnabled = Json.bool(m, "signEnabled", true);
        c.apiMode = Json.str(m, "apiMode", "FNOS");
        c.sourceMethod = Json.str(m, "sourceMethod", "MANUAL");
        c.loginPath = Json.str(m, "loginPath", c.loginPath);
        c.serverInfoPath = Json.str(m, "serverInfoPath", c.serverInfoPath);
        c.libraryPath = Json.str(m, "libraryPath", c.libraryPath);
        c.trimClient = Json.str(m, "trimClient", c.trimClient);
        c.trimClientVersion = Json.str(m, "trimClientVersion", c.trimClientVersion);
        c.mediaDbListPath = Json.str(m, "mediaDbListPath", c.mediaDbListPath);
        c.librarySumPath = Json.str(m, "librarySumPath", c.librarySumPath);
        c.mediaListPath = Json.str(m, "mediaListPath", c.mediaListPath);
        c.tagListPath = Json.str(m, "tagListPath", c.tagListPath);
        c.tagGenresPath = Json.str(m, "tagGenresPath", c.tagGenresPath);
        c.tagCountriesPath = Json.str(m, "tagCountriesPath", c.tagCountriesPath);
        c.itemInfoPath = Json.str(m, "itemInfoPath", c.itemInfoPath);
        c.favoritePath = Json.str(m, "favoritePath", c.favoritePath);
        c.watchedPath = Json.str(m, "watchedPath", c.watchedPath);
        c.episodeListPath = Json.str(m, "episodeListPath", c.episodeListPath);
        c.continueListPath = Json.str(m, "continueListPath", c.continueListPath);
        c.streamListPath = Json.str(m, "streamListPath", c.streamListPath);
        c.playInfoPath = Json.str(m, "playInfoPath", c.playInfoPath);
        c.playPlayPath = Json.str(m, "playPlayPath", c.playPlayPath);
        c.playRecordPath = Json.str(m, "playRecordPath", c.playRecordPath);
        c.originalStreamPath = Json.str(m, "originalStreamPath", c.originalStreamPath);
        c.transcodePath = Json.str(m, "transcodePath", c.transcodePath);
        c.transcodeControlPath = Json.str(m, "transcodeControlPath", c.transcodeControlPath);
        c.subtitleDlPath = Json.str(m, "subtitleDlPath", c.subtitleDlPath);
        c.subtitleSearchPath = Json.str(m, "subtitleSearchPath", c.subtitleSearchPath);
        c.subtitleDownloadPath = Json.str(m, "subtitleDownloadPath", c.subtitleDownloadPath);
        c.subtitlePredownloadPath = Json.str(m, "subtitlePredownloadPath", c.subtitlePredownloadPath);
        c.subtitleDelPath = Json.str(m, "subtitleDelPath", c.subtitleDelPath);
        c.subtitleMarkPath = Json.str(m, "subtitleMarkPath", c.subtitleMarkPath);
        c.serverPathPath = Json.str(m, "serverPathPath", c.serverPathPath);
        c.authorizedDirPath = Json.str(m, "authorizedDirPath", c.authorizedDirPath);
        c.mediaControlPath = Json.str(m, "mediaControlPath", c.mediaControlPath);
        c.userInfoPath = Json.str(m, "userInfoPath", c.userInfoPath);
        c.userDataGetPath = Json.str(m, "userDataGetPath", c.userDataGetPath);
        c.userDataSetPath = Json.str(m, "userDataSetPath", c.userDataSetPath);
        c.nasTranscodeCapable = Json.bool(m, "nasTranscodeCapable", true);
        c.gpuAcceleration = Json.bool(m, "gpuAcceleration", false);
        c.cpuDecoding = Json.bool(m, "cpuDecoding", true);
        c.directLinkEnabled = Json.bool(m, "directLinkEnabled", true);
        c.serverName = Json.str(m, "serverName", "");
        c.lastVerifiedAt = Json.longer(m, "lastVerifiedAt", 0L);
        c.forceTranscode = Json.bool(m, "forceTranscode", false);
        c.transcodeVideoEncoder = Json.str(m, "transcodeVideoEncoder", "");
        c.transcodeResolution = Json.str(m, "transcodeResolution", "");
        c.transcodeAudioEncoder = Json.str(m, "transcodeAudioEncoder", "");
        c.autoDiscoverOnStart = Json.bool(m, "autoDiscoverOnStart", true);
        c.connectTimeoutMs = Json.integer(m, "connectTimeoutMs", 5000);
        c.readTimeoutMs = Json.integer(m, "readTimeoutMs", 10000);
        // 旧版本「本地显示缩放」会把选中档位写进 transcodeResolution 但不开 forceTranscode，
        // 客户端已改为全部走 NAS 转码；这类残留值必须清掉，否则升级后会自动锁死在低档。
        if (!c.forceTranscode) {
            c.transcodeResolution = "";
        }
        return c;
    }
}
