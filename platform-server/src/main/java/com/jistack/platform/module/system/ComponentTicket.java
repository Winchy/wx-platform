package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_component_ticket")
public class ComponentTicket {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String componentAppid;
    private String ticket;
    private LocalDateTime receivedAt;
}
