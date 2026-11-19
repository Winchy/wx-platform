package com.jistack.platform.module.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_event_log")
public class EventLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String infoType;
    private String appid;
    private String bodyType;
    private String rawBody;
    private String decryptedBody;
    private Integer signatureOk;
    private Integer handled;
    private String errorMessage;
    private LocalDateTime receivedAt;
}
