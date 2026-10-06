package com.smms.common.util;

import net.sourceforge.pinyin4j.PinyinHelper;

/**
 * 汉字拼音首字母工具（FR-SALE-02 收银关键字检索）。
 * 商品名称落库时生成检索简称（如「可口可乐 500ml」→ kkkl500ml），
 * 归一为小写字母与数字，保证忽略空格/标点后的前缀输入仍可命中。
 */
public final class PinyinUtils {

    private PinyinUtils() {
    }

    /** 提取名称的拼音首字母简称；全部为非字母数字或转换失败时返回 null */
    public static String toInitials(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                continue;
            }
            if (c >= '\u4e00' && c <= '\u9fff') {
                String[] pinyin = PinyinHelper.toHanyuPinyinStringArray(c);
                if (pinyin != null && pinyin.length > 0 && !pinyin[0].isEmpty()) {
                    sb.append(Character.toLowerCase(pinyin[0].charAt(0)));
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
