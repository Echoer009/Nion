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

/// 每日任务完成记录 —— 追踪每日循环任务在每个日期的完成状态
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DailyCompletion {
    /// 关联的模板任务 ID
    pub task_id: String,
    /// 完成日期，格式 "YYYY-MM-DD"
    pub date: String,
    /// 完成时刻，RFC 3339 格式
    pub completed_at: String,
}

/// 带日期完成状态的任务 —— 用于每日视图返回
/// 每日任务的"完成"不再看 tasks.status，而是看 daily_completions 中有无记录
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DailyTaskStatus {
    /// 原始任务数据
    pub task: TaskData,
    /// 指定日期是否已完成（每日任务查 completions 表，普通任务看 status）
    pub completed_for_date: bool,
    /// 完成时间，仅当 completed_for_date = true 时有值
    pub completion_date: Option<String>,
}

/// 过期的每日任务 —— 某个每日模板在某个历史日期没有完成记录
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct OverdueDailyTask {
    /// 原始任务数据（模板）
    pub task: TaskData,
    /// 哪一天过期的，格式 "YYYY-MM-DD"
    pub overdue_date: String,
}

/// 日历日期标记 —— 用于日程页面标记日历上的日期状态
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CalendarDateMarker {
    /// 日期，格式 "YYYY-MM-DD"
    pub date: String,
    /// 该日期有多少个任务
    pub task_count: i32,
    /// 该日期已完成多少个任务
    pub completed_count: i32,
    /// 是否有过期的每日任务
    pub has_overdue: bool,
}

/// 任务附件 —— 存储关联到任务的图片或文件
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct AttachmentData {
    pub id: String,
    /// 所属任务的 ID
    pub task_id: String,
    /// 原始文件名（用于显示）
    pub file_name: String,
    /// 应用内部存储中的文件路径（绝对路径）
    pub file_path: String,
    /// MIME 类型，如 "image/jpeg"、"application/pdf" 等
    pub mime_type: String,
    /// 文件大小（字节）
    pub file_size: i64,
    /// 创建时间，RFC 3339 格式
    pub created_at: String,
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
