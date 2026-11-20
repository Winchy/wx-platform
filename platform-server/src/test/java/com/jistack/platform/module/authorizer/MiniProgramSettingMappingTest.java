package com.jistack.platform.module.authorizer;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.service.AuthorizerWriter;
import com.jistack.platform.wechat.service.WechatCaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置接口的响应映射：微信回下划线字段、前端要驼峰字段，这一层翻译写错只会表现为界面空白。
 * 这里让 WechatCaller 真的打到 MockWebServer，请求体与响应映射一次验完，不依赖数据库。
 */
@DisplayName("小程序配置：微信字段与界面字段的映射")
class MiniProgramSettingMappingTest {

    private static final String APPID = "wxauthorizer000001";
    private static final String TOKEN = "AUTHORIZER_ACCESS_TOKEN";

    private final ObjectMapper json = new ObjectMapper();

    private MockWebServer server;
    private MiniProgramSettingService service;
    private AuthorizerWriter writer;
    private AuthorizerQrcodeMapper qrcodeMapper;
    /** 本地副本表的内存替身：只用来验「命中副本就不打微信」「取图成功后覆盖」「失败时回退」。 */
    private final Map<String, AuthorizerQrcode> qrcodeRows = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // 与生产 WechatClientFactory 同型的工厂 + 短读超时：裸 new RestTemplate() 没有超时，
        // 桩不回响应时用例只会永久阻塞（jstack 里卡在复用连接的 getResponseCode）。
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(2000);
        http.setReadTimeout(3000);
        WechatApiClient api = new WechatApiClient(server.url("/").toString(), new RestTemplate(http), json);

