package com.jistack.platform.wechat.token;

import com.jistack.platform.common.crypto.SecretCipher;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.authorizer.Authorizer;
import com.jistack.platform.module.authorizer.AuthorizerMapper;
import com.jistack.platform.module.authorizer.AuthorizerToken;
import com.jistack.platform.module.authorizer.AuthorizerTokenMapper;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.client.WechatApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌链：第三方平台最容易在生产环境炸的一段，且坏了只会表现为「所有接口 42001」。
 * 这里只 mock Redis 与库表，微信侧走 MockWebServer，因此不依赖任何中间件也能验证
 * refresh_token 轮换必须回写、令牌失效只重试一次、授权失效要标记重新授权。
 */
@DisplayName("authorizer 令牌链：刷新、轮换回写与失效重试")
class AuthorizerTokenRotationTest {

    private static final String APPID = "wxauthorizer000001";
    private static final String COMPONENT_APPID = "wxcomponent0000001";
    private static final String TOKEN_KEY = "wx:open:authorizer-token:" + APPID;

    private final ObjectMapper json = new ObjectMapper();

    private MockWebServer server;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ComponentTokenService componentTokenService;
    private AuthorizerMapper authorizerMapper;
    private AuthorizerTokenMapper tokenMapper;
    private Authorizer authorizer;
    private AuthorizerTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WechatApiClient api = new WechatApiClient(server.url("/").toString(), new RestTemplate(), json);

        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        authorizer = new Authorizer();
        authorizer.setAuthorizerAppid(APPID);
        authorizer.setAuthState("AUTHORIZED");
        authorizer.setRefreshTokenEnc("ENC:REFRESH_OLD");

