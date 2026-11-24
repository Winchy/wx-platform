package com.jistack.platform.module.system.sms;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阿里云 RPC 风格 API 的 V1 签名（HMAC-SHA1）。
 *
 * <p>之所以自己写：官方 dysmsapi SDK 会把 okhttp 3.12 与 gson 带进生产包，而测试里已有的
 * MockWebServer 需要 okhttp 4.x，两边只能留一个——留 4.x 就变成「测试跑的客户端实现和生产不是
 * 一份代码」，这个仓库已经被「桩形状变规格」坑过多次，不再冒同类风险。
 *
 * <p>算法与阿里云官方签名器逐字对齐（见 SmsSignatureTest 里的黄金向量）：
 * 参数按 key 的 Unicode 顺序排 → 每个 key/value 各自 percentEncode 后用 & 拼成规范查询串 →
 * 整个串再 percentEncode 一次 → {@code POST&%2F&<它>} 作为待签名串 →
 * 以 {@code AccessKeySecret + "&"} 为密钥做 HmacSHA1 → Base64。
 */
final class AliyunSmsSigner {

    static final String HTTP_METHOD = "POST";
    private static final String ALGORITHM = "HmacSHA1";

    private AliyunSmsSigner() {
    }

    /**
     * RFC3986 百分号编码。Java 的 URLEncoder 是「表单编码」，空格变 +、* 不转、~ 转成 %7E，
     * 与阿里云要求的 unreserved={字母数字 - _ . ~} 不同，所以补三个替换；顺序不能调：
     * 必须先换 +，否则 %2B 里的 B 不受影响但 %20 会被当成字面量再处理一遍。
     */
    static String percentEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /** 规范查询串：按 key 排序、逐个 percentEncode、用 & 连接（不含 Signature）。 */
    static String canonicalizedQuery(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(percentEncode(entry.getKey())).append('=').append(percentEncode(entry.getValue()));
        }
        return builder.toString();
    }

    static String stringToSign(Map<String, String> params) {
        return HTTP_METHOD + "&" + percentEncode("/") + "&" + percentEncode(canonicalizedQuery(params));
    }

    static String signature(Map<String, String> params, String accessKeySecret) {
        return sign(stringToSign(params), accessKeySecret + "&");
    }

    /** 与规范查询串同样的拼法，区别是这里带着已算好的 Signature。 */
    static String formBody(Map<String, String> params) {
        return canonicalizedQuery(params);
    }

    private static String sign(String stringToSign, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            // HmacSHA1 是 JCA 必须提供的算法，走到这里只能是运行时被裁剪过
            throw new IllegalStateException("短信签名失败：" + ex.getMessage(), ex);
        }
    }
}
