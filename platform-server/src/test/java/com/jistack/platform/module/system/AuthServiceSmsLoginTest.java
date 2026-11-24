package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.SmsCodeService.Purpose;
import com.jistack.platform.module.system.sms.SmsSendException;
import com.jistack.platform.module.system.sms.SmsSender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手机号验证码登录的安全边界。
 *
 * <p>密码登录可以慢慢猜，验证码登录不能：它是一条不需要密码的入口，所以这里守的不是「登录是否方便」，
 * 而是「这条入口会不会反过来泄露账号是否存在、能不能被拿来加绑定、发不出去时会不会自己咬人」。
 *
 * <p>只测策略与调用顺序，不碰 Redis 与 HTTP：签发/限流的细节由 {@code SmsCodeServiceTest} 守，
 * 阿里云报文由 {@code AliyunSmsSenderContractTest} 守。
 */
class AuthServiceSmsLoginTest {

    private static final String PHONE = "13800138000";
    private static final String MASKED = "138****8000";
    private static final String IP = "203.0.113.7";
    private static final String CODE = "246813";
    private static final String PASSWORD = "admin123";

    static {
        // LambdaUpdateWrapper 靠实体列缓存把 SysUser::getPhone 翻成 phone，那份缓存平时由容器扫描实体时建好；
        // 纯单测没有容器，不先初始化就会在解绑那一步当场抛 can not find lambda cache
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private SysUserMapper userMapper;
    private SmsCodeService smsCode;
    private SmsSender sender;
    private TokenService tokens;
    private AuthService service;
    private SysUser admin;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        smsCode = mock(SmsCodeService.class);
        sender = mock(SmsSender.class);
        tokens = tokenService();
        service = new AuthService(userMapper, tokens, smsCode, sender);

        admin = new SysUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setNickname("平台管理员");
        admin.setPasswordHash(encoder.encode(PASSWORD));
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setPwdVer(3);
        admin.setPhone(PHONE);

        when(userMapper.selectById(1L)).thenReturn(admin);
        when(userMapper.selectByPhone(PHONE)).thenReturn(admin);
        when(smsCode.issue(any(), any(), any())).thenReturn(CODE);
        when(smsCode.resendCooldown()).thenReturn(Duration.ofSeconds(60));
        when(smsCode.codeTtl()).thenReturn(Duration.ofSeconds(300));
        RequestContext.clear();
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("验证码登录：与密码登录同一个会话出口，令牌照带 pwd_ver，返回脱敏手机号")
    void smsLoginIssuesTheSameSessionAsPasswordLogin() {
        when(smsCode.verify(PHONE, Purpose.LOGIN, CODE)).thenReturn(true);

        LoginResponse response = service.loginBySms(new SmsLoginRequest(PHONE, CODE));

        assertThat(tokens.verify(response.token()).pwdVer()).isEqualTo(3);
        assertThat(tokens.verify(response.token()).username()).isEqualTo("admin");
        assertThat(response.expiresIn()).isEqualTo(tokens.ttlSeconds());
        assertThat(response.user().phoneMasked()).isEqualTo(MASKED);
        assertThat(response.user().id()).isEqualTo(1L);
        assertThat(admin.getLastLoginAt()).isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(1));
        verify(userMapper).updateById(admin);
    }

    @Test
    @DisplayName("验证码错与号码未绑定：同一句话同一个 401，且码错时根本不查库")
    void wrongCodeAndUnboundPhoneAreIndistinguishable() {
        when(smsCode.verify(PHONE, Purpose.LOGIN, "000000")).thenReturn(false);
        BizException wrongCode = catchBiz(() -> service.loginBySms(new SmsLoginRequest(PHONE, "000000")));
        // 号码没绑过账号：码是真的，但没人可登
        when(smsCode.verify("13700137000", Purpose.LOGIN, CODE)).thenReturn(true);
        when(userMapper.selectByPhone("13700137000")).thenReturn(null);
        BizException unbound = catchBiz(() -> service.loginBySms(new SmsLoginRequest("13700137000", CODE)));

        assertThat(wrongCode.getMessage()).isEqualTo(unbound.getMessage()).isEqualTo("手机号或验证码不正确");
        assertThat(wrongCode.getCode()).isEqualTo(unbound.getCode()).isEqualTo(401);
        // 两条失败路都不写库，否则登录记录会告诉探测者哪种组合算「登进来过」
        verify(userMapper, never()).updateById(any(SysUser.class));
        // 码没通过就不查库，否则「先试个码」会变成在册号码查询器
        verify(userMapper, never()).selectByPhone(PHONE);
    }

