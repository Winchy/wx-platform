package com.jistack.platform.module.release;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.MiniProgramApi;
import com.jistack.platform.wechat.service.AuthorizerWriter;
import com.jistack.platform.wechat.service.WechatCaller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序研发发布链路：上传代码（体验版）→ 提交审核 → 查询/撤回审核 → 发布 / 分阶段灰度 → 回退历史版本。
 * 每步都写操作日志，失败时把微信 errcode/errmsg 原样带回界面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseService {

    private final WechatCaller caller;
    private final AuthorizerWriter authorizerWriter;
    private final AppReleaseMapper releaseMapper;
    private final AuditRecordMapper auditMapper;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------- 上传代码

    @Transactional
    public AppRelease commit(String authorizerAppid, CommitRequest request) {
        authorizerWriter.require(authorizerAppid);
        if (!StringUtils.hasText(request.templateId())) {
            throw new BizException("必须选择代码模板（可用草稿箱转模板后获得 template_id）");
        }
        String userVersion = trimmedToRequired(request.userVersion(), "版本号 user_version");
        if (userVersion.length() > 64) {
            throw new BizException("版本号最长 64 字符，当前 " + userVersion.length() + " 字符");
        }
        String userDesc = trimmedToRequired(request.userDesc(), "版本描述 user_desc");
        String extJson = StringUtils.hasText(request.extJson())
                ? request.extJson()
                : buildExtJson(authorizerAppid, request.ext(), request.extPages());

        JsonNode response = callAuth(authorizerAppid, "commit",
                (api, token) -> MiniProgramApi.commit(api, token, request.templateId().trim(), extJson,
                        userVersion, userDesc));

        AppRelease release = new AppRelease();
        release.setAuthorizerAppid(authorizerAppid);
        release.setTemplateId(request.templateId().trim());
        release.setExtAppid(authorizerAppid);
        release.setUserVersion(response.path("user_version").asText(userVersion));
        release.setUserDesc(userDesc);
        release.setStatus("COMMITTED");
        release.setErrcode(0);
        release.setCreatedAt(LocalDateTime.now());
        var principal = com.jistack.platform.module.system.RequestContext.get();
        if (principal != null) {
            release.setCreatedBy(principal.id());
        }
        releaseMapper.insert(release);
        // 微信返回体里没有 user_version，用请求值回写，保证列表展示一致
        if (release.getUserVersion() == null) {
            release.setUserVersion(userVersion);
            releaseMapper.updateById(release);
        }
        return release;
    }

    private String trimmedToRequired(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(label + " 是微信必填项，请填写后再上传代码");
        }
        return value.trim();
    }

    /** ext_appid 必须是被授权小程序自身 AppID，否则上传代码会报 89222/86001。 */
    private String buildExtJson(String authorizerAppid, Map<String, Object> ext, List<String> extPages) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("extAppid", authorizerAppid);
        if (ext != null && !ext.isEmpty()) {
            root.put("ext", ext);
        }
        if (extPages != null && !extPages.isEmpty()) {
            root.put("extPages", extPages);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new BizException("ext_json 组装失败: " + ex.getMessage());
        }
    }

    /** 体验版二维码（PNG/JPEG 二进制）。 */
    public byte[] experienceQrcode(String authorizerAppid, String path) {
        return caller.authorizerBinary(authorizerAppid, "get_qrcode",
                (api, token) -> MiniProgramApi.experienceQrcode(api, token, path));
    }

    public JsonNode pages(String authorizerAppid) {
        return callAuth(authorizerAppid, "get_page", MiniProgramApi::pageList);
    }

    /**
     * 体验版 + 线上版信息。微信在「还没有体验版 / 还没发布过线上版」时不会返回 exp_info / release_info，
     * 而 exp_time / release_time 是 epoch 秒，原样透传会让界面渲染成 1640762988 这样的数字，
     * 所以这里统一成 {version, desc, at}，没有对应版本时返回 null（表示「微信侧确实没有」而不是「没读到」）。
     */
    public Map<String, Object> versionInfo(String authorizerAppid) {
        JsonNode node = callAuth(authorizerAppid, "getversioninfo", MiniProgramApi::versionInfo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exp", versionOf(node.path("exp_info"), "exp_version", "exp_desc", "exp_time"));
        result.put("release", versionOf(node.path("release_info"), "release_version", "release_desc", "release_time"));
        result.put("visitStatus", readVisitStatus(authorizerAppid));
        return result;
    }

    private static Map<String, Object> versionOf(JsonNode info, String versionField, String descField, String timeField) {
        if (!info.isObject()) {
            return null;
        }
        JsonNode version = info.path(versionField);
        JsonNode desc = info.path(descField);
        JsonNode time = info.path(timeField);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("version", version.isTextual() ? version.asText() : null);
        normalized.put("desc", desc.isTextual() ? desc.asText() : null);
        normalized.put("at", time.isNumber()
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(time.asLong()), ZoneId.systemDefault()) : null);
        return normalized;
    }

    /** 服务状态：0 已暂停服务（主动暂停或违规暂停），1 未暂停服务；取不到时返回 null 表示未知。 */
    public Integer readVisitStatus(String authorizerAppid) {
        try {
            JsonNode node = callAuth(authorizerAppid, "getvisitstatus", MiniProgramApi::visitStatus);
            JsonNode status = node.path("status");
            return status.isNumber() || status.isBoolean() ? status.asInt() : null;
        } catch (RuntimeException ex) {
            log.warn("读取发布状态失败 appid={}: {}", authorizerAppid, ex.toString());
            return null;
        }
    }

    /**
     * 暂停 / 恢复服务。`change_visitstatus` 只回 errcode/errmsg，所以调用完必须再读一次状态，界面才知道真的生效了。
     */
    public Integer setVisitStatus(String authorizerAppid, String action) {
        if (!"open".equals(action) && !"close".equals(action)) {
            throw new BizException(400, "action 只能是 open（恢复服务）或 close（暂停服务）");
        }
        callAuth(authorizerAppid, "change_visitstatus",
                (api, token) -> MiniProgramApi.changeVisitStatus(api, token, action));
        return readVisitStatus(authorizerAppid);
    }

    // -------------------------------------------------- 备案

    /**
     * 备案状态。未备案时微信用 1024~1031 八个值区分缺哪个前置条件，界面无法直接解释，这里按位解码。
     */
    public Map<String, Object> icpStatus(String authorizerAppid) {
        JsonNode info = callAuth(authorizerAppid, "get_icp_entrance_info", MiniProgramApi::icpEntranceInfo)
                .path("info");
        int status = info.path("status").asInt(-1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("statusText", icpStatusText(status));
        result.put("filed", status == 6);
        result.put("canceling", info.path("is_canceling").asBoolean(false));
        result.put("entranceAvailable", info.path("available").asInt(1) == 1);
        result.put("missingPrerequisites", icpMissingPrerequisites(status));
        result.put("rejectReasons", info.path("audit_data"));
        return result;
    }

    private static boolean isNotFiled(int status) {
        return status >= 1024 && status <= 1031;
    }

    /** status - 1024 的低位依次是「基本信息未填」「类目未填」「未认证」。 */
    static List<String> icpMissingPrerequisites(int status) {
        List<String> missing = new ArrayList<>();
        if (!isNotFiled(status)) {
            return missing;
        }
        int bits = status - 1024;
        if ((bits & 1) != 0) {
            missing.add("小程序基本信息未填写（名称/头像/简介）");
        }
        if ((bits & 2) != 0) {
            missing.add("没有审核通过的小程序类目");
        }
        if ((bits & 4) != 0) {
            missing.add("主体未完成微信认证");
        }
        return missing;
    }

    static String icpStatusText(int status) {
        return switch (status) {
            case 2 -> "备案平台审核中";
            case 3 -> "备案平台审核驳回";
            case 4 -> "管局审核中";
            case 5 -> "管局审核驳回";
            case 6 -> "已备案";
            default -> isNotFiled(status) ? "未备案" : "备案状态未知";
        };
    }

    // -------------------------------------------------- 审核

    @Transactional
    public String submitAudit(String authorizerAppid, AuditSubmitRequest request) {
        List<Map<String, Object>> itemList = toItemLists(request);
        JsonNode response = callAuth(authorizerAppid, "submit_audit",
                (api, token) -> MiniProgramApi.submitAudit(api, token, itemList,
                        request.feedbackInfo(), request.versionDesc(), request.extra()));
        String auditId = response.path("auditid").asText(null);
        if (!StringUtils.hasText(auditId)) {
            throw new BizException(502, "微信未返回 auditid: " + response);
        }
        // auditid 可能重复出现（提审请求超时重试、或状态查询先建了档），按 upsert 处理避免撞唯一索引
        AuditRecord record = auditMapper.selectOne(new LambdaQueryWrapper<AuditRecord>()
                .eq(AuditRecord::getAuditId, auditId));
        if (record == null) {
            record = new AuditRecord();
            record.setAuthorizerAppid(authorizerAppid);
            record.setAuditId(auditId);
        }
        record.setStatus(2);
        record.setSubmittedAt(LocalDateTime.now());
        record.setDetailJson(response.toString());
        if (record.getId() == null) {
            auditMapper.insert(record);
        } else {
            auditMapper.updateById(record);
        }

        AppRelease latest = latest(authorizerAppid);
        if (latest != null) {
            latest.setLastAuditId(auditId);
            latest.setStatus("AUDITING");
            latest.setSubmitDuration(response.path("submit_duration").asInt(0));
            latest.setUpdatedAt(LocalDateTime.now());
            releaseMapper.updateById(latest);
        }
        return auditId;
    }

    private List<Map<String, Object>> toItemLists(AuditSubmitRequest request) {
        List<AuditSubmitRequest.Item> items = request.itemList();
        if (items == null || items.isEmpty()) {
            throw new BizException("提交审核至少需要一个页面（address + 一级/二级类目）");
        }
        if (items.size() > 5) {
            throw new BizException("微信规定单次提审至多 5 个页面，当前 " + items.size() + " 个");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditSubmitRequest.Item item : items) {
            if (!StringUtils.hasText(item.address())) {
                throw new BizException("提审页面的 address（页面路径）不能为空");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("address", item.address().trim());
            put(entry, "title", item.title());
            putCategory(entry, item);
            put(entry, "tag", tagValue(item));
            if (item.keyWordList() != null && !item.keyWordList().isEmpty()) {
                entry.put("key_word_list", item.keyWordList());
            }
            result.add(entry);
        }
        return result;
    }

    /** 微信用「类目名称 + 类目 id」定位类目：一级二级必填，三级需成对出现。 */
    private void putCategory(Map<String, Object> entry, AuditSubmitRequest.Item item) {
        requireCategory(item.firstClass(), item.firstId(), "一级", item.address());
        requireCategory(item.secondClass(), item.secondId(), "二级", item.address());
        entry.put("first_class", item.firstClass().trim());
        entry.put("first_id", item.firstId());
        entry.put("second_class", item.secondClass().trim());
        entry.put("second_id", item.secondId());

        boolean hasThirdName = StringUtils.hasText(item.thirdClass());
        boolean hasThirdId = item.thirdId() != null;
        if (hasThirdName != hasThirdId) {
            throw new BizException("提审页面 " + item.address() + " 的三级类目名称与 id 需同时填写或同时留空");
        }
        if (hasThirdName) {
            entry.put("third_class", item.thirdClass().trim());
            entry.put("third_id", item.thirdId());
        }
    }

    private void requireCategory(String name, Integer id, String level, String address) {
        if (!StringUtils.hasText(name) || id == null) {
            throw new BizException("提审页面 " + address + " 缺少" + level + "类目（微信要求类目名称与类目 id 同时提交）");
        }
    }

    /** tag 是空格分隔的页面标签（至多 10 个、每个至多 20 字），不是类目编号。 */
    private String tagValue(AuditSubmitRequest.Item item) {
        if (item.tag() == null || item.tag().isEmpty()) {
            return null;
        }
        List<String> cleaned = item.tag().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.size() > 10) {
            throw new BizException("提审页面 " + item.address() + " 的标签最多 10 个");
        }
        for (String tag : cleaned) {
            if (tag.length() > 20) {
                throw new BizException("提审页面 " + item.address() + " 的标签「" + tag + "」超过 20 字符");
            }
        }
        return String.join(" ", cleaned);
    }

    public AppRelease latest(String authorizerAppid) {
        return releaseMapper.selectOne(new LambdaQueryWrapper<AppRelease>()
                .eq(AppRelease::getAuthorizerAppid, authorizerAppid)
                .orderByDesc(AppRelease::getId)
                .last("limit 1"));
    }

    public List<AppRelease> history(String authorizerAppid, int limit) {
        return releaseMapper.selectList(new LambdaQueryWrapper<AppRelease>()
                .eq(AppRelease::getAuthorizerAppid, authorizerAppid)
                .orderByDesc(AppRelease::getId)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    public AuditRecord latestAudit(String authorizerAppid) {
        return auditMapper.selectOne(new LambdaQueryWrapper<AuditRecord>()
                .eq(AuditRecord::getAuthorizerAppid, authorizerAppid)
                .orderByDesc(AuditRecord::getId)
                .last("limit 1"));
    }

    /** 主动查询审核单状态并回写本地。 */
    @Transactional
    public AuditRecord refreshAuditStatus(String authorizerAppid, String auditId) {
        JsonNode node = callAuth(authorizerAppid, "get_auditstatus",
                (api, token) -> MiniProgramApi.auditStatus(api, token, auditId));
        return applyAuditStatus(authorizerAppid, auditId, node);
    }

    /** 未指定 auditId 时取最近一次提审。 */
    @Transactional
    public AuditRecord refreshLatestAudit(String authorizerAppid) {
        JsonNode node = callAuth(authorizerAppid, "get_latest_auditstatus", MiniProgramApi::latestAuditStatus);
        String auditId = node.path("auditid").asText(null);
        if (!StringUtils.hasText(auditId)) {
            throw new BizException(404, "该小程序没有提审记录");
        }
        return applyAuditStatus(authorizerAppid, auditId, node);
    }

    private AuditRecord applyAuditStatus(String authorizerAppid, String auditId, JsonNode node) {
        int status = node.path("status").asInt(-1);
        AuditRecord record = auditMapper.selectOne(new LambdaQueryWrapper<AuditRecord>()
                .eq(AuditRecord::getAuditId, auditId));
        if (record == null) {
            record = new AuditRecord();
            record.setAuthorizerAppid(authorizerAppid);
            record.setAuditId(auditId);
            record.setSubmittedAt(LocalDateTime.now());
        }
        record.setStatus(status);
        record.setReason(node.path("reason").asText(null));
        record.setDetailJson(node.toString());
        record.setLastQueriedAt(LocalDateTime.now());
        if (record.getId() == null) {
            auditMapper.insert(record);
        } else {
            auditMapper.updateById(record);
        }
        AppRelease release = latest(authorizerAppid);
        if (release != null) {
            release.setStatus(switch (status) {
                case 0 -> "AUDIT_PASS";
                case 1 -> "AUDIT_REJECT";
                case 2 -> "AUDITING";
                case 3 -> "COMMITTED";
                // 4 = 审核延后：审核单还在队列里，只是微信推迟了结论
                case 4 -> "AUDITING";
                default -> release.getStatus();
            });
            release.setAuditReason(node.path("reason").asText(null));
            release.setUpdatedAt(LocalDateTime.now());
            releaseMapper.updateById(release);
        }
        return record;
    }

    /**
     * 审核驳回截图：微信在 status=1 时只给 {@code screenshot} 的 media_id 竖线串，
     * 内容要用永久素材接口逐个取回。图片素材直接是字节，界面用 data URL 显示。
     */
    public List<Map<String, Object>> auditScreenshots(String authorizerAppid) {
        AuditRecord record = latestAudit(authorizerAppid);
        List<String> mediaIds = record == null ? List.of() : auditScreenshotIds(record.getDetailJson());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String mediaId : mediaIds) {
            byte[] bytes = caller.authorizerBinary(authorizerAppid, "get_material",
                    (api, token) -> MiniProgramApi.material(api, token, mediaId));
            String mime = imageMimeType(bytes);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mediaId", mediaId);
            item.put("contentType", mime);
            item.put("dataUrl", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            result.add(item);
        }
        return result;
    }

    /** screenshot 用竖线分隔；没有该字段（非驳回、或微信没给截图）时返回空清单。 */
    List<String> auditScreenshotIds(String detailJson) {
        if (!StringUtils.hasText(detailJson)) {
            return List.of();
        }
        String raw;
        try {
            raw = objectMapper.readTree(detailJson).path("screenshot").asText("");
        } catch (Exception ex) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            if (StringUtils.hasText(part)) {
                ids.add(part.trim());
            }
        }
        return ids;
    }

    /** 素材接口不回 Content-Type，只能按文件头判断，界面才拿到能渲染的 MIME。 */
    static String imageMimeType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "application/octet-stream";
        }
        int b0 = bytes[0] & 0xFF;
        if (b0 == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (b0 == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b0 == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (b0 == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    @Transactional
    public JsonNode undoAudit(String authorizerAppid) {
        JsonNode node = callAuth(authorizerAppid, "undocodeaudit", MiniProgramApi::undoAudit);
        AppRelease release = latest(authorizerAppid);
        if (release != null && "AUDITING".equals(release.getStatus())) {
            release.setStatus("COMMITTED");
            release.setUpdatedAt(LocalDateTime.now());
            releaseMapper.updateById(release);
        }
        return node;
    }

    /** 审核加速（每个第三方平台每月有限额）。未指定 auditId 时取最近一次提审。 */
    public JsonNode speedupAudit(String authorizerAppid, String auditId) {
        String target = StringUtils.hasText(auditId) ? auditId.trim() : null;
        if (target == null) {
            AuditRecord latest = latestAudit(authorizerAppid);
            target = latest == null ? null : latest.getAuditId();
        }
        if (!StringUtils.hasText(target)) {
            throw new BizException(404, "该小程序没有提审记录，无法加速审核");
        }
        String auditIdParam = target;
        return callAuth(authorizerAppid, "speedupaudit",
                (api, token) -> MiniProgramApi.speedupAudit(api, token, auditIdParam));
    }

    // -------------------------------------------------- 发布 / 灰度 / 回退

    @Transactional
    public AppRelease publish(String authorizerAppid) {
        callAuth(authorizerAppid, "release", MiniProgramApi::release);
        return markStatus(authorizerAppid, "PUBLISHED", null);
    }

    /** 分阶段发布：grayPercentage 是 0~100 的整数（微信必填），100 即全量；0 时必须指定优先灰度人群。 */
    @Transactional
    public JsonNode grayRelease(String authorizerAppid, Integer grayPercentage,
                                Boolean supportDebuggerFirst, Boolean supportExperiencerFirst) {
        if (grayPercentage == null) {
            throw new BizException("必须填写灰度比例（0~100 的整数）");
        }
        if (grayPercentage < 0 || grayPercentage > 100) {
            throw new BizException("灰度比例需在 0~100 之间，当前 " + grayPercentage);
        }
        if (grayPercentage == 0
                && !Boolean.TRUE.equals(supportDebuggerFirst)
                && !Boolean.TRUE.equals(supportExperiencerFirst)) {
            throw new BizException("灰度比例为 0 时，需开启「开发者优先」或「体验成员优先」");
        }
        JsonNode node = callAuth(authorizerAppid, "grayrelease", (api, token) ->
                MiniProgramApi.grayRelease(api, token, grayPercentage, supportDebuggerFirst, supportExperiencerFirst));
        markStatus(authorizerAppid, "GRAY", String.valueOf(grayPercentage));
        return node;
    }

    /**
     * 微信侧的灰度计划。本地发布单只能证明「我们点过灰度」，真实比例与是否还在灰度要以这里为准：
     * status 0 初始 / 1 执行中 / 2 暂停中 / 3 执行完毕 / 4 被删除，只有 1、2 还能结束或取消。
     */
    @Transactional
    public Map<String, Object> grayPlan(String authorizerAppid) {
        JsonNode node = callAuth(authorizerAppid, "getgrayreleaseplan", MiniProgramApi::grayReleasePlan);
        JsonNode plan = node.path("gray_release_plan");
        JsonNode statusNode = plan.path("status");
        Integer status = statusNode.isNumber() ? statusNode.asInt() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("statusText", grayStatusText(status));
        result.put("actionable", status != null && (status == 1 || status == 2));
        result.put("grayPercentage", plan.path("gray_percentage").asInt(0));
        // 微信这个字段自己就少写了一个 g（support_debuger_first），照正确拼写读会永远是 false
        result.put("supportDebuggerFirst", plan.path("support_debuger_first").asBoolean(false));
        result.put("supportExperiencerFirst", plan.path("support_experiencer_first").asBoolean(false));
        JsonNode created = plan.path("create_timestamp");
        result.put("createdAt", created.isNumber()
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(created.asLong()), ZoneId.systemDefault())
                : null);
        result.put("localCorrected", correctFinishedGray(authorizerAppid, status));
        return result;
    }

    /**
     * 灰度可以由商户直接在小程序后台结束或取消，本地那条 GRAY 就再也没人改：
     * 刷新读的就是这条记录，「灰度发布」会被永久锁死。读到确定性结论时顺手纠正。
     * 只动本地的 GRAY，其它状态与灰度计划无关；status 0（初始）不算结束。
     */
    private boolean correctFinishedGray(String authorizerAppid, Integer status) {
        boolean ended = status == null || status == 3 || status == 4;
        if (!ended) {
            return false;
        }
        AppRelease latest = latest(authorizerAppid);
        if (latest == null || !"GRAY".equals(latest.getStatus())) {
            return false;
        }
        String note = status == null ? "微信侧已无灰度计划" : "微信侧灰度" + grayStatusText(status);
        markStatus(authorizerAppid, "PUBLISHED", note + "，本地发布单已纠正");
        return true;
    }

    private static String grayStatusText(Integer status) {
        if (status == null) {
            return "微信侧暂无灰度计划";
        }
        return switch (status) {
            case 0 -> "初始状态";
            case 1 -> "执行中";
            case 2 -> "暂停中";
            case 3 -> "执行完毕";
            case 4 -> "已删除";
            default -> "未知状态 " + status;
        };
    }

    @Transactional
    public JsonNode cancelGray(String authorizerAppid) {
        JsonNode node = callAuth(authorizerAppid, "revertgrayrelease", MiniProgramApi::revertGrayRelease);
        markStatus(authorizerAppid, "PUBLISHED", "灰度已取消，回到灰度前版本");
        return node;
    }

    /** 灰度结束后全量放开，等价于 release。 */
    @Transactional
    public AppRelease finishGray(String authorizerAppid) {
        return publish(authorizerAppid);
    }

    @Transactional
    public JsonNode revert(String authorizerAppid, String appVersion) {
        JsonNode node = callAuth(authorizerAppid, "revertcoderelease", (api, token) ->
                MiniProgramApi.revertCodeRelease(api, token, appVersion));
        markStatus(authorizerAppid, "REVERTED", appVersion);
        return node;
    }

    public JsonNode historyVersions(String authorizerAppid) {
        return callAuth(authorizerAppid, "revertcoderelease?get_history_version", MiniProgramApi::historyVersions);
    }

    /** 审核/改名素材上传（图片），返回 mediaid。 */
    public String uploadMedia(String authorizerAppid, String filename, byte[] content) {
        JsonNode node = callAuth(authorizerAppid, "uploadmedia",
                (api, token) -> MiniProgramApi.uploadMedia(api, token, filename, content));
        String mediaId = node.path("mediaid").asText(null);
        if (!StringUtils.hasText(mediaId)) {
            throw new BizException(502, "素材上传未返回 mediaid: " + node);
        }
        return mediaId;
    }

    private AppRelease markStatus(String authorizerAppid, String status, String note) {
        AppRelease release = latest(authorizerAppid);
        if (release == null) {
            return null;
        }
        release.setStatus(status);
        if (note != null) {
            release.setAuditReason(note);
        }
        release.setUpdatedAt(LocalDateTime.now());
        releaseMapper.updateById(release);
        return release;
    }

    private JsonNode callAuth(String authorizerAppid, String action, WechatCaller.AuthCall call) {
        return caller.authorizer(authorizerAppid, action, call);
    }

    private static void put(Map<String, Object> body, String key, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            body.put(key, value);
        }
    }
}
