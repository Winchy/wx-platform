package com.jistack.platform.module.system;

public record LoginResponse(String token, long expiresIn, UserVO user) {
}