    @Test
    @DisplayName("手机号或验证码缺失/格式不对：400 且不消耗验证码、不查库")
    void blankOrMalformedInputStopsBeforeTheCodeIsTouched() {
        assertThat(catchBiz(() -> service.loginBySms(new SmsLoginRequest(null, CODE))).getCode()).isEqualTo(400);
        assertThat(catchBiz(() -> service.loginBySms(new SmsLoginRequest(PHONE, "  "))).getCode()).isEqualTo(400);
        BizException malformed = catchBiz(() -> service.loginBySms(new SmsLoginRequest("12345", CODE)));
        assertThat(malformed).hasMessageContaining("手机号格式不正确");

        verify(smsCode, never()).verify(any(), any(), any());
        verify(userMapper, never()).selectByPhone(any());
    }

    @Test
    @DisplayName("+86、空格、横线都归一化后再进 Redis 键与查询：同一个号码不会存成两串")
    void internationalPrefixAndSeparatorsNormalizeBeforeLookup() {
        when(smsCode.verify(PHONE, Purpose.LOGIN, CODE)).thenReturn(true);

        service.loginBySms(new SmsLoginRequest("+86 138-0013-8000", CODE));

        verify(smsCode).verify(PHONE, Purpose.LOGIN, CODE);
        verify(userMapper).selectByPhone(PHONE);
    }

    @Test
    @DisplayName("验证码登录成功后，改密码同样把这条入口签发的会话踢掉")
    void smsIssuedTokenIsRevokedByPasswordChange() throws Exception {
        when(smsCode.verify(PHONE, Purpose.LOGIN, CODE)).thenReturn(true);
        String token = service.loginBySms(new SmsLoginRequest(PHONE, CODE)).token();
        AuthInterceptor interceptor = new AuthInterceptor(tokens, userMapper, new ObjectMapper());

        assertThat(interceptor.preHandle(bearer(token), new MockHttpServletResponse(), new Object())).isTrue();

        admin.setPwdVer(4);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(token), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("密码已修改");
    }

