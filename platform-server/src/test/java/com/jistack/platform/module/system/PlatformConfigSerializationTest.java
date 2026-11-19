package com.jistack.platform.module.system;

import com.jistack.platform.module.authorizer.Authorizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥字段是 transient 的：既要能被前端 JSON 写入，又不能出现在 toString（日志）里。
 */
class PlatformConfigSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("PUT /api/platform/config 的明文密钥能被绑定")
    void bindsPlainSecrets() throws Exception {
        PlatformConfig config = objectMapper.readValue(
                "{\"componentAppid\":\"wx1\",\"componentSecret\":\"s3cr3t\","
                        + "\"messageToken\":\"tok\",\"encodingAesKey\":\"0123456789012345678901234567890123456789012\"}",
                PlatformConfig.class);

        assertEquals("wx1", config.getComponentAppid());
        assertEquals("s3cr3t", config.getComponentSecret());
        assertEquals("tok", config.getMessageToken());
        assertEquals(43, config.getEncodingAesKey().length());
    }

    @Test
    @DisplayName("toString 不得带出密钥明文")
    void toStringHidesSecrets() throws Exception {
        PlatformConfig config = objectMapper.readValue(
                "{\"componentSecret\":\"s3cr3t\",\"encodingAesKey\":\"AESKEY_VALUE\"}", PlatformConfig.class);

        String text = config.toString();
        assertFalse(text.contains("s3cr3t"), "toString 泄漏 componentSecret: " + text);
        assertFalse(text.contains("AESKEY_VALUE"), "toString 泄漏 encodingAesKey: " + text);
    }

    @Test
    @DisplayName("授权方 refresh_token 明文不进日志也不进响应")
    void authorizerRefreshTokenStaysInternal() throws Exception {
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerAppid("wx0000111122223333");
        authorizer.setRefreshToken("REFRESH_PLAIN");

        assertFalse(authorizer.toString().contains("REFRESH_PLAIN"), "toString 泄漏 refresh_token");
        assertFalse(objectMapper.writeValueAsString(authorizer).contains("REFRESH_PLAIN"), "响应泄漏 refresh_token");
    }
}
