package com.jistack.platform.module.authorizer;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.common.web.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * 小程序基本信息、域名、类目、体验成员配置。所有路径都以授权小程序为维度。
 */
@RestController
@RequestMapping("/api/authorizers/{appid}")
@RequiredArgsConstructor
public class MiniProgramSettingController {

    private final MiniProgramSettingService settingService;

    /** idCard 个人主体必填、license 组织主体必填，都是临时素材 media_id；namingOtherStuff 最多 5 份。 */
    public record NicknameRequest(String nickname, String idCard, String license,
                                  List<String> namingOtherStuff) {
    }

    public record AuditIdRequest(String auditId) {
    }

    public record SignatureRequest(String signature) {
    }

    public record DomainUpdateRequest(String action, String type, List<String> domains) {
    }

    public record WebviewUpdateRequest(String action, List<String> domains) {
    }

    public record CategoryRequest(Integer first, Integer second, Integer third,
                                 List<Map<String, Object>> certificates) {
    }

    public record TesterRequest(String wechatId, String userStr) {
    }

    /** setting_list 每项是 {privacy_key, privacy_text}，owner_setting 原样透传给微信。 */
    public record PrivacyUpdateRequest(Integer privacyVer, List<Map<String, Object>> settingList,
                                       Map<String, Object> ownerSetting) {
    }

    // -------------------------------------------------- 基本信息

    @GetMapping("/basic-info")
    public ApiResponse<Map<String, Object>> basicInfo(@PathVariable String appid) {
        return ApiResponse.ok(settingService.basicInfo(appid));
    }

    /**
     * 小程序码图片：默认给后端保存的那份（微信图片 CDN 有 Referer 防盗链，只能服务端取），
     * refresh=true 才重新去微信取一张并覆盖保存。界面拿 dataUrl 显示。
     */
    @GetMapping("/qrcode")
    public ApiResponse<Map<String, Object>> qrcode(@PathVariable String appid,
                                                   @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(settingService.qrcodeImage(appid, refresh));
    }

    @PostMapping("/nickname/check")
    public ApiResponse<Map<String, Object>> checkNickname(@PathVariable String appid,
                                                          @RequestBody NicknameRequest request) {
        return ApiResponse.ok(settingService.checkNickname(appid, request.nickname()));
    }

    @PutMapping("/nickname")
    public ApiResponse<Map<String, Object>> setNickname(@PathVariable String appid,
                                                        @RequestBody NicknameRequest request) {
        return ApiResponse.ok(settingService.setNickname(appid, request.nickname(), request.idCard(),
                request.license(), request.namingOtherStuff()));
    }

    /** 材料图片先换成临时素材 media_id，再填进改名请求；media_id 在微信侧只保存 3 天。 */
    @PostMapping("/nickname/materials")
    public ApiResponse<String> uploadNicknameMaterial(@PathVariable String appid,
                                                      @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(settingService.uploadNicknameMaterial(appid, file.getOriginalFilename(),
                file.getBytes()));
    }

    @GetMapping("/nickname/audit")
    public ApiResponse<JsonNode> queryNickname(@PathVariable String appid, @RequestParam String auditId) {
        return ApiResponse.ok(settingService.queryNickname(appid, auditId));
    }

    @PutMapping("/signature")
    public ApiResponse<JsonNode> setSignature(@PathVariable String appid,
                                              @RequestBody SignatureRequest request) {
        return ApiResponse.ok(settingService.setSignature(appid, request.signature()));
    }

