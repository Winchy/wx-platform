package com.jistack.platform.module.authorizer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@ToString(exclude = "refreshToken")
@TableName("wechat_authorizer")
public class Authorizer {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String componentAppid;
    private String authorizerAppid;
    private String nickName;
    private String headImg;
    private String qrcodeUrl;
    private String alias;
    private String principalName;
    private String originalUsername;
    private Integer verifyType;
    private Integer serviceType;
    private String funcInfoJson;
    private String refreshTokenEnc;
    private LocalDateTime tokenExpiredAt;
    private String authState;
    private LocalDateTime authorizedAt;
    private String releaseVersion;
    private String verifyInfo;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 解密后的 refresh_token，仅在服务内部流转，不参与持久化与序列化。 */
    @TableField(exist = false)
    @JsonIgnore
    private transient String refreshToken;
}
