-- =====================================================================
-- 超市商品管理系统（SMMS）初始化数据脚本
-- 内容：内置角色 / 权限树 / 演示用户 / 系统参数（§8.1）/
--       数据字典（FR-SYS-07）/ 计量单位（FR-GOODS-08）/ 会员等级（FR-MEM-05）
-- 说明：演示用户密码均为 BCrypt 强度 10 哈希；首次登录后应立即修改
-- =====================================================================

USE smms;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 内置角色（FR-SYS-04：内置角色不可删除，权限可调整）
-- ---------------------------------------------------------------------
INSERT INTO sys_role (id, role_name, role_code, description, is_builtin, status, create_by) VALUES
(1, '超级管理员', 'SUPER_ADMIN', '系统初始化、用户与角色管理、权限分配、数据字典与参数维护、日志审计', 1, 1, 'system'),
(2, '店长',       'MANAGER',     '经营全局查看、报表分析、价格与促销管理、采购与盘点审核、退货授权、供应商准入', 1, 1, 'system'),
(3, '采购员',     'PURCHASER',   '供应商维护、编制与提交采购单、跟踪到货、处理采购退货、处理缺货预警', 1, 1, 'system'),
(4, '库管员',     'KEEPER',      '采购入库验收、报损报溢登记、库存盘点执行、预警处理', 1, 1, 'system'),
(5, '收银员',     'CASHIER',     '收银结算、退换货办理、会员查询与注册、交班对账', 1, 1, 'system');

-- ---------------------------------------------------------------------
-- 2. 权限树：目录 D / 菜单 M / 按钮 B（FR-SYS-05）
--    route_path 供前端动态路由；perm_code 供后端接口校验（BR-SYS-05 双重校验）
-- ---------------------------------------------------------------------
INSERT INTO sys_permission (id, parent_id, name, perm_type, perm_code, route_path, component, icon, sort_no, status, create_by) VALUES
-- 目录
(1, 0, '系统管理', 'D', NULL, '/system',   NULL, 'Setting',  900, 1, 'system'),
(2, 0, '商品管理', 'D', NULL, '/goods',    NULL, 'Goods',    800, 1, 'system'),
(3, 0, '库存管理', 'D', NULL, '/inventory', NULL, 'Box',      700, 1, 'system'),
(4, 0, '采购管理', 'D', NULL, '/purchase', NULL, 'ShoppingCart', 600, 1, 'system'),
(5, 0, '销售管理', 'D', NULL, '/sale',     NULL, 'Sell',     500, 1, 'system'),
(6, 0, '会员管理', 'D', NULL, '/member',   NULL, 'User',     400, 1, 'system'),
(7, 0, '报表统计', 'D', NULL, '/report',   NULL, 'DataAnalysis', 300, 1, 'system'),

-- 系统管理菜单
(10, 1, '用户管理', 'M', 'sys:user:list',    '/system/user',     'system/user/index',    'User',      1, 1, 'system'),
(101, 10, '新增用户',     'B', 'sys:user:create',        NULL, NULL, NULL, 1, 1, 'system'),
(102, 10, '编辑用户',     'B', 'sys:user:update',        NULL, NULL, NULL, 2, 1, 'system'),
(103, 10, '停用/启用用户','B', 'sys:user:status',        NULL, NULL, NULL, 3, 1, 'system'),
(104, 10, '重置密码',     'B', 'sys:user:reset-password', NULL, NULL, NULL, 4, 1, 'system'),
(11, 1, '角色管理', 'M', 'sys:role:list',    '/system/role',     'system/role/index',    'Avatar',    2, 1, 'system'),
(111, 11, '新增角色',     'B', 'sys:role:create',        NULL, NULL, NULL, 1, 1, 'system'),
(112, 11, '编辑角色',     'B', 'sys:role:update',        NULL, NULL, NULL, 2, 1, 'system'),
(113, 11, '停用/启用角色','B', 'sys:role:status',        NULL, NULL, NULL, 3, 1, 'system'),
(12, 1, '系统参数', 'M', 'sys:config:list', '/system/config',   'system/config/index',  'Tools',     3, 1, 'system'),
(121, 12, '修改参数',     'B', 'sys:config:update',     NULL, NULL, NULL, 1, 1, 'system'),
(13, 1, '数据字典', 'M', 'sys:dict:list',   '/system/dict',     'system/dict/index',   'Collection',4, 1, 'system'),
(131, 13, '维护字典项',   'B', 'sys:dict:update',       NULL, NULL, NULL, 1, 1, 'system'),
(14, 1, '操作日志', 'M', 'sys:oplog:list',  '/system/oplog',    'system/oplog/index',  'Document',  5, 1, 'system'),
(141, 14, '导出日志',     'B', 'sys:oplog:export',      NULL, NULL, NULL, 1, 1, 'system'),
(15, 1, '登录日志', 'M', 'sys:loginlog:list','/system/loginlog', 'system/loginlog/index','Key',     6, 1, 'system'),
(16, 1, '定时任务', 'M', 'sys:sched:list',  '/system/scheduled-task', 'system/scheduled-task/index', 'AlarmClock', 7, 1, 'system'),
(161, 16, '手工触发任务', 'B', 'sys:sched:run',         NULL, NULL, NULL, 1, 1, 'system'),
(17, 1, '数据备份', 'M', 'sys:backup:list', '/system/backup',   'system/backup/index',  'FolderOpened', 8, 1, 'system'),
(171, 17, '一键触发备份', 'B', 'sys:backup:run',        NULL, NULL, NULL, 1, 1, 'system'),

