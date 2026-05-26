#!/usr/bin/env bash
# ============================================================================
# 专注统计测试数据脚本
#
# 用途：向设备上的 nion.db 插入/清除 focus_sessions 测试数据，
#       方便在专注统计面板（左滑）中验证日/周/月统计效果。
#
# 前置依赖：adb、sqlite3 命令行工具
#
# 用法：
#   ./tools/focus-test-data.sh insert   # 插入测试数据
#   ./tools/focus-test-data.sh clean    # 清除测试数据
#
# 数据分布（insert 后）：
#   - 今天：3 条记录，覆盖 2 个任务
#   - 近 7 天：每天 1-2 条，共约 10 条
#   - 近 30 天：每隔 2-3 天 1-2 条，共约 20 条
#   - 总计约 30 条记录
# ============================================================================

set -euo pipefail

# 设备上的数据库路径（Android app 私有目录）
DB_DEVICE_PATH="/data/data/com.echonion.nion/nion_data/nion.db"
# 本地临时文件
LOCAL_DB="/tmp/nion_focus_test.db"
# 测试标记前缀，用于 clean 时识别测试插入的记录（task_id 以此开头）
TEST_PREFIX="__test_focus__"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------------------------------------------------------------------------
# 从设备拉取数据库到本地
# ---------------------------------------------------------------------------
pull_db() {
    info "从设备拉取数据库..."
    adb pull "$DB_DEVICE_PATH" "$LOCAL_DB" || error "adb pull 失败，请确认设备已连接且有权限"
    # 确认文件是有效 SQLite 数据库
    sqlite3 "$LOCAL_DB" "SELECT 1;" >/dev/null 2>&1 || error "拉取的文件不是有效的 SQLite 数据库"
    info "数据库已拉取到 $LOCAL_DB"
}

# ---------------------------------------------------------------------------
# 推送本地数据库回设备
# ---------------------------------------------------------------------------
push_db() {
    info "推送数据库回设备..."
    adb push "$LOCAL_DB" "$DB_DEVICE_PATH" || error "adb push 失败"
    # 清理本地临时文件
    rm -f "$LOCAL_DB"
    info "数据库已推送并清理临时文件"
}

# ---------------------------------------------------------------------------
# 确保 focus_sessions 表存在（Rust 端 schema 中可能缺少 CREATE TABLE）
# ---------------------------------------------------------------------------
ensure_table() {
    sqlite3 "$LOCAL_DB" "
        CREATE TABLE IF NOT EXISTS focus_sessions (
            id TEXT PRIMARY KEY,
            task_id TEXT NOT NULL,
            seconds INTEGER NOT NULL,
            created_at TEXT NOT NULL
        );
    "
    info "focus_sessions 表已就绪"
}

# ---------------------------------------------------------------------------
# 获取 app 中已有的任务 ID 列表（取前 5 个）
# 如果没有任务，使用虚拟的测试 task_id
# ---------------------------------------------------------------------------
get_task_ids() {
    local ids
    ids=$(sqlite3 "$LOCAL_DB" "SELECT id FROM tasks LIMIT 5;")
    if [ -z "$ids" ]; then
        # 没有 task，使用测试专用 ID
        echo "${TEST_PREFIX}task1 ${TEST_PREFIX}task2 ${TEST_PREFIX}task3"
    else
        # 将换行替换为空格
        echo "$ids" | tr '\n' ' '
    fi
}

