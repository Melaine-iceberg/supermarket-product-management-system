package com.smms.common.aspect;

import com.smms.common.annotation.OpLog;
import com.smms.common.util.RequestUtils;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 操作日志切面（FR-SYS-08）：自动记录写操作的操作人、角色、时间、IP、模块、
 * 动作、目标对象、参数摘要（脱敏）、结果、错误信息与耗时。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    /** 参数摘要中的敏感字段脱敏（BR-SYS-09/NFR-SEC-07：日志不记录明文密码） */
    private static final Pattern SENSITIVE =
            Pattern.compile("(\"(?:password|oldPassword|newPassword|pwd|token)\"\\s*:\\s*)\"[^\"]*\"",
                    Pattern.CASE_INSENSITIVE);

    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();

    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OpLog opLog) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                record(pjp, opLog, start, error);
            } catch (Exception e) {
                log.warn("操作日志记录失败: {}", e.getMessage());
            }
        }
    }

    private void record(ProceedingJoinPoint pjp, OpLog opLog, long start, Throwable error) {
        OperationLog entity = new OperationLog();
        LoginUser user = SecurityUtils.getLoginUserOrNull();
        if (user != null) {
            entity.setUserId(user.userId());
            entity.setUsername(user.username());
            entity.setRoleNames(String.join(",", user.roleNames()));
        }
        entity.setModule(opLog.module());
        entity.setAction(opLog.action());
        entity.setTarget(resolveTarget(pjp, opLog.target()));
        HttpServletRequest request = RequestUtils.currentRequest();
        if (request != null) {
            entity.setHttpMethod(request.getMethod());
            entity.setRequestUrl(truncate(request.getRequestURI(), 200));
            entity.setIp(RequestUtils.getClientIp(request));
            entity.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
        }
        entity.setParamSummary(maskParams(pjp.getArgs()));
        entity.setResult(error == null ? 1 : 0);
        entity.setErrorMsg(error == null ? null : truncate(error.getMessage(), 500));
        entity.setCostMs((int) (System.currentTimeMillis() - start));
        entity.setOperateTime(LocalDateTime.now());
        operationLogService.recordAsync(entity);
    }

    /** 解析目标标识：支持 SpEL（如 #req.username、#id），空返回 null */
    private String resolveTarget(ProceedingJoinPoint pjp, String targetExpr) {
        if (targetExpr == null || targetExpr.isBlank() || !targetExpr.startsWith("#")) {
            return targetExpr == null || targetExpr.isBlank() ? null : truncate(targetExpr, 60);
        }
        try {
            Expression expression = expressionCache.computeIfAbsent(targetExpr, SPEL_PARSER::parseExpression);
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] paramNames = signature.getParameterNames();
            Object[] args = pjp.getArgs();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            Object value = expression.getValue(context);
            return value == null ? null : truncate(String.valueOf(value), 60);
        } catch (Exception e) {
            log.debug("OpLog target SpEL 解析失败: {} -> {}", targetExpr, e.getMessage());
            return null;
        }
    }

    /** 参数摘要：JSON 序列化 + 敏感字段脱敏，截断 1000 字符 */
    private String maskParams(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        String summary = Arrays.stream(args)
                .filter(arg -> arg != null
                        && !(arg instanceof ServletRequest)
                        && !(arg instanceof ServletResponse)
                        && !(arg instanceof MultipartFile)
                        && !(arg instanceof Throwable))
                .map(this::safeJson)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + "; " + b)
                .orElse(null);
        if (summary == null) {
            return null;
        }
        return truncate(SENSITIVE.matcher(summary).replaceAll("$1\"***\""), 1000);
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
