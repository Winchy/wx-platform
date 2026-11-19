package com.jistack.platform.wechat.service;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.client.MiniProgramApi;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.client.WechatApiException;
import com.jistack.platform.wechat.client.WechatClientFactory;
import com.jistack.platform.wechat.token.AuthorizerTokenService;
import com.jistack.platform.wechat.token.ComponentTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 所有微信接口调用的统一入口：取令牌、失败重试、写操作审计日志。
 * 业务层不再关心 access_token 从哪来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatCaller {

    private final PlatformConfigService configService;
    private final WechatClientFactory clientFactory;
    private final AuthorizerTokenService authorizerTokenService;
    private final ComponentTokenService componentTokenService;
    private final com.jistack.platform.module.log.OperationLogService operationLogService;

    public WechatApiClient api() {
        PlatformConfig config = configService.load();
        return clientFactory.forConfig(config);
    }

    /** 以授权方令牌调用，令牌失效自动刷新重试一次。 */
    public com.fasterxml.jackson.databind.JsonNode authorizer(String authorizerAppid, String action, AuthCall call) {
        WechatApiClient api = api();
        long start = System.currentTimeMillis();
        try {
            var node = authorizerTokenService.call(authorizerAppid, token -> call.apply(api, token));
            record(action, authorizerAppid, node, true, 0, null, start);
            return node;
        } catch (WechatApiException ex) {
            record(action, authorizerAppid, null, false, ex.getErrcode(), ex.getErrmsg(), start);
            throw ex;
        } catch (RuntimeException ex) {
            record(action, authorizerAppid, null, false, null, ex.getMessage(), start);
            throw ex;
        }
    }

    public byte[] authorizerBinary(String authorizerAppid, String action, BinaryCall call) {
        WechatApiClient api = api();
        long start = System.currentTimeMillis();
        try {
            byte[] bytes = authorizerTokenService.call(authorizerAppid, token -> call.apply(api, token));
            operationLogService.record(action, authorizerAppid, MiniProgramApi.pathOf(action), null,
                    "binary " + (bytes == null ? 0 : bytes.length) + "B", true, 0, null, cost(start));
            return bytes;
        } catch (WechatApiException ex) {
            operationLogService.record(action, authorizerAppid, MiniProgramApi.pathOf(action), null, null,
                    false, ex.getErrcode(), ex.getErrmsg(), cost(start));
            throw ex;
        }
    }

    /** 以第三方平台令牌调用（模板库、授权管理等）。 */
    public com.fasterxml.jackson.databind.JsonNode component(String action, ComponentCall call) {
        WechatApiClient api = api();
        String componentAppid = configService.load().getComponentAppid();
        long start = System.currentTimeMillis();
        try {
            var node = call.apply(api, componentTokenService.get());
            record(action, componentAppid, node, true, 0, null, start);
            return node;
        } catch (WechatApiException ex) {
            if (ex.isTokenInvalid()) {
                try {
                    var retry = call.apply(api, componentTokenService.forceRefresh());
                    record(action, componentAppid, retry, true, 0, null, start);
                    return retry;
                } catch (WechatApiException second) {
                    record(action, componentAppid, null, false, second.getErrcode(), second.getErrmsg(), start);
                    throw second;
                }
            }
            record(action, componentAppid, null, false, ex.getErrcode(), ex.getErrmsg(), start);
            throw ex;
        }
    }

    /**
     * 平台级调用，但**不预先取 component_access_token**：
     * api_start_push_ticket、api_component_token 这类凭据放在 body 里，
     * ticket 断流时若先取 token 会直接被自己的 409 卡住，恢复不了。
     */
    public com.fasterxml.jackson.databind.JsonNode platform(String action, ApiCall call) {
        PlatformConfig config = configService.load();
        WechatApiClient api = clientFactory.forConfig(config);
        long start = System.currentTimeMillis();
        try {
            var node = call.apply(api);
            record(action, config.getComponentAppid(), node, true, 0, null, start);
            return node;
        } catch (WechatApiException ex) {
            record(action, config.getComponentAppid(), null, false, ex.getErrcode(), ex.getErrmsg(), start);
            throw ex;
        } catch (RuntimeException ex) {
            record(action, config.getComponentAppid(), null, false, null, ex.getMessage(), start);
            throw ex;
        }
    }

    private void record(String action, String appid, com.fasterxml.jackson.databind.JsonNode node,
                        boolean success, Integer errcode, String errmsg, long start) {
        operationLogService.record(action, appid, MiniProgramApi.pathOf(action), null,
                brief(node), success, errcode, errmsg, cost(start));
    }

    private int cost(long start) {
        return (int) (System.currentTimeMillis() - start);
    }

    private String brief(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }
        String text = node.toString();
        return text.length() > 1500 ? text.substring(0, 1500) : text;
    }

    @FunctionalInterface
    public interface AuthCall {
        com.fasterxml.jackson.databind.JsonNode apply(WechatApiClient api, String authorizerToken);
    }

    @FunctionalInterface
    public interface BinaryCall {
        byte[] apply(WechatApiClient api, String authorizerToken);
    }

    @FunctionalInterface
    public interface ComponentCall {
        com.fasterxml.jackson.databind.JsonNode apply(WechatApiClient api, String componentToken);
    }

    @FunctionalInterface
    public interface ApiCall {
        com.fasterxml.jackson.databind.JsonNode apply(WechatApiClient api);
    }
}
