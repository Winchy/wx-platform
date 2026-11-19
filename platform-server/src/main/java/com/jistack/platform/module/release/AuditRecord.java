package com.jistack.platform.module.release;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_audit_record")
public class AuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String authorizerAppid;
    private String auditId;
    private Integer status;
    private String reason;
    private String detailJson;
    private LocalDateTime submittedAt;
    private LocalDateTime lastQueriedAt;
}
