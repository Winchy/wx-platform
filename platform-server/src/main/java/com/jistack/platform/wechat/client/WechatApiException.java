package com.jistack.platform.wechat.client;

import lombok.Getter;

@Getter
public class WechatApiException extends RuntimeException {

    private final int errcode;
    private final String errmsg;
    private final String apiPath;

    public WechatApiException(int errcode, String errmsg, String apiPath) {
        super("微信接口错误 [" + errcode + "] " + errmsg + " (" + apiPath + ")");
        this.errcode = errcode;
        this.errmsg = errmsg;
        this.apiPath = apiPath;
    }

    /** 令牌类错误：需要强制刷新后重试。 */
    public boolean isTokenInvalid() {
        return WechatApiClient.RETRYABLE_TOKEN_ERRORS.contains(errcode);
    }
}
