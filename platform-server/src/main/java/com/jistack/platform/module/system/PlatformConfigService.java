package com.jistack.platform.module.system;

import com.jistack.platform.common.crypto.SecretCipher;
import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.crypto.WeChatMsgCrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformConfigService {

    private final PlatformConfigMapper configMapper;
    private final ComponentTicketMapper ticketMapper;
    private final SecretCipher cipher;

    /** 读取配置并把密文解出到 transient 字段，供微信调用与加解密使用。 */
    public PlatformConfig load() {
        PlatformConfig config = configMapper.selectById(PlatformConfig.SINGLETON_ID);
        if (config == null) {
            config = new PlatformConfig();
            config.setId(PlatformConfig.SINGLETON_ID);
            config.setStatus(0);
            configMapper.insert(config);
        }
        config.setComponentSecret(StringUtils.hasText(config.getComponentSecretEnc())
                ? cipher.decrypt(config.getComponentSecretEnc()) : null);
        config.setEncodingAesKey(StringUtils.hasText(config.getAesKeyEnc())
                ? cipher.decrypt(config.getAesKeyEnc()) : null);
        // apiBase 必须原样保持为空：WechatClientFactory 靠它区分「库里的覆盖项」和「WECHAT_API_BASE 默认值」。
        return config;
    }

    public boolean isReady(PlatformConfig config) {
        return StringUtils.hasText(config.getComponentAppid())
                && StringUtils.hasText(config.getComponentSecret())
                && StringUtils.hasText(config.getMessageToken())
                && StringUtils.hasText(config.getEncodingAesKey());
    }

    public PlatformConfig requireReady() {
        PlatformConfig config = load();
        if (!isReady(config)) {
            throw new BizException(409, "第三方平台凭证未配置完整，请先在「平台配置」填写 AppID、AppSecret、消息校验 Token、EncodingAESKey");
        }
        if (config.getStatus() == null || config.getStatus() != 1) {
            throw new BizException(409, "第三方平台尚未启用，请在「平台配置」中开启");
        }
        return config;
    }

    /**
     * 增量更新：请求体里为 null/空串的字段保持原值，避免前端回显掩码后再提交把密钥清空。
     */
    @Transactional
    public PlatformConfig update(PlatformConfig incoming) {
        PlatformConfig current = load();
        if (StringUtils.hasText(incoming.getComponentAppid())) {
            current.setComponentAppid(incoming.getComponentAppid().trim());
        }
        if (StringUtils.hasText(incoming.getComponentSecret())) {
            current.setComponentSecretEnc(cipher.encrypt(incoming.getComponentSecret().trim()));
        }
        if (StringUtils.hasText(incoming.getMessageToken())) {
            current.setMessageToken(incoming.getMessageToken().trim());
        }
        if (StringUtils.hasText(incoming.getEncodingAesKey())) {
            String aesKey = incoming.getEncodingAesKey().trim();
            if (aesKey.length() != 43) {
                throw new BizException("EncodingAESKey 必须是 43 位字符");
            }
            current.setAesKeyEnc(cipher.encrypt(aesKey));
        }
        if (incoming.getSiteBase() != null) {
            current.setSiteBase(trimToNull(incoming.getSiteBase()));
        }
        if (incoming.getApiBase() != null) {
            current.setApiBase(trimToNull(incoming.getApiBase()));
        }
        if (incoming.getStatus() != null) {
            current.setStatus(incoming.getStatus());
        }
        var principal = RequestContext.get();
        if (principal != null) {
            current.setUpdatedBy(principal.id());
        }
        current.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(current);
        return load();
    }

    /** 构造报文加解密器；凭证缺失时抛业务异常。 */
    public WeChatMsgCrypt crypt() {
        PlatformConfig config = load();
        if (!StringUtils.hasText(config.getMessageToken()) || !StringUtils.hasText(config.getEncodingAesKey())) {
            throw new BizException(409, "未配置消息校验 Token 或 EncodingAESKey");
        }
        return new WeChatMsgCrypt(config.getMessageToken(), config.getEncodingAesKey(), config.getComponentAppid());
    }

    public Map<String, Object> toVO() {
        PlatformConfig config = load();
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("componentAppid", config.getComponentAppid());
        vo.put("secretSet", StringUtils.hasText(config.getComponentSecretEnc()));
        vo.put("messageTokenSet", StringUtils.hasText(config.getMessageToken()));
        vo.put("aesKeySet", StringUtils.hasText(config.getAesKeyEnc()));
        vo.put("siteBase", config.getSiteBase());
        vo.put("apiBase", config.getApiBase());
        vo.put("status", config.getStatus());
        vo.put("ticketReceivedAt", config.getTicketReceivedAt());
        vo.put("tokenExpiresAt", config.getTokenExpiresAt());
        vo.put("callbackUrl", callbackUrl(config));
        vo.put("messageUrl", callbackUrl(config) + "/$APPID$");
        vo.put("ephemeralMasterKey", cipher.isEphemeralKey());
        return vo;
    }

    public String callbackUrl(PlatformConfig config) {
        String base = StringUtils.hasText(config.getSiteBase()) ? config.getSiteBase() : "";
        return base.isEmpty() ? "/wx/callback" : base.replaceAll("/+$", "") + "/wx/callback";
    }

    public java.util.List<Map<String, Object>> recentTickets(int limit) {
        var tickets = ticketMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<ComponentTicket>()
                .orderByDesc(ComponentTicket::getId)
                .last("limit " + Math.max(1, Math.min(limit, 50))));
        return tickets.stream().map(t -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("ticket", mask(t.getTicket()));
            item.put("receivedAt", t.getReceivedAt());
            return item;
        }).toList();
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 12) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
