package com.jistack.platform.wechat.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代小程序实现业务：除模板库/草稿箱用 component_access_token，其余一律用 authorizer_access_token。
 * 路径与字段依据官方 openApi 文档核对。
 */
public final class MiniProgramApi {

    // 代码与发布
    public static final String COMMIT = "/wxa/commit";
    public static final String GET_QRCODE = "/wxa/get_qrcode";
    public static final String GET_PAGE = "/wxa/get_page";
    public static final String SUBMIT_AUDIT = "/wxa/submit_audit";
    public static final String GET_AUDIT_STATUS = "/wxa/get_auditstatus";
    public static final String GET_LATEST_AUDIT_STATUS = "/wxa/get_latest_auditstatus";
    public static final String UNDO_CODE_AUDIT = "/wxa/undocodeaudit";
    public static final String SPEEDUP_AUDIT = "/wxa/speedupaudit";
    public static final String RELEASE = "/wxa/release";
    public static final String GRAY_RELEASE = "/wxa/grayrelease";
    public static final String GET_GRAY_RELEASE_PLAN = "/wxa/getgrayreleaseplan";
    public static final String REVERT_GRAY_RELEASE = "/wxa/revertgrayrelease";
    public static final String REVERT_CODE_RELEASE = "/wxa/revertcoderelease";
    public static final String GET_VERSION_INFO = "/wxa/getversioninfo";
    public static final String GET_VISIT_STATUS = "/wxa/getvisitstatus";
    public static final String CHANGE_VISIT_STATUS = "/wxa/change_visitstatus";
    /** 上传提审素材（页面截图 / 录屏，权限集 18）：返回 {@code mediaid}，只能用在提审的 preview_info 里。 */
    public static final String UPLOAD_MEDIA = "/wxa/uploadmedia";
    /** 新增临时素材：改名资质材料用的 media_id 来自这里，3 天后失效 */
    public static final String UPLOAD_TEMP_MEDIA = "/cgi-bin/media/upload";
    public static final String GET_MATERIAL = "/cgi-bin/material/get_material";

    // 模板库 / 草稿箱（平台级资源，用 component_access_token）
    public static final String GET_TEMPLATE_DRAFT_LIST = "/wxa/gettemplatedraftlist";
    public static final String ADD_TO_TEMPLATE = "/wxa/addtotemplate";
    public static final String GET_TEMPLATE_LIST = "/wxa/gettemplatelist";
    public static final String DELETE_TEMPLATE = "/wxa/deletetemplate";

    // 域名
    public static final String MODIFY_DOMAIN = "/wxa/modify_domain";
    public static final String SET_WEBVIEW_DOMAIN = "/wxa/setwebviewdomain";
    public static final String GET_EFFECTIVE_WEBVIEW_DOMAIN = "/wxa/get_effective_webviewdomain";
    public static final String GET_WEBVIEW_CONFIRM_FILE = "/wxa/get_webviewdomain_confirmfile";

    // 类目
    public static final String ADD_CATEGORY = "/cgi-bin/wxopen/addcategory";
    public static final String DELETE_CATEGORY = "/cgi-bin/wxopen/deletecategory";
    public static final String GET_CATEGORY = "/cgi-bin/wxopen/getcategory";
    public static final String GET_ALL_CATEGORIES = "/cgi-bin/wxopen/getallcategories";

    // 基本信息
    public static final String SET_NICKNAME = "/wxa/setnickname";
    public static final String QUERY_NICKNAME = "/wxa/api_wxa_querynickname";
    public static final String CHECK_NICKNAME = "/cgi-bin/wxverify/checkwxverifynickname";
    /** 改名材料都是图片，新增临时素材的 type 取值 */
    public static final String TEMP_MEDIA_TYPE_IMAGE = "image";
    /** naming_other_stuff_1..5，微信只给 5 个槽位 */
    public static final int NAMING_OTHER_STUFF_MAX = 5;
    public static final String MODIFY_SIGNATURE = "/cgi-bin/account/modifysignature";
    public static final String MODIFY_HEAD_IMAGE = "/cgi-bin/account/modifyheadimage";
    public static final String GET_ACCOUNT_BASIC_INFO = "/cgi-bin/account/getaccountbasicinfo";

