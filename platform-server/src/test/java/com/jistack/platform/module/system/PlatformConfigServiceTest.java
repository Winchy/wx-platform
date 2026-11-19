package com.jistack.platform.module.system;

import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.common.crypto.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * api_base / site_base 是「可清空的覆盖项」，代码不能在任何路径上把它们补成硬编码默认值，
 * 否则 application.yml 里的 WECHAT_API_BASE / PLATFORM_SITE_BASE 永远不会生效。
 */
class PlatformConfigServiceTest {

    private PlatformConfigMapper configMapper;
    private PlatformConfig stored;
    private PlatformConfigService service;

    @BeforeEach
    void setUp() {
        configMapper = mock(PlatformConfigMapper.class);
        stored = new PlatformConfig();
        stored.setId(PlatformConfig.SINGLETON_ID);
        stored.setComponentAppid("wxcomponent0000001");
        stored.setStatus(1);
        when(configMapper.selectById(any())).thenReturn(stored);
        SecretCipher cipher = new SecretCipher(
                new PlatformProperties(Base64.getEncoder().encodeToString(new byte[32]), null, null));
        service = new PlatformConfigService(configMapper, mock(ComponentTicketMapper.class), cipher);
    }

    @Test
    @DisplayName("load() 不能把空 api_base 补成默认域名，否则环境变量开关失效")
    void loadKeepsApiBaseBlank() {
        stored.setApiBase(null);

        assertThat(service.load().getApiBase()).isNull();
    }

    @Test
    @DisplayName("首次自动建单行配置时也不写 api_base")
    void insertDoesNotStampApiBase() {
        when(configMapper.selectById(any())).thenReturn(null);

        service.load();

        ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
        verify(configMapper).insert(captor.capture());
        assertThat(captor.getValue().getApiBase()).isNull();
    }

    @Test
    @DisplayName("PUT 传空串能把覆盖清成 NULL，交还给 WECHAT_API_BASE")
    void emptyStringClearsOverride() {
        stored.setApiBase("http://127.0.0.1:9999");
        stored.setSiteBase("https://old.example.com");
        PlatformConfig incoming = new PlatformConfig();
        incoming.setApiBase("");
        incoming.setSiteBase("   ");

        service.update(incoming);

        ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
        verify(configMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiBase()).isNull();
        assertThat(captor.getValue().getSiteBase()).isNull();
    }

    @Test
    @DisplayName("PUT 不带 apiBase 字段时保持原值，增量更新语义不变")
    void missingFieldKeepsOverride() {
        stored.setApiBase("http://127.0.0.1:9999");

        service.update(new PlatformConfig());

        ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
        verify(configMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiBase()).isEqualTo("http://127.0.0.1:9999");
    }
}
