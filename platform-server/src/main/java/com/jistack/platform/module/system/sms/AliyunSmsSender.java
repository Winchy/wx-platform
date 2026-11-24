package com.jistack.platform.module.system.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jistack.platform.common.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 阿里云短信 SendSms（Version 2017-05-25，RPC 风格，签名 V1）。
 *
 * <p>只在 {@code platform.sms.enabled=true} 时装载；没开时由 {@link LoggingSmsSender} 顶上，
 * 验证码依旧生成、依旧只认那一次，只是把码打在日志里给开发者自己看。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "platform.sms", name = "enabled", havingValue = "true")
public class AliyunSmsSender implements SmsSender {

    private static final String ACTION = "SendSms";
    private static final String API_VERSION = "2017-05-25";
    private static final String SUCCESS_CODE = "OK";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final SmsProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final URI endpoint;

    public AliyunSmsSender(SmsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Duration connect = orDefault(properties.connectTimeout(), Duration.ofSeconds(5));
        // 阿里云文档建议国内短信超时不低于 1 秒；30 秒是上限保护，别让界面一直转圈等运营商
        Duration read = orDefault(properties.readTimeout(), Duration.ofSeconds(10));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        this.restTemplate = new RestTemplate(factory);
        this.endpoint = URI.create(StringUtils.hasText(properties.endpoint())
                ? properties.endpoint() : "https://dysmsapi.aliyuncs.com/");
    }

    @Override
    public void send(String phone, String code) {
        requireConfig();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", ACTION);
        params.put("Version", API_VERSION);
        params.put("Format", "JSON");
        params.put("RegionId", orDefault(properties.regionId(), "cn-hangzhou"));
        params.put("AccessKeyId", properties.accessKeyId());
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", TIMESTAMP.format(java.time.Instant.now()));
        params.put("PhoneNumbers", phone);
        params.put("SignName", properties.signName());
        params.put("TemplateCode", properties.templateCode());
        params.put("TemplateParam", templateParam(code));

        params.put("Signature", AliyunSmsSigner.signature(params, properties.accessKeySecret()));
        byte[] body = AliyunSmsSigner.formBody(params).getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // 显式给出长度：这条链路上吃过 chunked 被对端中断的亏（微信上传），换服务器也一样要防
        headers.setContentLength(body.length);

        String raw;
        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            raw = response.getBody();
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            // 阿里云的参数/凭据类错误是 4xx 且响应体仍是同一份 JSON，必须按 Code 判，不能只看状态码
            raw = ex.getResponseBodyAsString();
            if (!StringUtils.hasText(raw)) {
                throw new SmsSendException(String.valueOf(ex.getStatusCode().value()),
                        "短信服务返回错误状态：" + ex.getStatusCode());
            }
        } catch (ResourceAccessException ex) {
            throw new SmsSendException("NetworkError",
                    "连不上短信服务 " + endpoint.getHost() + "：" + ex.getMessage(), ex);
        }
        interpret(raw, phone);
    }

    private void interpret(String raw, String phone) {
        String code;
        String message;
        String bizId;
        String requestId;
        try {
            var node = objectMapper.readTree(raw == null ? "" : raw);
            code = node.path("Code").asText("");
            message = node.path("Message").asText("");
            bizId = node.path("BizId").asText("");
            requestId = node.path("RequestId").asText("");
        } catch (Exception ex) {
            // 中间代理/网关返回 HTML 错误页时走这里，原文截断留日志，别把整页塞进日志
            throw new SmsSendException("BadResponse", "短信服务响应不是预期的 JSON：" + preview(raw), ex);
        }
        if (!SUCCESS_CODE.equals(code)) {
            throw new SmsSendException(code.isEmpty() ? "BadResponse" : code,
                    "短信服务拒绝发送：" + (message.isEmpty() ? preview(raw) : message) + "（RequestId=" + requestId + "）");
        }
        log.info("验证码短信已提交阿里云 phone={} signName={} templateCode={} bizId={} requestId={}",
                PhoneNumbers.mask(phone), properties.signName(), properties.templateCode(), bizId, requestId);
    }

    /** 模板变量：阿里云要求是 JSON 字符串，变量名由控制台里的模板决定，默认 code。 */
    private String templateParam(String code) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(orDefault(properties.templateParamKey(), "code"), code);
        return node.toString();
    }

    /**
     * 缺配置要在本地就拦下来：少了签名密钥也照样能发出一个「格式正确」的请求，
     * 阿里云只会回 SignatureDoesNotMatch，看日志的人根本想不到是环境变量没配。
     */
    private void requireConfig() {
        String missing = firstMissing();
        if (missing != null) {
            throw new SmsSendException("ConfigMissing",
                    "短信通道已启用但缺少配置：" + missing + "（见 platform.sms.* / 对应环境变量）");
        }
    }

    private String firstMissing() {
        if (!StringUtils.hasText(properties.accessKeyId())) {
            return "PLATFORM_SMS_ACCESS_KEY_ID";
        }
        if (!StringUtils.hasText(properties.accessKeySecret())) {
            return "PLATFORM_SMS_ACCESS_KEY_SECRET";
        }
        if (!StringUtils.hasText(properties.signName())) {
            return "PLATFORM_SMS_SIGN_NAME";
        }
        if (!StringUtils.hasText(properties.templateCode())) {
            return "PLATFORM_SMS_TEMPLATE_CODE";
        }
        return null;
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String orDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String preview(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "<空响应>";
        }
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
