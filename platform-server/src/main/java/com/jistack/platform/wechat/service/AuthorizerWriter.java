package com.jistack.platform.wechat.service;

import com.jistack.platform.common.crypto.SecretCipher;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.authorizer.Authorizer;
import com.jistack.platform.module.authorizer.AuthorizerMapper;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.ComponentApi;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.token.ComponentTokenService;
import com.jistack.platform.wechat.token.WechatTokenStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 授权关系的写入端：把 api_query_auth / api_get_authorizer_info 的结果落到 wechat_authorizer。
 * refresh_token 加密存储，界面与接口都不回显明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizerWriter {

    private final PlatformConfigService configService;
    private final ComponentTokenService componentTokenService;
    private final WechatTokenStore store;
    private final AuthorizerMapper authorizerMapper;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final com.jistack.platform.wechat.token.AuthorizerTokenService authorizerTokenService;

    @Transactional
    public String exchangeAuthorizationCode(String authCode) {
        PlatformConfig config = configService.requireReady();
        WechatApiClient api = store.client(config);
        String componentToken = componentTokenService.get();
        JsonNode response;
        try {
            response = ComponentApi.queryAuth(api, componentToken, config.getComponentAppid(), authCode);
        } catch (com.jistack.platform.wechat.client.WechatApiException ex) {
            if (ex.getErrcode() == 40001 || ex.getErrcode() == 42001 || ex.getErrcode() == 61024) {
                response = ComponentApi.queryAuth(api, componentTokenService.forceRefresh(),
                        config.getComponentAppid(), authCode);
            } else {
                throw ex;
            }
        }
        JsonNode info = response.path("authorization_info");
        String appid = info.path("authorizer_appid").asText(null);
        String refreshToken = info.path("authorizer_refresh_token").asText(null);
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(refreshToken)) {
            throw new BizException(502, "api_query_auth 返回缺少 authorizer_appid/refresh_token: " + response);
        }
        upsertRefreshToken(config, appid, refreshToken, info.path("func_info").toString());
        String accessToken = info.path("authorizer_access_token").asText(null);
        long expiresIn = info.path("expires_in").asLong(7200);
        if (StringUtils.hasText(accessToken)) {
            // 授权后微信直接给了调用令牌，写入缓存避免首次操作再刷新一次
            authorizerTokenService.cache(appid, accessToken, expiresIn);
        }
        refreshInfo(appid);
        return appid;
    }

    /** 事件推送 authorized/updateauthorized 里只带 authorizer_appid + AuthorizationCode，走同样的换码逻辑。 */
    @Transactional
    public String applyAuthorizedEvent(String authorizerAppid, String authorizationCode) {
        if (StringUtils.hasText(authorizationCode)) {
            return exchangeAuthorizationCode(authorizationCode);
        }
        // 小程序后台直接切换授权（无 auth_code）时，只能靠 api_get_authorizer_list 恢复 refresh_token
        if (StringUtils.hasText(authorizerAppid)) {
            restoreFromAuthorizerList(authorizerAppid);
        }
        return authorizerAppid;
    }

    private void restoreFromAuthorizerList(String authorizerAppid) {
        PlatformConfig config = configService.requireReady();
        WechatApiClient api = store.client(config);
        String componentToken = componentTokenService.get();
        JsonNode response = ComponentApi.authorizerList(api, componentToken, config.getComponentAppid(), 0, 500);
        for (JsonNode item : response.path("list")) {
            if (authorizerAppid.equals(item.path("authorizer_appid").asText(null))) {
                upsertRefreshToken(config, authorizerAppid, item.path("refresh_token").asText(null), null);
                return;
            }
        }
        log.warn("在授权列表中未找到 appid={}，无法恢复 refresh_token", authorizerAppid);
    }

    /** api_get_authorizer_list 兜底导入（本地不存在该授权关系时）。 */
    @Transactional
    public Authorizer upsertFromWechat(String appid, String refreshToken, JsonNode ignored) {
        PlatformConfig config = configService.requireReady();
        if (!StringUtils.hasText(refreshToken)) {
            throw new BizException(502, "微信授权列表未返回 refresh_token，app=" + appid);
        }
        upsertRefreshToken(config, appid, refreshToken, null);
        return refreshInfo(appid);
    }

    private void upsertRefreshToken(PlatformConfig config, String appid, String refreshToken, String funcInfoJson) {
        Authorizer existing = find(appid);
        if (existing == null) {
            Authorizer entity = new Authorizer();
            applyRefreshToken(entity, config, appid, refreshToken, funcInfoJson);
            try {
                authorizerMapper.insert(entity);
                return;
            } catch (DuplicateKeyException ex) {
                // 微信在扫码授权后会同时推 authorized 事件、又跳转浏览器回调，两条链路并发对同一个
                // appid 做「查不到就插入」，唯一索引只放过一条。落后的那条必须转成更新：并发事务
                // 刚提交的行在本事务的快照读里看不到，得用 FOR UPDATE 走当前读。
                existing = findForUpdate(appid);
                if (existing == null) {
                    throw ex;
                }
                log.info("授权写入撞上唯一索引，改为更新已有记录 appid={}", appid);
            }
        }
        applyRefreshToken(existing, config, appid, refreshToken, funcInfoJson);
        authorizerMapper.updateById(existing);
    }

    private void applyRefreshToken(Authorizer entity, PlatformConfig config, String appid,
                                   String refreshToken, String funcInfoJson) {
        entity.setComponentAppid(config.getComponentAppid());
        entity.setAuthorizerAppid(appid);
        entity.setRefreshTokenEnc(cipher.encrypt(refreshToken));
        entity.setAuthState("AUTHORIZED");
        if (StringUtils.hasText(funcInfoJson)) {
            entity.setFuncInfoJson(funcInfoJson);
        }
        if (entity.getAuthorizedAt() == null) {
            entity.setAuthorizedAt(LocalDateTime.now());
        }
    }

    /** 昵称/主体/认证状态等展示信息只能从 api_get_authorizer_info 拿。 */
    @Transactional
    public Authorizer refreshInfo(String appid) {
        PlatformConfig config = configService.requireReady();
        WechatApiClient api = store.client(config);
        JsonNode response = ComponentApi.authorizerInfo(api, componentTokenService.get(),
                config.getComponentAppid(), appid);
        Authorizer authorizer = find(appid);
        if (authorizer == null) {
            throw BizException.notFound("小程序未接入: " + appid);
        }
        JsonNode info = response.path("authorizer_info");
        if (!info.isMissingNode()) {
            authorizer.setNickName(info.path("nick_name").asText(null));
            authorizer.setHeadImg(info.path("head_img").asText(null));
            authorizer.setQrcodeUrl(info.path("qrcode_url").asText(null));
            authorizer.setAlias(info.path("alias").asText(null));
            authorizer.setPrincipalName(info.path("principal_name").asText(null));
            authorizer.setOriginalUsername(info.path("user_name").asText(null));
            authorizer.setVerifyType(info.path("verify_type_info").path("id").asInt(-1));
            authorizer.setServiceType(info.path("service_type_info").path("id").asInt(0));
            if (info.hasNonNull("register_type")) {
                authorizer.setRegisterType(info.path("register_type").asInt());
            }
            authorizer.setVerifyInfo(truncate(info.path("signature").asText(null), 500));
            JsonNode mini = info.path("MiniProgramInfo");
            if (!mini.isMissingNode()) {
                authorizer.setReleaseVersion(mini.path("current_version").asText(null));
                authorizer.setRemark(mergeRemark(authorizer.getRemark(), mini.toString()));
            }
        }
        JsonNode authorization = response.path("authorization_info");
        if (authorization.hasNonNull("func_info")) {
            authorizer.setFuncInfoJson(authorization.path("func_info").toString());
        }
        authorizer.setAuthState("AUTHORIZED");
        authorizerMapper.updateById(authorizer);
        return authorizer;
    }

    /** 小程序管理员在微信侧取消授权：令牌立即失效，清缓存并标记状态，等待重新授权。 */
    @Transactional
    public void markUnauthorized(String authorizerAppid) {
        Authorizer authorizer = find(authorizerAppid);
        if (authorizer == null) {
            return;
        }
        authorizer.setAuthState("UNAUTHORIZED");
        authorizer.setTokenExpiredAt(null);
        authorizerMapper.updateById(authorizer);
        authorizerTokenService.invalidate(authorizerAppid);
    }

    public Authorizer find(String appid) {
        return authorizerMapper.selectOne(new LambdaQueryWrapper<Authorizer>()
                .eq(Authorizer::getAuthorizerAppid, appid));
    }

    /** 当前读并锁行：只在并发写入撞唯一索引后用一次，普通读取别走这里。 */
    private Authorizer findForUpdate(String appid) {
        return authorizerMapper.selectOne(new LambdaQueryWrapper<Authorizer>()
                .eq(Authorizer::getAuthorizerAppid, appid)
                .last("FOR UPDATE"));
    }

    public Authorizer require(String appid) {
        Authorizer authorizer = find(appid);
        if (authorizer == null) {
            throw BizException.notFound("小程序 " + appid + " 尚未接入第三方平台，请先完成扫码授权");
        }
        if (!"AUTHORIZED".equals(authorizer.getAuthState())) {
            throw new BizException(409, "小程序 " + appid + " 当前授权状态为 " + authorizer.getAuthState()
                    + "，无法代其开发或发布");
        }
        return authorizer;
    }

    /** 权限集 id 列表（逗号分隔），界面据此提示缺少哪个权限集（缺 `18` 时发布链路全报 48001）。 */
    public String describeFuncScopes(String funcInfoJson) {
        if (!StringUtils.hasText(funcInfoJson)) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonNode node : objectMapper.readTree(funcInfoJson)) {
                sb.append(node.path("funcscope_category").path("id").asText("")).append(',');
            }
            return sb.toString();
        } catch (Exception ex) {
            return funcInfoJson;
        }
    }

    private static String mergeRemark(String existing, String miniProgramInfo) {
        String marker = "miniprogram_info=";
        int index = existing == null ? -1 : existing.indexOf(marker);
        String prefix = index > 0 ? existing.substring(0, index).trim() : "";
        return truncate((prefix.isEmpty() ? "" : prefix + " ") + marker + miniProgramInfo, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
