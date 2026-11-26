package com.juying.medianest.core.model;

/** 统一异常：把 HTTP 状态、飞牛业务 code、以及用户可读原因打包在一起。 */
public final class FnException extends Exception {

    private static final long serialVersionUID = 1L;

    public final FailureReason reason;
    public final int httpCode;
    public final int apiCode;
    public final String detail;

    public FnException(FailureReason reason, String detail) {
        this(reason, detail, 0, 0, null);
    }

    public FnException(FailureReason reason, String detail, int httpCode, int apiCode) {
        this(reason, detail, httpCode, apiCode, null);
    }

    public FnException(FailureReason reason, String detail, int httpCode, int apiCode, Throwable cause) {
        super((reason == null ? FailureReason.UNKNOWN.title() : reason.title()) +
                (detail == null ? "" : " : " + detail), cause);
        this.reason = (reason == null) ? FailureReason.UNKNOWN : reason;
        this.detail = (detail == null) ? "" : detail;
        this.httpCode = httpCode;
        this.apiCode = apiCode;
    }

    public String userMessage() {
        return reason.friendly(detail);
    }

    /** 把飞牛影视的业务 code 映射为内部失败原因。 */
    public static FnException fromApiCode(int code, String msg, String path, int httpCode) {
        String text = (msg == null ? "" : msg) + " [" + path + "]";
        FailureReason r;
        switch (code) {
            case 0:
                r = FailureReason.NONE;
                break;
            case -2:
                r = FailureReason.AUTH_FAILED;
                break;
            case -6:
                r = FailureReason.SERVER_ERROR;
                break;
            case -15:
                r = FailureReason.AUTH_FAILED;
                break;
            case 5000:
                r = FailureReason.SIGN_FAILED;
                break;
            default:
                r = FailureReason.SERVER_ERROR;
        }
        return new FnException(r, text, httpCode, code);
    }

    public static FnException fromHttp(int httpCode, String path) {
        FailureReason r;
        if (httpCode == 401 || httpCode == 403) {
            r = FailureReason.AUTH_FAILED;
        } else if (httpCode == 404) {
            r = FailureReason.SERVER_ERROR;
        } else if (httpCode == 501 || httpCode >= 500) {
            r = FailureReason.SERVER_ERROR;
        } else if (httpCode == 415 || httpCode == 406) {
            r = FailureReason.UNSUPPORTED_FORMAT;
        } else {
            r = FailureReason.CONNECTION_FAILED;
        }
        return new FnException(r, "HTTP " + httpCode + " @ " + path, httpCode, 0);
    }
}
