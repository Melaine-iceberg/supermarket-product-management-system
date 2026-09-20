package com.smms.member.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.member.dto.MemberCreateRequest;
import com.smms.member.dto.MemberImportReportVO;
import com.smms.member.dto.MemberImportResultVO;
import com.smms.member.dto.MemberImportRowVO;
import com.smms.member.entity.Member;
import com.smms.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 会员 Excel 批量导入服务（FR-MEM-03，复用 FR-GOODS-11 的
 * "全量校验—确认—部分提交"机制，BR-GOODS-07）：
 * 校验阶段不写库；提交阶段在同一事务内仅写入通过行，失败行返回明细供修正重传。
 */
@Service
@RequiredArgsConstructor
public class MemberExcelService {

    /** 模板列（顺序固定，解析按列索引，* 为必填） */
    public static final String[] COLUMNS = {"姓名*", "手机号*", "性别", "生日", "地址", "备注"};

    private static final int MAX_IMPORT_ROWS = 5000;
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    /** STRICT 解析：杜绝 2 月 30 日这类被 SMART 静默纠正的非法日期 */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT));

    private final MemberMapper memberMapper;
    private final MemberService memberService;

    /** 解析后的原始行（Excel 行号从 1 开始计数，与用户视角一致） */
    private record ParsedRow(int rowNum, Map<String, String> data) {
        String val(String column) {
            String v = data.get(column);
            return v == null ? "" : v.trim();
        }
    }

    /** 校验后的行：错误列表 + 通过时可直接提交的请求对象 */
    private record CheckedRow(ParsedRow row, List<String> errors, MemberCreateRequest request) {
    }

    // ==================== 模板下载（FR-MEM-03） ====================

    /** 标准模板：表头 + 示例行（导入时自动跳过"示例"行）+ 填写说明 */
    public byte[] template() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("会员导入");
            var headStyle = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            headStyle.setFont(font);

            Row head = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                var cell = head.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(headStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }
            Row demo = sheet.createRow(1);
            String[] values = {"示例：张三（导入时自动跳过本行）", "13800000000", "女", "1990-01-01",
                    "南京市玄武区中山路1号", "导入示例行"};
            for (int i = 0; i < values.length; i++) {
                demo.createCell(i).setCellValue(values[i]);
            }

            Sheet tips = wb.createSheet("填写说明");
            String[] lines = {
                    "1. 请勿修改第一行表头；第 2 行为示例行，导入时自动跳过，也可自行删除。",
                    "2. 带 * 的列为必填：姓名、手机号。",
                    "3. 手机号须为大陆 11 位手机号，且不得与库内已有会员（含停用）或文件内其他行重复。",
                    "4. 性别填：男 / 女（可留空）。",
                    "5. 生日格式：yyyy-MM-dd（如 1990-01-01，可留空）。",
                    "6. 新会员初始等级为门槛最低的启用等级（普通会员），积分与储值余额为 0。",
                    "7. 上传后系统先全量校验并生成报告，确认后仅导入校验通过的行。"
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

    // ==================== 全量校验（不写库，BR-GOODS-07） ====================

    /** 上传校验：解析并逐行校验，返回报告（成功 N / 失败 M + 逐行失败原因） */
    public MemberImportReportVO validate(InputStream in) {
        List<CheckedRow> checked = parseAndCheck(in);
        List<MemberImportRowVO> rows = checked.stream()
                .map(c -> new MemberImportRowVO(c.row().rowNum(), c.errors().isEmpty(),
                        c.errors(), c.row().data()))
                .toList();
        int failed = (int) rows.stream().filter(r -> !r.passed()).count();
        return new MemberImportReportVO(rows.size(), rows.size() - failed, failed, rows);
    }

    // ==================== 提交导入（单事务、部分提交） ====================

    /**
     * 提交导入：重新校验同一文件（无状态，防篡改），单事务内仅写入通过行；
     * 任一行写入异常则整体回滚，避免半成功状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberImportResultVO commit(InputStream in, String operatorRealName) {
        List<CheckedRow> checked = parseAndCheck(in);
        List<MemberCreateRequest> pass = checked.stream()
                .filter(c -> c.errors().isEmpty())
                .map(CheckedRow::request)
                .toList();
        for (MemberCreateRequest req : pass) {
            // 复用注册逻辑（编号生成、默认等级），校验兜底重复执行无害
            memberService.create(req, operatorRealName);
        }
        List<MemberImportRowVO> failedRows = checked.stream()
                .filter(c -> !c.errors().isEmpty())
                .map(c -> new MemberImportRowVO(c.row().rowNum(), false, c.errors(), c.row().data()))
                .toList();
        return new MemberImportResultVO(pass.size(), failedRows.size(), failedRows);
    }

    // ==================== 解析与校验 ====================

    /** 解析 Excel 并逐行校验；表头不符/无法解析时抛参数异常 */
    private List<CheckedRow> parseAndCheck(InputStream in) {
        List<ParsedRow> rows = parse(in);

        // 文件内手机号重复检测 + 库内已存在检测（含停用会员，BR-MEM-01；分批 in 查询）
        Map<String, Integer> phoneCount = new HashMap<>();
        for (ParsedRow row : rows) {
            String phone = row.val("手机号*");
            if (!phone.isEmpty()) {
                phoneCount.merge(phone, 1, Integer::sum);
            }
        }
        Set<String> existsPhones = selectExistingPhones(phoneCount.keySet());

        List<CheckedRow> result = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            result.add(checkRow(row, phoneCount, existsPhones));
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
                String name = data.get("姓名*");
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

    /** 库内已注册的手机号集合（分批 in 查询） */
    private Set<String> selectExistingPhones(Collection<String> phones) {
        if (phones.isEmpty()) {
            return Set.of();
        }
        List<String> list = new ArrayList<>(phones);
        Set<String> exists = new HashSet<>();
        for (int i = 0; i < list.size(); i += 500) {
            List<String> batch = list.subList(i, Math.min(i + 500, list.size()));
            memberMapper.selectList(Wrappers.<Member>lambdaQuery()
                            .select(Member::getPhone)
                            .in(Member::getPhone, batch))
                    .forEach(m -> exists.add(m.getPhone()));
        }
        return exists;
    }

    private CheckedRow checkRow(ParsedRow row, Map<String, Integer> phoneCount, Set<String> existsPhones) {
        List<String> errors = new ArrayList<>();
        String name = row.val("姓名*");
        String phone = row.val("手机号*");

        if (name.isEmpty()) {
            errors.add("姓名不能为空");
        } else if (name.length() > 30) {
            errors.add("姓名不能超过30字");
        }
        if (phone.isEmpty()) {
            errors.add("手机号不能为空");
        } else if (!PHONE.matcher(phone).matches()) {
            errors.add("手机号【" + phone + "】不是合法的大陆 11 位手机号");
        } else if (existsPhones.contains(phone)) {
            errors.add("手机号【" + phone + "】已注册会员（含停用）");
        } else if (phoneCount.get(phone) > 1) {
            errors.add("手机号【" + phone + "】在文件内重复");
        }

        Integer gender = null;
        String genderVal = row.val("性别");
        if (!genderVal.isEmpty()) {
            gender = switch (genderVal) {
                case "男", "1" -> 1;
                case "女", "2" -> 2;
                default -> {
                    errors.add("性别只能填：男 / 女");
                    yield null;
                }
            };
        }

        LocalDate birthday = null;
        String birthdayVal = row.val("生日");
        if (!birthdayVal.isEmpty()) {
            birthday = parseDate(birthdayVal);
            if (birthday == null) {
                errors.add("生日【" + birthdayVal + "】格式不正确，应为 yyyy-MM-dd");
            } else if (birthday.isAfter(LocalDate.now())) {
                errors.add("生日不能晚于当天");
            }
        }

        String address = row.val("地址");
        if (address.length() > 200) {
            errors.add("地址不能超过200字");
        }
        String remark = row.val("备注");
        if (remark.length() > 200) {
            errors.add("备注不能超过200字");
        }

        if (!errors.isEmpty()) {
            return new CheckedRow(row, errors, null);
        }
        MemberCreateRequest req = new MemberCreateRequest();
        req.setName(name);
        req.setPhone(phone);
        req.setGender(gender);
        req.setBirthday(birthday);
        req.setAddress(address.isEmpty() ? null : address);
        req.setRemark(remark.isEmpty() ? null : remark);
        return new CheckedRow(row, errors, req);
    }

    private LocalDate parseDate(String value) {
        // Excel 数值日期单元格（如 36526）由 DataFormatter 按数值给出，需单独处理
        try {
            BigDecimal num = new BigDecimal(value);
            if (num.scale() <= 0 && num.compareTo(BigDecimal.ZERO) > 0) {
                return LocalDate.of(1899, 12, 30).plusDays(num.longValueExact());
            }
        } catch (NumberFormatException ignored) {
            // 非数值，继续按文本解析
        }
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, fmt);
            } catch (Exception ignored) {
                // 尝试下一格式
            }
        }
        return null;
    }
}
