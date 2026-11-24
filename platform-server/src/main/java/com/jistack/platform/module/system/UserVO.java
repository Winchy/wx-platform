package com.jistack.platform.module.system;

import com.jistack.platform.module.system.sms.PhoneNumbers;

public record UserVO(Long id, String username, String nickname, String role, String phoneMasked) {

    public static UserVO of(SysUser user) {
        // 手机号本身就是一条登录通道，回给界面的只留脱敏形式：够本人确认绑的是哪个号，不够别人拿去当靶子
        String masked = user.getPhone() == null || user.getPhone().isBlank()
                ? null : PhoneNumbers.mask(user.getPhone());
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole(), masked);
    }
}
