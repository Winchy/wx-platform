package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.SmsCodeService.Purpose;
import com.jistack.platform.module.system.sms.PhoneNumbers;
import com.jistack.platform.module.system.sms.SmsSendException;
import com.jistack.platform.module.system.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 手机号未绑定时也走完全相同的签发流程，只是不发短信：响应必须一致，否则这里成了在册号码查询器。 */
    private final SysUserMapper userMapper;
    private final TokenService tokenService;
    private final SmsCodeService smsCodeService;
    private final SmsSender smsSender;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse login(LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            throw new BizException(400, "用户名与密码不能为空");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("账号已禁用");
        }
        return startSession(user);
    }

    /**
     * 短信验证码登录。与密码登录同一条会话出口，令牌里一样带 pwd_ver，
     * 所以「改密即踢掉所有旧会话」对这两种登录方式同时生效。
     */
    public LoginResponse loginBySms(SmsLoginRequest request) {
        String rawPhone = request == null ? null : request.phone();
        String code = request == null ? null : request.code();
        if (!StringUtils.hasText(rawPhone) || !StringUtils.hasText(code)) {
            throw new BizException(400, "手机号与验证码都必填");
        }
        // 填了却填错的人需要知道的是「格式不对」，而不是被告知自己已经填过的字段是空的
        String phone = normalizeOrThrow(rawPhone);
        // 先验码再查库：查库需要码为真，否则「验证码正确但号码没绑」也是一种可区分的反馈
        boolean verified = smsCodeService.verify(phone, Purpose.LOGIN, code);
        SysUser user = verified ? findByPhone(phone) : null;
        if (user == null) {
            // 验证码错 / 已过期 / 这个号没绑过账号，三种情况合成一句，不给探测者留区分依据
            throw BizException.unauthorized("手机号或验证码不正确");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("账号已禁用");
        }
        log.info("手机号验证码登录成功 user={} phone={}", user.getUsername(), PhoneNumbers.mask(phone));
        return startSession(user);
    }

    /** 登录页取验证码（免鉴权）。 */
    public SmsCodeResult issueLoginCode(String rawPhone, String clientIp) {
        String phone = normalizeOrThrow(rawPhone);
        String code = smsCodeService.issue(phone, Purpose.LOGIN, clientIp);
        if (findByPhone(phone) == null) {
            log.info("手机号未绑定任何账号，跳过短信发送 phone={}", PhoneNumbers.mask(phone));
        } else {
            send(phone, code, Purpose.LOGIN, clientIp);
        }
        return new SmsCodeResult((int) smsCodeService.resendCooldown().toSeconds(),
                (int) smsCodeService.codeTtl().toSeconds());
    }

    /** 已登录用户绑定/更换手机号时取验证码：号码是他自己的，所以一定真发。 */
    public SmsCodeResult issueBindCode(String rawPhone, String clientIp) {
        RequestContext.require();
        String phone = normalizeOrThrow(rawPhone);
        String code = smsCodeService.issue(phone, Purpose.BIND, clientIp);
        send(phone, code, Purpose.BIND, clientIp);
        return new SmsCodeResult((int) smsCodeService.resendCooldown().toSeconds(),
                (int) smsCodeService.codeTtl().toSeconds());
    }

    /**
     * 绑定/更换手机号。密码 + 验证码两道都过才写库：
     * 手机号是一条不需要密码的登录通道，只凭被偷来的会话就能加上它，等于把账号交给攻击者。
     */
    public void bindPhone(BindPhoneRequest request) {
        String phone = normalizeOrThrow(request == null ? null : request.phone());
        String code = request == null ? null : request.code();
        if (!StringUtils.hasText(code)) {
            throw new BizException(400, "请填写短信验证码");
        }
        // 先验密码再验码：验码会消耗这个码的尝试次数，密码写错不该把人逼到重新申请
        SysUser user = requirePassword(RequestContext.require().id(), request.password());
        if (!smsCodeService.verify(phone, Purpose.BIND, code)) {
            throw new BizException(400, "验证码不正确或已过期");
        }
        SysUser taken = findByPhone(phone);
        if (taken != null && !taken.getId().equals(user.getId())) {
            throw new BizException(409, "该手机号已绑定其他账号");
        }
        user.setPhone(phone);
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException ex) {
            // 两个账号同时抢同一个号码时，唯一索引是最后一道闸，别把 SQL 抛给界面
            throw new BizException(409, "该手机号已绑定其他账号");
        }
        log.info("账号 {} 绑定手机号 {}", user.getUsername(), PhoneNumbers.mask(phone));
    }

    /** 解绑走密码：手机丢了的人还能用密码把这条路关掉，只有令牌的人关不掉。 */
    public void unbindPhone(UnbindPhoneRequest request) {
        SysUser user = requirePassword(RequestContext.require().id(), request == null ? null : request.password());
        if (user.getPhone() == null) {
            return;
        }
        // updateById 默认不写 null 字段，清空必须用 set(...) 显式生成 SET phone = NULL
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getPhone, null));
        log.info("账号 {} 解绑手机号 {}", user.getUsername(), PhoneNumbers.mask(user.getPhone()));
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

    private LoginResponse startSession(SysUser user) {
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
        String token = tokenService.issue(new RequestContext.Principal(
                user.getId(), user.getUsername(), user.getRole(), pwdVer(user)));
        return new LoginResponse(token, tokenService.ttlSeconds(), UserVO.of(user));
    }

    private void send(String phone, String code, Purpose purpose, String clientIp) {
        try {
            smsSender.send(phone, code);
        } catch (SmsSendException ex) {
            // 发不出去就退回配额与冷却：否则通道一坏，用户会被「今日次数已达上限」挡在真实原因前面
            smsCodeService.discard(phone, purpose, clientIp);
            log.error("验证码短信发送失败 phone={} providerCode={} reason={}",
                    PhoneNumbers.mask(phone), ex.getProviderCode(), ex.getMessage());
            // 阿里云的 Code 与原因只进日志（里面会有 AccessKey 状态、模板信息），界面只看这句
            throw new BizException(502, "验证码发送失败，请稍后重试");
        }
    }

    private SysUser requirePassword(Long userId, String password) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }
        if (!StringUtils.hasText(password) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw BizException.forbidden("密码不正确");
        }
        return user;
    }

    private SysUser findByPhone(String normalizedPhone) {
        return userMapper.selectByPhone(normalizedPhone);
    }

    private static String normalizeOrThrow(String rawPhone) {
        String phone = PhoneNumbers.normalize(rawPhone);
        if (phone == null) {
            throw new BizException(400, "手机号格式不正确，请填写 11 位中国大陆号码");
        }
        return phone;
    }

    private static int pwdVer(SysUser user) {
        return user.getPwdVer() == null ? 0 : user.getPwdVer();
    }
}
