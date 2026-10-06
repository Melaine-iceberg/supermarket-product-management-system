package com.smms.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 拼音首字母工具测试（FR-SALE-02 收银检索）。
 */
class PinyinUtilsTest {

    @Test
    void shouldConvertChineseNameToInitials() {
        assertEquals("kkkl", PinyinUtils.toInitials("可口可乐"));
    }

    @Test
    void shouldStripSpacesAndPunctuation() {
        assertEquals("xb125l", PinyinUtils.toInitials("雪碧 1.25L"));
        assertEquals("kkkl500ml", PinyinUtils.toInitials("可口可乐 500ml"));
    }

    @Test
    void shouldKeepAsciiDigitsAndLettersLowercase() {
        assertEquals("abc123", PinyinUtils.toInitials("ABc123"));
    }

    @Test
    void shouldReturnNullForBlankOrNonAlphanumeric() {
        assertNull(PinyinUtils.toInitials(null));
        assertNull(PinyinUtils.toInitials("   "));
        assertNull(PinyinUtils.toInitials("——（）"));
    }
}
