package com.jistack.platform.e2e;

import com.jistack.platform.wechat.crypto.WeChatMsgCrypt;
import com.jistack.platform.wechat.crypto.XmlCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockWebServer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 连库端到端验证：真实 Spring 上下文 + Flyway 建表的 MySQL + Redis，微信侧由
 * {@link MockWeChatServer} 桩替身，第三方平台凭据通过「平台配置」接口写入。
 *
 * <p>覆盖链路：HTTP 入口 → 后台鉴权 → 安全模式加解密回调 → component/authorizer 令牌
 * → 微信 HTTP 客户端 → MySQL 落库 / Redis 缓存 → 发布状态机。
 * 每个用例在 {@link #bootstrapPlatform()} 里自带前置状态，可单独运行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
@EnabledIf("infrastructureReady")
@DisplayName("端到端：第三方平台授权接入 → 开发 → 提审 → 发布/灰度/回退")
class PlatformEndToEndTest {

    private static final String COMPONENT_APPID = "wxcomponent000001";
    private static final String COMPONENT_SECRET = "mock-component-secret";
    private static final String MESSAGE_TOKEN = "e2e-message-token";
    private static final String ENCODING_AES_KEY = "GyIsL66L8c4T1PQQaAnGUbup1Ykg6h/8jLT2drGbVYA";
    private static final String AUTHORIZER_APPID = MockWeChatServer.AUTHORIZER_APPID;

    private static final MockWeChatServer WX_DISPATCHER = new MockWeChatServer();
    private static final MockWebServer WX_SERVER = new MockWebServer();
    private static final String WECHAT_API_BASE = startMock();
    private static final String SCHEMA = "wxplat_e2e_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    private static String startMock() {
        WX_SERVER.setDispatcher(WX_DISPATCHER);
        try {
            WX_SERVER.start();
        } catch (IOException ex) {
            throw new IllegalStateException("桩微信服务启动失败", ex);
        }
        String url = WX_SERVER.url("/").toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 没有本机 docker compose 的 MySQL/Redis 时整类跳过，避免无基础设施的环境误报。 */
    static boolean infrastructureReady() {
        return tcpOpen(3306) && tcpOpen(6379);
    }

    private static boolean tcpOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:3306/" + SCHEMA
                + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
    }

    @AfterAll
    static void stopMock() throws IOException {
        WX_SERVER.shutdown();
        dropEphemeralSchema();
    }

    /** 用完即删，免得每次运行都在 MySQL 里留下一个临时库。 */
    private static void dropEphemeralSchema() {
        String url = "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, "root", "jistack");
             Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + SCHEMA);
        } catch (SQLException ex) {
            System.err.println("临时库 " + SCHEMA + " 未清理，可手动删除：" + ex.getMessage());
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private StringRedisTemplate redis;

    private String token;

    @BeforeEach
    void bootstrapPlatform() throws Exception {
        login();
        applyPlatformConfig();
        clearWechatCache();
        pushTicket("ticket_setup_" + UUID.randomUUID().toString().replace("-", ""));
        authorizeByScan();
    }

    // ------------------------------------------------------------------ 平台侧

    @Test
    @DisplayName("登录取令牌 → 平台配置只回显掩码标记 → 健康检查全绿")
    void loginConfigAndHealth() throws Exception {
        JsonNode me = api(HttpMethod.GET, "/api/auth/me", null);
        assertThat(me.path("username").asText()).isEqualTo("admin");
        assertThat(me.path("role").asText()).isEqualTo("ADMIN");

        JsonNode config = api(HttpMethod.GET, "/api/platform/config", null);
        assertThat(config.path("componentAppid").asText()).isEqualTo(COMPONENT_APPID);
        assertThat(config.path("secretSet").asBoolean()).isTrue();
        assertThat(config.path("aesKeySet").asBoolean()).isTrue();
        assertThat(config.path("messageTokenSet").asBoolean()).isTrue();
        assertThat(config.path("apiBase").asText()).isEqualTo(WECHAT_API_BASE);
        assertThat(config.path("status").asInt()).isEqualTo(1);
        assertThat(config.path("callbackUrl").asText()).isEqualTo("https://e2e.example.com/wx/callback");
        assertThat(config.path("messageUrl").asText()).isEqualTo("https://e2e.example.com/wx/callback/$APPID$");
        assertThat(config.path("ephemeralMasterKey").asBoolean()).isFalse();

        // 回显绝不能带出明文密钥
        String raw = rawBody("/api/platform/config");
        assertThat(raw).doesNotContain(COMPONENT_SECRET).doesNotContain(ENCODING_AES_KEY);

        JsonNode health = api(HttpMethod.GET, "/api/platform/health", null);
        assertThat(health.path("configReady").asBoolean()).isTrue();
        assertThat(health.path("authorizedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(health.path("ticketFresh").asBoolean())
                .withFailMessage("健康检查返回：%s", health)
                .isTrue();
        assertThat(health.path("componentTokenCheck").asText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("未启用 / 密钥长度非法时，在写库与调用微信之前就被拦下")
    void configGuardrails() throws Exception {
        api(HttpMethod.PUT, "/api/platform/config", Map.of("status", 0));
        try {
            mvc.perform(MockMvcRequestBuilders.post("/api/drafts/sync")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message", Matchers.containsString("尚未启用")));
        } finally {
            api(HttpMethod.PUT, "/api/platform/config", Map.of("status", 1));
        }

        mvc.perform(MockMvcRequestBuilders.put("/api/platform/config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("encodingAesKey", "too-short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", Matchers.containsString("43 位")));

        // 校验失败不该破坏原配置：票据链路照常
        assertThat(api(HttpMethod.POST, "/api/platform/config/check", null).path("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("未登录访问后台接口 401，微信回调入口不受后台鉴权影响")
    void adminApiRequiresToken() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/authorizers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(MockMvcRequestBuilders.get("/api/authorizers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/platform/health"))
                .andExpect(status().isUnauthorized());

        // 图片类接口由浏览器直接发起，允许用 ?token= 认证
        mvc.perform(MockMvcRequestBuilders.get("/api/authorizers?size=1&token=" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ------------------------------------------------------------------ 回调

    @Test
    @DisplayName("安全模式推送票据：验签解密入库，事件表可核对原文")
    void ticketPushIsVerifiedDecryptedAndStored() throws Exception {
        String ticket = "ticket_probe_" + UUID.randomUUID().toString().replace("-", "");
        pushTicket(ticket);

        JsonNode tickets = api(HttpMethod.GET, "/api/platform/tickets?limit=5", null);
        assertThat(tickets).isNotEmpty();
        assertThat(tickets.get(0).path("ticket").asText()).contains("****").doesNotContain(ticket);

        JsonNode newest = firstRecord(api(HttpMethod.GET,
                "/api/events?infoType=component_verify_ticket&size=5", null));
        assertThat(newest.path("signatureOk").asInt()).isEqualTo(1);
        assertThat(newest.path("handled").asInt()).isEqualTo(1);
        assertThat(newest.path("appid").asText()).isEqualTo(COMPONENT_APPID);
        // 列表接口刻意不返回大字段
        assertThat(newest.has("rawBody")).isFalse();
        assertThat(newest.has("decryptedBody")).isFalse();

        JsonNode detail = api(HttpMethod.GET, "/api/events/" + newest.path("id").asLong(), null);
        assertThat(detail.path("decryptedBody").asText()).contains("component_verify_ticket").contains(ticket);
        assertThat(detail.path("rawBody").asText()).contains("<Encrypt>");
        assertThat(detail.has("errorMessage")).isFalse();

        JsonNode check = api(HttpMethod.POST, "/api/platform/config/check", null);
        assertThat(check.path("ok").asBoolean()).isTrue();
        assertThat(check.path("ticketAgeSeconds").asLong())
                .withFailMessage("连通性检查返回：%s", check)
                .isBetween(0L, 60L);
        assertThat(WX_DISPATCHER.called("/cgi-bin/component/api_component_token")).isTrue();
    }

    @Test
    @DisplayName("签名不符的推送只留档不生效")
    void badSignatureIsIgnoredButLogged() throws Exception {
        String maskBefore = api(HttpMethod.GET, "/api/platform/tickets?limit=1", null).get(0)
                .path("ticket").asText();

        String inner = "<xml><AppId>" + COMPONENT_APPID + "</AppId>"
                + "<InfoType>component_verify_ticket</InfoType>"
                + "<ComponentVerifyTicket>ticket_tampered_value</ComponentVerifyTicket></xml>";
        String outer = crypt().encryptToXml(inner, COMPONENT_APPID);
        Map<String, String> envelope = XmlCodec.parseFlat(outer);

        mvc.perform(MockMvcRequestBuilders.post("/wx/callback")
                        .param("msg_signature", "0".repeat(40))
                        .param("timestamp", envelope.get("TimeStamp"))
                        .param("nonce", envelope.get("Nonce"))
                        .contentType(MediaType.APPLICATION_XML)
                        .content(outer))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        JsonNode newest = firstRecord(api(HttpMethod.GET, "/api/events?size=1", null));
        assertThat(newest.path("signatureOk").asInt()).isZero();
        assertThat(newest.path("handled").asInt()).isZero();
        assertThat(newest.path("errorMessage").asText()).contains("msg_signature");
        // 最新票据仍是初始化推送的那一张
        assertThat(api(HttpMethod.GET, "/api/platform/tickets?limit=1", null).get(0)
                .path("ticket").asText()).isEqualTo(maskBefore);
    }

    @Test
    @DisplayName("配置接收 URL 时的 GET 校验原样返回解密后的 echostr")
    void urlVerificationReturnsDecryptedEchoStr() throws Exception {
        String plain = "e2e_echo_" + UUID.randomUUID().toString().replace("-", "");
        String outer = crypt().encryptToXml(plain, COMPONENT_APPID);
        Map<String, String> envelope = XmlCodec.parseFlat(outer);
        String echoStr = envelope.get("Encrypt");
        assertThat(echoStr).isNotBlank();

        mvc.perform(MockMvcRequestBuilders.get("/wx/callback")
                        .param("signature", crypt().computeMsgSignature(
                                envelope.get("TimeStamp"), envelope.get("Nonce"), echoStr))
                        .param("timestamp", envelope.get("TimeStamp"))
                        .param("nonce", envelope.get("Nonce"))
                        .param("echostr", echoStr))
                .andExpect(status().isOk())
                .andExpect(content().string(plain));
    }

    @Test
    @DisplayName("解除授权事件把本地小程序标记为 UNAUTHORIZED，再扫码可恢复")
    void unauthorizedEventThenReauthorize() throws Exception {
        pushEvent("<xml><AppId>" + COMPONENT_APPID + "</AppId><InfoType>unauthorized</InfoType>"
                + "<AuthorizerAppid>" + AUTHORIZER_APPID + "</AuthorizerAppid></xml>");
        assertThat(authorizerRow().path("authState").asText()).isEqualTo("UNAUTHORIZED");

        authorizeByScan();
        assertThat(authorizerRow().path("authState").asText()).isEqualTo("AUTHORIZED");
    }

    // ------------------------------------------------------------------ 授权

    @Test
    @DisplayName("扫码授权：会话出链接、浏览器回调换授权信息、前端轮询到 CONFIRMED")
    void authorizationSessionFlow() throws Exception {
        JsonNode session = api(HttpMethod.POST, "/api/authorizers/auth-sessions", Map.of());
        String state = session.path("state").asText();
        assertThat(state).isNotBlank();
        assertThat(session.path("authUrl").asText())
                .contains("component_appid=" + COMPONENT_APPID)
                .contains("pre_auth_code=MOCK_PRE_AUTH_CODE");
        assertThat(java.net.URLDecoder.decode(session.path("authUrl").asText(), StandardCharsets.UTF_8))
                .contains("/wx/auth/callback")
                .startsWith("https://mp.weixin.qq.com/cgi-bin/componentloginpage");
        assertThat(session.path("mobileAuthUrl").asText()).isNotBlank();
        assertThat(session.path("expiresAt").asText(null)).isNotBlank();

        JsonNode locked = api(HttpMethod.POST, "/api/authorizers/auth-sessions",
                Map.of("bizAppid", AUTHORIZER_APPID));
        assertThat(locked.path("authUrl").asText()).contains("biz_appid=" + AUTHORIZER_APPID);
        assertThat(locked.path("mobileAuthUrl").asText())
                .contains("biz_appid=" + AUTHORIZER_APPID)
                .endsWith("#wechat_redirect");
        assertThat(session.path("authUrl").asText()).doesNotContain("biz_appid=");

        JsonNode scoped = api(HttpMethod.POST, "/api/authorizers/auth-sessions",
                Map.of("bizAppid", AUTHORIZER_APPID, "funcScopeIds", java.util.List.of(18, 1)));
        assertThat(java.net.URLDecoder.decode(scoped.path("authUrl").asText(), StandardCharsets.UTF_8))
                .endsWith("biz_appid=" + AUTHORIZER_APPID + "&category_id_list=18|1");
        assertThat(scoped.path("mobileAuthUrl").asText()).contains("category_id_list=18%7C1");

        mvc.perform(MockMvcRequestBuilders.post("/api/authorizers/auth-sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"funcScopeIds\":[0]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        JsonNode waiting = api(HttpMethod.GET, "/api/authorizers/auth-sessions/" + state, null);
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING");

        mvc.perform(MockMvcRequestBuilders.get("/wx/auth/callback")
                        .param("auth_code", "MOCK_AUTH_CODE_E2E")
                        .param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("auth=success")));

        JsonNode confirmed = api(HttpMethod.GET, "/api/authorizers/auth-sessions/" + state, null);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("resultAppid").asText()).isEqualTo(AUTHORIZER_APPID);
        assertThat(WX_DISPATCHER.called("/cgi-bin/component/api_query_auth")).isTrue();

        JsonNode page = api(HttpMethod.GET, "/api/authorizers?size=10", null);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(rowOf(page.path("records"), AUTHORIZER_APPID).path("authState").asText())
                .isEqualTo("AUTHORIZED");

        mvc.perform(MockMvcRequestBuilders.get("/api/authorizers/auth-sessions/no-such-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("授权方详情/令牌续期/资料刷新，refresh_token 不出现在响应里")
    void authorizerDetailTokenAndRefresh() throws Exception {
        String raw = rawBody("/api/authorizers/" + AUTHORIZER_APPID);
        assertThat(raw).doesNotContain("MOCK_REFRESH_TOKEN").doesNotContain("refreshTokenEnc");
        assertThat(json.readTree(raw).path("data").path("appid").asText()).isEqualTo(AUTHORIZER_APPID);

        JsonNode refreshed = api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/token/refresh", null);
        assertThat(refreshed.path("tokenRefreshed").asBoolean()).isTrue();
        assertThat(refreshed.path("expiresIn").asLong()).isPositive();
        assertThat(WX_DISPATCHER.called("/cgi-bin/component/api_authorizer_token")).isTrue();

        JsonNode info = api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/refresh", null);
        assertThat(info.path("nickName").asText()).contains("协会小程序");
        assertThat(info.path("releaseVersion").asText()).isEqualTo("1.0.9");

        JsonNode version = api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/version", null);
        assertThat(version.path("expInfo").path("exp_version").asText()).isEqualTo("1.1.0");
        assertThat(version.path("releaseInfo").path("release_version").asText()).isEqualTo("1.0.9");

        JsonNode codeInfo = api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/code-info", null);
        assertThat(codeInfo.path("exp").path("version").asText()).isEqualTo("1.1.0");
        assertThat(codeInfo.path("exp").path("at").asText()).isNotBlank();
        assertThat(codeInfo.path("release").path("version").asText()).isEqualTo("1.0.9");
        assertThat(codeInfo.path("visitStatus").asInt()).isEqualTo(1);

        // 备案：桩返回 1026（未备案 + 仅类目未填），后端要把它解码成一条缺失前置条件
        JsonNode icp = api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/icp-status", null);
        assertThat(icp.path("status").asInt()).isEqualTo(1026);
        assertThat(icp.path("statusText").asText()).isEqualTo("未备案");
        assertThat(icp.path("filed").asBoolean()).isFalse();
        assertThat(icp.path("entranceAvailable").asBoolean()).isTrue();
        assertThat(icp.path("missingPrerequisites").size()).isEqualTo(1);
        assertThat(icp.path("missingPrerequisites").get(0).asText()).contains("类目");
        assertThat(icp.path("rejectReasons").get(0).path("key_name").asText()).isEqualTo("小程序简介");

        // 服务状态可写：close 之后重读必须是 0，界面才能确认真暂停了；收尾恢复，不影响其他用例
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/visit-status",
                Map.of("action", "close")).asInt()).isZero();
        assertThat(WX_DISPATCHER.called("/wxa/change_visitstatus")).isTrue();
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/visit-status", null).asInt())
                .isZero();

        mvc.perform(MockMvcRequestBuilders.post("/api/authorizers/" + AUTHORIZER_APPID + "/visit-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"suspend\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/visit-status",
                Map.of("action", "open")).asInt()).isEqualTo(1);

        // 体验版二维码由浏览器直接发起，只能靠 ?token= 认证，响应是图片字节
        mvc.perform(MockMvcRequestBuilders.get(
                        "/api/authorizers/" + AUTHORIZER_APPID + "/experience-qrcode?token=" + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, Matchers.containsString("image/png")));

        assertThat(api(HttpMethod.POST, "/api/authorizers/sync", null).asInt()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ 模板

    @Test
    @DisplayName("草稿箱同步、草稿转模板、模板库同步")
    void draftAndTemplateSync() throws Exception {
        JsonNode synced = api(HttpMethod.POST, "/api/drafts/sync", null);
        assertThat(synced).hasSize(2);
        assertThat(synced.get(0).path("draftId").asText()).isEqualTo("1001");
        assertThat(synced.get(0).path("userVersion").asText()).isEqualTo("1.0.0");
        assertThat(api(HttpMethod.GET, "/api/drafts", null)).hasSize(2);

        JsonNode converted = api(HttpMethod.POST, "/api/drafts/1002/to-template", Map.of("templateType", 0));
        assertThat(converted.path("template_id").asText()).isEqualTo(MockWeChatServer.TEMPLATE_ID);

        JsonNode templates = api(HttpMethod.POST, "/api/templates/sync", null);
        assertThat(templates).hasSize(2);
        assertThat(templates.get(0).path("templateId").asText()).isEqualTo("TPL0001");
        assertThat(templates.get(1).path("templateId").asText()).isEqualTo("TPL9001");
        assertThat(api(HttpMethod.GET, "/api/templates", null)).hasSize(2);

        mvc.perform(MockMvcRequestBuilders.post("/api/drafts/abc/to-template")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", Matchers.containsString("数字")));

        api(HttpMethod.DELETE, "/api/templates/TPL0001", null);
        assertThat(WX_DISPATCHER.called("/wxa/deletetemplate")).isTrue();
    }

    // ------------------------------------------------------------------ 发布

    @Test
    @DisplayName("上传代码：素材、模板、ext_appid 与体验版二维码")
    void commitCodeAndExperienceQrcode() throws Exception {
        String mediaRaw = mvc.perform(MockMvcRequestBuilders
                        .multipart("/api/authorizers/" + AUTHORIZER_APPID + "/media")
                        .file(new MockMultipartFile("file", "shot.png", "image/png", new byte[]{1, 2, 3}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(mediaRaw).path("data").asText()).isEqualTo("MOCK_MEDIA_ID");

        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("templateId", MockWeChatServer.TEMPLATE_ID);
        commit.put("userVersion", "1.1.0");
        commit.put("userDesc", "e2e 活动报名");
        commit.put("ext", Map.of("enableDev", true));
        JsonNode committed = api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/code", commit);
        assertThat(committed.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.path("templateId").asText()).isEqualTo(MockWeChatServer.TEMPLATE_ID);
        assertThat(committed.path("extAppid").asText()).isEqualTo(AUTHORIZER_APPID);
        assertThat(committed.path("userVersion").asText()).isEqualTo("1.1.0");
        assertThat(WX_DISPATCHER.called("/wxa/commit")).isTrue();

        var qrcode = mvc.perform(MockMvcRequestBuilders.get(
                        "/api/authorizers/" + AUTHORIZER_APPID + "/experience-qrcode?token=" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(qrcode.getContentType()).contains("image/png");
        assertThat(qrcode.getContentAsByteArray()).startsWith((byte) 0x89, 'P', 'N', 'G');
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/pages", null)
                .path("page_list").get(0).asText()).isEqualTo("pages/index/index");

        // 前置条件：没选模板不能调微信
        mvc.perform(MockMvcRequestBuilders.post("/api/authorizers/" + AUTHORIZER_APPID + "/code")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userVersion\":\"9.9.9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("模板")));
        mvc.perform(MockMvcRequestBuilders.post("/api/authorizers/wxnotexists0000/code")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"TPL0001\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("提审 → 审核通过 → 发布 → 灰度 → 取消灰度 → 回退，状态机逐步落库")
    void auditPublishGrayAndRevert() throws Exception {
        api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/code", Map.of(
                "templateId", MockWeChatServer.TEMPLATE_ID,
                "userVersion", "1.1.0",
                "userDesc", "e2e 活动报名"));

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("itemList", List.of(Map.of(
                "address", "pages/index/index",
                "title", "首页",
                "tag", List.of("协会", "报名"),
                "firstClass", "医疗服务",
                "firstId", 1,
                "secondClass", "公立医疗机构",
                "secondId", 2)));
        audit.put("feedbackInfo", "协会服务，会员报名");
        audit.put("versionDesc", "1.1.0 活动报名");
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/audit", audit).asText())
                .isEqualTo(MockWeChatServer.AUDIT_ID);
        assertThat(latestRelease().path("status").asText()).isEqualTo("AUDITING");
        assertThat(latestRelease().path("lastAuditId").asText()).isEqualTo(MockWeChatServer.AUDIT_ID);

        mvc.perform(MockMvcRequestBuilders.post("/api/authorizers/" + AUTHORIZER_APPID + "/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemList\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("页面")));

        JsonNode refreshed = api(HttpMethod.POST,
                "/api/authorizers/" + AUTHORIZER_APPID + "/audit/refresh", Map.of());
        assertThat(refreshed.path("status").asInt()).isZero();
        assertThat(refreshed.path("auditId").asText()).isEqualTo(MockWeChatServer.AUDIT_ID);
        assertThat(refreshed.path("lastQueriedAt").asText(null)).isNotBlank();
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/audit/latest", null)
                .path("status").asInt()).isZero();
        assertThat(latestRelease().path("status").asText()).isEqualTo("AUDIT_PASS");

        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/publish", null)
                .path("status").asText()).isEqualTo("PUBLISHED");

        JsonNode gray = api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/gray-release",
                Map.of("grayPercentage", 10));
        // 文档：grayrelease 只回 errcode/errmsg，比例与状态要另读灰度计划
        assertThat(gray.path("errcode").asInt()).isZero();
        assertThat(latestRelease().path("status").asText()).isEqualTo("GRAY");
        JsonNode plan = api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/gray-release", null);
        assertThat(plan.path("status").asInt()).isEqualTo(1);
        assertThat(plan.path("statusText").asText()).isEqualTo("执行中");
        assertThat(plan.path("actionable").asBoolean()).isTrue();
        assertThat(plan.path("grayPercentage").asInt()).isEqualTo(10);
        assertThat(plan.path("supportDebuggerFirst").asBoolean()).isTrue();
        assertThat(plan.path("createdAt").asText(null)).isNotBlank();

        // 文档写的是 0~100 的整数，100 也是合法的灰度计划
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/gray-release",
                Map.of("grayPercentage", 100)).path("errcode").asInt()).isZero();
        assertThat(latestRelease().path("status").asText()).isEqualTo("GRAY");

        mvc.perform(MockMvcRequestBuilders.post("/api/authorizers/" + AUTHORIZER_APPID + "/gray-release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grayPercentage\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("0~100")));

        api(HttpMethod.DELETE, "/api/authorizers/" + AUTHORIZER_APPID + "/gray-release", null);
        assertThat(latestRelease().path("status").asText()).isEqualTo("PUBLISHED");

        JsonNode revert = api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/revert",
                Map.of("appVersion", "1.0.0"));
        assertThat(revert.path("errcode").asInt()).isZero();
        assertThat(latestRelease().path("status").asText()).isEqualTo("REVERTED");
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/revert-versions", null)
                .path("version_list").get(0).path("app_version").asText()).isEqualTo("1.0.0");
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/release/history?limit=10", null))
                .isNotEmpty();
    }

    @Test
    @DisplayName("操作日志：动作名按微信接口小写名落库，可按动作/小程序精确检索")
    void operationLogsRecordWechatActions() throws Exception {
        api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/code", Map.of(
                "templateId", MockWeChatServer.TEMPLATE_ID, "userVersion", "1.5.0", "userDesc", "e2e 日志用例"));
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/publish", null)
                .path("status").asText()).isEqualTo("PUBLISHED");

        JsonNode commit = firstRecord(api(HttpMethod.GET,
                "/api/logs?action=commit&appid=" + AUTHORIZER_APPID, null));
        assertThat(commit.path("action").asText()).isEqualTo("commit");
        assertThat(commit.path("apiPath").asText()).isEqualTo("/wxa/commit");
        assertThat(commit.path("authorizerAppid").asText()).isEqualTo(AUTHORIZER_APPID);
        assertThat(commit.path("operatorName").asText()).isEqualTo("admin");
        assertThat(commit.path("success").asInt()).isEqualTo(1);

        JsonNode release = firstRecord(api(HttpMethod.GET, "/api/logs?action=release", null));
        assertThat(release.path("apiPath").asText()).isEqualTo("/wxa/release");
        assertThat(api(HttpMethod.GET, "/api/logs/recent?limit=5", null)).isNotEmpty();

        // 检索是精确匹配：大写或臆想的动作名查不到，前端提示只能给真实动作名
        assertThat(api(HttpMethod.GET, "/api/logs?action=PUBLISH", null).path("total").asLong()).isZero();
        assertThat(api(HttpMethod.GET, "/api/logs?action=AUDIT_SUBMIT", null).path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("提审撤回与审核加速走通微信入口")
    void undoAndSpeedupAudit() throws Exception {
        api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/code", Map.of(
                "templateId", MockWeChatServer.TEMPLATE_ID, "userVersion", "1.2.0", "userDesc", "e2e 撤回用例"));
        api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/audit", Map.of(
                "itemList", List.of(Map.of("address", "pages/index/index",
                        "firstClass", "医疗服务", "firstId", 1,
                        "secondClass", "公立医疗机构", "secondId", 2))));
        assertThat(latestRelease().path("status").asText()).isEqualTo("AUDITING");

        api(HttpMethod.DELETE, "/api/authorizers/" + AUTHORIZER_APPID + "/audit", null);
        assertThat(latestRelease().path("status").asText()).isEqualTo("COMMITTED");
        assertThat(WX_DISPATCHER.called("/wxa/undocodeaudit")).isTrue();

        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/authorizers/" + AUTHORIZER_APPID + "/audit/speedup?auditId=" + MockWeChatServer.AUDIT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(WX_DISPATCHER.called("/wxa/speedupaudit")).isTrue();

        // 界面不填 auditId 时后端要自己回落到最近一次提审，空 auditid 会让微信调用直接抛错
        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/authorizers/" + AUTHORIZER_APPID + "/audit/speedup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("第三方平台自身域名白名单：授权小程序域名的取值范围")
    void platformDomainAllowlist() throws Exception {
        JsonNode server = api(HttpMethod.GET, "/api/platform/domains/server", null);
        assertThat(server.path("published").get(0).asText()).isEqualTo("api.mock.example");
        assertThat(server.path("invalid").get(0).asText()).isEqualTo("bad.mock.example");
        assertThat(server.path("limit").asInt()).isEqualTo(1000);

        JsonNode saved = api(HttpMethod.PUT, "/api/platform/domains/server", Map.of(
                "action", "set",
                "domains", List.of("https://api.mock.example/activity/"),
                "modifyPublishedTogether", false));
        assertThat(saved.path("testing").get(0).asText()).isEqualTo("api.mock.example");

        assertThat(api(HttpMethod.GET, "/api/platform/domains/jump", null)
                .path("published").get(0).asText()).isEqualTo("h5.mock.example");
        assertThat(api(HttpMethod.GET, "/api/platform/domains/confirm-file", null)
                .path("fileContent").asText()).isEqualTo("mock-confirm-content");

        mvc.perform(MockMvcRequestBuilders.get("/api/platform/domains/webview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("server（服务器域名）")));
    }

    @Test
    @DisplayName("基本信息、域名、类目、体验成员配置接口打通桩微信")
    void miniProgramConfiguration() throws Exception {
        // 先刷新授权方资料：小程序码与 gh_ 原始 ID 只有 api_get_authorizer_info 返回，基本信息页靠它兜底
        api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/refresh", null);
        JsonNode basic = api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/basic-info", null);
        assertThat(basic.path("nickname").asText()).contains("协会小程序");
        assertThat(basic.path("signature").asText()).isEqualTo("服务协会会员");
        assertThat(basic.path("avatarUrl").asText()).isEqualTo("https://mock.example/avatar.png");
        assertThat(basic.path("qrCodeUrl").asText()).isEqualTo("https://mock.example/qr-authorizer.png");
        assertThat(basic.path("userName").asText()).isEqualTo("gh_xzmock");
        assertThat(basic.path("principalName").asText()).isEqualTo("某某行业协会");
        assertThat(basic.path("openVerify").asInt()).isZero();

        assertThat(api(HttpMethod.PUT, "/api/authorizers/" + AUTHORIZER_APPID + "/signature",
                Map.of("signature", "服务协会会员")).path("errcode").asInt()).isZero();
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/nickname/check",
                Map.of("nickname", "某某行业协会")).path("errcode").asInt()).isZero();

        JsonNode updated = api(HttpMethod.PUT, "/api/authorizers/" + AUTHORIZER_APPID + "/domains", Map.of(
                "action", "set", "type", "request", "domains", List.of("https://mock.example")));
        assertThat(updated.path("request")).isNotEmpty();
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/domains", null)
                .path("request").get(0).asText()).isEqualTo("https://mock.example");
        JsonNode webviewDomains = api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/webview-domains", null);
        assertThat(webviewDomains.toString()).contains("https://mock.example");
        assertThat(webviewDomains.toString()).contains("https://mp.example");
        assertThat(api(HttpMethod.PUT, "/api/authorizers/" + AUTHORIZER_APPID + "/webview-domains",
                Map.of("action", "add", "domains", List.of("https://mock.example")))
                .toString()).contains("https://mock.example");
        // 域名类型写错时不调用微信
        mvc.perform(MockMvcRequestBuilders.put("/api/authorizers/" + AUTHORIZER_APPID + "/domains")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set\",\"type\":\"ftp\",\"domains\":[\"https://x.example\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("域名类型")));

        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/categories", null)
                .path("categories").get(0).path("name").asText()).isEqualTo("医疗服务");
        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/all-categories", null)
                .path("category_list").get(0).path("name").asText()).isEqualTo("医疗服务");
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/categories",
                Map.of("first", 1, "second", 2)).path("errcode").asInt()).isZero();

        assertThat(api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/testers", null)
                .get(0).path("userstr").asText()).isEqualTo("mock-member-openid");
        assertThat(api(HttpMethod.POST, "/api/authorizers/" + AUTHORIZER_APPID + "/testers",
                Map.of("wechatId", "mock_wx_id")).asText()).isEqualTo("mock-userstr");
        assertThat(WX_DISPATCHER.called("/wxa/bind_tester")).isTrue();
    }

    // ------------------------------------------------------------------ 辅助

    private void login() throws Exception {
        JsonNode data = api(HttpMethod.POST, "/api/auth/login",
                Map.of("username", "admin", "password", "admin123"));
        token = data.path("token").asText();
        assertThat(token).isNotBlank();
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "admin", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    private void applyPlatformConfig() throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("componentAppid", COMPONENT_APPID);
        config.put("componentSecret", COMPONENT_SECRET);
        config.put("messageToken", MESSAGE_TOKEN);
        config.put("encodingAesKey", ENCODING_AES_KEY);
        config.put("siteBase", "https://e2e.example.com");
        config.put("apiBase", WECHAT_API_BASE);
        config.put("status", 1);
        api(HttpMethod.PUT, "/api/platform/config", config);
    }

    /** Redis 里的 ticket/token 跨用例复用会掩盖问题，初始化时清掉。 */
    private void clearWechatCache() {
        Set<String> keys = redis.keys("wx:open:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void pushTicket(String ticket) throws Exception {
        pushEvent("<xml><AppId>" + COMPONENT_APPID + "</AppId>"
                + "<InfoType>component_verify_ticket</InfoType>"
                + "<ComponentVerifyTicket>" + ticket + "</ComponentVerifyTicket></xml>");
    }

    /** 以微信安全模式推送一条事件（票据、授权事件等）。 */
    private void pushEvent(String innerXml) throws Exception {
        WeChatMsgCrypt crypt = crypt();
        String outer = crypt.encryptToXml(innerXml, COMPONENT_APPID);
        Map<String, String> envelope = XmlCodec.parseFlat(outer);
        mvc.perform(MockMvcRequestBuilders.post("/wx/callback")
                        .param("msg_signature", envelope.get("MsgSignature"))
                        .param("timestamp", envelope.get("TimeStamp"))
                        .param("nonce", envelope.get("Nonce"))
                        .contentType(MediaType.APPLICATION_XML)
                        .content(outer))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
    }

    /** 建立（或刷新）授权关系：等价于商家扫码后浏览器跳回本站。 */
    private void authorizeByScan() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/wx/auth/callback")
                        .param("auth_code", "MOCK_AUTH_CODE_SETUP")
                        .param("state", "e2e-setup"))
                .andExpect(status().is3xxRedirection());
    }

    private JsonNode authorizerRow() throws Exception {
        return api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID, null);
    }

    private JsonNode latestRelease() throws Exception {
        return api(HttpMethod.GET, "/api/authorizers/" + AUTHORIZER_APPID + "/release/latest", null);
    }

    private WeChatMsgCrypt crypt() {
        return new WeChatMsgCrypt(MESSAGE_TOKEN, ENCODING_AES_KEY, COMPONENT_APPID);
    }

    private JsonNode api(HttpMethod method, String url, Object body) throws Exception {
        var builder = MockMvcRequestBuilders.request(method, url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body));
        }
        String response = execute(builder);
        JsonNode root = json.readTree(response);
        assertThat(root.path("code").asInt(-1))
                .withFailMessage("接口 %s %s 调用失败: %s", method, url, response)
                .isZero();
        return root.path("data");
    }

    private String execute(MockHttpServletRequestBuilder builder) throws Exception {
        var result = mvc.perform(builder).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .withFailMessage("HTTP 请求失败，响应体：%s", body)
                .isEqualTo(200);
        return body;
    }

    /** 需要核对原始响应文本（例如确认密钥没有回显）时用，自动带后台令牌。 */
    private String rawBody(String url) throws Exception {
        return execute(MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static JsonNode firstRecord(JsonNode page) {
        JsonNode records = page.path("records");
        assertThat(records).isNotEmpty();
        return records.get(0);
    }

    private static JsonNode rowOf(JsonNode records, String appid) {
        for (JsonNode node : records) {
            if (appid.equals(node.path("appid").asText(null))) {
                return node;
            }
        }
        throw new AssertionError("列表中没有 appid=" + appid + "，实际=" + records);
    }
}