-- 商品管理菜单
(20, 2, '分类管理', 'M', 'sys:cat:list',    '/goods/category',  'goods/category/index', 'Folder',   1, 1, 'system'),
(201, 20, '新增分类',     'B', 'sys:cat:create',        NULL, NULL, NULL, 1, 1, 'system'),
(202, 20, '编辑分类',     'B', 'sys:cat:update',        NULL, NULL, NULL, 2, 1, 'system'),
(203, 20, '停用/启用分类','B', 'sys:cat:status',        NULL, NULL, NULL, 3, 1, 'system'),
(204, 20, '删除分类',     'B', 'sys:cat:delete',        NULL, NULL, NULL, 4, 1, 'system'),
(205, 20, '分类树只读',   'B', 'sys:cat:tree',          NULL, NULL, NULL, 5, 1, 'system'),
(21, 2, '商品档案', 'M', 'sys:goods:list',  '/goods/list',      'goods/list/index',     'GoodsFilled',2, 1, 'system'),
(211, 21, '新增商品',     'B', 'sys:goods:create',      NULL, NULL, NULL, 1, 1, 'system'),
(212, 21, '编辑商品',     'B', 'sys:goods:update',     NULL, NULL, NULL, 2, 1, 'system'),
(213, 21, '停用/启用商品','B', 'sys:goods:status',     NULL, NULL, NULL, 3, 1, 'system'),
(214, 21, '删除商品',     'B', 'sys:goods:delete',     NULL, NULL, NULL, 4, 1, 'system'),
(215, 21, '批量导入',     'B', 'sys:goods:import',     NULL, NULL, NULL, 5, 1, 'system'),
(216, 21, '导出清单',     'B', 'sys:goods:export',     NULL, NULL, NULL, 6, 1, 'system'),
(22, 2, '计量单位', 'M', 'sys:unit:list',   '/goods/unit',      'goods/unit/index',    'ScaleToOriginal', 3, 1, 'system'),
(221, 22, '新增单位',     'B', 'sys:unit:create',      NULL, NULL, NULL, 1, 1, 'system'),
(222, 22, '编辑单位',     'B', 'sys:unit:update',      NULL, NULL, NULL, 2, 1, 'system'),
(23, 2, '品牌管理', 'M', 'sys:brand:list',  '/goods/brand',     'goods/brand/index',   'PriceTag', 4, 1, 'system'),
(231, 23, '新增品牌',     'B', 'sys:brand:create',     NULL, NULL, NULL, 1, 1, 'system'),
(232, 23, '编辑品牌',     'B', 'sys:brand:update',     NULL, NULL, NULL, 2, 1, 'system'),

