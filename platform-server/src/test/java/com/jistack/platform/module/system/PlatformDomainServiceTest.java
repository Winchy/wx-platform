package com.jistack.platform.module.system;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.service.WechatCaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第三方平台域名白名单的请求形状：微信在这里要的是 ; 拼接的裸主机名，
 * 而且服务器域名与业务域名字段名不同（wxa_server_domain / wxa_jump_h5_domain）。
 * 写成数组或带 https:// 都会被微信判为无效域名。
 */
@DisplayName("第三方平台域名白名单：主机名规范化与 ; 拼接")
class PlatformDomainServiceTest {

    private static final String TOKEN = "COMPONENT_ACCESS_TOKEN";
    private static final String SERVER_PAYLOAD = """
            {"errcode":0,"errmsg":"ok",
             "published_wxa_server_domain":"api.mock.example;h5.mock.example",
             "testing_wxa_server_domain":"api.mock.example",
             "invalid_wxa_server_domain":"bad.mock.example"}
            """;
    private static final String JUMP_PAYLOAD = """
            {"errcode":0,"errmsg":"ok",
             "published_wxa_jump_h5_domain":"h5.mock.example",
             "testing_wxa_jump_h5_domain":"webview.mock.example"}
            """;

    private final ObjectMapper json = new ObjectMapper();

    private MockWebServer server;
    private PlatformDomainService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WechatApiClient api = new WechatApiClient(server.url("/").toString(), new RestTemplate(), json);

        WechatCaller caller = mock(WechatCaller.class);
        when(caller.component(anyString(), any())).thenAnswer(invocation -> {
            WechatCaller.ComponentCall call = invocation.getArgument(1, WechatCaller.ComponentCall.class);
            return call.apply(api, TOKEN);
        });
        service = new PlatformDomainService(caller);
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

    private JsonNode bodyOf(RecordedRequest request) throws Exception {
        return json.readTree(request.getBody().readUtf8());
    }

    @Test
    @DisplayName("读取用 action=get，且不带域名字段")
    void readUsesGetWithoutDomainField() throws Exception {
        ok(SERVER_PAYLOAD);

        Map<String, Object> result = service.read("server");

        RecordedRequest request = take();
        assertThat(request.getPath()).startsWith("/cgi-bin/component/modify_wxa_server_domain");
        JsonNode body = bodyOf(request);
        assertThat(body.path("wxa_server_domain").isMissingNode()).isTrue();
        assertThat(body.path("action").asText()).isEqualTo("get");
        assertThat(result).containsEntry("kind", "server")
                .containsEntry("published", List.of("api.mock.example", "h5.mock.example"))
                .containsEntry("testing", List.of("api.mock.example"))
                .containsEntry("invalid", List.of("bad.mock.example"))
                .containsEntry("limit", 1000);
    }

    @Test
    @DisplayName("业务域名用 wxa_jump_h5_domain，域名去掉协议与路径后以 ; 拼接")
    void jumpDomainsUseTheirOwnField() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        ok(JUMP_PAYLOAD);

        service.update("jump", "add", List.of("https://h5.mock.example/activity/", "h5.mock.example"), null);

        RecordedRequest write = take();
        assertThat(write.getPath()).startsWith("/cgi-bin/component/modify_wxa_jump_domain");
        JsonNode body = bodyOf(write);
        assertThat(body.path("action").asText()).isEqualTo("add");
        assertThat(body.path("wxa_jump_h5_domain").asText()).isEqualTo("h5.mock.example");
        assertThat(body.path("is_modify_published_together").isMissingNode()).isTrue();
        assertThat(body.has("wxa_server_domain")).isFalse();

        JsonNode read = bodyOf(take());
        assertThat(read.path("action").asText()).isEqualTo("get");
    }

    @Test
    @DisplayName("写接口不回清单，因此保存后必须再读一次")
    void reReadsAfterWrite() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        ok(SERVER_PAYLOAD);

        Map<String, Object> result = service.update("server", "set", List.of("Api.Mock.example"), Boolean.FALSE);

        assertThat(result).containsEntry("published", List.of("api.mock.example", "h5.mock.example"));
        assertThat(bodyOf(take()).path("wxa_server_domain").asText()).isEqualTo("Api.Mock.example");
        assertThat(bodyOf(take()).path("is_modify_published_together").asBoolean()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("端口与空列表在本地拦住：微信会判成无效域名")
    void rejectsPortAndEmptyList() {
        assertThatThrownBy(() -> service.update("server", "set", List.of("api.mock.example:8443"), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("端口");
        assertThatThrownBy(() -> service.update("server", "delete", List.of("  "), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("kind 与 action 的取值范围")
    void rejectsUnknownKindAndAction() {
        assertThatThrownBy(() -> service.read("webview"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("server（服务器域名）或 jump");
        assertThatThrownBy(() -> service.update("server", "get", List.of("api.mock.example"), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("add/delete/set");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("业务域名上限 300 个")
    void enforcesJumpDomainLimit() {
        List<String> hosts = IntStream.range(0, 301).mapToObj(i -> "d" + i + ".mock.example").toList();

        assertThatThrownBy(() -> service.update("jump", "set", hosts, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限 300");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("平台级校验文件：下划线字段翻成驼峰")
    void mapsConfirmFile() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\",\"file_name\":\"mock-confirm.txt\",\"file_content\":\"abc123\"}");

        Map<String, Object> result = service.confirmFile();

        assertThat(result).containsEntry("fileName", "mock-confirm.txt").containsEntry("fileContent", "abc123");
        assertThat(take().getPath()).startsWith("/cgi-bin/component/get_domain_confirmfile");
    }
}
