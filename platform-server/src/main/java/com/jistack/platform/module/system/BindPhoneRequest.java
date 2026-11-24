package com.jistack.platform.module.system;

/**
 * 绑定/更换手机号。要密码是因为手机号本身就是一条登录通道，
 * 只有会话被偷的人还差一步就能给自己补一个「不需要密码的登录方式」；
 * 有了密码，抢下令牌的人若不知道密码，仍然加不上这条通道。
 */
public record BindPhoneRequest(String phone, String code, String password) {
}