    @Test
    @DisplayName("账号被禁用：验证码为真也进不去，403 且不发令牌")
    void disabledAccountIsRejectedEvenWithACorrectCode() {
        admin.setStatus(0);
        when(smsCode.verify(PHONE, Purpose.LOGIN, CODE)).thenReturn(true);

        BizException ex = catchBiz(() -> service.loginBySms(new SmsLoginRequest(PHONE, CODE)));

        assertThat(ex.getCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(ex).hasMessageContaining("账号已禁用");
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("登录页取码：已绑定号码才真发短信，未绑定的走完全一样的流程并给出同样的响应")
    void loginCodeResponseIsIdenticalWhetherOrNotThePhoneIsBound() {
        SmsCodeResult bound = service.issueLoginCode(PHONE, IP);
        verify(sender).send(PHONE, CODE);
        verify(smsCode).issue(PHONE, Purpose.LOGIN, IP);

        when(userMapper.selectByPhone("13700137000")).thenReturn(null);
        SmsCodeResult unbound = service.issueLoginCode("13700137000", IP);

        // 响应一模一样，探测者拿不到「这个号在不在册」
        assertThat(unbound).isEqualTo(bound);
        assertThat(unbound.cooldownSeconds()).isEqualTo(60);
        assertThat(unbound.expiresIn()).isEqualTo(300);
        // 未绑定也要走完签发：跳过它反而等于回答「这号没绑」，而且冷却会失效让人无限重试
        verify(smsCode).issue("13700137000", Purpose.LOGIN, IP);
        verify(sender, never()).send(eq("13700137000"), anyString());
    }

    @Test
    @DisplayName("短信发不出去：作废这个码、退回冷却与配额，界面只看到一句不含阿里云原因的话")
    void sendFailureRollsBackQuotaAndHidesProviderReason() {
        doThrow(new SmsSendException("isv.AMOUNT_NOT_ENOUGH", "余额不足，AccessKeyId=LTAI5tFAKEKEY"))
                .when(sender).send(PHONE, CODE);

        BizException ex = catchBiz(() -> service.issueLoginCode(PHONE, IP));

        assertThat(ex.getCode()).isEqualTo(502);
        assertThat(ex.getMessage()).isEqualTo("验证码发送失败，请稍后重试");
        assertThat(ex.getMessage()).doesNotContain("isv", "余额", "LTAI");
        // 不回滚的话，通道坏了点几次就撞上「今日上限」，真实原因被自己的限流盖住
        verify(smsCode).discard(PHONE, Purpose.LOGIN, IP);
    }

    @Test
    @DisplayName("绑定用验证码必须登录后再取：未登录连签发都进不去")
    void bindCodeRequiresLogin() {
        assertThat(catchBiz(() -> service.issueBindCode(PHONE, IP)).getCode()).isEqualTo(401);
        assertThat(catchBiz(() -> service.bindPhone(new BindPhoneRequest(PHONE, CODE, PASSWORD))).getCode())
                .isEqualTo(401);

        verify(smsCode, never()).issue(any(), any(), any());
        verify(sender, never()).send(any(), any());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("绑定用验证码一定真发（号码就是当前登录人要的那个）")
    void bindCodeAlwaysSendsAndUsesBindPurpose() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));

        service.issueBindCode("+86 138 0013 8000", IP);

        verify(smsCode).issue(PHONE, Purpose.BIND, IP);
        verify(sender).send(PHONE, CODE);
    }

