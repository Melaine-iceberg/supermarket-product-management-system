# 浏览器 E2E（Playwright）

对 docker compose 部署栈的关键路径回归（23 例）：登录/权限链、收银员分类浏览（DEF-19）、库管员入库验收（DEF-18）、管理端各模块冒烟、体验规范（顶栏待办/全屏、销售单筛选、网络异常页，GAP-P2-03/25）、质量收尾（报表默认今日与快捷项、条码占用与重新生成编码、收银台手机号后 4 位，GAP-P2-08/19/21）。用例只读（不改变业务数据），造数由 `global-setup.ts` 经真实 API 幂等完成。

## 前置条件

1. 部署栈已启动（仓库根目录）：
   ```bash
   docker compose up -d --build
   ```
   Nginx 默认发布在 `http://localhost`；后端健康检查 `http://localhost:8080/actuator/health`。
2. 首次运行需安装浏览器（仅一次）：
   ```bash
   cd frontend && pnpm exec playwright install chromium
   ```

## 运行

```bash
cd frontend
pnpm test:e2e              # 无头 Chromium，串行执行
pnpm test:e2e:headed       # 有头模式（本机调试）
pnpm test:e2e:report       # 查看最近一次的 HTML 报告
```

单文件/单用例：

```bash
pnpm exec playwright test e2e/cashier.spec.ts
pnpm exec playwright test -g "DEF-18"
```

指向其它环境（无需改代码）：

```bash
E2E_BASE_URL=https://smms.example.com pnpm test:e2e
```

## 产物与登录态

| 路径 | 说明 | 是否入库 |
| --- | --- | --- |
| `e2e/.auth/*.json` | 演示账号登录态（global-setup 生成） | 否（gitignore） |
| `playwright-report/` | HTML 报告 | 否 |
| `test-results/` | 失败 trace / 截图 | 否 |

## 造数策略（幂等）

`global-setup.ts` 以 admin 身份经 API 保证以下数据存在（已存在则复用）：

- 供应商「E2E种子供应商」；
- 商品「E2E测试可乐」（条码 `200000000012`，分类「食品」）；
- 该供应商名下一张「已审核待入库」采购单（草稿/待审中断单会自动续跑）；
- 会员「E2E会员」（手机号 `13800009999`，收银台脱敏回归用）。

业务用例不做提交类写操作；如需在业务上继续写入（如提交验收），请另行评估演示库数据影响。

## 与登录限速的关系（NFR-SEC-03）

后端限制同 IP 每分钟最多 20 次登录请求。套件通过两项设计控制请求量：

- 仅 `admin/keeper/cashier` 预生成登录态（其余演示账号无 E2E 用例，不登录）；
- 只有真实登录路径用例走表单提交（未登录回跳、表单登录、错误密码各 1 次）。

单轮运行约 6 次登录请求；仍建议连续运行时间隔 1 分钟，避免叠加触发限速（表现为登录失败、错误码 429）。
