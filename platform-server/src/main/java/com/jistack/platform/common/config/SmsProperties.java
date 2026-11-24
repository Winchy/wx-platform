package com.jistack.platform.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 短信通道配置。独立于 {@link PlatformProperties}：那棵树的构造点散落在令牌、加密、微信客户端
 * 与十几处测试里，加一个分量就要全部跟着改，而短信只有一处用。
 *
 * <p>凭据只从环境变量进来，不落库——库里有主密钥备份就能解出全部微信凭据，
 * 再放一份阿里云 AccessKey 等于把「发短信花钱」的开关也一起交出去。
 */
@ConfigurationProperties(prefix = "platform.sms")
public record SmsProperties(Boolean enabled, String accessKeyId, String accessKeySecret, String endpoint,
                            String regionId, String signName, String templateCode, String templateParamKey,
                            Duration connectTimeout, Duration readTimeout, Code code) {

    /** 验证码策略，与传输方式无关，所以单列一层。 */
    public record Code(Duration ttl, Duration resendCooldown, Integer maxVerifyAttempts,
                       Integer dailyLimitPerPhone, Integer hourlyLimitPerIp) {
    }
}
