package com.smms.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志声明（FR-SYS-08）：标注在写操作接口上，由 AOP 自动留痕。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpLog {

    /** 模块名，如 系统管理 */
    String module();

    /** 动作名，如 新增用户 */
    String action();

    /** 目标对象标识，支持 SpEL，如 "#req.username"、#id */
    String target() default "";
}