-- 库存管理菜单
(30, 3, '库存查询', 'M', 'sys:inv:list',     '/inventory/list',    'inventory/list/index',   'Odometer', 1, 1, 'system'),
(301, 30, '安全库存阈值维护', 'B', 'sys:inv:threshold', NULL, NULL, NULL, 1, 1, 'system'),
(31, 3, '库存流水', 'M', 'sys:inv-record:list','/inventory/record', 'inventory/record/index','List',    2, 1, 'system'),
(32, 3, '库存预警', 'M', 'sys:inv-warning:list','/inventory/warning','inventory/warning/index','Bell',  3, 1, 'system'),
(321, 32, '导出预警清单', 'B', 'sys:inv-warning:export', NULL, NULL, NULL, 1, 1, 'system'),
(33, 3, '盘点管理', 'M', 'sys:check:list',   '/inventory/check',  'inventory/check/index',  'Finished',4, 1, 'system'),
(331, 33, '新建盘点单',   'B', 'sys:check:create',     NULL, NULL, NULL, 1, 1, 'system'),
(332, 33, '录入实盘数',   'B', 'sys:check:update',     NULL, NULL, NULL, 2, 1, 'system'),
(333, 33, '提交审核',     'B', 'sys:check:submit',     NULL, NULL, NULL, 3, 1, 'system'),
(334, 33, '盘点审核',     'B', 'sys:check:audit',      NULL, NULL, NULL, 4, 1, 'system'),
(335, 33, '作废盘点单',   'B', 'sys:check:cancel',     NULL, NULL, NULL, 5, 1, 'system'),
(34, 3, '报损报溢', 'M', 'sys:loss:list',    '/inventory/loss',   'inventory/loss/index',   'Warning', 5, 1, 'system'),
(341, 34, '报损/报溢登记','B', 'sys:loss:create',      NULL, NULL, NULL, 1, 1, 'system'),
(342, 34, '报损报溢审核', 'B', 'sys:loss:audit',       NULL, NULL, NULL, 2, 1, 'system'),
(35, 3, '库存体检', 'M', 'sys:inv:health',  '/inventory/health', 'inventory/health/index', 'FirstAidKit', 6, 1, 'system'),
(351, 35, '一键按流水修正结存','B', 'sys:inv:health:fix', NULL, NULL, NULL, 1, 1, 'system'),

-- 采购管理菜单
(40, 4, '采购单',   'M', 'sys:pur:list',    '/purchase/order',    'purchase/order/index',    'Tickets', 1, 1, 'system'),
(401, 40, '新建采购单',   'B', 'sys:pur:create',       NULL, NULL, NULL, 1, 1, 'system'),
(402, 40, '编辑采购单',   'B', 'sys:pur:update',       NULL, NULL, NULL, 2, 1, 'system'),
(403, 40, '删除草稿',     'B', 'sys:pur:delete',       NULL, NULL, NULL, 3, 1, 'system'),
(404, 40, '提交审核',     'B', 'sys:pur:submit',       NULL, NULL, NULL, 4, 1, 'system'),
(405, 40, '采购审核',     'B', 'sys:pur:audit',        NULL, NULL, NULL, 5, 1, 'system'),
(406, 40, '终止/作废',    'B', 'sys:pur:terminate',    NULL, NULL, NULL, 6, 1, 'system'),
(407, 40, '结算登记',     'B', 'sys:pur:settle',       NULL, NULL, NULL, 7, 1, 'system'),
(408, 40, '采购退货',     'B', 'sys:pur:return',       NULL, NULL, NULL, 8, 1, 'system'),
(409, 40, '退货审核',     'B', 'sys:pur:return-audit', NULL, NULL, NULL, 9, 1, 'system'),
(41, 4, '入库单',   'M', 'sys:rcv:list',    '/purchase/receipt',  'purchase/receipt/index',  'Download', 2, 1, 'system'),
(411, 41, '入库验收',     'B', 'sys:rcv:create',       NULL, NULL, NULL, 1, 1, 'system'),
(412, 41, '打印入库单',   'B', 'sys:rcv:print',        NULL, NULL, NULL, 2, 1, 'system'),
(42, 4, '供应商',   'M', 'sys:sup:list',   '/purchase/supplier', 'purchase/supplier/index', 'OfficeBuilding', 3, 1, 'system'),
(421, 42, '新增供应商',   'B', 'sys:sup:create',       NULL, NULL, NULL, 1, 1, 'system'),
(422, 42, '编辑供应商',   'B', 'sys:sup:update',       NULL, NULL, NULL, 2, 1, 'system'),
(423, 42, '停用/启用',    'B', 'sys:sup:status',       NULL, NULL, NULL, 3, 1, 'system'),
(424, 42, '维护供货商品', 'B', 'sys:sup:goods',        NULL, NULL, NULL, 4, 1, 'system'),
(43, 4, '建议采购', 'M', 'sys:suggest:list','/purchase/suggest',  'purchase/suggest/index', 'MagicStick', 4, 1, 'system'),

