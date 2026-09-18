package com.smms.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 脱敏工具测试（FR-SYS-09）。
 */
class MaskUtilsTest {

    @Test
    void shouldMaskPhone() {
        assertEquals("138****5678", MaskUtils.maskPhone("13800005678"));
    }

    @Test
    void shouldMaskBankAccount() {
        assertEquals("****6789", MaskUtils.maskBankAccount("622202123456789"));
        assertEquals("****", MaskUtils.maskBankAccount("1234"));
        assertEquals("****", MaskUtils.maskBankAccount(null));
    }
}
