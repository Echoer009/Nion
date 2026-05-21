use axum::{Json, Router, routing::get};
use serde_json::{json, Value};
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

async fn root() -> Json<Value> {
    Json(json!({
        "name": "Nion",
        "version": "0.1.0",
        "status": "running"
    }))
}

async fn health() -> Json<Value> {
    Json(json!({"status": "ok"}))
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("nion_backend=debug".parse().unwrap()))
        .init();

    let app = Router::new()
        .route("/", get(root))
        .route("/health", get(health))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http());

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    tracing::info!("Nion backend running on http://localhost:3000");
    axum::serve(listener, app).await.unwrap();
}