    // 体验成员
    public static final String BIND_TESTER = "/wxa/bind_tester";
    public static final String UNBIND_TESTER = "/wxa/unbind_tester";
    public static final String MEMBER_AUTH = "/wxa/memberauth";

    // 备案（未备案不能发布）
    public static final String GET_ICP_ENTRANCE_INFO = "/wxa/icp/get_icp_entrance_info";

    // 用户隐私保护指引（缺配置会被审核驳回）
    public static final String GET_PRIVACY_SETTING = "/cgi-bin/component/getprivacysetting";
    public static final String SET_PRIVACY_SETTING = "/cgi-bin/component/setprivacysetting";

    /** 审计日志用：把调用动作名映射到接口路径。 */
    public static final Map<String, String> PATH_INDEX = buildPathIndex();

    private static Map<String, String> buildPathIndex() {
        Map<String, String> index = new java.util.LinkedHashMap<>();
        index.put("commit", COMMIT);
        index.put("get_page", GET_PAGE);
        index.put("getversioninfo", GET_VERSION_INFO);
        index.put("getvisitstatus", GET_VISIT_STATUS);
        index.put("change_visitstatus", CHANGE_VISIT_STATUS);
        index.put("submit_audit", SUBMIT_AUDIT);
        index.put("get_auditstatus", GET_AUDIT_STATUS);
        index.put("get_latest_auditstatus", GET_LATEST_AUDIT_STATUS);
        index.put("undocodeaudit", UNDO_CODE_AUDIT);
        index.put("speedupaudit", SPEEDUP_AUDIT);
        index.put("release", RELEASE);
        index.put("grayrelease", GRAY_RELEASE);
        index.put("getgrayreleaseplan", GET_GRAY_RELEASE_PLAN);
        index.put("revertgrayrelease", REVERT_GRAY_RELEASE);
        index.put("revertcoderelease", REVERT_CODE_RELEASE);
        index.put("revertcoderelease?get_history_version", REVERT_CODE_RELEASE);
        index.put("uploadmedia", UPLOAD_MEDIA);
        index.put("mediaupload", UPLOAD_TEMP_MEDIA);
        index.put("get_material", GET_MATERIAL);
        index.put("modify_domain", MODIFY_DOMAIN);
        index.put("setwebviewdomain", SET_WEBVIEW_DOMAIN);
        index.put("get_effective_webviewdomain", GET_EFFECTIVE_WEBVIEW_DOMAIN);
        index.put("get_webviewdomain_confirmfile", GET_WEBVIEW_CONFIRM_FILE);
        index.put("modify_wxa_server_domain", ComponentApi.PATH_MODIFY_WXA_SERVER_DOMAIN);
        index.put("modify_wxa_jump_domain", ComponentApi.PATH_MODIFY_WXA_JUMP_DOMAIN);
        index.put("get_domain_confirmfile", ComponentApi.PATH_GET_DOMAIN_CONFIRM_FILE);
        index.put("addcategory", ADD_CATEGORY);
        index.put("deletecategory", DELETE_CATEGORY);
        index.put("getcategory", GET_CATEGORY);
        index.put("getallcategories", GET_ALL_CATEGORIES);
        index.put("setnickname", SET_NICKNAME);
        index.put("checkwxverifynickname", CHECK_NICKNAME);
        index.put("api_wxa_querynickname", QUERY_NICKNAME);
        index.put("modifysignature", MODIFY_SIGNATURE);
        index.put("modifyheadimage", MODIFY_HEAD_IMAGE);
        index.put("getaccountbasicinfo", GET_ACCOUNT_BASIC_INFO);
        index.put("bind_tester", BIND_TESTER);
        index.put("unbind_tester", UNBIND_TESTER);
        index.put("memberauth", MEMBER_AUTH);
        index.put("gettemplatedraftlist", GET_TEMPLATE_DRAFT_LIST);
        index.put("addtotemplate", ADD_TO_TEMPLATE);
        index.put("gettemplatelist", GET_TEMPLATE_LIST);
        index.put("deletetemplate", DELETE_TEMPLATE);
        index.put("get_icp_entrance_info", GET_ICP_ENTRANCE_INFO);
        index.put("getprivacysetting", GET_PRIVACY_SETTING);
        index.put("setprivacysetting", SET_PRIVACY_SETTING);
        return java.util.Collections.unmodifiableMap(index);
    }

