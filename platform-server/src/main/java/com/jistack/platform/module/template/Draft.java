package com.jistack.platform.module.template;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_draft")
public class Draft {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String draftId;
    private String userVersion;
    private String userDesc;
    private String screenshot;
    private Integer latest;
    private Long createdTime;
    private String rawJson;
    private LocalDateTime syncedAt;
}
