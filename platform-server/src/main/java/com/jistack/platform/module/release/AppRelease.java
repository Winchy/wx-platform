package com.jistack.platform.module.release;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_app_release")
public class AppRelease {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String authorizerAppid;
    private String templateId;
    private String draftId;
    private String extAppid;
    private String userVersion;
    private String userDesc;
    private Integer submitDuration;
    private String qrcodeMediaId;
    private String status;
    private String lastAuditId;
    private String auditReason;
    private Integer errcode;
    private String errmsg;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
