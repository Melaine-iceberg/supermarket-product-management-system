package com.smms.goods.dto;

import java.util.List;
import java.util.Map;

/**
 * 导入单行结果（FR-GOODS-11）：行号、是否通过、失败原因列表与原始行数据
 * （失败行可导出修正后重传，BR-GOODS-07）。
 */
public record GoodsImportRowVO(
        int rowNum,
        boolean passed,
        List<String> errors,
        /** 原始行数据（列名 → 单元格文本），保持模板列序 */
        Map<String, String> data
) {
}