-- 销售管理菜单
(50, 5, '收银台',   'M', 'sys:cashier:enter', '/sale/cashier',     'sale/cashier/index',     'Monitor', 1, 1, 'system'),
(51, 5, '销售单',   'M', 'sys:so:list',      '/sale/order',        'sale/order/index',       'Document', 2, 1, 'system'),
(52, 5, '退货管理', 'M', 'sys:ret:list',     '/sale/return',       'sale/return/index',      'RefreshLeft', 3, 1, 'system'),
(521, 52, '办理退货',     'B', 'sys:ret:create',       NULL, NULL, NULL, 1, 1, 'system'),
(53, 5, '交班管理', 'M', 'sys:shift:list',    '/sale/shift',        'sale/shift/index',      'AlarmClock', 4, 1, 'system'),
(531, 53, '开班',         'B', 'sys:shift:open',        NULL, NULL, NULL, 1, 1, 'system'),
(532, 53, '交班',         'B', 'sys:shift:close',       NULL, NULL, NULL, 2, 1, 'system'),

-- 会员管理菜单
(60, 6, '会员档案', 'M', 'sys:mem:list',     '/member/list',       'member/list/index',      'UserFilled', 1, 1, 'system'),
(601, 60, '新增会员',     'B', 'sys:mem:create',       NULL, NULL, NULL, 1, 1, 'system'),
(602, 60, '编辑会员',     'B', 'sys:mem:update',       NULL, NULL, NULL, 2, 1, 'system'),
(603, 60, '停用/启用会员','B', 'sys:mem:status',       NULL, NULL, NULL, 3, 1, 'system'),
(604, 60, '批量导入',     'B', 'sys:mem:import',       NULL, NULL, NULL, 4, 1, 'system'),
(605, 60, '储值充值',     'B', 'sys:mem:recharge',     NULL, NULL, NULL, 5, 1, 'system'),
(61, 6, '会员等级', 'M', 'sys:mem-level:list','/member/level',    'member/level/index',     'Trophy',  2, 1, 'system'),
(611, 61, '编辑等级规则', 'B', 'sys:mem-level:update',  NULL, NULL, NULL, 1, 1, 'system'),

-- 报表统计菜单
(70, 7, '经营看板', 'M', 'sys:rpt:dashboard',   '/report/dashboard', 'report/dashboard/index', 'DataBoard', 1, 1, 'system'),
(71, 7, '销售报表', 'M', 'sys:rpt:sales',       '/report/sales',    'report/sales/index',     'TrendCharts', 2, 1, 'system'),
(72, 7, '商品分析', 'M', 'sys:rpt:goods',       '/report/goods',    'report/goods/index',     'Histogram', 3, 1, 'system'),
(73, 7, '库存报表', 'M', 'sys:rpt:inventory',   '/report/inventory','report/inventory/index','Coin',      4, 1, 'system'),
(74, 7, '毛利分析', 'M', 'sys:rpt:profit',      '/report/profit',   'report/profit/index',    'PieChart',  5, 1, 'system'),
(75, 7, '采购报表', 'M', 'sys:rpt:purchase',    '/report/purchase', 'report/purchase/index', 'Money',     6, 1, 'system'),
(76, 7, '会员报表', 'M', 'sys:rpt:member',      '/report/member',   'report/member/index',    'Star',      7, 1, 'system');

