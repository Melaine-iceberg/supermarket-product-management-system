package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.LoginUser;
import com.smms.sys.entity.OperationLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 职责分离统一策略（BR-SYS-01，GAP-P1-08）：
 * 采购单创建人 ≠ 入库验收人；盘点人 ≠ 盘点审核人。违反时按系统参数
 * {@code duty_separation_strict} 统一处理——拒绝，或放行但记录"职责未分离"异常日志；
 * 超级管理员可代为处理（代验/代审），同样须留痕（操作日志同时记录账号与真实姓名，BR-SYS-07）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DutySeparationService {

    /** 职责分离是否强制拒绝（BR-SYS-01，默认 false 放行但记异常日志） */
    public static final String CFG_DUTY_SEPARATION_STRICT = "duty_separation_strict";

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final SysConfigService configService;
    private final OperationLogService operationLogService;

    /**
     * 职责未分离的场景处理：
     * 严格模式拒绝（抛 {@code rejectCode}）；非严格模式放行并记异常日志；
     * 超级管理员代处理时无视严格模式放行，但同样记异常日志。
     *
     * @param module   操作日志模块（如 采购管理 / 库存管理）
     * @param scenario 场景名（如 入库验收职责未分离）
     * @param detail   异常详情（单号、当事人）
     * @param rejectCode 强制拒绝时的业务错误码
     * @param operator 当前操作人
     */
    public void enforce(String module, String scenario, String detail,
                        ErrorCode rejectCode, LoginUser operator) {
        boolean superAdmin = operator.roleCodes() != null
                && operator.roleCodes().contains(ROLE_SUPER_ADMIN);
        if (!superAdmin && configService.getBool(CFG_DUTY_SEPARATION_STRICT, false)) {
            throw new BusinessException(rejectCode);
        }
        OperationLog dutyLog = new OperationLog();
        dutyLog.setUserId(operator.userId());
        dutyLog.setUsername(operator.username());
        dutyLog.setRealName(operator.realName());
        dutyLog.setRoleNames(String.join(",", operator.roleNames()));
        dutyLog.setModule(module);
        dutyLog.setAction(superAdmin ? "职责未分离（超级管理员代处理）" : "职责未分离告警");
        dutyLog.setParamSummary(scenario + "：" + detail + "（BR-SYS-01）");
        dutyLog.setResult(1);
        dutyLog.setOperateTime(LocalDateTime.now());
        operationLogService.recordAsync(dutyLog);
        log.warn("{}：{}（BR-SYS-01）", scenario, detail);
    }
}
