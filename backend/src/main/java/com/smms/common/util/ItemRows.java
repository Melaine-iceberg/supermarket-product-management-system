package com.smms.common.util;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;

import java.util.List;
import java.util.Objects;

/**
 * 请求明细行校验工具。
 *
 * <p>JSON 请求体中形如 {@code {"items":[null]}} 的空元素不会被 Bean Validation 拦截
 * （{@code @Valid List<T>} 只校验非空元素），若服务层直接解引用就会抛 NPE 并返回 500。
 * 统一在遍历/映射之前调用本工具，把这类请求形状问题收敛为 {@link ErrorCode#PARAM_INVALID}。
 */
public final class ItemRows {

    private ItemRows() {
    }

    /**
     * 校验明细行列表不含 null 元素。仅校验「空行」，不校验列表是否为空——
     * 空列表的语义与错误码由各服务自行定义（如采购明细为空返回 PUR_LINE_INVALID）。
     *
     * @param rows  明细行列表，允许为 null
     * @param label 错误提示中的业务名，如「结算明细」
     * @return 原列表（便于链式使用）
     * @throws BusinessException 存在 null 元素时抛 {@link ErrorCode#PARAM_INVALID}
     */
    public static <T> List<T> rejectNullRows(List<T> rows, String label) {
        if (rows != null && rows.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    label + "存在空行（items 内含 null），请检查后重新提交");
        }
        return rows;
    }
}
