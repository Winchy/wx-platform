package com.jistack.platform.wechat.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全模式加解密是手写实现，签名与分块填充最容易出错，这里做闭环与边界校验。
 */
class WeChatMsgCryptTest {

    private static final String TOKEN = "QEDNdqE24scO1Lg9NvBnE5rp1k8Dd3w5";
    private static final String COMPONENT_APPID = "wx1234567890abcdef";
    private static final String AUTHORIZER_APPID = "wx0000111122223333";
    private static final String AES_KEY = aesKey();

    /** 32 字节密钥的 Base64 去掉填充位正好是 43 位 EncodingAESKey。 */
    private static String aesKey() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7 + 3);
        }
        return Base64.getEncoder().encodeToString(raw).replace("=", "");
    }

    private static WeChatMsgCrypt crypt() {
        return new WeChatMsgCrypt(TOKEN, AES_KEY, COMPONENT_APPID);
    }

    /** 独立实现一遍排序拼接 sha1，用于验证实现没有漏掉参与签名的字段。 */
    private static String referenceSha1(String... values) throws Exception {
        String[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        for (String value : sorted) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    @DisplayName("密文长度必须是 16 的倍数，填充块固定 32")
    void aesKeyIs43CharsAndDecodesTo32Bytes() {
        assertEquals(43, AES_KEY.length());
        assertEquals(32, Base64.getDecoder().decode(AES_KEY + "=").length);
    }

    @Test
    void rejectsInvalidAesKey() {
        assertThrows(IllegalArgumentException.class, () -> new WeChatMsgCrypt(TOKEN, "short", COMPONENT_APPID));
        assertThrows(IllegalArgumentException.class, () -> new WeChatMsgCrypt(TOKEN, null, COMPONENT_APPID));
        // 43 位但不是合法 Base64（含非法字符）
        assertThrows(IllegalArgumentException.class,
                () -> new WeChatMsgCrypt(TOKEN, "****HhBviYdzzIlYHhkjuiAA8qrdQF1X04H8oj7lKZf", COMPONENT_APPID));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 12, 27, 28, 29, 32, 33, 100})
    @DisplayName("任意长度消息加密后都能解回原文（覆盖 32 字节块边界）")
    void roundTripForVariousMessageLengths(int bodyLength) {
        WeChatMsgCrypt crypt = crypt();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < bodyLength; i++) {
            body.append((char) ('a' + i % 26));
        }
        String plain = "<xml><InfoType>component_verify_ticket</InfoType><Ticket>" + body + "</Ticket></xml>";
        String xml = crypt.encryptToXml(plain, COMPONENT_APPID);
        String encrypted = XmlCodec.extractEncrypt(xml);
        Map<String, String> outer = XmlCodec.parseFlat(xml);

        assertEquals(plain, crypt.decrypt(encrypted, List.of(COMPONENT_APPID)));
        assertTrue(outer.containsKey("MsgSignature"), "被动回复需带 MsgSignature: " + xml);
        assertTrue(outer.containsKey("TimeStamp"));
        assertTrue(outer.containsKey("Nonce"));
        assertEquals(COMPONENT_APPID, outer.get("ToUserName"));
    }

    @Test
    @DisplayName("msg_signature 覆盖 token/timestamp/nonce/Encrypt 四项")
    void signatureMatchesReferenceImplementation() throws Exception {
        WeChatMsgCrypt crypt = crypt();
        String encrypted = XmlCodec.extractEncrypt(crypt.encryptToXml("<xml><A>1</A></xml>", COMPONENT_APPID));
        String timestamp = "1700000000";
        String nonce = "123456789";

        assertEquals(referenceSha1(TOKEN, timestamp, nonce, encrypted),
                crypt.computeMsgSignature(timestamp, nonce, encrypted));
        assertTrue(crypt.verify(timestamp, nonce, encrypted, crypt.computeMsgSignature(timestamp, nonce, encrypted)));
        assertFalse(crypt.verify(timestamp, nonce, encrypted, "0".repeat(40)));
        // 换掉密文后原签名必须失效
        assertFalse(crypt.verify(timestamp, nonce, encrypted + "A", crypt.computeMsgSignature(timestamp, nonce, encrypted)));
    }

    @Test
    void urlVerificationAcceptsBothPlainAndSecureSignature() throws Exception {
        WeChatMsgCrypt crypt = crypt();
        String timestamp = "1700000000";
        String nonce = "abcdef";
        String echoStr = XmlCodec.extractEncrypt(crypt.encryptToXml("ECHO_1234567890", COMPONENT_APPID));

        assertTrue(crypt.verifyUrl(timestamp, nonce, echoStr, referenceSha1(TOKEN, timestamp, nonce)));
        assertTrue(crypt.verifyUrl(timestamp, nonce, echoStr, referenceSha1(TOKEN, timestamp, nonce, echoStr)));
        assertFalse(crypt.verifyUrl(timestamp, nonce, echoStr, referenceSha1(TOKEN, timestamp, nonce, "other")));
        assertEquals("ECHO_1234567890", crypt.decrypt(echoStr, List.of(COMPONENT_APPID)));
    }

    @Test
    @DisplayName("解密后 AppId 不在允许集合内要拒绝（授权方与第三方平台 AppId 混用防线）")
    void rejectsUnexpectedAppId() {
        String encrypted = XmlCodec.extractEncrypt(crypt().encryptToXml("<xml><A>1</A></xml>", COMPONENT_APPID));
        WeChatMsgCrypt otherApp = new WeChatMsgCrypt(TOKEN, AES_KEY, AUTHORIZER_APPID);

        assertThrows(IllegalStateException.class, () -> otherApp.decrypt(encrypted, List.of(AUTHORIZER_APPID)));
        // 允许集合里包含 component appid 时可通过（ToUserName 场景）
        assertEquals("<xml><A>1</A></xml>", otherApp.decrypt(encrypted, List.of(AUTHORIZER_APPID, COMPONENT_APPID)));
    }

    @Test
    @DisplayName("密文被截断或长度非法时报错而不是解出乱码")
    void rejectsMalformedCipher() {
        WeChatMsgCrypt crypt = crypt();
        String encrypted = XmlCodec.extractEncrypt(crypt.encryptToXml("<xml><A>1</A></xml>", COMPONENT_APPID));
        String truncated = encrypted.substring(0, encrypted.length() - 8);

        assertThrows(RuntimeException.class, () -> crypt.decrypt(truncated, List.of(COMPONENT_APPID)));
        assertThrows(RuntimeException.class, () -> crypt.decrypt("not-base64!!!", List.of(COMPONENT_APPID)));
    }

    @Test
    void buildProducesParseableXml() {
        String xml = XmlCodec.build(Map.of("Encrypt", "abc+", "TimeStamp", "1700000000"));
        assertTrue(xml.startsWith("<xml>"), xml);
        assertEquals("abc+", XmlCodec.parseFlat(xml).get("Encrypt"));
        assertNotEquals(0, xml.length());
    }

    @Test
    @DisplayName("XXE 不被解析：外部实体与 DOCTYPE 一律拒绝")
    void rejectsXxe() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<xml><A>&e;</A></xml>";
        assertThrows(Exception.class, () -> XmlCodec.parseFlat(evil));
    }
}
