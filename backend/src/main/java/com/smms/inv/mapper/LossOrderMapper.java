package com.smms.inv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.LossOrderQuery;
import com.smms.inv.dto.LossOrderVO;
import com.smms.inv.entity.LossOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 报损/报溢单 Mapper（DR-15，FR-INV-10）：列表分页与详情（含商品/字典原因关联），
 * SQL 见 mapper/inv/LossOrderMapper.xml。
 */
@Mapper
public interface LossOrderMapper extends BaseMapper<LossOrder> {

    /** 报损/报溢单分页：单号/类型/状态/商品关键字/申请人/时间区间组合筛选，申请时间倒序 */
    IPage<LossOrderVO> selectLossPage(Page<LossOrderVO> page,
                                      @Param("q") LossOrderQuery q,
                                      @Param("beginTime") LocalDateTime beginTime,
                                      @Param("endTime") LocalDateTime endTime);

    /** 报损/报溢单详情（单商品） */
    LossOrderVO selectLossDetail(@Param("id") Long id);
}
