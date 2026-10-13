#!/usr/bin/env bash
# =====================================================================
# SMMS 数据库全量备份脚本（FR-SYS-10 / GAP-P2-23）
#
# 用法：
#   tools/backup-db.sh              # 本机 mysql 客户端 → db/backup/*.sql
#   tools/backup-db.sh --docker     # 经 docker compose exec 在 mysql 容器内备份
#
# 说明：
#   * mysqldump --single-transaction --routines --triggers 全量备份（含存储过程/触发器）；
#   * 备份文件默认保留最近 30 份（SMMS_BACKUP_RETAIN 可覆盖，与 SRS FR-SYS-10 一致）；
#   * 建议 crontab 每日营业日结束后执行（系统内"数据备份"页亦可一键触发并查看记录）：
#       30 23 * * * cd /path/to/supermarket-product-management-system && tools/backup-db.sh >> logs/backup.log 2>&1
#     （--docker 模式需在 crontab 中同样追加 --docker）
#
# 环境变量（默认值与 docker-compose.yml 一致）：
#   SMMS_DB_HOST(127.0.0.1) SMMS_DB_PORT(3306) SMMS_DB_NAME(smms)
#   SMMS_DB_USER(smms)      SMMS_DB_PASSWORD
#   SMMS_DB_ROOT_PASSWORD   --docker 模式使用（容器内 root 账号）
#   SMMS_BACKUP_RETAIN      保留份数（默认 30）
#
# 恢复：mysql -usmms -p"$SMMS_DB_PASSWORD" < db/backup/smms-YYYYMMDD-HHMMSS.sql
#       （详见《部署与运维手册》§5.1；恢复前请先停止应用写入）
# =====================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${SMMS_BACKUP_DIR_SCRIPT:-$ROOT_DIR/db/backup}"
RETAIN="${SMMS_BACKUP_RETAIN:-30}"
DB_NAME="${SMMS_DB_NAME:-smms}"

use_docker=0
for arg in "$@"; do
  case "$arg" in
    --docker) use_docker=1 ;;
    *) echo "未知参数：$arg（支持 --docker）" >&2; exit 2 ;;
  esac
done

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$BACKUP_DIR/smms-$STAMP.sql"

echo "[backup-db] 开始备份 $DB_NAME → $FILE" >&2
if [[ "$use_docker" = 1 ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "错误：--docker 模式需要 docker 命令" >&2
    exit 1
  fi
  : "${SMMS_DB_ROOT_PASSWORD:?--docker 模式请设置 SMMS_DB_ROOT_PASSWORD（与 .env 一致）}"
  (cd "$ROOT_DIR" && docker compose exec -T mysql \
    mysqldump -uroot -p"$SMMS_DB_ROOT_PASSWORD" \
    --single-transaction --routines --triggers --databases "$DB_NAME") > "$FILE"
else
  DB_HOST="${SMMS_DB_HOST:-127.0.0.1}"
  DB_PORT="${SMMS_DB_PORT:-3306}"
  DB_USER="${SMMS_DB_USER:-smms}"
  DB_PASSWORD="${SMMS_DB_PASSWORD:-}"
  if ! command -v mysqldump >/dev/null 2>&1; then
    echo "错误：找不到 mysqldump（或用 --docker 模式走容器内客户端）" >&2
    exit 1
  fi
  MYSQL_PWD="$DB_PASSWORD" mysqldump \
    -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --single-transaction --routines --triggers --databases "$DB_NAME" > "$FILE"
fi

if [[ ! -s "$FILE" ]]; then
  echo "错误：备份文件为空，请检查数据库连接与权限" >&2
  rm -f "$FILE"
  exit 1
fi

# 保留策略：仅保留最近 N 份（按时间倒序），历史文件删除
pruned=0
while IFS= read -r old; do
  rm -f "$old"
  pruned=$((pruned + 1))
done < <(ls -1t "$BACKUP_DIR"/smms-*.sql 2>/dev/null | tail -n +"$((RETAIN + 1))")

echo "[backup-db] 完成：$(du -h "$FILE" | cut -f1)（保留最近 $RETAIN 份，本次清理 $pruned 份旧备份）" >&2
