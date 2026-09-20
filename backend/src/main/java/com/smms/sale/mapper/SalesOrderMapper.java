package com.smms.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.sale.dto.SalesOrderQuery;
import com.smms.sale.dto.SalesOrderVO;
import com.smms.sale.entity.SalesOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface SalesOrderMapper extends BaseMapper<SalesOrder> {

    /**
     * 行锁读取当日该收银台最大单号（BR-SALE-02 单号串行生成）：
     * 事务内对单号前缀范围加锁，串行化同收银台并发结算，取号后插单防幻读冲突；
     * 当日无单返回 null。
     */
    String selectLastOrderNoForUpdate(@Param("prefix") String prefix);

    /** 销售单分页（FR-SALE-09）：多条件组合筛选，销售时间倒序；SQL 见 mapper/sale/SalesOrderMapper.xml */
    IPage<SalesOrderVO> selectOrderPage(Page<SalesOrderVO> page,
                                        @Param("q") SalesOrderQuery q,
                                        @Param("beginTime") LocalDateTime beginTime,
                                        @Param("endTime") LocalDateTime endTime);
}
