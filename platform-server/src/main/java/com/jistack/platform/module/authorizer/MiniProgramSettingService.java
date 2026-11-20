package com.jistack.platform.module.authorizer;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.MiniProgramApi;
import com.jistack.platform.wechat.service.AuthorizerWriter;
import com.jistack.platform.wechat.service.WechatCaller;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 小程序运营配置：基本信息（名称/简介/头像）、服务器域名与业务域名、类目、体验成员。
 * 这些接口都通过 authorizer_access_token 代小程序操作。
 */
@Service
@RequiredArgsConstructor
public class MiniProgramSettingService {

    /** 界面用的域名类型 → 微信字段名（官方字段没有下划线）。 */
    private static final Map<String, String> DOMAIN_KEYS = Map.of(
            "request", "requestdomain",
            "ws_request", "wsrequestdomain",
            "upload_file", "uploaddomain",
            "download_file", "downloaddomain",
            "udp", "udpdomain",
            "tcp", "tcpdomain");

    private static final List<String> DOMAIN_ORDER = List.of("request", "ws_request", "upload_file",
            "download_file", "udp", "tcp");

    /** 输出给前端的字段名统一驼峰，避免下划线/微信字段名混用。 */
    private static final Map<String, String> DOMAIN_CAMEL = Map.of(
            "request", "request",
            "ws_request", "wsRequest",
            "upload_file", "uploadFile",
            "download_file", "downloadFile",
            "udp", "udp",
            "tcp", "tcp");

    /** 微信文档对图片类临时素材的限制：不超过 2M，格式 PNG/JPG/JPEG/GIF/BMP（头像与改名材料共用）。 */
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private final WechatCaller caller;
    private final AuthorizerWriter authorizerWriter;

    // -------------------------------------------------- 基本信息

