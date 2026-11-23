package com.jistack.platform.module.system;

import com.jistack.platform.common.web.ApiResponse;
import com.jistack.platform.common.web.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final TokenService tokenService;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (StringUtils.hasText(header) && header.startsWith(BEARER)) {
            token = header.substring(BEARER.length()).trim();
        } else if ("GET".equalsIgnoreCase(request.getMethod())) {
            // 图片类接口由浏览器直接发起，无法带 Authorization 头
            token = request.getParameter("token");
        }
        if (!StringUtils.hasText(token)) {
            reject(response, 401, "缺少访问令牌");
            return false;
        }
        RequestContext.Principal principal = tokenService.verify(token);
        if (principal == null) {
            reject(response, 401, "登录已过期，请重新登录");
            return false;
        }
        // 令牌是无状态签名，只验签的话「改了密码」与「禁用了账号」都要等 TTL 到期才生效；
        // 这里按主键多读一次（后台单租户、每请求一次 PK 查询），换取改密/禁用当场失效。
        SysUser user = userMapper.selectById(principal.id());
        if (user == null) {
            reject(response, 401, "账号不存在，请重新登录");
            return false;
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            reject(response, 401, "账号已被禁用，请重新登录");
            return false;
        }
        int pwdVer = user.getPwdVer() == null ? 0 : user.getPwdVer();
        if (pwdVer != principal.pwdVer()) {
            reject(response, 401, "密码已修改，请用新密码重新登录");
            return false;
        }
        RequestContext.set(principal);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }

    private void reject(HttpServletResponse response, int code, String message) {
        try {
            response.setStatus(code);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code, message)));
        } catch (Exception ex2) {
            throw BizException.forbidden(message);
        }
    }
}
