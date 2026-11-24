package com.jistack.platform.module.system.sms;

import java.util.regex.Pattern;

/**
 * 手机号的规范化、校验与脱敏。规范化只做一件事：把 +86/0086/86 前缀去掉，得到 11 位裸号。
 * 库里存裸号、Redis 键用裸号，绑定时输入的写法不同也不会出现「同一个号两种键」。
 */
public final class PhoneNumbers {

    private static final Pattern MAINLAND = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-()]");

    private PhoneNumbers() {
    }

    /** 合法则返回规范化后的 11 位裸号，否则返回 null。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = SEPARATORS.matcher(raw).replaceAll("");
        if (digits.startsWith("+86")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0086")) {
            digits = digits.substring(4);
        } else if (digits.startsWith("86") && digits.length() > 11) {
            digits = digits.substring(2);
        }
        return MAINLAND.matcher(digits).matches() ? digits : null;
    }

    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }

    /** 138****8000：够本人确认是哪个号，不够被别人抄走当撞库目标。 */
    public static String mask(String phone) {
        String normalized = normalize(phone);
        if (normalized == null) {
            return "";
        }
        return normalized.substring(0, 3) + "****" + normalized.substring(7);
    }
}
