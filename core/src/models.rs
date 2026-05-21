#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum NionError {
    #[error("Database error: {msg}")]
    DatabaseError { msg: String },
    #[error("Not found: {msg}")]
    NotFound { msg: String },
    #[error("Validation error: {msg}")]
    ValidationError { msg: String },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ChecklistData {
    pub id: String,
    pub name: String,
    pub created_at: String,
}

#[derive(Debug, Clone, uniffi::Record)]
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
    pub created_at: String,
    pub updated_at: String,
    pub completed_at: Option<String>,
}
