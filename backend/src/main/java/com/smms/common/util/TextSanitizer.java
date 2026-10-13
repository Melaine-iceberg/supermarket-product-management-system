package com.smms.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备注类长文本标签过滤（NFR-SEC-05）：仅保留基础排版白名单标签
 * （b/i/em/strong/br/p，且不允许携带任何属性），其余标签整体过滤。
 * 前端不使用 v-html 渲染用户输入，此处按"标签白名单"在入库前再做一层兜底，
 * 避免存储型 XSS 载荷进入数据库与后续导出文件。
 */
public final class TextSanitizer {

    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    /** 白名单标签识别（允许携带属性，规范化时去除）：b/i/em/strong/br/p */
    private static final Pattern ALLOWED_NAME = Pattern.compile(
            "</?\\s*(?:b|i|em|strong|p)\\b[^>]*>|<br\\b[^>]*/?>", Pattern.CASE_INSENSITIVE);

    private static final Pattern OPEN_NAME = Pattern.compile("<\\s*(b|i|em|strong|p)");
    private static final Pattern CLOSE_NAME = Pattern.compile("</\\s*(b|i|em|strong|p)");

    private TextSanitizer() {
    }

    /** 过滤标签：白名单标签规范化保留，其余标签移除；null 原样返回 */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = TAG.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            sb.append(text, last, matcher.start());
            sb.append(normalize(matcher.group()));
            last = matcher.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** 规范化保留标签：去属性、统一小写；非白名单标签整体过滤 */
    private static String normalize(String tag) {
        if (!ALLOWED_NAME.matcher(tag).matches()) {
            return "";
        }
        String lower = tag.toLowerCase();
        if (lower.startsWith("</")) {
            Matcher close = CLOSE_NAME.matcher(lower);
            return close.find() ? "</" + close.group(1) + ">" : "";
        }
        if (lower.startsWith("<br")) {
            return "<br/>";
        }
        Matcher open = OPEN_NAME.matcher(lower);
        return open.find() ? "<" + open.group(1) + ">" : "";
    }
}
