package com.jistack.platform.module.system;

import com.jistack.platform.common.config.SmsProperties;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.sms.PhoneNumbers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 验证码的签发与校验，只负责「一次一密 + 不让人把短信通道当提款机」，不负责运输。
 *
 * <p>Redis 里存的是验证码的 SHA-256，不是明文：读到 Redis 的人不该顺带拿到能登录的凭据。
 * 键都按用途分开（login / bind），于是「用来登录的码」永远不能被拿去改绑定，反之亦然。
 */
@Slf4j
@Service
public class SmsCodeService {

    /** 验证码用途。登录码与绑定码互不通用，见类注释。 */
    public enum Purpose {
        LOGIN("login"),
        BIND("bind");

        private final String prefix;

        Purpose(String prefix) {
            this.prefix = prefix;
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final Duration codeTtl;
    private final Duration resendCooldown;
    private final int maxVerifyAttempts;
    private final int dailyLimitPerPhone;
    private final int hourlyLimitPerIp;

    public SmsCodeService(StringRedisTemplate redis, SmsProperties properties) {
        this.redis = redis;
        SmsProperties.Code code = properties == null ? null : properties.code();
        this.codeTtl = orDefault(code == null ? null : code.ttl(), Duration.ofMinutes(5));
        this.resendCooldown = orDefault(code == null ? null : code.resendCooldown(), Duration.ofSeconds(60));
        this.maxVerifyAttempts = orDefault(code == null ? null : code.maxVerifyAttempts(), 5);
        this.dailyLimitPerPhone = orDefault(code == null ? null : code.dailyLimitPerPhone(), 10);
        this.hourlyLimitPerIp = orDefault(code == null ? null : code.hourlyLimitPerIp(), 20);
    }

    /**
     * 生成并登记一个验证码，返回明文（只在这一刻存在于返回值与短信里）。
     * 冷却没过时什么都不写就拒绝；配额挡住时只留下计数，没有验证码键。
     */
    public String issue(String phone, Purpose purpose, String clientIp) {
        Long cooling = redis.getExpire(cooldownKey(purpose, phone), TimeUnit.SECONDS);
        if (cooling != null && cooling > 0) {
            throw new BizException(429, "验证码发送过于频繁，请 " + cooling + " 秒后再试");
        }
        String today = LocalDate.now(ZoneId.systemDefault()).format(DAY);
        long phoneCount = incr(dayKey(phone, today), Duration.ofDays(1));
        if (phoneCount > dailyLimitPerPhone) {
            throw new BizException(429, "该手机号今日验证码发送次数已达上限（" + dailyLimitPerPhone
                    + " 次），请明天再试，或改用密码登录");
        }
        String hour = LocalDateTime.now(ZoneId.systemDefault()).format(HOUR);
        long ipCount = incr(ipKey(clientIp, hour), Duration.ofHours(1));
        if (ipCount > hourlyLimitPerIp) {
            throw new BizException(429, "当前网络的验证码请求过于频繁，请稍后再试");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(codeKey(purpose, phone), sha256Hex(code), codeTtl);
        redis.delete(attemptsKey(purpose, phone));
        redis.opsForValue().set(cooldownKey(purpose, phone), "1", resendCooldown);
        log.info("验证码已签发 phone={} purpose={} 当日第 {} 次", PhoneNumbers.mask(phone), purpose, phoneCount);
        return code;
    }

    /**
     * 短信没发出去时的回滚：作废这个码、撤掉冷却、把两个配额计数退回去。
     * 不回滚的话，通道一坏（凭据过期、欠费）用户点几次就撞上「今日上限」，
     * 看到的是「发太多」，真实原因却是根本没发出去。
     */
    public void discard(String phone, Purpose purpose, String clientIp) {
        redis.delete(codeKey(purpose, phone));
        redis.delete(attemptsKey(purpose, phone));
        redis.delete(cooldownKey(purpose, phone));
        decrementQuota(phone, clientIp);
    }

    /** 校验通过即作废（单次使用），失败累计次数，超过上限直接作废让人重新申请。 */
    public boolean verify(String phone, Purpose purpose, String rawCode) {
        if (!StringUtils.hasText(rawCode)) {
            return false;
        }
        String codeKey = codeKey(purpose, phone);
        String storedHash = redis.opsForValue().get(codeKey);
        if (storedHash == null) {
            // 没发过、已用过、或已过期——三种情况对外没有区别，调用方给同一句「手机号或验证码不正确」
            return false;
        }
        long attempts = incr(attemptsKey(purpose, phone), remainingTtl(codeKey));
        if (attempts > maxVerifyAttempts) {
            redis.delete(codeKey);
            redis.delete(attemptsKey(purpose, phone));
            log.warn("验证码错误次数达到 {} 次，已作废，需重新申请 phone={}", maxVerifyAttempts, PhoneNumbers.mask(phone));
            return false;
        }
        boolean matched = MessageDigest.isEqual(sha256Hex(rawCode.trim()).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
        if (matched) {
            redis.delete(codeKey);
            redis.delete(attemptsKey(purpose, phone));
        }
        return matched;
    }

    public Duration codeTtl() {
        return codeTtl;
    }

    public Duration resendCooldown() {
        return resendCooldown;
    }

    private void decrementQuota(String phone, String clientIp) {
        String today = LocalDate.now(ZoneId.systemDefault()).format(DAY);
        String hour = LocalDateTime.now(ZoneId.systemDefault()).format(HOUR);
        decrement(dayKey(phone, today));
        decrement(ipKey(clientIp, hour));
    }

    /** 计数减一；已经为 0 或键已消失就当作不需要退。 */
    private void decrement(String key) {
        Long left = redis.opsForValue().decrement(key);
        if (left != null && left < 0) {
            redis.delete(key);
        }
    }

    /** 计数加一，并保证键一定带过期时间（崩溃在两步之间也不会把人永久锁死）。 */
    private long incr(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        long count = value == null ? 1L : value;
        Long left = redis.getExpire(key, TimeUnit.SECONDS);
        if (count == 1L || left == null || left < 0) {
            redis.expire(key, ttl);
        }
        return count;
    }

    /** 键的剩余有效期；读不到、没设过期或键已消失时退回默认 TTL，绝不让计数器变成无期限键。 */
    private Duration remainingTtl(String key) {
        Long seconds = redis.getExpire(key, TimeUnit.SECONDS);
        return seconds == null || seconds < 0 ? codeTtl : Duration.ofSeconds(seconds);
    }

    private static String codeKey(Purpose purpose, String phone) {
        return "sms:code:" + purpose.prefix + ":" + phone;
    }

    private static String attemptsKey(Purpose purpose, String phone) {
        return "sms:try:" + purpose.prefix + ":" + phone;
    }

    private static String cooldownKey(Purpose purpose, String phone) {
        return "sms:cd:" + purpose.prefix + ":" + phone;
    }

    private static String dayKey(String phone, String day) {
        return "sms:quota:phone:" + phone + ":" + day;
    }

    private static String ipKey(String clientIp, String hour) {
        return "sms:quota:ip:" + (StringUtils.hasText(clientIp) ? clientIp : "unknown") + ":" + hour;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
