package com.smms.common.util;

/**
 * EAN-13 条码工具（FR-GOODS-07）：
 * 店内码 = 前缀(20~29) + 商品序号 + 校验位，校验位按 EAN-13 标准算法计算。
 */
public final class Ean13Util {

    private Ean13Util() {
    }

    /**
     * 计算 EAN-13 校验位：前 12 位从左至右，奇数位×1、偶数位×3 求和，
     * 校验位 = (10 - sum % 10) % 10。
     *
     * @param first12 前 12 位数字（长度必须为 12）
     * @return 校验位（0~9）
     */
    public static int checkDigit(String first12) {
        if (first12 == null || first12.length() != 12 || !first12.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("EAN-13 数据位必须为 12 位数字：" + first12);
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }

    /**
     * 组装店内码：前缀(2位) + 序号(10位) + 校验位 = 13 位。
     */
    public static String buildInStoreCode(String prefix, long serial) {
        if (prefix == null || prefix.length() != 2 || !prefix.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("店内码前缀必须为 2 位数字");
        }
        if (serial < 0 || serial > 9_999_999_999L) {
            throw new IllegalArgumentException("店内码序号超出 10 位范围：" + serial);
        }
        String first12 = prefix + String.format("%010d", serial);
        return first12 + checkDigit(first12);
    }

    /**
     * 校验 13 位 EAN-13 条码（含店内码）校验位是否正确。
     */
    public static boolean isValid(String code) {
        if (code == null || code.length() != 13 || !code.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int check = code.charAt(12) - '0';
        return check == checkDigit(code.substring(0, 12));
    }
}
