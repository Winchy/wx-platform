package com.jistack.platform.module.system;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.common.web.GlobalExceptionHandler;
import com.jistack.platform.common.web.PageResult;
import com.jistack.platform.module.authorizer.AuthorizerController;
import com.jistack.platform.module.authorizer.AuthorizerService;
import com.jistack.platform.module.authorizer.MiniProgramSettingController;
import com.jistack.platform.module.authorizer.MiniProgramSettingService;
import com.jistack.platform.module.event.WechatCallbackController;
import com.jistack.platform.module.release.AppRelease;
import com.jistack.platform.module.release.CommitRequest;
import com.jistack.platform.module.release.ReleaseController;
import com.jistack.platform.module.release.ReleaseService;
import com.jistack.platform.wechat.client.WechatApiException;
import com.jistack.platform.wechat.service.WeChatCallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只测 Web 层契约：请求体绑定（含 transient 凭据字段）、响应信封、错误码映射。
 * 用 standalone MockMvc，避免依赖 MySQL/Redis。
 */
class PlatformWebContractTest {

    private PlatformConfigService configService;
    private AuthorizerService authorizerService;
    private ReleaseService releaseService;
    private MiniProgramSettingService settingService;
    private WeChatCallbackService callbackService;
    private AuthService authService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        configService = mock(PlatformConfigService.class);
        authorizerService = mock(AuthorizerService.class);
        releaseService = mock(ReleaseService.class);
        settingService = mock(MiniProgramSettingService.class);
        callbackService = mock(WeChatCallbackService.class);
        authService = mock(AuthService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new PlatformConfigController(configService, authorizerService),
                        new AuthorizerController(authorizerService),
                        new AuthController(authService),
                        new ReleaseController(releaseService),
                        new MiniProgramSettingController(settingService),
                        new WechatCallbackController(callbackService,
                                mock(com.jistack.platform.wechat.service.AuthorizationService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("PUT /api/platform/config：明文密钥能绑定进来，响应只回掩码状态")
    void updatePlatformConfig() throws Exception {
        when(configService.toVO()).thenReturn(Map.of("componentAppid", "wx1", "secretSet", true,
                "callbackUrl", "https://p.example.com/wx/callback"));

        mvc.perform(put("/api/platform/config").contentType(MediaType.APPLICATION_JSON).content("""
                {"componentAppid":"wx1","componentSecret":"plain-secret",
                 "messageToken":"tok","encodingAesKey":"0123456789012345678901234567890123456789012"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.secretSet").value(true))
                .andExpect(jsonPath("$.data.callbackUrl").value("https://p.example.com/wx/callback"))
                .andExpect(jsonPath("$.data.componentSecret").doesNotExist());

        ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
        verify(configService).update(captor.capture());
        assertThat(captor.getValue().getComponentSecret()).isEqualTo("plain-secret");
        assertThat(captor.getValue().getEncodingAesKey()).hasSize(43);
    }

    @Test
    @DisplayName("PUT /api/auth/password：两个字段能绑进请求体，原密码不符回 403")
    void changePasswordBindingAndStatus() throws Exception {
        mvc.perform(put("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content("""
                {"oldPassword":"admin123","newPassword":"NewPass2026"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<ChangePasswordRequest> captor = ArgumentCaptor.forClass(ChangePasswordRequest.class);
        verify(authService).changePassword(captor.capture());
        assertThat(captor.getValue().oldPassword()).isEqualTo("admin123");
        assertThat(captor.getValue().newPassword()).isEqualTo("NewPass2026");

        doThrow(BizException.forbidden("原密码不正确")).when(authService).changePassword(any());
        mvc.perform(put("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content("""
                {"oldPassword":"wrong","newPassword":"NewPass2026"}
                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("原密码不正确"));
    }

    @Test
    @DisplayName("微信把 URL 里的 $APPID$ 换成实际 appid，/wx/callback/{appid} 不能 404")
    void callbackAcceptsAppidPathSuffix() throws Exception {
        when(callbackService.handlePush(any(), any(), any(), any())).thenReturn("success");
        when(callbackService.verifyUrl(any(), any(), any(), any())).thenReturn("ECHO_1");

        mvc.perform(post("/wx/callback/wx9a1b2c3d4e5f6071")
                        .param("msg_signature", "sig").param("timestamp", "1763000000").param("nonce", "n")
                        .contentType(MediaType.TEXT_PLAIN).content("<xml><Encrypt>x</Encrypt></xml>"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        verify(callbackService).handlePush("sig", "1763000000", "n", "<xml><Encrypt>x</Encrypt></xml>");

        mvc.perform(get("/wx/callback/wx9a1b2c3d4e5f6071")
                        .param("signature", "sig").param("timestamp", "1763000000")
                        .param("nonce", "n").param("echostr", "e"))
                .andExpect(status().isOk())
                .andExpect(content().string("ECHO_1"));
    }

    @Test
    @DisplayName("URL 校验的签名放在 msg_signature 时也要认（安全模式）")
    void callbackVerifyAcceptsMsgSignature() throws Exception {
        // 只读 signature 时安全模式的校验永远拿到 null，后台「消息与事件接收 URL」保存不了
        when(callbackService.verifyUrl(any(), any(), any(), any())).thenReturn("ECHO_2");

        mvc.perform(get("/wx/callback/wx9a1b2c3d4e5f6071")
                        .param("msg_signature", "SEC_SIG").param("timestamp", "1763000000")
                        .param("nonce", "n").param("echostr", "ENC"))
                .andExpect(status().isOk())
                .andExpect(content().string("ECHO_2"));

        verify(callbackService).verifyUrl("SEC_SIG", "1763000000", "n", "ENC");
    }

    @Test
    @DisplayName("不带参数探测回调地址不再抛 500：容器健康检查改打 /wx/health")
    void callbackProbeWithoutParams() throws Exception {
        // 之前 timestamp/nonce 是必填 @RequestParam，浏览器直开回调地址或 HEALTHCHECK 探测
        // 每 30 秒就在日志里留一整段栈，把真正要查的错误淹掉。
        mvc.perform(get("/wx/callback"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("missing timestamp/nonce")));
        verify(callbackService, never()).verifyUrl(any(), any(), any(), any());

        mvc.perform(post("/wx/callback").contentType(MediaType.TEXT_PLAIN).content("<xml><Encrypt>x</Encrypt></xml>"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("missing timestamp/nonce")));
        verify(callbackService, never()).handlePush(any(), any(), any(), any());

        mvc.perform(get("/wx/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("ticket 未到时微信侧异常按 errcode 返回，HTTP 仍是 200，前端按 code 提示")
    void wechatErrorSurfacesAsErrcode() throws Exception {
        when(authorizerService.restartTicketPush())
                .thenThrow(new WechatApiException(61023, "refresh_token is invalid", "/cgi-bin/component/x"));

        mvc.perform(post("/api/platform/ticket-push/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(61023))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("61023")));
    }

    @Test
    @DisplayName("未接入小程序：BizException(404) → HTTP 404 + code 404")
    void notFoundMapping() throws Exception {
        when(authorizerService.detail("wxmissing")).thenThrow(BizException.notFound("小程序 wxmissing 尚未接入"));

        mvc.perform(get("/api/authorizers/wxmissing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET /api/authorizers/{appid}/icp-status 返回解码后的备案状态")
    void icpStatusRoute() throws Exception {
        when(releaseService.icpStatus("wx1")).thenReturn(Map.of(
                "status", 1026,
                "statusText", "未备案",
                "filed", false,
                "missingPrerequisites", List.of("没有审核通过的小程序类目")));

        mvc.perform(get("/api/authorizers/wx1/icp-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(1026))
                .andExpect(jsonPath("$.data.filed").value(false))
                .andExpect(jsonPath("$.data.missingPrerequisites[0]").value("没有审核通过的小程序类目"));

        verify(releaseService).icpStatus("wx1");
    }

    @Test
    @DisplayName("POST /visit-status：action 能绑定，响应是改完后重读的状态")
    void visitStatusWriteRoute() throws Exception {
        when(releaseService.setVisitStatus("wx1", "close")).thenReturn(0);

        mvc.perform(post("/api/authorizers/wx1/visit-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"close\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(0));

        verify(releaseService).setVisitStatus("wx1", "close");
    }

    @Test
    @DisplayName("GET /audit/screenshots：返回 mediaId + dataUrl 列表")
    void auditScreenshotsRoute() throws Exception {
        when(releaseService.auditScreenshots("wx1")).thenReturn(List.of(Map.of(
                "mediaId", "aaa", "contentType", "image/png", "dataUrl", "data:image/png;base64,AAAA")));

        mvc.perform(get("/api/authorizers/wx1/audit/screenshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].mediaId").value("aaa"))
                .andExpect(jsonPath("$.data[0].dataUrl").value("data:image/png;base64,AAAA"));
    }

    @Test
    @DisplayName("隐私指引路由：GET 绑定 privacyVer，PUT 的字段名与前端一致并原样透传")
    void privacySettingRoutes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        when(settingService.privacySetting("wx1", 2)).thenReturn(
                mapper.readTree("{\"errcode\":0,\"code_exist\":1,\"privacy_list\":[\"UserInfo\"]}"));
        when(settingService.updatePrivacySetting(eq("wx1"), eq(2), any(), any())).thenReturn(
                mapper.readTree("{\"errcode\":0,\"errmsg\":\"ok\"}"));

        mvc.perform(get("/api/authorizers/wx1/privacy-setting").param("privacyVer", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.code_exist").value(1))
                .andExpect(jsonPath("$.data.privacy_list[0]").value("UserInfo"));

        mvc.perform(put("/api/authorizers/wx1/privacy-setting").contentType(MediaType.APPLICATION_JSON).content("""
                {"privacyVer":2,
                 "settingList":[{"privacy_key":"UserInfo","privacy_text":"用来展示会员昵称与头像"}],
                 "ownerSetting":{"notice_method":"弹窗","contact_email":"privacy@example.org"}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errcode").value(0));

        verify(settingService).updatePrivacySetting("wx1", 2,
                List.of(Map.of("privacy_key", "UserInfo", "privacy_text", "用来展示会员昵称与头像")),
                Map.of("notice_method", "弹窗", "contact_email", "privacy@example.org"));
    }

    @Test
    @DisplayName("改名路由：驼峰请求体绑得上，占用检查只回 hitCondition/wording")
    void nicknameRoutes() throws Exception {
        when(settingService.setNickname(eq("wx1"), eq("智能制造行业协会"), eq("MEDIA_ID"), eq("MEDIA_LICENSE"),
                eq(List.of("MEDIA_A"))))
                .thenReturn(Map.of("auditId", "33001", "wording", ""));
        when(settingService.checkNickname("wx1", "智能制造行业协会"))
                .thenReturn(Map.<String, Object>of("hitCondition", true, "wording", "请提供可确认主体的材料"));

        mvc.perform(put("/api/authorizers/wx1/nickname").contentType(MediaType.APPLICATION_JSON).content("""
                {"nickname":"智能制造行业协会","idCard":"MEDIA_ID","license":"MEDIA_LICENSE",
                 "namingOtherStuff":["MEDIA_A"]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditId").value("33001"));

        mvc.perform(post("/api/authorizers/wx1/nickname/check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"智能制造行业协会\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hitCondition").value(true))
                .andExpect(jsonPath("$.data.errcode").doesNotExist());

        // 材料上传的 multipart 字段名前端写死为 file，改了就在这儿断掉
        when(settingService.uploadNicknameMaterial(eq("wx1"), eq("license.png"), any()))
                .thenReturn("MEDIA_LICENSE");
        mvc.perform(multipart("/api/authorizers/wx1/nickname/materials")
                        .file(new MockMultipartFile("file", "license.png", "image/png", new byte[]{1, 2})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("MEDIA_LICENSE"));
    }

    @Test
    @DisplayName("列表分页信封 {code,message,data:{records,total,page,size}}")
    void pageEnvelope() throws Exception {
        when(authorizerService.page(eq("协会"), any(), eq(1L), eq(20L))).thenReturn(
                new PageResult<>(List.of(Map.of("appid", "wx1", "nickName", "示例协会")), 1, 1, 20));

        mvc.perform(get("/api/authorizers").param("keyword", "协会"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].appid").value("wx1"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("上传代码：record 请求体的 templateId / extJson / userVersion 能绑定")
    void commitBinding() throws Exception {
        AppRelease release = new AppRelease();
        release.setStatus("COMMITTED");
        release.setUserVersion("1.2.0");
        when(releaseService.commit(eq("wx1"), any())).thenReturn(release);

        mvc.perform(post("/api/authorizers/wx1/code").contentType(MediaType.APPLICATION_JSON).content("""
                {"templateId":"TPL1","userVersion":"1.2.0","userDesc":"迭代","ext":{"apiBase":"https://a.example.com"}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.userVersion").value("1.2.0"));

        ArgumentCaptor<CommitRequest> captor = ArgumentCaptor.forClass(CommitRequest.class);
        verify(releaseService).commit(eq("wx1"), captor.capture());
        assertThat(captor.getValue().templateId()).isEqualTo("TPL1");
        assertThat(captor.getValue().ext()).containsEntry("apiBase", "https://a.example.com");
        assertThat(captor.getValue().extJson()).isNull();
    }

    @Test
    @DisplayName("体验版二维码直接返回图片字节，不套信封")
    void qrcodeIsRawImage() throws Exception {
        when(releaseService.experienceQrcode("wx1", null)).thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1});

        mvc.perform(get("/api/authorizers/wx1/experience-qrcode"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType(MediaType.IMAGE_PNG))
                .andExpect(result -> assertThat(((byte[]) result.getResponse()
                        .getContentAsByteArray())).hasSize(5));
    }

    @Test
    @DisplayName("ApiResponse 里 null data 不输出，前端按 code 判成功")
    void okWithoutData() throws Exception {
        when(configService.recentTickets(20)).thenReturn(List.of());

        mvc.perform(get("/api/platform/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiResponse.OK))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/auth/sms-code：手机号绑定得上，来源 IP 由容器传给服务端做限流")
    void smsCodeBindingAndClientIp() throws Exception {
        when(authService.issueLoginCode("13800138000", "127.0.0.1")).thenReturn(new SmsCodeResult(60, 300));

        mvc.perform(post("/api/auth/sms-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cooldownSeconds").value(60))
                .andExpect(jsonPath("$.data.expiresIn").value(300));

        // 限流按来源 IP 计数，取不到 IP 就等于没有这道闸
        verify(authService).issueLoginCode("13800138000", "127.0.0.1");
    }

    @Test
    @DisplayName("POST /api/auth/login-sms：两个字段都能绑定，错码走 401 而不是 400")
    void smsLoginBindingAndUnauthorized() throws Exception {
        when(authService.loginBySms(new SmsLoginRequest("13800138000", "246813"))).thenReturn(
                new LoginResponse("sms-token", 43200,
                        new UserVO(1L, "admin", "管理员", "ADMIN", "138****8000")));

        mvc.perform(post("/api/auth/login-sms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"code\":\"246813\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("sms-token"))
                .andExpect(jsonPath("$.data.user.phoneMasked").value("138****8000"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());

        doThrow(BizException.unauthorized("手机号或验证码不正确"))
                .when(authService).loginBySms(new SmsLoginRequest("13800138000", "000000"));
        mvc.perform(post("/api/auth/login-sms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("手机号或验证码不正确"));
    }

    @Test
    @DisplayName("429/409/502 只在信封里，HTTP 统一 400：前端按 code 分支，不能按状态码")
    void envelopeOnlyCodesMapToHttp400() throws Exception {
        // 改桩时要走 doThrow：when(mock.x()) 会真的调用一次，把上一条桩里备好的异常先抛出来
        doThrow(new BizException(429, "验证码发送过于频繁，请 37 秒后再试"))
                .when(authService).issueLoginCode(any(), any());
        mvc.perform(post("/api/auth/sms-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("37 秒")));

        doThrow(new BizException(409, "该手机号已绑定其他账号")).when(authService).bindPhone(any());
        mvc.perform(put("/api/auth/phone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"code\":\"246813\",\"password\":\"admin123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(409));

        doThrow(new BizException(502, "验证码发送失败，请稍后重试"))
                .when(authService).issueLoginCode(any(), any());
        mvc.perform(post("/api/auth/sms-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value("验证码发送失败，请稍后重试"));
    }

    @Test
    @DisplayName("绑定/解绑请求体：phone+code+password 与 password 都能绑上")
    void phoneBindingRoutes() throws Exception {
        mvc.perform(put("/api/auth/phone").contentType(MediaType.APPLICATION_JSON).content("""
                {"phone":"13800138000","code":"246813","password":"admin123"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<BindPhoneRequest> bind = ArgumentCaptor.forClass(BindPhoneRequest.class);
        verify(authService).bindPhone(bind.capture());
        assertThat(bind.getValue().phone()).isEqualTo("13800138000");
        assertThat(bind.getValue().code()).isEqualTo("246813");
        assertThat(bind.getValue().password()).isEqualTo("admin123");

        mvc.perform(post("/api/auth/phone/sms-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isOk());
        verify(authService).issueBindCode("13800138000", "127.0.0.1");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/auth/phone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<UnbindPhoneRequest> unbind = ArgumentCaptor.forClass(UnbindPhoneRequest.class);
        verify(authService).unbindPhone(unbind.capture());
        assertThat(unbind.getValue().password()).isEqualTo("admin123");
    }

    @Test
    @DisplayName("探测公开取码接口：body 残缺只回 400 固定文案，不带出解析器原文")
    void malformedBodyOnPublicSmsEndpointIsNotA500() throws Exception {
        mvc.perform(post("/api/auth/sms-code").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体缺失或不是合法的 JSON"));
        mvc.perform(post("/api/auth/sms-code").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体缺失或不是合法的 JSON"));

        verify(authService, never()).issueLoginCode(any(), any());
    }
}
