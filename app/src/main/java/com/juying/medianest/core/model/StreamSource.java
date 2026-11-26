package com.juying.medianest.core.model;

/** 一条候选播放流的来源类型。顺序即优先级语义。 */
public enum StreamSource {
    /** NAS 上的原始文件流（直接字节范围读取，画质无损、不占 NAS CPU）。 */
    ORIGINAL("原始流"),
    /** NAS 端解码/转码后输出的流（H.264/AAC，兼容性最好）。 */
    NAS_TRANSCODE("NAS 解码流"),
    /** 本地软解同一条原始流。 */
    CLIENT_SOFTWARE("本地软解"),
    /** 交给系统里第三方播放器。 */
    EXTERNAL_PLAYER("外部播放器");

    private final String label;

    StreamSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
