package com.juying.medianest.domain.port;

import com.juying.medianest.core.model.AudioTrack;
import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.policy.FilterEngine;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.FsEntry;
import com.juying.medianest.core.model.SubtitleSearchItem;
import com.juying.medianest.core.model.Library;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.Person;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.PlaybackSession;
import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.RouteOption;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.SubtitleTrack;

import java.util.List;
import java.util.Map;

/**
 * 媒体数据源抽象层。
 *
 * 飞牛影视没有公开 API，所有端点都是逆向得到的私有接口，版本升级随时可能变。
 * 因此 UI / 选流 / 回退逻辑只依赖这个接口，更换飞牛或 Jellyfin 实现不需要动上层代码。
 */
public interface MediaApi {

    /** 登录 + 读取 server/info，回填 NAS 转码能力。失败抛带用户提示的 FnException。 */
    ConfigCodec.ServerInfo verify(ServerConfig cfg) throws FnException;

    /**
     * 用配置里的账号密码重新登录，返回新 token。
     * 供本地鉴权代理在媒体流返回「200 + code:-2 Auth Failed」时续登使用：
     * 业务接口有 call() 自动重登，但代理转发的分片请求没有，必须显式续登。
     */
    String refreshToken(ServerConfig cfg) throws FnException;

    /** 图片与媒体请求使用的完整 Authorization 头。 */
    String authorizationHeader(ServerConfig cfg);

    /** 账号级首选字幕/音频语言；不支持该能力的媒体源返回空 Map。 */
    Map<String, String> fetchUserPrefs(ServerConfig cfg) throws FnException;

    /** 同步账号级首选字幕/音频语言；不支持该能力的媒体源忽略。 */
    void updateUserPrefs(ServerConfig cfg, String lan, String audioLan) throws FnException;

    /** 读取账号级配置；不支持该能力的媒体源返回空串。 */
    String fetchUserData(ServerConfig cfg, String key) throws FnException;

    /** 写入账号级配置；不支持该能力的媒体源忽略。 */
    void saveUserData(ServerConfig cfg, String key, String value) throws FnException;

    /** 拉取可播放影视条目；enrich=true 时顺带补齐编码信息用于能力匹配。 */
    List<MediaItem> listMedia(ServerConfig cfg, boolean enrich, int limit) throws FnException;

    /** 继续观看：网页端首页「继续观看」横卡，含 ts/duration 进度与横版海报。 */
    List<MediaItem> listContinue(ServerConfig cfg) throws FnException;

    /** 服务端搜索：GET /v/api/v1/search/list?q=，返回按文件粒度命中的条目。 */
    List<MediaItem> search(ServerConfig cfg, String query) throws FnException;

    /** 剧集列表：item/list 带 parent_guid=季 guid，返回 type=Episode 的条目。 */
    List<MediaItem> listEpisodes(ServerConfig cfg, String seasonGuid) throws FnException;

    /** 季列表：item/list 带 parent_guid=剧集 guid，返回 type=Season 的季条目。 */
    List<MediaItem> listSeasons(ServerConfig cfg, String showGuid) throws FnException;

    /** 媒体库列表：mediadb/list 动态返回所有媒体库（含 IPTV 虚拟库，新增/删除都会同步）。 */
    List<Library> listLibraries(ServerConfig cfg) throws FnException;

    /**
     * 直播频道列表：POST item/list 带 tags.type=["LiveChannel"]。
     * 这是网页端「电视直播 / 自定义直播源」分类的真实数据源；limit 控制首屏加载条数。
     */
    List<MediaItem> listLiveChannels(ServerConfig cfg, int limit) throws FnException;

    /** mediadb/sum：各库 guid 的条目数，以及 total/movie/tv/live/video/favorite 汇总。 */
    Map<String, Integer> libraryCounts(ServerConfig cfg) throws FnException;

    /**
     * 筛选面板的可用维度：GET /tag/list?&lt;scope&gt;，scope 由 ViewRules.tagScope 生成。
     * 网页端各库下拉项不同，正是因为不同 scope 返回的非空维度不同。
     * 返回值里的 id 已由 tag/genres、tag/iso3166 映射为中文显示文本。
     */
    FilterEngine.TagSet filterTags(ServerConfig cfg, String scope) throws FnException;

