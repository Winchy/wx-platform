package com.jistack.platform.module.authorizer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_authorizer_token")
public class AuthorizerToken {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String authorizerAppid;
    private String accessToken;
    private String refreshTokenEnc;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