-- ---------------------------------------------------------------------
-- 3. 演示用户（密码 BCrypt 强度 10，BR-SYS-02）
--    admin/Admin@2026  manager/Manager@2026  purchaser/Buyer@2026
--    keeper/Keeper@2026  cashier/Cashier@2026
-- ---------------------------------------------------------------------
INSERT INTO sys_user (id, username, password, real_name, phone, status, remark, create_by) VALUES
(1, 'admin',     '$2b$10$0bU7cZavM0MProkHVPDxYOwpwBrUn05yv392/w57uwjTgfhvnyIW.', '系统管理员', '13800000001', 1, '超级管理员账号，唯一且不可停用（BR-SYS-03）', 'system'),
(2, 'manager',   '$2b$10$goP5s522F07U9bATP7TQWerRyTvrzkXgDuvrrFfbLpv8PK.6pZogW', '王店长',     '13800000002', 1, '店长演示账号', 'system'),
(3, 'purchaser', '$2b$10$3nv98uoke69vCZEs9wdTq.AjREXcW2sqmylKZJGI2O7Kkctv9TMCm', '李采购',     '13800000003', 1, '采购员演示账号', 'system'),
(4, 'keeper',    '$2b$10$xHlQZNU/e1fl8B4A127A9.weM.A2UYe3fRZ3NcPip2ugtO3Uum6Ra', '赵库管',     '13800000004', 1, '库管员演示账号', 'system'),
(5, 'cashier',   '$2b$10$tlaxcRV6BK5oUDN.SxOZeurRHGdPUWJXat3bgzjswAIKDXUsKV3Au', '陈收银',     '13800000005', 1, '收银员演示账号', 'system');

-- 用户-角色关联
INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 1), (2, 2), (3, 3), (4, 4), (5, 5);

-- ---------------------------------------------------------------------
-- 4. 角色-权限分配（§1.3 角色职责）
-- ---------------------------------------------------------------------
-- 超级管理员：全部权限（含系统管理）
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 1, id FROM sys_permission;

-- 店长：除"系统管理"目录外的全部权限（商品/库存/采购/销售/会员/报表）；
-- 库存体检一键修正除外（FR-INV-11 仅超级管理员，白盒校验能力）
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT 2, p.id
FROM sys_permission p
WHERE NOT (
    p.id = 1
    OR p.parent_id = 1
    OR p.parent_id IN (SELECT id FROM (SELECT id FROM sys_permission WHERE parent_id = 1) t)
)
AND (p.perm_code IS NULL OR p.perm_code <> 'sys:inv:health:fix');

-- 采购员：供应商维护、采购单编制提交、采购退货、建议采购、入库跟踪；商品与库存仅查看
-- 注：205 分类树只读（业务页面按分类筛选/选品用，不开分类管理菜单）
INSERT INTO sys_role_permission (role_id, perm_id) VALUES
(3, 2), (3, 21), (3, 205),
(3, 3), (3, 30), (3, 31), (3, 32),
(3, 4), (3, 40), (3, 401), (3, 402), (3, 403), (3, 404), (3, 408),
(3, 41), (3, 42), (3, 421), (3, 422), (3, 424), (3, 43);

-- 库管员：入库验收、盘点执行、报损报溢登记、库存体检查看；商品与库存查看
INSERT INTO sys_role_permission (role_id, perm_id) VALUES
(4, 2), (4, 21), (4, 205),
(4, 3), (4, 30), (4, 31), (4, 32),
(4, 33), (4, 331), (4, 332), (4, 333), (4, 335),
(4, 34), (4, 341), (4, 35),
(4, 4), (4, 41), (4, 411), (4, 412);

-- 收银员：收银台、退货办理、交班；会员查询与注册、储值充值（充值需店长授权）
INSERT INTO sys_role_permission (role_id, perm_id) VALUES
(5, 5), (5, 50), (5, 51), (5, 52), (5, 521), (5, 53), (5, 531), (5, 532),
(5, 6), (5, 60), (5, 601), (5, 605);

