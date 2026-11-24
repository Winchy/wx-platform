package com.jistack.platform.module.system;

/** 解绑手机号同样要密码：手机丢了的人还能用密码解绑，只有令牌的人不行。 */
public record UnbindPhoneRequest(String password) {
}
