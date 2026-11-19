package com.jistack.platform.wechat.service;

import com.jistack.platform.module.event.EventLog;
import com.jistack.platform.module.event.EventLogMapper;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigMapper;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.crypto.WeChatMsgCrypt;
import com.jistack.platform.wechat.crypto.XmlCodec;
import com.jistack.platform.wechat.token.ComponentTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微信安全模式回调：授权事件推送 + 小程序消息/事件代收。
 *
 * <p>第三方平台强制 XML + 安全模式，因此所有包体都要先按 msg_signature 校验再解密；
 * 处理完成后一律返回纯文本 success，否则会触发微信重试（重试会重复推送同一 ticket）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatCallbackService {

    private static final String SUCCESS = "success";

    private final PlatformConfigService configService;
    private final PlatformConfigMapper configMapper;
    private final ComponentTokenService componentTokenService;
    private final com.jistack.platform.wechat.token.WechatTokenStore tokenStore;
    private final AuthorizerWriter authorizerWriter;
    private final EventLogMapper eventLogMapper;

    /** 配置回调 URL 时微信发起的 GET 校验，需原样返回解密后的 echostr。 */
    public String verifyUrl(String signature, String timestamp, String nonce, String echoStr) {
        WeChatMsgCrypt crypt = configService.crypt();
        if (!crypt.verifyUrl(timestamp, nonce, echoStr, signature)) {
            throw new IllegalStateException("URL 签名校验失败，请检查消息校验 Token 是否与开放平台一致");
        }
        // 校验阶段微信把 echostr 也放在 query 里并且已加密
        if (echoStr == null) {
            throw new IllegalStateException("缺少 echostr");
        }
        if (looksLikeBase64Cipher(echoStr)) {
            return crypt.decrypt(echoStr, List.of(crypt.expectedAppId()));
        }
        return echoStr;
    }

    public String handlePush(String msgSignature, String timestamp, String nonce, String body) {
        EventLog entry = new EventLog();
        entry.setRawBody(truncate(body, 8000));
        entry.setBodyType(body != null && body.trim().startsWith("<") ? "XML" : "JSON");
        entry.setReceivedAt(LocalDateTime.now());
        entry.setSignatureOk(0);
        entry.setHandled(0);
        try {
            WeChatMsgCrypt crypt = configService.crypt();
            Map<String, String> outer = XmlCodec.parseFlat(body);
            String encrypted = outer.getOrDefault("Encrypt", outer.get("encrypt"));
            String toUserName = outer.getOrDefault("ToUserName", outer.get("tousername"));
            if (!StringUtils.hasText(encrypted)) {
                throw new IllegalStateException("包体中没有 Encrypt 字段，可能未开启安全模式");
            }
            boolean verified = crypt.verify(timestamp, nonce, encrypted, msgSignature);
            entry.setSignatureOk(verified ? 1 : 0);
            if (!verified) {
                entry.setErrorMessage("msg_signature 校验失败");
                log.warn("回调签名校验失败，已忽略 timestamp={} nonce={}", timestamp, nonce);
                return SUCCESS;
            }
            List<String> accepted = new ArrayList<>();
            accepted.add(crypt.expectedAppId());
            if (StringUtils.hasText(toUserName)) {
                accepted.add(toUserName);
            }
            String plain = crypt.decrypt(encrypted, accepted);
            entry.setDecryptedBody(truncate(plain, 8000));
            Map<String, String> event = XmlCodec.parseFlat(plain);
            String infoType = event.getOrDefault("InfoType", event.get("MsgType"));
            entry.setInfoType(infoType);
            String componentAppid = firstNonNull(event.get("AppId"), toUserName);
            entry.setAppid(componentAppid);
            dispatch(infoType, event, componentAppid);
            entry.setHandled(1);
            return SUCCESS;
        } catch (Exception ex) {
            entry.setErrorMessage(truncate(ex.getMessage(), 500));
            log.error("处理微信回调失败: {}", ex.toString());
            // 仍返回 success：解析不了的报文重试也无意义，反而刷爆日志
            return SUCCESS;
        } finally {
            try {
                eventLogMapper.insert(entry);
            } catch (Exception ex) {
                log.warn("事件日志落库失败: {}", ex.toString());
            }
        }
    }

    private void dispatch(String infoType, Map<String, String> event, String componentAppid) {
        switch (infoType == null ? "" : infoType) {
            case "component_verify_ticket" -> {
                String ticket = event.get("ComponentVerifyTicket");
                if (!StringUtils.hasText(ticket)) {
                    throw new IllegalStateException("票据推送缺少 ComponentVerifyTicket 字段");
                }
                tokenStore.saveTicket(componentAppid, ticket);
                componentTokenService.onTicketReceived(componentAppid, ticket);
                markTicketReceived();
                log.info("已更新 component_verify_ticket appid={}", componentAppid);
            }
            case "authorized", "updateauthorized" -> {
                String appid = authorizerWriter.applyAuthorizedEvent(event.get("AuthorizerAppid"),
                        event.get("AuthorizationCode"));
                log.info("授权关系已更新 infoType={} appid={}", infoType, appid);
            }
            case "unauthorized" -> {
                String appid = event.get("AuthorizerAppid");
                log.info("收到解除授权事件 appid={}，标记为 UNAUTHORIZED", appid);
                if (StringUtils.hasText(appid)) {
                    authorizerWriter.markUnauthorized(appid);
                }
            }
            default -> {
                // 小程序消息与事件（客服消息、用户反馈、认证/违规通知等）已在事件表留档，由具体业务按需消费
                log.debug("未处理的信息类型 infoType={}", infoType);
            }
        }
    }

    private void markTicketReceived() {
        PlatformConfig config = configMapper.selectById(PlatformConfig.SINGLETON_ID);
        if (config != null) {
            config.setTicketReceivedAt(LocalDateTime.now());
            configMapper.updateById(config);
        }
    }

    private static boolean looksLikeBase64Cipher(String value) {
        return value != null && value.length() > 32 && !value.contains("<");
    }

    private static String firstNonNull(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
