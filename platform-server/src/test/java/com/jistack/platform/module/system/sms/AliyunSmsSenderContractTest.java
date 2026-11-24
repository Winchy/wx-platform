package com.jistack.platform.module.system.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jistack.platform.common.config.SmsProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SendSms 的线上形状：form-urlencoded 请求体、公共参数齐全、签名可被对端按同样规则复算，
 * 以及 Code!=OK 与 4xx 都要按响应体里的 Code 判。
 *
 * <p>响应形状取自真实探针（见 README §5）：阿里云把参数/凭据类错误放在 400/404 状态码上，
 * 响应体仍是同一份 {Code,Message,RequestId,...} JSON，所以只看 HTTP 状态会漏掉全部业务错误。
 */
class AliyunSmsSenderContractTest {

    private static final String SECRET = "ExampleSecretKeyForVector";

    private MockWebServer server;
    private ObjectMapper json;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        json = new ObjectMapper();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("发出的请求：表单编码、带 Content-Length、参数齐全且签名能按同一规则复算")
    void sendsSignedFormBody() throws Exception {
        serve(200, "{\"Code\":\"OK\",\"Message\":\"OK\",\"BizId\":\"9006197469364984001\",\"RequestId\":\"F655A8D5\"}");

        assertThatCode(() -> sender().send("13800138000", "123456")).doesNotThrowAnyException();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/");
        assertThat(request.getHeader("Content-Type")).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        String body = request.getBody().readUtf8();
        assertThat(request.getHeader("Content-Length")).isEqualTo(String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));

        Map<String, String> params = parseForm(body);
        assertThat(params)
                .containsEntry("Action", "SendSms")
                .containsEntry("Version", "2017-05-25")
                .containsEntry("Format", "JSON")
                .containsEntry("RegionId", "cn-hangzhou")
                .containsEntry("AccessKeyId", "LTAI5tExampleKeyId")
                .containsEntry("SignatureMethod", "HMAC-SHA1")
                .containsEntry("SignatureVersion", "1.0")
                .containsEntry("PhoneNumbers", "13800138000")
                .containsEntry("SignName", "极栈科技")
                .containsEntry("TemplateCode", "SMS_200000000")
                .containsEntry("TemplateParam", "{\"code\":\"123456\"}");
        // 时间戳是 UTC 的 yyyy-MM-dd'T'HH:mm:ss'Z'，随机数是一次性的 UUID
        assertThat(params.get("Timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(params.get("SignatureNonce")).matches("[0-9a-fA-F-]{36}");

        String sentSignature = params.remove("Signature");
        assertThat(sentSignature).isNotBlank();
        // 对端只拿到这份表单，复算出的签名必须与带来的相等——等价于「阿里云那边能验过」
        assertThat(AliyunSmsSigner.signature(new LinkedHashMap<>(params), SECRET)).isEqualTo(sentSignature);
    }

    @Test
    @DisplayName("模板变量名可配：控制台里叫 code 还是 RandomCode 都要能对上")
    void honoursConfiguredTemplateVariableName() throws Exception {
        serve(200, "{\"Code\":\"OK\",\"Message\":\"OK\"}");

        senderWith(new SmsProperties(true, "LTAI5tExampleKeyId", SECRET, server.url("/").toString(),
                "cn-hangzhou", "极栈科技", "SMS_200000000", "RandomCode", Duration.ofSeconds(2), Duration.ofSeconds(2), null))
                .send("13800138000", "654321");

        assertThat(parseForm(server.takeRequest().getBody().readUtf8()))
                .containsEntry("TemplateParam", "{\"RandomCode\":\"654321\"}");
    }

    @Test
    @DisplayName("HTTP 200 但 Code 是 isv.*：按业务失败抛出，带上阿里云的 Code 供排障")
    void mapsBusinessFailure() {
        serve(200, "{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\",\"Message\":\"触发云通信流控限制\"}");

        assertThatThrownBy(() -> sender().send("13800138000", "123456"))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("触发云通信流控限制")
                .extracting("providerCode").isEqualTo("isv.BUSINESS_LIMIT_CONTROL");
    }

    @Test
    @DisplayName("4xx 也读响应体：404 InvalidAccessKeyId.NotFound 要说清是凭据问题")
    void mapsErrorBodyOnClientStatus() {
        server.enqueue(new MockResponse().setResponseCode(404)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"Code\":\"InvalidAccessKeyId.NotFound\",\"Message\":\"Specified access key is not found.\","
                        + "\"RequestId\":\"01A20B55\",\"HostId\":\"dysmsapi.aliyuncs.com\"}"));

        assertThatThrownBy(() -> sender().send("13800138000", "123456"))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("Specified access key is not found")
                .extracting("providerCode").isEqualTo("InvalidAccessKeyId.NotFound");
    }

    @Test
    @DisplayName("对端回的不是 JSON（代理错误页）时不崩，留一份截断原文")
    void mapsNonJsonResponse() {
        server.enqueue(new MockResponse().setResponseCode(502)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway from nginx</body></html>"));

        assertThatThrownBy(() -> sender().send("13800138000", "123456"))
                .isInstanceOf(SmsSendException.class)
                .extracting("providerCode").isEqualTo("BadResponse");
    }

    @Test
    @DisplayName("启用却没配签名/模板：本地就拦住，别把 AccessKey 问题伪装成签名错误")
    void rejectsMissingConfigBeforeAnyHttpCall() {
        SmsProperties incomplete = new SmsProperties(true, "LTAI5tExampleKeyId", SECRET,
                server.url("/").toString(), "cn-hangzhou", "", null, null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), null);

        assertThatThrownBy(() -> senderWith(incomplete).send("13800138000", "123456"))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("PLATFORM_SMS_SIGN_NAME")
                .extracting("providerCode").isEqualTo("ConfigMissing");

        assertThat(server.getRequestCount()).isZero();
    }

    private AliyunSmsSender sender() {
        return senderWith(new SmsProperties(true, "LTAI5tExampleKeyId", SECRET,
                server.url("/").toString(), "cn-hangzhou", "极栈科技", "SMS_200000000", null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), null));
    }

    private AliyunSmsSender senderWith(SmsProperties properties) {
        return new AliyunSmsSender(properties, json);
    }

    private void serve(int status, String body) {
        server.enqueue(new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new TreeMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
