package com.smms.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EAN-13 工具测试（FR-GOODS-07）：校验位算法与店内码组装。
 */
class Ean13UtilTest {

    @Test
    @DisplayName("校验位：EAN-13 标准算法（奇数位×1、偶数位×3）")
    void checkDigit_standard() {
        // 690123456789 → 奇位和 26 + 偶位和 34×3 = 128 → 校验位 (10-8)%10 = 2
        assertThat(Ean13Util.checkDigit("690123456789")).isEqualTo(2);
        // 200000000001 → 奇位和 2 + 偶位和 1×3 = 5 → 校验位 5
        assertThat(Ean13Util.checkDigit("200000000001")).isEqualTo(5);
    }

    @Test
    @DisplayName("店内码组装：前缀2 + 序号10 + 校验位 = 13位")
    void buildInStoreCode() {
        assertThat(Ean13Util.buildInStoreCode("20", 1L)).isEqualTo("2000000000015");
        assertThat(Ean13Util.buildInStoreCode("29", 1234567890L)).hasSize(13);
        assertThat(Ean13Util.buildInStoreCode("20", 0L)).startsWith("2000000000");
    }

    @Test
    @DisplayName("合法性校验：13位数字且校验位正确")
    void isValid_checks() {
        assertThat(Ean13Util.isValid("6901234567892")).isTrue();
        assertThat(Ean13Util.isValid("2000000000015")).isTrue();
        assertThat(Ean13Util.isValid("6901234567890")).isFalse(); // 校验位错误
        assertThat(Ean13Util.isValid("69012345678921")).isFalse(); // 14位
        assertThat(Ean13Util.isValid("69012345678A")).isFalse();   // 非数字
        assertThat(Ean13Util.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("非法输入：数据位长度/序号越界直接拒绝")
    void invalidInputs_rejected() {
        assertThatThrownBy(() -> Ean13Util.checkDigit("12345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ean13Util.buildInStoreCode("2", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ean13Util.buildInStoreCode("20", 10_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
