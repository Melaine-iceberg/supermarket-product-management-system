package com.smms.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限声明（BR-SYS-05 双重校验原则）：
 * 后端对每个接口独立校验权限（多值时为"任一满足"）；越权访问返回 403 并记录安全日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 权限标识（如 sys:user:create）；多值表示任一权限即可访问（GAP-P0-05/06 同族的只读接口复用） */
    String[] value();
}
