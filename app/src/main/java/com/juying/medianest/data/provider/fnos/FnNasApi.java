package com.juying.medianest.data.provider.fnos;

import com.juying.medianest.core.util.L;
import com.juying.medianest.domain.port.MediaApi;
import com.juying.medianest.core.model.AudioTrack;
import com.juying.medianest.core.util.CodecNames;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.policy.FilterEngine;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.FsEntry;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.model.SubtitleSearchItem;
import com.juying.medianest.core.policy.ViewRules;
import com.juying.medianest.core.model.Library;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.Person;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.PlaybackSession;
import com.juying.medianest.core.policy.NasLevels;
import com.juying.medianest.core.util.NetUtil;
import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.RouteOption;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.policy.SessionPolicy;
import com.juying.medianest.data.api.HttpStack;
import com.juying.medianest.data.api.HttpStack.Response;
import com.juying.medianest.core.model.SubtitleTrack;
import com.juying.medianest.core.model.StreamSource;
import com.juying.medianest.core.policy.TranscodeProfile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞牛影视（trim.media）真实接口适配。
 *
 * 端点与签名规则来自对 nas.example.com:5666 的实测（2026-11-18）：
 *   POST /v/api/v1/login                  {"username","password","app_name"} -> data.token
 *   GET  /v/api/v1/server/info            服务器名 + gpu_acc / cpu_allow_decoding / direct_link_enable
 *   GET  /v/api/v1/mdb/list               媒体库（data 直接是数组）
 *   GET  /v/api/v1/mediadb/sum            各媒体库条目数
 *   POST /v/api/v1/item/list              {"page":1,"page_size":N} -> data.list[]
 *   GET  /v/api/v1/item/{guid}             详情：backdrops / posters / logos
 *   GET  /v/api/v1/episode/list/{guid}    季内集列表（data 直接是数组）
 *   GET  /v/api/v1/play/list              继续观看
 *   POST /v/api/v1/play/info              {"item_guid":...} -> media_guid / ts
 *   GET  /v/api/v1/stream/list/{guid}     video_streams / audio_streams / files（含真实编码）
 *   GET  /v/api/v1/media/range/{guid}     原始字节范围流（免签名）
 *
 * 除 /media/ /img/ /concat/ 等白名单外，每个请求都要带 authx 签名。
 * 这些是私有接口，路径全部可在 ServerConfig 里覆盖，升级失效时改配置即可。
 */
public final class FnNasApi implements MediaApi {

    private static final int ENRICH_LIMIT = 12;
    /** item/list 单页条数；带客户端版本头后直播频道会混入影视结果，必须翻页取全。 */
    private static final int MEDIA_PAGE_SIZE = 200;
    private static final int MAX_MEDIA_PAGES = 20;
    /** 直播频道单页条数；实测单页 200 可一次取回全部 125 条，上限页数只是防御。 */
    private static final int LIVE_PAGE_SIZE = 200;
    private static final int LIVE_MAX_PAGES = 20;

    public ConfigCodec.ServerInfo verify(ServerConfig cfg) throws FnException {
        if (cfg.baseUrl.length() == 0) {
            throw new FnException(FailureReason.CONNECTION_FAILED, "服务器地址为空");
        }
        if (!cfg.hasCredentials()) {
            throw new FnException(FailureReason.AUTH_FAILED, "请先在设置里填写飞牛影视账号与密码（不是 fnOS 系统账号）");
        }
        // 已有 token 就直接复用：call() 遇到 -2 会自动重登一次。
        // 旧实现每次首页加载都强制 login，token 频繁更换，且登录偶发失败会被
        // 显示成「账号或密码不正确」，看起来像授权被清空了。
        ensureToken(cfg);

        // 用一个带签名的业务请求确认签名常量仍然有效
        Object data = call(cfg, "GET", cfg.serverInfoPath, null, null);
        Map<String, Object> info = Json.map(data);
        cfg.serverName = Json.str(info, "name", "");
        cfg.gpuAcceleration = truthy(info.get("gpu_acc"));
        cfg.cpuDecoding = truthy(info.get("cpu_allow_decoding"));
        cfg.directLinkEnabled = truthy(info.get("direct_link_enable"));
        cfg.nasTranscodeCapable = cfg.gpuAcceleration || cfg.cpuDecoding || truthy(info.get("gpu_prefer"));
        cfg.lastVerifiedAt = System.currentTimeMillis();

        ConfigCodec.ServerInfo s = new ConfigCodec.ServerInfo();
        s.baseUrl = cfg.baseUrl;
        s.name = cfg.serverName;
        s.port = NetUtil.portOf(cfg.baseUrl);
        s.method = "MANUAL";
        s.verified = true;
        s.signature = "fnos";
        s.latencyMs = 0L;
        L.i("FnNasApi", "已连接 " + cfg.displayLabel() + " 转码能力=" + cfg.nasTranscodeCapable);
        return s;
    }

