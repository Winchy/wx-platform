package com.jistack.platform.module.authorizer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 后端保存的小程序码原图，键就是小程序 AppID，一个小程序一份。 */
@Data
@TableName("wechat_authorizer_qrcode")
public class AuthorizerQrcode {

    @TableId(value = "authorizer_appid", type = IdType.INPUT)
    private String authorizerAppid;

    /** 取回这张图时用的微信链接；落库的 qrcode_url 变了说明微信换了码，需要重新取。 */
    private String sourceUrl;

    private String contentType;

    private byte[] image;

    private LocalDateTime syncedAt;
}
