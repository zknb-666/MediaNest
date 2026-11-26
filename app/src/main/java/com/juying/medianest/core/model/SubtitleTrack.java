package com.juying.medianest.core.model;

/** stream/list 返回的 subtitle_streams 条目；字幕固定从 NAS 读取，不在本地搜索。 */
public final class SubtitleTrack {

    public String guid = "";
    public String title = "";
    public String language = "";
    public String format = "";
    public boolean isDefault = false;
    /** NAS 的 extra_file=1 / is_external=1：有独立字幕文件，可从 /subtitle/dl 取文本自行渲染。 */
    public boolean isExternal = false;
    public boolean hasDownloadableFile = false;
    public String mediaGuid = "";
    public String url = "";

    /** true 表示字幕已烧录在 NAS 转码流里，客户端不渲染文本层。 */
    public boolean isBurnedIn() {
        return !hasDownloadableFile;
    }

    /** 遥控器菜单里显示的轨道名。 */
    public String displayName() {
        if (title != null && title.length() > 0) {
            return title;
        }
        if (language != null && language.length() > 0) {
            return language.toUpperCase(java.util.Locale.US);
        }
        return format != null && format.length() > 0 ? format.toUpperCase(java.util.Locale.US) : "字幕";
    }
}
