package com.jistack.platform.wechat.token;

import com.jistack.platform.common.config.PlatformProperties;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * component_access_token 的生命周期：输入是微信每 10 分钟推的 ticket，输出是全站所有调用的门票。
 * 这一段出错时表现是「后台所有功能都报 40001/409」，所以 ticket 缺失的提示语、
 * 并发锁、TTL 提前量、票据新鲜度判定都要能离线验到，不能等上线才发现。
 */
@DisplayName("component 令牌：ticket 输入、并发锁与新鲜度")
class ComponentTokenLifecycleTest {

    private static final String APPID = "wxcomponent0000001";
    private static final String SECRET = "component-appsecret";
    private static final String TICKET_KEY = "wx:open:ticket:" + APPID;
    private static final String TOKEN_KEY = "wx:open:component-token:" + APPID;
    private static final String LOCK_KEY = "wx:open:lock:component-token:" + APPID;

    private final ObjectMapper json = new ObjectMapper();

    private MockWebServer server;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private WechatTokenStore store;
    private PlatformConfig config;
    private ComponentTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WechatApiClient api = new WechatApiClient(server.url("/").toString(), new RestTemplate(), json);

        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        config = new PlatformConfig();
        config.setComponentAppid(APPID);
        config.setComponentSecret(SECRET);
        PlatformConfigService configService = mock(PlatformConfigService.class);
        when(configService.requireReady()).thenReturn(config);
        when(configService.load()).thenReturn(config);

        store = mock(WechatTokenStore.class);
        when(store.client(any())).thenReturn(api);

