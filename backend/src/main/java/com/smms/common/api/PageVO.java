package com.smms.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页结果（IR-COM-04）：列表接口须返回总条数以支持前端分页。
 */
public record PageVO<T>(List<T> list, long total) {

    public static <T> PageVO<T> of(IPage<T> page) {
        return new PageVO<>(page.getRecords(), page.getTotal());
    }
}
