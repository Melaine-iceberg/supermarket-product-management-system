package com.smms.common.aspect;

import com.smms.common.annotation.RequirePerm;
import com.smms.common.util.RequestUtils;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 接口权限校验切面（BR-SYS-05、NFR-SEC-02）：
 * 后端对每个标注 @RequirePerm 的接口独立校验；
 * 越权访问抛出 403 并记录安全日志。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final OperationLogService operationLogService;

    @Before("@annotation(requirePerm)")
    public void check(RequirePerm requirePerm) {
        LoginUser user = SecurityUtils.getLoginUserOrNull();
        if (user == null) {
            throw new AccessDeniedException("未登录");
        }
        String code = requirePerm.value();
        if (user.permCodes().contains(code)) {
            return;
        }
        // 越权访问：记录安全日志（NFR-SEC-08 安全事件查询视图）
        OperationLog sec = new OperationLog();
        sec.setUserId(user.userId());
        sec.setUsername(user.username());
        sec.setRoleNames(String.join(",", user.roleNames()));
        sec.setModule("安全审计");
        sec.setAction("越权访问");
        sec.setTarget(code);
        sec.setResult(0);
        sec.setErrorMsg("无权限执行该操作");
        sec.setOperateTime(LocalDateTime.now());
        HttpServletRequest request = RequestUtils.currentRequest();
        if (request != null) {
            sec.setHttpMethod(request.getMethod());
            sec.setRequestUrl(request.getRequestURI());
            sec.setIp(RequestUtils.getClientIp(request));
            sec.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
        }
        operationLogService.recordAsync(sec);
        throw new AccessDeniedException("无权限执行该操作");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
