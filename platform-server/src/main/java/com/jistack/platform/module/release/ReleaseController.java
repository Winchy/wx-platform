package com.jistack.platform.module.release;

import com.jistack.platform.common.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 开发与发布链路：上传代码 → 体验版 → 提审 → 发布 / 灰度 / 回退。
 */
@RestController
@RequestMapping("/api/authorizers/{appid}")
@RequiredArgsConstructor
public class ReleaseController {

    private final ReleaseService releaseService;

    public record GrayReleaseRequest(Integer grayPercentage, Boolean supportDebuggerFirst,
                                     Boolean supportExperiencerFirst) {
    }

    public record RevertRequest(String appVersion) {
    }

    public record VisitStatusRequest(String action) {
    }

    public record AuditRefreshRequest(String auditId) {
    }

    // -------------------------------------------------- 上传代码 / 体验版

    @PostMapping("/code")
    public ApiResponse<AppRelease> commit(@PathVariable String appid, @RequestBody CommitRequest request) {
        return ApiResponse.ok(releaseService.commit(appid, request));
    }

    /** 体验版二维码，前端以 <img src="…?token=xxx"> 直接引用。 */
    @GetMapping("/experience-qrcode")
    public ResponseEntity<byte[]> experienceQrcode(@PathVariable String appid,
                                                   @RequestParam(required = false) String path) {
        byte[] image = releaseService.experienceQrcode(appid, path);
        boolean jpeg = image.length > 2 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8;
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(jpeg ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .body(image);
    }

    @GetMapping("/pages")
    public ApiResponse<JsonNode> pages(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.pages(appid));
    }

    @GetMapping("/code-info")
    public ApiResponse<Map<String, Object>> codeInfo(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.versionInfo(appid));
    }

    @GetMapping("/visit-status")
    public ApiResponse<Integer> visitStatus(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.readVisitStatus(appid));
    }

    /** 暂停（close）/ 恢复（open）服务。微信只回 errcode/errmsg，返回值是改完之后重新读到的状态。 */
    @PostMapping("/visit-status")
    public ApiResponse<Integer> setVisitStatus(@PathVariable String appid, @RequestBody VisitStatusRequest request) {
        return ApiResponse.ok(releaseService.setVisitStatus(appid, request.action()));
    }

    /** 驳回截图：微信只给 media_id 竖线串，内容要逐个调永久素材接口取回。 */
    @GetMapping("/audit/screenshots")
    public ApiResponse<List<Map<String, Object>>> auditScreenshots(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.auditScreenshots(appid));
    }

    /** 备案状态：未备案的小程序不能发布，返回值里带缺失的前置条件。 */
    @GetMapping("/icp-status")
    public ApiResponse<Map<String, Object>> icpStatus(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.icpStatus(appid));
    }

    @PostMapping("/media")
    public ApiResponse<String> uploadMedia(@PathVariable String appid,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(releaseService.uploadMedia(appid, file.getOriginalFilename(), file.getBytes()));
    }

    // -------------------------------------------------- 审核

    @PostMapping("/audit")
    public ApiResponse<String> submitAudit(@PathVariable String appid, @RequestBody AuditSubmitRequest request) {
        return ApiResponse.ok(releaseService.submitAudit(appid, request));
    }

    @GetMapping("/audit/latest")
    public ApiResponse<AuditRecord> latestAudit(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.latestAudit(appid));
    }

    /** 不传 auditId 时刷新最近一次提审单。 */
    @PostMapping("/audit/refresh")
    public ApiResponse<AuditRecord> refreshAudit(@PathVariable String appid,
                                                 @RequestBody(required = false) AuditRefreshRequest request) {
        String auditId = request == null ? null : request.auditId();
        return ApiResponse.ok(auditId == null || auditId.isBlank()
                ? releaseService.refreshLatestAudit(appid)
                : releaseService.refreshAuditStatus(appid, auditId));
    }

    @DeleteMapping("/audit")
    public ApiResponse<JsonNode> undoAudit(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.undoAudit(appid));
    }

    @PostMapping("/audit/speedup")
    public ApiResponse<JsonNode> speedupAudit(@PathVariable String appid,
                                              @RequestParam(required = false) String auditId) {
        return ApiResponse.ok(releaseService.speedupAudit(appid, auditId));
    }

    // -------------------------------------------------- 发布 / 灰度 / 回退

    @PostMapping("/publish")
    public ApiResponse<AppRelease> publish(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.publish(appid));
    }

    @PostMapping("/gray-release")
    public ApiResponse<JsonNode> grayRelease(@PathVariable String appid,
                                             @RequestBody GrayReleaseRequest request) {
        return ApiResponse.ok(releaseService.grayRelease(appid, request.grayPercentage(),
                request.supportDebuggerFirst(), request.supportExperiencerFirst()));
    }

    @GetMapping("/gray-release")
    public ApiResponse<Map<String, Object>> grayPlan(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.grayPlan(appid));
    }

    @DeleteMapping("/gray-release")
    public ApiResponse<JsonNode> cancelGray(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.cancelGray(appid));
    }

    @PostMapping("/gray-release/finish")
    public ApiResponse<AppRelease> finishGray(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.finishGray(appid));
    }

    @PostMapping("/revert")
    public ApiResponse<JsonNode> revert(@PathVariable String appid, @RequestBody RevertRequest request) {
        return ApiResponse.ok(releaseService.revert(appid, request.appVersion()));
    }

    @GetMapping("/revert-versions")
    public ApiResponse<JsonNode> historyVersions(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.historyVersions(appid));
    }

    // -------------------------------------------------- 发布记录

    @GetMapping("/release/latest")
    public ApiResponse<AppRelease> latest(@PathVariable String appid) {
        return ApiResponse.ok(releaseService.latest(appid));
    }

    @GetMapping("/release/history")
    public ApiResponse<List<AppRelease>> history(@PathVariable String appid,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(releaseService.history(appid, limit));
    }
}
