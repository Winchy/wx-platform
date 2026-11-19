package com.jistack.platform.wechat.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方平台（开放平台）平台级接口，鉴权凭证为 component_access_token。
 *
 * <p>历史文档用 {@code ?component_access_token=}，较新的 openApi 文档写成 {@code ?access_token=}，
 * 服务端对两个参数名都接受；这里同时下发同一个值以规避版本差异。
 */
public final class ComponentApi {

    public static final String PATH_COMPONENT_TOKEN = "/cgi-bin/component/api_component_token";
    public static final String PATH_START_PUSH_TICKET = "/cgi-bin/component/api_start_push_ticket";
    public static final String PATH_CREATE_PRE_AUTHCODE = "/cgi-bin/component/api_create_preauthcode";
    public static final String PATH_QUERY_AUTH = "/cgi-bin/component/api_query_auth";
    public static final String PATH_AUTHORIZER_TOKEN = "/cgi-bin/component/api_authorizer_token";
    public static final String PATH_AUTHORIZER_INFO = "/cgi-bin/component/api_get_authorizer_info";
    public static final String PATH_AUTHORIZER_LIST = "/cgi-bin/component/api_get_authorizer_list";
    public static final String PATH_MODIFY_WXA_SERVER_DOMAIN = "/cgi-bin/component/modify_wxa_server_domain";
    public static final String PATH_MODIFY_WXA_JUMP_DOMAIN = "/cgi-bin/component/modify_wxa_jump_domain";
    public static final String PATH_GET_DOMAIN_CONFIRM_FILE = "/cgi-bin/component/get_domain_confirmfile";

    /** 扫码授权页在 mp 域，不在 api 域。auth_type：1 公众号 2 小程序 3 两者 6 全部。 */
    public static final String AUTH_TYPE_MINIPROGRAM = "2";
    public static final String AUTH_TYPE_ALL = "3";
    public static final String LOGIN_PAGE = "https://mp.weixin.qq.com/cgi-bin/componentloginpage";
    public static final String BIND_PAGE = "https://open.weixin.qq.com/wxaopen/safe/bindcomponent";

    private ComponentApi() {
    }

