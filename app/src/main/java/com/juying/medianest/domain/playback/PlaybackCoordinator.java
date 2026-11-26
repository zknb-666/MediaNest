package com.juying.medianest.domain.playback;

import com.juying.medianest.core.util.L;
import com.juying.medianest.domain.port.MediaApi;
import com.juying.medianest.core.policy.BitrateController;
import com.juying.medianest.core.model.Capability;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.MediaItem;
import com.juying.medianest.core.policy.NasLevels;
import com.juying.medianest.core.model.PlaybackPlan;
import com.juying.medianest.core.model.PlaybackRequest;
import com.juying.medianest.core.model.QualityLevel;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.core.model.StreamCandidate;
import com.juying.medianest.core.policy.StreamSelector;
import com.juying.medianest.core.policy.TranscodeProfile;

import java.util.List;

/** 领域层播放编排：策略请求、Provider 选流、候选排序与地址解析。 */
public final class PlaybackCoordinator {
    public static final class Result {
        public final PlaybackPlan plan;
        public final PlaybackRequest request;
        public final BitrateController controller;
        public final String note;
        public final boolean directFallback;

        Result(PlaybackPlan plan, PlaybackRequest request, BitrateController controller,
               String note, boolean directFallback) {
            this.plan = plan;
            this.request = request;
            this.controller = controller;
            this.note = note;
            this.directFallback = directFallback;
        }
    }

    private PlaybackCoordinator() {
    }

    public static Result build(MediaApi api, ServerConfig cfg, MediaItem item, Capability cap,
                               List<QualityLevel> levels, int degradeLevel,
                               BitrateController controller) throws FnException {
        return build(api, cfg, item, cap, levels, degradeLevel, controller, 0L);
    }

    public static Result build(MediaApi api, ServerConfig cfg, MediaItem item, Capability cap,
                               List<QualityLevel> levels, int degradeLevel,
                               BitrateController controller, long resumeAtMs) throws FnException {
        return build(api, cfg, item, cap, levels, degradeLevel, controller, resumeAtMs, 0);
    }

    public static Result build(MediaApi api, ServerConfig cfg, MediaItem item, Capability cap,
                               List<QualityLevel> levels, int degradeLevel,
                               BitrateController controller, long resumeAtMs,
                               int playlistWindow) throws FnException {
        return build(api, cfg, item, cap, levels, degradeLevel, controller, resumeAtMs,
                playlistWindow, true);
    }

    public static Result build(MediaApi api, ServerConfig cfg, MediaItem item, Capability cap,
                               List<QualityLevel> levels, int degradeLevel,
                               BitrateController controller, long resumeAtMs,
                               int playlistWindow, boolean fnosLongSegments) throws FnException {
        TranscodeProfile profile = TranscodeProfile.pick(cap, item, cfg.forceTranscode,
                degradeLevel, NasLevels.resolutionValues(levels), cfg.transcodeResolution);
        PlaybackRequest request = new PlaybackRequest();
        request.width = PlaybackRequest.targetWidth(profile.resolution, item.width);
        request.height = PlaybackRequest.targetHeight(profile.resolution, item.height);
        request.bitrateBps = profile.bitrateBps;
        request.videoCodec = profile.videoEncoder.length() > 0 ? profile.videoEncoder : "h264";
        request.audioCodec = profile.audioEncoder.length() > 0 ? profile.audioEncoder : "ac3";
        request.audioChannels = profile.audioChannels;
        request.transcodeAudio = profile.needAudioTranscode;
        request.segmentLengthSeconds = PlaybackRequest.segmentLengthFor(item);
        long itemStartMs = Math.max(0L,
                (item.playbackPositionSec > 0 ? item.playbackPositionSec : item.resumeSeconds) * 1000L);
        request.startPositionMs = resumeAtMs > 0 ? resumeAtMs : itemStartMs;
        request.playlistWindow = playlistWindow;
        request.fnosLongSegments = fnosLongSegments;
        request.forceTranscode = cfg.forceTranscode;

        int deviceCeiling = cap != null && cap.bitrateReported
                ? cap.maxBitrateKbps * 1000 : request.bitrateBps;
        int sourceCeiling = item.bitrateKbps * 1000;
        if (controller == null) {
            controller = new BitrateController(request.bitrateBps, deviceCeiling, sourceCeiling);
        } else {
            controller.capCurrent(request.bitrateBps);
        }
        request.bitrateBps = controller.currentBps();
        L.i("PlaybackCoordinator", "video=" + request.videoCodec + " " + request.width + "x"
                + request.height + " bitrate=" + request.bitrateBps + " audio=" + request.audioCodec
                + " channels=" + request.audioChannels + " start=" + request.startPositionMs + "ms");

        List<StreamCandidate> streams = api.getStreams(cfg, item, request);
        boolean directFallback = false;
        for (int i = 0; i < streams.size(); i++) {
            if (streams.get(i).fallbackOnly) {
                directFallback = true;
                break;
            }
        }
        Capability effective = cap == null ? Capability.baseline() : Capability.fromJson(cap.toJson());
        effective.maxAudioChannels = Math.max(effective.maxAudioChannels, profile.audioChannels);
        effective.preferOriginal = api.preferOriginal(cfg, cap) && !profile.audioUnsupported;
        PlaybackPlan plan = StreamSelector.select(effective, item, streams,
                cfg.nasTranscodeCapable && !directFallback);
        for (int i = 0; i < plan.candidates.size(); i++) {
            StreamCandidate candidate = plan.candidates.get(i);
            if (!StreamSelector.needsUrlResolution(candidate)) continue;
            try {
                candidate.url = api.resolveTranscodeUrl(cfg, item, candidate, request);
            } catch (FnException ex) {
                candidate.url = "";
                L.w("PlaybackCoordinator", "转码地址解析失败：" + ex.detail);
            }
        }
        return new Result(plan, request, controller, profile.note, directFallback);
    }
}
