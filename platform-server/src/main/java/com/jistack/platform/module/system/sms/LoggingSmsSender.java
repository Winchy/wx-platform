package com.jistack.platform.module.system.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 未启用短信通道时的替身：验证码照样生成、照样进 Redis、照样一次一废，只是把明文码打到日志，
 * 让本地开发和没有阿里云凭据的环境也能走完整条登录流程。
 *
 * <p>这不是「关掉校验」——校验逻辑一行都没少，缺的只是运输。因此正式环境若忘了
 * {@code platform.sms.enabled=true}，后果是「验证码进日志、没人收得到短信」，
 * 而不是任何人凭手机号即可登录。日志级别故意留到最高，别被 INFO 噪声淹没。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "platform.sms", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        log.warn("短信通道未启用（platform.sms.enabled=false），验证码不发送，仅记录："
                + "phone={} code={} —— 正式环境请配置阿里云短信凭据", PhoneNumbers.mask(phone), code);
    }
}
