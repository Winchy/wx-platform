package com.jistack.platform.module.system;

import com.jistack.platform.common.web.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse login(LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            throw new BizException(400, "用户名与密码不能为空");
        }
        SysUser user = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("账号已禁用");
        }
        user.setLastLoginAt(java.time.LocalDateTime.now());
        userMapper.updateById(user);
        String token = tokenService.issue(new RequestContext.Principal(
                user.getId(), user.getUsername(), user.getRole(), pwdVer(user)));
        return new LoginResponse(token, tokenService.ttlSeconds(), UserVO.of(user));
    }

    /**
     * 修改当前登录账号的密码。改完把 pwd_ver 加一，此前签发的令牌当场失效，
     * 所以调用方（界面）需要带着新密码重新登录。
     */
    public void changePassword(ChangePasswordRequest request) {
        RequestContext.Principal principal = RequestContext.require();
        String oldPassword = request == null ? null : request.oldPassword();
        String newPassword = request == null ? null : request.newPassword();
        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            throw new BizException(400, "原密码与新密码都不能为空");
        }
        SysUser user = userMapper.selectById(principal.id());
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            // 只说「原密码不正确」，不区分账号不存在/已禁用，避免给撞库的人反馈
            throw BizException.forbidden("原密码不正确");
        }
        if (newPassword.length() < 8 || newPassword.length() > 64) {
            throw new BizException(400, "新密码长度需为 8-64 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(400, "新密码不能与原密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPwdVer(pwdVer(user) + 1);
        userMapper.updateById(user);
        log.info("账号 {} 修改密码，pwd_ver={}（此前签发的令牌已失效）", user.getUsername(), user.getPwdVer());
    }

    private static int pwdVer(SysUser user) {
        return user.getPwdVer() == null ? 0 : user.getPwdVer();
    }

    public UserVO current() {
        RequestContext.Principal principal = RequestContext.require();
        SysUser user = userMapper.selectById(principal.id());
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }
        return UserVO.of(user);
    }

    String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
