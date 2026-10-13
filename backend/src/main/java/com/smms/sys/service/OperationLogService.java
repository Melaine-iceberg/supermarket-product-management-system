package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 操作日志服务（FR-SYS-08）：所有写操作留痕，只增不改不删；
 * 日志写入异步执行，失败不影响业务主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OperationLogMapper logMapper;
    private final SysConfigService configService;

    /** 异步写入操作日志 */
    @Async("logExecutor")
    public void recordAsync(OperationLog entity) {
        try {
            if (entity.getOperateTime() == null) {
                entity.setOperateTime(LocalDateTime.now());
            }
            logMapper.insert(entity);
        } catch (Exception e) {
            // 日志失败不影响业务，但必须留下痕迹（NFR-REL-07）
            log.error("操作日志写入失败: user={}, module={}, action={}",
                    entity.getUsername(), entity.getModule(), entity.getAction(), e);
        }
    }

    /** 分页查询：按操作人/模块/结果/时间过滤（FR-SYS-08） */
    public PageVO<OperationLog> page(String username, String module, Integer result,
                                     LocalDate beginDate, LocalDate endDate, int page, int size) {
        Page<OperationLog> pager = logMapper.selectPage(
                Page.of(page, size),
                Wrappers.<OperationLog>lambdaQuery()
                        .like(username != null && !username.isBlank(), OperationLog::getUsername, username)
                        .eq(module != null && !module.isBlank(), OperationLog::getModule, module)
                        .eq(result != null, OperationLog::getResult, result)
                        .ge(beginDate != null, OperationLog::getOperateTime, beginDate == null ? null : beginDate.atStartOfDay())
                        .lt(endDate != null, OperationLog::getOperateTime, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                        .orderByDesc(OperationLog::getId));
        return PageVO.of(pager);
    }

    /** 导出 CSV（UTF-8 BOM，NFR-CMP-04），上限取参数 export_max_rows */
    public String exportCsv(String username, String module, Integer result,
                            LocalDate beginDate, LocalDate endDate) {
        int maxRows = configService.getInt("export_max_rows", 50_000);
        List<OperationLog> rows = logMapper.selectList(
                Wrappers.<OperationLog>lambdaQuery()
                        .like(username != null && !username.isBlank(), OperationLog::getUsername, username)
                        .eq(module != null && !module.isBlank(), OperationLog::getModule, module)
                        .eq(result != null, OperationLog::getResult, result)
                        .ge(beginDate != null, OperationLog::getOperateTime, beginDate == null ? null : beginDate.atStartOfDay())
                        .lt(endDate != null, OperationLog::getOperateTime, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                        .orderByDesc(OperationLog::getId)
                        .last("LIMIT " + maxRows));
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM 保证 Excel 中文不乱码
        sb.append("操作时间,操作人,真实姓名,角色,模块,动作,目标对象,请求方法,请求URL,结果,错误信息,耗时(ms),IP\r\n");
        for (OperationLog r : rows) {
            sb.append(csv(r.getOperateTime() == null ? "" : r.getOperateTime().format(TS))).append(',')
                    .append(csv(r.getUsername())).append(',')
                    .append(csv(r.getRealName())).append(',')
                    .append(csv(r.getRoleNames())).append(',')
                    .append(csv(r.getModule())).append(',')
                    .append(csv(r.getAction())).append(',')
                    .append(csv(r.getTarget())).append(',')
                    .append(csv(r.getHttpMethod())).append(',')
                    .append(csv(r.getRequestUrl())).append(',')
                    .append(r.getResult() != null && r.getResult() == 1 ? "成功" : "失败").append(',')
                    .append(csv(r.getErrorMsg())).append(',')
                    .append(r.getCostMs() == null ? "" : r.getCostMs()).append(',')
                    .append(csv(r.getIp())).append("\r\n");
        }
        return sb.toString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** 模块下拉选项（前端筛选用） */
    public List<String> listModules() {
        return logMapper.selectModules();
    }
}
