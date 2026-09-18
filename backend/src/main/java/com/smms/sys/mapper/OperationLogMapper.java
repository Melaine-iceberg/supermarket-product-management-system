package com.smms.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.sys.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /** 模块去重列表（筛选项） */
    @Select("SELECT DISTINCT module FROM operation_log")
    List<String> selectModules();
}
