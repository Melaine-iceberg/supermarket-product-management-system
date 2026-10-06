package com.smms.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.sale.entity.HoldOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收银挂单 Mapper（DR-21，FR-SALE-08）。
 */
@Mapper
public interface HoldOrderMapper extends BaseMapper<HoldOrder> {
}
