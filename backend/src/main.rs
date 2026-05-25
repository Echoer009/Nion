use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json, Router, routing::{get, post, put},
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

// ==================== 应用状态 ====================

/// Axum 共享状态，持有 NionCore 实例
struct AppState {
    core: Arc<nion_core::NionCore>,
}

// ==================== 请求/响应结构体 ====================

#[derive(Deserialize)]
struct CreateChecklistRequest {
    name: String,
}

#[derive(Deserialize)]
struct UpdateChecklistRequest {
    name: String,
}

#[derive(Deserialize)]
struct ReorderRequest {
    ordered_ids: Vec<String>,
}

#[derive(Deserialize)]
struct CreateTaskRequest {
    title: String,
    description: Option<String>,
    priority: Option<String>,
    due_date: Option<String>,
    category_id: Option<String>,
    parent_id: Option<String>,
    group_id: Option<String>,
}

#[derive(Deserialize)]
struct UpdateTaskRequest {
    title: Option<String>,
    description: Option<String>,
    priority: Option<String>,
    status: Option<String>,
    due_date: Option<String>,
    category_id: Option<String>,
    reminder: Option<String>,
    group_id: Option<String>,
}

#[derive(Deserialize)]
struct UpdateTaskParentRequest {
    new_parent_id: Option<String>,
}

#[derive(Deserialize)]
struct UpdateTaskGroupRequest {
    group_id: Option<String>,
}

#[derive(Deserialize)]
struct AddFocusTimeRequest {
    seconds: i64,
}

/// 查询参数：获取指定清单（和可选分组）下的任务
#[derive(Deserialize)]
struct TasksQuery {
    category_id: Option<String>,
    group_id: Option<String>,
}

#[derive(Deserialize)]
struct CreateGroupRequest {
    name: String,
    checklist_id: String,
    color: Option<String>,
}

#[derive(Deserialize)]
struct UpdateGroupRequest {
    name: String,
    color: Option<String>,
}

#[derive(Deserialize)]
struct SettingRequest {
    key: String,
    value: String,
}

// ==================== 基础路由 ====================

/// 健康检查 —— 返回服务基本信息
async fn root() -> Json<Value> {
    Json(json!({
        "name": "Nion",
        "version": "0.1.0",
        "status": "running"
    }))
}

/// 健康检查端点
async fn health() -> Json<Value> {
    Json(json!({"status": "ok"}))
}

// ==================== 清单 API ====================

/// GET /api/checklists —— 获取所有清单
async fn get_checklists(State(state): State<Arc<AppState>>) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let checklists = core.get_checklists().map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(checklists).unwrap()))
}

/// POST /api/checklists —— 创建新清单
async fn create_checklist(
    State(state): State<Arc<AppState>>,
    Json(body): Json<CreateChecklistRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let cl = core.create_checklist(body.name).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(cl).unwrap()))
}

/// PUT /api/checklists/:id —— 修改清单名称
async fn update_checklist(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(body): Json<UpdateChecklistRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let cl = core.update_checklist_name(id, body.name).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(cl).unwrap()))
}

/// DELETE /api/checklists/:id —— 删除清单
async fn delete_checklist(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let ok = core.delete_checklist(id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": ok})))
}

/// PUT /api/checklists/reorder —— 重排清单顺序
async fn reorder_checklists(
    State(state): State<Arc<AppState>>,
    Json(body): Json<ReorderRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.reorder_checklists(body.ordered_ids).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

// ==================== 任务 API ====================

/// GET /api/tasks —— 获取所有任务
async fn get_all_tasks(State(state): State<Arc<AppState>>) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let tasks = core.get_tasks().map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(tasks).unwrap()))
}

/// GET /api/tasks/by-category —— 获取指定清单和分组的顶层任务
async fn get_tasks_by_category(
    State(state): State<Arc<AppState>>,
    Query(query): Query<TasksQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let tasks = core.get_tasks_by_category(query.category_id, query.group_id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(tasks).unwrap()))
}

/// GET /api/tasks/:id —— 获取单个任务
async fn get_task(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let task = core.get_task(id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(task).unwrap()))
}

/// POST /api/tasks —— 创建新任务
async fn create_task(
    State(state): State<Arc<AppState>>,
    Json(body): Json<CreateTaskRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let task = core.create_task(
        body.title,
        body.description,
        body.priority.unwrap_or_else(|| "medium".to_string()),
        body.due_date,
        body.category_id,
        body.parent_id,
        body.group_id,
    ).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(task).unwrap()))
}

/// PUT /api/tasks/:id —— 更新任务
async fn update_task(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(body): Json<UpdateTaskRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let task = core.update_task(
        id,
        body.title,
        body.description,
        body.priority,
        body.status,
        body.due_date,
        body.category_id,
        body.reminder,
        body.group_id,
    ).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(task).unwrap()))
}

