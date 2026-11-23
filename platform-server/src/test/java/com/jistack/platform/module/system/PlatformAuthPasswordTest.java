package com.jistack.platform.module.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.common.web.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改密必须当场让旧凭据失效：哈希换掉、pwd_ver 加一，而拦截器每个请求都按 pwd_ver 与 status 复核。
 * 只改哈希不复核的话，已经发出去的无状态令牌还能带着旧凭据跑完 12 小时。
 */
class PlatformAuthPasswordTest {

    private static final String OLD_PASSWORD = "admin123";
    private static final String NEW_PASSWORD = "NewPass2026";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private SysUserMapper userMapper;
    private SysUser stored;
    private AuthService service;
    private TokenService tokens;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        stored = new SysUser();
        stored.setId(1L);
        stored.setUsername("admin");
        stored.setPasswordHash(encoder.encode(OLD_PASSWORD));
        stored.setRole("ADMIN");
        stored.setStatus(1);
        stored.setPwdVer(3);
        when(userMapper.selectById(any())).thenReturn(stored);
        tokens = tokenService();
        service = new AuthService(userMapper, tokens);
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("改密：换哈希、pwd_ver 加一，新旧密码各自只对一个")
    void changePasswordRehashesAndBumpsVersion() {
        // 桩里 selectById 返回的就是被改的那个对象，改之前的哈希要先抄一份
        String originalHash = stored.getPasswordHash();

        service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        SysUser saved = captor.getValue();
        assertThat(saved.getPwdVer()).isEqualTo(4);
        assertThat(saved.getPasswordHash()).isNotEqualTo(originalHash);
        assertThat(encoder.matches(NEW_PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(encoder.matches(OLD_PASSWORD, saved.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("原密码不对：403 且一个字节都不写库")
    void changePasswordRejectsWrongOldPassword() {
        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest("guess-me", NEW_PASSWORD)))
                .isInstanceOf(BizException.class)
                .hasMessage("原密码不正确")
                .extracting("code").isEqualTo(HttpStatus.FORBIDDEN.value());

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("新密码太短或与原密码相同：400 且不写库")
    void changePasswordRejectsWeakAndReusedPassword() {
        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, "short")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("8-64 位");
        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, OLD_PASSWORD)))
                .isInstanceOf(BizException.class)
                .hasMessage("新密码不能与原密码相同");
        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, "  ")))
                .isInstanceOf(BizException.class)
                .hasMessage("原密码与新密码都不能为空");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("未登录（无请求上下文）改不了任何账号的密码")
    void changePasswordRequiresLoginContext() {
        RequestContext.clear();

        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(401);
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("令牌带 pv：签发时取 pwd_ver，校验时原样读回")
    void tokenCarriesPasswordVersion() {
        String token = tokens.issue(new RequestContext.Principal(1L, "admin", "ADMIN", 7));

        assertThat(tokens.verify(token).pwdVer()).isEqualTo(7);
        // 升级前签发的令牌没有 pv，读回 0；迁移给的默认值也是 0，不能被当成版本不符
        assertThat(tokens.verify(tokens.issue(new RequestContext.Principal(1L, "admin", "ADMIN", 0))).pwdVer())
                .isZero();
    }

    @Test
    @DisplayName("拦截器：pwd_ver 变了就当未登录，并给出「用新密码重新登录」")
    void interceptorRejectsStalePasswordVersion() throws Exception {
        AuthInterceptor interceptor = interceptor();
        String token = tokens.issue(new RequestContext.Principal(1L, "admin", "ADMIN", 3));
        stored.setPwdVer(4);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(token), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("密码已修改");
    }

    @Test
    @DisplayName("拦截器：版本一致放行；账号被禁用则当场 401")
    void interceptorAcceptsCurrentVersionAndRejectsDisabled() throws Exception {
        AuthInterceptor interceptor = interceptor();
        String token = tokens.issue(new RequestContext.Principal(1L, "admin", "ADMIN", 3));

        assertThat(interceptor.preHandle(bearer(token), new MockHttpServletResponse(), new Object())).isTrue();

        stored.setStatus(0);
        MockHttpServletResponse disabled = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(token), disabled, new Object())).isFalse();
        assertThat(disabled.getStatus()).isEqualTo(401);
        assertThat(disabled.getContentAsString()).contains("已被禁用");
    }

    @Test
    @DisplayName("拦截器：库里 pwd_ver 为 NULL（老数据）时按 0 处理，不误伤")
    void interceptorTreatsNullVersionAsZero() throws Exception {
        stored.setPwdVer(null);
        AuthInterceptor interceptor = interceptor();
        String token = tokens.issue(new RequestContext.Principal(1L, "admin", "ADMIN", 0));

        assertThat(interceptor.preHandle(bearer(token), new MockHttpServletResponse(), new Object())).isTrue();
    }

    private AuthInterceptor interceptor() {
        return new AuthInterceptor(tokens, userMapper, new ObjectMapper());
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/authorizers");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static TokenService tokenService() {
        PlatformProperties.Security security =
                new PlatformProperties.Security("unit-test-secret", Duration.ofHours(12));
        return new TokenService(new PlatformProperties(null, security, null));
    }
}