    /**
     * 补齐条目用于本地筛选的元数据（流派 / 国家 / 分辨率 / 音轨 / 动态范围 / 观看与匹配状态）。
     * 数据来自 item/{guid} 详情，失败时退回 item/list 自带的 media_stream，不抛异常中断页面。
     */
    void enrichFilterMeta(ServerConfig cfg, List<MediaItem> items);

    /**
     * 网页端详情页的横版背景图：GET /item/{guid} 的 backdrops 字段。
     * 与竖版封面 posters 不同源；Season 季没有 backdrops 时返回空串，
     * 调用方按网页端规则把竖版海报放大模糊作为背景。
     */
    String showBackdrop(ServerConfig cfg, String itemGuid) throws FnException;

    /**
     * 详情页补齐：GET /item/{guid} 的 logos / content_ratings / watched_ts / tv_title
     * 以及完整的流派国家与流媒体角标。失败抛 FnException，调用方保留列表快照。
     */
    void loadDetail(ServerConfig cfg, MediaItem item) throws FnException;

    /** 音频轨列表（stream/list 的 audio_streams），供电影详情页「音频」下拉使用。 */
    List<AudioTrack> listAudioTracks(ServerConfig cfg, MediaItem item) throws FnException;

    /** 演职人员：POST /person/list/{itemGuid}，按官方 order 升序，详情页横向头像行使用。 */
    List<Person> listPersons(ServerConfig cfg, String itemGuid) throws FnException;

    /** 人物详情：GET /person/{personGuid}，补简介 / 原名 / 头像。 */
    Person loadPerson(ServerConfig cfg, String personGuid) throws FnException;

    /** 人物作品：POST /person/item/list，body {person_guid}，人物详情页分节展示。 */
    List<MediaItem> listPersonItems(ServerConfig cfg, String personGuid) throws FnException;

    /**
     * 收藏 / 取消收藏：网页端 PUT|DELETE /item/favorite，body {item_guid}，成功 code=0。
     * 服务端持久化，之后 item/list 的 is_favorite 会同步变化。
     */
    void setFavorite(ServerConfig cfg, String guid, boolean favorite) throws FnException;

    /**
     * 标记已看 / 取消已看：官方客户端 POST|DELETE /item/watched，body {item_guid}，成功 code=0。
     * 服务端持久化，之后 item/list 的 watched 与 item/{guid} 的 is_watched 同步变化，
     * 网页端同一账号立即可见。
     */
    void setWatched(ServerConfig cfg, String guid, boolean watched) throws FnException;

    /** 从服务端读回收藏状态（item/{guid} 的 is_favorite），用于重新进入详情页时校正。 */
    boolean isFavorite(ServerConfig cfg, String guid) throws FnException;

    /** 从服务端读回已看状态（item/{guid} 的 is_watched），用于重新进入详情页时校正。 */
    boolean isWatched(ServerConfig cfg, String guid) throws FnException;

    /**
     * 把 item/list 返回的 poster 相对路径转成可直接加载的图片 URL。
     * 实测格式：baseUrl + /v/api/v1/sys/img + /xx/xx/xx.webp + ?w=宽度，带 Authorization，免签名。
     */
    String imageUrl(ServerConfig cfg, String posterPath, int width);

