package com.smms.common.util;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 明细行空元素校验工具测试。
 *
 * <p>回归用例：{@code {"items":[null]}} 这类请求体不会被 Bean Validation 拦截，
 * 修复前会在服务层抛 NPE 并返回 HTTP 500（DEF-20261012-02）。
 */
class ItemRowsTest {

    @Test
    @DisplayName("含 null 元素时抛参数不合法（400），且提示带业务名")
    void rejectNullRows_throwsOnNullElement() {
        List<String> rows = new ArrayList<>();
        rows.add("A");
        rows.add(null);

        assertThatThrownBy(() -> ItemRows.rejectNullRows(rows, "结算明细"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结算明细")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("单个 null 元素同样被拒（items 只有一行且为 null 的场景）")
    void rejectNullRows_singleNull() {
        assertThatThrownBy(() -> ItemRows.rejectNullRows(Arrays.asList((String) null), "挂单明细"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("正常行与 null 列表原样返回，不改变空列表语义")
    void rejectNullRows_passesThrough() {
        List<String> rows = List.of("A", "B");
        assertThat(ItemRows.rejectNullRows(rows, "退货明细")).isSameAs(rows);
        assertThat(ItemRows.rejectNullRows(null, "退货明细")).isNull();
        assertThat(ItemRows.rejectNullRows(List.of(), "退货明细")).isEmpty();
    }
}
