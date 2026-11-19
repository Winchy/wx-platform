package com.jistack.platform.module.authorizer;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 授权小程序列表与授权接入：扫码授权链接由后端生成，前端只负责展示二维码。
 */
@RestController
@RequestMapping("/api/authorizers")
@RequiredArgsConstructor
public class AuthorizerController {

    private final AuthorizerService authorizerService;

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String authState,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(authorizerService.page(keyword, authState, page, size));
    }

    @GetMapping("/{appid}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String appid) {
        return ApiResponse.ok(authorizerService.detail(appid));
    }

    /** 重新拉取 authorizer_info（名称、头像、权限集等以微信为准）。 */
    @PostMapping("/{appid}/refresh")
    public ApiResponse<Map<String, Object>> refresh(@PathVariable String appid) {
        return ApiResponse.ok(authorizerService.refreshFromWechat(appid));
    }

    @GetMapping("/{appid}/version")
    public ApiResponse<Map<String, Object>> version(@PathVariable String appid) {
        return ApiResponse.ok(authorizerService.versionInfo(appid));
    }

    /** 手动续期 authorizer_access_token，用于排查 42001/61023。 */
    @PostMapping("/{appid}/token/refresh")
    public ApiResponse<Map<String, Object>> refreshToken(@PathVariable String appid) {
        return ApiResponse.ok(authorizerService.refreshToken(appid));
    }

    /** 本地解除授权；小程序侧取消授权由 unauthorized 事件驱动。 */
    @DeleteMapping("/{appid}")
    public ApiResponse<Void> revoke(@PathVariable String appid) {
        authorizerService.revoke(appid);
        return ApiResponse.ok();
    }

    /** 微信侧授权关系为准，补录本地缺失的小程序。 */
    @PostMapping("/sync")
    public ApiResponse<Integer> sync() {
        return ApiResponse.ok(authorizerService.syncFromWechat());
    }

    public record AuthSessionRequest(String bizAppid, List<Integer> funcScopeIds) {
    }

    @PostMapping("/auth-sessions")
    public ApiResponse<Map<String, Object>> createAuthSession(@RequestBody(required = false) AuthSessionRequest request) {
        String bizAppid = request == null ? null : request.bizAppid();
        List<Integer> funcScopeIds = request == null ? null : request.funcScopeIds();
        return ApiResponse.ok(authorizerService.createAuthSession(bizAppid, funcScopeIds));
    }

    /** 前端轮询扫码进度：PENDING / AUTHED / EXPIRED。 */
    @GetMapping("/auth-sessions/{state}")
    public ApiResponse<Map<String, Object>> authSessionStatus(@PathVariable String state) {
        return ApiResponse.ok(authorizerService.authSessionStatus(state));
    }
}