    @Test
    @DisplayName("绑定时密码不对：403，而且这个验证码一次都没被消耗")
    void wrongPasswordDoesNotBurnTheBindCode() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));

        BizException ex = catchBiz(() -> service.bindPhone(new BindPhoneRequest("13900001111", CODE, "guess")));

        assertThat(ex.getCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(ex).hasMessageContaining("密码不正确");
        verify(smsCode, never()).verify(any(), any(), any());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("绑定：验证码不对就 400 不写库；对了才写归一化后的号码")
    void bindPhoneWritesOnlyAfterCodePasses() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));
        String other = "13900001111";
        when(smsCode.verify(other, Purpose.BIND, "000000")).thenReturn(false);
        assertThat(catchBiz(() -> service.bindPhone(new BindPhoneRequest(other, "000000", PASSWORD))))
                .hasMessageContaining("验证码不正确或已过期");
        verify(userMapper, never()).updateById(any(SysUser.class));

        when(smsCode.verify(other, Purpose.BIND, CODE)).thenReturn(true);
        when(userMapper.selectByPhone(other)).thenReturn(null);
        service.bindPhone(new BindPhoneRequest("+86" + other, CODE, PASSWORD));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo(other);
    }

    @Test
    @DisplayName("绑定：空验证码 400，号码格式不对 400，都不碰密码与库")
    void bindPhoneRejectsMissingCodeAndBadPhone() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));

        assertThat(catchBiz(() -> service.bindPhone(new BindPhoneRequest(PHONE, " ", PASSWORD))))
                .hasMessageContaining("请填写短信验证码");
        assertThat(catchBiz(() -> service.bindPhone(new BindPhoneRequest("1380013", CODE, PASSWORD))))
                .hasMessageContaining("手机号格式不正确");

        verify(smsCode, never()).verify(any(), any(), any());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("绑定：号码已被别的账号占用 → 409；并发抢同一号码被唯一索引挡住也是 409，不把 SQL 抛给界面")
    void bindPhoneConflictBecomes409() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));
        String taken = "13900002222";
        SysUser other = new SysUser();
        other.setId(2L);
        other.setUsername("ops");
        other.setPhone(taken);
        when(smsCode.verify(taken, Purpose.BIND, CODE)).thenReturn(true);
        when(userMapper.selectByPhone(taken)).thenReturn(other);

        BizException busy = catchBiz(() -> service.bindPhone(new BindPhoneRequest(taken, CODE, PASSWORD)));
        assertThat(busy.getCode()).isEqualTo(409);
        assertThat(busy).hasMessageContaining("该手机号已绑定其他账号");
        verify(userMapper, never()).updateById(any(SysUser.class));

        // 前一刻查着没人，写进去才发现撞了唯一索引
        String raced = "13900003333";
        when(smsCode.verify(raced, Purpose.BIND, CODE)).thenReturn(true);
        when(userMapper.selectByPhone(raced)).thenReturn(null);
        doThrow(new DuplicateKeyException("Duplicate entry for key 'uk_sys_user_phone'"))
                .when(userMapper).updateById(any(SysUser.class));
        BizException race = catchBiz(() -> service.bindPhone(new BindPhoneRequest(raced, CODE, PASSWORD)));
        assertThat(race.getCode()).isEqualTo(409);
        assertThat(race.getMessage()).isEqualTo(busy.getMessage());
    }

    @Test
    @DisplayName("重新绑定自己的号码：查库拿到的就是自己，不该被自己的占用挡住")
    void rebindingYourOwnNumberIsAllowed() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));
        when(smsCode.verify(PHONE, Purpose.BIND, CODE)).thenReturn(true);

        service.bindPhone(new BindPhoneRequest(PHONE, CODE, PASSWORD));

        verify(userMapper).updateById(admin);
        assertThat(admin.getPhone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("解绑必须带密码，并且显式 SET phone = NULL（updateById 会跳过 null 字段，清不掉）")
    @SuppressWarnings("unchecked")
    void unbindPhoneClearsColumnWithExplicitSet() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));

        service.unbindPhone(new UnbindPhoneRequest(PASSWORD));

        ArgumentCaptor<LambdaUpdateWrapper<SysUser>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<SysUser> wrapper = captor.getValue();
        String sqlSet = wrapper.getSqlSet();
        assertThat(sqlSet).as("SET 子句只该写 phone 一列，别把密码之类的列一起写掉")
                .matches("phone=#\\{ew\\.paramNameValuePairs\\.MPGENVAL\\d+}");
        assertThat(sqlSet).doesNotContain("password").doesNotContain("pwd_ver");
        // null 也被当成参数绑上去，所以顺着占位符回查：清列靠的就是这个绑值为 null 的 SET
        String placeholder = sqlSet.substring(sqlSet.lastIndexOf('.') + 1, sqlSet.length() - 1);
        assertThat(wrapper.getParamNameValuePairs()).containsEntry(placeholder, null);
        assertThat(wrapper.getTargetSql()).contains("id =");
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("解绑：密码不对 403 且不写库；本来就没绑定则什么都不写")
    void unbindRequiresPasswordAndSkipsEmptyBinding() {
        RequestContext.set(new RequestContext.Principal(1L, "admin", "ADMIN", 3));
        assertThat(catchBiz(() -> service.unbindPhone(new UnbindPhoneRequest("guess"))).getCode())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(userMapper, never()).update(any(), any());

        admin.setPhone(null);
        service.unbindPhone(new UnbindPhoneRequest(PASSWORD));
        verify(userMapper, never()).update(any(), any());
    }

    private BizException catchBiz(Runnable call) {
        try {
            call.run();
        } catch (BizException ex) {
            return ex;
        }
        throw new AssertionError("预期抛出 BizException，但没有：" + call);
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static TokenService tokenService() {
        PlatformProperties.Security security =
                new PlatformProperties.Security("unit-test-secret", Duration.ofHours(12));
        return new TokenService(new PlatformProperties(null, security, null));
    }
}
