package com.smms.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 密码强度策略测试（BR-SYS-04）。
 */
class PasswordPolicyTest {

    @Test
    void shouldAcceptValidPassword() {
        assertNull(PasswordPolicy.validate("admin", "Good1234"));
        assertNull(PasswordPolicy.validate(null, "Xy9z12345"));
    }

    @Test
    void shouldRejectShortPassword() {
        assertEquals("密码长度须为 8~32 位", PasswordPolicy.validate("admin", "Ab1"));
        assertEquals("密码长度须为 8~32 位", PasswordPolicy.validate("admin", "a".repeat(33)));
    }

    @Test
    void shouldRejectPasswordWithoutLetterOrDigit() {
        assertEquals("密码须同时包含字母与数字", PasswordPolicy.validate("admin", "12345678"));
        assertEquals("密码须同时包含字母与数字", PasswordPolicy.validate("admin", "abcdefgh"));
    }

    @Test
    void shouldRejectUsernameSamePassword() {
        assertEquals("密码不得与用户名相同", PasswordPolicy.validate("admin123", "admin123"));
    }

    @Test
    void shouldRejectWeakPassword() {
        assertEquals("密码过于简单，请更换为更复杂的密码", PasswordPolicy.validate("admin", "qwer1234"));
        assertEquals("密码过于简单，请更换为更复杂的密码", PasswordPolicy.validate("admin", "password1"));
    }

    @Test
    void shouldRejectNonAsciiPassword() {
        assertNotNull(PasswordPolicy.validate("admin", "密码abc123"));
    }
}
