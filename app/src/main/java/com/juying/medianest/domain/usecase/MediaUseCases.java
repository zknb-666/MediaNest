package com.juying.medianest.domain.usecase;

import com.juying.medianest.core.model.AudioTrack;
import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.FsEntry;
import com.juying.medianest.core.model.Library;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.model.Person;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.PlaybackSession;
import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.RouteOption;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.model.SubtitleSearchItem;
import com.juying.medianest.core.model.SubtitleTrack;
import com.juying.medianest.core.policy.FilterEngine;
import com.juying.medianest.domain.port.MediaApi;

import java.util.List;
import java.util.Map;

/** Domain-facing media operations backed by one provider port implementation. */
public final class MediaUseCases implements MediaApi {

    private final MediaApi delegate;

    public MediaUseCases(MediaApi delegate) {
        this.delegate = delegate;
    }

    @Override
    public ConfigCodec.ServerInfo verify(ServerConfig cfg) throws FnException {
        return delegate.verify(cfg);
    }

    @Override
    public String refreshToken(ServerConfig cfg) throws FnException {
        return delegate.refreshToken(cfg);
    }

    @Override
    public String authorizationHeader(ServerConfig cfg) {
        return delegate.authorizationHeader(cfg);
    }

    @Override
    public Map<String, String> fetchUserPrefs(ServerConfig cfg) throws FnException {
        return delegate.fetchUserPrefs(cfg);
    }

    @Override
    public void updateUserPrefs(ServerConfig cfg, String lan, String audioLan) throws FnException {
        delegate.updateUserPrefs(cfg, lan, audioLan);
    }

    @Override
    public String fetchUserData(ServerConfig cfg, String key) throws FnException {
        return delegate.fetchUserData(cfg, key);
    }

    @Override
    public void saveUserData(ServerConfig cfg, String key, String value) throws FnException {
        delegate.saveUserData(cfg, key, value);
    }

    @Override
    public List<MediaItem> listMedia(ServerConfig cfg, boolean enrich, int limit) throws FnException {
        return delegate.listMedia(cfg, enrich, limit);
    }

    @Override
    public List<MediaItem> listContinue(ServerConfig cfg) throws FnException {
        return delegate.listContinue(cfg);
    }

    @Override
    public List<MediaItem> search(ServerConfig cfg, String query) throws FnException {
        return delegate.search(cfg, query);
    }

    @Override
    public List<MediaItem> listEpisodes(ServerConfig cfg, String seasonGuid) throws FnException {
        return delegate.listEpisodes(cfg, seasonGuid);
    }

    @Override
    public List<MediaItem> listSeasons(ServerConfig cfg, String showGuid) throws FnException {
        return delegate.listSeasons(cfg, showGuid);
    }

    @Override
    public List<Library> listLibraries(ServerConfig cfg) throws FnException {
        return delegate.listLibraries(cfg);
    }

    @Override
    public List<MediaItem> listLiveChannels(ServerConfig cfg, int limit) throws FnException {
        return delegate.listLiveChannels(cfg, limit);
    }

    @Override
    public Map<String, Integer> libraryCounts(ServerConfig cfg) throws FnException {
        return delegate.libraryCounts(cfg);
    }

    @Override
    public FilterEngine.TagSet filterTags(ServerConfig cfg, String scope) throws FnException {
        return delegate.filterTags(cfg, scope);
    }

    @Override
    public void enrichFilterMeta(ServerConfig cfg, List<MediaItem> items) {
        delegate.enrichFilterMeta(cfg, items);
    }

    @Override
    public String showBackdrop(ServerConfig cfg, String itemGuid) throws FnException {
        return delegate.showBackdrop(cfg, itemGuid);
    }

    @Override
    public void loadDetail(ServerConfig cfg, MediaItem item) throws FnException {
        delegate.loadDetail(cfg, item);
    }

    @Override
    public List<AudioTrack> listAudioTracks(ServerConfig cfg, MediaItem item) throws FnException {
        return delegate.listAudioTracks(cfg, item);
    }

    @Override
    public List<Person> listPersons(ServerConfig cfg, String itemGuid) throws FnException {
        return delegate.listPersons(cfg, itemGuid);
    }

    @Override
    public Person loadPerson(ServerConfig cfg, String personGuid) throws FnException {
        return delegate.loadPerson(cfg, personGuid);
    }

    @Override
    public List<MediaItem> listPersonItems(ServerConfig cfg, String personGuid) throws FnException {
        return delegate.listPersonItems(cfg, personGuid);
    }

    @Override
    public void setFavorite(ServerConfig cfg, String guid, boolean favorite) throws FnException {
        delegate.setFavorite(cfg, guid, favorite);
    }