        authorizerMapper = mock(AuthorizerMapper.class);
        when(authorizerMapper.selectOne(any())).thenReturn(authorizer);
        tokenMapper = mock(AuthorizerTokenMapper.class);

        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt(any())).thenAnswer(invocation -> {
            String stored = invocation.getArgument(0);
            return stored == null ? null : (stored.startsWith("ENC:") ? stored.substring(4) : stored);
        });
        when(cipher.encrypt(any())).thenAnswer(invocation -> "ENC:" + invocation.getArgument(0));

        PlatformConfig config = new PlatformConfig();
        config.setComponentAppid(COMPONENT_APPID);
        PlatformConfigService configService = mock(PlatformConfigService.class);
        when(configService.requireReady()).thenReturn(config);

        componentTokenService = mock(ComponentTokenService.class);
        when(componentTokenService.get()).thenReturn("COMPONENT_TOKEN");
        when(componentTokenService.forceRefresh()).thenReturn("COMPONENT_TOKEN_NEW");

        WechatTokenStore store = mock(WechatTokenStore.class);
        when(store.client(any())).thenReturn(api);

        service = new AuthorizerTokenService(redis, authorizerMapper, tokenMapper, configService,
                componentTokenService, store, cipher);
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

    /** MockWebServer 按发起顺序出队，每次调用取下一条请求。 */
    private JsonNode nextBody() throws Exception {
        RecordedRequest request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).as("应有一次微信请求记录").isNotNull();
        return json.readTree(request.getBody().readUtf8());
    }

    // -------------------------------------------------- 刷新与轮换

    @Test
    @DisplayName("微信轮换 refresh_token 后必须回写，下次刷新用新值")
    void rotatedRefreshTokenIsPersisted() throws Exception {
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_1\",\"expires_in\":7200,"
                + "\"authorizer_refresh_token\":\"REFRESH_NEW\"}");

        JsonNode response = service.refresh(APPID);

        assertThat(nextBody().path("authorizer_refresh_token").asText()).isEqualTo("REFRESH_OLD");
        assertThat(response.path("authorizer_access_token").asText()).isEqualTo("AT_1");
        assertThat(authorizer.getRefreshTokenEnc()).isEqualTo("ENC:REFRESH_NEW");
        assertThat(authorizer.getAuthState()).isEqualTo("AUTHORIZED");
        verify(authorizerMapper).updateById(authorizer);
        // 提前 600 秒到期，避免压着过期时间用令牌
        verify(values).set(eq(TOKEN_KEY), eq("AT_1"), eq(Duration.ofSeconds(6600)));

        ArgumentCaptor<AuthorizerToken> row = ArgumentCaptor.forClass(AuthorizerToken.class);
        verify(tokenMapper).insert(row.capture());
        assertThat(row.getValue().getAccessToken()).isEqualTo("AT_1");
        assertThat(row.getValue().getRefreshTokenEnc()).isEqualTo("ENC:REFRESH_NEW");
    }

    @Test
    @DisplayName("微信没轮换 refresh_token 时保留原值，不能刷成空")
    void keepsRefreshTokenWhenNotRotated() throws Exception {
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_2\",\"expires_in\":7200}");

        service.refresh(APPID);

        assertThat(authorizer.getRefreshTokenEnc()).isEqualTo("ENC:REFRESH_OLD");
        ArgumentCaptor<AuthorizerToken> row = ArgumentCaptor.forClass(AuthorizerToken.class);
        verify(tokenMapper).insert(row.capture());
        assertThat(row.getValue().getRefreshTokenEnc()).isEqualTo("ENC:REFRESH_OLD");
    }

    @Test
    @DisplayName("已有同一条 authorizer_token 记录时更新而不是插入")
    void updatesExistingTokenRow() throws Exception {
        AuthorizerToken existing = new AuthorizerToken();
        existing.setId(55L);
        existing.setAuthorizerAppid(APPID);
        when(tokenMapper.selectOne(any())).thenReturn(existing);
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_3\",\"expires_in\":7200}");

        service.refresh(APPID);

        assertThat(existing.getAccessToken()).isEqualTo("AT_3");
        verify(tokenMapper, never()).insert(any(AuthorizerToken.class));
        verify(tokenMapper).updateById(existing);
    }

    @Test
    @DisplayName("未返回 authorizer_access_token 时报错，不写坏缓存")
    void missingTokenFails() {
        ok("{\"errcode\":0,\"expires_in\":7200}");

        assertThatThrownBy(() -> service.refresh(APPID)).isInstanceOf(BizException.class)
                .hasMessageContaining("authorizer_access_token");

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // -------------------------------------------------- 失效重试

    @Test
    @DisplayName("令牌失效只重试一次，第二次用刷新出来的令牌")
    void retriesOnceWithFreshToken() throws Exception {
        when(values.get(TOKEN_KEY)).thenReturn(null);
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_FIRST\",\"expires_in\":7200,"
                + "\"authorizer_refresh_token\":\"REFRESH_NEW\"}");
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_SECOND\",\"expires_in\":7200}");

        AtomicInteger attempts = new AtomicInteger();
        String result = service.call(APPID, token -> {
            if (attempts.incrementAndGet() == 1) {
                throw new WechatApiException(42001, "access_token expired", "/wxa/get_page");
            }
            return token;
        });

        assertThat(result).isEqualTo("AT_SECOND");
        assertThat(nextBody().path("authorizer_refresh_token").asText()).isEqualTo("REFRESH_OLD");
        // 第二次刷新必须带上微信刚轮换出来的 refresh_token，否则整条授权链就断了
        assertThat(nextBody().path("authorizer_refresh_token").asText()).isEqualTo("REFRESH_NEW");
        assertThat(server.getRequestCount()).isEqualTo(2);
        verify(redis).delete(TOKEN_KEY);
    }

    @Test
    @DisplayName("重试后仍失效则标记 EXPIRED，提示重新授权")
    void marksExpiredAfterSecondFailure() {
        when(values.get(TOKEN_KEY)).thenReturn(null);
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_1\",\"expires_in\":7200}");
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_2\",\"expires_in\":7200}");

        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> service.call(APPID, token -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new WechatApiException(40001, "invalid credential", "/wxa/release");
            }
            return token;
        })).isInstanceOf(WechatApiException.class);

        assertThat(authorizer.getAuthState()).isEqualTo("EXPIRED");
        assertThat(authorizer.getRemark()).contains("40001").contains("需重新授权");
        verify(authorizerMapper, atLeastOnce()).updateById(authorizer);
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("缓存命中时不打微信")
    void usesCachedToken() {
        when(values.get(TOKEN_KEY)).thenReturn("AT_CACHED");

        assertThat(service.get(APPID)).isEqualTo("AT_CACHED");
        assertThat(server.getRequestCount()).isZero();
        verify(redis, never()).delete(anyString());
    }

    // -------------------------------------------------- 授权失效与前置校验

    @Test
    @DisplayName("61023 先强刷 component token 重试，仍然 61023 才判定授权失效")
    void componentTokenRotationRetries() throws Exception {
        ok("{\"errcode\":61023,\"errmsg\":\"invalid refresh_token\"}");
        ok("{\"errcode\":61023,\"errmsg\":\"invalid refresh_token\"}");

        assertThatThrownBy(() -> service.refresh(APPID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重新授权");

        verify(componentTokenService).forceRefresh();
        assertThat(nextBody().path("component_appid").asText()).isEqualTo(COMPONENT_APPID);
        assertThat(authorizer.getAuthState()).isEqualTo("EXPIRED");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("component token 自身过期导致的 40001，重试一次就能拿到新令牌")
    void retriesAfterComponentTokenRefresh() throws Exception {
        ok("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        ok("{\"errcode\":0,\"authorizer_access_token\":\"AT_OK\",\"expires_in\":7200}");

        assertThat(service.refresh(APPID).path("authorizer_access_token").asText()).isEqualTo("AT_OK");
        verify(componentTokenService, times(1)).forceRefresh();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("授权已解除时不发请求，直接提示重新扫码授权")
    void refusesWhenNotAuthorized() {
        authorizer.setAuthState("UNAUTHORIZED");
        authorizer.setRefreshTokenEnc(null);

        assertThatThrownBy(() -> service.refresh(APPID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请重新授权");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("refresh_token 解不出来时提示 master-key 变更")
    void hintsMasterKeyWhenDecryptFails() throws Exception {
        SecretCipher broken = mock(SecretCipher.class);
        when(broken.decrypt(any())).thenReturn("");
        WechatTokenStore store = mock(WechatTokenStore.class);
        AuthorizerTokenService local = new AuthorizerTokenService(redis, authorizerMapper, tokenMapper,
                mock(PlatformConfigService.class), componentTokenService, store, broken);

        assertThatThrownBy(() -> local.refresh(APPID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("platform.master-key");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("授权完成后写入 refresh_token 并顺手缓存微信附带的 access_token")
    void savesTokenFromAuthorization() {
        service.saveOnAuthorized(APPID, "REFRESH_FROM_AUTH", "AT_FROM_AUTH", 7200);

        assertThat(authorizer.getRefreshTokenEnc()).isEqualTo("ENC:REFRESH_FROM_AUTH");
        verify(authorizerMapper).updateById(authorizer);
        verify(values).set(eq(TOKEN_KEY), eq("AT_FROM_AUTH"), eq(Duration.ofSeconds(6600)));
    }

    @Test
    @DisplayName("小程序不存在时提示先完成扫码授权")
    void unknownAuthorizer() {
        when(authorizerMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.refresh("wxunknown"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未接入");
    }
}
