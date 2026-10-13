package com.smms.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 备注类长文本标签白名单过滤测试（NFR-SEC-05/GAP-P2-26）。
 */
class TextSanitizerTest {

    @Test
    void shouldKeepWhitelistedTags() {
        assertEquals("正常<b>加粗</b><i>斜体</i><br/><p>段落</p>",
                TextSanitizer.sanitize("正常<b>加粗</b><i>斜体</i><br><p>段落</p>"));
    }

    @Test
    void shouldStripScriptAndEventHandlers() {
        assertEquals("alert(1)", TextSanitizer.sanitize("<script>alert(1)</script>"));
        assertEquals("点击", TextSanitizer.sanitize("<img src=x onerror=alert(1)>点击"));
        assertEquals("文字", TextSanitizer.sanitize("文字<iframe src=//evil>"));
    }

    @Test
    void shouldStripAttributesOnWhitelistedTags() {
        assertEquals("<b>粗</b>", TextSanitizer.sanitize("<b style=\"color:red\" onclick=\"x()\">粗</b>"));
        assertEquals("断行<br/>", TextSanitizer.sanitize("断行<br clear=all>"));
    }

    @Test
    void shouldPassThroughPlainTextAndNull() {
        assertEquals("普通备注 100% 含 < 符号", TextSanitizer.sanitize("普通备注 100% 含 < 符号"));
        assertEquals("", TextSanitizer.sanitize(""));
        assertNull(TextSanitizer.sanitize(null));
    }
}
