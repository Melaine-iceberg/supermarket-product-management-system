package com.smms.inv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.inv.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {
}
