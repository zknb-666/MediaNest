package com.juying.medianest.core.model;

/** POST /v/api/v1/server/path 的文件/目录条目，用于「添加 NAS 字幕文件」浏览。 */
public final class FsEntry {

    public String filename = "";
    public boolean isDir = false;
    public long fileSize = 0L;
    /** 已是 NAS 媒体库条目的名称/id；为空表示普通文件。 */
    public String trimName = "";
    public String trimId = "";

    public String displayName() {
        return filename == null ? "" : filename;
    }

    /** 只把常见字幕扩展名当作可选文件。 */
    public boolean isSubtitleFile() {
        if (isDir) {
            return false;
        }
        String n = filename == null ? "" : filename.toLowerCase(java.util.Locale.US);
        return n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".srt")
                || n.endsWith(".vtt") || n.endsWith(".sub") || n.endsWith(".idx")
                || n.endsWith(".sup") || n.endsWith(".smi");
    }
}
