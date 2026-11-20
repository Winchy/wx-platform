package com.jistack.platform.wechat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jistack.platform.common.web.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
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
    private final String apiHost;
    private final String apiScheme;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WechatApiClient(String apiBase, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.apiBase = apiBase == null ? "https://api.weixin.qq.com" : apiBase.replaceAll("/+$", "");
        URI base;
        try {
            base = URI.create(this.apiBase);
        } catch (IllegalArgumentException ex) {
            base = null;
        }
        this.apiHost = base == null || base.getHost() == null ? "" : base.getHost();
        this.apiScheme = base == null || base.getScheme() == null ? "https" : base.getScheme();
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
        String boundary = "----WxPlatform" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream((content == null ? 0 : content.length) + 512);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            body.writeBytes(partHead(boundary, field.getKey(), null).getBytes(StandardCharsets.UTF_8));
            body.writeBytes(field.getValue().getBytes(StandardCharsets.UTF_8));
            body.writeBytes(CRLF);
        }
        if (content != null) {
            body.writeBytes(partHead(boundary, filePartName, fileName).getBytes(StandardCharsets.UTF_8));
            body.writeBytes(content);
            body.writeBytes(CRLF);
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("multipart", "form-data", Map.of("boundary", boundary)));
        headers.setContentLength(body.size());
        return exchange(path, url(path, query), HttpMethod.POST,
                new HttpEntity<>(body.toByteArray(), headers), "POST(multipart) " + path);
    }

    private static final byte[] CRLF = {'\r', '\n'};

    /** 文件部分带上文件名与内容类型，微信按后缀判断素材类型。 */
    private static String partHead(String boundary, String name, String fileName) {
        StringBuilder head = new StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(safeName(name)).append("\"");
        if (fileName != null) {
            head.append("; filename=\"").append(safeName(fileName)).append("\"\r\n")
                    .append("Content-Type: ").append(mediaTypeOf(fileName));
        }
        return head.append("\r\n\r\n").toString();
    }

    /** 文件名来自上传请求：去掉引号和换行，避免拼进 multipart 头部；非 ASCII 只保留后缀。 */
    static String safeName(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\"'\\r\\n\\x00\\\\]", "").trim();
        if (cleaned.isEmpty()) {
            return "file";
        }
        if (!ASCII_PRINTABLE.matcher(cleaned).matches()) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot >= 0 && dot < cleaned.length() - 1
                    ? cleaned.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "") : "";
            return ext.isEmpty() ? "file" : "file." + ext;
        }
        return cleaned.length() > 128 ? cleaned.substring(cleaned.length() - 128) : cleaned;
    }

    private static final java.util.regex.Pattern ASCII_PRINTABLE = java.util.regex.Pattern.compile("[\\x20-\\x7E]+");

    private static String mediaTypeOf(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return switch (lower.substring(lower.lastIndexOf('.') + 1)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    public byte[] getBinary(String path, String accessToken) {
        return getBinary(path, Map.of("access_token", accessToken));
    }

    /** 二维码等二进制响应：微信出错时会返回 JSON，需要按 Content-Type 区分。 */
    public byte[] getBinary(String path, Map<String, String> query) {
        return binary(path, url(path, query), HttpMethod.GET, null);
    }

    /**
     * 抓取微信的图片直链（如 authorizer_info.qrcode_url 指向的 showqrcode）：绝对地址、不带令牌。
     * 这类链接有 Referer 防盗链，浏览器带本站来源直连只会拿到「未经允许不可引用」的占位图，只能由服务端取。
     */
    public byte[] getBinaryAbsolute(String url) {
        URI uri = imageUri(url);
        return binary(uri.getPath(), uri.toString(), HttpMethod.GET, null);
    }

    /** 链接只来自微信响应，但落库后仍可能被改写，所以限定主机与协议，别让它变成任意 URL 抓取。 */
    private URI imageUri(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException("图片链接格式不合法，无法抓取");
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean weixinHost = host.equalsIgnoreCase("weixin.qq.com")
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".weixin.qq.com");
        if (!weixinHost && !host.equalsIgnoreCase(apiHost)) {
            throw new BizException("图片链接的域名不是微信站点，已拒绝抓取：" + host);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String expected = weixinHost ? "https" : apiScheme;
        if (!expected.equals(scheme)) {
            throw new BizException("图片链接应为 " + expected + " 协议，当前是"
                    + (scheme.isEmpty() ? "未指定协议" : " " + scheme));
        }
        return uri;
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
        } catch (ResourceAccessException ex) {
            // 同 exchange()：连接中断要原样透传，包成 IllegalStateException 界面只剩「服务器内部错误」
            log.error("微信二进制接口连接中断 {}: {}", path, ex.getMessage());
            throw ex;
        } catch (HttpStatusCodeException ex) {
            // 默认错误处理器不读错误体，界面只剩「403 Forbidden: [no body]」，看不出微信拒绝的理由
            throw new BizException(502, "微信图片接口 " + path + " 返回 " + ex.getStatusCode().value()
                    + "：" + briefText(ex.getResponseBodyAsString()), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("微信二进制接口调用失败 " + path + ": " + ex.getMessage(), ex);
        }
    }

    /** 微信的错误体可能是一整页 HTML，截断并去掉控制字符后才能进界面和日志。 */
    private static String briefText(String body) {
        if (body == null || body.isBlank()) {
            return "响应体为空";
        }
        String cleaned = body.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160) + "…";
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
            int code = errcode.asInt();
            // 微信的 errmsg 多是英文短句，界面要的是下一步该做什么；原始文本留在日志里
            String hint = errmsgOf(code);
            if (hint == null) {
                hint = errmsg.isBlank() ? "接口返回错误" : errmsg;
            } else if (!errmsg.isBlank() && !errmsg.equals(hint)) {
                log.warn("{} 微信原始 errmsg: [{}] {}", path, code, errmsg);
            }
            throw new WechatApiException(code, hint, path);
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
            case 40004 -> "素材类型不合法，图片只支持 PNG、JPG、GIF、BMP";
            case 40007 -> "media_id 无效：素材必须用该小程序的授权令牌上传，且临时素材只保存 3 天";
            case 40009 -> "图片尺寸太大，请压缩后重试";
            case 46001 -> "素材不存在或已过期：临时素材只保存 3 天，请重新上传";
            case 53202 -> "本月头像修改次数已用完，下月 1 日恢复";
            case 91001 -> "该小程序不是通过第三方平台 API 注册的，微信不允许接口改名；"
                    + "请小程序管理员登录微信公众平台，在「设置 → 基本设置 → 名称」中修改";
            case 91003 -> "改名状态不合法，请稍后重试";
            case 91004 -> "名称不合法，不能包含「小程序」等关键字";
            case 91005 -> "名称受 15 天主体保护，暂时无法使用";
            case 91007 -> "名称已被占用";
            case 91008 -> "名称命中 7 天侵权保护期";
            case 91009 -> "该名称需要提交证明材料，请上传营业执照或组织机构代码证后重试";
            case 91013 -> "同一主体占用该名称的小程序过多";
            case 91019 -> "小程序正在审核中，暂不能改名";
            case 91028 -> "试用小程序不支持通过接口改名";
            case 91029 -> "本年度改名次数已用尽，可通过发起微信认证继续改名";
            default -> null;
        };
    }
}
