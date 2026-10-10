package com.smms.purchase;

import com.smms.purchase.dto.SuggestItemVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智能补货 SQL 与 DTO 契约测试（DEF-11 同类缺陷回归防护）。
 *
 * <p>背景：MyBatis 对 record 型 resultType 采用按列序的构造器自动映射，
 * 结果集列数必须与 record 构造参数个数严格相等，否则运行期抛
 * {@code Constructor auto-mapping of 'SuggestItemVO(...)' failed} → 接口 500、
 * 页面误显示为“暂无数据”。2026-10 曾因 {@code suggestSelect} 少 5 个占位列
 * （avgDailySales/leadTimeDays/suggestQty/calcBasis/supplierName）导致
 * 智能补货整页不可用。
 *
 * <p>该语句通过 {@code <include>} 复用 SQL 片段，无法被
 * {@code com.smms.rpt.ReportSqlDtoContractTest} 的静态扫描覆盖，故在此单独比对
 * 分片顶层列别名与 record 组件（数量 + 顺序），无需数据库。
 */
class SuggestPurchaseSqlContractTest {

    private static final Path MAPPER =
            Path.of("src/main/resources/mapper/purchase/SuggestPurchaseMapper.xml");
    private static final String FRAGMENT_ID = "suggestSelect";
    /** 仅匹配顶层列别名（含 AS 的 CAST 位于括号内，不会命中） */
    private static final Pattern TOP_LEVEL_ALIAS =
            Pattern.compile("(?i)\\bAS\\s+([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    @DisplayName("智能补货 mapper：suggestSelect 顶层列序与 SuggestItemVO 组件严格一致")
    void suggestSelectColumnsMatchRecord() throws Exception {
        List<String> aliases = topLevelAliases(fragmentText(FRAGMENT_ID));
        List<String> components = Stream.of(SuggestItemVO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(aliases)
                .as("suggestSelect 顶层列别名（按列序）应与 SuggestItemVO 构造参数一致")
                .containsExactlyElementsOf(components);
    }

    /** 取指定 {@code <sql id="...">} 片段的文本内容 */
    private String fragmentText(String id) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(MAPPER.toFile().getAbsolutePath()));
        NodeList sqlNodes = doc.getElementsByTagName("sql");
        for (int i = 0; i < sqlNodes.getLength(); i++) {
            Element el = (Element) sqlNodes.item(i);
            if (id.equals(el.getAttribute("id"))) {
                return el.getTextContent().replaceAll("(?s)<!--.*?-->", " ");
            }
        }
        throw new IllegalStateException("mapper 中未找到 sql 片段：" + id);
    }

    /** 按括号深度提取顶层 {@code AS 别名}，忽略子查询内的别名 */
    private List<String> topLevelAliases(String sql) {
        List<String> aliases = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (c == 'A' || c == 'a')) {
                Matcher m = TOP_LEVEL_ALIAS.matcher(sql);
                m.region(i, sql.length());
                if (m.lookingAt()) {
                    aliases.add(m.group(1));
                    i = m.end() - 1;
                }
            }
        }
        return aliases;
    }
}