    @PutMapping("/avatar")
    public ApiResponse<JsonNode> setAvatar(@PathVariable String appid,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) Double x1,
                                           @RequestParam(required = false) Double y1,
                                           @RequestParam(required = false) Double x2,
                                           @RequestParam(required = false) Double y2) throws IOException {
        return ApiResponse.ok(settingService.setAvatar(appid, file.getBytes(), file.getOriginalFilename(),
                x1, y1, x2, y2));
    }

    // -------------------------------------------------- 服务器域名 / webview

    @GetMapping("/domains")
    public ApiResponse<Map<String, Object>> domains(@PathVariable String appid) {
        return ApiResponse.ok(settingService.readDomains(appid));
    }

    @PutMapping("/domains")
    public ApiResponse<Map<String, Object>> updateDomains(@PathVariable String appid,
                                                          @RequestBody DomainUpdateRequest request) {
        return ApiResponse.ok(settingService.updateDomains(appid, request.action(),
                request.type(), request.domains()));
    }

    @GetMapping("/webview-domains")
    public ApiResponse<List<String>> webviewDomains(@PathVariable String appid) {
        return ApiResponse.ok(settingService.readWebviewDomains(appid));
    }

    @PutMapping("/webview-domains")
    public ApiResponse<List<String>> updateWebviewDomains(@PathVariable String appid,
                                                         @RequestBody WebviewUpdateRequest request) {
        return ApiResponse.ok(settingService.updateWebviewDomains(appid, request.action(), request.domains()));
    }

    /** 业务域名校验文件内容，前端需把该文件放到域名服务器上再提交域名。 */
    @GetMapping("/webview-confirm-file")
    public ApiResponse<Map<String, Object>> webviewConfirmFile(@PathVariable String appid) {
        return ApiResponse.ok(settingService.webviewConfirmFile(appid));
    }

    // -------------------------------------------------- 类目

    @GetMapping("/categories")
    public ApiResponse<Map<String, Object>> categories(@PathVariable String appid) {
        return ApiResponse.ok(settingService.categories(appid));
    }

    @GetMapping("/all-categories")
    public ApiResponse<JsonNode> allCategories(@PathVariable String appid) {
        return ApiResponse.ok(settingService.allCategories(appid));
    }

    @PostMapping("/categories")
    public ApiResponse<JsonNode> addCategory(@PathVariable String appid, @RequestBody CategoryRequest request) {
        return ApiResponse.ok(settingService.addCategory(appid, request.first(), request.second(),
                request.third(), request.certificates()));
    }

    @DeleteMapping("/categories")
    public ApiResponse<JsonNode> deleteCategory(@PathVariable String appid,
                                                @RequestParam Integer first,
                                                @RequestParam Integer second,
                                                @RequestParam(required = false) Integer third) {
        return ApiResponse.ok(settingService.deleteCategory(appid, first, second, third));
    }

    // -------------------------------------------------- 用户隐私保护指引

    /** privacy_ver：1 现网版本 / 2 开发版（默认）。 */
    @GetMapping("/privacy-setting")
    public ApiResponse<JsonNode> privacySetting(@PathVariable String appid,
                                                @RequestParam(required = false) Integer privacyVer) {
        return ApiResponse.ok(settingService.privacySetting(appid, privacyVer));
    }

    @PutMapping("/privacy-setting")
    public ApiResponse<JsonNode> updatePrivacySetting(@PathVariable String appid,
                                                      @RequestBody PrivacyUpdateRequest request) {
        return ApiResponse.ok(settingService.updatePrivacySetting(appid, request.privacyVer(),
                request.settingList(), request.ownerSetting()));
    }

    // -------------------------------------------------- 体验成员

    @GetMapping("/testers")
    public ApiResponse<List<Map<String, Object>>> testers(@PathVariable String appid) {
        return ApiResponse.ok(settingService.testers(appid));
    }

    @PostMapping("/testers")
    public ApiResponse<String> bindTester(@PathVariable String appid, @RequestBody TesterRequest request) {
        return ApiResponse.ok(settingService.bindTester(appid, request.wechatId()));
    }

    @DeleteMapping("/testers")
    public ApiResponse<Void> unbindTester(@PathVariable String appid,
                                          @RequestParam(required = false) String wechatId,
                                          @RequestParam(required = false) String userStr) {
        if (wechatId == null && userStr == null) {
            throw new BizException("需提供微信号或成员唯一标识");
        }
        settingService.unbindTester(appid, wechatId, userStr);
        return ApiResponse.ok();
    }
}
