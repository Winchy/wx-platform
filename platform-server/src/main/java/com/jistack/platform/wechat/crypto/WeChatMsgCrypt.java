package com.jistack.platform.wechat.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信开放平台安全模式加解密（对应官方 WxBizMsgCrypt 语义）。
 *
 * <p>明文结构：16 字节随机串 + 4 字节大端消息长度 + 消息体 + AppId；
 * AES-256-CBC，密钥为 Base64 解码 {@code EncodingAESKey + "="} 得到的 32 字节，IV 取密钥前 16 字节；
 * 填充按官方实现使用 32 字节块的 PKCS#7。
 */
public class WeChatMsgCrypt {

    private static final int AES_KEY_BYTES = 32;
    private static final int IV_BYTES = 16;
    /** 官方样例的填充块大小固定为 32（16 的整数倍，CBC 下合法）。 */
    private static final int PADDING_BLOCK = 32;
    private static final int RANDOM_PREFIX = 16;

    private final String token;
    private final byte[] aesKey;
    private final String expectedAppId;

    public WeChatMsgCrypt(String token, String encodingAesKey, String expectedAppId) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey 长度必须为 43 位，当前="
                    + (encodingAesKey == null ? "null" : encodingAesKey.length()));
        }
        this.token = token;
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        if (this.aesKey.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("EncodingAESKey 不是合法的 43 位 Base64（无法解出 32 字节密钥）");
        }
        this.expectedAppId = expectedAppId;
    }

    /**
     * GET 校验 URL。普通模式 signature = sha1(sort(token, timestamp, nonce))；
     * 安全模式下 echostr 本身是密文且参与签名，故两种取值都接受。
     */
    public boolean verifyUrl(String timestamp, String nonce, String echoStr, String signature) {
        return equalsConstantTime(sha1Hex(timestamp, nonce, null), signature)
                || (echoStr != null && equalsConstantTime(sha1Hex(timestamp, nonce, echoStr), signature));
    }

    /** 推送报文：msg_signature = sha1(sort(token, timestamp, nonce, encrypt))。 */
    public boolean verify(String timestamp, String nonce, String encrypted, String msgSignature) {
        return equalsConstantTime(sha1Hex(timestamp, nonce, encrypted), msgSignature);
    }

    public String computeMsgSignature(String timestamp, String nonce, String encrypted) {
        return sha1Hex(timestamp, nonce, encrypted);
    }

    /**
     * 解密安全模式推送的密文。
     *
     * <p>明文尾部带的 AppId 在授权事件推送里是第三方平台 AppID，在小程序消息代收里是授权方 AppID，
     * 因此由调用方给出可接受的集合（通常 = {componentAppid, 报文 ToUserName}）。
     *
     * @param encrypted       {@code <Encrypt>} 内容
     * @param acceptedAppIds  允许出现的 AppId 集合，为空则不校验
     * @return 明文消息体（XML 字符串）
     */
    public String decrypt(String encrypted, java.util.Collection<String> acceptedAppIds) {
        try {
            byte[] cipherText = Base64.getDecoder().decode(encrypted);
            if (cipherText.length == 0 || cipherText.length % IV_BYTES != 0) {
                throw new IllegalArgumentException("密文长度非法: " + cipherText.length);
            }
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                    new javax.crypto.spec.IvParameterSpec(aesKey, 0, IV_BYTES));
            byte[] decrypted = cipher.doFinal(cipherText);
            byte[] stripped = removePkcs7(decrypted);

            int msgLength = ((stripped[RANDOM_PREFIX] & 0xFF) << 24)
                    | ((stripped[RANDOM_PREFIX + 1] & 0xFF) << 16)
                    | ((stripped[RANDOM_PREFIX + 2] & 0xFF) << 8)
                    | (stripped[RANDOM_PREFIX + 3] & 0xFF);
            if (msgLength < 0 || RANDOM_PREFIX + 4 + msgLength > stripped.length) {
                throw new IllegalArgumentException("明文长度字段越界: " + msgLength);
            }
            String message = new String(stripped, RANDOM_PREFIX + 4, msgLength, StandardCharsets.UTF_8);
            String appIdFromPayload = new String(stripped, RANDOM_PREFIX + 4 + msgLength,
                    stripped.length - RANDOM_PREFIX - 4 - msgLength, StandardCharsets.UTF_8);
            if (acceptedAppIds != null && !acceptedAppIds.isEmpty()
                    && !acceptedAppIds.contains(appIdFromPayload)
                    && !appIdFromPayload.equals(expectedAppId)) {
                throw new IllegalStateException("解密结果 AppId 不在可接受范围: " + appIdFromPayload);
            }
            return message;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("解密失败: " + ex.getMessage(), ex);
        }
    }

    /** 被动回复时把明文包成安全模式 XML。 */
    public String encryptToXml(String plainMessage, String toUserName) {
        try {
            byte[] random = new byte[RANDOM_PREFIX];
            new java.security.SecureRandom().nextBytes(random);
            byte[] message = plainMessage.getBytes(StandardCharsets.UTF_8);
            byte[] appId = (expectedAppId == null ? new byte[0] : expectedAppId.getBytes(StandardCharsets.UTF_8));
            byte[] lengthBytes = java.nio.ByteBuffer.allocate(4).putInt(message.length).array();

            byte[] unpadded = new byte[random.length + 4 + message.length + appId.length];
            int pos = 0;
            System.arraycopy(random, 0, unpadded, pos, random.length);
            pos += random.length;
            System.arraycopy(lengthBytes, 0, unpadded, pos, 4);
            pos += 4;
            System.arraycopy(message, 0, unpadded, pos, message.length);
            pos += message.length;
            System.arraycopy(appId, 0, unpadded, pos, appId.length);

            byte[] padded = addPkcs7(unpadded);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                    new javax.crypto.spec.IvParameterSpec(aesKey, 0, IV_BYTES));
            String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(padded));

            long timestamp = System.currentTimeMillis() / 1000;
            String nonce = Long.toHexString(System.nanoTime());
            Map<String, String> xml = new LinkedHashMap<>();
            xml.put("Encrypt", encrypted);
            xml.put("MsgSignature", sha1Hex(String.valueOf(timestamp), nonce, encrypted));
            xml.put("TimeStamp", String.valueOf(timestamp));
            xml.put("Nonce", nonce);
            xml.put("ToUserName", toUserName);
            return XmlCodec.build(xml);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("加密失败: " + ex.getMessage(), ex);
        }
    }

    private static byte[] addPkcs7(byte[] source) {
        int padCount = PADDING_BLOCK - (source.length % PADDING_BLOCK);
        if (padCount <= 0) {
            padCount = PADDING_BLOCK;
        }
        byte[] out = Arrays.copyOf(source, source.length + padCount);
        java.util.Arrays.fill(out, source.length, out.length, (byte) padCount);
        return out;
    }

    private static byte[] removePkcs7(byte[] source) {
        if (source.length == 0) {
            throw new IllegalArgumentException("解密得到空明文");
        }
        int pad = source[source.length - 1] & 0xFF;
        if (pad < 1 || pad > PADDING_BLOCK) {
            pad = 0;
        }
        return Arrays.copyOfRange(source, 0, Math.max(0, source.length - pad));
    }

    /** token、timestamp、nonce、[encrypt] 参与字段字典序排序后直接拼接做 sha1（小写 hex）。 */
    private String sha1Hex(String timestamp, String nonce, String encrypted) {
        try {
            String[] parts = encrypted == null
                    ? new String[]{token, timestamp, nonce}
                    : new String[]{token, timestamp, nonce, encrypted};
            Arrays.sort(parts, java.util.Comparator.naturalOrder());
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("sha1 计算失败", ex);
        }
    }

    private static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public String expectedAppId() {
        return expectedAppId;
    }
}
