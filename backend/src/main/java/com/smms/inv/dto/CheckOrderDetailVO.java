package com.smms.inv.dto;

import com.smms.common.api.PageVO;

/**
 * 盘点单详情（FR-INV-07）：单头 + 明细分页（明细量大时分页加载，扫码定位用 keyword）。
 */
public record CheckOrderDetailVO(
        CheckOrderVO order,
        PageVO<CheckItemVO> items) {
}
