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
    /// 循环规则：None 或 "none" 表示不循环，"daily" 表示每日循环
    pub recurrence_rule: Option<String>,
    /// 每日循环的提醒时间，格式为 "HH:MM"（精确到分钟），仅当 recurrence_rule="daily" 时有效
    pub recurrence_reminder_time: Option<String>,
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

/// 每日专注统计
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DailyFocusStat {
    pub date: String,
    pub total_seconds: i64,
    pub session_count: i64,
}

/// 任务专注分布
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct TaskFocusStat {
    pub task_id: String,
    pub task_title: String,
    pub seconds: i64,
}

/// 专注统计汇总：每日分布 + 任务分布 + 总时长
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct FocusStats {
    pub daily: Vec<DailyFocusStat>,
    pub task_breakdown: Vec<TaskFocusStat>,
    pub total_seconds: i64,
    pub total_sessions: i64,
    pub days: i32,
}

/// 对话记录 —— 存储一次完整的聊天会话
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ConversationData {
    pub id: String,
    pub title: String,
    pub messages: String,
    pub api_history: String,
    pub created_at: String,
    pub updated_at: String,
}
