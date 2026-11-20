package com.jistack.platform.wechat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 MockWebServer 锁定第三方平台接口的路径、鉴权参数与请求体字段名：
 * 这些细节写错只能在真机上才发现，故作为契约测试保留。
 */
class WechatApiContractTest {

    private static final String COMPONENT_APPID = "wxcomponent0000001";
    private static final String AUTHORIZER_APPID = "wxauthorizer000001";
    private static final String COMPONENT_TOKEN = "COMPONENT_ACCESS_TOKEN";
    private static final String AUTHORIZER_TOKEN = "AUTHORIZER_ACCESS_TOKEN";

    private MockWebServer server;
    private WechatApiClient api;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        api = new WechatApiClient(server.url("/").toString(), new RestTemplate(), objectMapper);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private void ok(String body) {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body));
    }

    private RecordedRequest request() throws Exception {
        return server.takeRequest();
    }

    private JsonNode bodyOf(RecordedRequest request) throws Exception {
        return objectMapper.readTree(request.getBody().readUtf8());
    }

    private static String pathOnly(RecordedRequest request) {
        return request.getPath().split("\\?")[0];
    }

    // -------------------------------------------------- component 令牌与授权

    @Test
    @DisplayName("api_component_token：三个凭证放 body，不带 query token")
    void componentToken() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"component_access_token\":\"T\",\"expires_in\":7200}");

        JsonNode response = ComponentApi.componentToken(api, COMPONENT_APPID, "secret", "ticket");

        RecordedRequest request = request();
        assertEquals("POST", request.getMethod());
        assertEquals("/cgi-bin/component/api_component_token", pathOnly(request));
        assertTrue(request.getPath().indexOf('?') < 0, "该接口不应带 query token");
        JsonNode body = bodyOf(request);
        assertEquals(COMPONENT_APPID, body.path("component_appid").asText());
        assertEquals("secret", body.path("component_appsecret").asText());
        assertEquals("ticket", body.path("component_verify_ticket").asText());
        assertEquals("T", response.path("component_access_token").asText());
    }

    @Test
    @DisplayName("api_start_push_ticket 字段名是 component_secret，不是 component_appsecret")
    void startPushTicket() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        ComponentApi.startPushTicket(api, COMPONENT_APPID, "secret");

        JsonNode body = bodyOf(request());
        assertEquals("secret", body.path("component_secret").asText());
        assertTrue(body.path("component_appsecret").isMissingNode());
    }

    @Test
    @DisplayName("component 级接口同时带 access_token 与 component_access_token（文档两个版本命名不一）")
    void componentLevelQueryParams() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"pre_auth_code\":\"PRE\",\"expires_in\":600}");

        ComponentApi.createPreAuthCode(api, COMPONENT_TOKEN, COMPONENT_APPID);

        RecordedRequest request = request();
        assertEquals("POST", request.getMethod());
        assertEquals("/cgi-bin/component/api_create_preauthcode", pathOnly(request));
        assertTrue(request.getPath().contains("component_access_token=" + COMPONENT_TOKEN), request.getPath());
        assertTrue(request.getPath().contains("access_token=" + COMPONENT_TOKEN), request.getPath());
        assertEquals(COMPONENT_APPID, bodyOf(request).path("component_appid").asText());
    }

    @Test
    @DisplayName("api_query_auth：auth_code 换 refresh_token / func_info，微信不返回昵称头像")
    void queryAuth() throws Exception {
        ok("{\"errcode\":0,\"authorization_info\":{\"authorizer_appid\":\"" + AUTHORIZER_APPID
                + "\",\"authorizer_refresh_token\":\"REFRESH\",\"func_info\":[{\"funcscope_category\":{\"id\":1}}]}}");

        JsonNode response = ComponentApi.queryAuth(api, COMPONENT_TOKEN, COMPONENT_APPID, "auth_code_value");

        RecordedRequest request = request();
        assertEquals("/cgi-bin/component/api_query_auth", pathOnly(request));
        JsonNode body = bodyOf(request);
        assertEquals(COMPONENT_APPID, body.path("component_appid").asText());
        assertEquals("auth_code_value", body.path("authorization_code").asText());
        JsonNode info = response.path("authorization_info");
        assertEquals(AUTHORIZER_APPID, info.path("authorizer_appid").asText());
        assertEquals("REFRESH", info.path("authorizer_refresh_token").asText());
        assertEquals(1, info.path("func_info").get(0).path("funcscope_category").path("id").asInt());
    }

    @Test
    @DisplayName("api_authorizer_token：必须回传 refresh_token，微信会轮换")
    void authorizerToken() throws Exception {
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT\",\"expires_in\":7200,"
                + "\"authorizer_refresh_token\":\"REFRESH_ROTATED\"}");

        JsonNode response = ComponentApi.authorizerToken(api, COMPONENT_TOKEN, COMPONENT_APPID,
                AUTHORIZER_APPID, "REFRESH_OLD");

        JsonNode body = bodyOf(request());
        assertEquals(AUTHORIZER_APPID, body.path("authorizer_appid").asText());
        assertEquals("REFRESH_OLD", body.path("authorizer_refresh_token").asText());
        assertEquals("AT", response.path("authorizer_access_token").asText());
        assertEquals("REFRESH_ROTATED", response.path("authorizer_refresh_token").asText());
    }

    @Test
    @DisplayName("授权方信息走 api_get_authorizer_info")
    void authorizerInfo() throws Exception {
        ok("{\"errcode\":0,\"authorizer_info\":{\"nick_name\":\"协会\",\"head_img\":\"https://x/y\","
                + "\"MiniProgramInfo\":{\"is_added\":true}}}");

        ComponentApi.authorizerInfo(api, COMPONENT_TOKEN, COMPONENT_APPID, AUTHORIZER_APPID);

        RecordedRequest request = request();
        assertEquals("/cgi-bin/component/api_get_authorizer_info", pathOnly(request));
        assertEquals(AUTHORIZER_APPID, bodyOf(request).path("authorizer_appid").asText());
    }

    @Test
    @DisplayName("已授权列表 count 上限 500")
    void authorizerListCapsCount() throws Exception {
        ok("{\"errcode\":0,\"list\":[]}");

        ComponentApi.authorizerList(api, COMPONENT_TOKEN, COMPONENT_APPID, 0, 5000);

        assertEquals(500, bodyOf(request()).path("count").asInt());
    }

    @Test
    void authUrlContainsRequiredParams() {
        String url = ComponentApi.authUrl(COMPONENT_APPID, "PRE_CODE", "https://example.com/wx/auth/callback",
                ComponentApi.AUTH_TYPE_MINIPROGRAM, null, null);

        assertTrue(url.startsWith(ComponentApi.LOGIN_PAGE + "?"), url);
        assertTrue(url.contains("component_appid=" + COMPONENT_APPID));
        assertTrue(url.contains("pre_auth_code=PRE_CODE"));
        assertTrue(url.contains("auth_type=2"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fexample.com%2Fwx%2Fauth%2Fcallback"), url);
        assertFalse(url.contains("category_id_list"), url);

        String scoped = ComponentApi.authUrl(COMPONENT_APPID, "PRE_CODE", "https://example.com/cb",
                ComponentApi.AUTH_TYPE_ALL, AUTHORIZER_APPID, "18|1");
        assertTrue(scoped.contains("biz_appid=" + AUTHORIZER_APPID), scoped);
        assertTrue(java.net.URLDecoder.decode(scoped, StandardCharsets.UTF_8).endsWith("category_id_list=18|1"), scoped);
    }

    @Test
    void mobileAuthUrlEndsWithWechatRedirect() {
        String url = ComponentApi.mobileAuthUrl(COMPONENT_APPID, "PRE", "https://example.com/cb", null,
                AUTHORIZER_APPID, null);

        assertTrue(url.startsWith(ComponentApi.BIND_PAGE + "?"), url);
        assertTrue(url.endsWith("#wechat_redirect"), url);
        assertTrue(url.contains("biz_appid=" + AUTHORIZER_APPID));
        assertTrue(url.contains("auth_type=3"), url);
    }

    // -------------------------------------------------- 小程序代操作

    @Test
    @DisplayName("上传代码：template_id 是 number，user_version / user_desc 必填不缺席")
    void commitPayload() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        MiniProgramApi.commit(api, AUTHORIZER_TOKEN, "9527",
                "{\"extAppid\":\"" + AUTHORIZER_APPID + "\"}", "1.0.0", "首版");

        RecordedRequest request = request();
        assertEquals("POST", request.getMethod());
        assertEquals("/wxa/commit?access_token=" + AUTHORIZER_TOKEN, request.getPath());
        JsonNode body = bodyOf(request);
        assertTrue(body.path("template_id").isNumber(), "文档里 template_id 是 number");
        assertEquals(9527L, body.path("template_id").asLong());
        assertEquals("1.0.0", body.path("user_version").asText());
        assertEquals("首版", body.path("user_desc").asText());
        assertTrue(body.path("ext_json").asText().contains("extAppid"));
        assertTrue(body.path("redirect_domain_list").isMissingNode(), "commit 不接受域名列表");
        assertTrue(body.path("draft_id").isMissingNode(), "commit 不能直接用草稿");

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.commit(api, AUTHORIZER_TOKEN, "TPL123", "{}", "1.0.1", "描述");
        assertEquals("TPL123", bodyOf(request()).path("template_id").asText(), "非数字 id 原样交给微信报错");
    }

    @Test
    @DisplayName("提审：类目按名称 + 数字 id 成对提交，tag 是空格分隔的标签")
    void submitAuditPayload() throws Exception {
        ok("{\"errcode\":0,\"auditid\":\"AUDIT1\"}");

        JsonNode response = MiniProgramApi.submitAudit(api, AUTHORIZER_TOKEN,
                List.of(Map.of("address", "pages/index/index",
                        "tag", "协会 报名",
                        "first_class", "商业服务", "first_id", 12,
                        "second_class", "公共服务", "second_id", 132,
                        "third_class", "认证", "third_id", 305)),
                "有问题联系管理员", "版本说明", null);

        RecordedRequest request = request();
        assertEquals("/wxa/submit_audit?access_token=" + AUTHORIZER_TOKEN, request.getPath());
        JsonNode item = bodyOf(request).path("item_list").get(0);
        assertEquals("pages/index/index", item.path("address").asText());
        assertEquals("协会 报名", item.path("tag").asText());
        assertTrue(item.path("tag").isTextual(), "tag 不能发成数组");
        assertEquals("商业服务", item.path("first_class").asText());
        assertTrue(item.path("first_id").isNumber());
        assertEquals(12, item.path("first_id").asInt());
        assertEquals(132, item.path("second_id").asInt());
        assertEquals(305, item.path("third_id").asInt());
        assertEquals("AUDIT1", response.path("auditid").asText());
    }

    @Test
    @DisplayName("auditid 按微信文档发成 number，查询与加急一致；非数字原样透传")
    void auditIdIsSentAsNumber() throws Exception {
        ok("{\"errcode\":0,\"status\":0}");
        MiniProgramApi.auditStatus(api, AUTHORIZER_TOKEN, "485268816");
        JsonNode queryBody = bodyOf(request());
        assertTrue(queryBody.path("auditid").isNumber(), "get_auditstatus 的 auditid 必须是数字");
        assertEquals(485268816L, queryBody.path("auditid").asLong());

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.speedupAudit(api, AUTHORIZER_TOKEN, "485268816");
        RecordedRequest speedup = request();
        assertEquals("/wxa/speedupaudit?access_token=" + AUTHORIZER_TOKEN, speedup.getPath());
        assertTrue(bodyOf(speedup).path("auditid").isNumber(), "speedupaudit 的 auditid 必须是数字");

        // 界面允许手填 auditId，填错成非数字时应原样发给微信、由微信报错，而不是本地抛异常
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.speedupAudit(api, AUTHORIZER_TOKEN, " AUDIT1 ");
        assertEquals(" AUDIT1 ", bodyOf(request()).path("auditid").asText());
    }

    @Test
    @DisplayName("灰度发布：gray_percentage 必填且 0~100 都照发")
    void grayReleaseUsesPercentage() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        MiniProgramApi.grayRelease(api, AUTHORIZER_TOKEN, 20, null, null);

        JsonNode body = bodyOf(request());
        assertEquals(20, body.path("gray_percentage").asInt());
        assertTrue(body.path("gray_percentage").isNumber());
        assertTrue(body.path("u32").isMissingNode());

        // 100 也是文档允许的比例（全量灰度计划），不再被本地拒绝
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.grayRelease(api, AUTHORIZER_TOKEN, 100, true, true);
        assertEquals(100, bodyOf(request()).path("gray_percentage").asInt());

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.grayRelease(api, AUTHORIZER_TOKEN, 0, false, true);
        JsonNode zero = bodyOf(request());
        assertEquals(0, zero.path("gray_percentage").asInt());
        assertTrue(zero.path("support_experiencer_first").asBoolean());
    }

    @Test
    @DisplayName("体验版二维码：path 先做一次 urlencode，页面参数不会被当成查询串截断")
    void experienceQrcodeEncodesPath() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(new byte[]{(byte) 0x89, 'P', 'N', 'G'})));

        MiniProgramApi.experienceQrcode(api, AUTHORIZER_TOKEN, "page/index?action=1");

        String path = request().getPath();
        assertTrue(path.startsWith("/wxa/get_qrcode?access_token=" + AUTHORIZER_TOKEN));
        assertTrue(path.contains("path=page%2Findex%3Faction%3D1"), "实际请求：" + path);
    }

    @Test
    @DisplayName("模板删除与改名结果查询的 id 按文档发成 number")
    void templateAndNicknameIdsAreNumbers() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.deleteTemplate(api, COMPONENT_TOKEN, "9527");
        assertTrue(bodyOf(request()).path("template_id").isNumber());

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.queryNickname(api, AUTHORIZER_TOKEN, "12345");
        assertTrue(bodyOf(request()).path("audit_id").isNumber(), "querynickname 的 audit_id 是 number");
    }

    @Test
    @DisplayName("服务器域名字段名无下划线：requestdomain")
    void modifyDomainFieldNames() throws Exception {
        ok("{\"errcode\":0,\"requestdomain\":[\"https://a.example.com\"]}");

        MiniProgramApi.modifyDomain(api, AUTHORIZER_TOKEN, "set",
                Map.of("requestdomain", List.of("https://a.example.com")));

        RecordedRequest request = request();
        assertEquals("/wxa/modify_domain?access_token=" + AUTHORIZER_TOKEN, request.getPath());
        JsonNode body = bodyOf(request);
        assertEquals("set", body.path("action").asText());
        assertEquals("https://a.example.com", body.path("requestdomain").get(0).asText());
    }

    @Test
    @DisplayName("类目接口在 /cgi-bin/wxopen/ 下")
    void categoryPathsAreUnderWxopen() throws Exception {
        ok("{\"errcode\":0,\"category_list\":[]}");

        MiniProgramApi.addCategory(api, AUTHORIZER_TOKEN, List.of(Map.of("first", 1, "second", 2)));

        assertEquals("/cgi-bin/wxopen/addcategory?access_token=" + AUTHORIZER_TOKEN, request().getPath());
    }

    @Test
    @DisplayName("备案状态是 GET + authorizer token，状态藏在 info.status 里")
    void icpEntranceInfoIsAuthorizerGet() throws Exception {
        ok("{\"errcode\":0,\"info\":{\"status\":1026,\"available\":1}}");

        JsonNode response = MiniProgramApi.icpEntranceInfo(api, AUTHORIZER_TOKEN);

        RecordedRequest request = request();
        assertEquals("GET", request.getMethod());
        assertEquals("/wxa/icp/get_icp_entrance_info?access_token=" + AUTHORIZER_TOKEN, request.getPath());
        assertEquals(1026, response.path("info").path("status").asInt());
    }

    @Test
    @DisplayName("保存隐私指引：中文用途原样送出，不做 unicode 转义")
    void setPrivacySettingKeepsChineseLiteral() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        MiniProgramApi.setPrivacySetting(api, AUTHORIZER_TOKEN, 2,
                List.of(Map.of("privacy_key", "Location", "privacy_text", "用于就近推荐活动场地")),
                Map.of("notice_method", "弹窗提示"));

        RecordedRequest request = request();
        assertEquals("/cgi-bin/component/setprivacysetting?access_token=" + AUTHORIZER_TOKEN, request.getPath());
        String raw = request.getBody().readUtf8();
        assertTrue(raw.contains("用于就近推荐活动场地"),
                "中文被转义成 \\uXXXX 后小程序端会显示乱码");
        JsonNode body = objectMapper.readTree(raw);
        assertEquals("弹窗提示", body.path("owner_setting").path("notice_method").asText());
        assertEquals("用于就近推荐活动场地", body.path("setting_list").get(0).path("privacy_text").asText());
    }

    @Test
    @DisplayName("查询隐私指引：不传 privacy_ver 就不带该字段")
    void getPrivacySettingOmitsVer() throws Exception {
        ok("{\"errcode\":0,\"code_exist\":1,\"privacy_list\":[\"Location\"]}");

        MiniProgramApi.privacySetting(api, AUTHORIZER_TOKEN, null);

        RecordedRequest request = request();
        assertEquals("/cgi-bin/component/getprivacysetting?access_token=" + AUTHORIZER_TOKEN, request.getPath());
        assertTrue(bodyOf(request).path("privacy_ver").isMissingNode());
    }

    @Test
    @DisplayName("业务域名：写入只带 action，读取走 get_effective_webviewdomain")
    void webviewDomainContract() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        MiniProgramApi.setWebviewDomain(api, AUTHORIZER_TOKEN, "add", List.of("https://m.example.org"));

        RecordedRequest write = request();
        assertEquals("/wxa/setwebviewdomain", pathOnly(write));
        JsonNode writeBody = bodyOf(write);
        assertEquals("add", writeBody.path("action").asText());
        assertEquals("https://m.example.org", writeBody.path("webviewdomain").get(0).asText());

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        MiniProgramApi.setWebviewDomain(api, AUTHORIZER_TOKEN, "add", null);
        assertTrue(bodyOf(request()).path("webviewdomain").isMissingNode(),
                "空数组会覆盖掉全部业务域名，没有列表时不该带该字段");

        ok("{\"errcode\":0,\"mp_webviewdomain\":[],\"third_webviewdomain\":[\"https://m.example.org\"],"
                + "\"direct_webviewdomain\":[],\"effective_webviewdomain\":[]}");
        MiniProgramApi.effectiveWebviewDomains(api, AUTHORIZER_TOKEN);

        RecordedRequest read = request();
        assertEquals("/wxa/get_effective_webviewdomain", pathOnly(read));
        assertEquals(0, bodyOf(read).size());
    }

    @Test
    @DisplayName("草稿转模板走 component token，draft_id 是数字、字段名是 template_type")
    void addToTemplate() throws Exception {
        ok("{\"errcode\":0,\"template_id\":\"TPL9\"}");

        JsonNode response = MiniProgramApi.addToTemplate(api, COMPONENT_TOKEN, 7L, 0);

        RecordedRequest request = request();
        assertEquals("/wxa/addtotemplate", pathOnly(request));
        assertTrue(request.getPath().contains("component_access_token=" + COMPONENT_TOKEN));
        JsonNode body = bodyOf(request);
        assertTrue(body.path("draft_id").isNumber(), "draft_id 必须是 number，实际：" + body);
        assertEquals(7, body.path("draft_id").asInt());
        assertEquals(0, body.path("template_type").asInt());
        assertEquals("TPL9", response.path("template_id").asText());
    }

    @Test
    @DisplayName("草稿只有查询与转模板接口，微信没有删除草稿接口")
    void draftListIsReadOnly() throws Exception {
        ok("{\"errcode\":0,\"draft_list\":[{\"draft_id\":\"D1\",\"user_version\":\"1.0.0\"}]}");

        MiniProgramApi.templateDraftList(api, COMPONENT_TOKEN);

        RecordedRequest request = request();
        assertEquals("GET", request.getMethod());
        assertEquals("/wxa/gettemplatedraftlist", pathOnly(request));
        assertTrue(request.getPath().contains("component_access_token=" + COMPONENT_TOKEN));
        assertFalse(MiniProgramApi.PATH_INDEX.containsKey("deletedraft"), "不存在删除草稿接口");
    }

    @Test
    @DisplayName("模板列表不带 template_type：带上就只返回该类型，普通模板会被筛掉")
    void templateListOmitsTypeFilter() throws Exception {
        ok("{\"errcode\":0,\"template_list\":[{\"template_id\":\"T1\",\"template_type\":0},"
                + "{\"template_id\":\"T2\",\"template_type\":1}]}");

        JsonNode response = MiniProgramApi.templateList(api, COMPONENT_TOKEN);

        RecordedRequest request = request();
        assertEquals("GET", request.getMethod());
        assertEquals("/wxa/gettemplatelist", pathOnly(request));
        assertTrue(request.getPath().contains("component_access_token=" + COMPONENT_TOKEN));
        assertFalse(request.getPath().contains("template_type"), "不传才返回全部模板：" + request.getPath());
        assertEquals(2, response.path("template_list").size());
    }

    @Test
    @DisplayName("体验版二维码返回二进制，出错时按 JSON 转成异常")
    void experienceQrcodeBinary() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(new byte[] {(byte) 0x89, 'P', 'N', 'G'})));

        byte[] image = MiniProgramApi.experienceQrcode(api, AUTHORIZER_TOKEN, "pages/index/index");

        RecordedRequest request = request();
        assertEquals("GET", request.getMethod());
        assertEquals("/wxa/get_qrcode", pathOnly(request));
        assertTrue(request.getPath().contains("path=pages%2Findex%2Findex"), request.getPath());
        assertEquals(4, image.length);

        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"errcode\":85017,\"errmsg\":\"参数错误\"}"));
        WechatApiException ex = assertThrows(WechatApiException.class,
                () -> MiniProgramApi.experienceQrcode(api, AUTHORIZER_TOKEN, null));
        assertEquals(85017, ex.getErrcode());
    }

    @Test
    @DisplayName("errcode 非 0 统一转异常：已知码给中文指引，未知码转述微信原文")
    void errcodeIsTurnedIntoExceptionWithApiPath() throws Exception {
        ok("{\"errcode\":48001,\"errmsg\":\"api unauthorized\"}");

        WechatApiException ex = assertThrows(WechatApiException.class,
                () -> MiniProgramApi.pageList(api, AUTHORIZER_TOKEN));

        assertEquals(48001, ex.getErrcode());
        assertEquals("/wxa/get_page", ex.getApiPath());
        // 微信原文多是英文短句，界面要能看出下一步做什么；原文只留在日志里
        assertEquals("该小程序未授权对应权限集", ex.getErrmsg());

        ok("{\"errcode\":85044,\"errmsg\":\"review unavailable\"}");
        WechatApiException unknown = assertThrows(WechatApiException.class,
                () -> MiniProgramApi.pageList(api, AUTHORIZER_TOKEN));
        assertEquals("review unavailable", unknown.getErrmsg());
    }

    @Test
    @DisplayName("令牌失效错误码决定是否刷新重试")
    void tokenInvalidCodes() {
        assertTrue(new WechatApiException(42001, "", "/x").isTokenInvalid());
        assertTrue(new WechatApiException(61023, "", "/x").isTokenInvalid());
        assertTrue(new WechatApiException(61024, "", "/x").isTokenInvalid());
        assertTrue(new WechatApiException(40001, "", "/x").isTokenInvalid());
        assertTrue(new WechatApiException(61005, "", "/x").isTokenInvalid(), "component token 失效同样要重试");
        assertFalse(new WechatApiException(48001, "", "/x").isTokenInvalid());
    }

    @Test
    @DisplayName("未返回 errcode 的接口也当作成功")
    void emptyBodyIsOk() throws Exception {
        ok("{}");

        assertEquals(0, MiniProgramApi.release(api, AUTHORIZER_TOKEN).path("errcode").asInt());
    }
}
