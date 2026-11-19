package com.jistack.platform.module.authorizer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_auth_session")
public class AuthSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String state;
    private String scene;
    private String preAuthCode;
    private LocalDateTime preAuthExpiredAt;
    private String authCode;
    private String resultAppid;
    private String status;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
