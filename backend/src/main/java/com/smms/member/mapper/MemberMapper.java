package com.smms.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.member.dto.MemberConsumeVO;
import com.smms.member.entity.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemberMapper extends BaseMapper<Member> {

    /** 当前最大会员编号（BR-MEM-02）：行锁串行化取号 */
    String selectLastMemberNoForUpdate();

    /** 会员行锁加载（储值扣减/积分累计并发防护，BR-MEM-07） */
    Member selectByIdForUpdate(@Param("id") Long id);

    /** 会员消费记录（FR-MEM-04）：销售单与退货单按时间倒序合并分页 */
    Page<MemberConsumeVO> selectConsumePage(Page<MemberConsumeVO> page,
                                            @Param("memberId") Long memberId,
                                            @Param("begin") String begin,
                                            @Param("end") String end);
}
