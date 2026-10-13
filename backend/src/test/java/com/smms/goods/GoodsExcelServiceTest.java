package com.smms.goods;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.GoodsImportReportVO;
import com.smms.goods.dto.GoodsImportResultVO;
import com.smms.goods.dto.GoodsListVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.entity.Brand;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.BrandMapper;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.goods.service.GoodsExcelService;
import com.smms.goods.service.GoodsService;
import com.smms.sys.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品 Excel 导入导出单元测试（FR-GOODS-11/12、BR-GOODS-07）：
 * 模板、全量校验（必填/格式/条码重复/关联档案/价格/称重约束）、
 * 部分提交（仅写通过行）、失败行导出（修正后重传）、按字段导出。
 */
@ExtendWith(MockitoExtension.class)
class GoodsExcelServiceTest {

    @Mock private GoodsMapper goodsMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private UnitMapper unitMapper;
    @Mock private BrandMapper brandMapper;
    @Mock private GoodsService goodsService;
    @Mock private SysConfigService configService;

    private GoodsExcelService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        service = new GoodsExcelService(goodsMapper, categoryMapper, unitMapper,
                brandMapper, goodsService, configService);
    }

    // ---------- 造数 ----------

    /** 标准表头 + 数据行构造 xlsx */
    private byte[] xlsx(List<String[]> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品导入");
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

    private String[] header() {
        return GoodsExcelService.COLUMNS;
    }

    /** 一行合法数据（分类=饮料 叶子，单位=瓶，品牌=可口可乐） */
    private String[] validRow(String name, String barcode) {
        return new String[]{name, "", barcode, "饮料", "瓶", "可口可乐",
                "500ml", "上海", "3.50", "5.00", "4.50", "否", "", "是", "10", "100", "50", ""};
    }

    /** 通用桩：分类树（食品→饮料）、单位（瓶/kg）、品牌（可口可乐），库内无同条码/编码商品 */
    private void stubLookups(List<Goods> existingGoods) {
        Category food = new Category();
        food.setId(2L);
        food.setParentId(0L);
        food.setName("食品");
        food.setStatus(1);
        Category drink = new Category();
        drink.setId(5L);
        drink.setParentId(2L);
        drink.setName("饮料");
        drink.setStatus(1);
        when(categoryMapper.selectList(null)).thenReturn(List.of(food, drink));

        Unit bottle = new Unit();
        bottle.setId(1L);
        bottle.setName("瓶");
        bottle.setIsWeight(0);
        bottle.setStatus(1);
        Unit kg = new Unit();
        kg.setId(2L);
        kg.setName("kg");
        kg.setIsWeight(1);
        kg.setStatus(1);
        when(unitMapper.selectList(null)).thenReturn(List.of(bottle, kg));

        Brand brand = new Brand();
        brand.setId(3L);
        brand.setName("可口可乐");
        brand.setStatus(1);
        when(brandMapper.selectList(null)).thenReturn(List.of(brand));

        // 部分用例无条码/编码可比对，不会触发库内查询，故放宽该桩
        lenient().when(goodsMapper.selectList(any())).thenReturn(existingGoods);
    }

    // ---------- 模板（FR-GOODS-11） ----------

    @Test
    @DisplayName("模板生成：表头齐全且可被解析校验流程复用")
    void template_ok() throws IOException {
        byte[] bytes = service.template();
        assertThat(bytes).isNotEmpty();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i < GoodsExcelService.COLUMNS.length; i++) {
                assertThat(sheet.getRow(0).getCell(i).getStringCellValue())
                        .isEqualTo(GoodsExcelService.COLUMNS[i]);
            }
            // 示例行以"示例"开头，导入时自动跳过
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).startsWith("示例");
        }
    }

    // ---------- 校验（BR-GOODS-07：不写库） ----------

    @Test
    @DisplayName("全量校验：合法行通过且不写库")
    void validate_allPassed() throws IOException {
        stubLookups(List.of());
        byte[] file = xlsx(List.of(header(), validRow("可乐", "2000000000015")));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        verify(goodsService, never()).create(any(), any());
    }

    @Test
    @DisplayName("必填校验：名称/分类/单位/零售价缺失逐项报错")
    void validate_missingRequired() throws IOException {
        stubLookups(List.of());
        // 仅填非必填的"规格"，整行非空但必填项全缺
        String[] bad = new String[18];
        bad[6] = "500ml";
        byte[] file = xlsx(List.of(header(), bad));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.failed()).isEqualTo(1);
        List<String> errors = report.rows().get(0).errors();
        assertThat(errors).contains("商品名称不能为空", "分类名称不能为空", "单位名称不能为空");
    }

    @Test
    @DisplayName("零售价缺失或为0时报错")
    void validate_retailPriceInvalid() throws IOException {
        stubLookups(List.of());
        // 第二行零售价置 0：重写第 10 列
        byte[] file;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品导入");
            Row h = sheet.createRow(0);
            for (int i = 0; i < header().length; i++) {
                h.createCell(i).setCellValue(header()[i]);
            }
            Row r1 = sheet.createRow(1);
            String[] v1 = validRow("可乐", "");
            for (int i = 0; i < v1.length; i++) {
                r1.createCell(i).setCellValue(v1[i]);
            }
            Row r2 = sheet.createRow(2);
            String[] v2 = validRow("雪碧", "");
            v2[9] = "0";
            for (int i = 0; i < v2.length; i++) {
                r2.createCell(i).setCellValue(v2[i]);
            }
            wb.write(out);
            file = out.toByteArray();
        }

        GoodsImportReportVO report = service.validate(stream(file));
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.rows().get(1).errors()).contains("零售单价必须大于0");
    }

    @Test
    @DisplayName("条码文件内重复：两行同条码均报错")
    void validate_barcodeDuplicateInFile() throws IOException {
        stubLookups(List.of());
        byte[] file = xlsx(List.of(header(),
                validRow("可乐", "2000000000015"), validRow("雪碧", "2000000000015")));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.failed()).isEqualTo(2);
        assertThat(report.rows().get(0).errors()).anyMatch(e -> e.contains("文件内重复"));
        assertThat(report.rows().get(1).errors()).anyMatch(e -> e.contains("文件内重复"));
    }

    @Test
    @DisplayName("BR-GOODS-01：条码与库内已有商品（含停用）重复时报错")
    void validate_barcodeExistsInDb() throws IOException {
        Goods existing = new Goods();
        existing.setBarcode("2000000000015");
        stubLookups(List.of(existing));
        byte[] file = xlsx(List.of(header(), validRow("可乐", "2000000000015")));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.rows().get(0).errors())
                .anyMatch(e -> e.contains("已存在（含停用商品）"));
    }

    @Test
    @DisplayName("13 位条码 EAN-13 校验位错误时报错")
    void validate_badEan13() throws IOException {
        stubLookups(List.of());
        byte[] file = xlsx(List.of(header(), validRow("可乐", "2000000000016")));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.rows().get(0).errors()).anyMatch(e -> e.contains("校验位不正确"));
    }

    @Test
    @DisplayName("BR-GOODS-05：称重商品单位须为重量单位且必填 PLU")
    void validate_weighedConstraints() throws IOException {
        stubLookups(List.of());
        String[] row = validRow("苹果", "");
        row[3] = "饮料";
        row[4] = "瓶";      // 非重量单位
        row[11] = "是";     // 称重
        row[12] = "";       // 无 PLU
        byte[] file = xlsx(List.of(header(), row));

        GoodsImportReportVO report = service.validate(stream(file));

        List<String> errors = report.rows().get(0).errors();
        assertThat(errors).contains("称重商品的单位必须为重量单位（kg/g）", "称重商品必须填写 PLU 码");
    }

    @Test
    @DisplayName("分类须为启用叶子分类：父分类报错")
    void validate_nonLeafCategory() throws IOException {
        stubLookups(List.of());
        String[] row = validRow("可乐", "");
        row[3] = "食品";    // 父分类
        byte[] file = xlsx(List.of(header(), row));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.rows().get(0).errors())
                .anyMatch(e -> e.contains("不是叶子分类"));
    }

    @Test
    @DisplayName("普通商品数量须为整数：初始库存 1.5 报错")
    void validate_nonIntegralStock() throws IOException {
        stubLookups(List.of());
        String[] row = validRow("可乐", "");
        row[16] = "1.5";
        byte[] file = xlsx(List.of(header(), row));

        GoodsImportReportVO report = service.validate(stream(file));

        assertThat(report.rows().get(0).errors())
                .anyMatch(e -> e.contains("初始库存必须为整数"));
    }

    @Test
    @DisplayName("表头不符/无数据行时拒绝并提示使用标准模板")
    void validate_badTemplate() throws IOException {
        // 解析阶段即失败，无需桩数据
        byte[] wrongHead = xlsx(List.of(new String[]{"随便", "表头"}, validRow("可乐", "")));
        assertThatThrownBy(() -> service.validate(stream(wrongHead)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标准模板");

        byte[] empty = xlsx(Collections.singletonList(header()));
        assertThatThrownBy(() -> service.validate(stream(empty)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可导入的数据行");
    }

    // ---------- 提交（BR-GOODS-07：单事务部分提交） ----------

    @Test
    @DisplayName("提交：仅导入通过行，失败行返回明细")
    void commit_importsPassedOnly() throws IOException {
        stubLookups(List.of());
        String[] bad = validRow("雪碧", "");
        bad[3] = "不存在的分类";
        byte[] file = xlsx(List.of(header(), validRow("可乐", ""), bad));

        GoodsImportResultVO result = service.commit(stream(file), "admin");

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedRows().get(0).errors())
                .anyMatch(e -> e.contains("不存在"));
        verify(goodsService, times(1)).create(any(), eq("admin"));
    }

    // ---------- 失败行导出（FR-GOODS-11：修正后重传） ----------

    @Test
    @DisplayName("失败行导出：仅含失败行与原因列，修正后可直接重传")
    void failedExport_reuploadAfterFix() throws IOException {
        stubLookups(List.of());
        String[] bad = validRow("雪碧", "");
        bad[3] = "不存在的分类";
        byte[] file = xlsx(List.of(header(), validRow("可乐", "2000000000015"), bad));

        byte[] exported = service.failedRowsExport(stream(file));

        byte[] fixed;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            Sheet sheet = wb.getSheetAt(0);
            // 表头 = 模板 18 列 + 失败原因列；仅失败行 1 行数据
            assertThat(sheet.getRow(0).getCell(18).getStringCellValue())
                    .isEqualTo(GoodsExcelService.REASON_COLUMN);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("雪碧");
            assertThat(sheet.getRow(1).getCell(18).getStringCellValue()).contains("不存在");
            // 修正分类后另存，作为重传文件
            sheet.getRow(1).getCell(3).setCellValue("饮料");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                fixed = out.toByteArray();
            }
        }

        // 重传：末列失败原因被自动忽略，全量通过
        GoodsImportReportVO report = service.validate(stream(fixed));
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败行导出：全部通过时拒绝导出")
    void failedExport_allPassedRejected() throws IOException {
        stubLookups(List.of());
        byte[] file = xlsx(List.of(header(), validRow("可乐", "2000000000015")));

        assertThatThrownBy(() -> service.failedRowsExport(stream(file)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有失败行");
    }

    // ---------- 导出（FR-GOODS-12） ----------

    @Test
    @DisplayName("导出：指定字段子集，表头与数据行正确")
    void export_selectedFields() throws IOException {
        GoodsListVO vo = new GoodsListVO(1L, "0500001", "2000000000015", "可乐",
                5L, "饮料", 1, 3L, "可口可乐", 1L, "瓶", "500ml", "上海",
                new BigDecimal("3.50"), new BigDecimal("5.00"), new BigDecimal("4.50"),
                null, null, null, 0, 1, BigDecimal.ZERO, null, 1,
                new BigDecimal("10"), new BigDecimal("3.50"), new BigDecimal("35.00"),
                "NORMAL", LocalDateTime.of(2026, 9, 19, 10, 0, 0));
        when(configService.getInt("export_max_rows", 50000)).thenReturn(50000);
        when(goodsService.listForExport(any(), eq(50_001))).thenReturn(List.of(vo));

        byte[] bytes = service.export(new GoodsQuery(null, null, null, null, null, null,
                null, null, null, 1, 20, null, null), List.of("goodsCode", "name", "retailPrice", "status"));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("商品编码");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("零售单价");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("0500001");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("可乐");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("启用");
        }
    }

    @Test
    @DisplayName("导出：未知字段拒绝")
    void export_unknownField() {
        assertThatThrownBy(() -> service.export(new GoodsQuery(null, null, null, null, null,
                null, null, null, null, 1, 20, null, null), List.of("hack")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的导出字段");
    }

    @Test
    @DisplayName("导出：超过 export_max_rows 上限时提示缩小范围（GAP-P0-01 / FR-RPT-08）")
    void export_overLimitRejected() {
        when(configService.getInt("export_max_rows", 50000)).thenReturn(50000);
        when(goodsService.listForExport(any(), eq(50_001)))
                .thenReturn(Collections.nCopies(50_001, (GoodsListVO) null));

        assertThatThrownBy(() -> service.export(new GoodsQuery(null, null, null, null, null,
                null, null, null, null, 1, 20, null, null), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过上限");
    }
}
