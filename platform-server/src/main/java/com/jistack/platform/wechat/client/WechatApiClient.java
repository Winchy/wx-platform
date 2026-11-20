package com.jistack.platform.wechat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信服务端 API 传输层：拼 URL、发请求、把 errcode!=0 统一转成异常。
 * apiBase 可注入，测试时指向 MockWebServer。
 */
@Slf4j
public class WechatApiClient {

    /** 官方文档里 token 失效/票据相关需要重试的错误码。 */
    public static final List<Integer> RETRYABLE_TOKEN_ERRORS = List.of(40001, 42001, 40013, 61005, 61023, 61024, 61035);

    private final String apiBase;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WechatApiClient(String apiBase, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.apiBase = apiBase == null ? "https://api.weixin.qq.com" : apiBase.replaceAll("/+$", "");
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 查询值统一在这里编码：调用方传原始值，本类保证发出去的 URL 不被 RestTemplate 二次编码。 */
    public String url(String path, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiBase + path);
        if (query != null) {
            query.forEach((k, v) -> {
                if (v != null) {
                    builder.queryParam(k, encodeQueryValue(v));
                }
            });
        }
        return builder.build(true).toUriString();
    }

    /** URLEncoder 是表单编码（空格变 +），URI 查询串要用 %20。 */
    private static String encodeQueryValue(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public String url(String path, String accessToken) {
        return url(path, Map.of("access_token", accessToken));
    }

    public JsonNode get(String path, String accessToken) {
        return exchange(path, url(path, accessToken), HttpMethod.GET, null, "GET " + path);
    }

    public JsonNode getWithQuery(String path, Map<String, String> query) {
        return exchange(path, url(path, query), HttpMethod.GET, null, "GET " + path);
    }

    public JsonNode post(String path, String accessToken, Object body) {
        return exchange(path, url(path, accessToken), HttpMethod.POST, jsonEntity(body), "POST " + path);
    }

    /** 第三方平台部分接口（如获取令牌）走 POST 但参数放 query。 */
    public JsonNode postWithQuery(String path, Map<String, String> query, Object body) {
        return exchange(path, url(path, query), HttpMethod.POST, jsonEntity(body), "POST " + path);
    }

    /** 无 query 鉴权参数，凭证放 body（api_component_token、api_start_push_ticket）。 */
    public JsonNode postRaw(String path, Object body) {
        return exchange(path, apiBase + path, HttpMethod.POST, jsonEntity(body), "POST " + path);
    }

    /** component 级接口：新旧文档分别写作 access_token / component_access_token，两个都带上同一值。 */
    public JsonNode postComponent(String path, String componentAccessToken, Object body) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", componentAccessToken);
        query.put("component_access_token", componentAccessToken);
        return exchange(path, url(path, query), HttpMethod.POST, jsonEntity(body), "POST " + path);
    }

