package com.smms.inv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.InventoryRecordQuery;
import com.smms.inv.dto.InventoryRecordVO;
import com.smms.inv.entity.InventoryRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 库存流水 Mapper：不可变流水（BR-INV-04 只增不改不删）的写入走 BaseMapper.insert，
 * 查询见 mapper/inv/InventoryRecordMapper.xml（FR-INV-03）。
 */
@Mapper
public interface InventoryRecordMapper extends BaseMapper<InventoryRecord> {

    /** 流水分页（FR-INV-03）：按商品/类型/时间区间/单据号/操作人查询，时间倒序 */
    IPage<InventoryRecordVO> selectRecordPage(Page<InventoryRecordVO> page,
                                              @Param("q") InventoryRecordQuery q,
                                              @Param("beginTime") LocalDateTime beginTime,
                                              @Param("endTime") LocalDateTime endTime);
}