    /** 取该条目的所有原始流候选（含多套直链地址模板）。直播频道走 m3u8 直链。 */
    List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item) throws FnException;

    default List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item,
                                             PlaybackRequest request) throws FnException {
        return getStreams(cfg, item);
    }

    default boolean preferOriginal(ServerConfig cfg, Capability cap) {
        return !cfg.forceTranscode;
    }

    /** 字幕轨道固定来自 NAS 的 stream/list，不搜索本地或第三方字幕。 */
    List<SubtitleTrack> listSubtitles(ServerConfig cfg, MediaItem item) throws FnException;

    /**
     * 外挂字幕文本（WebVTT / SRT 原文），来自 NAS /subtitle/dl/&lt;guid&gt;?fmt=vtt。
     * 内嵌 ASS 字幕（extra_file=0）在 NAS 上没有独立文件，返回 404，
     * 这类字幕由 NAS 转码时按 subtitle_guid 烧录到视频流里，客户端不渲染文本层。
     */
    String subtitleContent(ServerConfig cfg, String guid, String format) throws FnException;

    /** 在线字幕搜索（NAS 字幕库），用于官方「搜索字幕」弹窗。 */
    List<SubtitleSearchItem> searchSubtitles(ServerConfig cfg, String mediaGuid, String lan)
            throws FnException;

    /** 下载在线字幕到当前媒体，完成后重新读取轨道。 */
    void downloadOnlineSubtitle(ServerConfig cfg, String mediaGuid, String trimId)
            throws FnException;

    /** 删除外挂字幕。 */
    void deleteSubtitle(ServerConfig cfg, String subtitleGuid) throws FnException;

    /** 把 NAS 文件标记为该媒体的字幕。 */
    void markSubtitles(ServerConfig cfg, String mediaGuid, List<String> filepaths)
            throws FnException;

    /** 可浏览的 NAS 授权根目录，用于「添加 NAS 字幕文件」。 */
    List<String> authorizedDirs(ServerConfig cfg) throws FnException;

    /** 浏览 NAS 目录。 */
    List<FsEntry> listDirectory(ServerConfig cfg, String path) throws FnException;

    /**
     * NAS 返回的可用画质挡位：tag/list 的 resolutions + stream/list 的
     * video_streams[].resolution_type。挡位值、数量、顺序全由 NAS 决定，
     * 客户端不内置 4K/1080P/720P 枚举表。
     */
    List<QualityLevel> qualityLevels(ServerConfig cfg, MediaItem item) throws FnException;

    /**
     * NAS 返回的直播线路挡位：网页端 play/info 的 live_channels（media_guid 为当前线路），
     * 不可用时退回 stream/list.files。线路名称 / 数量 / 顺序全由 NAS 决定。
     * 调用后 item.mediaGuid 会被同步为 NAS 当前生效线路。
     */
    List<RouteOption> liveRoutes(ServerConfig cfg, MediaItem item) throws FnException;

    /** 上报播放进度；ended=true 时按网页端规则回传 ts=0。 */
    void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended);

    default void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended,
                                String playLink) {
        reportProgress(cfg, item, seconds, ended);
    }

    default void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended,
                                PlaybackSession session) {
        reportProgress(cfg, item, seconds, ended, session == null ? null : session.url);
    }

    /** 把 StreamSelector 生成的转码占位符换成真实 NAS 解码流地址。 */
    String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx) throws FnException;

    default String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx,
                                       PlaybackRequest request) throws FnException {
        return resolveTranscodeUrl(cfg, item, tx);
    }

    /**
     * 用当前转码参数重新创建一个播放会话，返回新的绝对 play_link。
     *
     * 真机实测 /v/media/&lt;hash&gt;/ 会话约 60 秒后统一 410 Gone，且拉流不会续命；
     * 本地鉴权代理在到期前调用本方法换 hash，再把旧请求路径重映射到新目录，
     * 就能在不打断播放的情况下续期（对外部系统播放器同样有效）。
     */
    String refreshPlayLink(ServerConfig cfg, MediaItem item) throws FnException;

    default String refreshPlayLink(ServerConfig cfg, MediaItem item,
                                   PlaybackRequest request) throws FnException {
        return refreshPlayLink(cfg, item);
    }

    /**
     * 网页端 POST /media/p {req:'media.checkPlayLink', playLink}：探测当前播放会话
     * 是否仍有效。返回 true 表示 alive（errno!=4100），此时客户端应保留原 hash，
     * 不再无条件 POST /play/play 重建会话 —— 这正是播放到 ~52 秒往回跳的根因。
     */
    boolean isPlayLinkAlive(ServerConfig cfg, String playLink) throws FnException;

    /**
     * 网页端每 30 秒 POST /media/p {req:'media.transcodeStatis', playLink} 心跳保活
     * （文章里飞牛音乐对应 /track/transcode/heartbeat）。
     */
    void touchPlaySession(ServerConfig cfg, String playLink) throws FnException;

    /**
     * 网页端 POST /media/p {req:'media.quit', playLink}：主动结束播放会话，
     * 让 NAS 立刻回收该会话的转码任务。切档/退出播放时调用，避免旧会话
     * 继续占住同片转码槽，导致新会话建得出却不产片（黑屏有声）。
     */
    void quitPlaySession(ServerConfig cfg, String playLink) throws FnException;
}
