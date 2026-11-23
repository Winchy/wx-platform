package com.jistack.platform.module.system;

public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
