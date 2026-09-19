package com.smms.goods.dto;

import java.util.List;

/**
 * 导入校验报告（FR-GOODS-11）：上传后先全量校验（不写库，BR-GOODS-07），
 * 报告成功 N 条 / 失败 M 条 + 逐行失败原因。
 */
public record GoodsImportReportVO(
        int total,
        int passed,
        int failed,
        List<GoodsImportRowVO> rows
) {
}
