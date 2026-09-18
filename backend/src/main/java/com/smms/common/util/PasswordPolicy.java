package com.smms.common.util;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 密码强度策略（BR-SYS-04）：长度 8~32 位，须同时包含字母与数字，
 * 不得与用户名相同，不得为常见弱口令。
 */
public final class PasswordPolicy {

    private static final Pattern LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    /** 内置弱口令黑名单（BR-SYS-04），按需扩展 */
    private static final Set<String> WEAK_BLACKLIST = Set.of(
            "12345678", "123456789", "1234567890", "87654321",
            "password", "password1", "password123", "admin123", "admin888",
            "qwerty123", "qwer1234", "abcd1234", "abc12345", "a1234567",
            "11111111", "88888888", "66666666", "1234qwer", "smms1234"
    );

    private PasswordPolicy() {
    }

    /**
     * 校验密码强度，不通过时返回中文原因，通过返回 null。
     */
    public static String validate(String username, String password) {
        if (password == null || password.length() < 8 || password.length() > 32) {
            return "密码长度须为 8~32 位";
        }
        if (!LETTER.matcher(password).find()) {
            return "密码须同时包含字母与数字";
        }
        if (!DIGIT.matcher(password).find()) {
            return "密码须同时包含字母与数字";
        }
        if (password.contains(" ") || password.chars().anyMatch(c -> c > 127)) {
            return "密码只能包含字母、数字与常规半角符号";
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            return "密码不得与用户名相同";
        }
        if (WEAK_BLACKLIST.contains(password.toLowerCase())) {
            return "密码过于简单，请更换为更复杂的密码";
        }
        return null;
    }
}
