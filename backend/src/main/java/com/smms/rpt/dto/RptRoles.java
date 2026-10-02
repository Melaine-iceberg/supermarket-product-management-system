package com.smms.rpt.dto;

import com.smms.security.LoginUser;

import java.util.Set;

/**
 * 报表字段可见性（BR-RPT-07）：收银员不可见成本价与毛利类指标；
 * 无权字段在服务端置空（不返回）而非前端隐藏。
 */
public final class RptRoles {

    /** 可见成本与毛利类指标的角色（店长及以上） */
    private static final Set<String> PROFIT_ROLES = Set.of("SUPER_ADMIN", "MANAGER");

    private RptRoles() {
    }

    /** 当前用户是否可见成本价/毛利/毛利率等指标（BR-RPT-07） */
    public static boolean canViewProfit(LoginUser user) {
        return user != null && user.roleCodes() != null
                && user.roleCodes().stream().anyMatch(PROFIT_ROLES::contains);
    }
}
