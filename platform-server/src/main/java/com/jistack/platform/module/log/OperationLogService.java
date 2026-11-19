package com.jistack.platform.module.log;

import com.jistack.platform.common.web.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper mapper;

    /** 审计写入失败不能影响主流程，故整体 try/catch 吞掉异常。 */
    public void record(String action, String authorizerAppid, String apiPath, String requestSummary,
                       String responseSummary, boolean success, Integer errcode, String errmsg, Integer costMs) {
        try {
            OperationLog entity = new OperationLog();
            var current = com.jistack.platform.module.system.RequestContext.get();
            if (current != null) {
                entity.setOperatorId(current.id());
                entity.setOperatorName(current.username());
            }
            entity.setAction(action);
            entity.setAuthorizerAppid(authorizerAppid);
            entity.setApiPath(apiPath);
            entity.setRequestSummary(truncate(requestSummary, 4000));
            entity.setResponseSummary(truncate(responseSummary, 4000));
            entity.setSuccess(success ? 1 : 0);
            entity.setErrcode(errcode);
            entity.setErrmsg(truncate(errmsg, 500));
            entity.setCostMs(costMs);
            mapper.insert(entity);
        } catch (Exception ex) {
            log.warn("操作日志写入失败 action={} err={}", action, ex.toString());
        }
    }

    public PageResult<OperationLog> query(String action, String appid, Boolean success, long page, long size) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .eq(StringUtils.hasText(action), OperationLog::getAction, action)
                .eq(StringUtils.hasText(appid), OperationLog::getAuthorizerAppid, appid)
                .eq(success != null, OperationLog::getSuccess, success == null ? null : (success ? 1 : 0))
                .orderByDesc(OperationLog::getId);
        Page<OperationLog> result = mapper.selectPage(new Page<>(page, size), wrapper);
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    public List<OperationLog> recent(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getId).last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
