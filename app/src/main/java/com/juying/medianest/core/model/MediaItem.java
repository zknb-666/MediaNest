package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 可播放的影视条目 + 从 stream/list 解析出的原始流媒体特征。 */
public final class MediaItem {

    public String guid = "";
    public String mediaGuid = "";
    /** play/info 返回的流 guid，启动播放会话时必填，否则 NAS 返回 System busy。 */
    public String videoGuid = "";
    public String audioGuid = "";
    public String subtitleGuid = "";
    public String title = "";
    public String subtitle = "";
    public String type = "Movie";
    public String posterPath = "";
    public String year = "";
    public int runtimeMinutes = 0;
    /** 服务端精确总时长（秒）；0 表示未提供，播放器再使用 HLS/内核时长。 */
    public long durationSeconds = 0L;
    public int resumeSeconds = 0;
    /**
     * 运行时字段（不序列化）：当前播放位置秒数。网页端重建 /play/play 会话时
     * 传 startTimestamp = Math.floor(currentTime)，否则 NAS 会按滞后的
     * /play/record 上报位置生成新 HLS，播放器在 ~52 秒处往回跳。
     */
    public int playbackPositionSec = 0;

    // ---- 网页端首页/详情页展示字段（与 trimemedia-web 对齐）----
    /** vote_average，网页端海报左上黄色角标，如 8.5。0 表示未评分。 */
    public double voteAverage = 0.0;
    /** Movie / TV / Live 等，决定详情与按钮文案。 */
    public String mediaType = "Movie";
    /** 季数，网页端「第 N 季」副标题。 */
    public int seasonCount = 0;
    /** 本地集数，网页端「共 N 集」。 */
    public int episodeCount = 0;
    /** 简介 overview。 */
    public String overview = "";
    /** is_favorite，收藏页与详情页心形状态。 */
    public boolean favorite = false;
    /** play/list 返回的观看进度百分比（0~100），继续观看蓝色进度条。 */
    public int progressPercent = 0;
    /** 单集/电影的 media_guid，用于从季详情进入具体播放。 */
    public String childGuid = "";
    /** 单集所属季的 guid：播放器据此拉兄弟集做「选集 / 上一集 / 下一集」。 */
    public String seasonGuid = "";
    /** 媒体库名（ancestor_name），首页「媒体库」卡片名。 */
    public String libraryName = "";
    /** 媒体库 guid（ancestor_guid），用于按库过滤与计数。 */
    public String libraryGuid = "";
    /** 剧集详情横版背景图（16:9 剧照），与竖版封面 poster 不同源。 */
    public String backdropPath = "";
    /** item 详情的 logos：官方详情页左上角的透明标题字图，无值时退回文本标题。 */
    public String logoPath = "";
    /** 分级（TV-MA / PG 等），来自 item 详情 content_ratings，官方元信息行第一项。 */
    public String contentRating = "";
    /** item 详情的 tv_title：季详情页大标题用剧名（季自身 title 是季名）。 */
    public String showTitle = "";
    /** item 详情的 watched_ts（秒），电影详情页「剩余 X 小时 Y 分钟」进度条用。 */
    public int watchedSeconds = 0;
    /** type=Season 时为真，用于区分两级详情页。 */
    public boolean isSeason() {
        return "Season".equalsIgnoreCase(mediaType);
    }

    public String container = "";
    public String videoCodec = "";
    public String audioCodec = "";
    /** 原始音轨声道数（stream/list 的 audio_streams[0].channels），用于多声道能力检测。 */
    public int audioChannels = 0;
    public String fileName = "";
    public int width = 0;
    public int height = 0;
    public int bitrateKbps = 0;
    public int bitDepth = 8;
    public boolean hdr = false;
    public boolean canPlay = true;
    public String playError = "";
    public List<String> fileGuids = new ArrayList<String>();

    // ---- 筛选面板元数据（item/{guid} 详情 + tag 字典，对应网页端 9 组筛选条件）----
    /** 类型/流派，已由 tag/genres 映射为中文，如「动作」「科幻奇幻」。 */
    public List<String> genres = new ArrayList<String>();
    /** 国家/地区，已由 tag/iso3166 映射为中文，如「日本」。 */
    public List<String> countries = new ArrayList<String>();
    /** 分辨率，如 4k / 1080P。 */
    public List<String> resolutions = new ArrayList<String>();
    /** 音频规格，如 杜比全景声 / 立体声 / 其他。 */
    public List<String> audioTypes = new ArrayList<String>();
    /** 视频动态范围，如 SDR / HDR。 */
    public List<String> colorRanges = new ArrayList<String>();
    /** 是否已观看（item 详情的 is_watched）。 */
    public boolean watched = false;
    /** 匹配状态：0 未匹配 / 1 已匹配 / 2 读取 NFO。 */
    public int matchState = 0;

    public String displayTitle() {
        if (subtitle != null && subtitle.length() > 0) {
            return title + " · " + subtitle;
        }
        return title;
    }

