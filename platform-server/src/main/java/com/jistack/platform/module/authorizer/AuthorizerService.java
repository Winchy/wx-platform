package com.jistack.platform.module.authorizer;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.common.web.PageResult;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.MiniProgramApi;
import com.jistack.platform.wechat.client.WechatApiException;
import com.jistack.platform.wechat.service.AuthorizerWriter;
import com.jistack.platform.wechat.service.AuthorizationService;
import com.jistack.platform.wechat.token.AuthorizerTokenService;
import com.jistack.platform.wechat.token.ComponentTokenService;
import com.jistack.platform.wechat.service.WechatCaller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已接入小程序（授权方）的查询与维护。列表数据以本地库为准，展示字段在需要时从微信回源刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizerService {

    private final AuthorizerMapper authorizerMapper;
    private final AuthorizerWriter authorizerWriter;
    private final AuthorizationService authorizationService;
    private final AuthorizerTokenService authorizerTokenService;
    private final ComponentTokenService componentTokenService;
    private final PlatformConfigService configService;
    private final WechatCaller caller;

    public PageResult<Map<String, Object>> page(String keyword, String authState, long page, long size) {
        LambdaQueryWrapper<Authorizer> wrapper = new LambdaQueryWrapper<Authorizer>()
                .eq(StringUtils.hasText(authState), Authorizer::getAuthState, authState)
                .orderByDesc(Authorizer::getId);
        if (StringUtils.hasText(keyword)) {
            String like = keyword.trim();
            wrapper.and(w -> w.like(Authorizer::getNickName, like)
                    .or().like(Authorizer::getAuthorizerAppid, like)
                    .or().like(Authorizer::getPrincipalName, like)
                    .or().like(Authorizer::getAlias, like));
        }
        Page<Authorizer> result = authorizerMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageResult<>(result.getRecords().stream().map(AuthorizerService::toVO).toList(),
                result.getTotal(), page, size);
    }

    /** 详情只读本地记录：取消授权后仍要能看到状态并入口重新授权，409 只保留给开发/发布操作。 */
    public Map<String, Object> detail(String appid) {
        Authorizer authorizer = authorizerWriter.find(appid);
        if (authorizer == null) {
            throw BizException.notFound("小程序 " + appid + " 尚未接入第三方平台，请先完成扫码授权");
        }
        Map<String, Object> vo = toVO(authorizer);
        vo.put("funcScopes", authorizerWriter.describeFuncScopes(authorizer.getFuncInfoJson()));
        vo.put("experienceQrcodeUrl", "/api/authorizers/" + appid + "/experience-qrcode");
        return vo;
    }

    /** 从微信回源刷新昵称/主体/认证状态等展示字段。 */
    public Map<String, Object> refreshFromWechat(String appid) {
        Authorizer authorizer = authorizerWriter.refreshInfo(appid);
        return toVO(authorizer);
    }

    /** 体验版与线上版信息，直接来自 wxa/getversioninfo。 */
    public Map<String, Object> versionInfo(String appid) {
        JsonNode node = caller.authorizer(appid, "getversioninfo", MiniProgramApi::versionInfo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expInfo", node.path("exp_info"));
        result.put("releaseInfo", node.path("release_info"));
        return result;
    }

    public Map<String, Object> refreshToken(String appid) {
        JsonNode node = authorizerTokenService.refresh(appid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expiresIn", node.path("expires_in").asLong(0));
        result.put("tokenRefreshed", true);
        return result;
    }

    public Map<String, Object> createAuthSession(String bizAppid, List<Integer> funcScopeIds) {
        return authorizationService.createAuthSession(bizAppid, funcScopeIds);
    }

    public Map<String, Object> authSessionStatus(String state) {
        return authorizationService.sessionStatus(state);
    }

    public int syncFromWechat() {
        return authorizationService.syncAuthorizerList();
    }

    public void revoke(String appid) {
        authorizationService.revokeLocal(appid);
        authorizerTokenService.invalidate(appid);
    }

    /** 概览页健康信息。 */
    public Map<String, Object> health() {
        PlatformConfig config = configService.load();
        boolean ready = configService.isReady(config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configReady", ready && config.getStatus() != null && config.getStatus() == 1);
        result.put("authorizerTotal", authorizerMapper.selectCount(null));
        result.put("authorizedCount", authorizerMapper.selectCount(new LambdaQueryWrapper<Authorizer>()
                .eq(Authorizer::getAuthState, "AUTHORIZED")));
        result.put("ticketAgeSeconds", config.getComponentAppid() == null ? -1
                : componentTokenService.ticketAgeSeconds(config.getComponentAppid()));
        result.put("ticketFresh", config.getComponentAppid() != null
                && componentTokenService.ticketFresh(config.getComponentAppid()));
        result.put("componentTokenValid", componentTokenService.nextExpiry() != null);
        result.put("tokenExpiresAt", componentTokenService.nextExpiry());
        if (ready && StringUtils.hasText(config.getComponentAppid())) {
            try {
                componentTokenService.get();
                result.put("componentTokenCheck", "ok");
            } catch (RuntimeException ex) {
                result.put("componentTokenCheck", ex.getMessage());
            }
        }
        return result;
    }

    /** 校验连通性：真正取一次 component_access_token，并把微信错误原样返回。 */
    public Map<String, Object> checkConnectivity() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String token = componentTokenService.forceRefresh();
            result.put("ok", true);
            result.put("expiresIn", 7200);
            result.put("ticketAgeSeconds", ticketAge());
            log.info("component_access_token 获取成功，长度={}", token.length());
        } catch (WechatApiException ex) {
            result.put("ok", false);
            result.put("errcode", ex.getErrcode());
            result.put("errmsg", ex.getErrmsg());
        } catch (RuntimeException ex) {
            result.put("ok", false);
            result.put("errcode", -1);
            result.put("errmsg", ex.getMessage());
        }
        return result;
    }

    /** ticket 长时间没推送时可主动请求微信重新开始推送（该接口凭据在 body，不需要 component token）。 */
    public Map<String, Object> restartTicketPush() {
        PlatformConfig config = configService.requireReady();
        JsonNode node = caller.platform("api_start_push_ticket",
                api -> com.jistack.platform.wechat.client.ComponentApi
                        .startPushTicket(api, config.getComponentAppid(), config.getComponentSecret()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errcode", node.path("errcode").asInt(0));
        result.put("errmsg", node.path("errmsg").asText("ok"));
        result.put("ticketAgeSeconds", ticketAge());
        return result;
    }

    private long ticketAge() {
        PlatformConfig config = configService.load();
        return config.getComponentAppid() == null ? -1
                : componentTokenService.ticketAgeSeconds(config.getComponentAppid());
    }

    public static Map<String, Object> toVO(Authorizer entity) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("appid", entity.getAuthorizerAppid());
        vo.put("nickName", entity.getNickName());
        vo.put("headImg", entity.getHeadImg());
        vo.put("alias", entity.getAlias());
        vo.put("principalName", entity.getPrincipalName());
        vo.put("originalUsername", entity.getOriginalUsername());
        vo.put("verifyType", entity.getVerifyType());
        vo.put("serviceType", entity.getServiceType());
        vo.put("authState", entity.getAuthState());
        vo.put("authorizedAt", entity.getAuthorizedAt());
        vo.put("tokenExpiredAt", entity.getTokenExpiredAt());
        vo.put("releaseVersion", entity.getReleaseVersion());
        vo.put("remark", entity.getRemark());
        return vo;
    }
}
