package com.jistack.platform.wechat.client;

import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.module.system.PlatformConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信接口域名的两级优先级：平台配置里的 api_base 是覆盖项，留空才回落到 WECHAT_API_BASE。
 * apiBase 在 WechatApiClient 里是私有字段，只能靠桩服务收到的请求来证明真正打到了哪个域名。
 */
class WechatClientFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockWebServer envStub = new MockWebServer();
    private final MockWebServer overrideStub = new MockWebServer();

    WechatClientFactoryTest() throws Exception {
        envStub.start();
        overrideStub.start();
    }

    @AfterEach
    void shutdown() throws Exception {
        envStub.shutdown();
        overrideStub.shutdown();
    }

    private WechatClientFactory factoryPointingAtEnvBase() {
        PlatformProperties.Wechat wechat = new PlatformProperties.Wechat(
                envStub.url("/").toString(), "https://platform.example.com",
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(5));
        return new WechatClientFactory(new PlatformProperties("key", null, wechat), objectMapper);
    }

    private PlatformConfig configWithApiBase(String apiBase) {
        PlatformConfig config = new PlatformConfig();
        config.setId(PlatformConfig.SINGLETON_ID);
        config.setApiBase(apiBase);
        return config;
    }

    private RecordedRequest invoke(WechatClientFactory factory, PlatformConfig config) {
        envStub.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("{\"errcode\":0}"));
        overrideStub.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("{\"errcode\":0}"));
        factory.forConfig(config).get("/cgi-bin/component/api_get_temporary_qrcode", "TOKEN");
        try {
            RecordedRequest request = envStub.takeRequest(1, TimeUnit.SECONDS);
            return request != null ? request : overrideStub.takeRequest(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("平台配置未填 api_base 时，请求打到 WECHAT_API_BASE 指定的地址")
    void blankApiBaseFallsBackToConfiguredBase() {
        RecordedRequest request = invoke(factoryPointingAtEnvBase(), configWithApiBase(null));

        assertThat(request).isNotNull();
        assertThat(request.getRequestUrl().toString()).startsWith(envStub.url("/").toString());
    }

    @Test
    @DisplayName("平台配置填了 api_base 时，覆盖 WECHAT_API_BASE")
    void storedApiBaseWins() {
        RecordedRequest request = invoke(factoryPointingAtEnvBase(),
                configWithApiBase(overrideStub.url("/").toString()));

        assertThat(request).isNotNull();
        assertThat(request.getRequestUrl().toString()).startsWith(overrideStub.url("/").toString());
    }
}
