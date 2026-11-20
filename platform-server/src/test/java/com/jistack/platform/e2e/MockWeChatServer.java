package com.jistack.platform.e2e;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 桩微信服务端：按真实请求路径的末段返回可预期的成功响应，让端到端测试能覆盖
 * 「HTTP 入口 → 业务服务 → MySQL/Redis → 微信客户端」整条链路，而不依赖真实凭据。
 *
 * <p>仅证明本站代码的串联正确，不代表微信真实返回结构（字段名由 WechatApiContractTest 锁定）。
 */
class MockWeChatServer extends Dispatcher {

    static final String AUTHORIZER_APPID = "wxauthorizer0001";
    static final String AUDIT_ID = "900001";
    static final String TEMPLATE_ID = "TPL0002";

    private final List<String> calledPaths = new ArrayList<>();

    /** 服务状态是桩里唯一可写的状态：close 之后 getvisitstatus 必须回 0，否则测不出「改完再读」是否生效。 */
    private volatile int visitStatus = 1;

    /** 1x1 PNG：体验版二维码接口返回二进制，不能用 JSON 桩。 */
    private static final byte[] PNG_PIXEL = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==");

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest request) {
        String path = request.getPath() == null ? "/" : request.getPath().split("\\?")[0];
        synchronized (calledPaths) {
            calledPaths.add(request.getMethod() + " " + path);
        }
        String last = path.replaceAll("/+$", "");
        last = last.substring(last.lastIndexOf('/') + 1);
        if ("get_qrcode".equals(last)) {
            okio.Buffer buffer = new okio.Buffer();
            buffer.write(PNG_PIXEL, 0, PNG_PIXEL.length);
            return new MockResponse().setHeader("Content-Type", "image/png").setBody(buffer);
        }
        if ("change_visitstatus".equals(last)) {
            visitStatus = request.getBody().readUtf8().contains("\"close\"") ? 0 : 1;
        }
        return new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body(last));
    }

    List<String> calledPaths() {
        synchronized (calledPaths) {
            return List.copyOf(calledPaths);
        }
    }

    boolean called(String pathSuffix) {
        return calledPaths().stream().anyMatch(item -> item.endsWith(pathSuffix));
    }

    private String body(String endpoint) {
        return switch (endpoint) {
            // ------------------------------------------------ 第三方平台（component token）
            case "api_component_token" -> json(
                    "errcode", 0, "errmsg", "ok",
                    "component_access_token", "MOCK_COMPONENT_TOKEN", "expires_in", 7200);
            case "api_create_preauthcode" -> json(
                    "errcode", 0, "errmsg", "ok",
                    "pre_auth_code", "MOCK_PRE_AUTH_CODE", "expires_in", 600);
            case "api_query_auth" -> flat("""
                    {"errcode":0,"errmsg":"ok","authorization_info":{
                      "authorizer_appid":"%s",
                      "authorizer_access_token":"MOCK_AUTHORIZER_TOKEN",
                      "expires_in":7200,
                      "authorizer_refresh_token":"MOCK_REFRESH_TOKEN_V1",
                      "func_info":[{"funcscope_category":{"id":1}},{"funcscope_category":{"id":4}},
                                   {"funcscope_category":{"id":17}}]}}
                    """.formatted(AUTHORIZER_APPID));
            case "api_authorizer_token" -> json(
                    "errcode", 0, "errmsg", "ok",
                    "authorizer_access_token", "MOCK_AUTHORIZER_TOKEN_V2", "expires_in", 7200,
                    "authorizer_refresh_token", "MOCK_REFRESH_TOKEN_V2");
            case "api_get_authorizer_list" -> flat("""
                    {"errcode":0,"errmsg":"ok","total_count":1,"list":[
                      {"authorizer_appid":"%s","refresh_token":"MOCK_REFRESH_TOKEN_SYNC","auth_time":1731389000}]}
                    """.formatted(AUTHORIZER_APPID));
            case "api_get_authorizer_info" -> flat("""
                    {"errcode":0,"errmsg":"ok","authorizer_info":{
                      "nick_name":"_mock_协会小程序","head_img":"https://mock.example/avatar.png",
                      "qrcode_url":"https://mock.example/qr-authorizer.png",
                      "user_name":"gh_xzmock","principal_name":"某某行业协会","alias":"xzmock",
                      "signature":"服务协会会员专属入口",
                      "service_type_info":{"id":1},"verify_type_info":{"id":-1},"business_info":[],
                      "MiniProgramInfo":{"current_version":"1.0.9"}},
                     "authorization_info":{"authorizer_appid":"%s",
                      "func_info":[{"funcscope_category":{"id":1}},{"funcscope_category":{"id":4}}]}}
                    """.formatted(AUTHORIZER_APPID));
            case "api_start_push_ticket" -> json("errcode", 0, "errmsg", "ok");
            case "modify_wxa_server_domain" -> flat("""
                    {"errcode":0,"errmsg":"ok",
                     "published_wxa_server_domain":"api.mock.example;h5.mock.example",
                     "testing_wxa_server_domain":"api.mock.example",
                     "invalid_wxa_server_domain":"bad.mock.example"}
                    """);
            case "modify_wxa_jump_domain" -> flat("""
                    {"errcode":0,"errmsg":"ok",
                     "published_wxa_jump_h5_domain":"h5.mock.example",
                     "testing_wxa_jump_h5_domain":"webview.mock.example"}
                    """);
            case "get_domain_confirmfile" -> json("errcode", 0, "errmsg", "ok",
                    "file_name", "mock-confirm.txt", "file_content", "mock-confirm-content");

            // ------------------------------------------------ 代码模板与草稿（component token）
            case "gettemplatedraftlist" -> flat("""
                    {"errcode":0,"errmsg":"ok","draft_list":[
                      {"draft_id":1001,"user_version":"1.0.0","user_desc":"初始草稿",
                       "create_time":1731389000,"screenshot":"https://mock.example/draft-1001.png"},
                      {"draft_id":1002,"user_version":"1.1.0","user_desc":"新增活动报名",
                       "create_time":1731475400,"screenshot":"https://mock.example/draft-1002.png"}]}
                    """);
            case "gettemplatelist" -> flat("""
                    {"errcode":0,"errmsg":"ok","template_list":[
                      {"template_id":"TPL0001","user_version":"1.1.0","user_desc":"协会标准模板",
                       "template_type":0,"draft_id":1002,"source_miniprogram":"协会小程序","category_list":[]},
                      {"template_id":"TPL9001","user_version":"2.0.0","user_desc":"行业模板库示例",
                       "template_type":1,"draft_id":1001,"source_miniprogram":"协会小程序",
                       "category_list":[{"first_class":"工具","second_class":"效率"}]}]}
                    """);
            case "addtotemplate" -> json("errcode", 0, "errmsg", "ok", "template_id", TEMPLATE_ID);
            case "deletetemplate" -> json("errcode", 0, "errmsg", "ok");

            // ------------------------------------------------ 研发与发布（authorizer token）
            case "commit" -> json("errcode", 0, "errmsg", "ok");
            case "get_page" -> flat("""
                    {"errcode":0,"errmsg":"ok","page_list":["pages/index/index","pages/activity/list"]}
                    """);
            case "getversioninfo" -> flat("""
                    {"errcode":0,"errmsg":"ok",
                     "exp_info":{"exp_version":"1.1.0","exp_desc":"活动报名体验版","exp_time":1731475400},
                     "release_info":{"release_version":"1.0.9","release_desc":"会员列表修复"}}
                    """);
            case "getvisitstatus" -> json("errcode", 0, "errmsg", "ok", "status", visitStatus);
            case "change_visitstatus" -> json("errcode", 0, "errmsg", "ok");
            case "get_icp_entrance_info" -> flat("""
                    {"errcode":0,"errmsg":"ok","info":{"status":1026,"is_canceling":false,"available":1,
                     "audit_data":[{"key_name":"小程序简介","error":"含医疗效果描述","suggest":"删除疗效相关表述"}]}}
                    """);
            case "getprivacysetting" -> flat("""
                    {"errcode":0,"errmsg":"ok","code_exist":1,"privacy_list":["Location","PhoneNumber"],
                     "setting_list":[{"privacy_key":"Location","privacy_text":"用于就近推荐活动场地"}],
                     "owner_setting":{"notice_method":"弹窗提示","contact_email":"ops@example.com"},
                     "update_time":1731475400}
                    """);
            case "setprivacysetting" -> json("errcode", 0, "errmsg", "ok");
            case "submit_audit" -> json("errcode", 0, "errmsg", "ok", "auditid", AUDIT_ID);
            case "get_latest_auditstatus" -> json("errcode", 0, "errmsg", "ok",
                    "auditid", AUDIT_ID, "status", 0, "reason", "");
            case "get_auditstatus" -> json("errcode", 0, "errmsg", "ok",
                    "auditid", AUDIT_ID, "status", 0, "reason", "");
            case "undocodeaudit", "speedupaudit", "release", "revertgrayrelease" ->
                    json("errcode", 0, "errmsg", "ok");
            case "grayrelease" -> json("errcode", 0, "errmsg", "ok");
            case "getgrayreleaseplan" -> flat("""
                    {"errcode":0,"errmsg":"ok","gray_release_plan":{"status":1,
                     "create_timestamp":1517553721,"gray_percentage":10,
                     "support_debuger_first":true,"support_experiencer_first":false}}
                    """);
            case "revertcoderelease" -> flat("""
                    {"errcode":0,"errmsg":"ok","version_list":[
                      {"app_version":"1.0.0","user_version":"1.0.9","user_desc":"上一线上版本",
                       "commit_time":1731308000}]}
                    """);
            case "uploadmedia" -> json("errcode", 0, "errmsg", "ok", "type", "image",
                    "mediaid", "MOCK_MEDIA_ID", "created_at", 1731475400);
            // 新增临时素材：键名是 media_id，和 uploadmedia 的 mediaid 不一样
            case "upload" -> json("errcode", 0, "errmsg", "ok", "type", "image",
                    "media_id", "MOCK_TEMP_MEDIA_ID", "created_at", 1731475400);

            // ------------------------------------------------ 小程序配置（authorizer token）
            case "modify_domain" -> flat("""
                    {"errcode":0,"errmsg":"ok","requestdomain":["https://mock.example"],
                     "wsrequestdomain":[],"uploaddomain":["https://mock.example"],
                     "downloaddomain":[],"udpdomain":[],"tcpdomain":[],
                     "invalid_domain":[{"domain":"https://bad.example","reason":"ICP 备案失效"}]}
                    """);
            case "setwebviewdomain" -> json("errcode", 0, "errmsg", "ok");
            case "get_effective_webviewdomain" -> flat("""
                    {"errcode":0,"errmsg":"ok","mp_webviewdomain":["https://mp.example"],
                     "third_webviewdomain":["https://mock.example"],"direct_webviewdomain":[],
                     "effective_webviewdomain":["https://mp.example","https://mock.example"]}
                    """);
            case "get_webviewdomain_confirmfile" -> json("errcode", 0, "errmsg", "ok",
                    "file_name", "mock-confirm.txt", "file_content", "mock-confirm-file");
            case "addcategory", "deletecategory" -> json("errcode", 0, "errmsg", "ok");
            case "getcategory" -> flat("""
                    {"errcode":0,"errmsg":"ok","categories":[{"id":1,"name":"医疗服务","level":1}],
                     "limit":5,"quota":4,"category_limit":5}
                    """);
            case "getallcategories" -> flat("""
                    {"errcode":0,"errmsg":"ok","category_list":[
                      {"id":"1","name":"医疗服务","levels":[1],"description":"","sensitive_auth":0}]}
                    """);
            case "setnickname" -> json("errcode", 0, "errmsg", "ok", "wording", "",
                    "audit_id", 300001);
            case "checkwxverifynickname" -> json("errcode", 0, "errmsg", "ok", "hit_condition", true,
                    "wording", "你申请的名称可能涉及特定主体姓名或名称，请提供可确认主体材料与名称对应的材料");
            case "api_wxa_querynickname", "modifysignature", "modifyheadimage" ->
                    json("errcode", 0, "errmsg", "ok");
            case "getaccountbasicinfo" -> flat("""
                    {"errcode":0,"errmsg":"ok","appid":"%s","account_type":3,"principal_type":1,
                     "principal_name":"某某行业协会","realname_status":1,"customer_type":0,
                     "wx_verify_info":{"qualification_verify":false,"naming_verify":false},
                     "signature_info":{"signature":"服务协会会员","modify_used_count":1,"modify_quota":5},
                     "head_image_info":{"head_image_url":"https://mock.example/avatar.png",
                                        "modify_used_count":0,"modify_quota":5},
                     "nickname":"_mock_协会小程序",
                     "nickname_info":{"nickname":"_mock_协会小程序","modify_used_count":0,"modify_quota":2}}
                    """.formatted(AUTHORIZER_APPID));
            case "bind_tester" -> json("errcode", 0, "errmsg", "ok", "userstr", "mock-userstr");
            case "memberauth" -> flat("""
                    {"errcode":0,"errmsg":"ok","count":1,
                     "members":[{"userstr":"mock-member-openid"}]}
                    """);
            case "unbind_tester" -> json("errcode", 0, "errmsg", "ok");
            case "getconfirmfile" -> json("errcode", 0, "errmsg", "ok", "content", "mock-confirm-file");

            default -> json("errcode", 0, "errmsg", "ok");
        };
    }

    /** 拼扁平 JSON：只覆盖测试需要的标量值。 */
    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v instanceof String s) {
                sb.append('"').append(s).append('"');
            } else if (v instanceof List<?> list) {
                sb.append(list);
            } else {
                sb.append(v);
            }
        }
        return sb.append('}').toString();
    }

    /** 去掉多行字符串里的缩进与换行，保持 JSON 合法即可。 */
    private static String flat(String value) {
        return value.replace("\n", "").replace(" ", "");
    }
}
