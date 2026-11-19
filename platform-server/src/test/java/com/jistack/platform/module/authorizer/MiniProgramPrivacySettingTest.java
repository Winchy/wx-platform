package com.jistack.platform.module.authorizer;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.service.AuthorizerWriter;
import com.jistack.platform.wechat.service.WechatCaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隐私保护指引的入参校验：微信的两条硬规则（owner_setting 必填、现网版本不接受 setting_list）
 * 在本地先拦住，避免把注定失败的请求发给微信。
 */
@DisplayName("用户隐私保护指引：请求校验与转发")
class MiniProgramPrivacySettingTest {

    private final ObjectMapper json = new ObjectMapper();

    private WechatCaller caller;
    private MiniProgramSettingService service;

    @BeforeEach
    void setUp() {
        caller = mock(WechatCaller.class);
        service = new MiniProgramSettingService(caller, mock(AuthorizerWriter.class));
    }

    private JsonNode okNode() {
        return json.createObjectNode().put("errcode", 0);
    }

    @Test
    @DisplayName("缺 owner_setting 时不发请求")
    void ownerSettingRequired() {
        assertThatThrownBy(() -> service.updatePrivacySetting("wx1", 2,
                List.of(Map.of("privacy_key", "Location", "privacy_text", "用于就近推荐活动场地")), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("owner_setting");

        assertThatThrownBy(() -> service.updatePrivacySetting("wx1", 2, List.of(), Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("notice_method");

        verify(caller, never()).authorizer(eq("wx1"), eq("setprivacysetting"), any());
    }

    @Test
    @DisplayName("现网版本（privacy_ver=1）不接受 setting_list")
    void productionVersionRejectsSettingList() {
        assertThatThrownBy(() -> service.updatePrivacySetting("wx1", 1,
                List.of(Map.of("privacy_key", "Location")), Map.of("notice_method", "弹窗提示")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("现网版本");

        verify(caller, never()).authorizer(eq("wx1"), eq("setprivacysetting"), any());
    }

    @Test
    @DisplayName("开发版保存走 setprivacysetting")
    void savesDevelopmentVersion() {
        JsonNode response = okNode();
        when(caller.authorizer(eq("wx1"), eq("setprivacysetting"), any())).thenReturn(response);

        assertThat(service.updatePrivacySetting("wx1", 2,
                List.of(Map.of("privacy_key", "Location", "privacy_text", "用于就近推荐活动场地")),
                Map.of("notice_method", "弹窗提示")).path("errcode").asInt()).isZero();
    }

    @Test
    @DisplayName("开发版必须带 setting_list，只改收集方信息请选现网版本")
    void developmentVersionRequiresSettingList() {
        assertThatThrownBy(() -> service.updatePrivacySetting("wx1", 2, List.of(),
                Map.of("notice_method", "弹窗提示")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("setting_list");

        assertThatThrownBy(() -> service.updatePrivacySetting("wx1", null, null,
                Map.of("notice_method", "弹窗提示")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("现网版本");

        verify(caller, never()).authorizer(eq("wx1"), eq("setprivacysetting"), any());
    }

    @Test
    @DisplayName("查询走 getprivacysetting，privacy_ver 缺省按开发版")
    void queriesWithoutVersion() {
        when(caller.authorizer(eq("wx1"), eq("getprivacysetting"), any())).thenReturn(okNode());

        service.privacySetting("wx1", null);

        verify(caller).authorizer(eq("wx1"), eq("getprivacysetting"), any());
    }
}