        WechatCaller caller = mock(WechatCaller.class);
        when(caller.authorizer(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WechatCaller.AuthCall call = invocation.getArgument(2, WechatCaller.AuthCall.class);
            return call.apply(api, TOKEN);
        });
        // 小程序码代取走 caller.api()，不经令牌链
        when(caller.api()).thenReturn(api);
        writer = mock(AuthorizerWriter.class);
        when(writer.require(anyString())).thenReturn(new Authorizer());
        // 取图失败会先「刷新资料」再重试一次；默认换不出新链接，让首因原样抛出
        when(writer.refreshInfo(anyString())).thenReturn(new Authorizer());
        qrcodeMapper = mock(AuthorizerQrcodeMapper.class);
        when(qrcodeMapper.selectById(any())).thenAnswer(invocation -> qrcodeRows.get(invocation.getArgument(0)));
        when(qrcodeMapper.insert(any(AuthorizerQrcode.class))).thenAnswer(invocation -> saveRow(invocation.getArgument(0)));
        when(qrcodeMapper.updateById(any(AuthorizerQrcode.class))).thenAnswer(invocation -> saveRow(invocation.getArgument(0)));
        service = new MiniProgramSettingService(caller, writer, qrcodeMapper);
    }

    private int saveRow(AuthorizerQrcode entity) {
        qrcodeRows.put(entity.getAuthorizerAppid(), entity);
        return 1;
    }

    /** 库里已有一份后端保存的小程序码。 */
    private AuthorizerQrcode savedRow(String sourceUrl, LocalDateTime syncedAt) {
        AuthorizerQrcode saved = new AuthorizerQrcode();
        saved.setAuthorizerAppid(APPID);
        saved.setSourceUrl(sourceUrl);
        saved.setContentType("image/png");
        saved.setImage(pngBytes());
        saved.setSyncedAt(syncedAt);
        return saved;
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void ok(String body) {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body));
    }

    private RecordedRequest take() throws Exception {
        return server.takeRequest();
    }

    /** 无参的 takeRequest() 等不到请求会一直阻塞，整轮测试跟着挂死；这里超时即失败。 */
    private RecordedRequest takeRequest() throws Exception {
        RecordedRequest request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).as("桩在 5 秒内没等到请求").isNotNull();
        return request;
    }

    private JsonNode requestBody() throws Exception {
        return json.readTree(take().getBody().readUtf8());
    }

    /** setAvatar 认文件头不认文件名，头像用例统一用这段带 PNG 签名的字节。 */
    private byte[] pngBytes() {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] body = "fake-png-body".getBytes();
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    /** 小程序码直链落在 wechat_authorizer.qrcode_url 上，这里指到本机桩，主机与 apiBase 一致才允许抓。 */
    private Authorizer withQrcodeLink(String path) {
        Authorizer authorizer = new Authorizer();
        authorizer.setQrcodeUrl(server.url(path).toString());
        when(writer.require(anyString())).thenReturn(authorizer);
        return authorizer;
    }

    private void imageResponse(byte[] bytes, String contentType) {
        server.enqueue(new MockResponse().addHeader("Content-Type", contentType)
                .setBody(new okio.Buffer().write(bytes)));
    }

    @Test
    @DisplayName("基本信息：按文档取 signature_info / head_image_info / nickname_info")
    void basicInfoReadsNestedDocumentedFields() throws Exception {
        ok("{\"errcode\":0,\"appid\":\"" + APPID + "\",\"account_type\":3,\"principal_type\":1,"
                + "\"principal_name\":\"某某行业协会\",\"realname_status\":1,"
                + "\"wx_verify_info\":{\"qualification_verify\":true,\"naming_verify\":false},"
                + "\"signature_info\":{\"signature\":\"服务协会会员\",\"modify_used_count\":1,\"modify_quota\":5},"
                + "\"head_image_info\":{\"head_image_url\":\"https://img/head.png\",\"modify_used_count\":0,\"modify_quota\":5},"
                + "\"nickname\":\"根节点名称\",\"nickname_info\":{\"nickname\":\"协会会员服务\",\"modify_quota\":2},"
                + "\"customer_type\":1}");

        Map<String, Object> info = service.basicInfo(APPID);

        assertThat(take().getPath()).startsWith("/cgi-bin/account/getaccountbasicinfo");
        assertThat(info.get("nickname")).isEqualTo("协会会员服务");
        assertThat(info.get("signature")).isEqualTo("服务协会会员");
        assertThat(info.get("avatarUrl")).isEqualTo("https://img/head.png");
        assertThat(info.get("principalName")).isEqualTo("某某行业协会");
        assertThat(info.get("openVerify")).isEqualTo(1);
        // 这个接口本身不返回小程序码与 gh_ 原始 ID，界面要靠授权方信息记录兜底（见下一个用例）
        assertThat(info.get("qrCodeUrl")).isNull();
        assertThat(info.get("userName")).isNull();
        assertThat(info.get("openScannedSearch")).isEqualTo(-1);
        assertThat(((JsonNode) info.get("raw")).path("signature_info").path("modify_quota").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("基本信息：老版平铺字段可用，微信未返回时用授权方记录兜底")
    void basicInfoFallsBackToLegacyAndAuthorizerRecord() throws Exception {
        Authorizer stored = new Authorizer();
        stored.setNickName("_mock_协会小程序");
        stored.setHeadImg("https://mock.example/avatar.png");
        stored.setQrcodeUrl("https://mock.example/qr.png");
        stored.setOriginalUsername("gh_xzmock");
        stored.setPrincipalName("某某行业协会");
        stored.setVerifyInfo("落库的功能简介");
        stored.setVerifyType(-1);
        when(writer.require(APPID)).thenReturn(stored);

        ok("{\"errcode\":0,\"nick_name\":\"老版名称\",\"signature\":\"老版简介\","
                + "\"head_img\":\"https://img/legacy.png\",\"qr_code_url\":\"https://qr/1.png\","
                + "\"user_name\":\"gh_123456789abc\",\"open_verify\":1,\"open_scanned_search\":0}");
        Map<String, Object> legacy = service.basicInfo(APPID);
        take();

        assertThat(legacy.get("nickname")).isEqualTo("老版名称");
        assertThat(legacy.get("signature")).isEqualTo("老版简介");
        assertThat(legacy.get("avatarUrl")).isEqualTo("https://img/legacy.png");
        assertThat(legacy.get("qrCodeUrl")).isEqualTo("https://qr/1.png");
        assertThat(legacy.get("userName")).isEqualTo("gh_123456789abc");
        assertThat(legacy.get("openVerify")).isEqualTo(1);
        assertThat(legacy.get("openScannedSearch")).isEqualTo(0);

        // 字段显式为 null 时不能显示成 "null"，要落到落库记录；verify_type -1 是未认证而非未返回
        ok("{\"errcode\":0,\"nickname_info\":{\"nickname\":null}}");
        Map<String, Object> blank = service.basicInfo(APPID);

        assertThat(blank.get("nickname")).isEqualTo("_mock_协会小程序");
        assertThat(blank.get("signature")).isEqualTo("落库的功能简介");
        assertThat(blank.get("avatarUrl")).isEqualTo("https://mock.example/avatar.png");
        assertThat(blank.get("qrCodeUrl")).isEqualTo("https://mock.example/qr.png");
        assertThat(blank.get("userName")).isEqualTo("gh_xzmock");
        assertThat(blank.get("principalName")).isEqualTo("某某行业协会");
        assertThat(blank.get("openVerify")).isEqualTo(0);
    }

    @Test
    @DisplayName("服务器域名：六类域名按驼峰返回，无效域名与未备案域名归到 invalid")
    void readDomainsMapsSnakeToCamel() throws Exception {
        ok("{\"errcode\":0,\"requestdomain\":[\"https://api.example.org\"],"
                + "\"wsrequestdomain\":[\"wss://ws.example.org\"],"
                + "\"uploaddomain\":[\"https://up.example.org\"],"
                + "\"downloaddomain\":[],\"udpdomain\":[],\"tcpdomain\":[],"
                + "\"invalid_requestdomain\":[\"https://bad.example.org\"],"
                + "\"no_icp_domain\":[\"https://noicp.example.org\"]}");
        ok("{\"errcode\":0,\"mp_webviewdomain\":[\"https://m.example.org\"],\"third_webviewdomain\":[],\"direct_webviewdomain\":[]}");

        Map<String, Object> domains = service.readDomains(APPID);

        assertThat(domains.get("request")).isEqualTo(List.of("https://api.example.org"));
        assertThat(domains.get("wsRequest")).isEqualTo(List.of("wss://ws.example.org"));
        assertThat(domains.get("uploadFile")).isEqualTo(List.of("https://up.example.org"));
        assertThat(domains.get("downloadFile")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> invalid = (Map<String, Object>) domains.get("invalid");
        assertThat(String.valueOf(invalid.get("request"))).contains("bad.example.org");
        assertThat(String.valueOf(invalid.get("noIcp"))).contains("noicp.example.org");
        assertThat(domains.get("webview")).isEqualTo(List.of("https://m.example.org"));
    }

    @Test
    @DisplayName("业务域名按来源分三份列表，读取时合并去重")
    void webviewDomainsMergeSources() throws Exception {
        ok("{\"errcode\":0,\"mp_webviewdomain\":[\"https://a.example.org\"],"
                + "\"third_webviewdomain\":[\"https://b.example.org\",\"https://a.example.org\"],"
                + "\"direct_webviewdomain\":[]}");

        assertThat(service.readWebviewDomains(APPID))
                .isEqualTo(List.of("https://a.example.org", "https://b.example.org"));
        assertThat(take().getPath()).startsWith("/wxa/get_effective_webviewdomain");
    }

    @Test
    @DisplayName("前端传驼峰域名类型，发给微信要换成无下划线的原字段名")
    void updateDomainsTranslatesType() throws Exception {
        ok("{\"errcode\":0,\"wsrequestdomain\":[\"wss://ws.example.org\"]}");

        Map<String, Object> result = service.updateDomains(APPID, "set", "wsRequest",
                List.of("wss://ws.example.org"));

        JsonNode body = requestBody();
        assertThat(body.path("action").asText()).isEqualTo("set");
        assertThat(body.path("wsrequestdomain").get(0).asText()).isEqualTo("wss://ws.example.org");
        assertThat(body.has("ws_requestdomain")).isFalse();
        assertThat(result.get("wsRequest")).isEqualTo(List.of("wss://ws.example.org"));
    }

    @Test
    @DisplayName("域名参数不合法时本地拦住，不占用微信的每日修改次数")
    void updateDomainsValidates() {
        assertThatThrownBy(() -> service.updateDomains(APPID, "set", "ftpdomain", List.of("https://a")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("域名类型不合法");

        assertThatThrownBy(() -> service.updateDomains(APPID, "remove", "request", List.of("https://a")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("add/delete/set");

        assertThatThrownBy(() -> service.updateDomains(APPID, "set", "request", List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("改头像两步走：先走新增临时素材拿 media_id，再带比例裁剪框提交")
    void setAvatarUploadsThenCommits() throws Exception {
        ok("{\"errcode\":0,\"media_id\":\"MEDIA_AVATAR\"}");
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        service.setAvatar(APPID, pngBytes(), null, 0.0, 0.0, 0.42, 0.42);

        RecordedRequest upload = take();
        // 文档要求头像素材来自新增临时素材：uploadmedia 拿到的 id 会被 modifyheadimage 判 40007
        assertThat(upload.getPath()).startsWith("/cgi-bin/media/upload");
        assertThat(upload.getPath()).contains("type=image");
        assertThat(upload.getBody().readUtf8()).contains("avatar.png");

        JsonNode body = json.readTree(take().getBody().readUtf8());
        assertThat(body.path("head_img_media_id").asText()).isEqualTo("MEDIA_AVATAR");
        // 微信要 [0,1] 的字符串坐标，数字或像素值都会被拒
        assertThat(body.path("x1").asText()).isEqualTo("0");
        assertThat(body.path("x1").isTextual()).isTrue();
        assertThat(body.path("x2").asText()).isEqualTo("0.42");
        assertThat(body.path("y2").asText()).isEqualTo("0.42");
    }

    @Test
    @DisplayName("multipart 出站必须带 Content-Length：chunked 会被微信中途断连")
    void multipartUploadSendsContentLength() throws Exception {
        ok("{\"errcode\":0,\"media_id\":\"MEDIA_AVATAR\"}");
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        service.setAvatar(APPID, pngBytes(), null, null, null, null, null);

        RecordedRequest upload = take();
        // MockWebServer 对 chunked 和 Content-Length 都能收，所以只能从请求头断言：
        // Spring 的 MultiValueMap 写法实测发 Transfer-Encoding: chunked，微信直接中途断连接。
        assertThat(upload.getHeader("Transfer-Encoding")).isNull();
        assertThat(upload.getHeader("Content-Length")).isNotNull();
        assertThat(upload.getHeader("Content-Length")).isEqualTo(String.valueOf(upload.getBodySize()));
        String contentType = upload.getHeader("Content-Type");
        assertThat(contentType).startsWith("multipart/form-data;boundary=");
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        String body = upload.getBody().readUtf8();
        assertThat(body).startsWith("--" + boundary)
                .contains("Content-Disposition: form-data; name=\"media\"; filename=\"avatar.png\"")
                .contains("Content-Type: image/png")
                .endsWith("--" + boundary + "--\r\n");

        take(); // modifyheadimage
    }

    @Test
    @DisplayName("文件名后缀骗人时按文件真实内容改名，不会把 .webp 发给微信")
    void setAvatarRenamesMismatchedExtension() throws Exception {
        ok("{\"errcode\":0,\"media_id\":\"MEDIA_AVATAR\"}");
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        service.setAvatar(APPID, pngBytes(), "logo.webp", null, null, null, null);

        String uploadBody = take().getBody().readUtf8();
        assertThat(uploadBody).contains("avatar.png");
        assertThat(uploadBody).doesNotContain("logo.webp");
        // 没给裁剪框时按整图提交，四个坐标仍是必填的字符串
        JsonNode body = json.readTree(take().getBody().readUtf8());
        assertThat(body.path("x1").asText()).isEqualTo("0");
        assertThat(body.path("y1").asText()).isEqualTo("0");
        assertThat(body.path("x2").asText()).isEqualTo("1");
        assertThat(body.path("y2").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("裁剪坐标传像素值时本地拦下，不浪费一次头像上传")
    void setAvatarRejectsPixelCrop() {
        assertThatThrownBy(() -> service.setAvatar(APPID, pngBytes(), "a.png", 0.0, 0.0, 420.0, 420.0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("0~1");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("超过 2M 或内容不是微信支持的图片：本地拦下，不发微信也不占修改额度")
    void setAvatarRejectsOversizedAndUnknownFormat() {
        byte[] png = pngBytes();
        byte[] tooBig = new byte[png.length + 2 * 1024 * 1024];
        System.arraycopy(png, 0, tooBig, 0, png.length);
        assertThatThrownBy(() -> service.setAvatar(APPID, tooBig, "big.png", null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超过 2M");

        assertThatThrownBy(() -> service.setAvatar(APPID, "RIFFxxxxWEBPvp8 ".getBytes(), "a.webp",
                null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只支持 PNG");

        assertThatThrownBy(() -> service.setAvatar(APPID, new byte[0], "a.png", null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请选择要上传的头像图片");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("上传没返回 media_id 时报错，而不是提交空 id")
    void setAvatarGuardsMediaId() {
        ok("{\"errcode\":0}");
        assertThatThrownBy(() -> service.setAvatar(APPID, pngBytes(), "a.png", 0.0, 0.0, 1.0, 1.0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("media_id");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("加类目：资质字段沿用微信文档里的 certicates 拼写，third 为空则不带")
    void addCategoryKeepsWechatSpelling() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        service.addCategory(APPID, 109, 19, null,
                List.of(Map.of("material_type", 1, "media_id", "MEDIA_LICENCE")));

        JsonNode category = requestBody().path("categories").get(0);
        assertThat(category.path("first").asInt()).isEqualTo(109);
        assertThat(category.path("second").asInt()).isEqualTo(19);
        assertThat(category.has("third")).isFalse();
        assertThat(category.path("certicates").get(0).path("media_id").asText()).isEqualTo("MEDIA_LICENCE");
        assertThat(category.has("certificates")).isFalse();
    }

    @Test
    @DisplayName("删类目：一级二级编号必填")
    void deleteCategoryRequiresLevels() {
        assertThatThrownBy(() -> service.deleteCategory(APPID, 109, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("一级与二级编号必填");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("体验成员：微信只给成员标识字符串，解绑要靠它")
    void testersUseUserStr() throws Exception {
        ok("{\"errcode\":0,\"members\":[{\"userstr\":\"u_1001\"},{\"userstr\":\"u_1002\"}]}");

        List<Map<String, Object>> members = service.testers(APPID);

        assertThat(take().getPath()).startsWith("/wxa/memberauth");
        assertThat(members).hasSize(2);
        assertThat(members.get(0).get("userstr")).isEqualTo("u_1001");
        assertThat(members.get(0).get("wechatId")).isNull();
    }

    @Test
    @DisplayName("绑定体验成员返回 userstr，微信号为空时不请求")
    void bindTesterReturnsUserStr() throws Exception {
        ok("{\"errcode\":0,\"userstr\":\"u_1001\"}");

        assertThat(service.bindTester(APPID, "collaborator_wxid")).isEqualTo("u_1001");
        assertThat(requestBody().path("wechatid").asText()).isEqualTo("collaborator_wxid");

        assertThatThrownBy(() -> service.bindTester(APPID, " "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("成员微信号不能为空");
    }

    @Test
    @DisplayName("解绑优先用 userstr，两者都空则拦住")
    void unbindTesterNeedsIdentifier() throws Exception {
        ok("{\"errcode\":0}");
        service.unbindTester(APPID, null, "u_1001");

        JsonNode body = requestBody();
        assertThat(body.path("userstr").asText()).isEqualTo("u_1001");
        assertThat(body.has("wechatid")).isFalse();

        assertThatThrownBy(() -> service.unbindTester(APPID, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("微信号或成员唯一标识");
    }

    @Test
    @DisplayName("业务域名写入后回读生效列表，空列表不发给微信")
    void webviewDomains() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        ok("{\"errcode\":0,\"mp_webviewdomain\":[],\"third_webviewdomain\":[\"https://a.example.org\"],"
                + "\"direct_webviewdomain\":[]}");

        assertThat(service.updateWebviewDomains(APPID, "add", List.of("https://a.example.org")))
                .isEqualTo(List.of("https://a.example.org"));

        RecordedRequest write = take();
        assertThat(write.getPath()).startsWith("/wxa/setwebviewdomain");
        JsonNode body = json.readTree(write.getBody().readUtf8());
        assertThat(body.path("action").asText()).isEqualTo("add");
        assertThat(body.path("webviewdomain").get(0).asText()).isEqualTo("https://a.example.org");
        assertThat(take().getPath()).startsWith("/wxa/get_effective_webviewdomain");

        assertThatThrownBy(() -> service.updateWebviewDomains(APPID, "set", List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.updateWebviewDomains(APPID, "clear", List.of("https://a.example.org")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("add/delete/set");
    }

    @Test
    @DisplayName("隐私指引按开发版提交时，setting_list 与 owner_setting 一起发给微信")
    void privacySettingPassesThrough() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        service.updatePrivacySetting(APPID, 2,
                List.of(Map.of("privacy_key", "Location", "privacy_text", "就近推荐活动场地")),
                Map.of("notice_method", "弹窗提示", "contact_email", "privacy@example.org"));

        RecordedRequest request = take();
        JsonNode body = json.readTree(request.getBody().readUtf8());
        assertThat(request.getPath()).startsWith("/cgi-bin/component/setprivacysetting");
        assertThat(body.path("privacy_ver").asInt()).isEqualTo(2);
        assertThat(body.path("setting_list").get(0).path("privacy_text").asText())
                .isEqualTo("就近推荐活动场地");
        assertThat(body.path("owner_setting").path("notice_method").asText()).isEqualTo("弹窗提示");
    }

    @Test
    @DisplayName("隐私指引读取必带 privacy_ver：不传按开发版，其他值本地拦下")
    void privacyReadPinsVersion() throws Exception {
        ok("{\"errcode\":0,\"privacy_list\":[{\"privacy_key\":\"Location\"}]}");

        service.privacySetting(APPID, null);

        assertThat(requestBody().path("privacy_ver").asInt()).isEqualTo(2);

        int sent = server.getRequestCount();
        assertThatThrownBy(() -> service.privacySetting(APPID, 3))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("1（现网版本）或 2（开发版）");
        assertThat(server.getRequestCount()).isEqualTo(sent);
    }

    @Test
    @DisplayName("改名请求体只出现文档字段：多余字段与数组值会让微信回 47001")
    void setNicknameSendsOnlyDocumentedKeys() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"wording\":\"材料已提交\",\"audit_id\":33001}");

        Map<String, Object> result = service.setNickname(APPID, "智能制造行业协会", "  ", "MEDIA_LICENSE",
                List.of("", "MEDIA_A", "MEDIA_B"));

        JsonNode body = requestBody();
        assertThat(body.path("nick_name").asText()).isEqualTo("智能制造行业协会");
        assertThat(body.path("license").asText()).isEqualTo("MEDIA_LICENSE");
        // 空行被过滤后按 naming_other_stuff_1.. 连续编号，不会留出空洞
        assertThat(body.path("naming_other_stuff_1").asText()).isEqualTo("MEDIA_A");
        assertThat(body.path("naming_other_stuff_2").asText()).isEqualTo("MEDIA_B");
        assertThat(body.has("naming_other_stuff_3")).isFalse();
        assertThat(body.has("id_card")).isFalse();
        assertThat(body.has("name_type")).isFalse();
        assertThat(body.has("enterprise_name")).isFalse();
        assertThat(body.has("credential_media_id")).isFalse();
        assertThat(body.path("license").isArray()).isFalse();
        assertThat(result).containsOnly(Map.entry("auditId", "33001"), Map.entry("wording", "材料已提交"));
    }

    @Test
    @DisplayName("名称未命中关键词时只发 nick_name，材料字段一个都不带；没有 audit_id 就是已生效")
    void setNicknameWithoutMaterialsSendsOnlyName() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"wording\":\"\"}");

        Map<String, Object> result = service.setNickname(APPID, "某某家具协会", null, "", List.of());

        assertThat(requestBody().fieldNames()).toIterable().containsExactly("nick_name");
        assertThat(result.get("auditId")).isNull();
    }

    @Test
    @DisplayName("其他证明材料超过 5 份本地拦下，不占用改名次数")
    void setNicknameCapsOtherMaterials() {
        assertThatThrownBy(() -> service.setNickname(APPID, "某某家具协会", null, null,
                List.of("M1", "M2", "M3", "M4", "M5", "M6")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多 5 份");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("普通方式注册的小程序不发起改名：微信只会回 91001 not fast register")
    void setNicknameBlockedForNormalRegister() {
        Authorizer normal = new Authorizer();
        normal.setRegisterType(0);
        when(writer.require(anyString())).thenReturn(normal);

        assertThatThrownBy(() -> service.setNickname(APPID, "某某家具协会", null, null, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("微信公众平台");
        assertThat(server.getRequestCount()).isZero();

        Authorizer byApi = new Authorizer();
        byApi.setRegisterType(6);
        when(writer.require(anyString())).thenReturn(byApi);
        assertThatThrownBy(() -> service.setNickname(APPID, "某某家具协会", null, null,
                List.of("M1", "M2", "M3", "M4", "M5", "M6")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多 5 份");
    }

    @Test
    @DisplayName("基本信息给出注册方式与能否接口改名，未刷新资料时不误判")
    void basicInfoExposesApiRenameCapability() throws Exception {
        Authorizer normal = new Authorizer();
        normal.setRegisterType(0);
        when(writer.require(anyString())).thenReturn(normal);
        ok("{\"errcode\":0,\"nickname_info\":{\"nickname\":\"协会会员服务\"}}");

        Map<String, Object> info = service.basicInfo(APPID);
        take();

        assertThat(info.get("registerType")).isEqualTo(0);
        assertThat(info.get("apiRenameSupported")).isEqualTo(false);

        // 没刷新过授权方资料时 register_type 为空，界面不能因此拦住改名
        when(writer.require(anyString())).thenReturn(new Authorizer());
        ok("{\"errcode\":0,\"nickname_info\":{\"nickname\":\"协会会员服务\"}}");
        Map<String, Object> unknown = service.basicInfo(APPID);
        take();

        assertThat(unknown.get("registerType")).isNull();
        assertThat(unknown.get("apiRenameSupported")).isNull();
    }

    @Test
    @DisplayName("名称检测把 hit_condition/wording 翻成界面字段，不把微信原始 JSON 丢给前端")
    void checkNicknameMapsToUiFields() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"hit_condition\":true,"
                + "\"wording\":\"请提供可确认主体材料与名称对应的材料\"}");

        Map<String, Object> hit = service.checkNickname(APPID, "智能制造行业协会");

        assertThat(requestBody().path("nick_name").asText()).isEqualTo("智能制造行业协会");
        assertThat(hit).containsOnly(Map.entry("hitCondition", true),
                Map.entry("wording", "请提供可确认主体材料与名称对应的材料"));

        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"hit_condition\":false,\"wording\":\"\"}");
        assertThat(service.checkNickname(APPID, "某某家具协会"))
                .containsOnly(Map.entry("hitCondition", false), Map.entry("wording", ""));
    }

    @Test
    @DisplayName("改名材料走新增临时素材：type=image 在 query 上，取回的是 media_id")
    void nicknameMaterialUsesTempMedia() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"type\":\"image\",\"media_id\":\"MEDIA_LICENSE\"}");

        assertThat(service.uploadNicknameMaterial(APPID, "license.png", new byte[]{1, 2}))
                .isEqualTo("MEDIA_LICENSE");

        RecordedRequest request = take();
        assertThat(request.getPath()).startsWith("/cgi-bin/media/upload");
        assertThat(request.getPath()).contains("type=image");
        assertThat(request.getBody().readUtf8()).contains("name=\"media\"").contains("license.png");

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        assertThatThrownBy(() -> service.uploadNicknameMaterial(APPID, "a.png", new byte[]{1}))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("media_id");
    }

    @Test
    @DisplayName("小程序码：优先用落库的 qrcode_url，取回的是 data URL 且不带 Referer")
    void qrcodeUsesStoredLink() throws Exception {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=gQG88Do");
        imageResponse(pngBytes(), "image/png");

        Map<String, Object> result = service.qrcodeImage(APPID, false);

        RecordedRequest request = take();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/cgi-bin/showqrcode?ticket=gQG88Do");
        // 微信的防盗链看 Referer：服务端这一趟不该带，带了就拿回「未经允许不可引用」占位图
        assertThat(request.getHeader("Referer")).isNull();
        assertThat(result.get("contentType")).isEqualTo("image/png");
        String dataUrl = (String) result.get("dataUrl");
        assertThat(dataUrl).startsWith("data:image/png;base64,");
        assertThat(java.util.Base64.getDecoder()
                .decode(dataUrl.substring("data:image/png;base64,".length()))).isEqualTo(pngBytes());
    }

    @Test
    @DisplayName("小程序码：库里没有链接时退回 getaccountbasicinfo 的老字段")
    void qrcodeFallsBackToAccountInfo() throws Exception {
        ok("{\"errcode\":0,\"qr_code_url\":\"" + server.url("/cgi-bin/showqrcode?ticket=OLD") + "\"}");
        imageResponse(pngBytes(), "image/png");

        assertThat(service.qrcodeImage(APPID, false).get("url")).asString().endsWith("/cgi-bin/showqrcode?ticket=OLD");

        assertThat(take().getPath()).startsWith("/cgi-bin/account/getaccountbasicinfo");
        assertThat(take().getPath()).isEqualTo("/cgi-bin/showqrcode?ticket=OLD");
    }

    @Test
    @DisplayName("小程序码：链接指向非微信域名时拒绝抓取，一个请求都不发")
    void qrcodeRejectsForeignHost() {
        Authorizer authorizer = new Authorizer();
        authorizer.setQrcodeUrl("http://169.254.169.254/latest/meta-data/");
        when(writer.require(anyString())).thenReturn(authorizer);

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是微信站点");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("小程序码：微信域名的链接必须是 https，不受 apiBase 协议影响")
    void qrcodeRequiresHttpsForWeixinHost() {
        Authorizer authorizer = new Authorizer();
        authorizer.setQrcodeUrl("http://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=X");
        when(writer.require(anyString())).thenReturn(authorizer);

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("https");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("小程序码：微信两处都没给链接时提示先刷新授权资料")
    void qrcodeWithoutAnyLink() {
        ok("{\"errcode\":0,\"nickname\":\"某某协会服务\"}");

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("刷新资料");
    }

    @Test
    @DisplayName("小程序码：取回的不是图片就点名，别把错误页当二维码显示")
    void qrcodeRejectsNonImage() {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=EXPIRED");
        server.enqueue(new MockResponse().addHeader("Content-Type", "text/html")
                .setBody("<html>ticket invalid</html>"));

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是图片")
                .hasMessageContaining("HTML 页面");
    }

    @Test
    @DisplayName("小程序码：微信回空体时说清是空内容，不是「未知格式」")
    void qrcodeRejectsEmptyBody() {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=X");
        imageResponse(new byte[0], "image/jpeg");

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("空内容");
    }

    @Test
    @DisplayName("小程序码：连接中途断开要原样抛 ResourceAccessException，好让界面看到出口网络原因")
    void qrcodeConnectionBreakStaysReadable() {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=X");
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));

        // 包成 IllegalStateException 只会显示「服务器内部错误: null」；透传才有 502 的「出口网络不通」
        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("小程序码：ticket 过期（404 空体）时自动刷新资料换新链接重试")
    void qrcodeRetriesWithFreshLink() throws Exception {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=EXPIRED");
        Authorizer fresh = new Authorizer();
        fresh.setQrcodeUrl(server.url("/cgi-bin/showqrcode?ticket=FRESH").toString());
        when(writer.refreshInfo(anyString())).thenReturn(fresh);
        // 按路径分派而不是顺序 enqueue：JDK 的连接池按 host:port 复用，桩端口又每例重分配，
        // 顺序队列会被多消耗一次，第二次请求就永远等不到响应。
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("ticket=FRESH")) {
                    return new MockResponse().addHeader("Content-Type", "image/png")
                            .setBody(new okio.Buffer().write(pngBytes()));
                }
                return new MockResponse().setResponseCode(404).setHeader("Connection", "close");
            }
        });

        Map<String, Object> result = service.qrcodeImage(APPID, false);

        assertThat(takeRequest().getPath()).isEqualTo("/cgi-bin/showqrcode?ticket=EXPIRED");
        assertThat(takeRequest().getPath()).isEqualTo("/cgi-bin/showqrcode?ticket=FRESH");
        // 界面「复制图片链接」拿到的必须是实际取到图的那条，否则复制出去的还是过期 ticket
        assertThat(result.get("url")).asString().endsWith("ticket=FRESH");
        assertThat(result.get("dataUrl")).asString().startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("小程序码：刷新后链接没变就抛首因，不再发第二次取图请求")
    void qrcodeKeepsFirstErrorWhenLinkUnchanged() throws Exception {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=EXPIRED");
        // 错误状态一律关连接：JDK 连接池按 host:port 复用，桩每例换端口却可能拿到同一个号
        server.enqueue(new MockResponse().setResponseCode(404).setHeader("Connection", "close"));

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("404");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("小程序码：微信回 4xx 时带上状态码与错误体，不留「[no body]」")
    void qrcodeReportsHttpErrorBody() {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=X");
        server.enqueue(new MockResponse().setResponseCode(403)
                .setHeader("Connection", "close")
                .addHeader("Content-Type", "text/html")
                .setBody("<html>access denied</html>"));

        assertThatThrownBy(() -> service.qrcodeImage(APPID, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("access denied");
    }

    @Test
    @DisplayName("小程序码：已有本地副本时直接返回，一个微信请求都不发")
    void qrcodeServesSavedCopyWithoutFetching() {
        String link = server.url("/cgi-bin/showqrcode?ticket=X").toString();
        withQrcodeLink("/cgi-bin/showqrcode?ticket=X");
        qrcodeRows.put(APPID, savedRow(link, LocalDateTime.now()));

        Map<String, Object> result = service.qrcodeImage(APPID, false);

        assertThat(result.get("source")).isEqualTo("local");
        assertThat(result.get("staleReason")).isNull();
        assertThat(result.get("dataUrl")).asString().startsWith("data:image/png;base64,");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("小程序码：取图成功后原图连链接一起存下来")
    void qrcodeSavesFetchedImage() {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=NEW");
        imageResponse(pngBytes(), "image/png");

        assertThat(service.qrcodeImage(APPID, false).get("source")).isEqualTo("wechat");

        AuthorizerQrcode saved = qrcodeRows.get(APPID);
        assertThat(saved).isNotNull();
        assertThat(saved.getImage()).isEqualTo(pngBytes());
        assertThat(saved.getSourceUrl()).isEqualTo(server.url("/cgi-bin/showqrcode?ticket=NEW").toString());
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("小程序码：点「重新取图」强制刷新并覆盖本地副本")
    void qrcodeRefreshOverwritesSavedCopy() {
        String link = server.url("/cgi-bin/showqrcode?ticket=X").toString();
        withQrcodeLink("/cgi-bin/showqrcode?ticket=X");
        AuthorizerQrcode stale = savedRow(link, LocalDateTime.now());
        stale.setImage(new byte[] {'o', 'l', 'd'});
        qrcodeRows.put(APPID, stale);
        imageResponse(pngBytes(), "image/png");

        Map<String, Object> result = service.qrcodeImage(APPID, true);

        assertThat(result.get("source")).isEqualTo("wechat");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(qrcodeRows.get(APPID).getImage()).isEqualTo(pngBytes());
    }

    @Test
    @DisplayName("小程序码：微信换了码或副本放久了都要重新取")
    void qrcodeRefetchesWhenLinkChangedOrExpired() {
        withQrcodeLink("/cgi-bin/showqrcode?ticket=NEW");
        qrcodeRows.put(APPID, savedRow(server.url("/cgi-bin/showqrcode?ticket=OLD").toString(),
                LocalDateTime.now().minusDays(1)));
        imageResponse(pngBytes(), "image/png");
        assertThat(service.qrcodeImage(APPID, false).get("source")).isEqualTo("wechat");

        // 链接没变但副本已超过 7 天：同样重新取，避免长期显示旧图
        qrcodeRows.clear();
        withQrcodeLink("/cgi-bin/showqrcode?ticket=NEW");
        qrcodeRows.put(APPID, savedRow(server.url("/cgi-bin/showqrcode?ticket=NEW").toString(),
                LocalDateTime.now().minusDays(8)));
        imageResponse(pngBytes(), "image/png");
        assertThat(service.qrcodeImage(APPID, false).get("source")).isEqualTo("wechat");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("小程序码：微信一时取不到就回退本地副本，界面不整块空白")
    void qrcodeFallsBackToSavedCopyOnFailure() {
        String link = server.url("/cgi-bin/showqrcode?ticket=X").toString();
        withQrcodeLink("/cgi-bin/showqrcode?ticket=X");
        qrcodeRows.put(APPID, savedRow(link, LocalDateTime.now().minusDays(8)));
        server.enqueue(new MockResponse().setResponseCode(404).setHeader("Connection", "close"));

        Map<String, Object> result = service.qrcodeImage(APPID, false);

        assertThat(result.get("source")).isEqualTo("local");
        assertThat(result.get("dataUrl")).asString().startsWith("data:image/png;base64,");
        // 首因要交给界面，否则用户只看到一张旧图、不知道其实已经取不到了
        assertThat(result.get("staleReason")).asString().contains("404");
    }
}
