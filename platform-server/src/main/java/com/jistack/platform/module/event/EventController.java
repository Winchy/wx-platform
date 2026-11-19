package com.jistack.platform.module.event;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.common.web.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信推送的授权事件与消息记录，用于排查 ticket 中断、签名校验失败等问题。
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventLogMapper eventLogMapper;

    @GetMapping
    public ApiResponse<PageResult<EventLog>> page(@RequestParam(required = false) String infoType,
                                                 @RequestParam(required = false) String appid,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<EventLog>()
                // 原文与解密报文可能很大，列表不返回，详情接口再取
                .select(EventLog::getId, EventLog::getInfoType, EventLog::getAppid, EventLog::getBodyType,
                        EventLog::getSignatureOk, EventLog::getHandled, EventLog::getErrorMessage,
                        EventLog::getReceivedAt)
                .eq(StringUtils.hasText(infoType), EventLog::getInfoType, infoType)
                .eq(StringUtils.hasText(appid), EventLog::getAppid, appid)
                .orderByDesc(EventLog::getId);
        Page<EventLog> result = eventLogMapper.selectPage(new Page<>(page, size), wrapper);
        return ApiResponse.ok(new PageResult<>(result.getRecords(), result.getTotal(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<EventLog> detail(@PathVariable Long id) {
        return ApiResponse.ok(eventLogMapper.selectById(id));
    }
}
