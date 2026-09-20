package com.smms.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.sale.dto.ReturnOrderVO;
import com.smms.sale.dto.ReturnQuery;
import com.smms.sale.entity.ReturnOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReturnOrderMapper extends BaseMapper<ReturnOrder> {

    /**
     * 行锁读取当日该收银台最大退货单号（BR-SALE-02，XT 前缀独立流水）：
     * 与销售单号同理，前缀范围加锁串行取号。
     */
    String selectLastReturnNoForUpdate(@Param("prefix") String prefix);

    /** 退货单分页（FR-SALE-10）：关联原销售单号与会员，退货时间倒序 */
    IPage<ReturnOrderVO> selectReturnPage(Page<ReturnOrderVO> page, @Param("q") ReturnQuery q);
}
