package com.jistack.platform.module.template;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_template")
public class CodeTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateId;
    private String userVersion;
    private String userDesc;
    private String draftId;
    private String categoryJson;
    private String source;
    private String rawJson;
    private LocalDateTime syncedAt;
}
