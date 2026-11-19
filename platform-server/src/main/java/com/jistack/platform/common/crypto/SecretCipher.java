package com.jistack.platform.common.crypto;

import com.jistack.platform.common.web.BizException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 落库前的字段级对称加密：AES-256-GCM，随机 12 字节 IV，密文格式 base64(iv || ciphertext || tag)。
 * 主密钥来自 platform.master-key（Base64 32 字节）。
 */
@Component
public class SecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public SecretCipher(com.jistack.platform.common.config.PlatformProperties properties) {
        String configured = properties.masterKey();
        if (!StringUtils.hasText(configured)) {
            // 未配置主密钥时生成进程内随机密钥：能让本地开发跑通，但重启后旧密文不可解，故只允许非生产使用。
            byte[] ephemeral = new byte[32];
            RANDOM.nextBytes(ephemeral);
            this.keySpec = new SecretKeySpec(ephemeral, "AES");
            this.ephemeral = true;
        } else {
            byte[] key = Base64.getDecoder().decode(configured);
            if (key.length != 32) {
                throw new IllegalStateException("platform.master-key 必须是 Base64 编码的 32 字节密钥");
            }
            this.keySpec = new SecretKeySpec(key, "AES");
            this.ephemeral = false;
        }
    }

    private final boolean ephemeral;

    public boolean isEphemeralKey() {
        return ephemeral;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception ex) {
            throw new BizException(500, "加密失败", ex);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BizException(500, "解密失败，请确认 platform.master-key 未变更", ex);
        }
    }
}