    @Override
    public void setWatched(ServerConfig cfg, String guid, boolean watched) throws FnException {
        delegate.setWatched(cfg, guid, watched);
    }

    @Override
    public boolean isFavorite(ServerConfig cfg, String guid) throws FnException {
        return delegate.isFavorite(cfg, guid);
    }

    @Override
    public boolean isWatched(ServerConfig cfg, String guid) throws FnException {
        return delegate.isWatched(cfg, guid);
    }

    @Override
    public String imageUrl(ServerConfig cfg, String posterPath, int width) {
        return delegate.imageUrl(cfg, posterPath, width);
    }

    @Override
    public List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item) throws FnException {
        return delegate.getStreams(cfg, item);
    }

    @Override
    public List<StreamCandidate> getStreams(ServerConfig cfg, MediaItem item,
                                           PlaybackRequest request) throws FnException {
        return delegate.getStreams(cfg, item, request);
    }

    @Override
    public boolean preferOriginal(ServerConfig cfg, Capability cap) {
        return delegate.preferOriginal(cfg, cap);
    }

    @Override
    public List<SubtitleTrack> listSubtitles(ServerConfig cfg, MediaItem item) throws FnException {
        return delegate.listSubtitles(cfg, item);
    }

    @Override
    public String subtitleContent(ServerConfig cfg, String guid, String format) throws FnException {
        return delegate.subtitleContent(cfg, guid, format);
    }

    @Override
    public List<SubtitleSearchItem> searchSubtitles(ServerConfig cfg, String mediaGuid, String lan)
            throws FnException {
        return delegate.searchSubtitles(cfg, mediaGuid, lan);
    }

    @Override
    public void downloadOnlineSubtitle(ServerConfig cfg, String mediaGuid, String trimId)
            throws FnException {
        delegate.downloadOnlineSubtitle(cfg, mediaGuid, trimId);
    }

    @Override
    public void deleteSubtitle(ServerConfig cfg, String subtitleGuid) throws FnException {
        delegate.deleteSubtitle(cfg, subtitleGuid);
    }

    @Override
    public void markSubtitles(ServerConfig cfg, String mediaGuid, List<String> filepaths)
            throws FnException {
        delegate.markSubtitles(cfg, mediaGuid, filepaths);
    }

    @Override
    public List<String> authorizedDirs(ServerConfig cfg) throws FnException {
        return delegate.authorizedDirs(cfg);
    }

    @Override
    public List<FsEntry> listDirectory(ServerConfig cfg, String path) throws FnException {
        return delegate.listDirectory(cfg, path);
    }

    @Override
    public List<QualityLevel> qualityLevels(ServerConfig cfg, MediaItem item) throws FnException {
        return delegate.qualityLevels(cfg, item);
    }

    @Override
    public List<RouteOption> liveRoutes(ServerConfig cfg, MediaItem item) throws FnException {
        return delegate.liveRoutes(cfg, item);
    }

    @Override
    public void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended) {
        delegate.reportProgress(cfg, item, seconds, ended);
    }

    @Override
    public void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended,
                               String playLink) {
        delegate.reportProgress(cfg, item, seconds, ended, playLink);
    }

    @Override
    public void reportProgress(ServerConfig cfg, MediaItem item, int seconds, boolean ended,
                               PlaybackSession session) {
        delegate.reportProgress(cfg, item, seconds, ended, session);
    }

    @Override
    public String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx)
            throws FnException {
        return delegate.resolveTranscodeUrl(cfg, item, tx);
    }

    @Override
    public String resolveTranscodeUrl(ServerConfig cfg, MediaItem item, StreamCandidate tx,
                                      PlaybackRequest request) throws FnException {
        return delegate.resolveTranscodeUrl(cfg, item, tx, request);
    }

    @Override
    public String refreshPlayLink(ServerConfig cfg, MediaItem item) throws FnException {
        return delegate.refreshPlayLink(cfg, item);
    }

    @Override
    public String refreshPlayLink(ServerConfig cfg, MediaItem item,
                                  PlaybackRequest request) throws FnException {
        return delegate.refreshPlayLink(cfg, item, request);
    }

    @Override
    public boolean isPlayLinkAlive(ServerConfig cfg, String playLink) throws FnException {
        return delegate.isPlayLinkAlive(cfg, playLink);
    }

    @Override
    public void touchPlaySession(ServerConfig cfg, String playLink) throws FnException {
        delegate.touchPlaySession(cfg, playLink);
    }

    @Override
    public void quitPlaySession(ServerConfig cfg, String playLink) throws FnException {
        delegate.quitPlaySession(cfg, playLink);
    }
}
