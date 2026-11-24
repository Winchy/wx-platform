package com.jistack.platform.module.system.sms;

/**
 * 发送失败的统一表述。{@code providerCode} 是阿里云返回的 Code 原文（OK 之外的那些，
 * 例如 isv.SMS_SIGNATURE_ILLEGAL、isv.BUSINESS_LIMIT_CONTROL、InvalidAccessKeyId.NotFound），
 * 上线排障全靠它区分「配置错了」和「被流控了」，但绝不能透给界面——界面只看 message。
 */
public class SmsSendException extends RuntimeException {

    private final String providerCode;

    public SmsSendException(String providerCode, String message) {
        super(message);
        this.providerCode = providerCode;
    }

    public SmsSendException(String providerCode, String message, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
    }

    public String getProviderCode() {
        return providerCode;
    }
}