-- ---------------------------------------------------------------------
-- 5. 系统参数（§8.1 参数清单，FR-SYS-06）
-- ---------------------------------------------------------------------
INSERT INTO sys_config (config_name, config_key, config_value, value_type, config_group, editable, instant_effect, remark, update_by) VALUES
('是否允许负库存',        'allow_negative_stock',      'false', 'BOOLEAN', 'inventory',  1, 1, 'false 时销售/报损出库不足即拒绝（BR-INV-02）', 'system'),
('采购超收容差比例',      'receiving_tolerance_rate',  '5',     'NUMBER', 'purchase',   1, 1, '实收>待入库×(1+比例)须店长授权（BR-PUR-04）', 'system'),
('实收单价价差容差',      'price_diff_tolerance_rate', '5',     'NUMBER', 'purchase',   1, 1, '与采购单价偏离超±百分比须授权（BR-PUR-05）', 'system'),
('采购价异常提示阈值',    'price_diff_warn_rate',       '20',    'NUMBER', 'purchase',   1, 1, '与近3次均价偏离超±百分比黄色提示（BR-PUR-05）', 'system'),
('退货授权金额阈值',      'return_auth_amount',        '200',   'NUMBER', 'sale',       1, 1, '退款超过该金额须店长授权（BR-SALE-05）', 'system'),
('退货有效期',            'return_valid_days',          '7',     'NUMBER', 'sale',       1, 1, '超期退货须店长特批（BR-SALE-05）', 'system'),
('最低整单折扣率',        'min_discount_rate',          '0.7',   'NUMBER', 'sale',       1, 1, '店长整单折扣下限70%（BR-SALE-05/10）', 'system'),
('每元积分数',            'point_per_yuan',             '1',     'NUMBER', 'member',     1, 1, '积分=实付×每元积分数×等级倍率（BR-MEM-05）', 'system'),
('储值消费是否计积分',    'point_include_recharge',     'false', 'BOOLEAN', 'member',    1, 1, '默认储值消费不计积分基数（BR-MEM-07）', 'system'),
('会员降级策略',          'member_level_downgrade',     'ONLY_UP', 'STRING', 'member',   1, 1, 'ONLY_UP只升不降/YEARLY按自然年重算（BR-MEM-06）', 'system'),
('建议采购量安全倍数K',   'suggest_order_k',            '1.5',   'NUMBER', 'purchase',   1, 1, '建议量=ceil(安全库存×K+日均销量×提前期-结存-在途)（BR-INV-07）', 'system'),
('日均销量统计天数N',     'suggest_order_days',         '28',    'NUMBER', 'purchase',   1, 1, '近N日销售出库合计/N（BR-INV-07）', 'system'),
('默认采购提前期',        'default_lead_time_days',     '3',     'NUMBER', 'purchase',   1, 1, '供应商未配置提前期时使用（BR-INV-07）', 'system'),
('营业日起始时刻',        'business_day_start',         '00:00', 'TIME',   'report',     1, 1, '跨零点单据归属营业日规则（BR-RPT-02）', 'system'),
('登录失败锁定阈值',      'login_fail_limit',           '5',     'NUMBER', 'security',   1, 1, '连续失败N次锁定账号（FR-SYS-01）', 'system'),
('登录锁定时长(分钟)',    'lock_minutes',               '15',    'NUMBER', 'security',   1, 1, '锁定时长（FR-SYS-01）', 'system'),
('Token有效期(小时)',     'token_expire_hours',         '8',     'NUMBER', 'security',   1, 0, 'JWT有效期，修改后对新签发Token生效（IR-SW-03）', 'system'),
('职责分离是否强制拒绝',  'duty_separation_strict',     'false', 'BOOLEAN', 'security',  1, 1, 'false时放行但记异常日志（BR-SYS-01）', 'system'),
('店内码前缀',            'barcode_prefix',             '20',    'STRING', 'goods',      1, 1, 'EAN-13店内码前缀20~29（FR-GOODS-07）', 'system'),
('超市名称',              'store_name',                 '好又多超市', 'STRING', 'basic',  1, 1, '打印页眉/登录页展示（IR-UI-08）', 'system'),
('超市地址',              'store_address',             '',      'STRING', 'basic',     1, 1, '打印页眉', 'system'),
('超市电话',              'store_phone',               '',      'STRING', 'basic',     1, 1, '打印页眉', 'system'),
('小票纸宽',              'receipt_paper_width',        '58',    'NUMBER', 'basic',     1, 1, '58/80mm（IR-UI-08）', 'system'),
('导出最大行数',          'export_max_rows',            '50000', 'NUMBER', 'basic',     1, 1, '单次导出上限（FR-RPT-08）', 'system');

