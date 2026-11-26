package com.juying.medianest.core.model;

/**
 * POST /v/api/v1/subtitle/search 的 data.subtitles[] 条目。
 * 下载时把 trim_id 交给 /subtitle/download，NAS 落盘后重新拉 stream/list 即可看到新轨道。
 */
public final class SubtitleSearchItem {

    /** NAS 字幕库里的字幕 id，下载接口用。 */
    public String trimId = "";
    public String filename = "";
    /** ass / srt / sub 等；空表示 NAS 未给出。 */
    public String format = "";
    /** 语言代码/名称，NAS 原样返回。 */
    public String language = "";
    public long fileSize = 0L;

    public String displayName() {
        if (filename != null && filename.length() > 0) {
            return filename;
        }
        return trimId;
    }

    /** 结果行右侧的格式徽标，例如「ASS · 简体中文」。 */
    public String detail() {
        StringBuilder sb = new StringBuilder();
        if (format != null && format.length() > 0) {
            sb.append(format.toUpperCase(java.util.Locale.US));
        }
        if (language != null && language.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(language);
        }
        return sb.toString();
    }
}