/// DELETE /api/tasks/:id —— 删除任务
async fn delete_task(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let ok = core.delete_task(id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": ok})))
}

/// GET /api/tasks/:id/subtasks —— 获取子任务
async fn get_subtasks(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let tasks = core.get_subtasks(id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(tasks).unwrap()))
}

/// PUT /api/tasks/:id/parent —— 更新任务的父任务
async fn update_task_parent(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(body): Json<UpdateTaskParentRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.update_task_parent(id, body.new_parent_id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

/// PUT /api/tasks/:id/group —— 更新任务的分组归属
async fn update_task_group(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(body): Json<UpdateTaskGroupRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.update_task_group(id, body.group_id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

/// POST /api/tasks/:id/focus —— 给任务累加专注时长
async fn add_focus_time(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(body): Json<AddFocusTimeRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.add_focus_time(id, body.seconds).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

/// PUT /api/tasks/reorder —— 重排任务顺序
async fn reorder_tasks(
    State(state): State<Arc<AppState>>,
    Json(body): Json<ReorderRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.reorder_tasks(body.ordered_ids).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

// ==================== 分组 API ====================

/// POST /api/groups —— 创建分组
async fn create_group(
    State(state): State<Arc<AppState>>,
    Json(body): Json<CreateGroupRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let group = core.create_group(body.name, body.checklist_id, body.color)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(group).unwrap()))
}

/// GET /api/groups/:checklist_id —— 获取指定清单下的所有分组
async fn get_groups_by_checklist(
    State(state): State<Arc<AppState>>,
    Path(checklist_id): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let groups = core.get_groups_by_checklist(checklist_id)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(groups).unwrap()))
}

/// PUT /api/groups/:id —— 更新分组名称/颜色
async fn update_group(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(body): Json<UpdateGroupRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let group = core.update_group(id, body.name, body.color)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::to_value(group).unwrap()))
}

/// DELETE /api/groups/:id —— 删除分组（保留组内任务）
async fn delete_group(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let ok = core.delete_group(id).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": ok})))
}

/// PUT /api/groups/reorder —— 重排分组顺序
async fn reorder_groups(
    State(state): State<Arc<AppState>>,
    Json(body): Json<ReorderRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.reorder_groups(body.ordered_ids).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

// ==================== 设置 API ====================

/// GET /api/settings/:key —— 获取设置值
async fn get_setting(
    State(state): State<Arc<AppState>>,
    Path(key): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    let value = core.get_setting(key).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"value": value})))
}

/// PUT /api/settings —— 设置键值对
async fn set_setting(
    State(state): State<Arc<AppState>>,
    Json(body): Json<SettingRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let core = &state.core;
    core.set_setting(body.key, body.value).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({"success": true})))
}

// ==================== 入口 ====================

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("nion_backend=debug".parse().unwrap()))
        .init();

    // 初始化 NionCore（SQLite 数据库文件路径可配置）
    let db_path = std::env::var("NION_DB_PATH").unwrap_or_else(|_| "nion.db".to_string());
    let nion_core = nion_core::NionCore::new(db_path)
        .expect("Failed to initialize NionCore");
    let state = Arc::new(AppState {
        core: Arc::new(nion_core),
    });

    let app = Router::new()
        // 基础路由
        .route("/", get(root))
        .route("/health", get(health))
        // 清单 API
        .route("/api/checklists", get(get_checklists).post(create_checklist))
        .route("/api/checklists/reorder", put(reorder_checklists))
        .route("/api/checklists/{id}", put(update_checklist).delete(delete_checklist))
        // 任务 API
        .route("/api/tasks", get(get_all_tasks).post(create_task))
        .route("/api/tasks/by-category", get(get_tasks_by_category))
        .route("/api/tasks/reorder", put(reorder_tasks))
        .route("/api/tasks/{id}", get(get_task).put(update_task).delete(delete_task))
        .route("/api/tasks/{id}/subtasks", get(get_subtasks))
        .route("/api/tasks/{id}/parent", put(update_task_parent))
        .route("/api/tasks/{id}/group", put(update_task_group))
        .route("/api/tasks/{id}/focus", post(add_focus_time))
        // 分组 API
        .route("/api/groups", post(create_group))
        .route("/api/groups/{checklist_id}", get(get_groups_by_checklist))
        .route("/api/groups/reorder", put(reorder_groups))
        .route("/api/groups/{id}", put(update_group).delete(delete_group))
        // 设置 API
        .route("/api/settings/{key}", get(get_setting))
        .route("/api/settings", put(set_setting))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    tracing::info!("Nion backend running on http://localhost:3000");
    axum::serve(listener, app).await.unwrap();
}
