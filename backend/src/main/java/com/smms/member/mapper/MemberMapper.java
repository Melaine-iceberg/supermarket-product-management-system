package com.smms.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.member.entity.Member;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<Member> {
}
