package com.jistack.platform.module.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private String operatorName;
    private String action;
    private String authorizerAppid;
    private String apiPath;
    private String requestSummary;
    private String responseSummary;
    private Integer success;
    private Integer errcode;
    private String errmsg;
    private Integer costMs;
    private LocalDateTime createdAt;
}