    /** 代码包等 multipart 上传：普通字段 + 一个二进制文件部分。 */
    public JsonNode postMultipart(String path, String accessToken, Map<String, String> fields,
                                 String filePartName, String fileName, byte[] content) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", accessToken);
        return multipart(path, query, fields, filePartName, fileName, content);
    }

    /** query 上还要带别的参数（如新增临时素材的 type=image）时用这个重载。 */
    public JsonNode postMultipart(String path, Map<String, String> query, String filePartName,
                                 String fileName, byte[] content) {
        return multipart(path, query, Map.of(), filePartName, fileName, content);
    }

    private JsonNode multipart(String path, Map<String, String> query, Map<String, String> fields,
                               String filePartName, String fileName, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        fields.forEach((k, v) -> {
            if (v != null) {
                form.add(k, v);
            }
        });
        if (content != null) {
            form.add(filePartName, new NamedByteArrayResource(content, fileName));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return exchange(path, url(path, query), HttpMethod.POST,
                new HttpEntity<>(form, headers), "POST(multipart) " + path);
    }

    public byte[] getBinary(String path, String accessToken) {
        return getBinary(path, Map.of("access_token", accessToken));
    }

    /** 二维码等二进制响应：微信出错时会返回 JSON，需要按 Content-Type 区分。 */
    public byte[] getBinary(String path, Map<String, String> query) {
        return binary(path, url(path, query), HttpMethod.GET, null);
    }

    /** 永久素材（如审核驳回截图）用 POST 取回，图片类型直接回二进制字节。 */
    public byte[] postBinary(String path, String accessToken, Object body) {
        return binary(path, url(path, accessToken), HttpMethod.POST, jsonEntity(body));
    }

    private byte[] binary(String path, String fullUrl, HttpMethod method, HttpEntity<?> entity) {
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(URI.create(fullUrl), method, entity, byte[].class);
            byte[] body = response.getBody();
            MediaType contentType = response.getHeaders().getContentType();
            boolean json = contentType != null && (contentType.includes(MediaType.APPLICATION_JSON)
                    || contentType.includes(MediaType.TEXT_PLAIN));
            if (body != null && (json || looksLikeJson(body))) {
                JsonNode node = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
                raiseIfError(node, path);
            }
            return body;
        } catch (WechatApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("微信二进制接口调用失败 " + path + ": " + ex.getMessage(), ex);
        }
    }

    private HttpEntity<String> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String payload = body == null ? "{}"
                    : (body instanceof String s ? s : objectMapper.writeValueAsString(body));
            return new HttpEntity<>(payload, headers);
        } catch (Exception ex) {
            throw new IllegalStateException("请求体序列化失败", ex);
        }
    }

    private JsonNode exchange(String path, String fullUrl, HttpMethod method, HttpEntity<?> entity, String label) {
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(URI.create(fullUrl), method, entity, String.class);
            String raw = response.getBody();
            if (raw == null || raw.isBlank()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("errcode", 0);
                node.put("errmsg", "ok");
                return node;
            }
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(raw);
            } catch (Exception parseError) {
                // 少数接口返回纯文本（如 success / 校验文件内容）
                ObjectNode node = objectMapper.createObjectNode();
                node.put("errcode", 0);
                node.put("raw", raw);
                return node;
            }
            raiseIfError(parsed, path);
            return parsed;
        } catch (WechatApiException ex) {
            log.warn("{} 失败 errcode={} errmsg={} cost={}ms", label, ex.getErrcode(), ex.getErrmsg(),
                    System.currentTimeMillis() - start);
            throw ex;
        } catch (ResourceAccessException ex) {
            // 连接被中途断开时交给 GlobalExceptionHandler 报 502，包成 IllegalStateException 只会显示“服务器内部错误”
            log.error("{} 与微信服务器连接中断 cost={}ms: {}", label, System.currentTimeMillis() - start,
                    ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("微信接口调用失败 " + label + ": " + ex.getMessage(), ex);
        }
    }

    private void raiseIfError(JsonNode node, String path) {
        JsonNode errcode = node.get("errcode");
        if (errcode != null && errcode.isNumber() && errcode.asInt() != 0) {
            String errmsg = node.path("errmsg").asText("");
            if (errmsg.isBlank() && node.path("ErrCode").isInt()) {
                // 安全模式被动回复偶尔使用大写键
                errcode = node.path("ErrCode");
                errmsg = node.path("ErrMsg").asText("");
            }
            throw new WechatApiException(errcode.asInt(), errmsg.isBlank() ? errmsgOf(errcode.asInt()) : errmsg, path);
        }
    }

    private static boolean looksLikeJson(byte[] body) {
        for (byte b : body) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{' || b == '[';
        }
        return false;
    }

    private static String errmsgOf(int errcode) {
        return switch (errcode) {
            case -1 -> "微信系统繁忙，请稍后重试";
            case 40001 -> "access_token 无效或已不是最新";
            case 40013 -> "AppID 不合法";
            case 40014 -> "access_token 不合法";
            case 41001 -> "缺少 access_token 参数";
            case 42001 -> "access_token 超时，请刷新";
            case 40164, 61004 -> "调用方 IP 不在第三方平台白名单";
            case 48001 -> "该小程序未授权对应权限集";
            case 45009 -> "接口调用超过当日配额";
            case 45011 -> "接口调用频率超限";
            case 61005 -> "component_access_token 不合法";
            case 61023 -> "authorizer_refresh_token 已失效，需要重新授权";
            case 61024 -> "刷新令牌与授权关系不匹配";
            case 61039 -> "代码安全检测未完成，暂不能提审";
            case 61040 -> "隐私接口声明有问题，需先处理";
            case 85009 -> "已有正在审核的版本，需先撤回";
            case 85019, 85020 -> "当前没有可发布的审核通过版本";
            case 85079 -> "当前不满足灰度发布前置条件";
            case 85080 -> "已有灰度中的版本";
            case 85081 -> "灰度比例不合法";
            case 85082 -> "灰度状态已变化，请刷新后重试";
            case 85086 -> "提审前必须先上传代码";
            case 86000 -> "非第三方平台调用或参数不合法";
            case 86001 -> "小程序尚未上传代码";
            case 86002 -> "小程序未设置名称/头像/简介，请先完善基本信息";
            case 89501, 89502, 89503, 89504 -> "需小程序管理员确认或流程处理中，请稍后重试";
            default -> "接口返回错误";
        };
    }

    /** multipart 需要文件名，ByteArrayResource 默认不给。 */
    static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename == null ? "file" : filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
