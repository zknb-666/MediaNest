package com.juying.medianest.core.model;

/**
 * stream/list 的 audio_streams 条目。
 *
 * 官方详情页「音频」下拉的数据源：条目只用于展示跟踪名与是否默认，
 * 选择结果通过 item.audioGuid 传给 /play/play，不在客户端解码。
 */
public final class AudioTrack {

    public String guid = "";
    public String title = "";
    public String language = "";
    public String audioType = "";
    public boolean isDefault = false;
}
