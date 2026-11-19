package com.jistack.platform.wechat.client;

import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.module.system.PlatformConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Slf4j
@Component
public class WechatClientFactory {

    private final PlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public WechatClientFactory(PlatformProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration connect = properties.wechat() == null || properties.wechat().connectTimeout() == null
                ? Duration.ofSeconds(5) : properties.wechat().connectTimeout();
        Duration read = properties.wechat() == null || properties.wechat().readTimeout() == null
                ? Duration.ofSeconds(30) : properties.wechat().readTimeout();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 按平台配置里的 apiBase 建客户端，便于测试环境整体指向桩服务。 */
    public WechatApiClient forConfig(PlatformConfig config) {
        String base = config != null && StringUtils.hasText(config.getApiBase())
                ? config.getApiBase()
                : (properties.wechat() == null ? "https://api.weixin.qq.com" : properties.wechat().apiBase());
        return new WechatApiClient(base, restTemplate, objectMapper);
    }

    public RestTemplate restTemplate() {
        return restTemplate;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
