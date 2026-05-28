use std::sync::Mutex;

use rusqlite::params;
use uuid::Uuid;

use crate::models::*;

#[derive(uniffi::Object)]
pub struct NionCore {
    db: Mutex<rusqlite::Connection>,
}

#[uniffi::export]
impl NionCore {
    #[uniffi::constructor]
    pub fn new(db_path: String) -> Result<Self, NionError> {
        let conn = rusqlite::Connection::open(&db_path).map_err(|e| NionError::DatabaseError {
            msg: format!("Failed to open database at {}: {}", db_path, e),
        })?;

        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS checklists (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                priority TEXT NOT NULL DEFAULT 'medium',
                status TEXT NOT NULL DEFAULT 'todo',
                due_date TEXT,
                reminder TEXT,
                parent_id TEXT,
                category_id TEXT,
                group_id TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                focus_seconds INTEGER NOT NULL DEFAULT 0,
                recurrence_rule TEXT,
                recurrence_reminder_time TEXT
            );
            CREATE TABLE IF NOT EXISTS task_groups (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                checklist_id TEXT NOT NULL,
                color TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS chat_conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                messages TEXT NOT NULL DEFAULT '[]',
                api_history TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS daily_completions (
                task_id TEXT NOT NULL,
                date TEXT NOT NULL,
                completed_at TEXT NOT NULL,
                PRIMARY KEY (task_id, date),
                FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                file_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                file_size INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS focus_sessions (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS stickers (
                id TEXT PRIMARY KEY,
                tag TEXT NOT NULL UNIQUE,
                file_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                file_size INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );"
        ).map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        // 逐条尝试添加新列，忽略已存在的错误
        conn.execute_batch("ALTER TABLE checklists ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0").ok();
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0").ok();
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN focus_seconds INTEGER NOT NULL DEFAULT 0").ok();
        // 给旧表添加 group_id 列（如果不存在）
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN group_id TEXT").ok();
        // 给旧表添加 api_history 列（如果不存在）
        conn.execute_batch("ALTER TABLE chat_conversations ADD COLUMN api_history TEXT NOT NULL DEFAULT '[]'").ok();
        // 每日循环字段迁移
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN recurrence_rule TEXT").ok();
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN recurrence_reminder_time TEXT").ok();

        Ok(Self {
            db: Mutex::new(conn),
        })
    }

    /// 根据 ID 查询单个任务，不存在则返回 NotFound 错误
    pub fn get_task(&self, id: String) -> Result<TaskData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        query_task(&db, &id)
    }

    pub fn get_tasks(&self) -> Result<Vec<TaskData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks ORDER BY sort_order ASC, created_at DESC"
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        let rows = stmt
            .query_map([], |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(result)
    }

    pub fn get_checklists(&self) -> Result<Vec<ChecklistData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare("SELECT id, name, created_at FROM checklists ORDER BY sort_order ASC, created_at ASC")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map([], |row| {
                Ok(ChecklistData {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    created_at: row.get(2)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(result)
    }

    pub fn create_checklist(&self, name: String) -> Result<ChecklistData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "INSERT INTO checklists (id, name, created_at) VALUES (?1, ?2, ?3)",
            params![id, name, now],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(ChecklistData {
            id,
            name,
            created_at: now,
        })
    }

    pub fn delete_checklist(&self, id: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let rows = db
            .execute("DELETE FROM checklists WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(rows > 0)
    }

    /// 获取指定清单（和可选分组）下的顶层任务（parent_id IS NULL）
    /// group_id = None 时不按分组过滤，返回该清单下所有未分组的顶层任务
    pub fn get_tasks_by_category(&self, category_id: Option<String>, group_id: Option<String>) -> Result<Vec<TaskData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        // 根据 category_id 和 group_id 的组合动态构建 WHERE 条件
        let mut conditions = vec![
            "parent_id IS NULL".to_string(),
        ];
        let mut param_values: Vec<Box<dyn rusqlite::types::ToSql>> = Vec::new();

        if let Some(ref cid) = category_id {
            let idx = param_values.len() + 1;
            conditions.push(format!("category_id = ?{idx}"));
            param_values.push(Box::new(cid.clone()));
        } else {
            conditions.push("category_id IS NULL".to_string());
        }

        if let Some(ref gid) = group_id {
            let idx = param_values.len() + 1;
            conditions.push(format!("group_id = ?{idx}"));
            param_values.push(Box::new(gid.clone()));
        }

        let sql = format!(
            "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE {} ORDER BY sort_order ASC, created_at DESC",
            conditions.join(" AND ")
        );
        let mut stmt = db.prepare(&sql).map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let param_refs: Vec<&dyn rusqlite::types::ToSql> = param_values.iter().map(|p| p.as_ref()).collect();
        let rows = stmt.query_map(param_refs.as_slice(), |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut tasks = Vec::new();
        for row in rows {
            tasks.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(tasks)
    }

    /// 获取今日需关注的任务（带每日任务完成状态）：
    /// 1. reminder 日期部分 = 今天的任务（普通任务，看 status 判断完成）
    /// 2. 设置了每日循环（recurrence_rule='daily'）的任务
    ///    每日任务的完成状态由 daily_completions 表决定，不看 tasks.status
    ///
    /// 参数 date: "YYYY-MM-DD" 格式的日期字符串
    /// 返回：跨所有清单聚合的顶层任务（parent_id IS NULL），附带当日完成状态
    pub fn get_tasks_due_today(&self, date: String) -> Result<Vec<DailyTaskStatus>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        // 先查出当天有完成记录的 (task_id, completed_at) 映射
        let mut comp_stmt = db
            .prepare("SELECT task_id, completed_at FROM daily_completions WHERE date = ?1")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let completions: std::collections::HashMap<String, String> = comp_stmt
            .query_map(params![date], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .filter_map(|r| r.ok())
            .collect();

        // 查询条件：
        // 1. reminder 日期部分 = date 的普通任务
        // 2. 所有每日循环模板任务
        // 排序：按时间从早到晚 → 手动排序 → 创建时间倒序
        // COALESCE 取优先级：reminder 的时间部分 > recurrence_reminder_time > '99:99'(排最后)
        let sql = "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE parent_id IS NULL AND (SUBSTR(reminder, 1, 10) = ?1 OR (recurrence_rule = 'daily' AND reminder IS NULL)) ORDER BY COALESCE(SUBSTR(reminder, 12, 5), recurrence_reminder_time, '99:99') ASC, sort_order ASC, created_at DESC";
        let mut stmt = db.prepare(sql)
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        let rows = stmt
            .query_map(params![date], |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        let mut result = Vec::new();
        for row in rows {
            let task = row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
            // 判断完成状态：每日任务查 completions 表，普通任务看 status
            let (completed, completion_date) = if task.recurrence_rule.as_deref() == Some("daily") {
                match completions.get(&task.id) {
                    Some(at) => (true, Some(at.clone())),
                    None => (false, None),
                }
            } else {
                if task.status == "done" {
                    (true, task.completed_at.clone())
                } else {
                    (false, None)
                }
            };
            result.push(DailyTaskStatus {
                task,
                completed_for_date: completed,
                completion_date,
            });
        }
        Ok(result)
    }

    pub fn get_subtasks(&self, parent_id: String) -> Result<Vec<TaskData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE parent_id = ?1 ORDER BY sort_order ASC, created_at ASC"
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map(params![parent_id], |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut tasks = Vec::new();
        for row in rows {
            tasks.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(tasks)
    }

    pub fn create_task(
        &self,
        title: String,
        description: Option<String>,
        priority: String,
        category_id: Option<String>,
        parent_id: Option<String>,
        group_id: Option<String>,
        recurrence_rule: Option<String>,
        recurrence_reminder_time: Option<String>,
    ) -> Result<TaskData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();

        db.execute(
            "INSERT INTO tasks (id, title, description, priority, status, category_id, parent_id, group_id, recurrence_rule, recurrence_reminder_time, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 'todo', ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![id, title, description, priority, category_id, parent_id, group_id, recurrence_rule, recurrence_reminder_time, now, now],
        )
        .map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        Ok(TaskData {
            id,
            title,
            description,
            priority,
            status: "todo".to_string(),
            reminder: None,
            parent_id,
            category_id,
            group_id,
            created_at: now.clone(),
            updated_at: now,
            completed_at: None,
            focus_seconds: 0,
            recurrence_rule,
            recurrence_reminder_time,
        })
    }

    pub fn update_task(
        &self,
        id: String,
        title: Option<String>,
        description: Option<String>,
        priority: Option<String>,
        status: Option<String>,
        category_id: Option<String>,
        reminder: Option<String>,
        group_id: Option<String>,
        recurrence_rule: Option<String>,
        recurrence_reminder_time: Option<String>,
    ) -> Result<TaskData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();

        let mut sets = Vec::new();
        let mut param_values: Vec<Box<dyn rusqlite::types::ToSql>> = Vec::new();

        if let Some(ref v) = title {
            sets.push(format!("title = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        if let Some(ref v) = description {
            sets.push(format!("description = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        if let Some(ref v) = priority {
            sets.push(format!("priority = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        if let Some(ref v) = status {
            sets.push(format!("status = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
            if v == "done" {
                sets.push(format!("completed_at = ?{}", param_values.len() + 1));
                param_values.push(Box::new(now.clone()));
            }
        }
        if let Some(ref v) = category_id {
            sets.push(format!("category_id = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        if let Some(ref v) = reminder {
            sets.push(format!("reminder = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        // 支持更新任务的分组归属
        if let Some(ref v) = group_id {
            sets.push(format!("group_id = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        // 每日循环规则：None 或 "none" 表示不循环，"daily" 表示每日循环
        if let Some(ref v) = recurrence_rule {
            sets.push(format!("recurrence_rule = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        // 每日循环提醒时间，格式 "HH:MM"，仅当 recurrence_rule="daily" 时有效
        if let Some(ref v) = recurrence_reminder_time {
            sets.push(format!("recurrence_reminder_time = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }

        if sets.is_empty() {
            return Err(NionError::ValidationError {
                msg: "No fields to update".to_string(),
            });
        }

        sets.push(format!("updated_at = ?{}", param_values.len() + 1));
        param_values.push(Box::new(now));

        let sql = format!(
            "UPDATE tasks SET {} WHERE id = ?{}",
            sets.join(", "),
            param_values.len() + 1
        );
        param_values.push(Box::new(id.clone()));

        let param_refs: Vec<&dyn rusqlite::types::ToSql> =
            param_values.iter().map(|p| p.as_ref()).collect();
        db.execute(&sql, param_refs.as_slice())
            .map_err(|e| NionError::DatabaseError {
                msg: e.to_string(),
            })?;

        // 如果修改了 category_id 或 group_id，需要级联更新所有子孙任务
        if category_id.is_some() || group_id.is_some() {
            cascade_to_descendants(&db, &id)?;
        }

        query_task(&db, &id)
    }

    pub fn delete_task(&self, id: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        // 先查询关联的附件文件路径，删除任务后清理磁盘文件
        let attachment_paths: Vec<String> = {
            let mut stmt = db
                .prepare("SELECT file_path FROM attachments WHERE task_id = ?1")
                .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
            let rows = stmt
                .query_map(params![id], |row| row.get(0))
                .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
            rows.filter_map(|r| r.ok()).collect()
        };
        // 删除关联的专注会话记录
        db.execute("DELETE FROM focus_sessions WHERE task_id = ?1", params![id]).ok();
        // 删除任务（attachments 通过 ON DELETE CASCADE 自动清理）
        let rows = db
            .execute("DELETE FROM tasks WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError {
                msg: e.to_string(),
            })?;
        // 删除磁盘上的附件文件（数据库行已由 CASCADE 删除）
        for path in &attachment_paths {
            std::fs::remove_file(path).ok();
        }
        Ok(rows > 0)
    }

    pub fn get_setting(&self, key: String) -> Result<Option<String>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare("SELECT value FROM settings WHERE key = ?1")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut rows = stmt.query(params![key]).map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        match rows.next() {
            Ok(Some(row)) => Ok(Some(row.get(0).map_err(|e| NionError::DatabaseError { msg: e.to_string() })?)),
            Ok(None) => Ok(None),
            Err(e) => Err(NionError::DatabaseError { msg: e.to_string() }),
        }
    }

    pub fn set_setting(&self, key: String, value: String) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?1, ?2)",
            params![key, value],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
    }

    // ==================== 附件管理 ====================

    /// 为任务添加附件记录。文件应已复制到应用内部存储，此方法仅写入数据库。
    pub fn add_attachment(
        &self,
        task_id: String,
        file_name: String,
        file_path: String,
        mime_type: String,
        file_size: i64,
    ) -> Result<AttachmentData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "INSERT INTO attachments (id, task_id, file_name, file_path, mime_type, file_size, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![id, task_id, file_name, file_path, mime_type, file_size, now],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(AttachmentData {
            id,
            task_id,
            file_name,
            file_path,
            mime_type,
            file_size,
            created_at: now,
        })
    }

    /// 删除附件记录，同时删除磁盘上的文件
    pub fn remove_attachment(&self, id: String) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        // 先查询文件路径，用于删除磁盘文件
        let file_path: Option<String> = db
            .prepare("SELECT file_path FROM attachments WHERE id = ?1")
            .and_then(|mut stmt| {
                let mut rows = stmt.query(params![id])?;
                match rows.next()? {
                    Some(row) => row.get(0),
                    None => Ok(None),
                }
            })
            .ok()
            .flatten();
        db.execute("DELETE FROM attachments WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        // 删除磁盘文件
        if let Some(path) = file_path {
            std::fs::remove_file(&path).ok();
        }
        Ok(())
    }

    /// 获取任务的所有附件
    pub fn get_attachments(&self, task_id: String) -> Result<Vec<AttachmentData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT id, task_id, file_name, file_path, mime_type, file_size, created_at
                 FROM attachments WHERE task_id = ?1 ORDER BY created_at ASC",
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map(params![task_id], |row| {
                Ok(AttachmentData {
                    id: row.get(0)?,
                    task_id: row.get(1)?,
                    file_name: row.get(2)?,
                    file_path: row.get(3)?,
                    mime_type: row.get(4)?,
                    file_size: row.get(5)?,
                    created_at: row.get(6)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    // ==================== 表情包管理 ====================

    /// 添加表情包。图片文件应已复制到内部存储，此方法仅写入数据库。
    /// tag 必须唯一，重复会返回 DatabaseError。
    pub fn create_sticker(
        &self,
        tag: String,
        file_name: String,
        file_path: String,
        mime_type: String,
        file_size: i64,
    ) -> Result<StickerData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "INSERT INTO stickers (id, tag, file_name, file_path, mime_type, file_size, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![id, tag, file_name, file_path, mime_type, file_size, now],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(StickerData {
            id,
            tag,
            file_name,
            file_path,
            mime_type,
            file_size,
            created_at: now,
        })
    }

    /// 获取所有表情包，按创建时间升序排列
    pub fn get_stickers(&self) -> Result<Vec<StickerData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT id, tag, file_name, file_path, mime_type, file_size, created_at
                 FROM stickers ORDER BY created_at ASC",
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map([], |row| {
                Ok(StickerData {
                    id: row.get(0)?,
                    tag: row.get(1)?,
                    file_name: row.get(2)?,
                    file_path: row.get(3)?,
                    mime_type: row.get(4)?,
                    file_size: row.get(5)?,
                    created_at: row.get(6)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    /// 根据标签查找表情包
    pub fn get_sticker_by_tag(&self, tag: String) -> Result<Option<StickerData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT id, tag, file_name, file_path, mime_type, file_size, created_at
                 FROM stickers WHERE tag = ?1",
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let result = stmt
            .query_row(params![tag], |row| {
                Ok(StickerData {
                    id: row.get(0)?,
                    tag: row.get(1)?,
                    file_name: row.get(2)?,
                    file_path: row.get(3)?,
                    mime_type: row.get(4)?,
                    file_size: row.get(5)?,
                    created_at: row.get(6)?,
                })
            })
            .ok();
        Ok(result)
    }

    /// 删除表情包，同时删除磁盘上的图片文件
    pub fn delete_sticker(&self, id: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        // 先查询文件路径，用于删除磁盘文件
        let file_path: Option<String> = db
            .prepare("SELECT file_path FROM stickers WHERE id = ?1")
            .and_then(|mut stmt| {
                let mut rows = stmt.query(params![id])?;
                match rows.next()? {
                    Some(row) => row.get(0),
                    None => Ok(None),
                }
            })
            .ok()
            .flatten();
        let deleted = db
            .execute("DELETE FROM stickers WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        // 删除磁盘文件
        if let Some(path) = file_path {
            std::fs::remove_file(&path).ok();
        }
        Ok(deleted > 0)
    }

    /// 更新任务的父任务 ID（用于拖拽改变层级关系）
    /// new_parent_id = None 表示提升为主任务（无父任务）。
    /// 当任务挂到新父任务下时，会自动继承新父任务的 category_id 和 group_id，
    /// 并递归级联到所有子孙任务，确保归属一致性。
    pub fn update_task_parent(&self, task_id: String, new_parent_id: Option<String>) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();

        // 更新父任务关系
        db.execute(
            "UPDATE tasks SET parent_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![new_parent_id, now, task_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        // 如果挂到了新父任务下，需要继承新父任务的 category_id 和 group_id
        if let Some(ref pid) = new_parent_id {
            let (parent_cat, parent_grp): (Option<String>, Option<String>) = db
                .query_row(
                    "SELECT category_id, group_id FROM tasks WHERE id = ?1",
                    params![pid],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .map_err(|e| NionError::DatabaseError {
                    msg: e.to_string(),
                })?;

            // 将当前任务的归属同步为新父任务的归属
            db.execute(
                "UPDATE tasks SET category_id = ?1, group_id = ?2, updated_at = ?3 WHERE id = ?4",
                params![parent_cat, parent_grp, now, task_id],
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        }

        // 级联：将当前任务的所有子孙任务的 category_id 和 group_id 同步
        cascade_to_descendants(&db, &task_id)?;

        Ok(())
    }

    /// 给指定任务累加专注时长（秒），同时写入 focus_sessions 日志
    pub fn add_focus_time(&self, task_id: String, seconds: i64) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "UPDATE tasks SET focus_seconds = focus_seconds + ?1, updated_at = ?2 WHERE id = ?3",
            params![seconds, now, task_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        // 写入专注会话日志，用于后续按日/周/月统计
        let session_id = Uuid::new_v4().to_string();
        db.execute(
            "INSERT INTO focus_sessions (id, task_id, seconds, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![session_id, task_id, seconds, now],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
    }

    /// 获取近 N 天的专注统计：每日分布 + 任务分布 + 总量
    ///
    /// 参数 days: 查询天数（7=周, 30=月, 365=年）
    pub fn get_focus_stats(&self, days: i32) -> Result<FocusStats, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let days_param = format!("-{} days", days);

        // 每日汇总：按日期聚合
        let mut daily_stmt = db
            .prepare(
                "SELECT substr(created_at, 1, 10) as d, SUM(seconds) as total, COUNT(*) as cnt
                 FROM focus_sessions
                 WHERE created_at >= date('now', ?1)
                 GROUP BY d
                 ORDER BY d DESC"
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let daily_rows = daily_stmt
            .query_map(params![days_param], |row| {
                Ok(DailyFocusStat {
                    date: row.get(0)?,
                    total_seconds: row.get(1)?,
                    session_count: row.get(2)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut daily = Vec::new();
        for r in daily_rows {
            daily.push(r.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }

        // 任务分布：按任务聚合
        let mut task_stmt = db
            .prepare(
                "SELECT s.task_id, COALESCE(t.title, '(已删除)') as title, SUM(s.seconds) as total
                 FROM focus_sessions s
                 LEFT JOIN tasks t ON t.id = s.task_id
                 WHERE s.created_at >= date('now', ?1)
                 GROUP BY s.task_id
                 ORDER BY total DESC"
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let task_rows = task_stmt
            .query_map(params![days_param], |row| {
                Ok(TaskFocusStat {
                    task_id: row.get(0)?,
                    task_title: row.get(1)?,
                    seconds: row.get(2)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut task_breakdown = Vec::new();
        for r in task_rows {
            task_breakdown.push(r.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }

        // 汇总
        let (total_seconds, total_sessions): (i64, i64) = db
            .query_row(
                "SELECT COALESCE(SUM(seconds), 0), COUNT(*) FROM focus_sessions WHERE created_at >= date('now', ?1)",
                params![days_param],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        Ok(FocusStats {
            daily,
            task_breakdown,
            total_seconds,
            total_sessions,
            days,
        })
    }

    pub fn reorder_tasks(&self, ordered_ids: Vec<String>) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute_batch("BEGIN").map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        for (i, id) in ordered_ids.iter().enumerate() {
            db.execute(
                "UPDATE tasks SET sort_order = ?1 WHERE id = ?2",
                params![i as i64, id],
            )
            .map_err(|e| {
                let _ = db.execute_batch("ROLLBACK");
                NionError::DatabaseError { msg: e.to_string() }
            })?;
        }
        db.execute_batch("COMMIT").map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
    }

    /// 修改清单名称
    pub fn update_checklist_name(&self, id: String, name: String) -> Result<ChecklistData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute(
            "UPDATE checklists SET name = ?1 WHERE id = ?2",
            params![name, id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        db.query_row(
            "SELECT id, name, created_at FROM checklists WHERE id = ?1",
            params![id],
            |row| {
                Ok(ChecklistData {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    created_at: row.get(2)?,
                })
            },
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    pub fn reorder_checklists(&self, ordered_ids: Vec<String>) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute_batch("BEGIN").map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        for (i, id) in ordered_ids.iter().enumerate() {
            db.execute(
                "UPDATE checklists SET sort_order = ?1 WHERE id = ?2",
                params![i as i64, id],
            )
            .map_err(|e| {
                let _ = db.execute_batch("ROLLBACK");
                NionError::DatabaseError { msg: e.to_string() }
            })?;
        }
        db.execute_batch("COMMIT").map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
    }

    // ==================== 分组（Group）CRUD ====================

    /// 创建分组：在指定清单下新建一个分组（如"语文"、"英语"）
    pub fn create_group(&self, name: String, checklist_id: String, color: Option<String>) -> Result<GroupData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();

        // 自动计算 sort_order：取当前清单下最大 sort_order + 1
        let max_order: i64 = db.query_row(
            "SELECT COALESCE(MAX(sort_order), -1) FROM task_groups WHERE checklist_id = ?1",
            params![checklist_id],
            |row| row.get(0),
        ).unwrap_or(-1);

        db.execute(
            "INSERT INTO task_groups (id, name, checklist_id, color, sort_order, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![id, name, checklist_id, color, max_order + 1, now],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        Ok(GroupData {
            id,
            name,
            checklist_id,
            color,
            sort_order: (max_order + 1) as i32,
            created_at: now,
        })
    }

    /// 获取指定清单下的所有分组，按 sort_order 排序
    pub fn get_groups_by_checklist(&self, checklist_id: String) -> Result<Vec<GroupData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare("SELECT id, name, checklist_id, color, sort_order, created_at FROM task_groups WHERE checklist_id = ?1 ORDER BY sort_order ASC, created_at ASC")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map(params![checklist_id], |row| {
                Ok(GroupData {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    checklist_id: row.get(2)?,
                    color: row.get(3)?,
                    sort_order: row.get(4)?,
                    created_at: row.get(5)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(result)
    }

    /// 修改分组名称和颜色
    pub fn update_group(&self, id: String, name: String, color: Option<String>) -> Result<GroupData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute(
            "UPDATE task_groups SET name = ?1, color = ?2 WHERE id = ?3",
            params![name, color, id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        db.query_row(
            "SELECT id, name, checklist_id, color, sort_order, created_at FROM task_groups WHERE id = ?1",
            params![id],
            |row| {
                Ok(GroupData {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    checklist_id: row.get(2)?,
                    color: row.get(3)?,
                    sort_order: row.get(4)?,
                    created_at: row.get(5)?,
                })
            },
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    /// 删除分组：不删除组内任务，仅将组内任务的 group_id 置空
    pub fn delete_group(&self, id: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        // 先把该分组下所有任务的 group_id 置空
        db.execute(
            "UPDATE tasks SET group_id = NULL WHERE group_id = ?1",
            params![id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        // 再删除分组本身
        let rows = db
            .execute("DELETE FROM task_groups WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(rows > 0)
    }

    /// 重排分组顺序，ordered_ids 为期望的新顺序
    pub fn reorder_groups(&self, ordered_ids: Vec<String>) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute_batch("BEGIN").map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        for (i, id) in ordered_ids.iter().enumerate() {
            db.execute(
                "UPDATE task_groups SET sort_order = ?1 WHERE id = ?2",
                params![i as i64, id],
            )
            .map_err(|e| {
                let _ = db.execute_batch("ROLLBACK");
                NionError::DatabaseError { msg: e.to_string() }
            })?;
        }
        db.execute_batch("COMMIT").map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
    }

    /// 根据 ID 获取单个分组
    pub fn get_group(&self, id: String) -> Result<GroupData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.query_row(
            "SELECT id, name, checklist_id, color, sort_order, created_at FROM task_groups WHERE id = ?1",
            params![id],
            |row| {
                Ok(GroupData {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    checklist_id: row.get(2)?,
                    color: row.get(3)?,
                    sort_order: row.get(4)?,
                    created_at: row.get(5)?,
                })
            },
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    /// 将分组移动到另一个清单，同时更新组内任务的 category_id
    /// 将整个分组移动到另一个清单。更新分组的 checklist_id，同步更新组内任务的
    /// category_id，并对每个有子任务的父任务递归级联，确保子孙任务的归属也同步变更。
    pub fn move_group_to_checklist(&self, group_id: String, checklist_id: String) -> Result<GroupData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        // 更新分组的 checklist_id
        db.execute(
            "UPDATE task_groups SET checklist_id = ?1 WHERE id = ?2",
            params![checklist_id, group_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        // 同步更新组内任务的 category_id，保持数据一致性
        db.execute(
            "UPDATE tasks SET category_id = ?1 WHERE group_id = ?2",
            params![checklist_id, group_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        // 级联：组内任务可能有子任务（子任务的 group_id 可能为空或不同），
        // 需要对每个组内顶层任务执行级联，确保其子任务也跟随移动
        let task_ids: Vec<String> = db
            .prepare("SELECT id FROM tasks WHERE group_id = ?1")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .query_map(params![group_id], |row| row.get(0))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .filter_map(|r| r.ok())
            .collect();
        for tid in task_ids {
            cascade_to_descendants(&db, &tid)?;
        }

        // 返回更新后的分组
        db.query_row(
            "SELECT id, name, checklist_id, color, sort_order, created_at FROM task_groups WHERE id = ?1",
            params![group_id],
            |row| {
                Ok(GroupData {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    checklist_id: row.get(2)?,
                    color: row.get(3)?,
                    sort_order: row.get(4)?,
                    created_at: row.get(5)?,
                })
            },
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    /// 更新任务的分组归属：将任务移到指定分组，或移出分组（group_id = None）。
    /// 同时级联更新所有子孙任务的 group_id，确保归属一致性。
    pub fn update_task_group(&self, task_id: String, group_id: Option<String>) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "UPDATE tasks SET group_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![group_id, now, task_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        // 级联：将所有子孙任务的 group_id 同步
        cascade_to_descendants(&db, &task_id)?;

        Ok(())
    }

    // ==================== 每日循环（Recurrence） ====================

    /// 设置任务的每日循环规则和提醒时间
    ///
    /// recurrence_rule: None 或 "none" 表示取消循环，"daily" 表示每日循环
    /// reminder_time: 每日提醒时间，格式 "HH:MM"，如 "09:00"
    pub fn set_task_recurrence(
        &self,
        task_id: String,
        recurrence_rule: Option<String>,
        reminder_time: Option<String>,
    ) -> Result<TaskData, NionError> {
        self.update_task(
            task_id,
            None,       // title
            None,       // description
            None,       // priority
            None,       // status
            None,       // category_id
            None,       // reminder
            None,       // group_id
            recurrence_rule,
            reminder_time,
        )
    }

    /// 移除任务的每日循环（将 recurrence_rule 和 recurrence_reminder_time 设为 NULL）
    pub fn remove_task_recurrence(&self, task_id: String) -> Result<TaskData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "UPDATE tasks SET recurrence_rule = NULL, recurrence_reminder_time = NULL, updated_at = ?1 WHERE id = ?2",
            rusqlite::params![now, task_id],
        )
        .map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        query_task(&db, &task_id)
    }

    // ==================== 每日任务完成记录（Daily Completions） ====================

    /// 标记每日任务在指定日期已完成
    /// 插入 daily_completions 记录；如果已存在则更新 completed_at
    /// 每日任务不修改 tasks.status，status 永远保持 "todo"
    pub fn complete_daily_task(&self, task_id: String, date: String) -> Result<DailyCompletion, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();
        // INSERT OR REPLACE：如果 (task_id, date) 已存在则更新 completed_at
        db.execute(
            "INSERT OR REPLACE INTO daily_completions (task_id, date, completed_at) VALUES (?1, ?2, ?3)",
            params![task_id, date, now],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(DailyCompletion {
            task_id,
            date,
            completed_at: now,
        })
    }

    /// 取消每日任务在指定日期的完成记录
    /// 删除 daily_completions 中的对应行
    pub fn uncomplete_daily_task(&self, task_id: String, date: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let rows = db
            .execute(
                "DELETE FROM daily_completions WHERE task_id = ?1 AND date = ?2",
                params![task_id, date],
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(rows > 0)
    }

    /// 查询某个每日任务在日期范围内的完成记录
    /// 用于统计、日历标记等
    pub fn get_daily_completions(
        &self,
        task_id: String,
        start_date: String,
        end_date: String,
    ) -> Result<Vec<DailyCompletion>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT task_id, date, completed_at FROM daily_completions WHERE task_id = ?1 AND date >= ?2 AND date <= ?3 ORDER BY date ASC",
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map(params![task_id, start_date, end_date], |row| {
                Ok(DailyCompletion {
                    task_id: row.get(0)?,
                    date: row.get(1)?,
                    completed_at: row.get(2)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(result)
    }

    /// 获取所有过期的每日任务
    /// 对每个 recurrence_rule='daily' 的模板任务，查找其 created_at 日期到 before_date 之间
    /// 没有 daily_completions 记录的日期，每缺一天返回一条 OverdueDailyTask
    /// 最多回溯 365 天，避免性能问题
    pub fn get_overdue_daily_tasks(&self, before_date: String) -> Result<Vec<OverdueDailyTask>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        // 查出所有每日循环模板任务
        let mut stmt = db
            .prepare(
                "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE recurrence_rule = 'daily' AND parent_id IS NULL",
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let daily_tasks: Vec<TaskData> = stmt
            .query_map([], |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .filter_map(|r| r.ok())
            .collect();

        // 解析 before_date
        let before = chrono::NaiveDate::parse_from_str(&before_date, "%Y-%m-%d")
            .map_err(|e| NionError::ValidationError {
                msg: format!("Invalid date format '{}': {}", before_date, e),
            })?;

        let mut result = Vec::new();

        for task in daily_tasks {
            // 计算生效起始日：取 created_at 的日期部分
            let created_date = chrono::NaiveDateTime::parse_from_str(&task.created_at, "%+")
                .map(|dt| dt.date())
                .or_else(|_| {
                    // 尝试只解析日期部分
                    chrono::NaiveDate::parse_from_str(&task.created_at[..10], "%Y-%m-%d")
                })
                .unwrap_or_else(|_| before);

            // 起始日 = max(created_date, before - 365天)
            let earliest = (before - chrono::Duration::days(365)).max(created_date);

            // 查出这个任务的所有完成记录
            let earliest_str = earliest.format("%Y-%m-%d").to_string();
            let before_str = before.format("%Y-%m-%d").to_string();
            let mut comp_stmt = db
                .prepare(
                    "SELECT date FROM daily_completions WHERE task_id = ?1 AND date >= ?2 AND date < ?3",
                )
                .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
            let completion_dates: std::collections::HashSet<String> = comp_stmt
                .query_map(params![task.id, earliest_str, before_str], |row| {
                    row.get::<_, String>(0)
                })
                .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
                .filter_map(|r| r.ok())
                .collect();

            // 遍历 [earliest, before) 的每一天，找出缺失的
            let mut d = earliest;
            while d < before {
                let ds = d.format("%Y-%m-%d").to_string();
                if !completion_dates.contains(&ds) {
                    result.push(OverdueDailyTask {
                        task: task.clone(),
                        overdue_date: ds,
                    });
                }
                d += chrono::Duration::days(1);
            }
        }

        // 按过期日期降序排列（最近的过期排在前面）
        result.sort_by(|a, b| b.overdue_date.cmp(&a.overdue_date));
        Ok(result)
    }

    /// 获取指定日期的所有任务（含每日任务的完成状态）
    /// 用于日程页面：返回 reminder 日期 = date 的普通任务 + 所有每日模板（附带该日完成状态）
    pub fn get_tasks_for_date(&self, date: String) -> Result<Vec<DailyTaskStatus>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        // 先查出当天有完成记录的 (task_id, completed_at) 映射
        let mut comp_stmt = db
            .prepare("SELECT task_id, completed_at FROM daily_completions WHERE date = ?1")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let completions: std::collections::HashMap<String, String> = comp_stmt
            .query_map(params![date], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .filter_map(|r| r.ok())
            .collect();

        // 查询条件：
        // 1. reminder 日期部分 = date 的普通任务（非每日循环）
        // 2. 所有每日循环模板任务
        // 排序：按时间从早到晚 → 手动排序 → 创建时间倒序
        // COALESCE 取优先级：reminder 的时间部分 > recurrence_reminder_time > '99:99'(排最后)
        let sql = "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE parent_id IS NULL AND (SUBSTR(reminder, 1, 10) = ?1 OR (recurrence_rule = 'daily' AND reminder IS NULL)) ORDER BY COALESCE(SUBSTR(reminder, 12, 5), recurrence_reminder_time, '99:99') ASC, sort_order ASC, created_at DESC";
        let mut stmt = db.prepare(sql).map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let rows = stmt
            .query_map(params![date], |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

        let mut result = Vec::new();
        for row in rows {
            let task = row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
            let (completed, completion_date) = if task.recurrence_rule.as_deref() == Some("daily") {
                // 每日任务：查 completions 表
                match completions.get(&task.id) {
                    Some(at) => (true, Some(at.clone())),
                    None => (false, None),
                }
            } else {
                // 普通任务：看 status
                if task.status == "done" {
                    (true, task.completed_at.clone())
                } else {
                    (false, None)
                }
            };
            result.push(DailyTaskStatus {
                task,
                completed_for_date: completed,
                completion_date,
            });
        }
        Ok(result)
    }

    /// 获取日历日期标记 —— 用于日程页面的日历标记
    /// 返回 start_date..=end_date 范围内每个日期的任务统计
    pub fn get_calendar_date_markers(
        &self,
        start_date: String,
        end_date: String,
    ) -> Result<Vec<CalendarDateMarker>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        let start = chrono::NaiveDate::parse_from_str(&start_date, "%Y-%m-%d")
            .map_err(|e| NionError::ValidationError {
                msg: format!("Invalid start_date '{}': {}", start_date, e),
            })?;
        let end = chrono::NaiveDate::parse_from_str(&end_date, "%Y-%m-%d")
            .map_err(|e| NionError::ValidationError {
                msg: format!("Invalid end_date '{}': {}", end_date, e),
            })?;

        // 收集范围内每天的所有完成记录
        let mut comp_stmt = db
            .prepare("SELECT date, COUNT(*) FROM daily_completions WHERE date >= ?1 AND date <= ?2 GROUP BY date")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let daily_comp_counts: std::collections::HashMap<String, i32> = comp_stmt
            .query_map(params![start_date, end_date], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i32>(1)?))
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .filter_map(|r| r.ok())
            .collect();

        // 收集范围内 reminder 日期部分落在各天的普通任务统计
        let mut due_stmt = db
            .prepare(
                "SELECT SUBSTR(reminder, 1, 10) as rd, COUNT(*), SUM(CASE WHEN status='done' THEN 1 ELSE 0 END) FROM tasks WHERE SUBSTR(reminder, 1, 10) >= ?1 AND SUBSTR(reminder, 1, 10) <= ?2 AND (recurrence_rule IS NULL OR recurrence_rule = 'none') AND parent_id IS NULL GROUP BY SUBSTR(reminder, 1, 10)",
            )
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let due_stats: std::collections::HashMap<String, (i32, i32)> = due_stmt
            .query_map(params![start_date, end_date], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    (row.get::<_, i32>(1)?, row.get::<_, i32>(2)?),
                ))
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?
            .filter_map(|r| r.ok())
            .collect();

        // 获取所有活跃的每日模板任务数量
        let daily_template_count: i32 = db
            .query_row(
                "SELECT COUNT(*) FROM tasks WHERE recurrence_rule = 'daily' AND parent_id IS NULL",
                params![],
                |row| row.get(0),
            )
            .unwrap_or(0);

        let mut result = Vec::new();
        let mut d = start;
        while d <= end {
            let ds = d.format("%Y-%m-%d").to_string();
            let (due_count, due_done) = due_stats.get(&ds).copied().unwrap_or((0, 0));
            let daily_done = daily_comp_counts.get(&ds).copied().unwrap_or(0);
            let task_count = due_count + daily_template_count;
            let completed_count = due_done + daily_done;
            // has_overdue: 如果当天的每日模板没有全部完成且日期 < 今天
            let today_str = chrono::Local::now().format("%Y-%m-%d").to_string();
            let has_overdue = ds.as_str() < today_str.as_str() && daily_done < daily_template_count;
            result.push(CalendarDateMarker {
                date: ds,
                task_count,
                completed_count,
                has_overdue,
            });
            d += chrono::Duration::days(1);
        }
        Ok(result)
    }

    // ==================== 对话记录（Conversation）CRUD ====================

    /// 保存对话：如果 id 已存在则更新，否则新建
    pub fn save_conversation(&self, id: String, title: String, messages: String, api_history: String) -> Result<ConversationData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "INSERT OR REPLACE INTO chat_conversations (id, title, messages, api_history, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![id, title, messages, api_history, now, now],
        ).map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(ConversationData {
            id,
            title,
            messages,
            api_history,
            created_at: now.clone(),
            updated_at: now,
        })
    }

    /// 获取所有对话列表，按更新时间倒序（最近的在前）
    pub fn get_conversations(&self) -> Result<Vec<ConversationData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare("SELECT id, title, messages, api_history, created_at, updated_at FROM chat_conversations ORDER BY updated_at DESC")
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let rows = stmt
            .query_map([], |row| {
                Ok(ConversationData {
                    id: row.get(0)?,
                    title: row.get(1)?,
                    messages: row.get(2)?,
                    api_history: row.get(3)?,
                    created_at: row.get(4)?,
                    updated_at: row.get(5)?,
                })
            })
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(result)
    }

    /// 获取单个对话，按 ID 查询
    pub fn get_conversation(&self, id: String) -> Result<ConversationData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.query_row(
            "SELECT id, title, messages, api_history, created_at, updated_at FROM chat_conversations WHERE id = ?1",
            params![id],
            |row| {
                Ok(ConversationData {
                    id: row.get(0)?,
                    title: row.get(1)?,
                    messages: row.get(2)?,
                    api_history: row.get(3)?,
                    created_at: row.get(4)?,
                    updated_at: row.get(5)?,
                })
            },
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })
    }

    /// 删除对话
    pub fn delete_conversation(&self, id: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let rows = db
            .execute("DELETE FROM chat_conversations WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(rows > 0)
    }
}

/// 递归级联：将 task_id 的 category_id 和 group_id 同步到所有子孙任务。
/// 当父任务移动到新清单/分组时，其子任务的归属也必须跟随变更，否则会出现
/// "父任务在清单B，子任务仍在清单A" 的数据不一致问题。
fn cascade_to_descendants(
    db: &rusqlite::Connection,
    task_id: &str,
) -> Result<(), NionError> {
    let now = chrono::Utc::now().to_rfc3339();

    // 查出当前任务的 category_id 和 group_id
    let (cat, grp): (Option<String>, Option<String>) = db
        .query_row(
            "SELECT category_id, group_id FROM tasks WHERE id = ?1",
            params![task_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

    // 更新所有直接子任务的 category_id 和 group_id，使其与父任务一致
    db.execute(
        "UPDATE tasks SET category_id = ?1, group_id = ?2, updated_at = ?3 WHERE parent_id = ?4",
        params![cat, grp, now, task_id],
    )
    .map_err(|e| NionError::DatabaseError {
        msg: e.to_string(),
    })?;

    // 查出所有直接子任务的 id，逐个递归处理（应对多层嵌套的场景）
    let child_ids: Vec<String> = db
        .prepare("SELECT id FROM tasks WHERE parent_id = ?1")
        .map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?
        .query_map(params![task_id], |row| row.get(0))
        .map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?
        .filter_map(|r| r.ok())
        .collect();

    // 对每个子任务递归级联，确保深层子任务也被更新
    for cid in child_ids {
        cascade_to_descendants(db, &cid)?;
    }

    Ok(())
}

fn map_task_row(row: &rusqlite::Row) -> rusqlite::Result<TaskData> {
    Ok(TaskData {
        id: row.get(0)?,
        title: row.get(1)?,
        description: row.get(2)?,
        priority: row.get(3)?,
        status: row.get(4)?,
        reminder: row.get(5)?,
        parent_id: row.get(6)?,
        category_id: row.get(7)?,
        group_id: row.get(8)?,
        created_at: row.get(9)?,
        updated_at: row.get(10)?,
        completed_at: row.get(11)?,
        focus_seconds: row.get(12)?,
        recurrence_rule: row.get(13)?,
        recurrence_reminder_time: row.get(14)?,
    })
}

fn query_task(db: &rusqlite::Connection, id: &str) -> Result<TaskData, NionError> {
    db.query_row(
        "SELECT id, title, description, priority, status, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE id = ?1",
        params![id],
        |row| map_task_row(row),
    )
    .map_err(|e| NionError::DatabaseError {
        msg: e.to_string(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_in_memory() -> NionCore {
        let dir = std::env::temp_dir().join(format!("nion_test_{}_{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().subsec_nanos()));
        NionCore::new(dir.to_string_lossy().to_string()).unwrap()
    }

    #[test]
    fn test_create_and_get_tasks() {
        let core = make_in_memory();
        let task = core.create_task(
            "Test task".to_string(),
            Some("A description".to_string()),
            "high".to_string(),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        assert_eq!(task.title, "Test task");
        assert_eq!(task.priority, "high");
        assert_eq!(task.status, "todo");

        let tasks = core.get_tasks().unwrap();
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].id, task.id);
    }

    #[test]
    fn test_update_task() {
        let core = make_in_memory();
        let task = core.create_task(
            "Original".to_string(),
            None,
            "medium".to_string(),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        let updated = core.update_task(
            task.id.clone(),
            Some("Updated".to_string()),
            None,
            Some("high".to_string()),
            Some("in_progress".to_string()),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        assert_eq!(updated.title, "Updated");
        assert_eq!(updated.priority, "high");
        assert_eq!(updated.status, "in_progress");
        assert!(updated.completed_at.is_none());
    }

    #[test]
    fn test_complete_task() {
        let core = make_in_memory();
        let task = core.create_task(
            "Complete me".to_string(),
            None,
            "low".to_string(),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        let updated = core.update_task(
            task.id.clone(),
            None,
            None,
            None,
            Some("done".to_string()),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        assert_eq!(updated.status, "done");
        assert!(updated.completed_at.is_some());
    }

    #[test]
    fn test_delete_task() {
        let core = make_in_memory();
        let task = core.create_task(
            "Delete me".to_string(),
            None,
            "medium".to_string(),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        assert!(core.delete_task(task.id).unwrap());
        assert_eq!(core.get_tasks().unwrap().len(), 0);
    }

    #[test]
    fn test_checklist_crud() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习".to_string()).unwrap();
        assert_eq!(cl.name, "学习");

        let lists = core.get_checklists().unwrap();
        assert_eq!(lists.len(), 1);

        assert!(core.delete_checklist(cl.id).unwrap());
        assert_eq!(core.get_checklists().unwrap().len(), 0);
    }

    #[test]
    fn test_tasks_by_category() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习".to_string()).unwrap();

        core.create_task("Task A".to_string(), None, "high".to_string(), Some(cl.id.clone()), None, None, None, None).unwrap();
        core.create_task("Task B".to_string(), None, "low".to_string(), None, None, None, None, None).unwrap();
        core.create_task("Task C".to_string(), None, "medium".to_string(), Some(cl.id.clone()), None, None, None, None).unwrap();

        let all = core.get_tasks().unwrap();
        assert_eq!(all.len(), 3);

        let in_cl = core.get_tasks_by_category(Some(cl.id.clone()), None).unwrap();
        assert_eq!(in_cl.len(), 2);

        let no_cl = core.get_tasks_by_category(None, None).unwrap();
        assert_eq!(no_cl.len(), 1);
        assert_eq!(no_cl[0].title, "Task B");
    }

    #[test]
    fn test_subtasks() {
        let core = make_in_memory();
        let parent = core.create_task("Parent".to_string(), None, "high".to_string(), None, None, None, None, None).unwrap();
        core.create_task("Child 1".to_string(), None, "low".to_string(), None, Some(parent.id.clone()), None, None, None).unwrap();
        core.create_task("Child 2".to_string(), None, "medium".to_string(), None, Some(parent.id.clone()), None, None, None).unwrap();

        let top = core.get_tasks_by_category(None, None).unwrap();
        assert_eq!(top.len(), 1);
        assert_eq!(top[0].title, "Parent");

        let subs = core.get_subtasks(parent.id.clone()).unwrap();
        assert_eq!(subs.len(), 2);
        assert_eq!(subs[0].title, "Child 1");
        assert_eq!(subs[1].title, "Child 2");
    }

    #[test]
    fn test_update_task_parent() {
        let core = make_in_memory();
        let parent_a = core.create_task("Parent A".to_string(), None, "high".to_string(), None, None, None, None, None).unwrap();
        let parent_b = core.create_task("Parent B".to_string(), None, "medium".to_string(), None, None, None, None, None).unwrap();
        let child = core.create_task("Child".to_string(), None, "low".to_string(), None, Some(parent_a.id.clone()), None, None, None).unwrap();

        // 初始：Child 是 Parent A 的子任务
        let subs_a = core.get_subtasks(parent_a.id.clone()).unwrap();
        assert_eq!(subs_a.len(), 1);
        assert_eq!(subs_a[0].id, child.id);

        // 把 Child 移到 Parent B 下
        core.update_task_parent(child.id.clone(), Some(parent_b.id.clone())).unwrap();
        let subs_a = core.get_subtasks(parent_a.id.clone()).unwrap();
        assert_eq!(subs_a.len(), 0);
        let subs_b = core.get_subtasks(parent_b.id.clone()).unwrap();
        assert_eq!(subs_b.len(), 1);
        assert_eq!(subs_b[0].id, child.id);

        // 把 Child 提升为主任务
        core.update_task_parent(child.id.clone(), None).unwrap();
        let top = core.get_tasks_by_category(None, None).unwrap();
        assert_eq!(top.len(), 3);
        let subs_b = core.get_subtasks(parent_b.id.clone()).unwrap();
        assert_eq!(subs_b.len(), 0);
    }

    #[test]
    fn test_get_task() {
        let core = make_in_memory();
        let task = core.create_task(
            "Single task".to_string(),
            Some("desc".to_string()),
            "high".to_string(),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();
        // 通过 update_task 设置 reminder
        let task = core.update_task(
            task.id.clone(),
            None, None, None, None,
            None,
            Some("2026-06-01T09:00".to_string()),
            None, None, None,
        ).unwrap();

        // 通过 ID 查询单个任务
        let found = core.get_task(task.id.clone()).unwrap();
        assert_eq!(found.id, task.id);
        assert_eq!(found.title, "Single task");
        assert_eq!(found.reminder, Some("2026-06-01T09:00".to_string()));

        // 不存在的 ID 应返回错误
        let err = core.get_task("nonexistent".to_string());
        assert!(err.is_err());
    }

    #[test]
    fn test_update_task_extended_fields() {
        let core = make_in_memory();
        let cl = core.create_checklist("工作".to_string()).unwrap();
        let task = core.create_task(
            "Task".to_string(),
            None,
            "medium".to_string(),
            None,
            None,
            None,
            None,
            None,
        ).unwrap();

        // 修改所属清单、提醒时间
        let updated = core.update_task(
            task.id.clone(),
            None,
            None,
            None,
            None,
            Some(cl.id.clone()),
            Some("2026-12-30T09:00:00Z".to_string()),
            None,
            None,
            None,
        ).unwrap();
        assert_eq!(updated.category_id, Some(cl.id));
        assert_eq!(updated.reminder, Some("2026-12-30T09:00:00Z".to_string()));
    }

    #[test]
    fn test_update_checklist_name() {
        let core = make_in_memory();
        let cl = core.create_checklist("旧名称".to_string()).unwrap();
        assert_eq!(cl.name, "旧名称");

        let updated = core.update_checklist_name(cl.id.clone(), "新名称".to_string()).unwrap();
        assert_eq!(updated.name, "新名称");
        assert_eq!(updated.id, cl.id);

        // 持久化验证
        let lists = core.get_checklists().unwrap();
        assert_eq!(lists[0].name, "新名称");
    }

    // ==================== 分组测试 ====================

    #[test]
    fn test_group_crud() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习清单".to_string()).unwrap();

        // 创建分组
        let g1 = core.create_group("语文".to_string(), cl.id.clone(), Some("#FF5722".to_string())).unwrap();
        assert_eq!(g1.name, "语文");
        assert_eq!(g1.checklist_id, cl.id);
        assert_eq!(g1.color, Some("#FF5722".to_string()));
        assert_eq!(g1.sort_order, 0);

        let g2 = core.create_group("英语".to_string(), cl.id.clone(), None).unwrap();
        assert_eq!(g2.name, "英语");
        assert_eq!(g2.sort_order, 1);

        // 获取分组列表
        let groups = core.get_groups_by_checklist(cl.id.clone()).unwrap();
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].name, "语文");
        assert_eq!(groups[1].name, "英语");

        // 更新分组
        let updated = core.update_group(g1.id.clone(), "语文（必修）".to_string(), Some("#4CAF50".to_string())).unwrap();
        assert_eq!(updated.name, "语文（必修）");
        assert_eq!(updated.color, Some("#4CAF50".to_string()));

        // 删除分组
        assert!(core.delete_group(g2.id.clone()).unwrap());
        let groups = core.get_groups_by_checklist(cl.id.clone()).unwrap();
        assert_eq!(groups.len(), 1);
    }

    #[test]
    fn test_tasks_with_group() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习清单".to_string()).unwrap();
        let g1 = core.create_group("语文".to_string(), cl.id.clone(), None).unwrap();
        let g2 = core.create_group("英语".to_string(), cl.id.clone(), None).unwrap();

        // 在不同分组下创建任务
        core.create_task("背单词".to_string(), None, "high".to_string(), Some(cl.id.clone()), None, Some(g1.id.clone()), None, None).unwrap();
        core.create_task("读课文".to_string(), None, "medium".to_string(), Some(cl.id.clone()), None, Some(g1.id.clone()), None, None).unwrap();
        core.create_task("听力练习".to_string(), None, "low".to_string(), Some(cl.id.clone()), None, Some(g2.id.clone()), None, None).unwrap();
        // 不属于任何分组的任务
        core.create_task("自习".to_string(), None, "medium".to_string(), Some(cl.id.clone()), None, None, None, None).unwrap();

        // 获取语文分组的任务
        let chinese_tasks = core.get_tasks_by_category(Some(cl.id.clone()), Some(g1.id.clone())).unwrap();
        assert_eq!(chinese_tasks.len(), 2);

        // 获取英语分组的任务
        let english_tasks = core.get_tasks_by_category(Some(cl.id.clone()), Some(g2.id.clone())).unwrap();
        assert_eq!(english_tasks.len(), 1);
        assert_eq!(english_tasks[0].title, "听力练习");

        // 获取未分组的任务（group_id = None 时不按分组过滤，返回所有）
        let all_tasks = core.get_tasks_by_category(Some(cl.id.clone()), None).unwrap();
        assert_eq!(all_tasks.len(), 4);
    }

    #[test]
    fn test_delete_group_preserves_tasks() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习清单".to_string()).unwrap();
        let g = core.create_group("语文".to_string(), cl.id.clone(), None).unwrap();

        core.create_task("背单词".to_string(), None, "high".to_string(), Some(cl.id.clone()), None, Some(g.id.clone()), None, None).unwrap();

        // 删除分组后，任务仍存在，group_id 被置空
        assert!(core.delete_group(g.id.clone()).unwrap());
        let tasks = core.get_tasks_by_category(Some(cl.id.clone()), None).unwrap();
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].group_id, None);
    }

    #[test]
    fn test_reorder_groups() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习清单".to_string()).unwrap();
        let g1 = core.create_group("语文".to_string(), cl.id.clone(), None).unwrap();
        let g2 = core.create_group("英语".to_string(), cl.id.clone(), None).unwrap();
        let g3 = core.create_group("数学".to_string(), cl.id.clone(), None).unwrap();

        // 原始顺序：语文(0), 英语(1), 数学(2)
        // 调整为：数学, 语文, 英语
        core.reorder_groups(vec![g3.id.clone(), g1.id.clone(), g2.id.clone()]).unwrap();

        let groups = core.get_groups_by_checklist(cl.id.clone()).unwrap();
        assert_eq!(groups[0].name, "数学");
        assert_eq!(groups[1].name, "语文");
        assert_eq!(groups[2].name, "英语");
    }

    #[test]
    fn test_update_task_group() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习清单".to_string()).unwrap();
        let g1 = core.create_group("语文".to_string(), cl.id.clone(), None).unwrap();
        let g2 = core.create_group("英语".to_string(), cl.id.clone(), None).unwrap();

        let task = core.create_task("某任务".to_string(), None, "medium".to_string(), Some(cl.id.clone()), None, Some(g1.id.clone()), None, None).unwrap();
        assert_eq!(task.group_id, Some(g1.id.clone()));

        // 移到英语分组
        core.update_task_group(task.id.clone(), Some(g2.id.clone())).unwrap();
        let updated = core.get_task(task.id.clone()).unwrap();
        assert_eq!(updated.group_id, Some(g2.id.clone()));

        // 移出分组
        core.update_task_group(task.id.clone(), None).unwrap();
        let updated = core.get_task(task.id.clone()).unwrap();
        assert_eq!(updated.group_id, None);
    }

    #[test]
    fn test_get_group() {
        let core = make_in_memory();
        let cl = core.create_checklist("学习清单".to_string()).unwrap();
        let g = core.create_group("语文".to_string(), cl.id.clone(), Some("#FF5722".to_string())).unwrap();

        // 按 ID 查询单个分组
        let found = core.get_group(g.id.clone()).unwrap();
        assert_eq!(found.id, g.id);
        assert_eq!(found.name, "语文");
        assert_eq!(found.checklist_id, cl.id);
        assert_eq!(found.color, Some("#FF5722".to_string()));

        // 不存在的 ID 应返回错误
        let err = core.get_group("nonexistent".to_string());
        assert!(err.is_err());
    }

    #[test]
    fn test_get_tasks_due_today() {
        let core = make_in_memory();
        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        let future = chrono::Local::now().checked_add_days(chrono::Days::new(7)).unwrap().format("%Y-%m-%d").to_string();
        // 任务 A：reminder 日期 = 今天 → 应出现在"今天"
        let task_a = core.create_task("Task A".to_string(), None, "high".to_string(), None, None, None, None, None).unwrap();
        core.update_task(task_a.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", today)), None, None, None).unwrap();
        // 任务 B：reminder 日期 = 未来 → 不应该出现
        let task_b = core.create_task("Task B".to_string(), None, "medium".to_string(), None, None, None, None, None).unwrap();
        core.update_task(task_b.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", future)), None, None, None).unwrap();
        // 任务 C：每日循环，reminder = null → 应出现在"今天"
        core.create_task("Task C".to_string(), None, "low".to_string(), None, None, None, Some("daily".to_string()), None).unwrap();
        // 任务 D：不循环 + 无 reminder → 不应该出现
        core.create_task("Task D".to_string(), None, "low".to_string(), None, None, None, None, None).unwrap();

        let today_tasks = core.get_tasks_due_today(today.clone()).unwrap();
        assert_eq!(today_tasks.len(), 2);
        // 返回类型改为 DailyTaskStatus，取 .task.title
        let titles: Vec<&str> = today_tasks.iter().map(|t| t.task.title.as_str()).collect();
        assert!(titles.contains(&"Task A"));
        assert!(titles.contains(&"Task C"));
        // 每日任务初始状态：未完成
        let task_c = today_tasks.iter().find(|t| t.task.title == "Task C").unwrap();
        assert!(!task_c.completed_for_date);
    }

    #[test]
    fn test_get_tasks_due_today_excludes_subtasks() {
        let core = make_in_memory();
        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        let parent = core.create_task("Parent".to_string(), None, "high".to_string(), None, None, None, None, None).unwrap();
        core.update_task(parent.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", today)), None, None, None).unwrap();
        // 子任务不应出现在"今天"中（parent_id IS NULL 过滤）
        let child = core.create_task("Child".to_string(), None, "low".to_string(), None, Some(parent.id.clone()), None, None, None).unwrap();
        core.update_task(child.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", today)), None, None, None).unwrap();

        let today_tasks = core.get_tasks_due_today(today).unwrap();
        assert_eq!(today_tasks.len(), 1);
        assert_eq!(today_tasks[0].task.title, "Parent");
    }

    // ==================== 每日任务完成记录测试 ====================

    #[test]
    fn test_complete_and_uncomplete_daily_task() {
        let core = make_in_memory();
        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        // 创建每日任务
        let task = core.create_task("背单词".to_string(), None, "high".to_string(), None, None, None, Some("daily".to_string()), Some("09:00".to_string())).unwrap();

        // 完成今日
        let comp = core.complete_daily_task(task.id.clone(), today.clone()).unwrap();
        assert_eq!(comp.task_id, task.id);
        assert_eq!(comp.date, today);

        // 查今日任务，应该显示已完成
        let today_tasks = core.get_tasks_due_today(today.clone()).unwrap();
        let found = today_tasks.iter().find(|t| t.task.id == task.id).unwrap();
        assert!(found.completed_for_date);
        assert!(found.completion_date.is_some());

        // 取消完成
        let removed = core.uncomplete_daily_task(task.id.clone(), today.clone()).unwrap();
        assert!(removed);

        // 再次查今日任务，应该显示未完成
        let today_tasks = core.get_tasks_due_today(today.clone()).unwrap();
        let found = today_tasks.iter().find(|t| t.task.id == task.id).unwrap();
        assert!(!found.completed_for_date);
    }

    #[test]
    fn test_get_daily_completions_range() {
        let core = make_in_memory();
        let task = core.create_task("运动".to_string(), None, "medium".to_string(), None, None, None, Some("daily".to_string()), None).unwrap();

        // 完成 3 天
        let d1 = chrono::Local::now().checked_sub_days(chrono::Days::new(2)).unwrap().format("%Y-%m-%d").to_string();
        let d2 = chrono::Local::now().checked_sub_days(chrono::Days::new(1)).unwrap().format("%Y-%m-%d").to_string();
        let d3 = chrono::Local::now().format("%Y-%m-%d").to_string();
        core.complete_daily_task(task.id.clone(), d1.clone()).unwrap();
        core.complete_daily_task(task.id.clone(), d2.clone()).unwrap();
        core.complete_daily_task(task.id.clone(), d3.clone()).unwrap();

        // 查范围
        let comps = core.get_daily_completions(task.id.clone(), d1.clone(), d3.clone()).unwrap();
        assert_eq!(comps.len(), 3);
    }

    #[test]
    fn test_get_overdue_daily_tasks() {
        let core = make_in_memory();
        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        let yesterday = chrono::Local::now().checked_sub_days(chrono::Days::new(1)).unwrap().format("%Y-%m-%d").to_string();
        let two_days_ago = chrono::Local::now().checked_sub_days(chrono::Days::new(2)).unwrap().format("%Y-%m-%d").to_string();
        let three_days_ago = chrono::Local::now().checked_sub_days(chrono::Days::new(3)).unwrap().format("%Y-%m-%d").to_string();

        // 创建每日任务（created_at = now，但我们手动把 created_at 改到 3 天前）
        let task = core.create_task("冥想".to_string(), None, "low".to_string(), None, None, None, Some("daily".to_string()), None).unwrap();

        // 手动更新 created_at 到 3 天前，让过期检测能覆盖更多天
        {
            let db = core.db.lock().unwrap();
            let past_created = chrono::Local::now().checked_sub_days(chrono::Days::new(3)).unwrap().format("%Y-%m-%dT00:00:00Z").to_string();
            db.execute("UPDATE tasks SET created_at = ?1 WHERE id = ?2", params![past_created, task.id]).unwrap();
        }

        // 完成 3 天前和今天
        core.complete_daily_task(task.id.clone(), three_days_ago.clone()).unwrap();
        core.complete_daily_task(task.id.clone(), today.clone()).unwrap();

        // 查 today 之前的过期任务（before_date = today，不包括 today）
        let overdue = core.get_overdue_daily_tasks(today.clone()).unwrap();
        // 3 天前 ✅、2 天前 ❌、昨天 ❌ → 2 条过期
        assert_eq!(overdue.len(), 2);
        let overdue_dates: Vec<&str> = overdue.iter().map(|o| o.overdue_date.as_str()).collect();
        assert!(overdue_dates.contains(&two_days_ago.as_str()));
        assert!(overdue_dates.contains(&yesterday.as_str()));
    }

    #[test]
    fn test_get_tasks_for_date() {
        let core = make_in_memory();
        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        let future = chrono::Local::now().checked_add_days(chrono::Days::new(7)).unwrap().format("%Y-%m-%d").to_string();

        // 普通任务，reminder = 今天
        let normal = core.create_task("普通任务".to_string(), None, "high".to_string(), None, None, None, None, None).unwrap();
        core.update_task(normal.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", today)), None, None, None).unwrap();
        // 每日任务
        let daily = core.create_task("每日任务".to_string(), None, "low".to_string(), None, None, None, Some("daily".to_string()), None).unwrap();
        // 未来任务（不应出现在今天）
        let future_task = core.create_task("未来任务".to_string(), None, "medium".to_string(), None, None, None, None, None).unwrap();
        core.update_task(future_task.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", future)), None, None, None).unwrap();

        // 完成每日任务今天
        core.complete_daily_task(daily.id.clone(), today.clone()).unwrap();

        let tasks = core.get_tasks_for_date(today.clone()).unwrap();
        assert_eq!(tasks.len(), 2);
        let titles: Vec<&str> = tasks.iter().map(|t| t.task.title.as_str()).collect();
        assert!(titles.contains(&"普通任务"));
        assert!(titles.contains(&"每日任务"));

        // 每日任务应标记为已完成
        let daily_status = tasks.iter().find(|t| t.task.title == "每日任务").unwrap();
        assert!(daily_status.completed_for_date);
    }

    #[test]
    fn test_get_calendar_date_markers() {
        let core = make_in_memory();
        let today = chrono::Local::now().format("%Y-%m-%d").to_string();
        let yesterday = chrono::Local::now().checked_sub_days(chrono::Days::new(1)).unwrap().format("%Y-%m-%d").to_string();

        // 每日任务
        let daily = core.create_task("每天阅读".to_string(), None, "medium".to_string(), None, None, None, Some("daily".to_string()), None).unwrap();
        // 普通任务，reminder = 今天
        let today_task = core.create_task("今天到期".to_string(), None, "high".to_string(), None, None, None, None, None).unwrap();
        core.update_task(today_task.id.clone(), None, None, None, None, None, Some(format!("{}T09:00", today)), None, None, None).unwrap();

        // 完成今天的每日任务
        core.complete_daily_task(daily.id.clone(), today.clone()).unwrap();

        let markers = core.get_calendar_date_markers(yesterday.clone(), today.clone()).unwrap();
        assert_eq!(markers.len(), 2);

        // 今天：1 每日任务（已完成）+ 1 普通任务 = 2 任务，1 完成
        let today_marker = markers.iter().find(|m| m.date == today).unwrap();
        assert_eq!(today_marker.task_count, 2);
        assert_eq!(today_marker.completed_count, 1);

        // 昨天：1 每日任务（未完成）= 有过期
        let yd_marker = markers.iter().find(|m| m.date == yesterday).unwrap();
        assert_eq!(yd_marker.task_count, 1);
        assert!(yd_marker.has_overdue);
    }

    #[test]
    fn test_move_group_to_checklist() {
        let core = make_in_memory();
        let cl1 = core.create_checklist("学习清单".to_string()).unwrap();
        let cl2 = core.create_checklist("工作清单".to_string()).unwrap();
        let g = core.create_group("语文".to_string(), cl1.id.clone(), None).unwrap();

        // 在分组下创建任务
        let task = core.create_task("背单词".to_string(), None, "high".to_string(), Some(cl1.id.clone()), None, Some(g.id.clone()), None, None).unwrap();
        assert_eq!(task.category_id, Some(cl1.id.clone()));
        assert_eq!(task.group_id, Some(g.id.clone()));

        // 将分组从"学习清单"移到"工作清单"
        let moved = core.move_group_to_checklist(g.id.clone(), cl2.id.clone()).unwrap();
        assert_eq!(moved.checklist_id, cl2.id);
        assert_eq!(moved.name, "语文");

        // 组内任务的 category_id 也应同步更新
        let updated_task = core.get_task(task.id.clone()).unwrap();
        assert_eq!(updated_task.category_id, Some(cl2.id));
        assert_eq!(updated_task.group_id, Some(g.id.clone())); // 分组归属不变
    }

    // ==================== 级联更新测试 ====================

    /// 测试：通过 update_task_parent 将一个有子任务的 task 挂到另一个清单的父任务下，
    /// 验证 task 及其子任务的 category_id 和 group_id 都跟随变更。
    #[test]
    fn test_cascade_on_parent_change() {
        let core = make_in_memory();
        let cl1 = core.create_checklist("清单A".to_string()).unwrap();
        let cl2 = core.create_checklist("清单B".to_string()).unwrap();
        let g1 = core.create_group("分组X".to_string(), cl1.id.clone(), None).unwrap();
        let g2 = core.create_group("分组Y".to_string(), cl2.id.clone(), None).unwrap();

        // 在清单B/分组Y 下创建目标父任务
        let parent_b = core.create_task(
            "Parent B".to_string(), None, "medium".to_string(),
            Some(cl2.id.clone()), None, Some(g2.id.clone()), None, None,
        ).unwrap();
        assert_eq!(parent_b.category_id, Some(cl2.id.clone()));
        assert_eq!(parent_b.group_id, Some(g2.id.clone()));

        // 在清单A/分组X 下创建一个父任务，带有子任务和孙任务
        let parent_a = core.create_task(
            "Parent A".to_string(), None, "high".to_string(),
            Some(cl1.id.clone()), None, Some(g1.id.clone()), None, None,
        ).unwrap();
        let child = core.create_task(
            "Child".to_string(), None, "low".to_string(),
            Some(cl1.id.clone()), Some(parent_a.id.clone()), Some(g1.id.clone()), None, None,
        ).unwrap();
        let grandchild = core.create_task(
            "Grandchild".to_string(), None, "low".to_string(),
            Some(cl1.id.clone()), Some(child.id.clone()), Some(g1.id.clone()), None, None,
        ).unwrap();

        // 初始：所有任务都在清单A/分组X
        assert_eq!(parent_a.category_id, Some(cl1.id.clone()));
        assert_eq!(child.category_id, Some(cl1.id.clone()));
        assert_eq!(grandchild.category_id, Some(cl1.id.clone()));

        // 把 parent_a 挂到 parent_b 下（拖拽到清单B的任务下）
        core.update_task_parent(parent_a.id.clone(), Some(parent_b.id.clone())).unwrap();

        // parent_a 应继承 parent_b 的归属
        let moved_parent = core.get_task(parent_a.id.clone()).unwrap();
        assert_eq!(moved_parent.category_id, Some(cl2.id.clone()), "parent_a 应移到清单B");
        assert_eq!(moved_parent.group_id, Some(g2.id.clone()), "parent_a 应移到分组Y");

        // 子任务应级联跟随
        let moved_child = core.get_task(child.id.clone()).unwrap();
        assert_eq!(moved_child.category_id, Some(cl2.id.clone()), "child 应跟随到清单B");
        assert_eq!(moved_child.group_id, Some(g2.id.clone()), "child 应跟随到分组Y");

        // 孙任务也应级联跟随
        let moved_gc = core.get_task(grandchild.id.clone()).unwrap();
        assert_eq!(moved_gc.category_id, Some(cl2.id.clone()), "grandchild 应跟随到清单B");
        assert_eq!(moved_gc.group_id, Some(g2.id.clone()), "grandchild 应跟随到分组Y");
    }

    /// 测试：通过 update_task 改变 category_id，验证子任务级联跟随。
    #[test]
    fn test_cascade_on_update_task_category() {
        let core = make_in_memory();
        let cl1 = core.create_checklist("清单A".to_string()).unwrap();
        let cl2 = core.create_checklist("清单B".to_string()).unwrap();

        // 在清单A下创建父+子任务
        let parent = core.create_task(
            "Parent".to_string(), None, "high".to_string(),
            Some(cl1.id.clone()), None, None, None, None,
        ).unwrap();
        let child = core.create_task(
            "Child".to_string(), None, "low".to_string(),
            Some(cl1.id.clone()), Some(parent.id.clone()), None, None, None,
        ).unwrap();

        assert_eq!(parent.category_id, Some(cl1.id.clone()));
        assert_eq!(child.category_id, Some(cl1.id.clone()));

        // 通过 update_task 将父任务移到清单B
        core.update_task(
            parent.id.clone(), None, None, None, None,
            Some(cl2.id.clone()), None, None, None, None,
        ).unwrap();

        // 子任务应级联跟随到清单B
        let moved_child = core.get_task(child.id.clone()).unwrap();
        assert_eq!(moved_child.category_id, Some(cl2.id.clone()), "child 应跟随到清单B");
    }

    /// 测试：三层嵌套（A→B→C），移动 A 后验证 C 也跟着变。
    #[test]
    fn test_cascade_deep_nesting() {
        let core = make_in_memory();
        let cl1 = core.create_checklist("清单A".to_string()).unwrap();
        let cl2 = core.create_checklist("清单B".to_string()).unwrap();

        // 创建三层嵌套：root → level1 → level2
        let root = core.create_task(
            "Root".to_string(), None, "high".to_string(),
            Some(cl1.id.clone()), None, None, None, None,
        ).unwrap();
        let level1 = core.create_task(
            "Level1".to_string(), None, "medium".to_string(),
            Some(cl1.id.clone()), Some(root.id.clone()), None, None, None,
        ).unwrap();
        let level2 = core.create_task(
            "Level2".to_string(), None, "low".to_string(),
            Some(cl1.id.clone()), Some(level1.id.clone()), None, None, None,
        ).unwrap();

        // 初始：所有任务都在清单A
        assert_eq!(root.category_id, Some(cl1.id.clone()));
        assert_eq!(level1.category_id, Some(cl1.id.clone()));
        assert_eq!(level2.category_id, Some(cl1.id.clone()));

        // 通过 update_task 把 root 移到清单B
        core.update_task(
            root.id.clone(), None, None, None, None,
            Some(cl2.id.clone()), None, None, None, None,
        ).unwrap();

        // 验证：level1 和 level2 都应级联到清单B
        let l1 = core.get_task(level1.id.clone()).unwrap();
        let l2 = core.get_task(level2.id.clone()).unwrap();
        assert_eq!(l1.category_id, Some(cl2.id.clone()), "level1 应跟随到清单B");
        assert_eq!(l2.category_id, Some(cl2.id.clone()), "level2 应跟随到清单B");
    }

    /// 测试：update_task_group 改变分组时，子任务也级联跟随。
    #[test]
    fn test_cascade_on_update_task_group() {
        let core = make_in_memory();
        let cl = core.create_checklist("清单".to_string()).unwrap();
        let g1 = core.create_group("分组A".to_string(), cl.id.clone(), None).unwrap();
        let g2 = core.create_group("分组B".to_string(), cl.id.clone(), None).unwrap();

        // 在分组A下创建父+子任务
        let parent = core.create_task(
            "Parent".to_string(), None, "medium".to_string(),
            Some(cl.id.clone()), None, Some(g1.id.clone()), None, None,
        ).unwrap();
        let child = core.create_task(
            "Child".to_string(), None, "low".to_string(),
            Some(cl.id.clone()), Some(parent.id.clone()), Some(g1.id.clone()), None, None,
        ).unwrap();

        // 将父任务移到分组B
        core.update_task_group(parent.id.clone(), Some(g2.id.clone())).unwrap();

        // 子任务应级联跟随到分组B
        let moved_child = core.get_task(child.id.clone()).unwrap();
        assert_eq!(moved_child.group_id, Some(g2.id.clone()), "child 应跟随到分组B");
    }

    /// 测试：move_group_to_checklist 时，组内任务的子任务（group_id 为空）也级联跟随。
    #[test]
    fn test_cascade_on_move_group_with_subtasks() {
        let core = make_in_memory();
        let cl1 = core.create_checklist("学习清单".to_string()).unwrap();
        let cl2 = core.create_checklist("工作清单".to_string()).unwrap();
        let g = core.create_group("语文".to_string(), cl1.id.clone(), None).unwrap();

        // 在分组下创建任务，并添加子任务（子任务也在同一分组）
        let parent = core.create_task(
            "背单词".to_string(), None, "high".to_string(),
            Some(cl1.id.clone()), None, Some(g.id.clone()), None, None,
        ).unwrap();
        let child = core.create_task(
            "复习单词".to_string(), None, "medium".to_string(),
            Some(cl1.id.clone()), Some(parent.id.clone()), Some(g.id.clone()), None, None,
        ).unwrap();

        // 将分组从"学习清单"移到"工作清单"
        core.move_group_to_checklist(g.id.clone(), cl2.id.clone()).unwrap();

        // 组内任务及其子任务的 category_id 都应更新
        let moved_parent = core.get_task(parent.id.clone()).unwrap();
        let moved_child = core.get_task(child.id.clone()).unwrap();
        assert_eq!(moved_parent.category_id, Some(cl2.id.clone()), "parent 应移到工作清单");
        assert_eq!(moved_child.category_id, Some(cl2.id.clone()), "child 也应跟随到工作清单");
    }

    /// 测试：提升任务为根任务（parent_id = None）时，不会改变 category_id/group_id，
    /// 子任务保持与被提升的任务一致。
    #[test]
    fn test_cascade_on_promote_to_root() {
        let core = make_in_memory();
        let cl = core.create_checklist("清单".to_string()).unwrap();

        let parent = core.create_task(
            "Parent".to_string(), None, "high".to_string(),
            Some(cl.id.clone()), None, None, None, None,
        ).unwrap();
        let child = core.create_task(
            "Child".to_string(), None, "low".to_string(),
            Some(cl.id.clone()), Some(parent.id.clone()), None, None, None,
        ).unwrap();

        // 把 child 提升为根任务
        core.update_task_parent(child.id.clone(), None).unwrap();

        // child 的 category_id 不应变
        let promoted = core.get_task(child.id.clone()).unwrap();
        assert_eq!(promoted.category_id, Some(cl.id.clone()), "提升后 category_id 不应变");
        assert_eq!(promoted.parent_id, None, "parent_id 应为 None");
    }
}
