package com.smms.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.sys.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 查询用户（经启用角色）拥有的全部启用权限 */
    @Select("""
            SELECT DISTINCT p.* FROM sys_permission p
            JOIN sys_role_permission rp ON rp.perm_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId} AND r.status = 1 AND p.status = 1
            """)
    List<SysPermission> selectByUserId(@Param("userId") Long userId);
}