    /** POST /v/api/v1/login，成功后把 token 写回配置（由调用方保存到 ConfigStore）。 */
    public void login(ServerConfig cfg) throws FnException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("username", cfg.username);
        body.put("password", cfg.password);
        body.put("app_name", cfg.appName);
        Object data = rawCall(cfg, "POST", cfg.loginPath, null, body, false);
        String token = Json.str(Json.map(data), "token", "");
        if (token.length() == 0) {
            throw new FnException(FailureReason.AUTH_FAILED, "登录响应里没有 token");
        }
        cfg.token = token;
        L.i("FnNasApi", "登录成功，token 长度 " + token.length());
    }

    /** 供本地鉴权代理在媒体流鉴权失败时重新登录；返回新 token，失败抛异常。 */
    public String refreshToken(ServerConfig cfg) throws FnException {
        login(cfg);
        return cfg.token == null ? "" : cfg.token;
    }

    public String authorizationHeader(ServerConfig cfg) {
        return cfg.token == null ? "" : cfg.token;
    }

    public List<MediaItem> listMedia(ServerConfig cfg, boolean enrich, int limit) throws FnException {
        ensureToken(cfg);
        // 带上 X-Trim-Client-Version 后，item/list 会把 334 个直播频道与 18 条影视放在同一结果集里，
        // 且大部分直播排在影视前面。只取第 1 页（200 条）会拿到 198 个直播 + 2 个目录，
        // 影视条目全被挤到第 2 页 —— 表现为「除直播源外其他媒体库都不加载」。
        // 因此按服务端 total 翻页取全，再由本地过滤 LiveChannel，避免依赖类型枚举。
        List<Object> arr = new ArrayList<Object>();
        int total = -1;
        for (int page = 1; page <= MAX_MEDIA_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("page", Integer.valueOf(page));
            body.put("page_size", Integer.valueOf(MEDIA_PAGE_SIZE));
            Object data = call(cfg, "POST", cfg.mediaListPath, null, body);
            Map<String, Object> root = Json.map(data);
            List<Object> one = Json.list(root.get("list"));
            if (total < 0) {
                total = Json.integer(root, "total", one.size());
            }
            arr.addAll(one);
            if (one.isEmpty() || arr.size() >= total) {
                break;
            }
        }
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size() && out.size() < limit; i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            MediaItem item = new MediaItem();
            item.guid = Json.str(m, "guid", "");
            item.title = firstNonEmpty(Json.str(m, "title", ""), Json.str(m, "tv_title", ""), "未命名");
            item.subtitle = firstNonEmpty(Json.str(m, "parent_title", ""), Json.str(m, "tv_title", ""), "");
            if (item.subtitle.equals(item.title)) {
                item.subtitle = "";
            }
            item.type = Json.str(m, "type", "Movie");
            item.mediaType = item.type;
            // 直播频道由 listLiveChannels 按 tags.type 单独拉取，这里统一排除避免重复
            if ("LiveChannel".equalsIgnoreCase(item.type)) {
                continue;
            }
            item.posterPath = posterOf(m);
            item.year = Json.str(m, "release_date", "");
            item.runtimeMinutes = Json.integer(m, "runtime", 0);
            item.durationSeconds = Json.longer(m, "duration", 0L);
            // 网页端展示字段
            item.voteAverage = Json.doubleNum(m, "vote_average", 0.0);
            // 本地入库季数/集数优先：网页端剧集详情显示「共 2 季」，而 number_of_seasons
            // 是 TMDB 元数据（实测 1），local_number_of_seasons 才是本地真实季数（实测 2）。
            item.seasonCount = Json.integer(m, "local_number_of_seasons", 0);
            if (item.seasonCount == 0) {
                item.seasonCount = Json.integer(m, "number_of_seasons", 0);
            }
            item.episodeCount = Json.integer(m, "local_number_of_episodes", 0);
            if (item.episodeCount == 0) {
                item.episodeCount = Json.integer(m, "number_of_episodes", 0);
            }
            item.overview = snippet(Json.str(m, "overview", ""));
            item.favorite = truthy(m.get("is_favorite"));
            item.childGuid = Json.str(m, "single_child_guid", "");
            item.libraryName = Json.str(m, "ancestor_name", "");
            item.libraryGuid = Json.str(m, "ancestor_guid", "");
            item.progressPercent = progressOf(m);
            // item/list 自带的流媒体概要，作为筛选元数据的兜底（详情补齐失败时仍有值）
            Map<String, Object> stream = Json.map(m.get("media_stream"));
            addStrings(item.resolutions, stream.get("resolutions"));
            addStrings(item.colorRanges, stream.get("color_range_type"));
            addStrings(item.audioTypes, stream.get("audio_type"));
            item.watched = watchedOf(m, item.progressPercent);
            item.matchState = Json.str(m, "trim_id", "").length() > 0 ? 1 : 0;
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        if (enrich) {
            int n = Math.min(out.size(), ENRICH_LIMIT);
            for (int i = 0; i < n; i++) {
                try {
                    fillStreamInfo(cfg, out.get(i));
                } catch (FnException ex) {
                    L.w("FnNasApi", "条目 " + out.get(i).guid + " 编码信息读取失败：" + ex.detail);
                    // 编码未知时 StreamSelector 会保守地走 NAS 解码流，不会崩
                }
            }
        }
        L.i("FnNasApi", "媒体列表 " + out.size() + " 条");
        return out;
    }

    public List<MediaItem> search(ServerConfig cfg, String query) throws FnException {
        ensureToken(cfg);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("q", query == null ? "" : query);
        Object data = call(cfg, "GET", "/v/api/v1/search/list", params, null);
        List<Object> arr = Json.list(data);
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            MediaItem item = new MediaItem();
            item.guid = Json.str(m, "guid", "");
            item.title = firstNonEmpty(Json.str(m, "title", ""),
                    Json.str(m, "tv_title", ""), "未命名");
            item.subtitle = firstNonEmpty(Json.str(m, "parent_title", ""), "", "");
            if (item.subtitle.equals(item.title)) {
                item.subtitle = "";
            }
            item.type = Json.str(m, "type", "Movie");
            item.mediaType = item.type;
            item.posterPath = posterOf(m);
            item.year = Json.str(m, "release_date", "");
            item.runtimeMinutes = Json.integer(m, "runtime", 0);
            item.durationSeconds = Json.longer(m, "duration", 0L);
            item.voteAverage = Json.doubleNum(m, "vote_average", 0.0);
            item.childGuid = Json.str(m, "single_child_guid", "");
            item.libraryName = Json.str(m, "ancestor_name", "");
            item.libraryGuid = Json.str(m, "ancestor_guid", "");
            item.progressPercent = progressOf(m);
            Map<String, Object> stream = Json.map(m.get("media_stream"));
            addStrings(item.resolutions, stream.get("resolutions"));
            addStrings(item.colorRanges, stream.get("color_range_type"));
            addStrings(item.audioTypes, stream.get("audio_type"));
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        L.i("FnNasApi", "搜索「" + query + "」命中 " + out.size() + " 条");
        return out;
    }

    /**
     * 继续观看：GET /v/api/v1/play/list 的 data 直接是数组。
     * 网页端首页横卡取 tv_title 作标题、第 N 季·第 M 集作副标题、ts/duration 画进度条。
     */
    public List<MediaItem> listContinue(ServerConfig cfg) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.continueListPath, null, null);
        List<Object> arr = Json.list(data);
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            MediaItem item = new MediaItem();
            item.guid = Json.str(m, "guid", "");
            String tvTitle = Json.str(m, "tv_title", "");
            String epTitle = Json.str(m, "title", "");
            item.title = firstNonEmpty(tvTitle, Json.str(m, "parent_title", ""), epTitle);
            item.subtitle = firstNonEmpty(epTitle, "", "");
            if (item.subtitle.equals(item.title)) {
                item.subtitle = "";
            }
            item.type = Json.str(m, "type", "Episode");
            item.mediaType = item.type;
            item.posterPath = posterOf(m);
            item.seasonCount = Json.integer(m, "season_number", 0);
            item.episodeCount = Json.integer(m, "episode_number", 0);
            item.runtimeMinutes = Json.integer(m, "runtime", 0);
            item.durationSeconds = Json.longer(m, "duration", 0L);
            item.mediaGuid = Json.str(m, "media_guid", "");
            item.videoGuid = Json.str(m, "video_guid", "");
            item.audioGuid = Json.str(m, "audio_guid", "");
            item.subtitleGuid = Json.str(m, "subtitle_guid", "");
            item.progressPercent = progressOf(m);
            // 继续观看同样按服务端的已看标记/进度判断，首页卡片要画「已观看」图标
            item.watched = watchedOf(m, item.progressPercent);
            // play/list 的 parent_guid 对单集就是所属季 guid（实测同季 9 集同 parent），
            // 播放器用它拉兄弟集，继续观看进入的单集也能用选集 / 上下集。
            item.seasonGuid = Json.str(m, "parent_guid", "");
            item.libraryName = Json.str(m, "ancestor_name", "");
            item.libraryGuid = Json.str(m, "ancestor_guid", "");
            // 直播频道的 guid 是 64 位（影视条目 32 位）；play/list 有时不带 type，
            // 用 guid 长度兜底，保证详情页能按直播呈现「播放直播」而不是「继续播放」。
            if (item.guid.length() > 40) {
                item.type = "LiveChannel";
                item.mediaType = "LiveChannel";
            }
            // 首页「继续观看」点击后直接起播，播放器按 resumeSeconds 定位：
            // ts 就是已观看秒数（progressOf 用它算百分比）。直播没有续播位置，保持 0。
            if (!"LiveChannel".equals(item.mediaType)) {
                long watchedSec = Json.longer(m, "ts", 0L);
                item.resumeSeconds = watchedSec > 0
                        ? (int) Math.min(watchedSec, Integer.MAX_VALUE) : 0;
            }
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        L.i("FnNasApi", "继续观看 " + out.size() + " 条");
        return out;
    }

    /**
     * 集列表：入参必须是「真正的季 guid」。网页端两级详情页里，剧集详情先经
     * listSeasons() 拿到季，季详情再用季 guid 调本方法，因此这里不再需要下钻；
     * 旧版对季 guid 再做一次 resolveSeasonGuid 会误判集标题、串到别的季里。
     */
    public List<MediaItem> listEpisodes(ServerConfig cfg, String seasonGuid) throws FnException {
        ensureToken(cfg);
        return fetchEpisodes(cfg, seasonGuid);
    }

    /** 按已知季 guid 拉集列表。 */
    private List<MediaItem> fetchEpisodes(ServerConfig cfg, String seasonGuid) throws FnException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("page", Integer.valueOf(1));
        body.put("page_size", Integer.valueOf(200));
        body.put("parent_guid", seasonGuid);
        Object data = call(cfg, "POST", cfg.mediaListPath, null, body);
        List<Object> arr = Json.list(Json.map(data).get("list"));
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            MediaItem item = new MediaItem();
            item.guid = Json.str(m, "guid", "");
            // 网页端集卡片是「第 N 集 · 集名」，tv_title 只是剧名，不能当集名用
            item.title = firstNonEmpty(Json.str(m, "title", ""), Json.str(m, "tv_title", ""), "未命名");
            // 副标题放剧名，播放器标题就是「集名 · 剧名」，不与集名重复
            item.subtitle = Json.str(m, "tv_title", "");
            if (item.subtitle.equals(item.title)) {
                item.subtitle = "";
            }
            item.type = "Episode";
            item.mediaType = "Episode";
            // 记住所属季：播放器据此拉兄弟集，实现选集 / 上一集 / 下一集
            item.seasonGuid = seasonGuid;
            item.posterPath = posterOf(m);
            item.episodeCount = Json.integer(m, "episode_number", 0);
            item.seasonCount = Json.integer(m, "season_number", 0);
            item.runtimeMinutes = Json.integer(m, "runtime", 0);
            item.durationSeconds = Json.longer(m, "duration", 0L);
            item.voteAverage = Json.doubleNum(m, "vote_average", 0.0);
            item.overview = snippet(Json.str(m, "overview", ""));
            item.progressPercent = progressOf(m);
            Map<String, Object> ms = Json.map(m.get("media_stream"));
            addStrings(item.resolutions, ms.get("resolutions"));
            item.mediaGuid = Json.str(m, "media_guid", "");
            item.videoGuid = Json.str(m, "video_guid", "");
            item.audioGuid = Json.str(m, "audio_guid", "");
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        // NAS 的 item/list 返回顺序不按集号（实测首条是第4集），网页端是按集号升序展示
        java.util.Collections.sort(out, new java.util.Comparator<MediaItem>() {
            public int compare(MediaItem a, MediaItem b) {
                return a.episodeCount < b.episodeCount ? -1 : (a.episodeCount == b.episodeCount ? 0 : 1);
            }
        });
        L.i("FnNasApi", "季 " + seasonGuid + " 剧集 " + out.size() + " 集");
        return out;
    }

    /**
     * 演职人员：POST /v/api/v1/person/list/{itemGuid}。
     * 返回 item_guid / person_guid / role / job / order / name / original_name /
     * profile_path / biography，详情页按 order 升序横向展示。
     */
    public List<Person> listPersons(ServerConfig cfg, String itemGuid) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("page", Integer.valueOf(1));
        body.put("page_size", Integer.valueOf(100));
        Object data = call(cfg, "POST", "/v/api/v1/person/list/" + itemGuid, null, body);
        List<Object> arr = Json.list(Json.map(data).get("list"));
        final List<Person> out = new ArrayList<Person>();
        for (int i = 0; i < arr.size(); i++) {
            Person p = Person.fromJson(Json.map(arr.get(i)));
            if (p.personGuid.length() > 0) {
                out.add(p);
            }
        }
        // 同一 order 保持 NAS 原序（Collections.sort 是稳定排序）
        java.util.Collections.sort(out, new java.util.Comparator<Person>() {
            public int compare(Person a, Person b) {
                return a.order < b.order ? -1 : (a.order == b.order ? 0 : 1);
            }
        });
        L.i("FnNasApi", "演职人员 " + out.size() + " 人");
        return out;
    }

    /** 人物详情：GET /v/api/v1/person/{personGuid}，字段 guid/name/original_name/profile/biography。 */
    public Person loadPerson(ServerConfig cfg, String personGuid) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", "/v/api/v1/person/" + personGuid, null, null);
        Map<String, Object> m = Json.map(data);
        if (m.isEmpty()) {
            throw new FnException(FailureReason.UNKNOWN, "未读取到人物信息");
        }
        return Person.fromDetailJson(m);
    }

    /** 人物作品：POST /v/api/v1/person/item/list，body {person_guid}。 */
    public List<MediaItem> listPersonItems(ServerConfig cfg, String personGuid)
            throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("person_guid", personGuid);
        body.put("page", Integer.valueOf(1));
        body.put("page_size", Integer.valueOf(100));
        Object data = call(cfg, "POST", "/v/api/v1/person/item/list", null, body);
        List<Object> arr = Json.list(Json.map(data).get("list"));
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            MediaItem item = new MediaItem();
            item.guid = Json.str(m, "guid", "");
            item.title = firstNonEmpty(Json.str(m, "title", ""),
                    Json.str(m, "tv_title", ""), "未命名");
            item.subtitle = firstNonEmpty(Json.str(m, "parent_title", ""),
                    Json.str(m, "tv_title", ""), "");
            if (item.subtitle.equals(item.title)) {
                item.subtitle = "";
            }
            item.type = Json.str(m, "type", "Movie");
            item.mediaType = item.type;
            item.posterPath = posterOf(m);
            item.year = Json.str(m, "release_date", "");
            item.runtimeMinutes = Json.integer(m, "runtime", 0);
            item.durationSeconds = Json.longer(m, "duration", 0L);
            item.voteAverage = Json.doubleNum(m, "vote_average", 0.0);
            item.childGuid = Json.str(m, "single_child_guid", "");
            item.libraryName = Json.str(m, "ancestor_name", "");
            item.libraryGuid = Json.str(m, "ancestor_guid", "");
            item.progressPercent = progressOf(m);
            Map<String, Object> stream = Json.map(m.get("media_stream"));
            addStrings(item.resolutions, stream.get("resolutions"));
            addStrings(item.colorRanges, stream.get("color_range_type"));
            addStrings(item.audioTypes, stream.get("audio_type"));
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        L.i("FnNasApi", "人物作品 " + out.size() + " 部");
        return out;
    }

    /** 媒体库列表：GET /v/api/v1/mediadb/list 的 data 直接是数组，新增/删除库会实时反映。 */
    public List<Library> listLibraries(ServerConfig cfg) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.mediaDbListPath, null, null);
        List<Object> arr = Json.list(data);
        List<Library> out = new ArrayList<Library>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            Library lib = new Library();
            lib.guid = Json.str(m, "guid", "");
            // mdb/list 用 name，mediadb/list 用 title，两个端点都兼容
            lib.name = firstNonEmpty(Json.str(m, "name", ""), Json.str(m, "title", ""), "未命名媒体库");
            lib.category = Json.str(m, "category", "");
            List<Object> ps = Json.list(m.get("posters"));
            for (int k = 0; k < ps.size() && k < 4; k++) {
                lib.posters.add(String.valueOf(ps.get(k)));
            }
            if (lib.guid.length() > 0) {
                out.add(lib);
            }
        }
        L.i("FnNasApi", "媒体库 " + out.size() + " 个");
        return out;
    }

    /**
     * 直播频道：网页端 /v/list/live 的真实请求体是
     * {"tags":{"type":["LiveChannel"]},"sort_type":"DESC","sort_column":"sort_title","page":1,"page_size":N}。
     * 顶层 type/category 参数会被固件忽略（返回影视库 18 条），必须放在 tags.type 里。
     */
    public List<MediaItem> listLiveChannels(ServerConfig cfg, int limit) throws FnException {
        ensureToken(cfg);
        List<String> types = new ArrayList<String>();
        types.add("LiveChannel");
        Map<String, Object> tags = new LinkedHashMap<String, Object>();
        tags.put("type", types);
        // 网页端 /v/list/live 只按 22 条一页请求，但服务端 total 是真实频道总数（实测 125），
        // 只取单页会让列表被截断。这里按 total 翻页拉全，limit 仅作为本地安全上限。
        int total = -1;
        List<Object> arr = new ArrayList<Object>();
        for (int page = 1; page <= LIVE_MAX_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("tags", tags);
            body.put("sort_type", "DESC");
            body.put("sort_column", "sort_title");
            body.put("page", Integer.valueOf(page));
            body.put("page_size", Integer.valueOf(LIVE_PAGE_SIZE));
            Object data = call(cfg, "POST", cfg.mediaListPath, null, body);
            Map<String, Object> root = Json.map(data);
            List<Object> one = Json.list(root.get("list"));
            if (total < 0) {
                total = Json.integer(root, "total", one.size());
            }
            arr.addAll(one);
            if (one.isEmpty() || arr.size() >= total || arr.size() >= limit) {
                break;
            }
        }
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size() && out.size() < limit; i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            MediaItem item = new MediaItem();
            item.guid = Json.str(m, "guid", "");
            item.title = firstNonEmpty(Json.str(m, "title", ""), Json.str(m, "tv_title", ""), "未命名频道");
            item.type = "LiveChannel";
            item.mediaType = "LiveChannel";
            item.posterPath = posterOf(m);
            item.libraryGuid = Json.str(m, "ancestor_guid", "");
            item.libraryName = Json.str(m, "ancestor_name", "");
            item.favorite = truthy(m.get("is_favorite"));
            item.voteAverage = Json.doubleNum(m, "vote_average", 0.0);
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        L.i("FnNasApi", "直播频道 " + out.size() + " 个");
        return out;
    }

    /** mediadb/sum 返回 {库guid: 条数, total, movie, tv, live, video, favorite}。 */
    public Map<String, Integer> libraryCounts(ServerConfig cfg) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.librarySumPath, null, null);
        Map<String, Object> m = Json.map(data);
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        java.util.Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> e = it.next();
            if (e.getValue() instanceof Number) {
                out.put(e.getKey(), Integer.valueOf(((Number) e.getValue()).intValue()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 筛选面板：tag/list 按范围返回可用维度，item/{guid} 补齐条目的实际取值
    // ------------------------------------------------------------------

    /** genre id → 中文名、ISO3166 → 中文名、详情响应缓存，避免每次切页重复请求。 */
    private Map<String, String> genreNames;
    private Map<String, String> countryNames;
    private final Map<String, Map<String, Object>> detailCache =
            new LinkedHashMap<String, Map<String, Object>>();

    /**
     * 网页端各库「筛选」下拉项不同的根源：GET /tag/list?&lt;scope&gt; 只返回当前范围内
     * 有值的维度（例如 type=LiveChannel 只有 genres，自定义其他视频库只有 3 个流媒体维度）。
     * 这里把服务端返回的 id/枚举值映射成中文显示文本，交给 FilterEngine 生成面板。
     */
    public FilterEngine.TagSet filterTags(ServerConfig cfg, String scope) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.tagListPath, scopeParams(scope), null);
        Map<String, Object> m = Json.map(data);
        Map<String, String> genres = genreNames(cfg);
        Map<String, String> countries = countryNames(cfg);

        FilterEngine.TagSet out = new FilterEngine.TagSet();
        out.put(ViewRules.G_GENRE, mapped(m.get("genres"), genres, 'g'));
        out.put(ViewRules.G_RESOLUTION, mapped(m.get("resolutions"), null, 'r'));
        out.put(ViewRules.G_COLOR, mapped(m.get("color_range"), null, 'c'));
        out.put(ViewRules.G_AUDIO, mapped(m.get("audio_type"), null, 'a'));
        out.put(ViewRules.G_COUNTRY, mapped(m.get("locate"), countries, 'g'));
        out.put(ViewRules.G_YEAR, mapped(m.get("decades"), decadeNames(), 'g'));
        out.put(ViewRules.G_MATCH, mapped(m.get("recognition_status"), matchNames(), 'g'));
        return out;
    }

    /** 补齐条目用于本地筛选的元数据；单条失败不影响其它条目与页面渲染。 */
    public void enrichFilterMeta(ServerConfig cfg, List<MediaItem> items) {
        if (items == null) {
            return;
        }
        Map<String, String> genres = genreNamesQuiet(cfg);
        Map<String, String> countries = countryNamesQuiet(cfg);
        int n = 0;
        for (int i = 0; i < items.size() && n < 120; i++, n++) {
            MediaItem item = items.get(i);
            if (item == null || item.guid.length() == 0) {
                continue;
            }
            Map<String, Object> d = detailCache.get(item.guid);
            if (d == null) {
                try {
                    d = Json.map(call(cfg, "GET", cfg.itemInfoPath + item.guid, null, null));
                    detailCache.put(item.guid, d);
                } catch (Throwable t) {
                    L.w("FnNasApi", "筛选元数据读取失败 " + item.guid + "：" + t.getMessage());
                    continue;
                }
            }
            List<Object> gs = Json.list(d.get("genres"));
            for (int k = 0; k < gs.size(); k++) {
                String name = genres.get(String.valueOf(gs.get(k)));
                if (name != null) {
                    item.genres.add(name);
                }
            }
            List<Object> cs = Json.list(d.get("production_countries"));
            for (int k = 0; k < cs.size(); k++) {
                String name = countries.get(String.valueOf(cs.get(k)));
                if (name != null) {
                    item.countries.add(name);
                }
            }
            Map<String, Object> ms = Json.map(d.get("media_stream"));
            addStrings(item.resolutions, ms.get("resolutions"));
            addStrings(item.colorRanges, ms.get("color_range_type"));
            addStrings(item.audioTypes, ms.get("audio_type"));
            item.watched = watchedOf(d, item.progressPercent);
            item.matchState = Json.str(d, "trim_id", "").length() > 0 ? 1 : 0;
            // 横幅视图用 16:9 剧照，避免把竖版海报裁成横条
            String backdrop = Json.str(d, "backdrops", "");
            if (backdrop.length() > 0) {
                item.backdropPath = backdrop;
            }
        }
    }

    private Map<String, String> genreNames(ServerConfig cfg) throws FnException {
        if (genreNames == null) {
            genreNames = dictionary(cfg, cfg.tagGenresPath, "id");
        }
        return genreNames;
    }

    private Map<String, String> countryNames(ServerConfig cfg) throws FnException {
        if (countryNames == null) {
            countryNames = dictionary(cfg, cfg.tagCountriesPath, "key");
        }
        return countryNames;
    }

    private Map<String, String> genreNamesQuiet(ServerConfig cfg) {
        try {
            return genreNames(cfg);
        } catch (Throwable t) {
            return new LinkedHashMap<String, String>();
        }
    }

    private Map<String, String> countryNamesQuiet(ServerConfig cfg) {
        try {
            return countryNames(cfg);
        } catch (Throwable t) {
            return new LinkedHashMap<String, String>();
        }
    }

    private Map<String, String> dictionary(ServerConfig cfg, String path, String keyField)
            throws FnException {
        ensureToken(cfg);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("lan", "zh-CN");
        Object data = call(cfg, "GET", path, params, null);
        List<Object> arr = Json.list(data);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            Object key = m.get(keyField);
            String value = Json.str(m, "value", "");
            if (key != null && value.length() > 0) {
                out.put(String.valueOf(key), value);
            }
        }
        return out;
    }

    private static Map<String, String> decadeNames() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("Recent", "今年");
        m.put("2020s", "2020年代");
        m.put("2010s", "2010年代");
        m.put("2000s", "其他");
        m.put("Others", "其他");
        return m;
    }

    private static Map<String, String> matchNames() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("1", "未匹配");
        m.put("2", "已匹配");
        m.put("3", "读取 NFO");
        return m;
    }

    /** 'g' 字典直取 / 'r' 分辨率 / 'c' 动态范围 / 'a' 音频规格。 */
    private static List<String> mapped(Object raw, Map<String, String> dict, char kind) {
        List<Object> arr = Json.list(raw);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.size(); i++) {
            String s = String.valueOf(arr.get(i));
            if (dict != null && dict.containsKey(s)) {
                s = dict.get(s);
            } else if (kind == 'r') {
                s = FilterEngine.normalizeResolution(s);
            } else if (kind == 'c') {
                s = FilterEngine.normalizeColor(s);
            } else if (kind == 'a') {
                s = FilterEngine.normalizeAudio(s);
            }
            if (s.length() > 0 && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static void addStrings(List<String> out, Object raw) {
        List<Object> arr = Json.list(raw);
        for (int i = 0; i < arr.size(); i++) {
            String s = String.valueOf(arr.get(i));
            if (s.length() > 0 && !"null".equals(s) && !out.contains(s)) {
                out.add(s);
            }
        }
    }

    private static Map<String, String> scopeParams(String scope) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (scope == null || scope.length() == 0) {
            return out;
        }
        String[] parts = scope.split("&");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                out.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }
        return out;
    }

    /**
     * 季列表：item/list 带 parent_guid=剧集 guid，返回 type=Season 的季条目。
     * 网页端剧集详情页显示的是季卡片（第 N 季 · 共 N 集 · 年份），不是集数网格。
     * 注意固件的 episode_number 在季条目里被填成「该季集数」，season_number 才是季号。
     */
    public List<MediaItem> listSeasons(ServerConfig cfg, String showGuid) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("page", Integer.valueOf(1));
        body.put("page_size", Integer.valueOf(100));
        body.put("parent_guid", showGuid);
        Object data = call(cfg, "POST", cfg.mediaListPath, null, body);
        List<Object> arr = Json.list(Json.map(data).get("list"));
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            if (!"Season".equalsIgnoreCase(Json.str(m, "type", ""))) {
                continue;
            }
            MediaItem s = new MediaItem();
            s.guid = Json.str(m, "guid", "");
            s.title = Json.str(m, "title", "");
            s.mediaType = "Season";
            s.type = "Season";
            s.posterPath = posterOf(m);
            s.seasonCount = Json.integer(m, "season_number", 0);
            // 固件把季条目的 episode_number 填成 TMDB 全剧集数（实测第 1 季=24、第 2 季=12），
            // 网页端季卡片显示的是本地实际集数（12 / 12），用 /episode/list/{guid} 精确统计。
            s.episodeCount = seasonEpisodeCount(cfg, s.guid);
            if (s.episodeCount == 0) {
                s.episodeCount = Json.integer(m, "episode_number", 0);
            }
            // 网页端季卡片右下角的清晰度角标（1080P / 4K）
            List<Object> resolutions = Json.list(Json.map(m.get("media_stream")).get("resolutions"));
            for (int k = 0; k < resolutions.size() && s.height == 0; k++) {
                s.height = resolutionHeight(String.valueOf(resolutions.get(k)));
            }
            s.year = firstNonEmpty(Json.str(m, "air_date", ""), Json.str(m, "release_date", ""), "");
            s.voteAverage = Json.doubleNum(m, "vote_average", 0.0);
            s.overview = snippet(Json.str(m, "overview", ""));
            s.libraryName = Json.str(m, "ancestor_name", "");
            s.libraryGuid = Json.str(m, "ancestor_guid", "");
            if (s.guid.length() > 0) {
                out.add(s);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<MediaItem>() {
            public int compare(MediaItem a, MediaItem b) {
                return a.seasonCount < b.seasonCount ? -1 : (a.seasonCount == b.seasonCount ? 0 : 1);
            }
        });
        L.i("FnNasApi", "剧集 " + showGuid + " 共 " + out.size() + " 季");
        return out;
    }

    /** 网页端 GET /episode/list/{guid}：data 直接是数组，用来精确统计该季本地集数。 */
    private int seasonEpisodeCount(ServerConfig cfg, String seasonGuid) {
        try {
            Object data = call(cfg, "GET", cfg.episodeListPath + seasonGuid, null, null);
            return Json.list(data).size();
        } catch (Throwable t) {
            L.w("FnNasApi", "季集数统计失败，退回固件字段：" + t.getMessage());
            return 0;
        }
    }

    /** "1080p" → 1080，用于网页端季卡片右下角清晰度角标。 */
    private static int resolutionHeight(String r) {
        if (r == null) {
            return 0;
        }
        String s = r.toLowerCase().trim();
        if (!s.endsWith("p")) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(0, s.length() - 1).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 网页端详情页背景图规则（从 trimemedia-web JS 端点表 + 实测确认）：
     * GET /v/api/v1/item/{guid} 返回 backdrops / posters / logos 三个不同字段。
     *   - TV 剧集组：backdrops 有值，是 16:9 横版大图（实测 1280×720），
     *     与竖版 posters（2000×2827）完全不同源 → 网页端剧集详情就用它当背景。
     *   - Season 季：backdrops 为空 → 网页端退回把竖版海报模糊放大当背景。
     * 因此这里只返回精确字段；为空时由调用方按网页端规则做海报模糊兜底。
     */
    public String showBackdrop(ServerConfig cfg, String itemGuid) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.itemInfoPath + itemGuid, null, null);
        return Json.str(Json.map(data), "backdrops", "");
    }

    /**
     * 官方详情页补齐。item/{guid} 比列表多出 logos、content_ratings、watched_ts、
     * tv_title（季详情用剧名做标题）以及完整 overview，全部按网页端字段名读取。
     */
    public void loadDetail(ServerConfig cfg, MediaItem item) throws FnException {
        ensureToken(cfg);
        Map<String, Object> d = detailCache.get(item.guid);
        if (d == null) {
            d = Json.map(call(cfg, "GET", cfg.itemInfoPath + item.guid, null, null));
            detailCache.put(item.guid, d);
        }
        if (d.isEmpty()) {
            return;
        }
        String type = Json.str(d, "type", "");
        if (type.length() > 0) {
            item.type = type;
            item.mediaType = type;
        }
        String tvTitle = Json.str(d, "tv_title", "");
        if (tvTitle.length() > 0) {
            item.showTitle = tvTitle;
        }
        String title = Json.str(d, "title", "");
        if (title.length() > 0) {
            item.title = title;
        }
        String parentTitle = Json.str(d, "parent_title", "");
        if (parentTitle.length() > 0) {
            item.subtitle = parentTitle;
        }
        if (item.subtitle.equals(item.title)) {
            item.subtitle = "";
        }
        item.logoPath = firstNonEmpty(Json.str(d, "logos", ""), item.logoPath, "");
        item.backdropPath = firstNonEmpty(Json.str(d, "backdrops", ""), item.backdropPath, "");
        item.posterPath = firstNonEmpty(Json.str(d, "posters", ""), item.posterPath, "");
        item.overview = firstNonEmpty(Json.str(d, "overview", ""), item.overview, "");
        item.contentRating = firstNonEmpty(Json.str(d, "content_ratings", ""),
                item.contentRating, "");
        // 官方详情页年份用 air_date（JOJO 实测 release_date=2012、air_date=2026，官方显示 2026）
        item.year = firstNonEmpty(Json.str(d, "air_date", ""),
                firstNonEmpty(Json.str(d, "release_date", ""), item.year, ""), "");
        int runtime = Json.integer(d, "runtime", 0);
        if (runtime > 0) {
            item.runtimeMinutes = runtime;
        }
        long duration = Json.longer(d, "duration", 0L);
        if (duration > 0L) {
            item.durationSeconds = duration;
        }
        double vote = Json.doubleNum(d, "vote_average", 0.0);
        if (vote > 0) {
            item.voteAverage = vote;
        }
        item.favorite = truthy(d.get("is_favorite")) || item.favorite;
        // 详情响应带 is_watched 时以它为准（取消已看要能真的取消），只有字段缺失才沿用列表快照。
        item.watched = d.containsKey("is_watched") ? truthy(d.get("is_watched")) : item.watched;
        item.watchedSeconds = Json.integer(d, "watched_ts", 0);
        item.libraryName = firstNonEmpty(Json.str(d, "ancestor_name", ""), item.libraryName, "");
        item.childGuid = firstNonEmpty(Json.str(d, "single_child_guid", ""), item.childGuid, "");
        if (item.isSeason()) {
            // 季自身 season_number 是「第 N 季」，页面标题不用它，但卡片副标题可能用到
            int sn = Json.integer(d, "season_number", 0);
            if (sn > 0) {
                item.seasonCount = sn;
            }
            int epCount = Json.integer(d, "local_number_of_episodes", 0);
            if (epCount > 0) {
                item.episodeCount = epCount;
            }
        } else {
            int seasons = Json.integer(d, "local_number_of_seasons", 0);
            if (seasons == 0) {
                seasons = Json.integer(d, "number_of_seasons", 0);
            }
            if (seasons > 0) {
                item.seasonCount = seasons;
            }
            int episodes = Json.integer(d, "local_number_of_episodes", 0);
            if (episodes == 0) {
                episodes = Json.integer(d, "number_of_episodes", 0);
            }
            if (episodes > 0) {
                item.episodeCount = episodes;
            }
        }
        Map<String, Object> ms = Json.map(d.get("media_stream"));
        item.resolutions.clear();
        addStrings(item.resolutions, ms.get("resolutions"));
        item.colorRanges.clear();
        addStrings(item.colorRanges, ms.get("color_range_type"));
        item.audioTypes.clear();
        addStrings(item.audioTypes, ms.get("audio_type"));
        item.genres.clear();
        Map<String, String> genres = genreNamesQuiet(cfg);
        List<Object> gs = Json.list(d.get("genres"));
        // 官方按 genre id 升序展示（电影实测 动画16 · 音乐10402 · 科幻奇幻10765），这里保持一致。
        List<Integer> genreIds = new ArrayList<Integer>();
        for (int k = 0; k < gs.size(); k++) {
            try {
                genreIds.add(Integer.valueOf(String.valueOf(gs.get(k))));
            } catch (NumberFormatException ignored) {
                // 非数字 id 直接忽略
            }
        }
        java.util.Collections.sort(genreIds);
        // 官方 web 端在同时存在 Action(1) / Adventure(2) 时不重复显示合并项 30(Action & Adventure)：
        // JOJO 的 genres=[1,2,3,30,7,9]，官方 meta 是 动作 · 冒险 · 动画 · 剧情，第 4 个不是「动作冒险」。
        boolean hasAction = genreIds.contains(Integer.valueOf(1));
        boolean hasAdventure = genreIds.contains(Integer.valueOf(2));
        for (int k = 0; k < genreIds.size(); k++) {
            String id = String.valueOf(genreIds.get(k));
            if (hasAction && hasAdventure && "30".equals(id)) {
                continue;
            }
            String name = genres.get(id);
            if (name != null) {
                item.genres.add(name);
            }
        }
        item.countries.clear();
        Map<String, String> countries = countryNamesQuiet(cfg);
        List<Object> cs = Json.list(d.get("production_countries"));
        for (int k = 0; k < cs.size(); k++) {
            String name = countries.get(String.valueOf(cs.get(k)));
            if (name != null) {
                item.countries.add(name);
            }
        }
    }

    /** stream/list 的 audio_streams，供电影详情页「音频」下拉。 */
    public List<AudioTrack> listAudioTracks(ServerConfig cfg, MediaItem item) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
        List<Object> arr = Json.list(Json.map(data).get("audio_streams"));
        List<AudioTrack> out = new ArrayList<AudioTrack>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            AudioTrack t = new AudioTrack();
            t.guid = Json.str(m, "guid", "");
            t.title = Json.str(m, "title", "");
            t.language = Json.str(m, "language", "");
            t.audioType = Json.str(m, "audio_type", "");
            t.isDefault = truthy(m.get("is_default"));
            if (t.guid.length() > 0) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 收藏 / 取消收藏。网页端端点表：PUT /item/favorite 加收藏、DELETE /item/favorite 取消，
     * body 都是 {item_guid}，成功 code=0。写完服务端 item/list 的 is_favorite 即同步，
     * 因此收藏页与详情页都以服务端为准，不做本地持久化。
     */
    public void setFavorite(ServerConfig cfg, String guid, boolean favorite) throws FnException {
        ensureToken(cfg);
        if (guid == null || guid.length() == 0) {
            throw new FnException(FailureReason.UNKNOWN, "缺少 item_guid，无法收藏");
        }
        if (favorite) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("item_guid", guid);
            call(cfg, "PUT", cfg.favoritePath, null, body);
            L.i("FnNasApi", "PUT " + cfg.favoritePath + " item_guid=" + guid + " 成功");
        } else {
            // 取消收藏只有 DELETE + {item_guid} 一种可用写法（PUT 带 favorite:false 实测无效），
            // 老系统 DELETE 带 body 由 HttpStack 用原生 Socket 发。
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("item_guid", guid);
            call(cfg, "DELETE", cfg.favoritePath, null, body);
            L.i("FnNasApi", "DELETE " + cfg.favoritePath + " item_guid=" + guid + " 成功");
        }
    }

    /** GET /item/{guid} 的 is_favorite，用于详情页重新进入时校正本地状态。 */
    public boolean isFavorite(ServerConfig cfg, String guid) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.itemInfoPath + guid, null, null);
        boolean fav = truthy(Json.map(data).get("is_favorite"));
        L.i("FnNasApi", "读取收藏状态 item_guid=" + guid + " is_favorite=" + fav);
        return fav;
    }

    /**
     * 标记已看 / 取消已看。网页端接口表实测：{@code watched: POST /item/watched} 标记、
     * {@code delWatched: DELETE /item/watched} 取消，body 都是 {item_guid}（与收藏同一套键名，
     * 用 {guid} 服务端直接回 code=-1 Invalid Params）；成功 code=0，
     * item/list 的 watched 与 item/{guid} 的 is_watched 随之同步，网页端同一账号同步可见。
     * 取消已看会连带清掉服务端记录的 watched_ts 续播位置，这是服务端行为。
     */
    public void setWatched(ServerConfig cfg, String guid, boolean watched) throws FnException {
        ensureToken(cfg);
        if (guid == null || guid.length() == 0) {
            throw new FnException(FailureReason.UNKNOWN, "缺少 item_guid，无法标记已看");
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("item_guid", guid);
        if (watched) {
            call(cfg, "POST", cfg.watchedPath, null, body);
            L.i("FnNasApi", "POST " + cfg.watchedPath + " item_guid=" + guid + " 成功");
        } else {
            call(cfg, "DELETE", cfg.watchedPath, null, body);
            L.i("FnNasApi", "DELETE " + cfg.watchedPath + " item_guid=" + guid + " 成功");
        }
        // 详情响应有缓存，不失效的话重进详情页还是旧的 is_watched，标记看起来「没生效」。
        detailCache.remove(guid);
    }

    /** GET /item/{guid} 的 is_watched，用于详情页重新进入时校正本地状态。 */
    public boolean isWatched(ServerConfig cfg, String guid) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.itemInfoPath + guid, null, null);
        boolean seen = truthy(Json.map(data).get("is_watched"));
        L.i("FnNasApi", "读取已看状态 item_guid=" + guid + " is_watched=" + seen);
        return seen;
    }

    /**
     * 服务端的已看字段优先：item/list 与 play/list 都带 watched，item/{guid} 带 is_watched。
     * 只有字段缺失（老服务端）才退回「进度 ≥95% 视为已看」，否则取消已看后残留的续播进度
     * 会把条目永远显示成已观看，标记来回失效。
     */
    private static boolean watchedOf(Map<String, Object> m, int progressPercent) {
        if (m.containsKey("watched") || m.containsKey("is_watched")) {
            return truthy(m.get("watched")) || truthy(m.get("is_watched"));
        }
        return progressPercent >= 95;
    }

    /** ts/duration 换算 0~100 进度，网页端继续观看蓝色进度条。 */
    private static int progressOf(Map<String, Object> m) {
        long ts = Json.longer(m, "ts", 0L);
        long duration = Json.longer(m, "duration", 0L);
        if (duration <= 0) {
            return 0;
        }
        int pct = (int) (ts * 100L / duration);
        return pct < 0 ? 0 : (pct > 100 ? 100 : pct);
    }

    /** 网页端海报：/v/api/v1/sys/img + /xx/xx/xx.webp?w=宽度（免签名，带 Authorization）。 */
    public String imageUrl(ServerConfig cfg, String posterPath, int width) {
        if (posterPath == null || posterPath.length() == 0) {
            return "";
        }
        String path = posterPath.startsWith("/") ? posterPath : "/" + posterPath;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        // width<=0 时取原图：带 w 参数会被服务端转成无 alpha 的 webp（logo 会变黑底）
        String url = NetUtil.join(cfg.baseUrl, "/v/api/v1/sys/img" + path);
        return width > 0 ? url + "?w=" + width : url;
    }

    private static boolean isLiveChannel(MediaItem item) {
        String t = item == null || item.mediaType == null ? "" : item.mediaType.toLowerCase();
        return t.indexOf("live") >= 0 || t.indexOf("channel") >= 0;
    }

    /**
     * 网页端直播点击后直接播放 /v/live/&lt;guid&gt;，起播用的是
     * GET /stream/list/&lt;guid&gt; 里 files[0].path 的第三方 m3u8 直链。
     * 实测 POST /play/play 对直播返回 -6 Not Found，不能走影视的转码会话。
     */
    private List<StreamCandidate> liveStreams(ServerConfig cfg, MediaItem item)
            throws FnException {
        Object data = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
        List<Object> files = Json.list(Json.map(data).get("files"));
        List<StreamCandidate> out = new ArrayList<StreamCandidate>();
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> f = Json.map(files.get(i));
            String path = Json.str(f, "path", "");
            if (path.length() == 0) {
                continue;
            }
            // 线路名称完全取 NAS 的 file_name；缺失时用 guid 前缀，不写死「线路 N」
            String name = Json.str(f, "file_name", "");
            if (name.length() == 0) {
                String fg = Json.str(f, "guid", "");
                name = fg.length() > 8 ? fg.substring(0, 8) : fg;
            }
            if (i == 0) {
                String mediaGuid = Json.str(f, "guid", "");
                if (mediaGuid.length() > 0) {
                    item.mediaGuid = mediaGuid;
                }
                item.fileName = name;
            }
            StreamCandidate c = StreamCandidate.original(path, item);
            c.id = "live:" + i;
            c.label = "直播直链 · " + name;
            out.add(c);
        }
        L.i("FnNasApi", "直播直链 " + out.size() + " 条");
        return out;
    }

    /** 字幕轨道固定从 NAS 的 stream/list 读取（subtitle_streams）。 */
    public List<SubtitleTrack> listSubtitles(ServerConfig cfg, MediaItem item)
            throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
        List<Object> arr = Json.list(Json.map(data).get("subtitle_streams"));
        List<SubtitleTrack> out = new ArrayList<SubtitleTrack>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            SubtitleTrack t = new SubtitleTrack();
            t.guid = Json.str(m, "guid", "");
            t.title = Json.str(m, "title", "");
            t.language = Json.str(m, "language", "");
            t.format = Json.str(m, "format", t.format.length() > 0
                    ? t.format : Json.str(m, "codec_name", ""));
            t.isDefault = truthy(m.get("is_default"));
            t.isExternal = truthy(m.get("is_external"));
            t.hasDownloadableFile = t.isExternal || truthy(m.get("extra_file"))
                    || Json.str(m, "url", "").length() > 0;
            t.mediaGuid = Json.str(m, "media_guid", "");
            t.url = Json.str(m, "url", "");
            if (t.guid.length() > 0) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 外挂字幕文本：GET /subtitle/dl/&lt;guid&gt;。
     *
     * 网页端默认取 <b>ASS 原文</b>（SubtitleRenderer 用 AssRenderer + wasm 渲染），
     * 只有设备不支持 ASS 时才强制 fmt=vtt。ASS 里保留了双语样式、\pos 定位、
     * \fs 字号、\frz 旋转、\fad 淡入淡出；转成 VTT 会把花式字幕全部压平，
     * 所以这里优先 fmt=ass，拿不到才退回 fmt=vtt。
     *
     * 该端点不走 rawCall（它强制解析 JSON envelope），这里直接读响应体。
     */
    public String subtitleContent(ServerConfig cfg, String guid, String format)
            throws FnException {
        ensureToken(cfg);
        String ass = downloadSubtitle(cfg, guid, "ass");
        if (ass.length() > 0 && (ass.indexOf("[Script Info]") >= 0
                || ass.indexOf("Dialogue:") >= 0)) {
            L.i("FnNasApi", "字幕取到 ASS 原文（保留双语与花式定位） " + ass.length() + " 字节");
            return ass;
        }
        String vtt = downloadSubtitle(cfg, guid, "vtt");
        if (vtt.length() > 0) {
            L.i("FnNasApi", "字幕退回 WebVTT（花式定位已由 NAS 压平） " + vtt.length() + " 字节");
            return vtt;
        }
        return ass.length() > 0 ? ass : "";
    }

    private String downloadSubtitle(ServerConfig cfg, String guid, String fmt)
            throws FnException {
        String path = cfg.subtitleDlPath + guid;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("fmt", fmt);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Trim-Client", cfg.trimClient);
        headers.put("X-Trim-Client-Version", cfg.trimClientVersion);
        if (cfg.signEnabled && !CseSigner.isUnsignedPath(path)) {
            headers.put("authx", CseSigner.sign("GET", path, params, "",
                    cfg.signSecret, cfg.signApiKey));
        }
        if (cfg.token != null && cfg.token.length() > 0) {
            headers.put("Authorization", cfg.token);
        }
        String url = NetUtil.appendQuery(NetUtil.join(cfg.baseUrl, path),
                CseSigner.buildQuery(params));
        Response r;
        try {
            r = HttpStack.request("GET", url, headers, null,
                    cfg.connectTimeoutMs, cfg.readTimeoutMs);
        } catch (FnException ex) {
            return ""; // 该格式不支持（404/501）时交给调用方退回另一种格式
        }
        if (!r.ok()) {
            return "";
        }
        String text = r.body == null ? "" : r.body;
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            try {
                Object d = Json.parseObject(trimmed).get("data");
                return d instanceof String ? (String) d : "";
            } catch (RuntimeException ignored) {
                return text;
            }
        }
        return text;
    }

    // ------------------------------------------------------------------
    // 在线字幕：搜索 / 下载 / 预下载 / 删除 / 标记 NAS 文件
    // 端点与字段来自官方 1.5.1 dex（/v/api/v1/subtitle/*）与网页端 bundle。
    // ------------------------------------------------------------------

    /** POST /subtitle/search {lan, media_guid} -> data.subtitles[]。 */
    public List<SubtitleSearchItem> searchSubtitles(ServerConfig cfg, String mediaGuid,
                                                    String lan) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("lan", lan == null ? "" : lan);
        body.put("media_guid", mediaGuid == null ? "" : mediaGuid);
        Object data = call(cfg, "POST", cfg.subtitleSearchPath, null, body);
        List<Object> arr = Json.list(Json.map(data).get("subtitles"));
        List<SubtitleSearchItem> out = new ArrayList<SubtitleSearchItem>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            SubtitleSearchItem s = new SubtitleSearchItem();
            s.trimId = Json.str(m, "trim_id", Json.str(m, "guid", ""));
            s.filename = Json.str(m, "filename", Json.str(m, "name", ""));
            s.format = Json.str(m, "format", Json.str(m, "ext", ""));
            s.language = Json.str(m, "language", Json.str(m, "lan", ""));
            s.fileSize = Json.longer(m, "file_size", Json.longer(m, "size", 0L));
            if (s.trimId.length() > 0 || s.filename.length() > 0) {
                out.add(s);
            }
        }
        L.i("FnNasApi", "在线字幕搜索 lan=" + lan + " 结果 " + out.size() + " 条");
        return out;
    }

    /** POST /subtitle/download {media_guid, trim_id, sync_download:1}，落盘后重新读轨道。 */
    public void downloadOnlineSubtitle(ServerConfig cfg, String mediaGuid, String trimId)
            throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("media_guid", mediaGuid);
        body.put("trim_id", trimId);
        body.put("sync_download", Integer.valueOf(1));
        call(cfg, "POST", cfg.subtitleDownloadPath, null, body);
        L.i("FnNasApi", "字幕下载完成 trim_id=" + trimId);
    }

    /** POST /subtitle/predownload {media_guid, subtitle_guid}。 */
    public void predownloadSubtitle(ServerConfig cfg, String mediaGuid, String subtitleGuid)
            throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("media_guid", mediaGuid);
        body.put("subtitle_guid", subtitleGuid);
        call(cfg, "POST", cfg.subtitlePredownloadPath, null, body);
        L.i("FnNasApi", "字幕预下载请求已提交 subtitle_guid=" + subtitleGuid);
    }

    /** DELETE /subtitle/del：优先 query 参数，失败退回 DELETE body。 */
    public void deleteSubtitle(ServerConfig cfg, String subtitleGuid) throws FnException {
        ensureToken(cfg);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("subtitle_guid", subtitleGuid);
        try {
            call(cfg, "DELETE", cfg.subtitleDelPath, params, null);
        } catch (FnException ex) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("subtitle_guid", subtitleGuid);
            call(cfg, "DELETE", cfg.subtitleDelPath, null, body);
        }
        L.i("FnNasApi", "字幕已删除 subtitle_guid=" + subtitleGuid);
    }

    /** PUT /subtitle/mark {media_guid, filepaths}：把 NAS 上的文件挂成该媒体的字幕。 */
    public void markSubtitles(ServerConfig cfg, String mediaGuid, List<String> filepaths)
            throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("media_guid", mediaGuid);
        body.put("filepaths", new ArrayList<Object>(filepaths));
        call(cfg, "PUT", cfg.subtitleMarkPath, null, body);
        L.i("FnNasApi", "已标记 NAS 字幕文件 " + filepaths.size() + " 个");
    }

    /** GET /server/getAppAuthorizedDir -> data.authDirList[].path。 */
    public List<String> authorizedDirs(ServerConfig cfg) throws FnException {
        ensureToken(cfg);
        Object data = call(cfg, "GET", cfg.authorizedDirPath, null, null);
        List<Object> arr = Json.list(Json.map(data).get("authDirList"));
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.size(); i++) {
            String p = Json.str(Json.map(arr.get(i)), "path", "");
            if (p.length() > 0) {
                out.add(p);
            }
        }
        return out;
    }

    /** POST /server/path {path}：NAS 文件浏览，data 直接是数组。 */
    public List<FsEntry> listDirectory(ServerConfig cfg, String path) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("path", path);
        Object data = call(cfg, "POST", cfg.serverPathPath, null, body);
        List<Object> arr = Json.list(data);
        List<FsEntry> out = new ArrayList<FsEntry>();
        for (int i = 0; i < arr.size(); i++) {
            Map<String, Object> m = Json.map(arr.get(i));
            FsEntry e = new FsEntry();
            e.filename = Json.str(m, "filename", "");
            e.isDir = truthy(m.get("is_dir"));
            e.fileSize = Json.longer(m, "file_size", 0L);
            e.trimName = Json.str(m, "trim_name", "");
            e.trimId = Json.str(m, "trim_id", "");
            if (e.filename.length() > 0) {
                out.add(e);
            }
        }
        // 目录在前，文件按名称排序，避免大目录里翻不到字幕
        java.util.Collections.sort(out, new java.util.Comparator<FsEntry>() {
            public int compare(FsEntry a, FsEntry b) {
                if (a.isDir != b.isDir) {
                    return a.isDir ? -1 : 1;
                }
                return a.filename.compareToIgnoreCase(b.filename);
            }
        });
        return out;
    }

    /**
     * 可用画质挡位完全来自 NAS：tag/list 的 resolutions（当前条目类型范围内实际有值）
     * 与 stream/list 的 video_streams[].resolution_type（该片原片画质）。
     * 不内置任何 4K/1080P/720P 档位表；NAS 没给就返回空列表。
     */
    public List<QualityLevel> qualityLevels(ServerConfig cfg, MediaItem item)
            throws FnException {
        ensureToken(cfg);
        // 首选：POST /play/quality 返回该片真实可播放的画质/码率挡位。
        // 实测返回 [{bitrate, resolution:"4k"/"1080"/"720"...}, ...]，按码率降序，
        // 比 tag/list 的库级维度更精确，且不需要创建转码会话。
        List<Object> playQuality = null;
        try {
            String mediaGuid = item.mediaGuid;
            if (mediaGuid.length() == 0) {
                Object streams = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
                List<Object> files = Json.list(Json.map(streams).get("files"));
                if (!files.isEmpty()) {
                    mediaGuid = Json.str(Json.map(files.get(0)), "guid", "");
                }
            }
            if (mediaGuid.length() > 0) {
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("item_guid", item.guid);
                body.put("media_guid", mediaGuid);
                Object data = call(cfg, "POST", cfg.transcodeControlPath, null, body);
                List<Object> arr = Json.list(data);
                playQuality = new ArrayList<Object>();
                for (int i = 0; i < arr.size(); i++) {
                    playQuality.add(Json.str(Json.map(arr.get(i)), "resolution", ""));
                }
            }
        } catch (FnException ex) {
            L.w("FnNasApi", "play/quality 画质挡位读取失败：" + ex.detail);
        } catch (Throwable t) {
            L.w("FnNasApi", "play/quality 解析失败：" + t);
        }
        if (playQuality != null && !playQuality.isEmpty()) {
            List<QualityLevel> out = NasLevels.parseQualities(playQuality, null);
            if (!out.isEmpty()) {
                L.i("FnNasApi", "NAS 可用画质挡位（play/quality）" + out.size()
                        + " 档：" + NasLevels.signature(out));
                return out;
            }
        }
        List<Object> tagResolutions = null;
        List<Object> videoStreams = null;
        String type = item == null || item.mediaType == null ? "" : item.mediaType;
        String scope = type.length() > 0 ? "type=" + type : null;
        try {
            Object tags = call(cfg, "GET", cfg.tagListPath, scopeParams(scope), null);
            tagResolutions = Json.list(Json.map(tags).get("resolutions"));
        } catch (FnException ex) {
            L.w("FnNasApi", "tag/list 画质挡位读取失败：" + ex.detail);
        }
        if (!isLiveChannel(item) && (tagResolutions == null || tagResolutions.isEmpty())) {
            // 某些类型（如 Episode）该维度为空：退回全库维度，仍由 NAS 决定；
            // 直播没有画质维度，不套用影视库的挡位
            try {
                Object all = call(cfg, "GET", cfg.tagListPath, scopeParams(null), null);
                tagResolutions = Json.list(Json.map(all).get("resolutions"));
            } catch (FnException ex) {
                L.w("FnNasApi", "tag/list 全库画质挡位读取失败：" + ex.detail);
            }
        }
        try {
            Object streams = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
            videoStreams = Json.list(Json.map(streams).get("video_streams"));
        } catch (FnException ex) {
            L.w("FnNasApi", "stream/list 原片画质读取失败：" + ex.detail);
        }
        List<QualityLevel> out = NasLevels.parseQualities(tagResolutions, videoStreams);
        L.i("FnNasApi", "NAS 可用画质挡位 " + NasLevels.signature(out));
        return out;
    }

    /**
     * 直播线路挡位完全来自 NAS：网页端 play/info 的 live_channels，
     * 并用 media_guid 定位当前生效线路；接口不可用时退回 stream/list.files。
     */
    public List<RouteOption> liveRoutes(ServerConfig cfg, MediaItem item)
            throws FnException {
        ensureToken(cfg);
        List<Object> channels = null;
        List<Object> files = null;
        String activeGuid = "";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("item_guid", item.guid);
        try {
            Object info = call(cfg, "POST", cfg.playInfoPath, null, body);
            Map<String, Object> m = Json.map(info);
            channels = Json.list(m.get("live_channels"));
            activeGuid = Json.str(m, "media_guid", "");
        } catch (FnException ex) {
            L.w("FnNasApi", "play/info 直播线路读取失败：" + ex.detail);
        }
        try {
            Object streams = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
            files = Json.list(Json.map(streams).get("files"));
        } catch (FnException ex) {
            L.w("FnNasApi", "stream/list 线路读取失败：" + ex.detail);
        }
        if (activeGuid.length() > 0) {
            item.mediaGuid = activeGuid;
        }
        List<RouteOption> out = NasLevels.parseRoutes(channels, files);
        L.i("FnNasApi", "NAS 直播线路挡位 " + out.size() + " 条，当前 " + activeGuid);
        return out;
    }

    /** 网页端播放器定时 POST /play/record {item_guid, media_guid, ts}；播放结束 ts=0。 */
    public void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended) {
        try {
            ensureToken(cfg);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("item_guid", item.guid);
            body.put("media_guid", item.mediaGuid);
            body.put("ts", Integer.valueOf(ended ? 0 : Math.max(0, seconds)));
            call(cfg, "POST", cfg.playRecordPath, null, body);
        } catch (Throwable t) {
            L.w("FnNasApi", "播放进度上报失败：" + t.getMessage());
        }
    }

    public List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item) throws FnException {
        return getStreams(cfg, item, null);
    }

    public List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item,
                                            PlaybackRequest request) throws FnException {
        if (request != null) {
            cfg = request.applyTo(cfg);
            item.playbackPositionSec = (int) (request.startPositionMs / 1000L);
        }
        ensureToken(cfg);
        if (isLiveChannel(item)) {
            List<StreamCandidate> lives = liveStreams(cfg, item);
            if (!lives.isEmpty()) {
                return lives;
            }
        }
        fillStreamInfo(cfg, item);
        ensurePlayInfo(cfg, item);

        // 真机实测（2026-11-18 / TCL Android 4.2.1）：只有 POST /play/play 返回的
        // data.play_link = /v/media/<hash>/preset.m3u8 才是 NAS 解码后的真实 HLS；
        // 旧代码拼的 /wp/m3u8?vcodec=... 在当前固件返回 501，播放器会立刻退出。
        String playLink = "";
        try {
            playLink = startPlaySessionWithRetry(cfg, item, request);
            L.i("FnNasApi", "取得 NAS 解码流 play_link=" + playLink);
        } catch (FnException ex) {
            // 实测：NAS mediasrv 忙时 POST /play/play 会持续返回 1001
            // "System busy, please retry later."，重试也无效。此时不能让播放计划为空，
            // 改为回退到 /media/range 直连（本地鉴权代理会补 Authorization 头，实测 206）。
            L.w("FnNasApi", "播放会话创建失败（转码服务不可用），改用 NAS 直连：" + ex.detail);
        }

        String mediaGuid = item.mediaGuid;
        if (mediaGuid.length() == 0 && !item.fileGuids.isEmpty()) {
            mediaGuid = item.fileGuids.get(0);
        }
        if (mediaGuid.length() == 0) {
            mediaGuid = item.guid;
        }
        String authQuery = authQuery(cfg);
        List<StreamCandidate> out = new ArrayList<StreamCandidate>();

        // 直链模板一：media_guid（新版固件）
        StreamCandidate c1 = StreamCandidate.original(
                NetUtil.appendQuery(NetUtil.join(cfg.baseUrl, cfg.originalStreamPath + mediaGuid), authQuery), item);
        c1.id = "original:mediaGuid";
        c1.label = "原始流 · " + item.fileName;
        out.add(c1);

        // 直链模板二：item_guid（部分固件/网盘直链）
        if (!item.guid.equals(mediaGuid)) {
            StreamCandidate c2 = StreamCandidate.original(
                    NetUtil.appendQuery(NetUtil.join(cfg.baseUrl, cfg.originalStreamPath + item.guid), authQuery), item);
            c2.id = "original:itemGuid";
            c2.label = "原始流（备用地址） · " + item.fileName;
            out.add(c2);
        }

        if (playLink.length() == 0) {
            // 实测：/media/range/<item_guid> 恒返回 403，只有 <media_guid> 返回 206。
            // 转码不可用时必须丢掉 item_guid 备用地址，否则播放器会在 403 上反复空转，
            // 画面卡在「正在打开 原始流…」，字幕也无从渲染。
            for (int i = out.size() - 1; i >= 0; i--) {
                if ("original:itemGuid".equals(out.get(i).id)) {
                    out.remove(i);
                }
            }
            if (mediaGuid.equals(item.guid)) {
                L.w("FnNasApi", "stream/list 未给出 media_guid，直连回退可能被 NAS 拒绝");
            }
            // 转码服务不可用：把直连标记为 direct:，StreamSelector 会无条件保留，
            // 不再按设备能力把 4K/HEVC 原片判为「不可直连」而丢掉最后一条可播路径。
            for (int i = 0; i < out.size(); i++) {
                StreamCandidate c = out.get(i);
                c.id = "direct:" + c.id;
                c.fallbackOnly = true;
                c.label = "NAS 直连（转码服务不可用） · " + item.fileName;
            }
            L.i("FnNasApi", "已生成 " + out.size() + " 条 NAS 直连回退候选");
        }

        if (playLink.length() > 0) {
            // 交给 StreamSelector：设备放不了原始流时它会把这条排到队首，
            // 设备能直连时则排在队尾作为解码失败兜底。
            StreamCandidate tx = new StreamCandidate();
            tx.id = "nasPlayLink";
            tx.url = absoluteUrl(cfg, playLink);
            tx.source = StreamSource.NAS_TRANSCODE;
            tx.label = "NAS 解码流 · 真实 HLS 播放列表";
            tx.videoCodec = "h264";
            tx.audioCodec = "aac";
            tx.container = "hls";
            tx.protocol = "hls";
            tx.width = item.width > 0 ? item.width : 1920;
            tx.height = item.height > 0 ? item.height : 1080;
            tx.bitDepth = 8;
            // 不再无条件假定 NAS 输出的是 H.264：真机实测 4K HEVC 片在未要求
            // video_encoder 时，preset.m3u8 的 CODECS 仍是 hvc1.*，系统播放器会
            // 直接 error(1,-5003)。这里读回真实编码/分辨率，供 OSD 与诊断使用。
            readPlayLinkInfo(cfg, tx);
            tx.audioChannels = request != null ? request.audioChannels : item.audioChannels;

            // 播放列表的 CODECS 是模板（谎报 AAC），以 TS 的 PMT 为准做校验。
            // NAS 若没按要求转音频，自动换一个设备能解的备选编码重建会话。
            String realAudio = verifyAudioStream(cfg, tx);
            if (cfg.transcodeAudioEncoder.length() > 0 && realAudio.length() > 0
                    && !realAudio.equals(cfg.transcodeAudioEncoder)) {
                String fallback = audioFallback(cfg.transcodeAudioEncoder);
                L.w("FnNasApi", "NAS 实际输出 " + realAudio + "，与要求的 "
                        + cfg.transcodeAudioEncoder + " 不符；改用 " + fallback + " 重建会话");
                cfg.transcodeAudioEncoder = fallback;
                String discardedPlayLink = tx.url;
                try {
                    String again = startPlaySession(cfg, item, request);
                    if (again.length() > 0) {
                        tx.url = absoluteUrl(cfg, again);
                        tx.session = new PlaybackSession(tx.url, "", item.mediaGuid,
                                SessionPolicy.mediaDirOf(tx.url));
                        verifyAudioStream(cfg, tx);
                        try {
                            quitPlaySession(cfg, discardedPlayLink);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (FnException ex) {
                    L.w("FnNasApi", "备选音频编码重建会话失败：" + ex.detail);
                }
            }
            String sessionUrl = tx.url;
            tx.url = NetUtil.appendQuery(sessionUrl, request != null && request.fnosLongSegments
                    ? "long=1" : "window=" + (request == null ? 0 : request.playlistWindow));
            tx.session = new PlaybackSession(sessionUrl, "", item.mediaGuid,
                    SessionPolicy.mediaDirOf(sessionUrl));
            out.add(tx);
        }

        // NAS 已经产出的转码画质（若固件返回），优先级交给 StreamSelector
        List<StreamCandidate> qualities = readQualities(cfg, item);
        for (int i = 0; i < qualities.size(); i++) {
            out.add(qualities.get(i));
        }
        return out;
    }

    /**
     * 会话续期：复用当前 cfg 里的 video_encoder / audio_encoder / resolution，
     * 重新 POST /play/play 生成新 hash。参数必须与首播一致，否则新会话的
     * 分片编号/时长会变化，重映射后可能出现画面跳变或不可解的音轨。
     */
    /**
     * 网页端 POST /media/p，用 req 字段分派 checkPlayLink / transcodeStatis。
     * 该端点在网页端是「播放会话保活」通道，不能拿 rawCall 的 data 语义硬套。
     */
    private Map<String, Object> mediaControl(ServerConfig cfg, String req, String playLink)
            throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("req", req);
        body.put("reqid", "FnTV-" + System.currentTimeMillis());
        body.put("playLink", playLink);
        body.put("result", "succ");
        Object data = call(cfg, "POST", cfg.mediaControlPath, null, body);
        Map<String, Object> out = Json.map(data);
        // 部分固件把 errno/result 放在顶层而不是 data 里
        if (out.isEmpty()) {
            L.w("FnNasApi", "media/p 响应无 data，req=" + req);
        }
        return out;
    }

    public boolean isPlayLinkAlive(ServerConfig cfg, String playLink) throws FnException {
        if (playLink == null || playLink.length() == 0) {
            return false;
        }
        Map<String, Object> d = mediaControl(cfg, "media.checkPlayLink", playLink);
        // 网页端：errno===4100 表示会话已失效需要换 URL；其余（含 0）视为仍有效
        int errno = Json.integer(d, "errno", 0);
        boolean alive = errno != 4100 && !"fail".equals(Json.str(d, "result", ""));
        L.i("FnNasApi", "checkPlayLink errno=" + errno + " alive=" + alive);
        return alive;
    }

    public void touchPlaySession(ServerConfig cfg, String playLink) throws FnException {
        if (playLink == null || playLink.length() == 0) {
            return;
        }
        mediaControl(cfg, "media.transcodeStatis", playLink);
    }

    /**
     * 主动结束播放会话，让 NAS 立刻回收转码任务。
     * 切档/退出时旧会话若继续存活（心跳续命），新会话可能排队拿不到分片。
     */
    public void quitPlaySession(ServerConfig cfg, String playLink) throws FnException {
        if (playLink == null || playLink.length() == 0) {
            return;
        }
        mediaControl(cfg, "media.quit", playLink);
        L.i("FnNasApi", "已通知 NAS 结束播放会话 " + playLink);
    }

    public String refreshPlayLink(ServerConfig cfg, MediaItem item) throws FnException {
        return refreshPlayLink(cfg, item, null);
    }

    public String refreshPlayLink(ServerConfig cfg, MediaItem item,
                                  PlaybackRequest request) throws FnException {
        if (request != null) {
            cfg = request.applyTo(cfg);
            item.playbackPositionSec = (int) (request.startPositionMs / 1000L);
        }
        ensureToken(cfg);
        String link = startPlaySession(cfg, item);
        if (link.length() == 0) {
            throw new FnException(FailureReason.PLAYBACK_FAILED,
                    "重建播放会话未返回 play_link（可能 NAS 转码任务忙）");
        }
        return absoluteUrl(cfg, link);
    }

    public String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx)
            throws FnException {
        return resolveTranscodeUrl(cfg, item, tx, null);
    }

    public String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx,
                                      PlaybackRequest request) throws FnException {
        if (request != null) {
            cfg = request.applyTo(cfg);
            item.playbackPositionSec = (int) (request.startPositionMs / 1000L);
        }
        // 候选里已经带了真实 play_link 时直接返回，不再拼占位参数
        String fixed = tx.transcodeParams.get("playLink");
        if (fixed != null && fixed.length() > 0) {
            String url = absoluteUrl(cfg, fixed);
            tx.session = new PlaybackSession(url, "", item.mediaGuid,
                    SessionPolicy.mediaDirOf(url));
            return NetUtil.appendQuery(url, request != null && request.fnosLongSegments
                    ? "long=1" : "window=" + (request == null ? 0 : request.playlistWindow));
        }
        // 尽力通知 NAS 启动转码会话；不同固件请求体不同，失败不影响后续尝试
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("item_guid", item.guid);
            body.put("media_guid", item.mediaGuid);
            body.put("video_codec", tx.videoCodec);
            body.put("audio_codec", tx.audioCodec);
            body.put("max_width", Integer.valueOf(tx.width));
            body.put("max_height", Integer.valueOf(tx.height));
            body.put("max_bitrate", Integer.valueOf(8000));
            call(cfg, "POST", cfg.transcodeControlPath, null, body);
        } catch (FnException ex) {
            L.w("FnNasApi", "转码控制接口不可用（将继续尝试 HLS 地址）：" + ex.detail);
        }

        Map<String, String> q = new LinkedHashMap<String, String>();
        q.putAll(tx.transcodeParams);
        q.put("item_guid", item.guid);
        q.put("media_guid", item.mediaGuid);
        if (cfg.token.length() > 0) {
            q.put("token", cfg.token);
        }
        String url = NetUtil.appendQuery(NetUtil.join(cfg.baseUrl, cfg.transcodePath),
                CseSigner.buildQuery(q));
        tx.session = new PlaybackSession(url, "", item.mediaGuid, SessionPolicy.mediaDirOf(url));
        return url;
    }

    // ------------------------------------------------------------------
    // 账号级偏好（与网页端设置页共用同一份服务端数据）
    // ------------------------------------------------------------------

    /** GET /user/info：返回 lan（首选字幕语言）与 audio_lan（首选音频语言），空串表示任意语言。 */
    @Override
    public Map<String, String> fetchUserPrefs(ServerConfig cfg) throws FnException {
        ensureToken(cfg);
        Map<String, Object> info = Json.map(call(cfg, "GET", cfg.userInfoPath, null, null));
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("lan", Json.str(info, "lan", ""));
        result.put("audio_lan", Json.str(info, "audio_lan", ""));
        return result;
    }

    /** POST /user/info：两个语言字段必须一起提交，网页端同样如此。 */
    @Override
    public void updateUserPrefs(ServerConfig cfg, String lan, String audioLan) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("lan", lan);
        body.put("audio_lan", audioLan);
        call(cfg, "POST", cfg.userInfoPath, null, body);
    }

    /** POST /user/getData：读取账号级 JSON 配置；未设置过时 data.value 为空串。 */
    @Override
    public String fetchUserData(ServerConfig cfg, String key) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("key", key);
        Map<String, Object> data = Json.map(call(cfg, "POST", cfg.userDataGetPath, null, body));
        return Json.str(data, "value", "");
    }

    /** POST /user/setData：写入账号级 JSON 配置，value 必须是 JSON 字符串。 */
    @Override
    public void saveUserData(ServerConfig cfg, String key, String value) throws FnException {
        ensureToken(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("key", key);
        body.put("value", value);
        call(cfg, "POST", cfg.userDataSetPath, null, body);
    }

    // ------------------------------------------------------------------
    // 内部：带签名的请求
    // ------------------------------------------------------------------

    private void ensureToken(ServerConfig cfg) throws FnException {
        if (cfg.token == null || cfg.token.length() == 0) {
            login(cfg);
        }
    }

    /** 带一次自动重新登录的重试包装。 */
    private Object call(ServerConfig cfg, String method, String path,
                        Map<String, String> params, Map<String, Object> body) throws FnException {
        try {
            return rawCall(cfg, method, path, params, body, true);
        } catch (FnException ex) {
            if (ex.reason == FailureReason.AUTH_FAILED && ex.apiCode == -2) {
                L.w("FnNasApi", "token 失效，重新登录后重试一次");
                login(cfg);
                return rawCall(cfg, method, path, params, body, true);
            }
            throw ex;
        }
    }

    private Object rawCall(ServerConfig cfg, String method, String path,
                           Map<String, String> params, Map<String, Object> body,
                           boolean withToken) throws FnException {
        String query = CseSigner.buildQuery(params);
        String url = NetUtil.appendQuery(NetUtil.join(cfg.baseUrl, path), query);
        String bodyJson = (body == null) ? "" : Json.stringify(body);
        if (!"GET".equals(method) && body == null) {
            bodyJson = "{}";
        }

        Map<String, String> headers = new LinkedHashMap<String, String>();
        // 网页端必备：服务端按客户端版本过滤数据，缺少时看不到 IPTV 库与直播频道
        headers.put("X-Trim-Client", cfg.trimClient);
        headers.put("X-Trim-Client-Version", cfg.trimClientVersion);
        if (cfg.signEnabled && !CseSigner.isUnsignedPath(path)) {
            headers.put("authx", CseSigner.sign(method, path, params, bodyJson,
                    cfg.signSecret, cfg.signApiKey));
        }
        if (withToken && cfg.token != null && cfg.token.length() > 0) {
            headers.put("Authorization", cfg.token);
        }

        Response r = HttpStack.request(method, url, headers,
                "GET".equals(method) ? null : bodyJson,
                cfg.connectTimeoutMs, cfg.readTimeoutMs);
        if (!r.ok()) {
            throw FnException.fromHttp(r.status, path);
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(r.body);
        } catch (RuntimeException ex) {
            throw new FnException(FailureReason.SERVER_ERROR,
                    "响应不是 JSON：" + snippet(r.body), r.status, 0);
        }
        if (!root.containsKey("code")) {
            // nginx 的 {"message":"Not Implemented, please update."} 会走这里
            throw new FnException(FailureReason.SERVER_ERROR,
                    Json.str(root, "message", snippet(r.body)), r.status, 0);
        }
        int code = Json.integer(root, "code", 0);
        if (code != 0) {
            throw FnException.fromApiCode(code, Json.str(root, "msg", ""), path, r.status);
        }
        return root.get("data");
    }

    /** 从 stream/list 补齐 item 的真实编码、位深、分辨率、码率（能力匹配的输入）。 */
    private void fillStreamInfo(ServerConfig cfg, MediaItem item) throws FnException {
        Object data = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
        Map<String, Object> m = Json.map(data);

        List<Object> videos = Json.list(m.get("video_streams"));
        if (!videos.isEmpty()) {
            Map<String, Object> v = Json.map(videos.get(0));
            item.videoCodec = CodecNames.video(Json.str(v, "codec_name", ""));
            item.width = Json.integer(v, "width", 0);
            item.height = Json.integer(v, "height", 0);
            item.bitDepth = Json.integer(v, "bit_depth", 8);
            item.bitrateKbps = Json.integer(v, "bps", 0) / 1000;
            String wrapper = CodecNames.container(Json.str(v, "wrapper", ""));
            item.container = wrapper.length() > 0 ? wrapper : item.container;
            String color = Json.str(v, "color_range_type", "") + Json.str(v, "color_transfer", "");
            item.hdr = color.toLowerCase().indexOf("hdr") >= 0 || Json.integer(v, "dv_profile", 0) > 0;
            item.fileGuids.add(Json.str(v, "media_guid", ""));
            // play/info 的 video_guid 实测恒为空，而 /play/play 缺它必回
            // 1001 System busy（见 docs/API.md 第 7 节）；真实 guid 只有这里给。
            String vg = Json.str(v, "guid", "");
            if (item.videoGuid.length() == 0 && vg.length() > 0) {
                item.videoGuid = vg;
            }
        }
        List<Object> audios = Json.list(m.get("audio_streams"));
        if (!audios.isEmpty()) {
            Map<String, Object> a = Json.map(audios.get(0));
            item.audioCodec = CodecNames.audio(Json.str(a, "codec_name", ""));
            // 声道数是老设备能否解码的关键：真机 5.1ch AAC 打不开 OMX 音频解码器
            item.audioChannels = Json.integer(a, "channels", 0);
            String ag = Json.str(a, "guid", "");
            if (item.audioGuid.length() == 0 && ag.length() > 0) {
                item.audioGuid = ag;
            }
        }
        List<Object> files = Json.list(m.get("files"));
        if (!files.isEmpty()) {
            Map<String, Object> f = Json.map(files.get(0));
            item.fileName = Json.str(f, "file_name", "");
            item.canPlay = Json.integer(f, "can_play", 1) != 0;
            item.playError = Json.str(f, "play_error", "");
            String fg = Json.str(f, "guid", "");
            if (fg.length() > 0) {
                item.fileGuids.add(0, fg);
                if (item.mediaGuid.length() == 0) {
                    item.mediaGuid = fg;
                }
            }
            if (item.container.length() == 0) {
                item.container = CodecNames.containerFromPath(item.fileName);
            }
        }
    }

    private void ensurePlayInfo(ServerConfig cfg, MediaItem item) throws FnException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("item_guid", item.guid);
        try {
            Object data = call(cfg, "POST", cfg.playInfoPath, null, body);
            Map<String, Object> m = Json.map(data);
            String mg = Json.str(m, "media_guid", "");
            if (mg.length() > 0) {
                item.mediaGuid = mg;
            }
            // 实测 play/info 恒返回空字符串的 video_guid / audio_guid，
            // 这里不能用空串覆盖 stream/list 已补齐的真实 guid，否则
            // /play/play 会因缺 guid 返回 1001 System busy。
            String vg = Json.str(m, "video_guid", "");
            if (vg.length() > 0) {
                item.videoGuid = vg;
            }
            String ag = Json.str(m, "audio_guid", "");
            if (ag.length() > 0) {
                item.audioGuid = ag;
            }
            String sg = Json.str(m, "subtitle_guid", "");
            if (sg.length() > 0) {
                item.subtitleGuid = sg;
            }
            item.resumeSeconds = Json.integer(m, "ts", item.resumeSeconds);
        } catch (FnException ex) {
            L.w("FnNasApi", "play/info 不可用，改用 stream/list 的 media_guid：" + ex.detail);
        }
    }

    /**
     * POST /play/play 对瞬时繁忙（1001 System busy）做有限次退避重试；
     * 连续失败时交给上层回退直连，不把 NAS 的临时状态当成永久播放失败。
     */
    private String startPlaySessionWithRetry(ServerConfig cfg, MediaItem item,
                                             PlaybackRequest request) throws FnException {
        FnException last = null;
        long[] waits = {0L, 900L, 1800L};
        for (int i = 0; i < waits.length; i++) {
            if (waits[i] > 0) {
                try {
                    Thread.sleep(waits[i]);
                } catch (InterruptedException ignored) {
                }
            }
            try {
                String link = startPlaySession(cfg, item, request);
                if (link.length() > 0) {
                    return link;
                }
            } catch (FnException ex) {
                last = ex;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new FnException(FailureReason.PLAYBACK_FAILED, "NAS 未返回 play_link");
    }

    /** POST /play/play 启动播放会话，返回 NAS 生成的真实 HLS 播放列表路径。 */
    private String startPlaySession(ServerConfig cfg, MediaItem item) throws FnException {
        return startPlaySession(cfg, item, null);
    }

    private String startPlaySession(ServerConfig cfg, MediaItem item,
                                    PlaybackRequest request) throws FnException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("item_guid", item.guid);
        body.put("media_guid", item.mediaGuid);
        body.put("video_guid", item.videoGuid);
        body.put("audio_guid", item.audioGuid);
        body.put("subtitle_guid", item.subtitleGuid);
        // 网页端 switchURLHandler 重建会话时必须带 startTimestamp = Math.floor(currentTime)，
        // 并把 mediaConfig.ts 写回当前时间。少了它，NAS 会按滞后的 /play/record 上报
        // 位置（或 0）生成新 HLS，播放器在首次续期（约 52 秒）处往回跳 1 秒。
        int ts = request != null && request.fnosLongSegments
                ? 0 : Math.max(0, item.playbackPositionSec);
        body.put("startTimestamp", Integer.valueOf(ts));
        body.put("ts", Integer.valueOf(ts));
        // 真机实测（TCL Android 4.2.1）：只有 video_encoder 能强制 NAS 真正转码，
        // direct_link / transcode / video_codec / force_transcode 等字段全部无效。
        // 设备不支持原编码时要求 H.264，并按能力快照限制分辨率，老电视才能解码。
        if (cfg.transcodeVideoEncoder.length() > 0) {
            body.put("video_encoder", cfg.transcodeVideoEncoder);
        }
        if (cfg.transcodeResolution.length() > 0) {
            body.put("resolution", cfg.transcodeResolution);
        }
        // 主动要高码率：接口单位就是 bps，这里直接下发 bps，绝不再做 kbps→bps 换算。
        // 历史教训：曾把 11246(kbps) 原样当 bps 传，被 NAS 当成 11kbps 压到下限，
        // 实际只有 1.59Mbps，电视上出现明显花屏。1080 默认封顶 8Mbps，12Mbps 为实验开关。
        if (cfg.transcodeBitrateBps > 0) {
            body.put("bitrate", Integer.valueOf(cfg.transcodeBitrateBps));
        }
        // 真机实测：NAS 转视频时默认把原音轨透传（E-AC-3 5.1），老电视无 EAC3
        // 解码器会无声并拖死 AVSync。audio_encoder 是唯一能真正转音频的字段，
        // 已验证 aac / mp3 / ac3 分别产出 0x0f / 0x03 / 0x81 的 TS 音频流。
        // 私有协议没有已确认的声道参数，保留源声道；不兼容时由降级链路退回 MP3 立体声。
        if (cfg.transcodeAudioEncoder.length() > 0) {
            body.put("audio_encoder", cfg.transcodeAudioEncoder);
        }
        Object data = call(cfg, "POST", cfg.playPlayPath, null, body);
        return Json.str(Json.map(data), "play_link", "");
    }

    /**
     * 校验 NAS 实际输出的音频编码：master playlist 的 CODECS 是硬编码模板，会谎报
     * "mp4a.40.34"（AAC），真实编码只能从首个 TS 分片的 PMT 读取。
     * 若与要求的目标不符（或设备根本解不了），返回真实编码短名，交给上层重建会话。
     */
    private String verifyAudioStream(ServerConfig cfg, StreamCandidate tx) {
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            if (cfg.token.length() > 0) {
                headers.put("Authorization", cfg.token);
            }
            Response master = HttpStack.request("GET", tx.url, headers, null,
                    cfg.connectTimeoutMs, cfg.readTimeoutMs);
            String mediaRef = firstPlaylistEntry(master.body);
            if (mediaRef.length() == 0) {
                return "";
            }
            String mediaUrl = relativeUrl(tx.url, mediaRef);
            Response media = HttpStack.request("GET", mediaUrl, headers, null,
                    cfg.connectTimeoutMs, cfg.readTimeoutMs);
            String segRef = firstPlaylistEntry(media.body);
            if (segRef.length() == 0) {
                return "";
            }
            String segUrl = relativeUrl(mediaUrl, segRef);
            byte[] head = HttpStack.requestBytes(segUrl, headers, 64 * 1024,
                    cfg.connectTimeoutMs, cfg.readTimeoutMs);
            int type = TsProbe.audioStreamType(head);
            String codec = TranscodeProfile.audioFromStreamType(type);
            L.i("FnNasApi", "TS 分片真实音频 stream_type=0x"
                    + Integer.toHexString(type < 0 ? 0 : type) + " → " + codec
                    + "（请求目标 " + cfg.transcodeAudioEncoder + "）");
            if (codec.length() > 0) {
                tx.audioCodec = codec;
                tx.label = "NAS 解码流 · " + tx.videoCodec.toUpperCase()
                        + "+" + codec.toUpperCase() + " " + tx.width + "x" + tx.height;
            }
            return codec;
        } catch (FnException ex) {
            L.w("FnNasApi", "音频编码校验失败（不阻塞播放）：" + ex.detail);
        } catch (Throwable t) {
            L.w("FnNasApi", "解析 TS PMT 失败：" + t);
        }
        return "";
    }

    private static String audioFallback(String requested) {
        if ("ac3".equals(requested)) return "mp3";
        if ("mp3".equals(requested)) return "aac";
        return "mp3"; // aac 打不开时退回最保守的 MP3
    }

    private static String firstPlaylistEntry(String body) {
        if (body == null) {
            return "";
        }
        String[] lines = body.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.length() > 0 && s.charAt(0) != '#') {
                return s;
            }
        }
        return "";
    }

    /** 把 m3u8 里的相对分片路径补成绝对 URL。 */
    private static String relativeUrl(String base, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return ref;
        }
        int scheme = base.indexOf("://");
        if (scheme < 0) {
            return ref;
        }
        int hostStart = scheme + 3;
        int hostEnd = base.indexOf('/', hostStart);
        String origin = hostEnd < 0 ? base : base.substring(0, hostEnd);
        if (ref.startsWith("/")) {
            return origin + ref;
        }
        int q = base.indexOf('?');
        String clean = q > 0 ? base.substring(0, q) : base;
        int slash = clean.lastIndexOf('/');
        return (slash >= hostStart ? clean.substring(0, slash + 1) : origin + "/") + ref;
    }

    /**
     * 读取 play_link 的 master playlist，把真实 CODECS / RESOLUTION / BANDWIDTH
     * 回填到候选里。飞牛私有 HLS 只认 Authorization 头（URL query / Cookie 均无效）。
     */
    private void readPlayLinkInfo(ServerConfig cfg, StreamCandidate tx) {
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            if (cfg.token.length() > 0) {
                headers.put("Authorization", cfg.token);
            }
            Response r = HttpStack.request("GET", tx.url, headers, null,
                    cfg.connectTimeoutMs, cfg.readTimeoutMs);
            if (!r.ok() || r.body.indexOf("#EXTM3U") < 0) {
                L.w("FnNasApi", "播放列表读取失败 HTTP " + r.status + "：" + snippet(r.body));
                return;
            }
            String[] lines = r.body.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("#EXT-X-STREAM-INF")) {
                    continue;
                }
                String codecs = playlistAttr(line, "CODECS");
                if (codecs.length() > 0) {
                    String[] parts = codecs.split(",");
                    tx.videoCodec = CodecNames.video(firstCodecToken(parts[0]));
                    if (parts.length > 1) {
                        tx.audioCodec = CodecNames.audio(firstCodecToken(parts[1]));
                    }
                }
                String res = playlistAttr(line, "RESOLUTION");
                int x = res.indexOf('x');
                if (x > 0) {
                    tx.width = parseInt(res.substring(0, x), tx.width);
                    tx.height = parseInt(res.substring(x + 1), tx.height);
                }
                int bw = parseInt(playlistAttr(line, "BANDWIDTH"), 0);
                if (bw > 0) {
                    tx.bitrateKbps = bw / 1000;
                }
                // 主动要高码率时校验服务端是否照办：只对 >=8Mbps 的请求告警，
                // 且以「实际 < 目标的 70%」为限流判据；720/480 档的 4M/2M 是正常目标值。
                if (cfg.transcodeBitrateBps >= 8000000 && bw > 0
                        && bw < cfg.transcodeBitrateBps * 7 / 10) {
                    L.w("FnNasApi", "NAS 返回 BANDWIDTH=" + bw + " 明显低于请求 "
                            + cfg.transcodeBitrateBps + " bps，疑似被限流或改档");
                }
                tx.bitDepth = tx.videoCodec.indexOf("hevc") >= 0 || tx.videoCodec.indexOf("hvc") >= 0
                        ? 10 : 8;
                tx.label = "NAS 解码流 · " + tx.videoCodec.toUpperCase()
                        + " " + tx.width + "x" + tx.height;
                L.i("FnNasApi", "播放列表真实参数 CODECS=" + codecs
                        + " RESOLUTION=" + res + " BANDWIDTH=" + bw
                        + " → " + tx.label);
                if (!"h264".equals(tx.videoCodec) && !"mpeg4".equals(tx.videoCodec)) {
                    L.w("FnNasApi", "NAS 未输出设备可解的 H.264（" + tx.videoCodec
                            + "），老设备可能无法播放，请检查 video_encoder 设置");
                }
                break;
            }
        } catch (FnException ex) {
            L.w("FnNasApi", "读取播放列表元数据失败：" + ex.detail);
        } catch (Throwable t) {
            L.w("FnNasApi", "解析播放列表失败：" + t);
        }
    }

    /** "avc1.640029" -> "avc1"，"hvc1.2.4.L150.B0" -> "hvc1"。 */
    private static String firstCodecToken(String raw) {
        String s = raw == null ? "" : raw.trim();
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** 从 #EXT-X-STREAM-INF 属性行里取 KEY=value 或 KEY="value"。 */
    private static String playlistAttr(String line, String key) {
        int p = line.indexOf(key + "=");
        if (p < 0) {
            return "";
        }
        String rest = line.substring(p + key.length() + 1);
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 0 ? rest.substring(1, end) : "";
        }
        int comma = rest.indexOf(',');
        return (comma >= 0 ? rest.substring(0, comma) : rest).trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** play_link 可能是绝对路径或完整 URL，统一补成可播放地址。 */
    private static String absoluteUrl(ServerConfig cfg, String link) {
        if (link == null || link.length() == 0) {
            return "";
        }
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        return NetUtil.join(cfg.baseUrl, link);
    }

    private List<StreamCandidate> readQualities(ServerConfig cfg, MediaItem item) throws FnException {
        List<StreamCandidate> out = new ArrayList<StreamCandidate>();
        try {
            Object data = call(cfg, "GET", cfg.streamListPath + item.guid, null, null);
            List<Object> qs = Json.list(Json.map(data).get("qualities"));
            if (qs.isEmpty()) {
                qs = Json.list(Json.map(data).get("direct_link_qualities"));
            }
            for (int i = 0; i < qs.size(); i++) {
                Map<String, Object> q = Json.map(qs.get(i));
                String url = Json.str(q, "url", "");
                if (url.length() == 0) {
                    continue;
                }
                if (url.startsWith("/")) {
                    url = NetUtil.join(cfg.baseUrl, url);
                }
                boolean isM3u8 = Json.bool(q, "is_m3u8", false) || url.indexOf(".m3u8") >= 0;
                StreamCandidate s = new StreamCandidate();
                s.id = "nasQuality:" + i;
                s.url = NetUtil.appendQuery(url, authQuery(cfg));
                s.source = isM3u8 ? StreamSource.NAS_TRANSCODE : StreamSource.ORIGINAL;
                s.label = isM3u8 ? "NAS 解码流 · 画质" + (i + 1) : "原始流 · 画质" + (i + 1);
                s.videoCodec = isM3u8 ? "h264" : item.videoCodec;
                s.audioCodec = isM3u8 ? "aac" : item.audioCodec;
                s.container = isM3u8 ? "hls" : item.container;
                s.protocol = isM3u8 ? "hls" : "http";
                s.width = Json.integer(q, "width", item.width);
                s.height = Json.integer(q, "height", item.height);
                s.bitDepth = 8;
                out.add(s);
            }
        } catch (FnException ex) {
            L.w("FnNasApi", "未读取到画质列表：" + ex.detail);
        }
        return out;
    }

    private String authQuery(ServerConfig cfg) {
        if (cfg.token == null || cfg.token.length() == 0) {
            return "";
        }
        return "playlink=" + enc(cfg.token) + "&token=" + enc(cfg.token);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            // UTF-8 由 JVM 强制实现，不会失败；旧实现回退到平台默认编码，
            // 在 GBK 固件上会把 token 编错，直接失败更能暴露问题。
            throw new IllegalStateException("UTF-8 不可用", ex);
        }
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        if (o instanceof String) return "1".equals(o) || "true".equalsIgnoreCase((String) o);
        return false;
    }

    private static String posterOf(Map<String, Object> m) {
        String p = Json.str(m, "poster", "");
        if (p.length() == 0) {
            p = Json.str(m, "posters", "");
        }
        if (p.length() == 0) {
            List<Object> ps = Json.list(m.get("posters"));
            if (!ps.isEmpty()) p = String.valueOf(ps.get(0));
        }
        // type=Directory 的条目只有 poster_list，新增媒体库后首页会出现这类目录
        if (p.length() == 0) {
            List<Object> ps = Json.list(m.get("poster_list"));
            if (!ps.isEmpty()) p = String.valueOf(ps.get(0));
        }
        return p;
    }

    private static String firstNonEmpty(String a, String b, String def) {
        if (a != null && a.length() > 0) return a;
        if (b != null && b.length() > 0) return b;
        return def;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