    public String technicalLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(videoCodec.length() > 0 ? videoCodec.toUpperCase() : "未知编码");
        if (bitDepth > 8) {
            sb.append(' ').append(bitDepth).append("bit");
        }
        if (audioCodec.length() > 0) {
            sb.append(" / ").append(audioCodec.toUpperCase());
            if (audioChannels > 0) {
                sb.append(' ').append(audioChannels).append("ch");
            }
        }
        if (container.length() > 0) {
            sb.append(" / ").append(container.toUpperCase());
        }
        if (height > 0) {
            sb.append(" / ").append(height).append('p');
        }
        return sb.toString();
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("guid", guid);
        m.put("mediaGuid", mediaGuid);
        m.put("videoGuid", videoGuid);
        m.put("audioGuid", audioGuid);
        m.put("subtitleGuid", subtitleGuid);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("type", type);
        m.put("posterPath", posterPath);
        m.put("year", year);
        m.put("runtimeMinutes", Integer.valueOf(runtimeMinutes));
        m.put("durationSeconds", Long.valueOf(durationSeconds));
        m.put("resumeSeconds", Integer.valueOf(resumeSeconds));
        m.put("voteAverage", Double.valueOf(voteAverage));
        m.put("mediaType", mediaType);
        m.put("seasonCount", Integer.valueOf(seasonCount));
        m.put("episodeCount", Integer.valueOf(episodeCount));
        m.put("overview", overview);
        m.put("favorite", Boolean.valueOf(favorite));
        m.put("progressPercent", Integer.valueOf(progressPercent));
        m.put("childGuid", childGuid);
        m.put("seasonGuid", seasonGuid);
        m.put("libraryName", libraryName);
        m.put("libraryGuid", libraryGuid);
        m.put("backdropPath", backdropPath);
        m.put("logoPath", logoPath);
        m.put("contentRating", contentRating);
        m.put("showTitle", showTitle);
        m.put("watchedSeconds", Integer.valueOf(watchedSeconds));
        m.put("container", container);
        m.put("videoCodec", videoCodec);
        m.put("audioCodec", audioCodec);
        m.put("audioChannels", Integer.valueOf(audioChannels));
        m.put("fileName", fileName);
        m.put("width", Integer.valueOf(width));
        m.put("height", Integer.valueOf(height));
        m.put("bitrateKbps", Integer.valueOf(bitrateKbps));
        m.put("bitDepth", Integer.valueOf(bitDepth));
        m.put("hdr", Boolean.valueOf(hdr));
        m.put("canPlay", Boolean.valueOf(canPlay));
        m.put("fileGuids", Json.toList(fileGuids));
        m.put("genres", Json.toList(genres));
        m.put("countries", Json.toList(countries));
        m.put("resolutions", Json.toList(resolutions));
        m.put("audioTypes", Json.toList(audioTypes));
        m.put("colorRanges", Json.toList(colorRanges));
        m.put("watched", Boolean.valueOf(watched));
        m.put("matchState", Integer.valueOf(matchState));
        return m;
    }

    public static MediaItem fromJson(Object o) {
        Map<String, Object> m = Json.map(o);
        MediaItem i = new MediaItem();
        i.guid = Json.str(m, "guid", "");
        i.mediaGuid = Json.str(m, "mediaGuid", "");
        i.videoGuid = Json.str(m, "videoGuid", "");
        i.audioGuid = Json.str(m, "audioGuid", "");
        i.subtitleGuid = Json.str(m, "subtitleGuid", "");
        i.title = Json.str(m, "title", "未命名");
        i.subtitle = Json.str(m, "subtitle", "");
        i.type = Json.str(m, "type", "Movie");
        i.posterPath = Json.str(m, "posterPath", "");
        i.year = Json.str(m, "year", "");
        i.runtimeMinutes = Json.integer(m, "runtimeMinutes", 0);
        i.durationSeconds = Json.longer(m, "durationSeconds", 0L);
        i.resumeSeconds = Json.integer(m, "resumeSeconds", 0);
        i.voteAverage = Json.doubleNum(m, "voteAverage", 0.0);
        i.mediaType = Json.str(m, "mediaType", "Movie");
        i.seasonCount = Json.integer(m, "seasonCount", 0);
        i.episodeCount = Json.integer(m, "episodeCount", 0);
        i.overview = Json.str(m, "overview", "");
        i.favorite = Json.bool(m, "favorite", false);
        i.progressPercent = Json.integer(m, "progressPercent", 0);
        i.childGuid = Json.str(m, "childGuid", "");
        i.seasonGuid = Json.str(m, "seasonGuid", "");
        i.libraryName = Json.str(m, "libraryName", "");
        i.libraryGuid = Json.str(m, "libraryGuid", "");
        i.backdropPath = Json.str(m, "backdropPath", "");
        i.logoPath = Json.str(m, "logoPath", "");
        i.contentRating = Json.str(m, "contentRating", "");
        i.showTitle = Json.str(m, "showTitle", "");
        i.watchedSeconds = Json.integer(m, "watchedSeconds", 0);
        i.container = Json.str(m, "container", "");
        i.videoCodec = Json.str(m, "videoCodec", "");
        i.audioCodec = Json.str(m, "audioCodec", "");
        i.audioChannels = Json.integer(m, "audioChannels", 0);
        i.fileName = Json.str(m, "fileName", "");
        i.width = Json.integer(m, "width", 0);
        i.height = Json.integer(m, "height", 0);
        i.bitrateKbps = Json.integer(m, "bitrateKbps", 0);
        i.bitDepth = Json.integer(m, "bitDepth", 8);
        i.hdr = Json.bool(m, "hdr", false);
        i.canPlay = Json.bool(m, "canPlay", true);
        List<Object> gs = Json.list(m.get("fileGuids"));
        for (int k = 0; k < gs.size(); k++) {
            i.fileGuids.add(String.valueOf(gs.get(k)));
        }
        fillStrings(m, "genres", i.genres);
        fillStrings(m, "countries", i.countries);
        fillStrings(m, "resolutions", i.resolutions);
        fillStrings(m, "audioTypes", i.audioTypes);
        fillStrings(m, "colorRanges", i.colorRanges);
        i.watched = Json.bool(m, "watched", false);
        i.matchState = Json.integer(m, "matchState", 0);
        return i;
    }

    private static void fillStrings(Map<String, Object> m, String key, List<String> out) {
        List<Object> vals = Json.list(m.get(key));
        for (int k = 0; k < vals.size(); k++) {
            out.add(String.valueOf(vals.get(k)));
        }
    }
}
