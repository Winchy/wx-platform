package com.jistack.platform.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(String masterKey, Security security, Wechat wechat) {

    public record Security(String jwtSecret, Duration accessTokenTtl) {
    }

    public record Wechat(String apiBase, String siteBase, Duration connectTimeout, Duration readTimeout,
                         Duration refreshAhead) {
    }
}
