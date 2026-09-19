package com.smms.goods.dto;

import java.util.List;

/**
 * 导入提交结果（FR-GOODS-11、BR-GOODS-07）：提交阶段在单事务内
 * 仅写入校验通过的行；失败行返回明细供导出修正后重传。
 */
public record GoodsImportResultVO(
        int imported,
        int failed,
        List<GoodsImportRowVO> failedRows
) {
}
