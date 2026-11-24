package com.jistack.platform.module.system;

import com.jistack.platform.common.config.SmsProperties;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.SmsCodeService.Purpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证码策略：一次一废、错满作废、按号码与按来源限流、冷却挡住连点，
 * 而 Redis 里只有摘要——读到 Redis 的人不该顺带拿到能登录的凭据。
 *
 * <p>这里用一个内存假 Redis（只实现本类用到的那几个命令，并如实模拟「INCR 出来的键默认没有过期时间」），
 * 因为策略的价值在于状态如何随调用次数变化，逐条断言 redis 调用反而看不出结果。
 */
class SmsCodeServiceTest {

    private static final String PHONE = "13800138000";
    private static final String IP = "203.0.113.9";

    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Long> ttls = new HashMap<>();

    private StringRedisTemplate redis;
    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        redis = fakeRedis();
        service = new SmsCodeService(redis, properties(null));
    }

    /** 内存假 Redis：只实现本类用到的命令，并如实模拟「INCR 出来的键默认没有过期时间」。 */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(call -> store.get(call.<String>getArgument(0)));
        when(values.increment(any())).thenAnswer(call -> bump(call.getArgument(0), 1));
        when(values.decrement(any())).thenAnswer(call -> bump(call.getArgument(0), -1));
        when(template.getExpire(any(), any(TimeUnit.class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            // 与 Redis 一致：-2 键不存在，-1 键存在但没设过期
            if (!store.containsKey(key)) {
                return -2L;
            }
            return ttls.getOrDefault(key, -1L);
        });
        when(template.delete(any(String.class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            ttls.remove(key);
            return store.remove(key) != null;
        });
        when(template.expire(any(), any(Duration.class))).thenAnswer(call -> {
            ttls.put(call.getArgument(0), ((Duration) call.getArgument(1)).getSeconds());
            return true;
        });
        org.mockito.Mockito.doAnswer(call -> {
            store.put(call.getArgument(0), call.getArgument(1));
            ttls.put(call.<String>getArgument(0), ((Duration) call.getArgument(2)).getSeconds());
            return null;
        }).when(values).set(any(), any(), any(Duration.class));
        return template;
    }

    @Test
    @DisplayName("签发：存 SHA-256 摘要而不是明文，验证码键 5 分钟、冷却 60 秒、配额计数都带上限")
    void issueStoresHashWithAllTtl() {
        String code = service.issue(PHONE, Purpose.LOGIN, IP);

        assertThat(code).matches("\\d{6}");
        String codeKey = "sms:code:login:" + PHONE;
        assertThat(store).containsEntry(codeKey, sha256Hex(code));
        assertThat(store.get(codeKey)).isNotEqualTo(code);
        assertThat(ttls.get(codeKey)).isEqualTo(300L);
        assertThat(ttls.get("sms:cd:login:" + PHONE)).isEqualTo(60L);

        String dayKey = "sms:quota:phone:" + PHONE + ":" + LocalDate.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(store).containsEntry(dayKey, "1");
        assertThat(ttls.get(dayKey)).as("计数键必须有过期时间，否则崩溃在两步之间会把号码永久锁死").isEqualTo(86400L);
        String ipKey = "sms:quota:ip:" + IP + ":" + LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        assertThat(store).containsEntry(ipKey, "1");
        assertThat(ttls.get(ipKey)).isEqualTo(3600L);
    }

    @Test
    @DisplayName("冷却没过就再点：429 并给出剩余秒数，不再生成第二个码")
    void resendInsideCooldownIsRejected() {
        String first = service.issue(PHONE, Purpose.LOGIN, IP);

        assertThatThrownBy(() -> service.issue(PHONE, Purpose.LOGIN, IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码发送过于频繁")
                .hasMessageContaining("秒后再试")
                .extracting("code").isEqualTo(429);

        assertThat(store.get("sms:code:login:" + PHONE)).isEqualTo(sha256Hex(first));
    }

    @Test
    @DisplayName("每个手机号每天封顶 10 条：第 11 条挡住（按条计费的花钱闸门）")
    void dailyLimitPerPhoneBlocksTheEleventh() {
        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) {
                service.issue(PHONE, Purpose.LOGIN, "10.0.0." + i);
                store.remove("sms:cd:login:" + PHONE);
            }
        }).doesNotThrowAnyException();

        store.remove("sms:cd:login:" + PHONE);
        assertThatThrownBy(() -> service.issue(PHONE, Purpose.LOGIN, "10.0.0.99"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("今日验证码发送次数已达上限");
    }

    @Test
    @DisplayName("同一个来源 IP 每小时封顶 20 条：换着号码刷也刷不动")
    void hourlyLimitPerIpBlocksTheTwentyFirst() {
        for (int i = 0; i < 20; i++) {
            service.issue("138001380" + String.format("%02d", i), Purpose.LOGIN, IP);
        }

        assertThatThrownBy(() -> service.issue("13900000000", Purpose.LOGIN, IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("当前网络的验证码请求过于频繁");
    }

    @Test
    @DisplayName("校验通过即作废：同一个码用第二次就不算通过")
    void correctCodeIsSingleUse() {
        String code = service.issue(PHONE, Purpose.LOGIN, IP);

        assertThat(service.verify(PHONE, Purpose.LOGIN, code)).isTrue();
        assertThat(store).doesNotContainKey("sms:code:login:" + PHONE);
        assertThat(service.verify(PHONE, Purpose.LOGIN, code)).isFalse();
    }

    @Test
    @DisplayName("错满 5 次后第 6 次即使输对也作废：爆破 6 位数字的机会被掐死")
    void fiveWrongAttemptsInvalidateTheCode() {
        String code = service.issue(PHONE, Purpose.LOGIN, IP);

        for (int i = 0; i < 5; i++) {
            assertThat(service.verify(PHONE, Purpose.LOGIN, "00000" + i)).isFalse();
        }
        assertThat(store).containsKey("sms:code:login:" + PHONE);

        assertThat(service.verify(PHONE, Purpose.LOGIN, code)).isFalse();
        assertThat(store).doesNotContainKey("sms:code:login:" + PHONE);
        assertThat(store).doesNotContainKey("sms:try:login:" + PHONE);
    }

    @Test
    @DisplayName("尝试次数键继承验证码的剩余有效期，不会留下无期限的键")
    void attemptsKeyInheritsRemainingTtl() {
        String code = service.issue(PHONE, Purpose.LOGIN, IP);
        assertThat(service.verify(PHONE, Purpose.LOGIN, "wrong!")).isFalse();

        assertThat(store).containsEntry("sms:try:login:" + PHONE, "1");
        assertThat(ttls.get("sms:try:login:" + PHONE)).isLessThanOrEqualTo(300L);
        assertThat(ttls.get("sms:try:login:" + PHONE)).isPositive();
        assertThat(code).hasSize(6);
    }

    @Test
    @DisplayName("用途分键，互不通用：登录码进不了绑定，绑定码进不了登录，而且拿错用途去试不会把原码消耗掉")
    void codesAreNotInterchangeableBetweenPurposes() {
        String loginCode = service.issue(PHONE, Purpose.LOGIN, IP);

        assertThat(service.verify(PHONE, Purpose.BIND, loginCode)).isFalse();
        // 错位校验不该消耗正确的码：否则用户在自己没做的事上被扣掉一次机会
        assertThat(service.verify(PHONE, Purpose.LOGIN, loginCode)).isTrue();

        String bindCode = service.issue(PHONE, Purpose.BIND, IP);
        assertThat(service.verify(PHONE, Purpose.LOGIN, bindCode)).isFalse();
        assertThat(service.verify(PHONE, Purpose.BIND, bindCode)).isTrue();
    }

    @Test
    @DisplayName("同一号码的绑定冷却与登录冷却互不影响")
    void purposesHaveSeparateCooldowns() {
        service.issue(PHONE, Purpose.LOGIN, IP);

        assertThatCode(() -> service.issue(PHONE, Purpose.BIND, IP)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.issue(PHONE, Purpose.LOGIN, IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过于频繁");
    }

    @Test
    @DisplayName("短信没发出去时回滚：码作废、冷却撤销、两个配额都退回")
    void discardRollsBackEverything() {
        String code = service.issue(PHONE, Purpose.LOGIN, IP);
        assertThat(store).containsKey("sms:cd:login:" + PHONE);

        service.discard(PHONE, Purpose.LOGIN, IP);

        assertThat(store)
                .doesNotContainKey("sms:code:login:" + PHONE)
                .doesNotContainKey("sms:cd:login:" + PHONE)
                .doesNotContainKey("sms:try:login:" + PHONE);
        assertThat(store).containsEntry("sms:quota:phone:" + PHONE + ":"
                + LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.BASIC_ISO_DATE), "0");
        // 回滚之后可以马上重新申请，不会被「今日上限」挡住真实原因
        assertThatCode(() -> service.issue(PHONE, Purpose.LOGIN, IP)).doesNotThrowAnyException();
        assertThat(store.get("sms:code:login:" + PHONE)).isNotEqualTo(sha256Hex(code));
    }

    @Test
    @DisplayName("空验证码不参与校验，也不消耗尝试次数")
    void blankCodeIsRefusedEarly() {
        service.issue(PHONE, Purpose.LOGIN, IP);

        assertThat(service.verify(PHONE, Purpose.LOGIN, null)).isFalse();
        assertThat(service.verify(PHONE, Purpose.LOGIN, "  ")).isFalse();
        assertThat(store).doesNotContainKey("sms:try:login:" + PHONE);
    }

    @Test
    @DisplayName("没发过 / 已过期的号码直接不通过，界面拿不到「有没有这个号」的反馈")
    void verifyWithoutIssuedCodeFails() {
        assertThat(service.verify("13700137000", Purpose.LOGIN, "123456")).isFalse();
    }

    @Test
    @DisplayName("默认策略值就是配置文件里写的那一套")
    void defaultPolicyMatchesConfiguredValues() {
        assertThat(service.codeTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(service.resendCooldown()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("配置可以把有效期与上限改掉，策略跟着变")
    void configuredPolicyOverridesDefaults() {
        SmsCodeService tuned = new SmsCodeService(redis,
                properties(new SmsProperties.Code(Duration.ofSeconds(60), Duration.ofSeconds(5), 2, 2, 3)));
        String code = tuned.issue(PHONE, Purpose.LOGIN, IP);

        assertThat(ttls.get("sms:code:login:" + PHONE)).isEqualTo(60L);
        assertThat(tuned.verify(PHONE, Purpose.LOGIN, "000000")).isFalse();
        assertThat(tuned.verify(PHONE, Purpose.LOGIN, "000001")).isFalse();
        // maxVerifyAttempts=2：第三次（哪怕输对）也作废
        assertThat(tuned.verify(PHONE, Purpose.LOGIN, code)).isFalse();
    }

    private long bump(String key, long delta) {
        long next = Long.parseLong(store.getOrDefault(key, "0")) + delta;
        store.put(key, String.valueOf(next));
        return next;
    }

    private static SmsProperties properties(SmsProperties.Code code) {
        return new SmsProperties(false, null, null, null, null, null, null, null, null, null, code);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
