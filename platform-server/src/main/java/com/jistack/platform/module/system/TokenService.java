package com.jistack.platform.module.system;

import com.jistack.platform.common.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * 自签名的 HMAC-SHA256 令牌（header.payload.signature 三段，JWT 兼容形态）。
 * 站点是内网/后台单租户管理系统，够用且不引入 security starter。
 */
@Service
public class TokenService {

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TokenService(PlatformProperties properties) {
        String configured = properties.security() == null ? null : properties.security().jwtSecret();
        if (!StringUtils.hasText(configured)) {
            // 随机密钥：重启即失效，仅本地开发兜底。
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            this.secret = random;
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = properties.security() == null || properties.security().accessTokenTtl() == null
                ? 43200 : properties.security().accessTokenTtl().toSeconds();
    }

    @SneakyThrows
    public String issue(RequestContext.Principal principal) {
        String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = base64Url(objectMapper.writeValueAsBytes(Map.of(
                "uid", principal.id(),
                "sub", principal.username(),
                "role", principal.role(),
                "pv", principal.pwdVer(),
                "exp", Instant.now().getEpochSecond() + ttlSeconds)));
        String content = header + "." + payload;
        return content + "." + base64Url(hmac(content));
    }

    /** 校验签名与过期时间，失败返回 null。 */
    @SneakyThrows
    public RequestContext.Principal verify(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String content = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(hmac(content), base64UrlDecode(parts[2]))) {
            return null;
        }
        var node = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        long exp = node.path("exp").asLong(0);
        if (exp < Instant.now().getEpochSecond()) {
            return null;
        }
        return new RequestContext.Principal(
                node.path("uid").asLong(),
                node.path("sub").asText(),
                node.path("role").asText("OPERATOR"),
                node.path("pv").asInt(0));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private byte[] hmac(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("令牌签名失败", ex);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
