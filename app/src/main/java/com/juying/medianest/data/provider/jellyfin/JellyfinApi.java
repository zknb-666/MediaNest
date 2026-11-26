package com.juying.medianest.data.provider.jellyfin;

import com.juying.medianest.data.api.HttpStack;
import com.juying.medianest.data.api.HttpStack.Response;
import com.juying.medianest.core.util.L;
import com.juying.medianest.domain.port.MediaApi;
import com.juying.medianest.core.model.AudioTrack;
import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.util.CodecNames;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.policy.FilterEngine;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.FsEntry;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.model.Library;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.util.NetUtil;
import com.juying.medianest.core.model.Person;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.PlaybackSession;
import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.RouteOption;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.SubtitleSearchItem;
import com.juying.medianest.core.model.SubtitleTrack;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JellyfinApi implements MediaApi {

    private static final String CLIENT = "MediaNest";
    private static final String DEVICE = "Android TV";
    private static final String VERSION = "1.0.0";
    private static final String FIELDS = "Genres,ProductionLocations,MediaSources,MediaStreams,"
            + "UserData,People,ParentId,AncestorIds";
    private static final int ENRICH_LIMIT = 12;

    public ConfigCodec.ServerInfo verify(ServerConfig cfg) throws FnException {
        if (cfg.baseUrl == null || cfg.baseUrl.length() == 0) {
            throw new FnException(FailureReason.CONNECTION_FAILED, "服务器地址为空");
        }
        Object publicData = publicCall(cfg, "/System/Info/Public");
        Map<String, Object> info = Json.map(publicData);
        if (!isJellyfinInfo(info)) {
            throw new FnException(FailureReason.SERVER_ERROR, "该服务器不是 Jellyfin");
        }
        ensureDeviceId(cfg);
        if (cfg.token.length() == 0 || cfg.userId.length() == 0) {
            login(cfg);
        } else {
            requestAuthenticated(cfg, "GET", "/UserViews", null, null);
        }
        cfg.serverName = Json.str(info, "ServerName", "");
        cfg.cpuDecoding = true;
        cfg.directLinkEnabled = true;
        cfg.nasTranscodeCapable = true;
        cfg.lastVerifiedAt = System.currentTimeMillis();
        ConfigCodec.ServerInfo server = new ConfigCodec.ServerInfo();
        server.baseUrl = cfg.baseUrl;
        server.name = cfg.serverName;
        server.port = NetUtil.portOf(cfg.baseUrl);
        server.method = "MANUAL";
        server.verified = true;
        server.signature = "jellyfin";
        return server;
    }

    public void login(ServerConfig cfg) throws FnException {
        if (cfg.baseUrl == null || cfg.baseUrl.length() == 0) {
            throw new FnException(FailureReason.CONNECTION_FAILED, "服务器地址为空");
        }
        if (!cfg.hasCredentials()) {
            throw new FnException(FailureReason.AUTH_FAILED, "请填写 Jellyfin 用户名与密码");
        }
        ensureDeviceId(cfg);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("Username", cfg.username);
        body.put("Pw", cfg.password);
        Response response = rawCall(cfg, "POST", "/Users/AuthenticateByName", null, body,
                clientAuthorization(cfg), false);
        Map<String, Object> result = Json.map(responseObject(response, "/Users/AuthenticateByName"));
        String token = Json.str(result, "AccessToken", "");
        String userId = Json.str(Json.map(result.get("User")), "Id", "");
        if (token.length() == 0 || userId.length() == 0) {
            throw new FnException(FailureReason.AUTH_FAILED, "Jellyfin 登录响应缺少令牌或用户 ID");
        }
        cfg.token = token;
        cfg.userId = userId;
    }

    public String refreshToken(ServerConfig cfg) throws FnException {
        login(cfg);
        return cfg.token;
    }

    @Override
    public String authorizationHeader(ServerConfig cfg) {
        return cfg.token.length() == 0 ? "" : "MediaBrowser Token=\"" + cfg.token + "\"";
    }

    @Override
    public Map<String, String> fetchUserPrefs(ServerConfig cfg) {
        return new LinkedHashMap<String, String>();
    }

    @Override
    public void updateUserPrefs(ServerConfig cfg, String lan, String audioLan) {
    }

    @Override
    public String fetchUserData(ServerConfig cfg, String key) {
        return "";
    }

    @Override
    public void saveUserData(ServerConfig cfg, String key, String value) {
    }

    public List<MediaItem> listMedia(ServerConfig cfg, boolean enrich, int limit) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("Recursive", "true");
        params.put("IncludeItemTypes", "Movie,Series");
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableUserData", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary,Thumb,Backdrop,Logo");
        params.put("Limit", String.valueOf(limit > 0 ? limit : 200));
        params.put("SortBy", "SortName");
        Object data = requestAuthenticated(cfg, "GET", "/Items", params, null);
        List<MediaItem> out = mapItems(Json.map(data).get("Items"));
        resolveLibraries(cfg, out);
        if (enrich) {
            int count = Math.min(out.size(), ENRICH_LIMIT);
            for (int i = 0; i < count; i++) {
                MediaItem item = out.get(i);
                if (!"Movie".equalsIgnoreCase(item.mediaType)
                        && !"Episode".equalsIgnoreCase(item.mediaType)) {
                    continue;
                }
                try {
                    fillStreamInfo(item, firstSource(playbackInfo(cfg, item)));
                } catch (FnException ex) {
                    L.w("JellyfinApi", "条目编码信息读取失败：" + ex.detail);
                }
            }
        }
        return out;
    }

    public List<MediaItem> listContinue(ServerConfig cfg) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("IncludeItemTypes", "Movie,Episode");
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableUserData", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary,Thumb,Backdrop");
        params.put("Limit", "50");
        Object data = requestAuthenticated(cfg, "GET", "/UserItems/Resume", params, null);
        return mapItems(Json.map(data).get("Items"));
    }

    public List<MediaItem> search(ServerConfig cfg, String query) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("Recursive", "true");
        params.put("SearchTerm", query == null ? "" : query);
        params.put("IncludeItemTypes", "Movie,Series,Episode");
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableUserData", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary,Thumb,Backdrop");
        params.put("Limit", "100");
        Object data = requestAuthenticated(cfg, "GET", "/Items", params, null);
        return mapItems(Json.map(data).get("Items"));
    }

    public List<MediaItem> listEpisodes(ServerConfig cfg, String seasonGuid) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("ParentId", seasonGuid);
        params.put("IncludeItemTypes", "Episode");
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableUserData", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary,Thumb,Backdrop,Logo");
        params.put("SortBy", "IndexNumber");
        Object data = requestAuthenticated(cfg, "GET", "/Items", params, null);
        return mapItems(Json.map(data).get("Items"));
    }

    public List<MediaItem> listSeasons(ServerConfig cfg, String showGuid) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableUserData", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary,Thumb,Backdrop,Logo");
        Object data = requestAuthenticated(cfg, "GET", "/Shows/" + enc(showGuid) + "/Seasons",
                params, null);
        return mapItems(Json.map(data).get("Items"));
    }

    public List<Library> listLibraries(ServerConfig cfg) throws FnException {
        Object data = requestAuthenticated(cfg, "GET", "/UserViews", baseParams(cfg), null);
        List<Object> values = Json.list(Json.map(data).get("Items"));
        List<Library> out = new ArrayList<Library>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> value = Json.map(values.get(i));
            Library library = new Library();
            library.guid = Json.str(value, "Id", "");
            library.name = Json.str(value, "Name", "");
            library.category = categoryOf(Json.str(value, "CollectionType", ""));
            if (library.guid.length() > 0) {
                out.add(library);
            }
        }
        return out;
    }

    public List<MediaItem> listLiveChannels(ServerConfig cfg, int limit) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("EnableImages", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary");
        params.put("Limit", String.valueOf(limit > 0 ? limit : 200));
        params.put("SortBy", "SortName");
        Object data = requestAuthenticated(cfg, "GET", "/LiveTv/Channels", params, null);
        List<Object> values = Json.list(Json.map(data).get("Items"));
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> value = Json.map(values.get(i));
            MediaItem item = mapItem(value);
            item.type = "LiveChannel";
            item.mediaType = "LiveChannel";
            item.libraryName = "电视直播";
            item.posterPath = imagePath(item.guid, "Primary", imageTags(value));
            out.add(item);
        }
        return out;
    }

    public Map<String, Integer> libraryCounts(ServerConfig cfg) throws FnException {
        Object data = requestAuthenticated(cfg, "GET", "/Items/Counts", baseParams(cfg), null);
        Map<String, Object> counts = Json.map(data);
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        int movieCount = Json.integer(counts, "MovieCount", 0);
        int seriesCount = Json.integer(counts, "SeriesCount", 0);
        out.put("total", Integer.valueOf(movieCount + seriesCount));
        out.put("movie", Integer.valueOf(movieCount));
        out.put("tv", Integer.valueOf(seriesCount));
        out.put("live", Integer.valueOf(0));
        out.put("favorite", Integer.valueOf(Json.integer(counts, "FavoriteCount", 0)));
        return out;
    }

    public FilterEngine.TagSet filterTags(ServerConfig cfg, String scope) {
        return new FilterEngine.TagSet();
    }

    public void enrichFilterMeta(ServerConfig cfg, List<MediaItem> items) {
        if (items == null) {
            return;
        }
        int count = Math.min(items.size(), ENRICH_LIMIT);
        for (int i = 0; i < count; i++) {
            MediaItem item = items.get(i);
            if (!"Movie".equalsIgnoreCase(item.mediaType)
                    && !"Episode".equalsIgnoreCase(item.mediaType)) {
                continue;
            }
            try {
                fillStreamInfo(item, firstSource(playbackInfo(cfg, item)));
            } catch (FnException ex) {
                L.w("JellyfinApi", "筛选元数据读取失败 " + item.guid + "：" + ex.detail);
            }
        }
    }

    public String showBackdrop(ServerConfig cfg, String itemGuid) throws FnException {
        MediaItem item = mapItem(itemInfo(cfg, itemGuid));
        if (item.backdropPath.length() == 0 && item.seasonGuid.length() > 0) {
            inheritParentImages(cfg, item, item.seasonGuid);
        }
        return item.backdropPath;
    }

    public void loadDetail(ServerConfig cfg, MediaItem item) throws FnException {
        MediaItem detail = mapItem(itemInfo(cfg, item.guid));
        if (detail.title.length() > 0) item.title = detail.title;
        if (detail.subtitle.length() > 0) item.subtitle = detail.subtitle;
        if (detail.posterPath.length() > 0) item.posterPath = detail.posterPath;
        if (detail.backdropPath.length() > 0) item.backdropPath = detail.backdropPath;
        if (detail.logoPath.length() > 0) item.logoPath = detail.logoPath;
        if (item.posterPath.length() == 0 || item.logoPath.length() == 0
                || item.backdropPath.length() == 0) {
            try {
                inheritParentImages(cfg, item, detail.seasonGuid);
            } catch (FnException ex) {
                L.w("JellyfinApi", "父剧图片继承失败，保留当前详情图片：" + ex.detail);
            }
        }
        if (detail.overview.length() > 0) item.overview = detail.overview;
        if (detail.year.length() > 0) item.year = detail.year;
        if (detail.runtimeMinutes > 0) item.runtimeMinutes = detail.runtimeMinutes;
        if (detail.voteAverage > 0) item.voteAverage = detail.voteAverage;
        if (detail.contentRating.length() > 0) item.contentRating = detail.contentRating;
        if (detail.showTitle.length() > 0) item.showTitle = detail.showTitle;
        if (detail.seasonGuid.length() > 0) item.seasonGuid = detail.seasonGuid;
        if (detail.libraryGuid.length() > 0) item.libraryGuid = detail.libraryGuid;
        if (detail.seasonCount > 0) item.seasonCount = detail.seasonCount;
        if (detail.episodeCount > 0) item.episodeCount = detail.episodeCount;
        item.favorite = detail.favorite;
        item.watched = detail.watched;
        item.resumeSeconds = detail.resumeSeconds;
        item.watchedSeconds = detail.watchedSeconds;
        item.progressPercent = detail.progressPercent;
        item.genres = detail.genres;
        item.countries = detail.countries;
        if ("Movie".equalsIgnoreCase(item.mediaType)
                || "Episode".equalsIgnoreCase(item.mediaType)) {
            fillStreamInfo(item, firstSource(playbackInfo(cfg, item)));
        }
    }

    public List<AudioTrack> listAudioTracks(ServerConfig cfg, MediaItem item) throws FnException {
        List<AudioTrack> out = new ArrayList<AudioTrack>();
        Map<String, Object> source = firstSource(playbackInfo(cfg, item));
        List<Object> streams = Json.list(source.get("MediaStreams"));
        for (int i = 0; i < streams.size(); i++) {
            Map<String, Object> stream = Json.map(streams.get(i));
            if (!"Audio".equalsIgnoreCase(Json.str(stream, "Type", ""))) {
                continue;
            }
            AudioTrack track = new AudioTrack();
            track.guid = String.valueOf(Json.integer(stream, "Index", i));
            track.title = Json.str(stream, "DisplayTitle", Json.str(stream, "Title", ""));
            track.language = Json.str(stream, "Language", "");
            track.audioType = Json.str(stream, "Codec", "");
            track.isDefault = Json.bool(stream, "IsDefault", false);
            out.add(track);
        }
        return out;
    }

    public List<Person> listPersons(ServerConfig cfg, String itemGuid) throws FnException {
        List<Person> out = new ArrayList<Person>();
        List<Object> people = Json.list(itemInfo(cfg, itemGuid).get("People"));
        for (int i = 0; i < people.size(); i++) {
            out.add(mapPerson(Json.map(people.get(i))));
        }
        return out;
    }

    public Person loadPerson(ServerConfig cfg, String personGuid) throws FnException {
        return mapPerson(itemInfo(cfg, personGuid));
    }

    public List<MediaItem> listPersonItems(ServerConfig cfg, String personGuid) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("PersonIds", personGuid);
        params.put("Recursive", "true");
        params.put("IncludeItemTypes", "Movie,Series,Episode");
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableUserData", "true");
        params.put("ImageTypeLimit", "1");
        params.put("EnableImageTypes", "Primary,Thumb");
        params.put("Limit", "100");
        Object data = requestAuthenticated(cfg, "GET", "/Items", params, null);
        return mapItems(Json.map(data).get("Items"));
    }

    public void setFavorite(ServerConfig cfg, String guid, boolean favorite) throws FnException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("IsFavorite", Boolean.valueOf(favorite));
        requestAuthenticated(cfg, "POST", "/UserItems/" + enc(guid) + "/UserData", null, body);
    }

    public void setWatched(ServerConfig cfg, String guid, boolean watched) throws FnException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("Played", Boolean.valueOf(watched));
        requestAuthenticated(cfg, "POST", "/UserItems/" + enc(guid) + "/UserData", null, body);
    }

    public boolean isFavorite(ServerConfig cfg, String guid) throws FnException {
        return Json.bool(userData(cfg, guid), "IsFavorite", false);
    }

    public boolean isWatched(ServerConfig cfg, String guid) throws FnException {
        return Json.bool(userData(cfg, guid), "Played", false);
    }

    public String imageUrl(ServerConfig cfg, String posterPath, int width) {
        return imageUrl(cfg.baseUrl, posterPath, width);
    }

    public List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item) throws FnException {
        return getStreams(cfg, item, null);
    }

    public List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item,
                                            PlaybackRequest request) throws FnException {
        if (request != null) cfg = request.applyTo(cfg);
        List<StreamCandidate> out = new ArrayList<StreamCandidate>();
        List<Object> sources = Json.list(postedPlaybackInfo(cfg, item, request).get("MediaSources"));
        for (int i = 0; i < sources.size(); i++) {
            Map<String, Object> source = Json.map(sources.get(i));
            String sourceId = Json.str(source, "Id", item.guid);
            String container = Json.str(source, "Container", "mp4");
            MediaItem sourceItem = MediaItem.fromJson(item.toJson());
            sourceItem.mediaGuid = sourceId;
            fillStreamInfo(sourceItem, source);
            if (i == 0) {
                item.mediaGuid = sourceId;
            }
            StreamCandidate original = StreamCandidate.original(
                    NetUtil.join(cfg.baseUrl, "/Videos/" + enc(item.guid) + "/stream."
                            + enc(container) + "?static=true&mediaSourceId=" + enc(sourceId)), sourceItem);
            original.id = ("Live".equalsIgnoreCase(Json.str(source, "Protocol", ""))
                    || "LiveChannel".equalsIgnoreCase(item.mediaType) ? "live:" : "")
                    + "jellyfin:" + sourceId + ":original";
            original.label = "Jellyfin 原始流 · " + item.title;
            original.protocol = "http";
            out.add(original);

            if (Json.bool(source, "SupportsDirectStream", false)
                    && !"mp4".equalsIgnoreCase(container)) {
                StreamCandidate remux = original.copy();
                remux.id = "jellyfin:" + sourceId + ":remux";
                remux.url = NetUtil.join(cfg.baseUrl, "/Videos/" + enc(item.guid)
                        + "/stream.mp4?static=true&mediaSourceId=" + enc(sourceId));
                remux.container = "mp4";
                remux.label = "Jellyfin 重封装 · MP4";
                out.add(remux);
            }

            String transcodeUrl = Json.str(source, "TranscodingUrl", "");
            if (transcodeUrl.length() > 0) {
                StreamCandidate transcode = new StreamCandidate();
                transcode.id = "jellyfin:" + sourceId + ":transcode";
                transcode.url = transcodeUrl.startsWith("http://") || transcodeUrl.startsWith("https://")
                        ? transcodeUrl : NetUtil.join(cfg.baseUrl, transcodeUrl);
                transcode.session = playbackSession(transcode.url);
                transcode.source = com.juying.medianest.core.model.StreamSource.NAS_TRANSCODE;
                transcode.label = "Jellyfin 官方转码流";
                transcode.container = "ts";
                transcode.videoCodec = request != null ? request.videoCodec
                        : (cfg.transcodeVideoEncoder.length() > 0 ? cfg.transcodeVideoEncoder : "h264");
                transcode.audioCodec = request != null ? request.audioCodec
                        : (cfg.transcodeAudioEncoder.length() > 0 ? cfg.transcodeAudioEncoder : "ac3");
                transcode.audioChannels = request != null ? request.audioChannels
                        : (cfg.transcodeAudioChannels > 0 ? cfg.transcodeAudioChannels : 2);
                transcode.mediaSourceId = sourceId;
                transcode.protocol = "hls";
                transcode.width = request != null ? request.width
                        : targetWidth(cfg.transcodeResolution, sourceItem.width);
                transcode.height = request != null ? request.height
                        : targetHeight(cfg.transcodeResolution, sourceItem.height);
                transcode.bitrateKbps = (request != null ? request.bitrateBps
                        : (cfg.transcodeBitrateBps > 0 ? cfg.transcodeBitrateBps : 12000000)) / 1000;
                transcode.bitDepth = 8;
                transcode.hdr = false;
                out.add(transcode);
            }
        }
        return out;
    }

    public List<SubtitleTrack> listSubtitles(ServerConfig cfg, MediaItem item) throws FnException {
        List<SubtitleTrack> out = new ArrayList<SubtitleTrack>();
        Map<String, Object> source = firstSource(playbackInfo(cfg, item));
        List<Object> streams = Json.list(source.get("MediaStreams"));
        for (int i = 0; i < streams.size(); i++) {
            Map<String, Object> stream = Json.map(streams.get(i));
            if (!"Subtitle".equalsIgnoreCase(Json.str(stream, "Type", ""))) {
                continue;
            }
            if (!Json.bool(stream, "IsTextSubtitleStream", true)) {
                continue;
            }
            SubtitleTrack track = new SubtitleTrack();
            track.title = Json.str(stream, "DisplayTitle", Json.str(stream, "Title", ""));
            track.language = Json.str(stream, "Language", "");
            track.format = Json.str(stream, "Codec", "srt");
            track.isDefault = Json.bool(stream, "IsDefault", false);
            track.isExternal = Json.bool(stream, "IsExternal", false);
            track.hasDownloadableFile = Json.bool(stream, "IsTextSubtitleStream", true);
            track.mediaGuid = Json.str(source, "Id", item.mediaGuid);
            track.url = subtitleUrl(cfg, item.guid, track.mediaGuid,
                    Json.integer(stream, "Index", i), track.format);
            track.guid = track.url;
            out.add(track);
        }
        return out;
    }

    public String subtitleContent(ServerConfig cfg, String guid, String format) throws FnException {
        if (guid == null || guid.length() == 0) {
            throw new FnException(FailureReason.NO_ORIGINAL_STREAM, "字幕地址为空");
        }
        Response response = HttpStack.request("GET", guid,
                Collections.singletonMap("Authorization", authorizationHeader(cfg)), null,
                cfg.connectTimeoutMs, cfg.readTimeoutMs);
        if (!response.ok()) {
            throw FnException.fromHttp(response.status, guid);
        }
        return response.body;
    }

    public List<SubtitleSearchItem> searchSubtitles(ServerConfig cfg, String mediaGuid, String lan)
            throws FnException {
        Map<String, String> params = baseParams(cfg);
        Object data = requestAuthenticated(cfg, "GET", "/Items/" + enc(mediaGuid)
                + "/RemoteSearch/Subtitles/" + enc(lan), params, null);
        List<SubtitleSearchItem> out = new ArrayList<SubtitleSearchItem>();
        List<Object> values = Json.list(Json.map(data).get("Items"));
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> value = Json.map(values.get(i));
            SubtitleSearchItem item = new SubtitleSearchItem();
            item.trimId = Json.str(value, "Id", "");
            item.filename = Json.str(value, "Name", Json.str(value, "Title", ""));
            item.format = Json.str(value, "Format", "");
            item.language = Json.str(value, "Language", "");
            item.fileSize = Json.longer(value, "FileSize", 0L);
            if (item.trimId.length() > 0) {
                out.add(item);
            }
        }
        return out;
    }

    public void downloadOnlineSubtitle(ServerConfig cfg, String mediaGuid, String trimId)
            throws FnException {
        requestAuthenticated(cfg, "POST", "/Items/" + enc(mediaGuid)
                + "/RemoteSearch/Subtitles/" + enc(trimId), null, null);
    }

    public void deleteSubtitle(ServerConfig cfg, String subtitleGuid) throws FnException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("/Videos/([^/]+)/([^/]+)/Subtitles/(\\d+)")
                .matcher(subtitleGuid == null ? "" : subtitleGuid);
        if (!matcher.find()) {
            throw new FnException(FailureReason.SERVER_ERROR, "无法识别 Jellyfin 字幕轨道");
        }
        requestAuthenticated(cfg, "DELETE", "/Videos/" + enc(matcher.group(1))
                + "/Subtitles/" + matcher.group(3), null, null);
    }

    public void markSubtitles(ServerConfig cfg, String mediaGuid, List<String> filepaths)
            throws FnException {
        throw new FnException(FailureReason.UNSUPPORTED_FORMAT, "Jellyfin 不支持从 NAS 路径标记字幕");
    }

    public List<String> authorizedDirs(ServerConfig cfg) {
        return new ArrayList<String>();
    }

    public List<FsEntry> listDirectory(ServerConfig cfg, String path) {
        return new ArrayList<FsEntry>();
    }

    public List<QualityLevel> qualityLevels(ServerConfig cfg, MediaItem item) throws FnException {
        int sourceHeight = 0;
        List<Object> streams = Json.list(firstSource(playbackInfo(cfg, item)).get("MediaStreams"));
        for (int i = 0; i < streams.size(); i++) {
            Map<String, Object> stream = Json.map(streams.get(i));
            if ("Video".equalsIgnoreCase(Json.str(stream, "Type", ""))) {
                sourceHeight = Math.max(sourceHeight, Json.integer(stream, "Height", 0));
            }
        }
        if (sourceHeight <= 0) {
            sourceHeight = item.height;
        }
        List<QualityLevel> out = new ArrayList<QualityLevel>();
        int[] tiers = {2160, 1080, 720, 540, 480, 360};
        for (int i = 0; i < tiers.length; i++) {
            int height = tiers[i];
            if (height > sourceHeight) {
                continue;
            }
            String value = height >= 2160 ? "4k" : height + "p";
            out.add(new QualityLevel(value, value.toUpperCase(java.util.Locale.US), height));
        }
        return out;
    }

    public boolean preferOriginal(ServerConfig cfg, Capability cap) {
        return !cfg.forceTranscode && cap != null && cap.sdkInt >= 21;
    }

    public List<RouteOption> liveRoutes(ServerConfig cfg, MediaItem item) {
        return new ArrayList<RouteOption>();
    }

    public void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended) {
        reportProgress(cfg, item, seconds, ended, (String) null);
    }

    public void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended,
                               String playLink) {
        try {
            if (ended) {
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("Played", Boolean.TRUE);
                body.put("PlaybackPositionTicks", Long.valueOf(0L));
                requestAuthenticated(cfg, "POST", "/UserItems/" + enc(item.guid) + "/UserData",
                        null, body);
                stopPlaySession(cfg, item, playLink);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            String sessionId = playSessionId(playLink);
            if (sessionId.length() > 0) body.put("SessionId", sessionId);
            body.put("ItemId", item.guid);
            body.put("MediaSourceId", item.mediaGuid);
            body.put("PositionTicks", Long.valueOf(Math.max(0, seconds) * 10000000L));
            body.put("CanSeek", Boolean.TRUE);
            requestAuthenticated(cfg, "POST", "/Sessions/Playing/Progress", null, body);
        } catch (FnException ex) {
            L.w("JellyfinApi", "播放进度上报失败：" + ex.detail);
        }
    }

    public String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx)
            throws FnException {
        return resolveTranscodeUrl(cfg, item, tx, null);
    }

    public String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx,
                                      PlaybackRequest request) throws FnException {
        if (request != null) cfg = request.applyTo(cfg);
        if (cfg.transcodeResolution != null && cfg.transcodeResolution.length() > 0) {
            tx.width = targetWidth(cfg.transcodeResolution, tx.width);
            tx.height = targetHeight(cfg.transcodeResolution, tx.height);
            if (cfg.transcodeAudioEncoder.length() > 0) {
                tx.audioCodec = cfg.transcodeAudioEncoder;
            }
        }
        int selectedBitrate = 0;
        if (cfg.transcodeResolution != null && cfg.transcodeResolution.length() > 0) {
            selectedBitrate = cfg.transcodeBitrateBps > 0
                    ? cfg.transcodeBitrateBps : targetBitrateBps(cfg.transcodeResolution);
        }
        if (selectedBitrate <= 0) {
            selectedBitrate = parseInt(tx.transcodeParams.get("maxBitrate"), 0) * 1000;
        }
        if (selectedBitrate > 0) {
            tx.transcodeParams.put("maxBitrate", String.valueOf(selectedBitrate / 1000));
            tx.bitrateKbps = selectedBitrate / 1000;
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        String sourceId = tx.mediaSourceId != null && tx.mediaSourceId.length() > 0
                ? tx.mediaSourceId : item.mediaGuid;
        params.put("mediaSourceId", sourceId);
        params.put("videoCodec", tx.videoCodec);
        params.put("audioCodec", tx.audioCodec);
        params.put("maxWidth", String.valueOf(tx.width));
        params.put("maxHeight", String.valueOf(tx.height));
        int bitrate = parseInt(tx.transcodeParams.get("maxBitrate"), 0);
        if (bitrate > 0) {
            params.put("videoBitRate", String.valueOf(bitrate * 1000));
        }
        int channels = cfg.transcodeAudioChannels > 0 ? cfg.transcodeAudioChannels : 2;
        params.put("audioChannels", String.valueOf(channels));
        params.put("transcodingMaxAudioChannels", String.valueOf(channels));
        tx.audioChannels = channels;
        params.put("maxVideoBitDepth", "8");
        params.put("segmentContainer", "ts");
        params.put("segmentLength", String.valueOf(hlsSegmentSeconds(item)));
        int position = item.playbackPositionSec > 0 ? item.playbackPositionSec : item.resumeSeconds;
        params.put("startTimeTicks", String.valueOf(Math.max(0, position) * 10000000L));
        params.put("deviceId", cfg.deviceId);
        params.put("static", "false");
        tx.protocol = "hls";
        String url = buildUrl(cfg.baseUrl, "/Videos/" + enc(item.guid) + "/master.m3u8", params);
        tx.session = playbackSession(url);
        return url;
    }

    public String refreshPlayLink(ServerConfig cfg, MediaItem item) {
        return "";
    }

    public boolean isPlayLinkAlive(ServerConfig cfg, String playLink) {
        return true;
    }

    public void touchPlaySession(ServerConfig cfg, String playLink) {
        try {
            String sessionId = playSessionId(playLink);
            if (sessionId.length() == 0) return;
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("SessionId", sessionId);
            body.put("MediaSourceId", itemMediaSourceId(cfg, playLink));
            body.put("IsPaused", Boolean.FALSE);
            requestAuthenticated(cfg, "POST", "/Sessions/Playing/Progress", null, body);
        } catch (FnException ex) {
            L.w("JellyfinApi", "播放会话心跳失败：" + ex.detail);
        }
    }

    public void quitPlaySession(ServerConfig cfg, String playLink) {
        try {
            stopPlaySession(cfg, null, playLink);
        } catch (FnException ex) {
            L.w("JellyfinApi", "结束播放会话失败：" + ex.detail);
        }
    }

    private void stopPlaySession(ServerConfig cfg, MediaItem item, String playLink)
            throws FnException {
        String sessionId = playSessionId(playLink);
        if (sessionId.length() == 0) return;
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("SessionId", sessionId);
        String sourceId = item == null ? itemMediaSourceId(cfg, playLink) : item.mediaGuid;
        if (sourceId.length() > 0) body.put("MediaSourceId", sourceId);
        body.put("PositionTicks", Long.valueOf(0L));
        requestAuthenticated(cfg, "POST", "/Sessions/Playing/Stopped", null, body);
    }

    private static String itemMediaSourceId(ServerConfig cfg, String playLink) {
        return queryParameter(playLink, "MediaSourceId");
    }

    private static String playSessionId(String playLink) {
        return queryParameter(playLink, "PlaySessionId");
    }

    private static PlaybackSession playbackSession(String url) {
        return new PlaybackSession(url, playSessionId(url),
                queryParameter(url, "MediaSourceId"), "");
    }

    private static String queryParameter(String url, String name) {
        if (url == null) return "";
        int at = url.indexOf('?');
        if (at < 0) return "";
        String[] pairs = url.substring(at + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equalsIgnoreCase(key)) {
                return equals < 0 ? "" : pair.substring(equals + 1);
            }
        }
        return "";
    }

    public static ConfigCodec.ServerInfo probe(String baseUrl) {
        if (baseUrl == null || baseUrl.length() == 0) {
            return null;
        }
        Response response = HttpStack.probe(NetUtil.join(baseUrl, "/System/Info/Public"), 2500);
        if (!response.ok()) {
            return null;
        }
        try {
            Map<String, Object> info = Json.parseObject(response.body);
            if (!isJellyfinInfo(info)) {
                return null;
            }
            ConfigCodec.ServerInfo server = new ConfigCodec.ServerInfo();
            server.baseUrl = baseUrl;
            server.name = Json.str(info, "ServerName", "");
            server.port = NetUtil.portOf(baseUrl);
            server.method = "MANUAL";
            server.verified = true;
            server.signature = "jellyfin";
            return server;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static MediaItem mapItem(Map<String, Object> value) {
        MediaItem item = new MediaItem();
        item.guid = Json.str(value, "Id", "");
        item.mediaGuid = item.guid;
        String jellyfinType = Json.str(value, "Type", "Movie");
        item.type = "Series".equalsIgnoreCase(jellyfinType) ? "TV" : jellyfinType;
        item.mediaType = item.type;
        item.title = Json.str(value, "Name", "未命名");
        item.year = String.valueOf(Json.integer(value, "ProductionYear", 0));
        if ("0".equals(item.year)) {
            item.year = "";
        }
        item.runtimeMinutes = (int) (Json.longer(value, "RunTimeTicks", 0L) / 600000000L);
        item.voteAverage = Json.doubleNum(value, "CommunityRating", 0.0);
        item.overview = Json.str(value, "Overview", "");
        item.contentRating = Json.str(value, "OfficialRating", "");
        item.showTitle = Json.str(value, "SeriesName", "");
        item.seasonGuid = Json.str(value, "ParentId", "");
        item.seasonCount = Json.integer(value, "ChildCount", 0);
        item.episodeCount = Json.integer(value, "RecursiveItemCount", 0);
        if ("Episode".equalsIgnoreCase(item.mediaType)) {
            int season = Json.integer(value, "ParentIndexNumber", 0);
            int episode = Json.integer(value, "IndexNumber", 0);
            if (season > 0 || episode > 0) {
                item.subtitle = "S" + season + "E" + episode;
            }
        }
        List<Object> ancestors = Json.list(value.get("AncestorIds"));
        if (!ancestors.isEmpty()) {
            item.libraryGuid = String.valueOf(ancestors.get(0));
        }
        item.genres = stringList(value.get("Genres"));
        item.countries = stringList(value.get("ProductionLocations"));
        Map<String, Object> tags = imageTags(value);
        item.posterPath = imagePath(item.guid, "Primary", tags);
        if (item.posterPath.length() == 0) {
            item.posterPath = imagePath(item.guid, "Thumb", tags);
        }
        item.backdropPath = imagePath(item.guid, "Backdrop", tags);
        if (item.backdropPath.length() == 0) {
            item.backdropPath = indexedImagePath(item.guid, "Backdrop",
                    value.get("BackdropImageTags"));
        }
        if (item.backdropPath.length() == 0) {
            item.backdropPath = parentImagePath(value, "ParentBackdropItemId",
                    "ParentBackdropImageTags", "Backdrop");
        }
        item.logoPath = imagePath(item.guid, "Logo", tags);
        if (item.logoPath.length() == 0) {
            item.logoPath = parentImagePath(value, "ParentLogoItemId",
                    "ParentLogoImageTag", "Logo");
        }
        if (item.posterPath.length() == 0) {
            item.posterPath = parentImagePath(value, "ParentPrimaryImageItemId",
                    "ParentPrimaryImageTag", "Primary");
        }
        Map<String, Object> userData = Json.map(value.get("UserData"));
        long positionTicks = Json.longer(userData, "PlaybackPositionTicks", 0L);
        item.resumeSeconds = ticksToSeconds(positionTicks);
        item.watchedSeconds = item.resumeSeconds;
        item.progressPercent = Math.max(0, Math.min(100,
                (int) Math.round(Json.doubleNum(userData, "PlayedPercentage", 0.0))));
        item.favorite = Json.bool(userData, "IsFavorite", false);
        item.watched = Json.bool(userData, "Played", false);
        item.matchState = 1;
        fillStreamInfo(item, firstSource(value));
        return item;
    }

    static String imageUrl(String baseUrl, String path, int width) {
        if (path == null || path.length() == 0) {
            return "";
        }
        String url = path.startsWith("http://") || path.startsWith("https://")
                ? path : NetUtil.join(baseUrl, path);
        if (width > 0 && path.indexOf("/Images/") >= 0) {
            url = NetUtil.appendQuery(url, "maxWidth=" + width);
        }
        return url;
    }

    private static List<MediaItem> mapItems(Object value) {
        List<Object> values = Json.list(value);
        List<MediaItem> out = new ArrayList<MediaItem>();
        for (int i = 0; i < values.size(); i++) {
            MediaItem item = mapItem(Json.map(values.get(i)));
            if (item.guid.length() > 0) {
                out.add(item);
            }
        }
        return out;
    }

    private static Map<String, Object> playbackInfo(ServerConfig cfg, MediaItem item)
            throws FnException {
        Object data = new JellyfinApi().requestAuthenticated(cfg, "GET", "/Items/" + enc(item.guid)
                + "/PlaybackInfo", baseParams(cfg), null);
        return Json.map(data);
    }

    private static Map<String, Object> postedPlaybackInfo(ServerConfig cfg, MediaItem item)
            throws FnException {
        return postedPlaybackInfo(cfg, item, null);
    }

    private static Map<String, Object> postedPlaybackInfo(ServerConfig cfg, MediaItem item,
                                                          PlaybackRequest request)
            throws FnException {
        int bitrate = request != null ? request.bitrateBps
                : (cfg.transcodeBitrateBps > 0 ? cfg.transcodeBitrateBps : 12000000);
        int audioChannels = request != null ? request.audioChannels
                : (cfg.transcodeAudioChannels > 0 ? cfg.transcodeAudioChannels : 2);
        int width = request != null ? request.width
                : targetWidth(cfg.transcodeResolution, item.width);
        int height = request != null ? request.height
                : targetHeight(cfg.transcodeResolution, item.height);
        String videoCodec = request != null ? request.videoCodec
                : (cfg.transcodeVideoEncoder.length() > 0 ? cfg.transcodeVideoEncoder : "h264");
        String audioCodec = request != null ? request.audioCodec
                : (cfg.transcodeAudioEncoder.length() > 0 ? cfg.transcodeAudioEncoder : "ac3");
        int position = request != null ? (int) (request.startPositionMs / 1000L)
                : (item.playbackPositionSec > 0 ? item.playbackPositionSec : item.resumeSeconds);

        Map<String, Object> directPlay = new LinkedHashMap<String, Object>();
        directPlay.put("Container", item.container.length() > 0 ? item.container : "mkv");
        directPlay.put("Type", "Video");
        directPlay.put("VideoCodec", item.videoCodec.length() > 0 ? item.videoCodec : "h264");
        directPlay.put("AudioCodec", item.audioCodec.length() > 0 ? item.audioCodec : "ac3");
        List<Object> directPlayProfiles = new ArrayList<Object>();
        directPlayProfiles.add(directPlay);

        Map<String, Object> transcoding = new LinkedHashMap<String, Object>();
        transcoding.put("Container", "ts");
        transcoding.put("Type", "Video");
        transcoding.put("VideoCodec", videoCodec);
        transcoding.put("AudioCodec", audioCodec);
        transcoding.put("Protocol", "hls");
        transcoding.put("Context", "Streaming");
        transcoding.put("CopyTimestamps", Boolean.FALSE);
        transcoding.put("SegmentLength", Integer.valueOf(request != null
                ? request.segmentLengthSeconds : hlsSegmentSeconds(item)));
        List<Object> transcodingProfiles = new ArrayList<Object>();
        transcodingProfiles.add(transcoding);

        Map<String, Object> videoConditions = new LinkedHashMap<String, Object>();
        videoConditions.put("Condition", "LessThanEqual");
        videoConditions.put("Property", "Width");
        videoConditions.put("Value", String.valueOf(width));
        videoConditions.put("IsRequired", Boolean.FALSE);
        Map<String, Object> heightConditions = new LinkedHashMap<String, Object>();
        heightConditions.put("Condition", "LessThanEqual");
        heightConditions.put("Property", "Height");
        heightConditions.put("Value", String.valueOf(height));
        heightConditions.put("IsRequired", Boolean.FALSE);
        Map<String, Object> depthConditions = new LinkedHashMap<String, Object>();
        depthConditions.put("Condition", "LessThanEqual");
        depthConditions.put("Property", "VideoBitDepth");
        depthConditions.put("Value", "8");
        depthConditions.put("IsRequired", Boolean.FALSE);
        List<Object> videoProfileConditions = new ArrayList<Object>();
        videoProfileConditions.add(videoConditions);
        videoProfileConditions.add(heightConditions);
        videoProfileConditions.add(depthConditions);
        Map<String, Object> videoProfile = new LinkedHashMap<String, Object>();
        videoProfile.put("Type", "Video");
        videoProfile.put("Codec", videoCodec);
        videoProfile.put("Conditions", videoProfileConditions);

        Map<String, Object> channelConditions = new LinkedHashMap<String, Object>();
        channelConditions.put("Condition", "LessThanEqual");
        channelConditions.put("Property", "AudioChannels");
        channelConditions.put("Value", String.valueOf(audioChannels));
        channelConditions.put("IsRequired", Boolean.FALSE);
        List<Object> audioProfileConditions = new ArrayList<Object>();
        audioProfileConditions.add(channelConditions);
        Map<String, Object> audioProfile = new LinkedHashMap<String, Object>();
        audioProfile.put("Type", "Audio");
        audioProfile.put("Codec", audioCodec);
        audioProfile.put("Conditions", audioProfileConditions);
        List<Object> codecProfiles = new ArrayList<Object>();
        codecProfiles.add(videoProfile);
        codecProfiles.add(audioProfile);

        List<Object> subtitleProfiles = new ArrayList<Object>();
        for (String format : new String[]{"ass", "srt", "vtt"}) {
            Map<String, Object> subtitle = new LinkedHashMap<String, Object>();
            subtitle.put("Format", format);
            subtitle.put("Method", "External");
            subtitleProfiles.add(subtitle);
        }

        Map<String, Object> deviceProfile = new LinkedHashMap<String, Object>();
        deviceProfile.put("Name", CLIENT);
        deviceProfile.put("MaxStreamingBitrate", Integer.valueOf(bitrate));
        deviceProfile.put("MaxStaticBitrate", Integer.valueOf(bitrate));
        deviceProfile.put("MusicStreamingTranscodingBitrate", Integer.valueOf(192000));
        deviceProfile.put("DirectPlayProfiles", directPlayProfiles);
        deviceProfile.put("TranscodingProfiles", transcodingProfiles);
        deviceProfile.put("CodecProfiles", codecProfiles);
        deviceProfile.put("SubtitleProfiles", subtitleProfiles);
        deviceProfile.put("ResponseProfiles", new ArrayList<Object>());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("UserId", cfg.userId);
        body.put("MaxStreamingBitrate", Integer.valueOf(bitrate));
        body.put("MaxAudioChannels", Integer.valueOf(audioChannels));
        body.put("StartTimeTicks", Long.valueOf(Math.max(0, position) * 10000000L));
        body.put("MediaSourceId", item.mediaGuid.length() > 0 ? item.mediaGuid : item.guid);
        body.put("DeviceProfile", deviceProfile);
        Object data = new JellyfinApi().requestAuthenticated(cfg, "POST", "/Items/" + enc(item.guid)
                + "/PlaybackInfo", baseParams(cfg), body);
        return Json.map(data);
    }

    static int hlsSegmentSeconds(MediaItem item) {
        int seconds = item == null ? 0 : Math.max(0, item.runtimeMinutes) * 60;
        if (seconds <= 0) return 3;
        return Math.max(3, (seconds + 1499) / 1500);
    }

    private static int targetWidth(String resolution, int fallback) {
        if ("4k".equalsIgnoreCase(resolution)) return 3840;
        if ("1080".equalsIgnoreCase(resolution) || "1080p".equalsIgnoreCase(resolution)) return 1920;
        if ("720".equalsIgnoreCase(resolution) || "720p".equalsIgnoreCase(resolution)) return 1280;
        if ("540".equalsIgnoreCase(resolution) || "540p".equalsIgnoreCase(resolution)) return 960;
        if ("480".equalsIgnoreCase(resolution) || "480p".equalsIgnoreCase(resolution)) return 854;
        if ("360".equalsIgnoreCase(resolution) || "360p".equalsIgnoreCase(resolution)) return 640;
        return fallback > 0 ? fallback : 1920;
    }

    private static int targetHeight(String resolution, int fallback) {
        if ("4k".equalsIgnoreCase(resolution)) return 2160;
        if ("1080".equalsIgnoreCase(resolution) || "1080p".equalsIgnoreCase(resolution)) return 1080;
        if ("720".equalsIgnoreCase(resolution) || "720p".equalsIgnoreCase(resolution)) return 720;
        if ("540".equalsIgnoreCase(resolution) || "540p".equalsIgnoreCase(resolution)) return 540;
        if ("480".equalsIgnoreCase(resolution) || "480p".equalsIgnoreCase(resolution)) return 480;
        if ("360".equalsIgnoreCase(resolution) || "360p".equalsIgnoreCase(resolution)) return 360;
        return fallback > 0 ? fallback : 1080;
    }

    private static int targetBitrateBps(String resolution) {
        int height = targetHeight(resolution, 0);
        if (height >= 2160) return 12000000;
        if (height >= 1080) return 12000000;
        if (height >= 720) return 4000000;
        if (height >= 540) return 2500000;
        if (height >= 480) return 2000000;
        if (height >= 360) return 1000000;
        return 0;
    }

    private static void resolveLibraries(ServerConfig cfg, List<MediaItem> items) {
        Map<String, MediaItem> byId = new LinkedHashMap<String, MediaItem>();
        for (int i = 0; i < items.size(); i++) {
            byId.put(items.get(i).guid, items.get(i));
        }
        try {
            List<Library> libraries = new JellyfinApi().listLibraries(cfg);
            for (int i = 0; i < libraries.size(); i++) {
                Library library = libraries.get(i);
                if ("IPTV".equalsIgnoreCase(library.category)) {
                    continue;
                }
                Map<String, String> params = baseParams(cfg);
                params.put("ParentId", library.guid);
                params.put("Recursive", "true");
                params.put("IncludeItemTypes", "Movie,Series");
                params.put("Limit", "200");
                try {
                    Object data = new JellyfinApi().requestAuthenticated(cfg, "GET", "/Items",
                            params, null);
                    List<Object> values = Json.list(Json.map(data).get("Items"));
                    for (int k = 0; k < values.size(); k++) {
                        MediaItem item = byId.get(Json.str(Json.map(values.get(k)), "Id", ""));
                        if (item != null) {
                            item.libraryGuid = library.guid;
                            item.libraryName = library.name;
                        }
                    }
                } catch (FnException ex) {
                    L.w("JellyfinApi", "媒体库条目读取失败 " + library.name + "：" + ex.detail);
                }
            }
        } catch (FnException ex) {
            L.w("JellyfinApi", "媒体库列表读取失败：" + ex.detail);
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).libraryGuid.length() == 0) {
                resolveLibrary(cfg, items.get(i));
            }
        }
    }

    private static void resolveLibrary(ServerConfig cfg, MediaItem item) {
        try {
            Object data = new JellyfinApi().requestAuthenticated(cfg, "GET", "/Items/"
                    + enc(item.guid) + "/Ancestors", baseParams(cfg), null);
            List<Object> ancestors = Json.list(data);
            for (int i = 0; i < ancestors.size(); i++) {
                Map<String, Object> ancestor = Json.map(ancestors.get(i));
                if ("CollectionFolder".equalsIgnoreCase(Json.str(ancestor, "Type", ""))) {
                    item.libraryGuid = Json.str(ancestor, "Id", "");
                    item.libraryName = Json.str(ancestor, "Name", "");
                    return;
                }
            }
        } catch (FnException ex) {
            L.w("JellyfinApi", "媒体库关联读取失败 " + item.guid + "：" + ex.detail);
        }
    }

    private Map<String, Object> itemInfo(ServerConfig cfg, String guid) throws FnException {
        Map<String, String> params = baseParams(cfg);
        params.put("Fields", FIELDS);
        params.put("EnableImages", "true");
        params.put("EnableImageTypes", "Primary,Thumb,Backdrop,Logo");
        params.put("ImageTypeLimit", "1");
        Object data = requestAuthenticated(cfg, "GET", "/Items/" + enc(guid), params, null);
        return Json.map(data);
    }

    private void inheritParentImages(ServerConfig cfg, MediaItem item, String parentId)
            throws FnException {
        String current = parentId == null ? "" : parentId;
        for (int depth = 0; depth < 2 && current.length() > 0
                && (item.posterPath.length() == 0 || item.logoPath.length() == 0
                || item.backdropPath.length() == 0); depth++) {
            Map<String, Object> parent = itemInfo(cfg, current);
            MediaItem parentItem = mapItem(parent);
            if (item.posterPath.length() == 0) {
                item.posterPath = parentItem.posterPath;
            }
            if (item.logoPath.length() == 0) {
                item.logoPath = parentItem.logoPath;
            }
            if (item.backdropPath.length() == 0) {
                item.backdropPath = parentItem.backdropPath;
            }
            String next = Json.str(parent, "ParentId", "");
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
    }

    private Map<String, Object> userData(ServerConfig cfg, String guid) throws FnException {
        Object data = requestAuthenticated(cfg, "GET", "/UserItems/" + enc(guid) + "/UserData",
                baseParams(cfg), null);
        return Json.map(data);
    }

    private Object requestAuthenticated(ServerConfig cfg, String method, String path,
                                        Map<String, String> params, Map<String, Object> body)
            throws FnException {
        if (cfg.token.length() == 0 || cfg.userId.length() == 0) {
            login(cfg);
        }
        Response response = rawCall(cfg, method, path, params, body, authorizationHeader(cfg), true);
        if (response.status != 401 || !cfg.hasCredentials()) {
            return responseObject(response, path);
        }
        login(cfg);
        response = rawCall(cfg, method, path, params, body, authorizationHeader(cfg), true);
        return responseObject(response, path);
    }

    private Object publicCall(ServerConfig cfg, String path) throws FnException {
        Response response = rawCall(cfg, "GET", path, null, null, "", false);
        return responseObject(response, path);
    }

    private Response rawCall(ServerConfig cfg, String method, String path,
                             Map<String, String> params, Map<String, Object> body,
                             String authorization, boolean authenticated) throws FnException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (authorization != null && authorization.length() > 0) {
            headers.put("Authorization", authorization);
        }
        String json = body == null ? null : Json.stringify(body);
        Response response = HttpStack.request(method, buildUrl(cfg.baseUrl, path, params), headers,
                json, cfg.connectTimeoutMs, cfg.readTimeoutMs);
        if (!response.ok()) {
            if (authenticated && response.status == 401) {
                return response;
            }
            throw FnException.fromHttp(response.status, path);
        }
        return response;
    }

    private static Object responseObject(Response response, String path) throws FnException {
        if (!response.ok()) {
            throw FnException.fromHttp(response.status, path);
        }
        if (response.body == null || response.body.length() == 0) {
            return null;
        }
        try {
            return Json.parse(response.body);
        } catch (RuntimeException ex) {
            throw new FnException(FailureReason.SERVER_ERROR, "Jellyfin 返回了无效 JSON", response.status, 0);
        }
    }

    private static Map<String, String> baseParams(ServerConfig cfg) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("UserId", cfg.userId);
        return params;
    }

    private static String clientAuthorization(ServerConfig cfg) {
        return "MediaBrowser Client=\"" + CLIENT + "\", Device=\"" + DEVICE
                + "\", DeviceId=\"" + cfg.deviceId + "\", Version=\"" + VERSION + "\"";
    }

    private static void ensureDeviceId(ServerConfig cfg) {
        if (cfg.deviceId == null || cfg.deviceId.length() == 0) {
            cfg.deviceId = UUID.randomUUID().toString();
        }
    }

    private static Map<String, Object> firstSource(Map<String, Object> playbackInfo) {
        List<Object> sources = Json.list(playbackInfo.get("MediaSources"));
        return sources.isEmpty() ? new LinkedHashMap<String, Object>() : Json.map(sources.get(0));
    }

    private static void fillStreamInfo(MediaItem item, Map<String, Object> source) {
        if (source.isEmpty()) {
            return;
        }
        item.container = Json.str(source, "Container", item.container);
        item.fileName = Json.str(source, "Path", item.fileName);
        item.bitrateKbps = Json.integer(source, "Bitrate", 0) / 1000;
        List<Object> streams = Json.list(source.get("MediaStreams"));
        for (int i = 0; i < streams.size(); i++) {
            Map<String, Object> stream = Json.map(streams.get(i));
            String type = Json.str(stream, "Type", "");
            if ("Video".equalsIgnoreCase(type)) {
                item.videoCodec = Json.str(stream, "Codec", item.videoCodec);
                item.width = Json.integer(stream, "Width", item.width);
                item.height = Json.integer(stream, "Height", item.height);
                item.bitDepth = Json.integer(stream, "BitDepth", item.bitDepth);
                String range = Json.str(stream, "VideoRangeType", "");
                item.hdr = range.length() > 0 && !"SDR".equalsIgnoreCase(range)
                        && !"UNKNOWN".equalsIgnoreCase(range);
                if (item.height > 0) {
                    addUnique(item.resolutions, resolutionOf(item.height));
                }
                addUnique(item.colorRanges, item.hdr ? "HDR" : "SDR");
            } else if ("Audio".equalsIgnoreCase(type) && item.audioCodec.length() == 0) {
                item.audioCodec = Json.str(stream, "Codec", "");
                item.audioChannels = Json.integer(stream, "Channels", 0);
                addUnique(item.audioTypes, item.audioCodec);
            }
        }
        if (item.videoCodec.length() > 0) {
            item.videoCodec = CodecNames.video(item.videoCodec);
        }
        if (item.audioCodec.length() > 0) {
            item.audioCodec = CodecNames.audio(item.audioCodec);
        }
        if (item.container.length() > 0) {
            item.container = CodecNames.container(item.container);
        }
    }

    private static Person mapPerson(Map<String, Object> value) {
        Person person = new Person();
        person.personGuid = Json.str(value, "Id", "");
        person.name = Json.str(value, "Name", "");
        person.role = Json.str(value, "Role", "");
        person.job = Json.str(value, "Type", "");
        person.biography = Json.str(value, "Overview", "");
        person.profilePath = imagePath(person.personGuid, "Primary", imageTags(value));
        if (person.profilePath.length() == 0
                && Json.str(value, "PrimaryImageTag", "").length() > 0) {
            person.profilePath = "/Items/" + enc(person.personGuid) + "/Images/Primary";
        }
        return person;
    }

    private static Map<String, Object> imageTags(Map<String, Object> value) {
        return Json.map(value.get("ImageTags"));
    }

    private static String imagePath(String itemId, String type, Map<String, Object> tags) {
        Object tag = tags.get(type);
        return tag == null || String.valueOf(tag).length() == 0
                ? "" : "/Items/" + enc(itemId) + "/Images/" + type;
    }

    private static String indexedImagePath(String itemId, String type, Object tags) {
        String tag = firstString(tags);
        return tag.length() == 0 ? "" : "/Items/" + enc(itemId) + "/Images/" + type;
    }

    private static String parentImagePath(Map<String, Object> value, String idField,
                                           String tagField, String type) {
        String parentId = Json.str(value, idField, "");
        String tag = firstString(value.get(tagField));
        return parentId.length() == 0 || tag.length() == 0
                ? "" : "/Items/" + enc(parentId) + "/Images/" + type;
    }

    private static String firstString(Object value) {
        List<Object> values = Json.list(value);
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null && String.valueOf(values.get(i)).length() > 0) {
                return String.valueOf(values.get(i));
            }
        }
        return value instanceof String ? (String) value : "";
    }

    private static String subtitleUrl(ServerConfig cfg, String itemId, String sourceId,
                                      int index, String format) {
        String safeFormat = format == null || format.length() == 0 ? "srt" : format;
        return NetUtil.join(cfg.baseUrl, "/Videos/" + enc(itemId) + "/" + enc(sourceId)
                + "/Subtitles/" + index + "/Stream." + enc(safeFormat));
    }

    private static String categoryOf(String collectionType) {
        if ("movies".equalsIgnoreCase(collectionType)) return "Movie";
        if ("tvshows".equalsIgnoreCase(collectionType)) return "TV";
        if ("livetv".equalsIgnoreCase(collectionType)) return "IPTV";
        if ("boxsets".equalsIgnoreCase(collectionType)) return "Mix";
        return collectionType;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
        List<Object> values = Json.list(value);
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                out.add(String.valueOf(values.get(i)));
            }
        }
        return out;
    }

    private static void addUnique(List<String> values, String value) {
        if (value.length() > 0 && !values.contains(value)) {
            values.add(value);
        }
    }

    private static String resolutionOf(int height) {
        return height >= 2160 ? "4k" : height + "P";
    }

    private static int ticksToSeconds(long ticks) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, ticks / 10000000L));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static boolean isJellyfinInfo(Map<String, Object> info) {
        return Json.str(info, "ProductName", "").toLowerCase(java.util.Locale.US)
                .indexOf("jellyfin") >= 0;
    }

    private static String absoluteUrl(String baseUrl, String path) {
        return path.startsWith("http://") || path.startsWith("https://")
                ? path : NetUtil.join(baseUrl, path);
    }

    private static String buildUrl(String baseUrl, String path, Map<String, String> params) {
        String url = NetUtil.join(baseUrl, path);
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return NetUtil.appendQuery(url, query.toString());
    }

    private static String enc(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            return value;
        }
    }
}
