package com.jistack.platform.module.system;

import com.jistack.platform.common.web.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
        String token = tokenService.issue(new RequestContext.Principal(user.getId(), user.getUsername(), user.getRole()));
        return new LoginResponse(token, tokenService.ttlSeconds(), UserVO.of(user));
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
