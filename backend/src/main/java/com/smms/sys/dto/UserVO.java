package com.smms.sys.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户列表/详情视图（FR-SYS-02）：不返回密码哈希。
 */
public record UserVO(
        Long id,
        String username,
        String realName,
        String phone,
        Integer status,
        String remark,
        List<Long> roleIds,
        List<String> roleNames,
        LocalDateTime lastLoginTime,
        boolean mustChangePwd,
        LocalDateTime createTime
) {
}
