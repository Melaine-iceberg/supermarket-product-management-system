package com.smms.goods.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.common.util.Ean13Util;
import com.smms.goods.dto.GoodsCreateRequest;
import com.smms.goods.dto.GoodsImportReportVO;
import com.smms.goods.dto.GoodsImportResultVO;
import com.smms.goods.dto.GoodsImportRowVO;
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
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品 Excel 导入导出服务（FR-GOODS-11/12）：
 * 导入采用"全量校验、部分提交"策略（BR-GOODS-07）——校验阶段不写库；
 * 提交阶段仅写入通过行，整个提交在单事务内完成；
 * 导出按当前筛选条件流式写出，行数上限取参数 export_max_rows。
 */
@Service
@RequiredArgsConstructor
public class GoodsExcelService {

    /** 模板列（顺序固定，解析按列索引，* 为必填） */
    public static final String[] COLUMNS = {
            "商品名称*", "商品编码", "商品条码", "分类名称*", "单位名称*", "品牌名称",
            "规格", "产地", "进货参考价", "零售单价*", "会员价", "是否称重",
            "PLU码", "是否允许退货", "安全库存下限", "库存上限", "初始库存", "备注"
    };

    private static final int MAX_IMPORT_ROWS = 5000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 导出字段白名单（key → 列标题，FR-GOODS-12 导出字段可选） */
    static final Map<String, String> EXPORT_FIELDS = new LinkedHashMap<>();

    static {
        EXPORT_FIELDS.put("goodsCode", "商品编码");
        EXPORT_FIELDS.put("barcode", "商品条码");
        EXPORT_FIELDS.put("name", "商品名称");
        EXPORT_FIELDS.put("category", "分类");
        EXPORT_FIELDS.put("brand", "品牌");
        EXPORT_FIELDS.put("unit", "单位");
        EXPORT_FIELDS.put("spec", "规格");
        EXPORT_FIELDS.put("origin", "产地");
        EXPORT_FIELDS.put("purchasePrice", "进货参考价");
        EXPORT_FIELDS.put("retailPrice", "零售单价");
        EXPORT_FIELDS.put("memberPrice", "会员价");
        EXPORT_FIELDS.put("promoPrice", "当前促销价");
        EXPORT_FIELDS.put("plu", "PLU码");
        EXPORT_FIELDS.put("weighed", "是否称重");
        EXPORT_FIELDS.put("allowReturn", "允许退货");
        EXPORT_FIELDS.put("lower", "安全库存下限");
        EXPORT_FIELDS.put("upper", "库存上限");
        EXPORT_FIELDS.put("status", "状态");
        EXPORT_FIELDS.put("quantity", "结存数量");
        EXPORT_FIELDS.put("stockStatus", "库存状态");
        EXPORT_FIELDS.put("createTime", "创建时间");
    }

    private final GoodsMapper goodsMapper;
    private final CategoryMapper categoryMapper;
    private final UnitMapper unitMapper;
    private final BrandMapper brandMapper;
    private final GoodsService goodsService;
    private final SysConfigService configService;

    /** 解析后的原始行（Excel 行号从 1 开始计数，与用户视角一致） */
    private record ParsedRow(int rowNum, Map<String, String> data) {
        String val(String column) {
            String v = data.get(column);
            return v == null ? "" : v.trim();
        }
    }

    /** 校验后的行：错误列表 + 通过时可直接提交的请求对象 */
    private record CheckedRow(ParsedRow row, List<String> errors, GoodsCreateRequest request) {
    }

    // ==================== 模板下载（FR-GOODS-11） ====================

    /** 标准模板：表头 + 示例行（导入时自动跳过"示例"行）+ 填写说明 */
    public byte[] template() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品导入");
            var headStyle = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            headStyle.setFont(font);

