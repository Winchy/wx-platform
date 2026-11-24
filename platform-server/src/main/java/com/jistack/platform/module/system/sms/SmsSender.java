package com.jistack.platform.module.system.sms;

/**
 * 短信发送通道。验证码的生成本身不在这里（见 SmsCodeService），这个接口只负责「把一条
 * 六位数字送到手机上」，因此测试与本地开发都能用替身顶掉它，而不必改动验证码策略。
 */
public interface SmsSender {

    /**
     * @param phone 已校验过格式的手机号
     * @param code  明文验证码，只在调用瞬间存在，不落库
     * @throws SmsSendException 运营商/阿里云侧失败，或网络不通；调用方需要回滚配额让用户能重试
     */
    void send(String phone, String code);
}
