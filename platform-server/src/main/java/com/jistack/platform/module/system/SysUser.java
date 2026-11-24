package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** 手机号（规范化后的 11 位裸号），NULL 表示未绑定，也就没有验证码登录这条路 */
    private String phone;
    private String passwordHash;
    private String nickname;
    private String role;
    private Integer status;
    /** 密码版本号：改密即 +1，令牌里带的版本与这里不一致就当未登录 */
    private Integer pwdVer;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
