package com.smms.common.api;

import lombok.Getter;

/**
 * 统一错误码体系（NFR-REL-06：可预期异常返回明确业务错误码与中文提示）。
 * 约定：0 成功；4xx 通用客户端错误；分段号位保留给各业务模块扩展。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---------- 通用客户端错误 ----------
    PARAM_INVALID(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),

    // ---------- 通用服务端错误 ----------
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用，请稍后重试"),

    // ---------- 通用业务错误（各模块错误码后续按段扩展） ----------
    BUSINESS_ERROR(1000, "业务处理失败"),
    CONCURRENT_CONFLICT(1001, "操作冲突，请刷新后重试"),

    // ---------- SYS 系统模块（1100 ~ 1199，FR-SYS-01 ~ 09） ----------
    USERNAME_PASSWORD_ERROR(1101, "用户名或密码错误"),
    ACCOUNT_LOCKED(1102, "账号已锁定，请稍后重试"),
    ACCOUNT_DISABLED(1103, "账号已停用，请联系管理员"),
    USERNAME_EXISTS(1104, "用户名已存在"),
    ROLE_CODE_EXISTS(1105, "角色代码已存在"),
    WEAK_PASSWORD(1106, "密码强度不足"),
    OLD_PASSWORD_ERROR(1107, "原密码不正确"),
    BUILTIN_PROTECTED(1108, "内置对象受保护，不允许该操作"),

    // ---------- CAT 分类模块（1200 ~ 1299，FR-CAT-01 ~ 07） ----------
    CAT_NAME_EXISTS(1201, "同级下已存在同名分类"),
    CAT_LEVEL_EXCEED(1202, "分类层级最多3级"),
    CAT_PARENT_INVALID(1203, "上级分类不能是自身或其子孙分类"),
    CAT_HAS_CHILDREN(1204, "存在子分类，禁止删除"),
    CAT_HAS_GOODS(1205, "分类下存在商品，禁止删除，请改用停用"),
    CAT_PARENT_DISABLED(1206, "上级分类已停用，请先启用上级分类"),
    CAT_ANCESTOR_DISABLED(1207, "上级分类已停用，无法启用该分类，请先启用上级分类"),

    // ---------- GOODS 商品模块（1300 ~ 1399，FR-GOODS-01 ~ 13） ----------
    GOODS_CODE_EXISTS(1301, "商品编码已存在"),
    GOODS_BARCODE_EXISTS(1302, "商品条码已存在"),
    GOODS_HAS_BUSINESS(1303, "商品已发生业务，禁止删除，请改用停用"),
    GOODS_CATEGORY_NOT_LEAF(1304, "商品只能挂在叶子分类（无子分类）下"),
    GOODS_WEIGHED_UNIT(1305, "称重商品的计量单位必须为重量单位（BR-GOODS-05）"),
    GOODS_WEIGHED_PLU_REQUIRED(1306, "称重商品必须录入 PLU 码（BR-GOODS-05）"),
    GOODS_BARCODE_CONFIRM_REQUIRED(1307, "条码修改需二次确认"),
    GOODS_CODE_IMMUTABLE(1308, "商品编码创建后不可修改"),
    UNIT_NAME_EXISTS(1311, "计量单位名称已存在"),
    UNIT_HAS_GOODS(1312, "该单位已被商品使用，禁止删除，请改用停用"),
    BRAND_NAME_EXISTS(1321, "品牌名称已存在"),
    PROMO_OVERLAP(1331, "同一商品同一时间段只允许一条生效促销（BR-GOODS-04）"),
    PROMO_PRICE_INVALID(1332, "促销价必须低于零售价"),

    // ---------- INV 库存模块（1400 ~ 1499，FR-INV-01 ~ 12） ----------
    STOCK_NOT_ENOUGH(1401, "库存不足"),
    CHECK_SCOPE_EMPTY(1411, "盘点范围内没有可盘商品"),
    CHECK_OVERLAP(1412, "范围内已存在未审核盘点单，不可重复创建（BR-INV-09）"),
    CHECK_STATUS_INVALID(1413, "盘点单当前状态不允许该操作"),
    CHECK_AUDIT_SELF(1414, "审核人与盘点人必须为不同用户（BR-SYS-01）"),
    CHECK_REASON_REQUIRED(1415, "差异原因或驳回意见不能为空"),

    // ---------- SALE 销售模块（1500 ~ 1599，FR-SALE-01 ~ 13） ----------
    SALE_GOODS_INVALID(1501, "商品不存在或已停用，不可销售"),
    SALE_GOODS_DUPLICATED(1502, "同一商品在同一单内只能占一行（BR-SALE-01）"),
    SALE_QTY_INVALID(1503, "普通商品数量必须为正整数"),
    SALE_NET_WEIGHT_REQUIRED(1504, "称重商品必须录入净重（最多 3 位小数）"),
    SALE_PAY_TYPE_INVALID(1505, "收款方式不合法"),
    SALE_PAY_AMOUNT_INVALID(1506, "收款金额必须大于 0"),
    SALE_PAID_NOT_ENOUGH(1507, "实收金额不足应收金额"),
    SALE_DISCOUNT_BELOW_MIN(1508, "整单折扣率低于系统下限（BR-SALE-05）"),
    SALE_DISCOUNT_AUTH_REQUIRED(1509, "整单折扣须店长授权（FR-SALE-07）"),
    SALE_MEMBER_DISABLED(1510, "会员已停用，请按散客结算（BR-MEM-03）"),
    SALE_MEMBER_NOT_FOUND(1511, "会员不存在"),
    SALE_BALANCE_UNAVAILABLE(1512, "储值支付暂未开放"),
    SALE_ORDER_NO_RETRY(1513, "单号生成冲突，请重试结算"),
    SALE_DAILY_NO_EXCEED(1514, "该收银台当日流水号已用尽");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