    public static String pathOf(String action) {
        return PATH_INDEX.getOrDefault(action, action);
    }

    private MiniProgramApi() {
    }

    // -------------------------------------------------- 代码上传与体验版

    /**
     * 用模板给授权小程序上传代码并生成体验版。
     * extAppid / ext / extPages / plugins 等运行时配置都要序列化进 ext_json 字符串里。
     * template_id / user_version / user_desc 在文档里都是必填，缺失时微信直接报错，不做静默省略。
     */
    public static JsonNode commit(WechatApiClient api, String authorizerToken, String templateId,
                                  String extJson, String userVersion, String userDesc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_id", numericId(templateId));
        body.put("ext_json", extJson);
        body.put("user_version", userVersion);
        body.put("user_desc", userDesc);
        return api.post(COMMIT, authorizerToken, body);
    }

    /** 体验版二维码：微信直接返回图片二进制。文档要求 path 先做一次 urlencode（page/index?a=1 → page%2Findex%3Fa%3D1）。 */
    public static byte[] experienceQrcode(WechatApiClient api, String authorizerToken, String path) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", authorizerToken);
        if (path != null && !path.isBlank()) {
            // 页面路径带查询串（pages/x?id=1）时必须整体转义，WechatApiClient 会对查询值编码
            query.put("path", path.trim());
        }
        return api.getBinary(GET_QRCODE, query);
    }

    public static JsonNode pageList(WechatApiClient api, String authorizerToken) {
        return api.get(GET_PAGE, authorizerToken);
    }

    /** exp_version/exp_time 与 release_version/release_time，用于展示体验版与线上版。 */
    public static JsonNode versionInfo(WechatApiClient api, String authorizerToken) {
        return api.post(GET_VERSION_INFO, authorizerToken, Map.of());
    }

    /** status：0 表示已暂停服务，1 表示未暂停服务。 */
    public static JsonNode visitStatus(WechatApiClient api, String authorizerToken) {
        return api.post(GET_VISIT_STATUS, authorizerToken, Map.of());
    }

    /** 暂停/恢复服务：action 只能是 open（可见）或 close（不可见），响应只有 errcode/errmsg。 */
    public static JsonNode changeVisitStatus(WechatApiClient api, String authorizerToken, String action) {
        return api.post(CHANGE_VISIT_STATUS, authorizerToken, Map.of("action", action));
    }

    // -------------------------------------------------- 审核

    /**
     * 提交审核。itemList 每项形如
     * {address, title, tag, first_class, first_id, second_class, second_id, third_class, third_id}：
     * 类目要名称与数字 id 成对提交（一级/二级必填，三级选填），tag 是空格分隔的页面标签而非类目编号，
     * 至多 5 项。组装逻辑见 ReleaseService#toItemLists。
     */
    public static JsonNode submitAudit(WechatApiClient api, String authorizerToken, List<Map<String, Object>> itemList,
                                       String feedbackInfo, String versionDesc, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (itemList != null && !itemList.isEmpty()) {
            body.put("item_list", itemList);
        }
        put(body, "feedback_info", feedbackInfo);
        put(body, "version_desc", versionDesc);
        if (extra != null) {
            body.putAll(extra);
        }
        return api.post(SUBMIT_AUDIT, authorizerToken, body);
    }

    public static JsonNode auditStatus(WechatApiClient api, String authorizerToken, String auditId) {
        return api.post(GET_AUDIT_STATUS, authorizerToken, Map.of("auditid", numericId(auditId)));
    }

    public static JsonNode latestAuditStatus(WechatApiClient api, String authorizerToken) {
        return api.get(GET_LATEST_AUDIT_STATUS, authorizerToken);
    }

    public static JsonNode undoAudit(WechatApiClient api, String authorizerToken) {
        return api.get(UNDO_CODE_AUDIT, authorizerToken);
    }

    public static JsonNode speedupAudit(WechatApiClient api, String authorizerToken, String auditId) {
        return api.post(SPEEDUP_AUDIT, authorizerToken, Map.of("auditid", numericId(auditId)));
    }

    /** 微信把 auditid / template_id 这类 id 声明为 number，本地统一按字符串保存；非数字原样交给微信，由其返回错误码。 */
    private static Object numericId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (RuntimeException ex) {
            return value;
        }
    }

    // -------------------------------------------------- 发布 / 灰度 / 回退

    /** 发布已通过审核的版本（全量）。 */
    public static JsonNode release(WechatApiClient api, String authorizerToken) {
        return api.post(RELEASE, authorizerToken, Map.of());
    }

    /** 分阶段发布：gray_percentage 是 0~100 的整数（必填），0 时需二选一开启优先灰度人群。 */
    public static JsonNode grayRelease(WechatApiClient api, String authorizerToken, Integer grayPercentage,
                                       Boolean supportDebuggerFirst, Boolean supportExperiencerFirst) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gray_percentage", grayPercentage);
        put(body, "support_debuger_first", supportDebuggerFirst);
        put(body, "support_experiencer_first", supportExperiencerFirst);
        return api.post(GRAY_RELEASE, authorizerToken, body);
    }

    public static JsonNode grayReleasePlan(WechatApiClient api, String authorizerToken) {
        return api.get(GET_GRAY_RELEASE_PLAN, authorizerToken);
    }

    /** 取消灰度，回到灰度前的线上版本。 */
    public static JsonNode revertGrayRelease(WechatApiClient api, String authorizerToken) {
        return api.get(REVERT_GRAY_RELEASE, authorizerToken);
    }

    /** 回退线上代码到历史版本，appVersion 留空则回退到上一版。 */
    public static JsonNode revertCodeRelease(WechatApiClient api, String authorizerToken, String appVersion) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", authorizerToken);
        if (appVersion != null && !appVersion.isBlank()) {
            query.put("app_version", appVersion.trim());
        }
        return api.getWithQuery(REVERT_CODE_RELEASE, query);
    }

    /** 可回退的历史版本列表 version_list[{app_version,user_version,user_desc,commit_time}]。 */
    public static JsonNode historyVersions(WechatApiClient api, String authorizerToken) {
        return api.getWithQuery(REVERT_CODE_RELEASE,
                Map.of("access_token", authorizerToken, "action", "get_history_version"));
    }

    // -------------------------------------------------- 模板库与草稿箱（component token）

    public static JsonNode templateDraftList(WechatApiClient api, String componentToken) {
        return api.getWithQuery(GET_TEMPLATE_DRAFT_LIST, componentQuery(componentToken));
    }

    /** addtotemplate 的 draft_id 是 number；template_type 默认 0（普通模板），1 为标准模板库。 */
    public static JsonNode addToTemplate(WechatApiClient api, String componentToken, long draftId, Integer templateType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draft_id", draftId);
        put(body, "template_type", templateType);
        return api.postComponent(ADD_TO_TEMPLATE, componentToken, body);
    }

    /** gettemplatelist 不带 template_type 时返回全部模板，带上就只剩该类型，普通模板会被筛掉。 */
    public static JsonNode templateList(WechatApiClient api, String componentToken) {
        return api.getWithQuery(GET_TEMPLATE_LIST, componentQuery(componentToken));
    }

    public static JsonNode deleteTemplate(WechatApiClient api, String componentToken, String templateId) {
        return api.postComponent(DELETE_TEMPLATE, componentToken, Map.of("template_id", numericId(templateId)));
    }

    // -------------------------------------------------- 域名

    /**
     * 服务器域名。domainKey 取值 requestdomain/wsrequestdomain/uploaddomain/downloaddomain/udpdomain/tcpdomain
     * （官方字段名没有下划线分隔）。
     */
    public static JsonNode modifyDomain(WechatApiClient api, String authorizerToken, String action,
                                        Map<String, List<String>> domains) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (domains != null) {
            domains.forEach((key, value) -> {
                if (value != null && !value.isEmpty()) {
                    body.put(key, value);
                }
            });
        }
        return api.post(MODIFY_DOMAIN, authorizerToken, body);
    }

    public static JsonNode setWebviewDomain(WechatApiClient api, String authorizerToken, String action,
                                           List<String> domains) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        put(body, "webviewdomain", domains);
        return api.post(SET_WEBVIEW_DOMAIN, authorizerToken, body);
    }

    /** 业务域名的读取只能走这里：setwebviewdomain 的响应只有 errcode/errmsg。 */
    public static JsonNode effectiveWebviewDomains(WechatApiClient api, String authorizerToken) {
        return api.post(GET_EFFECTIVE_WEBVIEW_DOMAIN, authorizerToken, Map.of());
    }

    /** 业务域名校验文件（fileName + fileContent，需放到授权小程序域名根下）。 */
    public static JsonNode webviewDomainConfirmFile(WechatApiClient api, String authorizerToken) {
        return api.post(GET_WEBVIEW_CONFIRM_FILE, authorizerToken, Map.of());
    }

    // -------------------------------------------------- 类目

    public static JsonNode addCategory(WechatApiClient api, String authorizerToken, List<Map<String, Object>> categories) {
        return api.post(ADD_CATEGORY, authorizerToken, Map.of("categories", categories));
    }

    public static JsonNode deleteCategory(WechatApiClient api, String authorizerToken,
                                         Integer first, Integer second, Integer third) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("first", first);
        body.put("second", second);
        put(body, "third", third);
        return api.post(DELETE_CATEGORY, authorizerToken, body);
    }

    public static JsonNode categories(WechatApiClient api, String authorizerToken) {
        return api.get(GET_CATEGORY, authorizerToken);
    }

    public static JsonNode allCategories(WechatApiClient api, String authorizerToken) {
        return api.get(GET_ALL_CATEGORIES, authorizerToken);
    }

    // -------------------------------------------------- 备案与隐私指引

    /** 备案入口信息与状态。未备案时 status 用低位标出缺哪个前置条件，见文档枚举 1024~1031。 */
    public static JsonNode icpEntranceInfo(WechatApiClient api, String authorizerToken) {
        return api.get(GET_ICP_ENTRANCE_INFO, authorizerToken);
    }

    /** 查询用户隐私保护指引。privacy_ver：1 现网版本 / 2 开发版（默认）。 */
    public static JsonNode privacySetting(WechatApiClient api, String authorizerToken, Integer privacyVer) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "privacy_ver", privacyVer);
        return api.post(GET_PRIVACY_SETTING, authorizerToken, body);
    }

    /**
     * 设置用户隐私保护指引。owner_setting 是微信的必填项；
     * setting_list 只在开发版（privacy_ver=2）可传，现网版传了会报错。
     */
    public static JsonNode setPrivacySetting(WechatApiClient api, String authorizerToken, Integer privacyVer,
                                             List<Map<String, Object>> settingList,
                                             Map<String, Object> ownerSetting) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "privacy_ver", privacyVer);
        if (settingList != null && !settingList.isEmpty()) {
            body.put("setting_list", settingList);
        }
        body.put("owner_setting", ownerSetting);
        return api.post(SET_PRIVACY_SETTING, authorizerToken, body);
    }

    /**
     * 取永久素材内容（审核驳回截图就是图片素材）。
     * 图片素材微信直接回二进制，出错回 JSON，故走 postBinary 由客户端按 Content-Type 区分。
     */
    public static byte[] material(WechatApiClient api, String authorizerToken, String mediaId) {
        return api.postBinary(GET_MATERIAL, authorizerToken, Map.of("media_id", mediaId));
    }

    // -------------------------------------------------- 基本信息

    /**
     * 改名材料：全部是「新增临时素材」换来的 media_id。
     *
     * <p>微信 /wxa/setnickname 只认 {@code nick_name}、{@code id_card}、{@code license}、
     * {@code naming_other_stuff_1..5} 这几个键，且值必须是字符串：多发字段或把多个 media_id
     * 拼成数组都会被判成 47001 data format error。
     */
    public record NicknameMaterials(String idCard, String license, List<String> namingOtherStuff) {
    }

    /** 名称未命中关键词时可以只传 nick_name；命中后按主体类型补 id_card（个人）或 license（组织）。 */
    public static JsonNode setNickname(WechatApiClient api, String authorizerToken, String nickname,
                                       NicknameMaterials materials) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nick_name", nickname);
        if (materials != null) {
            putIfHasText(body, "id_card", materials.idCard());
            putIfHasText(body, "license", materials.license());
            List<String> stuff = materials.namingOtherStuff();
            if (stuff != null) {
                for (int i = 0; i < stuff.size(); i++) {
                    putIfHasText(body, "naming_other_stuff_" + (i + 1), stuff.get(i));
                }
            }
        }
        return api.post(SET_NICKNAME, authorizerToken, body);
    }

    private static void putIfHasText(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    public static JsonNode checkNickname(WechatApiClient api, String authorizerToken, String nickname) {
        return api.post(CHECK_NICKNAME, authorizerToken, Map.of("nick_name", nickname));
    }

    public static JsonNode queryNickname(WechatApiClient api, String authorizerToken, String auditId) {
        return api.post(QUERY_NICKNAME, authorizerToken, Map.of("audit_id", numericId(auditId)));
    }

    public static JsonNode modifySignature(WechatApiClient api, String authorizerToken, String signature) {
        return api.post(MODIFY_SIGNATURE, authorizerToken, Map.of("signature", signature));
    }

    /**
     * 头像的 head_img_media_id 必须来自「新增临时素材」，且那份 media_id 要用 authorizer_access_token
     * 取（文档明确：别的令牌取来的都会报 40007）。x1y1x2y2 是裁剪框的比例坐标（取值 [0, 1]），
     * 文档要求发成字符串，整数像素值会被微信拒绝。
     */
    public static JsonNode modifyHeadImage(WechatApiClient api, String authorizerToken, String headImgMediaId,
                                           String x1, String y1, String x2, String y2) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("head_img_media_id", headImgMediaId);
        body.put("x1", x1);
        body.put("y1", y1);
        body.put("x2", x2);
        body.put("y2", y2);
        return api.post(MODIFY_HEAD_IMAGE, authorizerToken, body);
    }

    public static JsonNode accountBasicInfo(WechatApiClient api, String authorizerToken) {
        return api.get(GET_ACCOUNT_BASIC_INFO, authorizerToken);
    }

    /** 提审素材上传（截图 / 录屏），multipart 字段名固定为 media。 */
    public static JsonNode uploadMedia(WechatApiClient api, String authorizerToken, String filename, byte[] content) {
        return api.postMultipart(UPLOAD_MEDIA, authorizerToken, Map.of(), "media", filename, content);
    }

    /**
     * 新增临时素材：改名材料走这个接口，返回的是 {@code media_id}（{@link #UPLOAD_MEDIA} 返回的是
     * {@code mediaid}，两者键名不同）。type 在 query 上，文件部分名固定 media。
     */
    public static JsonNode uploadTempMedia(WechatApiClient api, String authorizerToken, String filename,
                                           byte[] content) {
        return api.postMultipart(UPLOAD_TEMP_MEDIA,
                Map.of("access_token", authorizerToken, "type", TEMP_MEDIA_TYPE_IMAGE), "media", filename, content);
    }

    // -------------------------------------------------- 体验成员

    public static JsonNode bindTester(WechatApiClient api, String authorizerToken, String wechatId) {
        return api.post(BIND_TESTER, authorizerToken, Map.of("wechatid", wechatId));
    }

    /** 绑定返回的 userstr 更能唯一定位成员，优先用它解绑。 */
    public static JsonNode unbindTester(WechatApiClient api, String authorizerToken, String wechatId, String userStr) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "wechatid", wechatId);
        put(body, "userstr", userStr);
        return api.post(UNBIND_TESTER, authorizerToken, body);
    }

    public static JsonNode testerList(WechatApiClient api, String authorizerToken) {
        return api.post(MEMBER_AUTH, authorizerToken, Map.of("action", "get_experiencer"));
    }

    // -------------------------------------------------- helpers

    private static Map<String, String> componentQuery(String componentToken) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", componentToken);
        query.put("component_access_token", componentToken);
        return query;
    }

    private static void put(Map<String, Object> body, String key, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            body.put(key, value);
        }
    }

    private static void put(Map<String, String> query, String key, String value) {
        if (value != null && !value.isBlank()) {
            query.put(key, value);
        }
    }
}
