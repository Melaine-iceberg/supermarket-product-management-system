package com.smms.sys.dto;

import java.util.List;

/**
 * 数据字典视图（FR-SYS-07）：类型 + 字典项列表。
 */
public record DictVO(
        Long id,
        String dictType,
        String dictName,
        String remark,
        List<ItemVO> items
) {
    public record ItemVO(
            Long id,
            String label,
            String value,
            Integer sortNo,
            Integer status,
            String remark
    ) {
    }
}
