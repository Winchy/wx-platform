package com.jistack.platform.wechat.token;

import com.jistack.platform.common.crypto.SecretCipher;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.authorizer.Authorizer;
import com.jistack.platform.module.authorizer.AuthorizerMapper;
import com.jistack.platform.module.authorizer.AuthorizerToken;
import com.jistack.platform.module.authorizer.AuthorizerTokenMapper;
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
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * authorizer_access_token：Redis 缓存 → 持久表恢复 → 用 refresh_token 现取，三级兜底。
 * 微信会轮换 refresh_token，每次刷新都必须回写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizerTokenService {

    private static final String TOKEN_KEY = "wx:open:authorizer-token:";

    private final StringRedisTemplate redis;
    private final AuthorizerMapper authorizerMapper;
    private final AuthorizerTokenMapper tokenMapper;
    private final PlatformConfigService configService;
    private final ComponentTokenService componentTokenService;
    private final WechatTokenStore store;
    private final SecretCipher cipher;

    public String get(String authorizerAppid) {
        String cached = redis.opsForValue().get(TOKEN_KEY + authorizerAppid);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        return refreshValue(authorizerAppid);
    }

    public void invalidate(String authorizerAppid) {
        redis.delete(TOKEN_KEY + authorizerAppid);
    }

    /** api_query_auth 会顺带返回可用的 authorizer_access_token，直接缓存避免首次操作再刷一次。 */
    public void cache(String authorizerAppid, String accessToken, long expiresIn) {
        if (!StringUtils.hasText(accessToken)) {
            return;
        }
        redis.opsForValue().set(TOKEN_KEY + authorizerAppid, accessToken,
                Duration.ofSeconds(Math.max(120, expiresIn - 600)));
    }

    /**
     * 用 authorizer token 调用微信接口，遇到令牌失效自动刷新并重试一次；
     * component token 失效同样只重试一次，避免循环。
     */
    public <T> T call(String authorizerAppid, Function<String, T> action) {
        try {
            return action.apply(get(authorizerAppid));
        } catch (WechatApiException ex) {
            if (!ex.isTokenInvalid()) {
                throw ex;
            }
            log.info("authorizer token 失效(errcode={})，刷新后重试 appid={}", ex.getErrcode(), authorizerAppid);
            invalidate(authorizerAppid);
            try {
                return action.apply(refreshValue(authorizerAppid));
            } catch (WechatApiException retry) {
                if (retry.isTokenInvalid()) {
                    markExpired(authorizerAppid, retry);
                }
                throw retry;
            }
        }
    }

    private void markExpired(String authorizerAppid, WechatApiException ex) {
        Authorizer authorizer = find(authorizerAppid);
        if (authorizer != null) {
            authorizer.setAuthState("EXPIRED");
            authorizer.setRemark("令牌刷新失败 " + ex.getErrcode() + "，需重新授权");
            authorizerMapper.updateById(authorizer);
        }
    }

    /** refresh() 返回完整响应便于接口展示，内部只需要令牌本身。 */
    private String refreshValue(String authorizerAppid) {
        return refresh(authorizerAppid).path("authorizer_access_token").asText();
    }

    public JsonNode refresh(String authorizerAppid) {
        Authorizer authorizer = find(authorizerAppid);
        if (authorizer == null) {
            throw BizException.notFound("小程序 " + authorizerAppid + " 尚未接入，请先完成扫码授权");
        }
        if (!"AUTHORIZED".equals(authorizer.getAuthState()) && !StringUtils.hasText(authorizer.getRefreshTokenEnc())) {
            throw new BizException(409, "该小程序授权状态为 " + authorizer.getAuthState() + "，请重新授权");
        }
        String refreshToken = cipher.decrypt(authorizer.getRefreshTokenEnc());
        if (!StringUtils.hasText(refreshToken)) {
            throw new BizException(500, "refresh_token 无法解密，请确认 platform.master-key 未变更");
        }
        PlatformConfig config = configService.requireReady();
        String componentToken = componentTokenService.get();
        WechatApiClient api = store.client(config);
        JsonNode response;
        try {
            response = ComponentApi.authorizerToken(api, componentToken, config.getComponentAppid(),
                    authorizerAppid, refreshToken);
        } catch (WechatApiException ex) {
            if (ex.getErrcode() == 61023 || ex.getErrcode() == 61024 || ex.getErrcode() == 40001) {
                // component token 自身过期也会导致这类报错，先强刷 component token 再试一次
                String retried = componentTokenService.forceRefresh();
                try {
                    response = ComponentApi.authorizerToken(api, retried, config.getComponentAppid(),
                            authorizerAppid, refreshToken);
                } catch (WechatApiException second) {
                    if (second.getErrcode() == 61023 || second.getErrcode() == 61024) {
                        markExpired(authorizerAppid, second);
                        throw new BizException(second.getErrcode(),
                                "授权已失效，需要小程序管理员重新授权：" + second.getErrmsg());
                    }
                    throw second;
                }
            } else {
                throw ex;
            }
        }
        String token = response.path("authorizer_access_token").asText(null);
        if (!StringUtils.hasText(token)) {
            throw new BizException(502, "微信未返回 authorizer_access_token: " + response);
        }
        long expiresIn = response.path("expires_in").asLong(7200);
        String rotatedRefresh = response.path("authorizer_refresh_token").asText(null);
        persist(authorizer, token, rotatedRefresh, expiresIn);
        return response;
    }

    private void persist(Authorizer authorizer, String token, String rotatedRefresh, long expiresIn) {
        long usable = Math.max(300, expiresIn - 600);
        redis.opsForValue().set(TOKEN_KEY + authorizer.getAuthorizerAppid(), token, Duration.ofSeconds(usable));
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(usable);

        AuthorizerToken row = tokenMapper.selectOne(new LambdaQueryWrapper<AuthorizerToken>()
                .eq(AuthorizerToken::getAuthorizerAppid, authorizer.getAuthorizerAppid()));
        if (row == null) {
            row = new AuthorizerToken();
            row.setAuthorizerAppid(authorizer.getAuthorizerAppid());
        }
        row.setAccessToken(token);
        row.setExpiresAt(expiresAt);
        // refresh_token_enc 列存密文，微信轮换时也不能把明文写进去
        String refreshToStore = StringUtils.hasText(rotatedRefresh)
                ? cipher.encrypt(rotatedRefresh) : authorizer.getRefreshTokenEnc();
        row.setRefreshTokenEnc(refreshToStore);
        if (row.getId() == null) {
            tokenMapper.insert(row);
        } else {
            tokenMapper.updateById(row);
        }

        if (StringUtils.hasText(rotatedRefresh) && !rotatedRefresh.equals(cipher.decrypt(authorizer.getRefreshTokenEnc()))) {
            authorizer.setRefreshTokenEnc(cipher.encrypt(rotatedRefresh));
        }
        authorizer.setTokenExpiredAt(expiresAt);
        authorizer.setAuthState("AUTHORIZED");
        authorizerMapper.updateById(authorizer);
    }

    /** 授权完成后写入新的 refresh_token（此时还没有 access_token 缓存）。 */
    public void saveOnAuthorized(String authorizerAppid, String refreshTokenPlain, String accessToken, long expiresIn) {
        String enc = cipher.encrypt(refreshTokenPlain);
        Authorizer authorizer = find(authorizerAppid);
        if (authorizer != null) {
            authorizer.setRefreshTokenEnc(enc);
            authorizerMapper.updateById(authorizer);
        }
        if (StringUtils.hasText(accessToken)) {
            long usable = Math.max(300, expiresIn - 600);
            redis.opsForValue().set(TOKEN_KEY + authorizerAppid, accessToken, Duration.ofSeconds(usable));
        }
    }

    public Authorizer find(String authorizerAppid) {
        return authorizerMapper.selectOne(new LambdaQueryWrapper<Authorizer>()
                .eq(Authorizer::getAuthorizerAppid, authorizerAppid));
    }
}
