package com.jistack.platform.wechat.token;

import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.ComponentApi;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.client.WechatApiException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * component_access_token 生命周期：以微信每 10 分钟推送的 component_verify_ticket 为输入，
 * Redis 缓存 + 提前刷新 + Redis 分布式锁避免并发重复取号（微信同一 ticket 重复取号会互相顶掉）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentTokenService {

    private static final String TICKET_KEY = "wx:open:ticket:";
    private static final String TOKEN_KEY = "wx:open:component-token:";
    private static final String LOCK_KEY = "wx:open:lock:component-token:";
    private static final Duration TOKEN_TTL = Duration.ofSeconds(7200);

    private final StringRedisTemplate redis;
    private final PlatformConfigService configService;
    private final WechatTokenStore store;
    private final PlatformProperties properties;

    public void onTicketReceived(String componentAppid, String ticket) {
        redis.opsForValue().set(TICKET_KEY + componentAppid, ticket, Duration.ofMinutes(35));
        // ticket 轮换后旧 token 仍然可用，但缓存的 token 若来自更早的 ticket 需失效以保证一致性
        redis.delete(TOKEN_KEY + componentAppid);
    }

    public String cachedTicket(String componentAppid) {
        String ticket = redis.opsForValue().get(TICKET_KEY + componentAppid);
        if (StringUtils.hasText(ticket)) {
            return ticket;
        }
        return store.latestTicket(componentAppid);
    }

    public String get() {
        PlatformConfig config = configService.requireReady();
        String key = TOKEN_KEY + config.getComponentAppid();
        String cached = redis.opsForValue().get(key);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        return refresh(config);
    }

    /** 令牌被微信判定失效时调用（40001/42001/61005 等）。 */
    public void invalidate() {
        PlatformConfig config = configService.load();
        redis.delete(TOKEN_KEY + config.getComponentAppid());
    }

    public String forceRefresh() {
        PlatformConfig config = configService.requireReady();
        redis.delete(TOKEN_KEY + config.getComponentAppid());
        return refresh(config);
    }

    private String refresh(PlatformConfig config) {
        String appid = config.getComponentAppid();
        String key = TOKEN_KEY + appid;
        String lockKey = LOCK_KEY + appid;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(20));
        if (!Boolean.TRUE.equals(locked)) {
            // 其他实例正在取号：等它写缓存
            for (int i = 0; i < 40; i++) {
                sleep(250);
                String cached = redis.opsForValue().get(key);
                if (StringUtils.hasText(cached)) {
                    return cached;
                }
            }
            throw new BizException(503, "获取 component_access_token 的并发锁等待超时");
        }
        try {
            String cached = redis.opsForValue().get(key);
            if (StringUtils.hasText(cached)) {
                return cached;
            }
            String ticket = cachedTicket(appid);
            if (!StringUtils.hasText(ticket)) {
                throw new BizException(409, "尚未收到微信推送的 component_verify_ticket（每 10 分钟一次）。"
                        + "请确认第三方平台后台的「授权事件接收 URL」已指向本站并已启用");
            }
            WechatApiClient client = store.client(config);
            JsonNode response;
            try {
                response = ComponentApi.componentToken(client, appid, config.getComponentSecret(), ticket);
            } catch (WechatApiException ex) {
                throw new BizException(ex.getErrcode(),
                        "获取 component_access_token 失败: " + ex.getMessage()
                                + "（若为 61023/40001 通常是 ticket 已过期，等待下一次推送即可）");
            }
            String token = response.path("component_access_token").asText(null);
            if (!StringUtils.hasText(token)) {
                throw new BizException(502, "微信未返回 component_access_token: " + response);
            }
            long expiresIn = response.path("expires_in").asLong(TOKEN_TTL.toSeconds());
            long usable = Math.max(300, expiresIn - refreshAheadSeconds());
            redis.opsForValue().set(key, token, Duration.ofSeconds(usable));
            store.recordComponentToken(appid, token, LocalDateTime.now().plusSeconds(usable));
            return token;
        } finally {
            redis.delete(lockKey);
        }
    }

    private long refreshAheadSeconds() {
        Duration ahead = properties.wechat() == null ? null : properties.wechat().refreshAhead();
        return ahead == null ? 600 : Math.max(60, ahead.toSeconds());
    }

    public LocalDateTime nextExpiry() {
        PlatformConfig config = configService.load();
        Long remaining = redis.getExpire(TOKEN_KEY + config.getComponentAppid(), java.util.concurrent.TimeUnit.SECONDS);
        if (remaining == null || remaining <= 0) {
            return null;
        }
        return LocalDateTime.now().plusSeconds(remaining);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供健康检查使用：票据距今秒数，无票据返回 -1。 */
    public long ticketAgeSeconds(String componentAppid) {
        LocalDateTime receivedAt = store.latestTicketTime(componentAppid);
        if (receivedAt == null) {
            return -1;
        }
        // received_at 只存到秒，MySQL 会把毫秒进位，刚推完的票据可能带未来时间，
        // 直接取秒数会得到 -1（与"无票据"哨兵值撞车），这里按 0 处理
        return Math.max(0, Duration.between(receivedAt, LocalDateTime.now()).getSeconds());
    }

    /** ticket 推送健康阈值：超过 12 分钟没收到即认为回调链路异常。 */
    public boolean ticketFresh(String componentAppid) {
        long age = ticketAgeSeconds(componentAppid);
        return age >= 0 && age < Duration.ofMinutes(12).toSeconds();
    }
}