            Row head = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                var cell = head.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(headStyle);
                sheet.setColumnWidth(i, 14 * 256);
            }
            Row demo = sheet.createRow(1);
            String[] values = {"示例：可口可乐（导入时自动跳过本行）", "", "", "饮料", "瓶", "可口可乐",
                    "500ml", "上海", "3.50", "5.00", "4.50", "否", "", "是", "10", "100", "50", ""};
            for (int i = 0; i < values.length; i++) {
                demo.createCell(i).setCellValue(values[i]);
            }

            Sheet tips = wb.createSheet("填写说明");
            String[] lines = {
                    "1. 请勿修改第一行表头；第 2 行为示例行，导入时自动跳过，也可自行删除。",
                    "2. 带 * 的列为必填：商品名称、分类名称、单位名称、零售单价。",
                    "3. 商品编码/条码留空时系统自动生成（编码=分类码+流水，条码=EAN-13 店内码）。",
                    "4. 是否称重/是否允许退货填：是 / 否（允许退货留空默认为是）。",
                    "5. 称重商品的单位必须为重量单位（kg/g），且必须填写 PLU 码。",
                    "6. 价格精度到分（2 位小数）；数量精度：普通商品为整数，称重商品 3 位小数。",
                    "7. 分类名称须为启用的叶子分类；同一文件内条码/编码不可重复，也不可与库内已有商品重复。",
                    "8. 上传后系统先全量校验并生成报告，确认后仅导入校验通过的行。"
            };
            for (int i = 0; i < lines.length; i++) {
                tips.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模板生成失败");
        }
    }

    // ==================== 全量校验（BR-GOODS-07：不写库） ====================

    /** 上传校验：解析并逐行校验，返回报告（成功 N / 失败 M + 逐行失败原因） */
    public GoodsImportReportVO validate(InputStream in) {
        List<CheckedRow> checked = parseAndCheck(in);
        List<GoodsImportRowVO> rows = checked.stream()
                .map(c -> new GoodsImportRowVO(c.row().rowNum(), c.errors().isEmpty(),
                        c.errors(), c.row().data()))
                .toList();
        int failed = (int) rows.stream().filter(r -> !r.passed()).count();
        return new GoodsImportReportVO(rows.size(), rows.size() - failed, failed, rows);
    }

    // ==================== 提交导入（BR-GOODS-07：单事务、部分提交） ====================

    /**
     * 提交导入：重新校验同一文件（无状态，防篡改），单事务内仅写入通过行；
     * 任一行写入异常则整体回滚，避免半成功状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public GoodsImportResultVO commit(InputStream in, String operator) {
        List<CheckedRow> checked = parseAndCheck(in);
        List<GoodsCreateRequest> pass = checked.stream()
                .filter(c -> c.errors().isEmpty())
                .map(CheckedRow::request)
                .toList();
        for (GoodsCreateRequest req : pass) {
            // 复用新增逻辑（编码/条码生成、期初建账），校验兜底重复执行无害
            goodsService.create(req, operator);
        }
        List<GoodsImportRowVO> failedRows = checked.stream()
                .filter(c -> !c.errors().isEmpty())
                .map(c -> new GoodsImportRowVO(c.row().rowNum(), false, c.errors(), c.row().data()))
                .toList();
        return new GoodsImportResultVO(pass.size(), failedRows.size(), failedRows);
    }

    // ==================== 导出（FR-GOODS-12） ====================

    /** 按筛选条件导出，fields 为空时导出全部白名单字段；行数上限 export_max_rows */
    public byte[] export(GoodsQuery query, List<String> fields) {
        List<String> cols = resolveExportFields(fields);
        int maxRows = configService.getInt("export_max_rows", 50000);
        List<GoodsListVO> list = goodsService.listForExport(query, maxRows);

        try (SXSSFWorkbook wb = new SXSSFWorkbook(200);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品清单");
            Row head = sheet.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                head.createCell(i).setCellValue(EXPORT_FIELDS.get(cols.get(i)));
                sheet.setColumnWidth(i, 14 * 256);
            }
            int r = 1;
            for (GoodsListVO vo : list) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < cols.size(); i++) {
                    row.createCell(i).setCellValue(cellValue(cols.get(i), vo));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "导出失败");
        }
    }

    private List<String> resolveExportFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.copyOf(EXPORT_FIELDS.keySet());
        }
        for (String f : fields) {
            if (!EXPORT_FIELDS.containsKey(f)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的导出字段：" + f);
            }
        }
        return fields;
    }

    private String cellValue(String key, GoodsListVO vo) {
        return switch (key) {
            case "goodsCode" -> nvl(vo.goodsCode());
            case "barcode" -> nvl(vo.barcode());
            case "name" -> nvl(vo.name());
            case "category" -> nvl(vo.categoryName());
            case "brand" -> nvl(vo.brandName());
            case "unit" -> nvl(vo.unitName());
            case "spec" -> nvl(vo.spec());
            case "origin" -> nvl(vo.origin());
            case "purchasePrice" -> price(vo.purchasePrice());
            case "retailPrice" -> price(vo.retailPrice());
            case "memberPrice" -> price(vo.memberPrice());
            case "promoPrice" -> price(vo.promoPrice());
            case "plu" -> nvl(vo.pluCode());
            case "weighed" -> yesNo(vo.isWeighed());
            case "allowReturn" -> yesNo(vo.allowReturn());
            case "lower" -> nvl(vo.safetyStockLower());
            case "upper" -> nvl(vo.safetyStockUpper());
            case "status" -> vo.status() != null && vo.status() == 1 ? "启用" : "停用";
            case "quantity" -> nvl(vo.quantity());
            case "stockStatus" -> nvl(vo.stockStatus());
            case "createTime" -> vo.createTime() == null ? "" : vo.createTime().format(TIME_FORMAT);
            default -> "";
        };
    }

    private String nvl(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String price(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private String yesNo(Integer v) {
        if (v == null) {
            return "";
        }
        return v == 1 ? "是" : "否";
    }

    // ==================== 解析与校验 ====================

    /** 解析 Excel 并逐行校验；表头不符/无法解析时抛参数异常 */
    private List<CheckedRow> parseAndCheck(InputStream in) {
        List<ParsedRow> rows = parse(in);

        // 预载名称索引（分类/单位/品牌数据量小）；parentIds 用于叶子判断
        Map<String, List<Category>> categoryByName = categoryMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(c -> nvl(c.getName())));
        Map<String, Unit> unitByName = unitMapper.selectList(null).stream()
                .collect(Collectors.toMap(u -> nvl(u.getName()), u -> u, (a, b) -> a));
        Map<String, Brand> brandByName = brandMapper.selectList(null).stream()
                .collect(Collectors.toMap(b -> nvl(b.getName()), b -> b, (a, b) -> a));
        Set<Long> parentIds = categoryByName.values().stream()
                .flatMap(List::stream)
                .map(Category::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 文件内条码/编码重复检测 + 库内已存在检测（批量 in 查询）
        Map<String, Integer> barcodeCount = new HashMap<>();
        Map<String, Integer> codeCount = new HashMap<>();
        for (ParsedRow row : rows) {
            String barcode = row.val("商品条码");
            if (!barcode.isEmpty()) {
                barcodeCount.merge(barcode, 1, Integer::sum);
            }
            String code = row.val("商品编码");
            if (!code.isEmpty()) {
                codeCount.merge(code, 1, Integer::sum);
            }
        }
        Set<String> existsBarcodes = selectExistingValues(barcodeCount.keySet(), true);
        Set<String> existsCodes = selectExistingValues(codeCount.keySet(), false);

        List<CheckedRow> result = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            result.add(checkRow(row, categoryByName, unitByName, brandByName,
                    parentIds, barcodeCount, codeCount, existsBarcodes, existsCodes));
        }
        return result;
    }

    private List<ParsedRow> parse(InputStream in) {
        List<ParsedRow> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            if (head == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "模板表头为空，请下载标准模板填写");
            }
            DataFormatter fmt = new DataFormatter();
            for (int i = 0; i < COLUMNS.length; i++) {
                String actual = fmt.formatCellValue(head.getCell(i)).trim();
                if (!COLUMNS[i].equals(actual)) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "第 " + (i + 1) + " 列表头应为《" + COLUMNS[i] + "》，实际为《" + actual + "》，请使用标准模板");
                }
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> data = new LinkedHashMap<>();
                boolean blank = true;
                for (int i = 0; i < COLUMNS.length; i++) {
                    String v = fmt.formatCellValue(row.getCell(i)).trim();
                    data.put(COLUMNS[i], v);
                    blank = blank && v.isEmpty();
                }
                if (blank) {
                    continue;
                }
                // 示例行跳过
                String name = data.get("商品名称*");
                if (name != null && name.startsWith("示例")) {
                    continue;
                }
                if (rows.size() >= MAX_IMPORT_ROWS) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "单次导入不超过 " + MAX_IMPORT_ROWS + " 行，请分批上传");
                }
                rows.add(new ParsedRow(r + 1, data));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "无法解析文件，请上传按标准模板填写的 xlsx 文件");
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "文件中没有可导入的数据行");
        }
        return rows;
    }

    /** 库内已存在的条码/编码集合（分批 in 查询，含停用商品，BR-GOODS-01） */
    private Set<String> selectExistingValues(Collection<String> values, boolean barcode) {
        if (values.isEmpty()) {
            return Set.of();
        }
        List<String> list = new ArrayList<>(values);
        Set<String> exists = new HashSet<>();
        for (int i = 0; i < list.size(); i += 500) {
            List<String> batch = list.subList(i, Math.min(i + 500, list.size()));
            List<Goods> found = goodsMapper.selectList(Wrappers.<Goods>lambdaQuery()
                    .select(barcode ? Goods::getBarcode : Goods::getGoodsCode)
                    .in(barcode ? Goods::getBarcode : Goods::getGoodsCode, batch));
            found.forEach(g -> exists.add(barcode ? g.getBarcode() : g.getGoodsCode()));
        }
        return exists;
    }

    private CheckedRow checkRow(ParsedRow row, Map<String, List<Category>> categoryByName,
                                Map<String, Unit> unitByName, Map<String, Brand> brandByName,
                                Set<Long> parentIds, Map<String, Integer> barcodeCount,
                                Map<String, Integer> codeCount,
                                Set<String> existsBarcodes, Set<String> existsCodes) {
        List<String> errors = new ArrayList<>();
        String name = row.val("商品名称*");
        String code = row.val("商品编码");
        String barcode = row.val("商品条码");
        String categoryName = row.val("分类名称*");
        String unitName = row.val("单位名称*");
        String brandName = row.val("品牌名称");

        if (name.isEmpty()) {
            errors.add("商品名称不能为空");
        } else if (name.length() > 100) {
            errors.add("商品名称不能超过100字");
        }
        if (!code.isEmpty() && !code.matches("[A-Za-z0-9\\-]+")) {
            errors.add("商品编码只能包含字母、数字或中划线");
        } else if (!code.isEmpty()) {
            if (existsCodes.contains(code)) {
                errors.add("商品编码【" + code + "】已存在");
            } else if (codeCount.get(code) > 1) {
                errors.add("商品编码【" + code + "】在文件内重复");
            }
        }
        if (!barcode.isEmpty()) {
            if (!barcode.chars().allMatch(Character::isDigit)) {
                errors.add("商品条码只能包含数字");
            } else if (barcode.length() == 13 && !Ean13Util.isValid(barcode)) {
                errors.add("13 位条码的 EAN-13 校验位不正确");
            } else if (existsBarcodes.contains(barcode)) {
                errors.add("商品条码【" + barcode + "】已存在（含停用商品）");
            } else if (barcodeCount.get(barcode) > 1) {
                errors.add("商品条码【" + barcode + "】在文件内重复");
            }
        }

        // 分类：须为启用的叶子分类，同名叶子多个时报不唯一
        Long categoryId = null;
        if (categoryName.isEmpty()) {
            errors.add("分类名称不能为空");
        } else {
            List<Category> matches = categoryByName.getOrDefault(categoryName, List.of());
            List<Category> leaves = matches.stream()
                    .filter(c -> c.getStatus() != null && c.getStatus() == 1
                            && !parentIds.contains(c.getId()))
                    .toList();
            if (leaves.isEmpty()) {
                errors.add("分类【" + categoryName + "】不存在、已停用或不是叶子分类");
            } else if (leaves.size() > 1) {
                errors.add("分类名称【" + categoryName + "】不唯一，请联系管理员整理分类");
            } else {
                categoryId = leaves.get(0).getId();
            }
        }

        Long unitId = null;
        Unit unit = null;
        if (unitName.isEmpty()) {
            errors.add("单位名称不能为空");
        } else {
            unit = unitByName.get(unitName);
            if (unit == null || unit.getStatus() == null || unit.getStatus() != 1) {
                errors.add("单位【" + unitName + "】不存在或已停用");
            } else {
                unitId = unit.getId();
            }
        }

        Long brandId = null;
        if (!brandName.isEmpty()) {
            Brand brand = brandByName.get(brandName);
            if (brand == null || brand.getStatus() == null || brand.getStatus() != 1) {
                errors.add("品牌【" + brandName + "】不存在或已停用");
            } else {
                brandId = brand.getId();
            }
        }

        BigDecimal purchasePrice = decimal(row.val("进货参考价"), "进货参考价", errors);
        BigDecimal retailPrice = decimal(row.val("零售单价*"), "零售单价", errors);
        BigDecimal memberPrice = decimal(row.val("会员价"), "会员价", errors);
        if (retailPrice != null && retailPrice.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("零售单价必须大于0");
        }
        if (purchasePrice != null && purchasePrice.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("进货参考价不能为负数");
        }
        if (memberPrice != null && retailPrice != null && memberPrice.compareTo(retailPrice) > 0) {
            errors.add("会员价不能高于零售单价");
        }

        Integer isWeighed = bool(row.val("是否称重"), "是否称重", errors, 0);
        Integer allowReturn = bool(row.val("是否允许退货"), "是否允许退货", errors, 1);
        String pluCode = row.val("PLU码");
        if (isWeighed != null && isWeighed == 1) {
            if (unit != null && (unit.getIsWeight() == null || unit.getIsWeight() != 1)) {
                errors.add("称重商品的单位必须为重量单位（kg/g）");
            }
            if (pluCode.isEmpty()) {
                errors.add("称重商品必须填写 PLU 码");
            }
        } else if (!pluCode.isEmpty() && pluCode.length() > 10) {
            errors.add("PLU码不能超过10字");
        }

        BigDecimal lower = decimal(row.val("安全库存下限"), "安全库存下限", errors);
        BigDecimal upper = decimal(row.val("库存上限"), "库存上限", errors);
        BigDecimal initialStock = decimal(row.val("初始库存"), "初始库存", errors);
        if (lower != null && lower.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("安全库存下限不能为负数");
        }
        if (upper != null && upper.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("库存上限不能为负数");
        }
        if (initialStock != null && initialStock.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("初始库存不能为负数");
        }
        if (lower != null && upper != null && upper.compareTo(lower) < 0) {
            errors.add("库存上限不能小于安全库存下限");
        }
        if (isWeighed == null || isWeighed == 0) {
            checkIntegral(lower, "安全库存下限", errors);
            checkIntegral(upper, "库存上限", errors);
            checkIntegral(initialStock, "初始库存", errors);
        }
        String remark = row.val("备注");
        if (remark.length() > 500) {
            errors.add("备注不能超过500字");
        }

        if (!errors.isEmpty()) {
            return new CheckedRow(row, errors, null);
        }
        GoodsCreateRequest request = new GoodsCreateRequest(
                name, categoryId, unitId, brandId,
                emptyToNull(row.val("规格")), emptyToNull(row.val("产地")),
                purchasePrice, retailPrice, memberPrice,
                emptyToNull(barcode), emptyToNull(code), emptyToNull(pluCode),
                isWeighed, allowReturn, lower, upper, null, initialStock, emptyToNull(remark));
        return new CheckedRow(row, errors, request);
    }

    private BigDecimal decimal(String value, String field, List<String> errors) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(value);
            if (v.stripTrailingZeros().scale() > 3) {
                errors.add(field + "最多3位小数");
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(field + "【" + value + "】不是合法数字");
            return null;
        }
    }

    /** 是/否（或 1/0）解析；空值返回默认；非法值报错返回 null */
    private Integer bool(String value, String field, List<String> errors, int defaultValue) {
        if (value.isEmpty()) {
            return defaultValue;
        }
        return switch (value) {
            case "是", "1", "Y", "y" -> 1;
            case "否", "0", "N", "n" -> 0;
            default -> {
                errors.add(field + "只能填：是 / 否");
                yield null;
            }
        };
    }

    private void checkIntegral(BigDecimal value, String field, List<String> errors) {
        if (value != null && value.stripTrailingZeros().scale() > 0) {
            errors.add("非称重商品的" + field + "必须为整数");
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
