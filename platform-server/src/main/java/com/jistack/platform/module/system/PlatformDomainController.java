package com.jistack.platform.module.system;

import com.jistack.platform.common.web.ApiResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第三方平台账号自身的小程序域名白名单。kind 取 server（服务器域名）或 jump（业务域名）。
 */
@RestController
@RequestMapping("/api/platform/domains")
@RequiredArgsConstructor
public class PlatformDomainController {

    private final PlatformDomainService domainService;

    public record UpdateRequest(String action, List<String> domains, Boolean modifyPublishedTogether) {
    }

    /** 业务域名校验文件：需放到域名根目录后微信才接受该业务域名。字面量路径优先于 {kind}。 */
    @GetMapping("/confirm-file")
    public ApiResponse<Map<String, Object>> confirmFile() {
        return ApiResponse.ok(domainService.confirmFile());
    }

    @GetMapping("/{kind}")
    public ApiResponse<Map<String, Object>> read(@PathVariable String kind) {
        return ApiResponse.ok(domainService.read(kind));
    }

    @PutMapping("/{kind}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String kind,
                                                   @RequestBody UpdateRequest request) {
        return ApiResponse.ok(domainService.update(kind, request.action(), request.domains(),
                request.modifyPublishedTogether()));
    }
}
