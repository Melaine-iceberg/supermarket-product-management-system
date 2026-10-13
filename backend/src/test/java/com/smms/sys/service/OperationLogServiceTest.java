package com.smms.sys.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.mapper.OperationLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 操作日志导出单测（GAP-P1-06）：超过 export_max_rows 时明确报错提示缩小范围，
 * 不再静默截断为 maxRows 行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperationLogServiceTest {

    @Mock
    private OperationLogMapper logMapper;

    @Mock
    private SysConfigService configService;

    private OperationLogService service;

    @BeforeEach
    void setUp() {
        service = new OperationLogService(logMapper, configService);
        when(configService.getInt("export_max_rows", 50_000)).thenReturn(3);
    }

    private OperationLog log(String username, String realName) {
        OperationLog log = new OperationLog();
        log.setUsername(username);
        log.setRealName(realName);
        log.setModule("统计报表");
        log.setAction("导出销售报表");
        log.setResult(1);
        log.setOperateTime(LocalDateTime.of(2026, 3, 1, 10, 0));
        return log;
    }

    @Test
    @DisplayName("日志导出超限：抛业务异常提示缩小范围（而非静默截断，GAP-P1-06）")
    void exportCsvRejectsOverLimit() {
        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                log("manager", "王店长"), log("manager", "王店长"),
                log("manager", "王店长"), log("manager", "王店长")));

        assertThatThrownBy(() -> service.exportCsv(null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过上限 3 行")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("日志导出未超限：CSV 含 BOM/表头/真实姓名（BR-SYS-07）")
    void exportCsvWithinLimit() {
        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log("manager", "王店长")));

        String csv = service.exportCsv(null, null, null, null, null);
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("操作时间,操作人,真实姓名");
        assertThat(csv).contains("王店长");
    }
}