-- ---------------------------------------------------------------------
-- 6. 数据字典（FR-SYS-07）
-- ---------------------------------------------------------------------
INSERT INTO sys_dict (id, dict_type, dict_name, status, remark, create_by) VALUES
(1, 'pay_type',           '收款方式', 1, 'X-01：结算时登记收款方式，金额手工录入', 'system'),
(2, 'return_reason',      '退货原因', 1, NULL, 'system'),
(3, 'loss_reason',        '报损报溢原因', 1, 'FR-INV-10：过期/破损/失窃/赠品/样品等', 'system'),
(4, 'check_diff_reason',  '盘点差异原因', 1, 'FR-INV-08：差异行必填原因', 'system'),
(5, 'settle_type',        '供应商结算方式', 1, 'FR-SUP-01', 'system');

INSERT INTO sys_dict_item (dict_id, label, value, sort_no, status, create_by) VALUES
(1, '现金',     'CASH',   1, 1, 'system'),
(1, '银行卡',   'BANK',   2, 1, 'system'),
(1, '移动支付', 'MOBILE', 3, 1, 'system'),
(1, '会员储值', 'BALANCE',4, 1, 'system'),
(2, '质量问题', 'QUALITY',    1, 1, 'system'),
(2, '错拿商品', 'WRONG_ITEM', 2, 1, 'system'),
(2, '误操作',   'MISTAKE',    3, 1, 'system'),
(2, '其他',     'OTHER',      4, 1, 'system'),
(3, '过期', 'EXPIRED',  1, 1, 'system'),
(3, '破损', 'DAMAGED',  2, 1, 'system'),
(3, '失窃', 'STOLEN',   3, 1, 'system'),
(3, '赠品', 'GIFT',     4, 1, 'system'),
(3, '样品', 'SAMPLE',   5, 1, 'system'),
(3, '其他', 'OTHER',    6, 1, 'system'),
(4, '计数误差', 'COUNT_ERROR',  1, 1, 'system'),
(4, '损耗',     'NATURAL_LOSS', 2, 1, 'system'),
(4, '失窃',     'STEAL',        3, 1, 'system'),
(4, '单据错误', 'RECORD_ERROR', 4, 1, 'system'),
(4, '其他',     'OTHER',        5, 1, 'system'),
(5, '现结', 'CASH',     1, 1, 'system'),
(5, '月结', 'MONTHLY',  2, 1, 'system'),
(5, '账期', 'CREDIT',   3, 1, 'system');

-- ---------------------------------------------------------------------
-- 7. 计量单位（FR-GOODS-08）：称重商品只能选重量单位（BR-GOODS-05）
-- ---------------------------------------------------------------------
INSERT INTO unit (id, name, symbol, is_weight, status, create_by) VALUES
(1, '瓶', '瓶', 0, 1, 'system'),
(2, '袋', '袋', 0, 1, 'system'),
(3, '盒', '盒', 0, 1, 'system'),
(4, '包', '包', 0, 1, 'system'),
(5, 'kg', 'kg', 1, 1, 'system'),
(6, 'g',  'g',  1, 1, 'system'),
(7, '个', '个', 0, 1, 'system'),
(8, '箱', '箱', 0, 1, 'system'),
(9, '提', '提', 0, 1, 'system'),
(10, '份', '份', 0, 1, 'system');

-- ---------------------------------------------------------------------
-- 8. 会员等级（FR-MEM-05 预设三级，门槛与折扣可配置）
-- ---------------------------------------------------------------------
INSERT INTO member_level (id, name, threshold, discount_rate, point_rate, sort_no, status, create_by) VALUES
(1, '普通会员', 0,    1.0000, 1.0, 1, 1, 'system'),
(2, '银卡会员', 1000, 0.9800, 1.0, 2, 1, 'system'),
(3, '金卡会员', 5000, 0.9500, 1.2, 3, 1, 'system');

-- ---------------------------------------------------------------------
-- 9. 示例分类（可选：提供一级大类基础数据，便于联调）
-- ---------------------------------------------------------------------
INSERT INTO category (id, parent_id, name, level, path, sort_no, status, create_by) VALUES
(1, 0, '食品',     1, '1', 1, 1, 'system'),
(2, 0, '饮料',     1, '2', 2, 1, 'system'),
(3, 0, '日用品',   1, '3', 3, 1, 'system'),
(4, 0, '生鲜果蔬', 1, '4', 4, 1, 'system');