# ---------------------------------------------------------------------------
# 插入测试数据
# ---------------------------------------------------------------------------
do_insert() {
    info "===== 插入专注测试数据 ====="

    pull_db
    ensure_table

    # 获取可用 task_id
    local task_ids
    task_ids=($(get_task_ids))
    local num_tasks=${#task_ids[@]}
    info "可用任务数: $num_tasks，ID: ${task_ids[*]}"

    # 获取当前时间戳（ISO 8601 格式，与 Rust 端 chrono::Utc::now().to_rfc3339() 一致）
    local now_iso
    now_iso=$(date -u +"%Y-%m-%dT%H:%M:%S+00:00")

    local count=0

    # ---------- 今天的记录（3 条） ----------
    info "插入今天的记录..."
    local today=$(date -u +"%Y-%m-%d")
    local task_idx=0

    # 记录 1：25 分钟专注
    sqlite3 "$LOCAL_DB" "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES ('${TEST_PREFIX}$(printf '%04d' $count)', '${task_ids[$((task_idx % num_tasks))]}', 1500, '${today}T09:30:00+00:00');"
    count=$((count + 1))
    task_idx=$((task_idx + 1))

    # 记录 2：45 分钟专注
    sqlite3 "$LOCAL_DB" "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES ('${TEST_PREFIX}$(printf '%04d' $count)', '${task_ids[$((task_idx % num_tasks))]}', 2700, '${today}T14:00:00+00:00');"
    count=$((count + 1))
    task_idx=$((task_idx + 1))

    # 记录 3：15 分钟专注
    sqlite3 "$LOCAL_DB" "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES ('${TEST_PREFIX}$(printf '%04d' $count)', '${task_ids[$((task_idx % num_tasks))]}', 900, '${today}T20:15:00+00:00');"
    count=$((count + 1))
    task_idx=$((task_idx + 1))

    # ---------- 近 7 天的记录（昨天到 6 天前，每天 1-2 条） ----------
    info "插入近 7 天的记录..."
    for days_ago in $(seq 1 6); do
        local day
        day=$(date -u -d "$days_ago days ago" +"%Y-%m-%d" 2>/dev/null || date -u -v-${days_ago}d +"%Y-%m-%d")

        # 每天 1-2 条，偶数天多一条
        local sessions=1
        if [ $((days_ago % 2)) -eq 0 ]; then
            sessions=2
        fi

        for s in $(seq 1 $sessions); do
            local secs=$((1200 + RANDOM % 2400))  # 20-60 分钟
            local hour=$((8 + RANDOM % 12))
            local task_idx_local=$(((count + days_ago + s) % num_tasks))

            sqlite3 "$LOCAL_DB" "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES ('${TEST_PREFIX}$(printf '%04d' $count)', '${task_ids[$task_idx_local]}', $secs, '${day}T$(printf '%02d' $hour):$(printf '%02d' $((RANDOM % 60))):00+00:00');"
            count=$((count + 1))
        done
    done

    # ---------- 近 30 天的记录（8-29 天前，每隔 2-3 天） ----------
    info "插入近 30 天的记录..."
    for days_ago in $(seq 8 2 29); do
        local day
        day=$(date -u -d "$days_ago days ago" +"%Y-%m-%d" 2>/dev/null || date -u -v-${days_ago}d +"%Y-%m-%d")

        local secs=$((900 + RANDOM % 2700))  # 15-60 分钟
        local hour=$((9 + RANDOM % 10))
        local task_idx_local=$((days_ago % num_tasks))

        sqlite3 "$LOCAL_DB" "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES ('${TEST_PREFIX}$(printf '%04d' $count)', '${task_ids[$task_idx_local]}', $secs, '${day}T$(printf '%02d' $hour):$(printf '%02d' $((RANDOM % 60))):00+00:00');"
        count=$((count + 1))

        # 某些天额外加一条
        if [ $((days_ago % 3)) -eq 0 ]; then
            local secs2=$((600 + RANDOM % 1800))
            local hour2=$((14 + RANDOM % 6))
            local task_idx_local2=$(((days_ago + 1) % num_tasks))

            sqlite3 "$LOCAL_DB" "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES ('${TEST_PREFIX}$(printf '%04d' $count)', '${task_ids[$task_idx_local2]}', $secs2, '${day}T$(printf '%02d' $hour2):$(printf '%02d' $((RANDOM % 60))):00+00:00');"
            count=$((count + 1))
        fi
    done

    # 更新对应 task 的 focus_seconds（累加）
    info "更新 tasks.focus_seconds..."
    local all_test_ids
    all_test_ids=$(sqlite3 "$LOCAL_DB" "SELECT DISTINCT task_id FROM focus_sessions WHERE id LIKE '${TEST_PREFIX}%';")
    for tid in $all_test_ids; do
        # 只更新真实存在的 task（跳过虚拟测试 ID）
        local exists
        exists=$(sqlite3 "$LOCAL_DB" "SELECT COUNT(*) FROM tasks WHERE id='$tid';")
        if [ "$exists" -gt 0 ]; then
            sqlite3 "$LOCAL_DB" "UPDATE tasks SET focus_seconds = focus_seconds + (SELECT COALESCE(SUM(seconds),0) FROM focus_sessions WHERE task_id='$tid' AND id LIKE '${TEST_PREFIX}%') WHERE id='$tid';"
            info "  已更新 task $tid 的 focus_seconds"
        fi
    done

    # 验证插入结果
    local total
    total=$(sqlite3 "$LOCAL_DB" "SELECT COUNT(*) FROM focus_sessions WHERE id LIKE '${TEST_PREFIX}%';")
    info "共插入 $total 条测试记录"

    push_db
    info "===== 测试数据插入完成 ====="
}

# ---------------------------------------------------------------------------
# 清除测试数据
# ---------------------------------------------------------------------------
do_clean() {
    info "===== 清除专注测试数据 ====="

    pull_db

    # 查询即将删除的记录数
    local total
    total=$(sqlite3 "$LOCAL_DB" "SELECT COUNT(*) FROM focus_sessions WHERE id LIKE '${TEST_PREFIX}%';")

    if [ "$total" -eq 0 ]; then
        warn "没有找到测试数据（id 以 ${TEST_PREFIX} 开头），无需清理"
        rm -f "$LOCAL_DB"
        return
    fi

    info "找到 $total 条测试记录"

    # 还原 tasks.focus_seconds：减去测试记录的累计秒数
    local all_test_ids
    all_test_ids=$(sqlite3 "$LOCAL_DB" "SELECT DISTINCT task_id FROM focus_sessions WHERE id LIKE '${TEST_PREFIX}%';")
    for tid in $all_test_ids; do
        local exists
        exists=$(sqlite3 "$LOCAL_DB" "SELECT COUNT(*) FROM tasks WHERE id='$tid';")
        if [ "$exists" -gt 0 ]; then
            local deduct
            deduct=$(sqlite3 "$LOCAL_DB" "SELECT COALESCE(SUM(seconds),0) FROM focus_sessions WHERE task_id='$tid' AND id LIKE '${TEST_PREFIX}%';")
            sqlite3 "$LOCAL_DB" "UPDATE tasks SET focus_seconds = MAX(0, focus_seconds - $deduct) WHERE id='$tid';"
            info "  已还原 task $tid 的 focus_seconds (-${deduct}s)"
        fi
    done

    # 删除测试记录
    sqlite3 "$LOCAL_DB" "DELETE FROM focus_sessions WHERE id LIKE '${TEST_PREFIX}%';"
    info "已删除 $total 条测试记录"

    push_db
    info "===== 测试数据清除完成 ====="
}

# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
case "${1:-}" in
    insert)
        do_insert
        ;;
    clean)
        do_clean
        ;;
    *)
        echo "用法: $0 {insert|clean}"
        echo ""
        echo "  insert  — 插入专注统计测试数据（约 30 条，覆盖近 30 天）"
        echo "  clean   — 清除所有测试数据（id 以 ${TEST_PREFIX} 开头的记录）"
        exit 1
        ;;
esac