    /** 名称、头像、功能介绍等；资料未完善时微信会返回 86002。 */
    public Map<String, Object> basicInfo(String appid) {
        Authorizer authorizer = authorizerWriter.require(appid);
        JsonNode account = caller.authorizer(appid, "getaccountbasicinfo", MiniProgramApi::accountBasicInfo);
        // 新版文档：signature_info.signature / head_image_info.head_image_url / nickname_info.nickname，
        // 平铺字段是老版本写法，两者都缺时用 api_get_authorizer_info 落库的记录兜底
        JsonNode signatureInfo = account.path("signature_info");
        JsonNode headImageInfo = account.path("head_image_info");
        JsonNode nicknameInfo = account.path("nickname_info");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nickname", orText(
                firstNonEmpty(nicknameInfo.path("nickname"), account.path("nickname"), account.path("nick_name")),
                authorizer.getNickName()));
        result.put("signature", orText(
                firstNonEmpty(signatureInfo.path("signature"), account.path("signature")),
                authorizer.getVerifyInfo()));
        result.put("avatarUrl", orText(
                firstNonEmpty(headImageInfo.path("head_image_url"), account.path("head_image_url"),
                        account.path("head_img")),
                authorizer.getHeadImg()));
        result.put("qrCodeUrl", qrcodeUrlOf(account, authorizer));
        result.put("userName", orText(account.path("user_name").asText(null), authorizer.getOriginalUsername()));
        result.put("principalName", orText(account.path("principal_name").asText(null), authorizer.getPrincipalName()));
        result.put("openVerify", verifyFlag(account, authorizer.getVerifyType()));
        result.put("openScannedSearch", account.path("open_scanned_search").asInt(-1));
        result.put("registerType", authorizer.getRegisterType());
        // 微信 91001：只有「通过 API 注册」的小程序能用 setnickname 改名；未刷新过资料时给 null，界面不拦
        result.put("apiRenameSupported", authorizer.getRegisterType() == null
                ? null : authorizer.getRegisterType() != 0);
        result.put("raw", account);
        return result;
    }

    /** 普通方式注册的小程序微信不允许接口改名（91001），提前拦下，别让管理员空等一次调用。 */
    private void requireApiRenameSupported(String appid) {
        Integer registerType = authorizerWriter.require(appid).getRegisterType();
        if (registerType != null && registerType == 0) {
            throw new BizException("该小程序是普通方式注册（非第三方平台 API 注册），微信不允许通过接口改名；"
                    + "请小程序管理员登录微信公众平台，在「设置 → 基本设置 → 名称」中修改");
        }
    }

    /**
     * 小程序码图片。qrcode_url 指向 mp.weixin.qq.com 的 showqrcode，浏览器带本站 Referer 直连会被微信
     * 换成「此图片来自微信公众平台 未经允许不可引用」的占位图，所以由后端取回原图，界面拿 data URL 显示。
     */
    public Map<String, Object> qrcodeImage(String appid) {
        String url = resolveQrcodeUrl(appid);
        byte[] image = caller.api().getBinaryAbsolute(url);
        String format = imageFormat(image);
        if (format == null) {
            throw new BizException("取回的内容不是图片（PNG/JPG/GIF/BMP），微信这次没有给出小程序码");
        }
        String contentType = "image/" + ("jpg".equals(format) ? "jpeg" : format);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("contentType", contentType);
        result.put("size", image.length);
        result.put("dataUrl", "data:" + contentType + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(image));
        return result;
    }

    /** 落库的 qrcode_url 来自 api_get_authorizer_info；这一列为空的旧数据才再问一次 getaccountbasicinfo。 */
    private String resolveQrcodeUrl(String appid) {
        Authorizer authorizer = authorizerWriter.require(appid);
        if (StringUtils.hasText(authorizer.getQrcodeUrl())) {
            return authorizer.getQrcodeUrl();
        }
        JsonNode account = caller.authorizer(appid, "getaccountbasicinfo", MiniProgramApi::accountBasicInfo);
        String url = qrcodeUrlOf(account, authorizer);
        if (!StringUtils.hasText(url)) {
            throw new BizException("微信未返回该小程序的小程序码链接，请先在授权列表「刷新资料」后重试");
        }
        return url;
    }

    /** 新版 getaccountbasicinfo 已不返回小程序码链接，只有 api_get_authorizer_info 的 authorizer_info.qrcode_url 有。 */
    private static String qrcodeUrlOf(JsonNode account, Authorizer authorizer) {
        return orText(account == null ? null
                : firstNonEmpty(account.path("qr_code_url"), account.path("qrcode_url")), authorizer.getQrcodeUrl());
    }

    /** 认证状态：新版是 wx_verify_info 的两个布尔，老版是 open_verify，都没有时沿用授权方信息里的 verify_type。 */
    private static int verifyFlag(JsonNode account, Integer verifyType) {
        JsonNode wxVerify = account.path("wx_verify_info");
        if (wxVerify.isObject()) {
            boolean verified = wxVerify.path("qualification_verify").asBoolean(false)
                    || wxVerify.path("naming_verify").asBoolean(false);
            return verified ? 1 : 0;
        }
        if (account.hasNonNull("open_verify")) {
            return account.path("open_verify").asInt(-1);
        }
        if (verifyType != null) {
            // authorizer_info.verify_type_info.id：-1 未认证，>=0 为已认证类型
            return verifyType >= 0 ? 1 : 0;
        }
        return -1;
    }

    /** 改名前先查是否命中关键词：命中才需要提交材料，没命中直接改名即可。 */
    public Map<String, Object> checkNickname(String appid, String nickname) {
        requireText(nickname, "小程序名称");
        JsonNode node = caller.authorizer(appid, "checkwxverifynickname",
                (api, token) -> MiniProgramApi.checkNickname(api, token, nickname));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hitCondition", node.path("hit_condition").asBoolean(false));
        result.put("wording", node.path("wording").asText(""));
        return result;
    }

    /**
     * 修改名称：材料是临时素材的 media_id，命中关键词却被微信判为 91009 时要补齐材料重新提交。
     * 返回的 auditId 为空表示名称未命中关键词、已直接生效；有值则进入审核，可拿去 queryNickname 查状态。
     */
    public Map<String, Object> setNickname(String appid, String nickname, String idCard, String license,
                                           List<String> namingOtherStuff) {
        requireText(nickname, "小程序名称");
        requireApiRenameSupported(appid);
        List<String> stuff = namingOtherStuff == null ? List.of() : namingOtherStuff.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (stuff.size() > MiniProgramApi.NAMING_OTHER_STUFF_MAX) {
            throw new BizException("其他证明材料最多 " + MiniProgramApi.NAMING_OTHER_STUFF_MAX + " 份");
        }
        MiniProgramApi.NicknameMaterials materials =
                new MiniProgramApi.NicknameMaterials(trimToNull(idCard), trimToNull(license), stuff);
        JsonNode node = caller.authorizer(appid, "setnickname",
                (api, token) -> MiniProgramApi.setNickname(api, token, nickname, materials));
        // 微信只在需要审核时给 audit_id，缺省即已直接生效
        JsonNode auditId = node.path("audit_id");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditId", auditId.isValueNode() && !auditId.isNull() ? auditId.asText() : null);
        result.put("wording", node.path("wording").asText(""));
        return result;
    }

    /** 材料图片先传成临时素材换 media_id，界面拿它填进身份证/营业执照等字段。 */
    public String uploadNicknameMaterial(String appid, String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BizException("请选择要上传的材料图片");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw new BizException("材料图片不能超过 2M，当前 " + (content.length / 1024) + "K，请先压缩");
        }
        JsonNode node = caller.authorizer(appid, "mediaupload",
                (api, token) -> MiniProgramApi.uploadTempMedia(api, token,
                        StringUtils.hasText(filename) ? filename : "material.png", content));
        String mediaId = node.path("media_id").asText(null);
        if (!StringUtils.hasText(mediaId)) {
            throw new BizException(502, "材料上传未返回 media_id");
        }
        return mediaId;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public JsonNode queryNickname(String appid, String auditId) {
        requireText(auditId, "名称审核单号");
        return caller.authorizer(appid, "api_wxa_querynickname",
                (api, token) -> MiniProgramApi.queryNickname(api, token, auditId));
    }

    public JsonNode setSignature(String appid, String signature) {
        requireText(signature, "小程序简介");
        return caller.authorizer(appid, "modifysignature",
                (api, token) -> MiniProgramApi.modifySignature(api, token, signature));
    }

    /** 头像：图片先上传拿 media_id，再按裁剪框提交。裁剪框是 0~1 的比例坐标，留空表示整图裁剪。 */
    public JsonNode setAvatar(String appid, byte[] image, String filename,
                              Double x1, Double y1, Double x2, Double y2) {
        if (image == null || image.length == 0) {
            throw new BizException("请选择要上传的头像图片");
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new BizException("头像图片不能超过 2M，当前 " + (image.length / 1024) + "K，请先压缩或裁成正方形");
        }
        String format = imageFormat(image);
        if (format == null) {
            throw new BizException("头像只支持 PNG、JPG、GIF、BMP 图片（按文件内容判断），WebP/HEIC 请先转成 JPG 或 PNG");
        }
        String name = avatarFilename(filename, format);
        String[] crop = {
                x1 == null ? "0" : ratio(x1, "x1"),
                y1 == null ? "0" : ratio(y1, "y1"),
                x2 == null ? "1" : ratio(x2, "x2"),
                y2 == null ? "1" : ratio(y2, "y2")
        };
        String mediaId = caller.authorizer(appid, "mediaupload",
                (api, token) -> MiniProgramApi.uploadTempMedia(api, token, name, image))
                .path("media_id").asText(null);
        if (!StringUtils.hasText(mediaId)) {
            throw new BizException(502, "头像上传未返回 media_id");
        }
        return caller.authorizer(appid, "modifyheadimage",
                (api, token) -> MiniProgramApi.modifyHeadImage(api, token, mediaId,
                        crop[0], crop[1], crop[2], crop[3]));
    }

    /** 微信侧坐标声明为 [0,1] 的字符串，像素值或越界值直接拦下来。 */
    private static String ratio(double value, String name) {
        if (value < 0 || value > 1) {
            throw new BizException("裁剪坐标 " + name + " 需为 0~1 的比例值，当前 " + value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** 微信按文件内容判断素材类型，不看本地文件名：这里用文件头识别，认不出就拒掉（WebP/HEIC/非图片）。 */
    private static String imageFormat(byte[] image) {
        if (image.length > 4 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
            return "png";
        }
        if (image.length > 2 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) {
            return "jpg";
        }
        if (image.length > 3 && image[0] == 'G' && image[1] == 'I' && image[2] == 'F' && image[3] == '8') {
            return "gif";
        }
        if (image.length > 2 && image[0] == 'B' && image[1] == 'M') {
            return "bmp";
        }
        return null;
    }

    /** multipart 的文件名会当作素材类型发给微信，后缀与真实格式不符（或没有后缀）时按真实格式改名。 */
    private static String avatarFilename(String filename, String format) {
        String name = StringUtils.hasText(filename) ? filename.trim() : "";
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean jpg = "jpg".equals(format) || "jpeg".equals(format);
        boolean matched = suffix.equals(format) || (jpg && ("jpg".equals(suffix) || "jpeg".equals(suffix)));
        return matched ? name : "avatar." + format;
    }

    // -------------------------------------------------- 域名

    public Map<String, Object> readDomains(String appid) {
        authorizerWriter.require(appid);
        JsonNode node = caller.authorizer(appid, "modify_domain",
                (api, token) -> MiniProgramApi.modifyDomain(api, token, "get", Map.of()));
        Map<String, Object> result = new LinkedHashMap<>();
        for (String type : DOMAIN_ORDER) {
            result.put(camel(type), toArray(node.path(DOMAIN_KEYS.get(type))));
        }
        result.put("invalid", invalidDomains(node));
        result.put("webview", readWebviewDomains(appid));
        return result;
    }

    public Map<String, Object> updateDomains(String appid, String action, String domainType, List<String> domains) {
        String type = canonicalDomainType(domainType);
        String key = DOMAIN_KEYS.get(type);
        if (key == null) {
            throw new BizException("域名类型不合法，可选：" + String.join("/", DOMAIN_ORDER));
        }
        if (!List.of("add", "delete", "set").contains(action)) {
            throw new BizException("action 只能是 add/delete/set");
        }
        if (domains == null || domains.isEmpty()) {
            throw new BizException("域名列表不能为空（要移除域名请用 delete 指定具体条目）");
        }
        JsonNode node = caller.authorizer(appid, "modify_domain",
                (api, token) -> MiniProgramApi.modifyDomain(api, token, action, Map.of(key, domains)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(camel(type), toArray(node.path(key)));
        result.put("invalid", invalidDomains(node));
        return result;
    }

    /**
     * 业务域名清单。setwebviewdomain 只返回 errcode/errmsg，微信给出的读取入口是
     * get_effective_webviewdomain，它按来源分成公众平台配置、第三方接口配置、直接配置三份列表。
     */
    public List<String> readWebviewDomains(String appid) {
        JsonNode node = caller.authorizer(appid, "get_effective_webviewdomain",
                MiniProgramApi::effectiveWebviewDomains);
        List<String> domains = new ArrayList<>();
        for (String key : List.of("mp_webviewdomain", "third_webviewdomain", "direct_webviewdomain")) {
            node.path(key).forEach(item -> {
                String domain = item.asText(null);
                if (domain != null && !domain.isBlank() && !domains.contains(domain)) {
                    domains.add(domain);
                }
            });
        }
        return domains;
    }

    public List<String> updateWebviewDomains(String appid, String action, List<String> domains) {
        if (!List.of("add", "delete", "set").contains(action)) {
            throw new BizException("action 只能是 add/delete/set");
        }
        if (domains == null || domains.isEmpty()) {
            throw new BizException("业务域名列表不能为空（要移除域名请用 delete 指定具体条目）");
        }
        caller.authorizer(appid, "setwebviewdomain",
                (api, token) -> MiniProgramApi.setWebviewDomain(api, token, action, domains));
        return readWebviewDomains(appid);
    }

    /** 业务域名校验文件：需把 fileContent 放到域名根目录下同名文件。 */
    public Map<String, Object> webviewConfirmFile(String appid) {
        JsonNode node = caller.authorizer(appid, "get_webviewdomain_confirmfile",
                MiniProgramApi::webviewDomainConfirmFile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", node.path("file_name").asText(null));
        result.put("fileContent", node.path("file_content").asText(null));
        return result;
    }

    // -------------------------------------------------- 用户隐私保护指引

    /** 查询隐私保护指引。privacy_ver 微信必填：1 现网版本 / 2 开发版，不传按开发版查。 */
    public JsonNode privacySetting(String appid, Integer privacyVer) {
        int ver = requirePrivacyVer(privacyVer);
        return caller.authorizer(appid, "getprivacysetting",
                (api, token) -> MiniProgramApi.privacySetting(api, token, ver));
    }

    private static int requirePrivacyVer(Integer privacyVer) {
        if (privacyVer == null) {
            return 2;
        }
        if (privacyVer != 1 && privacyVer != 2) {
            throw new BizException("privacy_ver 只能是 1（现网版本）或 2（开发版），当前 " + privacyVer);
        }
        return privacyVer;
    }

    /**
     * 保存隐私保护指引。owner_setting 是微信的必填项；改现网版本（privacy_ver=1）时只能改收集方信息，
     * 带上 setting_list 会被微信拒绝。
     */
    public JsonNode updatePrivacySetting(String appid, Integer privacyVer,
                                         List<Map<String, Object>> settingList,
                                         Map<String, Object> ownerSetting) {
        if (ownerSetting == null || ownerSetting.isEmpty()) {
            throw new BizException("必须填写收集方信息 owner_setting（notice_method 为微信必填项）");
        }
        int ver = requirePrivacyVer(privacyVer);
        boolean hasSettings = settingList != null && !settingList.isEmpty();
        if (ver == 1 && hasSettings) {
            throw new BizException("privacy_ver=1（现网版本）只允许改收集方信息，收集用途请在开发版设置");
        }
        if (ver == 2 && !hasSettings) {
            throw new BizException("开发版必须提交收集用途 setting_list；只改收集方信息请选现网版本（privacy_ver=1）");
        }
        return caller.authorizer(appid, "setprivacysetting",
                (api, token) -> MiniProgramApi.setPrivacySetting(api, token, ver, settingList, ownerSetting));
    }

    // -------------------------------------------------- 类目

    public Map<String, Object> categories(String appid) {
        JsonNode node = caller.authorizer(appid, "getcategory", MiniProgramApi::categories);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", node.path("categories"));
        result.put("limit", node.path("limit").asInt(0));
        result.put("quota", node.path("quota").asInt(0));
        result.put("categoryLimit", node.path("category_limit").asInt(0));
        return result;
    }

    public JsonNode allCategories(String appid) {
        return caller.authorizer(appid, "getallcategories", MiniProgramApi::allCategories);
    }

    public JsonNode addCategory(String appid, Integer first, Integer second, Integer third,
                                List<Map<String, Object>> certificates) {
        requireCategory(first, second);
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("first", first);
        category.put("second", second);
        if (third != null) {
            category.put("third", third);
        }
        if (certificates != null && !certificates.isEmpty()) {
            category.put("certicates", certificates);
        }
        return caller.authorizer(appid, "addcategory",
                (api, token) -> MiniProgramApi.addCategory(api, token, List.of(category)));
    }

    public JsonNode deleteCategory(String appid, Integer first, Integer second, Integer third) {
        requireCategory(first, second);
        return caller.authorizer(appid, "deletecategory",
                (api, token) -> MiniProgramApi.deleteCategory(api, token, first, second, third));
    }

    // -------------------------------------------------- 体验成员

    public List<Map<String, Object>> testers(String appid) {
        JsonNode node = caller.authorizer(appid, "memberauth", MiniProgramApi::testerList);
        List<Map<String, Object>> result = new ArrayList<>();
        node.path("members").forEach(member -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userstr", member.path("userstr").asText(null));
            item.put("wechatId", null);
            result.add(item);
        });
        return result;
    }

    public String bindTester(String appid, String wechatId) {
        requireText(wechatId, "成员微信号");
        JsonNode node = caller.authorizer(appid, "bind_tester",
                (api, token) -> MiniProgramApi.bindTester(api, token, wechatId));
        return node.path("userstr").asText(null);
    }

    public void unbindTester(String appid, String wechatId, String userStr) {
        if (!StringUtils.hasText(wechatId) && !StringUtils.hasText(userStr)) {
            throw new BizException("需提供微信号或成员唯一标识");
        }
        caller.authorizer(appid, "unbind_tester",
                (api, token) -> MiniProgramApi.unbindTester(api, token, wechatId, userStr));
    }

    // -------------------------------------------------- helpers

    /** 下划线类型名 → 前端驼峰字段名；未登记时原样返回。 */
    private static String camel(String type) {
        return DOMAIN_CAMEL.getOrDefault(type, type);
    }

    /** 前端可传驼峰或下划线，统一成 DOMAIN_KEYS 的键。 */
    private static String canonicalDomainType(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim();
        if (DOMAIN_KEYS.containsKey(value)) {
            return value;
        }
        for (Map.Entry<String, String> entry : DOMAIN_CAMEL.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return value;
    }

    private static Map<String, Object> invalidDomains(JsonNode node) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        DOMAIN_KEYS.forEach((type, key) -> {
            JsonNode invalidNode = node.path("invalid_" + key);
            if (invalidNode.isArray() && !invalidNode.isEmpty()) {
                invalid.put(camel(type), invalidNode);
            }
        });
        if (node.hasNonNull("no_icp_domain") && node.path("no_icp_domain").isArray()
                && !node.path("no_icp_domain").isEmpty()) {
            invalid.put("noIcp", node.path("no_icp_domain"));
        }
        return invalid;
    }

    private static List<String> toArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private static void requireCategory(Integer first, Integer second) {
        if (first == null || second == null) {
            throw new BizException("类目的一级与二级编号必填");
        }
    }

    private static void requireText(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(label + "不能为空");
        }
    }

    private static String orText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    /** 微信在不同版本里把同一个字段放在嵌套对象或根节点，按顺序取第一个有值的。 */
    private static String firstNonEmpty(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isNull() || !node.isValueNode()) {
                continue;
            }
            String value = node.asText();
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
