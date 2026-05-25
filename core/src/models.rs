#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum NionError {
    #[error("Database error: {msg}")]
    DatabaseError { msg: String },
    #[error("Not found: {msg}")]
    NotFound { msg: String },
    #[error("Validation error: {msg}")]
    ValidationError { msg: String },
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ChecklistData {
    pub id: String,
    pub name: String,
    pub created_at: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct TaskData {
    pub id: String,
    pub title: String,
    pub description: Option<String>,
    pub priority: String,
    pub status: String,
    pub due_date: Option<String>,
    pub reminder: Option<String>,
    pub parent_id: Option<String>,
    pub category_id: Option<String>,
    pub group_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub completed_at: Option<String>,
    pub focus_seconds: i64,
}

/// 任务分组 —— 清单下的二级分类，例如"学习清单"下的"语文"、"英语"
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct GroupData {
    pub id: String,
    pub name: String,
    pub checklist_id: String,
    pub color: Option<String>,
    pub sort_order: i32,
    pub created_at: String,
}
