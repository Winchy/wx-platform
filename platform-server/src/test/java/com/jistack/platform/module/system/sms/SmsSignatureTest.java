package com.jistack.platform.module.system.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名必须与阿里云自己的签名器逐字节一致，否则线上只会收到 SignatureDoesNotMatch，
 * 而本地没有真实 AccessKey 时谁都看不出来。
 *
 * <p>期望值不是手算的，是阿里云官方 aliyun-python-sdk-core 2.16.1 里
 * {@code aliyunsdkcore/auth/composer/rpc_signature_composer.py} 的
 * {@code __compose_string_to_sign} + {@code sha_hmac1.get_sign_string} 对同一组固定参数
 * （含固定 SignatureNonce 与 Timestamp）算出来的。重做办法：
 * {@code pip3 download aliyun-python-sdk-core --no-deps}，解压后按本类的参数调用那两个函数即可。
 *
 * <p>注意：用探针打真实 dysmsapi 只能证明参数格式被接受（阿里云先查 AccessKeyId 再验签名，
 * 假 key 永远停在 InvalidAccessKeyId.NotFound），签名的数学对错只能靠这种等价向量。
 */
class SmsSignatureTest {

    private static final String ACCESS_KEY_ID = "LTAI5tExampleKeyId";
    private static final String ACCESS_KEY_SECRET = "ExampleSecretKeyForVector";
    private static final String NONCE = "3b1c9f6e-0000-4aaa-8bbb-ccddeeff0011";
    private static final String TIMESTAMP = "2026-11-24T02:15:30Z";

    @Test
    @DisplayName("中文签名名 + JSON 模板参数：待签名串与签名值都对齐官方签名器")
    void matchesOfficialSignerForStandardRequest() {
        Map<String, String> params = params("极栈科技", "{\"code\":\"123456\"}");

        assertThat(AliyunSmsSigner.stringToSign(params)).isEqualTo("""
                POST&%2F&AccessKeyId%3DLTAI5tExampleKeyId%26Action%3DSendSms%26Format%3DJSON\
                %26PhoneNumbers%3D13800138000%26RegionId%3Dcn-hangzhou\
                %26SignName%3D%25E6%259E%2581%25E6%25A0%2588%25E7%25A7%2591%25E6%258A%2580\
                %26SignatureMethod%3DHMAC-SHA1%26SignatureNonce%3D3b1c9f6e-0000-4aaa-8bbb-ccddeeff0011\
                %26SignatureVersion%3D1.0%26TemplateCode%3DSMS_200000000\
                %26TemplateParam%3D%257B%2522code%2522%253A%2522123456%2522%257D\
                %26Timestamp%3D2026-11-24T02%253A15%253A30Z%26Version%3D2017-05-25""");

        assertThat(AliyunSmsSigner.signature(params, ACCESS_KEY_SECRET)).isEqualTo("yqdMwAbqLp5yATbilztb+jxoutA=");
    }

    @Test
    @DisplayName("参数里有 * ~ / 空格 + % 时，percentEncode 的三条补替换一条都不能少")
    void matchesOfficialSignerForReservedCharacters() {
        Map<String, String> params = params("a*b~c/d e+f", "{\"url\":\"https://x.y/a?b=c&d=e\",\"n\":\"100%\"}");

        assertThat(AliyunSmsSigner.stringToSign(params)).isEqualTo("""
                POST&%2F&AccessKeyId%3DLTAI5tExampleKeyId%26Action%3DSendSms%26Format%3DJSON\
                %26PhoneNumbers%3D13800138000%26RegionId%3Dcn-hangzhou%26SignName%3Da%252Ab~c%252Fd%2520e%252Bf\
                %26SignatureMethod%3DHMAC-SHA1%26SignatureNonce%3D3b1c9f6e-0000-4aaa-8bbb-ccddeeff0011\
                %26SignatureVersion%3D1.0%26TemplateCode%3DSMS_200000000\
                %26TemplateParam%3D%257B%2522url%2522%253A%2522https%253A%252F%252Fx.y%252Fa%253Fb%253Dc\
                %2526d%253De%2522%252C%2522n%2522%253A%2522100%2525%2522%257D\
                %26Timestamp%3D2026-11-24T02%253A15%253A30Z%26Version%3D2017-05-25""");

        assertThat(AliyunSmsSigner.signature(params, ACCESS_KEY_SECRET)).isEqualTo("Wbj7iY8J5aV7JJwS1J1T1YA/vx8=");
    }

    @Test
    @DisplayName("percentEncode：空格出 %20 不出 +，* 要转，~ 要还原，/ 要转成 %2F")
    void percentEncodeFollowsRfc3986NotFormEncoding() {
        assertThat(AliyunSmsSigner.percentEncode("a b")).isEqualTo("a%20b");
        assertThat(AliyunSmsSigner.percentEncode("a+b")).isEqualTo("a%2Bb");
        assertThat(AliyunSmsSigner.percentEncode("a*b")).isEqualTo("a%2Ab");
        assertThat(AliyunSmsSigner.percentEncode("a~b")).isEqualTo("a~b");
        assertThat(AliyunSmsSigner.percentEncode("a/b")).isEqualTo("a%2Fb");
        assertThat(AliyunSmsSigner.percentEncode("100%")).isEqualTo("100%25");
        assertThat(AliyunSmsSigner.percentEncode("验证码")).isEqualTo("%E9%AA%8C%E8%AF%81%E7%A0%81");
    }

    @Test
    @DisplayName("参数按 key 排序后再拼，插入顺序不影响签名")
    void sortingIgnoresInsertionOrder() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("Action", "SendSms");
        one.put("PhoneNumbers", "13800138000");
        one.put("Format", "JSON");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("PhoneNumbers", "13800138000");
        other.put("Format", "JSON");
        other.put("Action", "SendSms");

        assertThat(AliyunSmsSigner.canonicalizedQuery(one)).isEqualTo(AliyunSmsSigner.canonicalizedQuery(other));
    }

    /** 与 AliyunSmsSender.send 里同一组公共参数，只是把随机的 Nonce/Timestamp 钉死。 */
    private static Map<String, String> params(String signName, String templateParam) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "SendSms");
        params.put("Version", "2017-05-25");
        params.put("Format", "JSON");
        params.put("RegionId", "cn-hangzhou");
        params.put("AccessKeyId", ACCESS_KEY_ID);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", NONCE);
        params.put("Timestamp", TIMESTAMP);
        params.put("PhoneNumbers", "13800138000");
        params.put("SignName", signName);
        params.put("TemplateCode", "SMS_200000000");
        params.put("TemplateParam", templateParam);
        return params;
    }
}
