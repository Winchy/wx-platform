package com.jistack.platform.module.system;

public record UserVO(Long id, String username, String nickname, String role) {

    public static UserVO of(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }
}
