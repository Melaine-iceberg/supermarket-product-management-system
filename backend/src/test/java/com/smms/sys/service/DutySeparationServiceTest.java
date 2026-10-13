package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.LoginUser;
import com.smms.sys.entity.OperationLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 职责分离统一策略单元测试（GAP-P1-08，BR-SYS-01）：
 * 严格模式拒绝、非严格模式放行并记异常日志、超级管理员代处理（无视严格模式但留痕），
 * 异常日志同时记录账号与真实姓名（BR-SYS-07）。
 */
@ExtendWith(MockitoExtension.class)
class DutySeparationServiceTest {

    private static final String CFG = DutySeparationService.CFG_DUTY_SEPARATION_STRICT;

    @Mock private SysConfigService configService;
    @Mock private OperationLogService operationLogService;

    private DutySeparationService service;

    private final LoginUser purchaser = new LoginUser(9L, "buyer", "李采购",
            List.of("PURCHASER"), List.of("采购员"), Set.of(), "fp", false);
    private final LoginUser superAdmin = new LoginUser(1L, "admin", "系统管理员",
            List.of("SUPER_ADMIN"), List.of("超级管理员"), Set.of(), "fp", false);

    @BeforeEach
    void setUp() {
        service = new DutySeparationService(configService, operationLogService);
    }

    private void enforce(LoginUser operator) {
        service.enforce("采购管理", "入库验收职责未分离",
                "采购单 PO202610130001 验收人与创建人同为 李采购",
                ErrorCode.PUR_RECEIPT_DUTY_SEPARATION, operator);
    }

    @Test
    @DisplayName("严格模式：非超管违反职责分离被拒，且不写异常日志")
    void strictModeRejectsNonSuperAdmin() {
        when(configService.getBool(CFG, false)).thenReturn(true);

        assertThatThrownBy(() -> enforce(purchaser))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RECEIPT_DUTY_SEPARATION.getCode());
        verify(operationLogService, never()).recordAsync(any());
    }

    @Test
    @DisplayName("非严格模式：放行并记录异常日志（含账号与真实姓名）")
    void lenientModeLogsAnomaly() {
        when(configService.getBool(CFG, false)).thenReturn(false);

        enforce(purchaser);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogService).recordAsync(captor.capture());
        OperationLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("职责未分离告警");
        assertThat(log.getUsername()).isEqualTo("buyer");
        assertThat(log.getRealName()).isEqualTo("李采购"); // BR-SYS-07/GAP-P1-10
        assertThat(log.getParamSummary()).contains("PO202610130001");
    }

    @Test
    @DisplayName("超级管理员代处理：无视严格模式放行，但记录代处理日志")
    void superAdminOverridesStrictWithLog() {
        enforce(superAdmin); // 超管不读取严格模式参数

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogService).recordAsync(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("职责未分离（超级管理员代处理）");
    }
}