    /** 该接口不用 query 鉴权，凭证全部在 body 内。 */
    public static JsonNode componentToken(WechatApiClient api, String componentAppid,
                                         String componentSecret, String verifyTicket) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component_appid", componentAppid);
        body.put("component_appsecret", componentSecret);
        body.put("component_verify_ticket", verifyTicket);
        return api.postRaw(PATH_COMPONENT_TOKEN, body);
    }

    /** ticket 超过 12 小时没推送时，可主动让微信重新开启推送（注意字段名是 component_secret）。 */
    public static JsonNode startPushTicket(WechatApiClient api, String componentAppid, String componentSecret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component_appid", componentAppid);
        body.put("component_secret", componentSecret);
        return api.postRaw(PATH_START_PUSH_TICKET, body);
    }

    public static JsonNode createPreAuthCode(WechatApiClient api, String componentToken, String componentAppid) {
        return api.postComponent(PATH_CREATE_PRE_AUTHCODE, componentToken, Map.of("component_appid", componentAppid));
    }

    /** 用回调带回的 auth_code（即 authorization_code）换 authorizer_appid / refresh_token / func_info。 */
    public static JsonNode queryAuth(WechatApiClient api, String componentToken,
                                     String componentAppid, String authorizationCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component_appid", componentAppid);
        body.put("authorization_code", authorizationCode);
        return api.postComponent(PATH_QUERY_AUTH, componentToken, body);
    }

    /** 返回新的 authorizer_access_token，并且会轮换 refresh_token，调用方必须持久化返回值。 */
    public static JsonNode authorizerToken(WechatApiClient api, String componentToken, String componentAppid,
                                           String authorizerAppid, String authorizerRefreshToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component_appid", componentAppid);
        body.put("authorizer_appid", authorizerAppid);
        body.put("authorizer_refresh_token", authorizerRefreshToken);
        return api.postComponent(PATH_AUTHORIZER_TOKEN, componentToken, body);
    }

    public static JsonNode authorizerInfo(WechatApiClient api, String componentToken,
                                          String componentAppid, String authorizerAppid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component_appid", componentAppid);
        body.put("authorizer_appid", authorizerAppid);
        return api.postComponent(PATH_AUTHORIZER_INFO, componentToken, body);
    }

    /** 分页拉取已授权账号，count 上限 500，返回项含 refresh_token 可用于恢复授权。 */
    public static JsonNode authorizerList(WechatApiClient api, String componentToken,
                                          String componentAppid, int offset, int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component_appid", componentAppid);
        body.put("offset", offset);
        body.put("count", Math.min(count, 500));
        return api.postComponent(PATH_AUTHORIZER_LIST, componentToken, body);
    }

    /**
     * 第三方平台自身的小程序服务器域名白名单（最多 1000 个）。
     * wxa_server_domain 是 ; 拼接的裸主机名，不带 http:// 前缀、路径和端口；action=get 时不带该字段。
     */
    public static JsonNode modifyWxaServerDomain(WechatApiClient api, String componentToken, String action,
                                                 String domainValue, Boolean modifyPublishedTogether) {
        return api.postComponent(PATH_MODIFY_WXA_SERVER_DOMAIN, componentToken,
                platformDomainBody("wxa_server_domain", action, domainValue, modifyPublishedTogether));
    }

    /** 第三方平台自身的小程序业务域名白名单（最多 300 个），字段名为 wxa_jump_h5_domain，规则同上。 */
    public static JsonNode modifyWxaJumpDomain(WechatApiClient api, String componentToken, String action,
                                               String domainValue, Boolean modifyPublishedTogether) {
        return api.postComponent(PATH_MODIFY_WXA_JUMP_DOMAIN, componentToken,
                platformDomainBody("wxa_jump_h5_domain", action, domainValue, modifyPublishedTogether));
    }

    private static Map<String, Object> platformDomainBody(String field, String action, String domainValue,
                                                          Boolean modifyPublishedTogether) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (domainValue != null && !domainValue.isBlank()) {
            body.put(field, domainValue);
        }
        if (modifyPublishedTogether != null) {
            body.put("is_modify_published_together", modifyPublishedTogether);
        }
        return body;
    }

    public static JsonNode getPlatformDomainConfirmFile(WechatApiClient api, String componentToken) {
        return api.postComponent(PATH_GET_DOMAIN_CONFIRM_FILE, componentToken, Map.of());
    }

    /**
     * PC 扫码授权链接。redirect_uri 必须 URL 编码，且域名需与第三方平台后台配置的授权事件接收域一致。
     *
     * @param bizAppid 指定只授权某个小程序时传入，其余账号会隐藏
     * @param categoryIdList 中竖线分隔的权限集 id（如 {@code 18|1}）；留空表示授权全部已发布权限集
     */
    public static String authUrl(String componentAppid, String preAuthCode, String redirectUri,
                                 String authType, String bizAppid, String categoryIdList) {
        StringBuilder query = new StringBuilder();
        append(query, "component_appid", componentAppid);
        append(query, "pre_auth_code", preAuthCode);
        append(query, "redirect_uri", redirectUri);
        append(query, "auth_type", authType == null ? AUTH_TYPE_ALL : authType);
        if (bizAppid != null && !bizAppid.isBlank()) {
            append(query, "biz_appid", bizAppid);
        }
        if (categoryIdList != null && !categoryIdList.isBlank()) {
            append(query, "category_id_list", categoryIdList);
        }
        return LOGIN_PAGE + "?" + query;
    }

    /** 手机H5 授权链接，结尾必须带 #wechat_redirect。 */
    public static String mobileAuthUrl(String componentAppid, String preAuthCode, String redirectUri,
                                       String authType, String bizAppid, String categoryIdList) {
        StringBuilder query = new StringBuilder();
        append(query, "action", "bindcomponent");
        append(query, "no_scan", "1");
        append(query, "component_appid", componentAppid);
        append(query, "pre_auth_code", preAuthCode);
        append(query, "redirect_uri", redirectUri);
        append(query, "auth_type", authType == null ? AUTH_TYPE_ALL : authType);
        if (bizAppid != null && !bizAppid.isBlank()) {
            append(query, "biz_appid", bizAppid);
        }
        if (categoryIdList != null && !categoryIdList.isBlank()) {
            append(query, "category_id_list", categoryIdList);
        }
        return BIND_PAGE + "?" + query + "#wechat_redirect";
    }

    private static void append(StringBuilder query, String name, String value) {
        if (value == null) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(name).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
