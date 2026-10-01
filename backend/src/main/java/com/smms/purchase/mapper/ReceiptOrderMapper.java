package com.smms.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.purchase.entity.ReceiptOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 入库单 Mapper（FR-PUR-06/07）。 */
@Mapper
public interface ReceiptOrderMapper extends BaseMapper<ReceiptOrder> {

    /** 当前最大入库单号（BR-PUR-01）：按前缀行锁取号 */
    String selectLastOrderNoForUpdate(@Param("prefix") String prefix);
}
