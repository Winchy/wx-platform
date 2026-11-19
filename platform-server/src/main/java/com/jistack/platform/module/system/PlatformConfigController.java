package com.jistack.platform.module.system;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.module.authorizer.AuthorizerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 第三方平台凭据与健康状态：开放平台后台的授权事件接收 URL 需与此处回调地址一致。
 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final PlatformConfigService configService;
    private final AuthorizerService authorizerService;

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(configService.toVO());
    }

    /** 仅更新填写的字段；secret / aesKey 留空表示保持原值。 */
    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> update(@RequestBody PlatformConfig incoming) {
        configService.update(incoming);
        return ApiResponse.ok(configService.toVO());
    }

    @PostMapping("/config/check")
    public ApiResponse<Map<String, Object>> check() {
        return ApiResponse.ok(authorizerService.checkConnectivity());
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(authorizerService.health());
    }

    @PostMapping("/ticket-push/restart")
    public ApiResponse<Map<String, Object>> restartTicketPush() {
        return ApiResponse.ok(authorizerService.restartTicketPush());
    }

    @GetMapping("/tickets")
    public ApiResponse<List<Map<String, Object>>> tickets(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(configService.recentTickets(limit));
    }
}
