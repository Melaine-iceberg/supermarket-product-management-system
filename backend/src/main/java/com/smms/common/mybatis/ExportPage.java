package com.smms.common.mybatis;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 导出专用分页对象（GAP-P0-01 / FR-RPT-08 / IR-SW-04）。
 *
 * <p>导出查询以 {@code export_max_rows + 1} 作为页大小，用于"是否超过 5 万行上限"的判定；
 * 该页大小必须绕过全局单页上限保护（{@link com.smms.config.MybatisPlusConfig} 的
 * {@code maxLimit=500}，对应 IR-COM-04 的"单页最大 500 条"），否则结果会被静默截断为 500 行，
 * 且 {@code checkLimit} 永远无法触发。
 *
 * <p>MyBatis-Plus 分页插件在 {@code beforeQuery} 中优先读取 {@code IPage#maxLimit()} 作为页大小上限，
 * 本类覆写为 {@link Long#MAX_VALUE} 即声明"本页不参与单页上限截断"；
 * 超限处理由调用方（各导出服务/ReportExcelService.checkLimit）显式给出提示。
 *
 * @param <T> 行类型
 */
public class ExportPage<T> extends Page<T> {

    public ExportPage(long size) {
        super(1, size, false);
    }

    /** 优先级高于分页插件的全局 maxLimit（IR-COM-04）：导出页不受"单页最大 500 条"限制。 */
    @Override
    public Long maxLimit() {
        return Long.MAX_VALUE;
    }
}
