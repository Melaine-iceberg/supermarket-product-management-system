package com.smms.common.util;

/**
 * 敏感信息脱敏工具（FR-SYS-09、BR-SYS-08）。
 * 无权字段由后端不返回或脱敏后返回，而非前端隐藏。
 */
public final class MaskUtils {

    private MaskUtils() {
    }

    /** 手机号脱敏：138****5678；为空或格式异常时原样返回占位（FR-SYS-09 通用口径） */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone == null ? null : "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 手机号仅保留后 4 位：****5678（BR-MEM-08 收银台最小必要口径）。
     * 收银台/销售单等一线界面不显示前三段，完整手机号仅店长及以上角色可见。
     */
    public static String maskPhoneTail4(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.length() == 11 ? "****" + phone.substring(7) : "****";
    }

    /** 银行账号脱敏：仅保留后 4 位 */
    public static String maskBankAccount(String account) {
        if (account == null || account.length() <= 4) {
            return "****";
        }
        return "****" + account.substring(account.length() - 4);
    }
}
