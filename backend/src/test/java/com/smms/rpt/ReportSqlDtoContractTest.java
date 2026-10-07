package com.smms.rpt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报表 SQL 与 DTO 契约测试（DEF-11 回归防护）。
 *
 * <p>背景：MyBatis 对 record 型 resultType 采用<b>按列序</b>的构造器自动映射
 * （{@code applyColumnOrderBasedConstructorAutomapping}），要求
 * <b>结果集列数必须与 record 构造参数个数严格相等</b>，否则运行期抛
 * {@code ExecutorException: Constructor auto-mapping of '...' failed}。
 * 本项目未开启 {@code arg-name-based-constructor-auto-mapping}，故按名容错不生效。
 *
 * <p>2026-10 曾因此导致 16 个报表视图（商品分析 4/4、采购 4/5、库存 3/6、毛利 2/4、会员 3/5）
 * 全部 500，且因缺少覆盖而长期未被发现。本测试在编译期扫描各报表 mapper XML 的
 * SELECT 列表，与对应 record 的组件逐个比对（数量 + 顺序），无需数据库即可拦截同类缺陷。
 *
 * <p>局限：SELECT 列表含动态标签（{@code <include>}/{@code <if>}/{@code <choose>}）的
 * 语句无法静态展开，本测试跳过并在断言中记录数量，避免误报。
 */
class ReportSqlDtoContractTest {

    private static final Path MAPPER_DIR = Path.of("src/main/resources/mapper/rpt");
    private static final String DTO_PREFIX = "com.smms.rpt.dto.";

    @Test
    @DisplayName("报表 mapper：每个静态 SELECT 的列数/顺序与 DTO record 组件一致（DEF-11 回归）")
    void selectColumnsMatchDtoRecordComponents() throws Exception {
        List<Path> mappers;
        try (Stream<Path> s = Files.list(MAPPER_DIR)) {
            mappers = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
        assertThat(mappers).as("报表 mapper 文件应存在").isNotEmpty();

        List<String> violations = new ArrayList<>();
        int checked = 0;
        int skipped = 0;

        for (Path mapper : mappers) {
            Document doc = parse(mapper);
            NodeList selects = doc.getElementsByTagName("select");
            for (int i = 0; i < selects.getLength(); i++) {
                Element sel = (Element) selects.item(i);
                String resultType = sel.getAttribute("resultType");
                if (!resultType.startsWith(DTO_PREFIX)) {
                    continue;
                }
                Class<?> dto = Class.forName(resultType);
                if (!dto.isRecord()) {
                    continue;
                }
                List<String> columns = staticSelectColumns(sel);
                if (columns == null) {
                    skipped++;
                    continue;
                }
                checked++;

                RecordComponent[] components = dto.getRecordComponents();
                List<String> expected = Stream.of(components).map(RecordComponent::getName).toList();
                if (!columns.equals(expected)) {
                    violations.add(String.format(
                            "%s#%s：SELECT 列(%d) 与 %s 组件(%d) 不一致%n    SQL   = %s%n    record= %s",
                            mapper.getFileName(), sel.getAttribute("id"), columns.size(),
                            dto.getSimpleName(), expected.size(), columns, expected));
                }
            }
        }

        assertThat(violations)
                .as("报表 SQL 与 DTO record 构造参数必须逐列一致（共校验 %d 条，跳过动态 SQL %d 条）",
                        checked, skipped)
                .isEmpty();
        assertThat(checked).as("至少应校验到 20 条静态 SELECT，否则测试失去意义").isGreaterThanOrEqualTo(20);
    }

    /**
     * 提取静态 SELECT 的列别名序列；若 <b>SELECT 列表</b>含动态标签（无法静态展开）返回 null。
     * 注意：仅当动态标签出现在顶层 FROM <b>之前</b>（即列清单内）才跳过；
     * WHERE 子句中的 {@code <if>}/{@code <where>} 等不影响列清单，必须照常校验。
     */
    private static List<String> staticSelectColumns(Element select) {
        StringBuilder sb = new StringBuilder();
        NodeList children = select.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String tag = child.getNodeName().toLowerCase(Locale.ROOT);
                if ("include".equals(tag) || "if".equals(tag) || "choose".equals(tag) || "foreach".equals(tag)) {
                    return null;   // 列清单内含动态标签，交由运行期验证
                }
                continue;
            }
            sb.append(child.getNodeValue());
            if (indexOfTopLevelFrom(sb.toString()) >= 0) {
                break;             // 已到达 FROM，列清单收集完毕
            }
        }
        String sql = sb.toString();
        int from = indexOfTopLevelFrom(sql);
        if (from < 0) {
            return null;
        }
        String list = sql.substring(0, from).replaceFirst("(?is)^\\s*SELECT\\s+", "");
        if (list.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String item : splitTopLevel(list)) {
            String expr = item.trim();
            if (expr.isEmpty()) {
                continue;
            }
            Matcher as = Pattern.compile("(?is)\\bAS\\s+([A-Za-z_]\\w*)\\s*$").matcher(expr);
            if (as.find()) {
                names.add(as.group(1));
            } else {
                String[] parts = expr.split("\\s+");
                names.add(parts[parts.length - 1]);
            }
        }
        return names;
    }

    private static int indexOfTopLevelFrom(String sql) {
        int depth = 0;
        String upper = sql.toUpperCase(Locale.ROOT);
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("FROM", i)
                    && (i == 0 || Character.isWhitespace(sql.charAt(i - 1)))
                    && (i + 4 >= sql.length() || Character.isWhitespace(sql.charAt(i + 4)))) {
                return i;
            }
        }
        return -1;
    }

    /** 按深度 0 的逗号切分（跳过函数括号与 CASE 内的逗号）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(new File(file.toString()));
    }
}