        PlatformProperties properties = new PlatformProperties(null, null,
                new PlatformProperties.Wechat(null, null, null, null, null));
        service = new ComponentTokenService(redis, configService, store, properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void ok(String body) {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body));
    }

    private JsonNode nextBody() throws Exception {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).as("应有一次 api_component_token 请求").isNotNull();
        return json.readTree(request.getBody().readUtf8());
    }

    @Test
    @DisplayName("ticket 没收到时给出可操作的 409，不打微信也不留锁")
    void missingTicketExplainsCallbackUrl() {
        when(values.get(TICKET_KEY)).thenReturn(null);
        when(store.latestTicket(APPID)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> service.get())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("授权事件接收 URL")
                .hasMessageContaining("每 10 分钟");

        assertThat(server.getRequestCount()).isZero();
        verify(redis).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("Redis 里没有 ticket 时从库里恢复，重启后不需要等下一次推送")
    void ticketRestoresFromDatabase() throws Exception {
        when(values.get(TICKET_KEY)).thenReturn(null);
        when(values.get(TOKEN_KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(store.latestTicket(APPID)).thenReturn("TICKET_FROM_DB");
        ok("{\"errcode\":0,\"component_access_token\":\"CT_1\",\"expires_in\":7200}");

        assertThat(service.get()).isEqualTo("CT_1");

        JsonNode body = nextBody();
        assertThat(body.path("component_appid").asText()).isEqualTo(APPID);
        assertThat(body.path("component_appsecret").asText()).isEqualTo(SECRET);
        assertThat(body.path("component_verify_ticket").asText()).isEqualTo("TICKET_FROM_DB");
    }

    @Test
    @DisplayName("令牌按提前量缓存，并把到期时间落库供健康检查展示")
    void cachesWithRefreshAhead() throws Exception {
        when(values.get(TICKET_KEY)).thenReturn("TICKET_LIVE");
        when(values.get(TOKEN_KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        ok("{\"errcode\":0,\"component_access_token\":\"CT_2\",\"expires_in\":7200}");

        service.get();

        // 7200 - 600：压着过期时间用令牌会随机撞 42001
        verify(values).set(eq(TOKEN_KEY), eq("CT_2"), eq(Duration.ofSeconds(6600)));
        verify(store).recordComponentToken(eq(APPID), eq("CT_2"), any(LocalDateTime.class));
        verify(redis).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("缓存命中直接返回，不加锁不请求")
    void cachedTokenShortCircuits() {
        when(values.get(TOKEN_KEY)).thenReturn("CT_CACHED");

        assertThat(service.get()).isEqualTo("CT_CACHED");

        assertThat(server.getRequestCount()).isZero();
        verify(values, never()).setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("另一个实例正在取号时只等缓存，不重复取号")
    void waitsForConcurrentHolder() {
        when(values.get(TOKEN_KEY)).thenReturn(null, null, "CT_BY_OTHERS");
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.get()).isEqualTo("CT_BY_OTHERS");

        assertThat(server.getRequestCount()).isZero();
        verify(redis, never()).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("微信报错也释放锁，并提示 ticket 过期这一最常见原因")
    void releasesLockOnWechatError() {
        when(values.get(TICKET_KEY)).thenReturn("TICKET_STALE");
        when(values.get(TOKEN_KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        ok("{\"errcode\":61023,\"errmsg\":\"invalid refresh_token\"}");

        assertThatThrownBy(() -> service.get())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("component_access_token 失败")
                .hasMessageContaining("ticket 已过期");

        verify(redis).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("响应里没有 component_access_token 时报 502，不写坏缓存")
    void missingTokenInResponse() {
        when(values.get(TICKET_KEY)).thenReturn("TICKET_LIVE");
        when(values.get(TOKEN_KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        ok("{\"errcode\":0,\"expires_in\":7200}");

        assertThatThrownBy(() -> service.get())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("component_access_token");

        verify(values, never()).set(eq(TOKEN_KEY), anyString(), any(Duration.class));
        verify(redis).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("收到新 ticket 时清掉旧 token 缓存，保证用最新 ticket 取号")
    void ticketRotationInvalidatesToken() {
        service.onTicketReceived(APPID, "TICKET_NEW");

        verify(values).set(eq(TICKET_KEY), eq("TICKET_NEW"), eq(Duration.ofMinutes(35)));
        verify(redis).delete(TOKEN_KEY);
    }

    @Test
    @DisplayName("forceRefresh 先删缓存再取号，用于 40001 自愈")
    void forceRefreshDropsCacheFirst() throws Exception {
        when(values.get(TICKET_KEY)).thenReturn("TICKET_LIVE");
        when(values.get(TOKEN_KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        ok("{\"errcode\":0,\"component_access_token\":\"CT_FORCED\",\"expires_in\":7200}");

        assertThat(service.forceRefresh()).isEqualTo("CT_FORCED");
        verify(redis).delete(TOKEN_KEY);
    }

    // -------------------------------------------------- 票据新鲜度（平台健康页）

    @Test
    @DisplayName("刚推来的 ticket 因 MySQL 毫秒进位可能算出负数，按 0 秒处理")
    void freshTicketIsNotNegative() {
        when(store.latestTicketTime(APPID)).thenReturn(LocalDateTime.now().plusSeconds(1));

        assertThat(service.ticketAgeSeconds(APPID)).isZero();
        assertThat(service.ticketFresh(APPID)).isTrue();
    }

    @Test
    @DisplayName("库里没有票据时用 -1 区分「从未收到」与「刚收到」")
    void noTicketIsSentinel() {
        when(store.latestTicketTime(APPID)).thenReturn(null);

        assertThat(service.ticketAgeSeconds(APPID)).isEqualTo(-1);
        assertThat(service.ticketFresh(APPID)).isFalse();
    }

    @Test
    @DisplayName("超过 12 分钟没有 ticket 判为回调链路异常")
    void staleTicketFailsHealth() {
        when(store.latestTicketTime(APPID)).thenReturn(LocalDateTime.now().minusMinutes(13));

        assertThat(service.ticketAgeSeconds(APPID)).isGreaterThanOrEqualTo(780);
        assertThat(service.ticketFresh(APPID)).isFalse();
    }

    @Test
    @DisplayName("nextExpiry 只缓存真实存在时给时间，键已过期返回空")
    void nextExpiryHandlesMissingKey() {
        when(redis.getExpire(TOKEN_KEY, TimeUnit.SECONDS)).thenReturn(-2L);
        assertThat(service.nextExpiry()).isNull();

        when(redis.getExpire(TOKEN_KEY, TimeUnit.SECONDS)).thenReturn(120L);
        assertThat(service.nextExpiry()).isAfter(LocalDateTime.now());
    }
}
