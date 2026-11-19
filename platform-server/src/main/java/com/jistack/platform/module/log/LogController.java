package com.jistack.platform.module.log;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 操作审计：记录每一次代调用小程序接口的请求、结果与耗时。
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final OperationLogService operationLogService;

    @GetMapping
    public ApiResponse<PageResult<OperationLog>> page(@RequestParam(required = false) String action,
                                                      @RequestParam(required = false) String appid,
                                                      @RequestParam(required = false) Boolean success,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(operationLogService.query(action, appid, success, page, size));
    }

    @GetMapping("/recent")
    public ApiResponse<List<OperationLog>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(operationLogService.recent(limit));
    }
}
