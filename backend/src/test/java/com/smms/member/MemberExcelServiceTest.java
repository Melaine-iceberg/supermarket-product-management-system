package com.smms.member;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.member.dto.MemberCreateRequest;
import com.smms.member.dto.MemberImportReportVO;
import com.smms.member.dto.MemberImportResultVO;
import com.smms.member.dto.MemberImportRowVO;
import com.smms.member.entity.Member;
import com.smms.member.mapper.MemberMapper;
import com.smms.member.service.MemberExcelService;
import com.smms.member.service.MemberService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员 Excel 导入单元测试（FR-MEM-03，BR-GOODS-07 机制）：
 * 模板、全量校验（必填/手机号规则/文件内与库内重复/性别/生日）、部分提交。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberExcelServiceTest {

    @Mock private MemberMapper memberMapper;
    @Mock private MemberService memberService;

    private MemberExcelService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Member.class);
        service = new MemberExcelService(memberMapper, memberService);
        // 库内手机号默认无重复
        when(memberMapper.selectList(any())).thenReturn(List.of());
    }

    // ---------- 造数 ----------

    private byte[] xlsx(List<String[]> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("会员导入");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                String[] vals = rows.get(r);
                for (int c = 0; c < vals.length; c++) {
                    if (vals[c] != null && !vals[c].isEmpty()) {
                        row.createCell(c).setCellValue(vals[c]);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private String[] validRow(String name, String phone) {
        return new String[]{name, phone, "女", "1990-01-01", "南京市玄武区", ""};
    }

    // ---------- 模板（FR-MEM-03） ----------

    @Test
    @DisplayName("模板：含标准表头与示例行")
    void template_containsHeader() throws IOException {
        byte[] template = service.template();

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i < MemberExcelService.COLUMNS.length; i++) {
                assertThat(sheet.getRow(0).getCell(i).getStringCellValue())
                        .isEqualTo(MemberExcelService.COLUMNS[i]);
            }
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).startsWith("示例");
        }
    }

    // ---------- 校验（BR-GOODS-07：不写库） ----------

    @Test
    @DisplayName("校验：合法行通过，不写库")
    void validate_validRow() throws IOException {
        MemberImportReportVO report = service.validate(
                stream(xlsx(List.of(MemberExcelService.COLUMNS, validRow("张三", "13800001111")))));

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.passedCount()).isEqualTo(1);
        assertThat(report.failedCount()).isZero();
        assertThat(report.rows().get(0).passed()).isTrue();
        verify(memberService, never()).create(any(), any());
    }

    @Test
    @DisplayName("校验：手机号非法/文件内重复/库内已注册（BR-MEM-01）")
    void validate_phoneErrors() throws IOException {
        Member exists = new Member();
        exists.setPhone("13800003333");
        when(memberMapper.selectList(any())).thenReturn(List.of(exists));

        MemberImportReportVO report = service.validate(stream(xlsx(List.of(
                MemberExcelService.COLUMNS,
                validRow("张三", "12800001111"),     // 非法号段
                validRow("李四", "13800002222"),
                validRow("王五", "13800002222"),     // 文件内重复
                validRow("赵六", "13800003333"),     // 库内已存在
                validRow("", "13800004444")))));    // 姓名缺失

        assertThat(report.total()).isEqualTo(5);
        assertThat(report.passedCount()).isZero();
        List<List<String>> errors = report.rows().stream()
                .map(MemberImportRowVO::errors).toList();
        assertThat(errors.get(0)).anyMatch(e -> e.contains("不是合法"));
        assertThat(errors.get(1)).anyMatch(e -> e.contains("文件内重复"));
        assertThat(errors.get(2)).anyMatch(e -> e.contains("文件内重复"));
        assertThat(errors.get(3)).anyMatch(e -> e.contains("已注册会员"));
        assertThat(errors.get(4)).anyMatch(e -> e.contains("姓名不能为空"));
    }

    @Test
    @DisplayName("校验：性别/生日格式与未来生日")
    void validate_genderAndBirthday() throws IOException {
        MemberImportReportVO report = service.validate(stream(xlsx(List.of(
                MemberExcelService.COLUMNS,
                new String[]{"张三", "13800001111", "未知", "", "", ""},
                new String[]{"李四", "13800002222", "", "1990/1/1", "", ""},
                new String[]{"王五", "13800003333", "", "2099-01-01", "", ""},
                new String[]{"赵六", "13800004444", "男", "2000-02-30", "", ""}))));

        assertThat(report.total()).isEqualTo(4);
        List<List<String>> errors = report.rows().stream()
                .map(MemberImportRowVO::errors).toList();
        assertThat(errors.get(0)).anyMatch(e -> e.contains("男 / 女"));
        assertThat(report.rows().get(1).passed()).isTrue();  // yyyy/M/d 合法
        assertThat(errors.get(2)).anyMatch(e -> e.contains("不能晚于当天"));
        assertThat(errors.get(3)).anyMatch(e -> e.contains("格式不正确"));
    }

    @Test
    @DisplayName("校验：表头不符拒绝（要求使用标准模板）")
    void validate_badHeader() throws IOException {
        assertThatThrownBy(() -> service.validate(stream(xlsx(List.of(
                new String[]{"姓名", "手机号", "性别", "生日", "地址", "备注"},
                validRow("张三", "13800001111"))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请使用标准模板");
    }

    // ---------- 提交（BR-GOODS-07：单事务、部分提交） ----------

    @Test
    @DisplayName("提交：仅写入通过行，失败行返回明细；失败行不调注册")
    void commit_partial() throws IOException {
        MemberImportResultVO result = service.commit(
                stream(xlsx(List.of(
                        MemberExcelService.COLUMNS,
                        validRow("张三", "13800001111"),
                        new String[]{"李四", "12800002222", "", "", "", ""}))),
                "王店长");

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedRows().get(0).errors()).anyMatch(e -> e.contains("不是合法"));

        ArgumentCaptor<MemberCreateRequest> captor = ArgumentCaptor.forClass(MemberCreateRequest.class);
        verify(memberService, times(1)).create(captor.capture(), any());
        MemberCreateRequest saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("张三");
        assertThat(saved.getPhone()).isEqualTo("13800001111");
        assertThat(saved.getGender()).isEqualTo(2);
        assertThat(saved.getBirthday()).isEqualTo(LocalDate.of(1990, 1, 1));
    }
}
