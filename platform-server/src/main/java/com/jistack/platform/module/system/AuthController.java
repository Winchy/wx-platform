package com.jistack.platform.module.system;

import com.jistack.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /**
     * 取登录验证码。免鉴权，所以配额与冷却都在服务端挡（见 SmsCodeService）；
     * 响应不区分「这个号码绑没绑账号」，理由写在 AuthService.issueLoginCode。
     */
    @PostMapping("/sms-code")
    public ApiResponse<SmsCodeResult> smsCode(@RequestBody SmsCodeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.issueLoginCode(request == null ? null : request.phone(),
                servletRequest.getRemoteAddr()));
    }

    /** 手机号 + 验证码登录，免鉴权。 */
    @PostMapping("/login-sms")
    public ApiResponse<LoginResponse> loginBySms(@RequestBody SmsLoginRequest request) {
        return ApiResponse.ok(authService.loginBySms(request));
    }

    /** 绑定/更换手机号前的取码，需要已登录。 */
    @PostMapping("/phone/sms-code")
    public ApiResponse<SmsCodeResult> phoneSmsCode(@RequestBody SmsCodeRequest request,
                                                   HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.issueBindCode(request == null ? null : request.phone(),
                servletRequest.getRemoteAddr()));
    }

    @PutMapping("/phone")
    public ApiResponse<Void> bindPhone(@RequestBody BindPhoneRequest request) {
        authService.bindPhone(request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/phone")
    public ApiResponse<Void> unbindPhone(@RequestBody(required = false) UnbindPhoneRequest request) {
        authService.unbindPhone(request);
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(authService.current());
    }

    /**
     * 修改当前账号密码。成功后本次请求带的令牌也一起作废（pwd_ver 加了），
     * 所以界面要清掉会话、用新密码重新登录。
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.ok();
    }

    /** 无状态令牌，登出由前端丢弃 token；保留接口便于审计与后续接入黑名单。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok();
    }
}
