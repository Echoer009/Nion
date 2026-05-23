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
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                focus_seconds INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );"
        ).map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;

        // 逐条尝试添加新列，忽略已存在的错误
        conn.execute_batch("ALTER TABLE checklists ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0").ok();
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0").ok();
        conn.execute_batch("ALTER TABLE tasks ADD COLUMN focus_seconds INTEGER NOT NULL DEFAULT 0").ok();

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
                "SELECT id, title, description, priority, status, due_date, reminder, parent_id, category_id, created_at, updated_at, completed_at, focus_seconds FROM tasks ORDER BY sort_order ASC, created_at DESC"
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

    pub fn get_tasks_by_category(&self, category_id: Option<String>) -> Result<Vec<TaskData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let sql = if category_id.is_some() {
            "SELECT id, title, description, priority, status, due_date, reminder, parent_id, category_id, created_at, updated_at, completed_at, focus_seconds FROM tasks WHERE category_id = ?1 AND parent_id IS NULL ORDER BY sort_order ASC, created_at DESC"
        } else {
            "SELECT id, title, description, priority, status, due_date, reminder, parent_id, category_id, created_at, updated_at, completed_at, focus_seconds FROM tasks WHERE category_id IS NULL AND parent_id IS NULL ORDER BY sort_order ASC, created_at DESC"
        };
        let mut stmt = db.prepare(sql).map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let params: Vec<Box<dyn rusqlite::types::ToSql>> = if let Some(ref cid) = category_id {
            vec![Box::new(cid.clone())]
        } else {
            vec![]
        };
        let param_refs: Vec<&dyn rusqlite::types::ToSql> = params.iter().map(|p| p.as_ref()).collect();
        let rows = stmt.query_map(param_refs.as_slice(), |row| map_task_row(row))
            .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        let mut tasks = Vec::new();
        for row in rows {
            tasks.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
        }
        Ok(tasks)
    }

    pub fn get_subtasks(&self, parent_id: String) -> Result<Vec<TaskData>, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let mut stmt = db
            .prepare(
                "SELECT id, title, description, priority, status, due_date, reminder, parent_id, category_id, created_at, updated_at, completed_at, focus_seconds FROM tasks WHERE parent_id = ?1 ORDER BY sort_order ASC, created_at ASC"
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
        due_date: Option<String>,
        category_id: Option<String>,
        parent_id: Option<String>,
    ) -> Result<TaskData, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();

        db.execute(
            "INSERT INTO tasks (id, title, description, priority, status, due_date, category_id, parent_id, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 'todo', ?5, ?6, ?7, ?8, ?9)",
            params![id, title, description, priority, due_date, category_id, parent_id, now, now],
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
            due_date,
            reminder: None,
            parent_id,
            category_id,
            created_at: now.clone(),
            updated_at: now,
            completed_at: None,
            focus_seconds: 0,
        })
    }

    pub fn update_task(
        &self,
        id: String,
        title: Option<String>,
        description: Option<String>,
        priority: Option<String>,
        status: Option<String>,
        due_date: Option<String>,
        category_id: Option<String>,
        reminder: Option<String>,
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
        // 新增：支持修改截止日期、所属清单、提醒时间
        if let Some(ref v) = due_date {
            sets.push(format!("due_date = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        if let Some(ref v) = category_id {
            sets.push(format!("category_id = ?{}", param_values.len() + 1));
            param_values.push(Box::new(v.clone()));
        }
        if let Some(ref v) = reminder {
            sets.push(format!("reminder = ?{}", param_values.len() + 1));
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

        query_task(&db, &id)
    }

    pub fn delete_task(&self, id: String) -> Result<bool, NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let rows = db
            .execute("DELETE FROM tasks WHERE id = ?1", params![id])
            .map_err(|e| NionError::DatabaseError {
                msg: e.to_string(),
            })?;
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

    /// 更新任务的父任务 ID（用于拖拽改变层级关系）
    /// new_parent_id = None 表示提升为主任务（无父任务）
    pub fn update_task_parent(&self, task_id: String, new_parent_id: Option<String>) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        let now = chrono::Utc::now().to_rfc3339();
        db.execute(
            "UPDATE tasks SET parent_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![new_parent_id, now, task_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
    }

    /// 给指定任务累加专注时长（秒）
    pub fn add_focus_time(&self, task_id: String, seconds: i64) -> Result<(), NionError> {
        let db = self.db.lock().map_err(|e| NionError::DatabaseError {
            msg: e.to_string(),
        })?;
        db.execute(
            "UPDATE tasks SET focus_seconds = focus_seconds + ?1, updated_at = ?2 WHERE id = ?3",
            params![seconds, chrono::Utc::now().to_rfc3339(), task_id],
        )
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
        Ok(())
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
}

fn map_task_row(row: &rusqlite::Row) -> rusqlite::Result<TaskData> {
    Ok(TaskData {
        id: row.get(0)?,
        title: row.get(1)?,
        description: row.get(2)?,
        priority: row.get(3)?,
        status: row.get(4)?,
        due_date: row.get(5)?,
        reminder: row.get(6)?,
        parent_id: row.get(7)?,
        category_id: row.get(8)?,
        created_at: row.get(9)?,
        updated_at: row.get(10)?,
        completed_at: row.get(11)?,
        focus_seconds: row.get(12)?,
    })
}

fn query_task(db: &rusqlite::Connection, id: &str) -> Result<TaskData, NionError> {
    db.query_row(
        "SELECT id, title, description, priority, status, due_date, reminder, parent_id, category_id, created_at, updated_at, completed_at, focus_seconds FROM tasks WHERE id = ?1",
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

        core.create_task("Task A".to_string(), None, "high".to_string(), None, Some(cl.id.clone()), None).unwrap();
        core.create_task("Task B".to_string(), None, "low".to_string(), None, None, None).unwrap();
        core.create_task("Task C".to_string(), None, "medium".to_string(), None, Some(cl.id.clone()), None).unwrap();

        let all = core.get_tasks().unwrap();
        assert_eq!(all.len(), 3);

        let in_cl = core.get_tasks_by_category(Some(cl.id.clone())).unwrap();
        assert_eq!(in_cl.len(), 2);

        let no_cl = core.get_tasks_by_category(None).unwrap();
        assert_eq!(no_cl.len(), 1);
        assert_eq!(no_cl[0].title, "Task B");
    }

    #[test]
    fn test_subtasks() {
        let core = make_in_memory();
        let parent = core.create_task("Parent".to_string(), None, "high".to_string(), None, None, None).unwrap();
        core.create_task("Child 1".to_string(), None, "low".to_string(), None, None, Some(parent.id.clone())).unwrap();
        core.create_task("Child 2".to_string(), None, "medium".to_string(), None, None, Some(parent.id.clone())).unwrap();

        let top = core.get_tasks_by_category(None).unwrap();
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
        let parent_a = core.create_task("Parent A".to_string(), None, "high".to_string(), None, None, None).unwrap();
        let parent_b = core.create_task("Parent B".to_string(), None, "medium".to_string(), None, None, None).unwrap();
        let child = core.create_task("Child".to_string(), None, "low".to_string(), None, None, Some(parent_a.id.clone())).unwrap();

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
        let top = core.get_tasks_by_category(None).unwrap();
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
            Some("2026-06-01".to_string()),
            None,
            None,
        ).unwrap();

        // 通过 ID 查询单个任务
        let found = core.get_task(task.id.clone()).unwrap();
        assert_eq!(found.id, task.id);
        assert_eq!(found.title, "Single task");
        assert_eq!(found.due_date, Some("2026-06-01".to_string()));

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
        ).unwrap();

        // 修改截止日期、所属清单、提醒时间
        let updated = core.update_task(
            task.id.clone(),
            None,
            None,
            None,
            None,
            Some("2026-12-31".to_string()),
            Some(cl.id.clone()),
            Some("2026-12-30T09:00:00Z".to_string()),
        ).unwrap();
        assert_eq!(updated.due_date, Some("2026-12-31".to_string()));
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
}
