package com.jistack.platform.wechat.service;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.authorizer.Authorizer;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.ComponentApi;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.client.WechatApiException;
import com.jistack.platform.wechat.token.ComponentTokenService;
import com.jistack.platform.wechat.token.WechatTokenStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 授权流程：预授权码 → 扫码链接 → 回调 auth_code → api_query_auth 落库 refresh_token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final PlatformConfigService configService;
    private final ComponentTokenService componentTokenService;
    private final WechatTokenStore store;
    private final AuthorizerWriter authorizerWriter;
    private final com.jistack.platform.module.authorizer.AuthSessionMapper sessionMapper;
    private final com.jistack.platform.module.authorizer.AuthorizerMapper authorizerMapper;

    /** 生成一次扫码授权会话，返回给前端渲染二维码的链接。 */
    @Transactional
    public Map<String, Object> createAuthSession(String bizAppid, List<Integer> funcScopeIds) {
        PlatformConfig config = configService.requireReady();
        WechatApiClient api = store.client(config);
        String componentToken = componentTokenService.get();
        JsonNode response = ComponentApi.createPreAuthCode(api, componentToken, config.getComponentAppid());
        String preAuthCode = response.path("pre_auth_code").asText(null);
        if (!StringUtils.hasText(preAuthCode)) {
            throw new BizException(502, "未获取到 pre_auth_code: " + response);
        }
        long expiresIn = response.path("expires_in").asLong(600);
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(config);
        String categoryIdList = categoryIdList(funcScopeIds);

        var session = new com.jistack.platform.module.authorizer.AuthSession();
        session.setState(state);
        session.setScene("AUTHORIZE");
        session.setPreAuthCode(preAuthCode);
        session.setPreAuthExpiredAt(LocalDateTime.now().plusSeconds(expiresIn));
        session.setStatus("WAITING");
        var principal = com.jistack.platform.module.system.RequestContext.get();
        if (principal != null) {
            session.setCreatedBy(principal.id());
        }
        sessionMapper.insert(session);

        String biz = StringUtils.hasText(bizAppid) ? bizAppid : null;
        String authUrl = ComponentApi.authUrl(config.getComponentAppid(), preAuthCode, redirect,
                ComponentApi.AUTH_TYPE_ALL, biz, categoryIdList);
        return Map.of(
                "state", state,
                "authUrl", authUrl,
                "mobileAuthUrl", ComponentApi.mobileAuthUrl(config.getComponentAppid(), preAuthCode, redirect,
                        ComponentApi.AUTH_TYPE_ALL, biz, categoryIdList),
                "expiresAt", session.getPreAuthExpiredAt(),
                "redirectUri", redirect);
    }

    /**
     * 微信的 category_id_list 是中竖线分隔的权限集 id；留空表示「当前第三方平台已全网发布的全部权限集」。
     * 一旦指定，管理员就只能授权列出的这几个，所以这里不补任何默认值。
     */
    private static String categoryIdList(List<Integer> funcScopeIds) {
        if (funcScopeIds == null || funcScopeIds.isEmpty()) {
            return null;
        }
        List<Integer> ids = new ArrayList<>();
        for (Integer id : funcScopeIds) {
            if (id == null || id <= 0) {
                throw new BizException(400, "权限集 id 必须是正整数：" + funcScopeIds);
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        StringBuilder joined = new StringBuilder();
        for (Integer id : ids) {
            if (!joined.isEmpty()) {
                joined.append('|');
            }
            joined.append(id);
        }
        return joined.toString();
    }

    private String redirectUri(PlatformConfig config) {
        String base = StringUtils.hasText(config.getSiteBase()) ? config.getSiteBase()
                : "";
        if (base.isBlank()) {
            throw new BizException(409, "生成授权链接需要先在「平台配置」填写本站对外基地址（site_base，须为外网 https 域名）");
        }
        return base.replaceAll("/+$", "") + "/wx/auth/callback";
    }

    /** 浏览器回调：带 auth_code 与 state，换取授权信息并落库。 */
    @Transactional
    public String confirmByAuthCode(String authCode, String state) {
        if (!StringUtils.hasText(authCode)) {
            throw new BizException(400, "缺少 auth_code");
        }
        var session = state == null ? null : sessionMapper.selectOne(new LambdaQueryWrapper<
                com.jistack.platform.module.authorizer.AuthSession>()
                .eq(com.jistack.platform.module.authorizer.AuthSession::getState, state));
        try {
            String appid = authorizerWriter.exchangeAuthorizationCode(authCode);
            if (session != null) {
                session.setStatus("CONFIRMED");
                session.setResultAppid(appid);
                session.setAuthCode(authCode);
                sessionMapper.updateById(session);
            }
            return appid;
        } catch (RuntimeException ex) {
            if (session != null) {
                session.setStatus("FAILED");
                session.setErrorMessage(truncate(ex.getMessage()));
                sessionMapper.updateById(session);
            }
            throw ex;
        }
    }

    /** 前端轮询扫码结果。 */
    public Map<String, Object> sessionStatus(String state) {
        var session = sessionMapper.selectOne(new LambdaQueryWrapper<
                com.jistack.platform.module.authorizer.AuthSession>()
                .eq(com.jistack.platform.module.authorizer.AuthSession::getState, state));
        if (session == null) {
            throw BizException.notFound("授权会话不存在或已过期");
        }
        String status = session.getStatus();
        if ("WAITING".equals(status) && session.getPreAuthExpiredAt() != null
                && session.getPreAuthExpiredAt().isBefore(LocalDateTime.now())) {
            status = "EXPIRED";
        }
        return Map.of("state", session.getState(), "status", status,
                "resultAppid", session.getResultAppid() == null ? "" : session.getResultAppid(),
                "errorMessage", session.getErrorMessage() == null ? "" : session.getErrorMessage());
    }

    /** 主动从微信拉取全部授权账号，用列表里的 refresh_token 修复本地缺失的授权关系。 */
    @Transactional
    public int syncAuthorizerList() {
        PlatformConfig config = configService.requireReady();
        WechatApiClient api = store.client(config);
        String componentToken = componentTokenService.get();
        int total = -1;
        int offset = 0;
        int imported = 0;
        do {
            JsonNode response = ComponentApi.authorizerList(api, componentToken, config.getComponentAppid(), offset, 100);
            total = response.path("total_count").asInt(0);
            for (JsonNode item : response.path("list")) {
                String appid = item.path("authorizer_appid").asText(null);
                String refreshToken = item.path("refresh_token").asText(null);
                if (!StringUtils.hasText(appid)) {
                    continue;
                }
                Authorizer existing = authorizerMapper.selectOne(new LambdaQueryWrapper<Authorizer>()
                        .eq(Authorizer::getAuthorizerAppid, appid));
                if (existing == null) {
                    authorizerWriter.upsertFromWechat(appid, refreshToken, null);
                    imported++;
                }
                offset++;
            }
        } while (offset < total && offset < 1000);
        return imported;
    }

    /** 解除本地授权关系（微信侧授权由小程序管理员在小程序后台取消，这里只做状态标记与令牌清理）。 */
    @Transactional
    public void revokeLocal(String authorizerAppid) {
        Authorizer authorizer = authorizerMapper.selectOne(new LambdaQueryWrapper<Authorizer>()
                .eq(Authorizer::getAuthorizerAppid, authorizerAppid));
        if (authorizer == null) {
            throw BizException.notFound("小程序未接入: " + authorizerAppid);
        }
        authorizer.setAuthState("UNAUTHORIZED");
        authorizer.setRemark("平台侧于 " + LocalDateTime.now() + " 标记为已解除授权");
        authorizerMapper.updateById(authorizer);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
