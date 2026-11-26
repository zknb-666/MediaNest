package com.juying.medianest.core.model;

/** Provider 无关的播放会话句柄。 */
public final class PlaybackSession {
    public final String url;
    public final String id;
    public final String mediaSourceId;
    public final String mediaDir;
    public final boolean renewable;

    public PlaybackSession(String url, String id, String mediaSourceId, String mediaDir) {
        this.url = url == null ? "" : url;
        this.id = id == null ? "" : id;
        this.mediaSourceId = mediaSourceId == null ? "" : mediaSourceId;
        this.mediaDir = mediaDir == null ? "" : mediaDir;
        this.renewable = this.id.length() > 0 || this.mediaDir.length() > 0;
    }

    public boolean isEmpty() {
        return url.length() == 0;
    }
}
