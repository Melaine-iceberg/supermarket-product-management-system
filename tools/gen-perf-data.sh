#!/usr/bin/env bash
# =====================================================================
# SMMS 仿真数据导入包装脚本（AC-D07 / NFR-PERF-05）
#
# 用法：
#   tools/gen-perf-data.sh [--docker] [--dry-run] [生成器参数...]
#
# 说明：
#   * 将 tools/gen-perf-data.py 生成的 SQL 流直接管道给 mysql 客户端执行；
#   * 除 --docker / --dry-run 外的参数原样透传给生成器
#     （如 --goods 20000 --orders 2000000 --months 36 --seed 20261006）；
#   * 目标库请先完成 db/schema.sql + db/init-data.sql 初始化；
#     重复导入会因主键冲突失败，需要重置业务数据时加 --truncate。
#
# 环境变量（默认值与 docker-compose.yml 一致，可通过环境覆盖）：
#   SMMS_DB_HOST(127.0.0.1)  SMMS_DB_PORT(3306)  SMMS_DB_NAME(smms)
#   SMMS_DB_USER(smms)       SMMS_DB_PASSWORD(空)
#   SMMS_DB_ROOT_PASSWORD    仅 --docker 模式使用（容器内 root 账号）
#
# 示例：
#   # 本机 MySQL（小规模联调）
#   SMMS_DB_PASSWORD=*** tools/gen-perf-data.sh --goods 500 --orders 20000 --scale 0.05
#   # docker compose 部署的 MySQL（全量 2 万商品 / 200 万单，耗时较长）
#   SMMS_DB_ROOT_PASSWORD=*** tools/gen-perf-data.sh --docker --truncate
#   # 只生成不导入（校验脚本与规模设置）
#   tools/gen-perf-data.sh --dry-run --goods 100 --orders 2000
# =====================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$ROOT_DIR/tools/gen-perf-data.py"

use_docker=0
dry_run=0
gen_args=()
for arg in "$@"; do
  case "$arg" in
    --docker)  use_docker=1 ;;
    --dry-run) dry_run=1 ;;
    *)         gen_args+=("$arg") ;;
  esac
done

if [[ ! -f "$GEN" ]]; then
  echo "错误：找不到生成器 $GEN" >&2
  exit 1
fi

PY="${PYTHON:-python3}"
if ! command -v "$PY" >/dev/null 2>&1; then
  echo "错误：找不到 $PY（Python 3.8+，仅依赖标准库）" >&2
  exit 1
fi

DB_NAME="${SMMS_DB_NAME:-smms}"

if [[ "$dry_run" = 1 ]]; then
  echo "[gen-perf-data] 演练模式：仅生成 SQL 并丢弃（不入库）" >&2
  "$PY" "$GEN" ${gen_args[@]+"${gen_args[@]}"} > /dev/null
  echo "[gen-perf-data] 演练完成：生成器正常结束" >&2
  exit 0
fi

if [[ "$use_docker" = 1 ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "错误：--docker 模式需要 docker 命令" >&2
    exit 1
  fi
  : "${SMMS_DB_ROOT_PASSWORD:?--docker 模式请设置 SMMS_DB_ROOT_PASSWORD（与 .env 一致）}"
  echo "[gen-perf-data] 目标：docker compose 服务 mysql，库名 $DB_NAME" >&2
  echo "[gen-perf-data] 提示：容器须已启动（docker compose up -d mysql）" >&2
  "$PY" "$GEN" ${gen_args[@]+"${gen_args[@]}"} | (cd "$ROOT_DIR" && docker compose exec -T mysql \
      mysql --default-character-set=utf8mb4 -uroot -p"$SMMS_DB_ROOT_PASSWORD" "$DB_NAME")
else
  DB_HOST="${SMMS_DB_HOST:-127.0.0.1}"
  DB_PORT="${SMMS_DB_PORT:-3306}"
  DB_USER="${SMMS_DB_USER:-smms}"
  DB_PASSWORD="${SMMS_DB_PASSWORD:-}"
  if ! command -v mysql >/dev/null 2>&1; then
    echo "错误：找不到 mysql 客户端（或用 --docker 模式走容器内客户端）" >&2
    exit 1
  fi
  echo "[gen-perf-data] 目标：$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME" >&2
  MYSQL_PWD="$DB_PASSWORD" "$PY" "$GEN" ${gen_args[@]+"${gen_args[@]}"} | \
    mysql --default-character-set=utf8mb4 -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME"
fi

echo "[gen-perf-data] 导入完成：$DB_NAME" >&2
