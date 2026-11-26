package com.juying.medianest.core.model;

/** 用户可见的失败原因分类。每个分类都带可执行的排查建议。 */
public enum FailureReason {

    NONE("", ""),
    CONNECTION_FAILED("无法连接到 NAS", "确认电视与 NAS 在同一局域网、地址端口正确、NAS 已开机"),
    DNS_FAILED("找不到服务器地址", "检查输入的 IP / 域名，或改用局域网扫描"),
    TIMEOUT("连接超时", "NAS 繁忙或网络不稳；可稍后重试，或换用 5GHz / 有线连接"),
    AUTH_FAILED("账号或密码不正确", "飞牛影视账号与 fnOS 系统账号不同，请在设置里重新填写"),
    SIGN_FAILED("接口签名校验失败", "飞牛影视版本更新后签名常量可能变化，请在高级设置里更新密钥"),
    SERVER_ERROR("NAS 服务返回错误", "查看 NAS 上飞牛影视服务是否正常运行"),
    NO_ORIGINAL_STREAM("没有可直接播放的原始流", "已尝试切换为 NAS 解码流"),
    UNSUPPORTED_FORMAT("设备不支持该视频格式", "需要 NAS 端转码，或安装支持该编码的外部播放器"),
    TRANSCODE_UNAVAILABLE("NAS 未提供解码后的视频流", "确认 NAS 已开启硬件转码且有足够磁盘缓存空间"),
    DECODER_ERROR("解码器打开失败", "已跳过同类原始流，改用 NAS 解码流"),
    DECODER_TIMEOUT("解码器响应超时", "已自动降低画质与音频规格并重建播放会话；若仍卡住可重新检测设备能力"),
    AUDIO_DECODER_ERROR("音轨无法解码", "设备不支持该音频编码或多声道，已请求 NAS 转换为兼容音轨"),
    PLAYBACK_FAILED("播放失败", "尝试切换播放方式或重新连接"),
    NETWORK_INTERRUPTED("播放中网络中断", "检查 Wi-Fi 信号，或降低码率"),
    UNKNOWN("发生未知错误", "可在设置里重新检测设备能力后重试");

    private final String title;
    private final String hint;

    FailureReason(String title, String hint) {
        this.title = title;
        this.hint = hint;
    }

    public String title() {
        return title;
    }

    public String hint() {
        return hint;
    }

    public String friendly(String detail) {
        StringBuilder sb = new StringBuilder(title);
        if (detail != null && detail.length() > 0) {
            sb.append('\n').append(detail);
        }
        if (hint.length() > 0) {
            sb.append("\n\n建议：").append(hint);
        }
        return sb.toString();
    }

    /** 是否属于「解码/格式」类失败：这类失败应立刻升级到转码，而不是重试同一条流。 */
    public boolean isCodecFailure() {
        return this == UNSUPPORTED_FORMAT || this == DECODER_ERROR || this == NO_ORIGINAL_STREAM
                || this == DECODER_TIMEOUT || this == AUDIO_DECODER_ERROR;
    }

    /** 是否属于「网络/鉴权」类失败：这类失败重试同一条流没有意义。 */
    public boolean isFatalForPlayback() {
        return this == AUTH_FAILED || this == SIGN_FAILED || this == DNS_FAILED || this == CONNECTION_FAILED;
    }
}
