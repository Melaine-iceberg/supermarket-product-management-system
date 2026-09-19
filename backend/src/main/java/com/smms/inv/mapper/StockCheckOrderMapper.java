package com.smms.inv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.dto.CheckQuery;
import com.smms.inv.entity.StockCheckOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 盘点单 Mapper（DR-13，FR-INV-07）：列表分页（含录入进度/差异行数统计），
 * SQL 见 mapper/inv/StockCheckMapper.xml。
 */
@Mapper
public interface StockCheckOrderMapper extends BaseMapper<StockCheckOrder> {

    /** 盘点单分页：单号/状态/盘点人/审核人/时间区间组合筛选，创建时间倒序 */
    IPage<CheckOrderVO> selectCheckPage(Page<CheckOrderVO> page,
                                         @Param("q") CheckQuery q,
                                         @Param("beginTime") LocalDateTime beginTime,
                                         @Param("endTime") LocalDateTime endTime);
}
