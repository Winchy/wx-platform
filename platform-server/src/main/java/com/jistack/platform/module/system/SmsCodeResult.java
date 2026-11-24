package com.jistack.platform.module.system;

/** 倒计时以服务端为准：冷却时长与有效期都是可配的，界面写死 60/300 会在改了配置后自己骗自己。 */
public record SmsCodeResult(int cooldownSeconds, int expiresIn) {
}
