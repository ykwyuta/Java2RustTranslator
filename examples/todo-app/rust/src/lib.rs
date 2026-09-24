//! Spring Boot の自動構成（DataSource・DispatcherServlet・静的リソース・セッション）を
//! 手で組み立てる部分。main.rs とテストの両方から使う。

pub mod clock;
pub mod domain;
pub mod mapper;
pub mod service;
pub mod web;

use std::sync::Arc;

use axum::Router;
use serde::Deserialize;
use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;
use tower_http::services::ServeDir;
use tower_http::trace::TraceLayer;
use tower_sessions::{MemoryStore, SessionManagerLayer};

use crate::clock::{Clock, SystemClock};
use crate::service::todo_service::TodoService;

/// application.yml の内容。
#[derive(Debug, Deserialize)]
pub struct AppConfig {
    pub database_url: String,
    pub port: u16,
}

impl AppConfig {
    /// config/application.toml を読み、同名の環境変数（DATABASE_URL・PORT）で上書きする。
    pub fn load() -> Result<Self, config::ConfigError> {
        config::Config::builder()
            .add_source(config::File::with_name("config/application").required(false))
            .add_source(config::Environment::default().try_parsing(true))
            .set_default("database_url", "postgres://todo:todo@localhost:5432/todo")?
            .set_default("port", 8080)?
            .build()?
            .try_deserialize()
    }
}

/// DataSource の作成と spring.sql.init（schema.sql）に相当するマイグレーションの実行。
pub async fn connect(config: &AppConfig) -> Result<PgPool, sqlx::Error> {
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&config.database_url)
        .await?;
    sqlx::migrate!("./migrations").run(&pool).await?;
    Ok(pool)
}

/// ApplicationContext に相当する。コントローラが使う Bean を持つ。
#[derive(Clone)]
pub struct AppState {
    pub todo_service: TodoService,
    pub clock: Arc<dyn Clock>,
}

impl AppState {
    pub fn new(pool: PgPool) -> Self {
        Self::with_clock(pool, Arc::new(SystemClock))
    }

    pub fn with_clock(pool: PgPool, clock: Arc<dyn Clock>) -> Self {
        Self {
            todo_service: TodoService::new(pool, clock.clone()),
            clock,
        }
    }
}

/// DispatcherServlet に相当するルータ。
/// 静的ファイルと config/ は、作業ディレクトリ（cargo run ならこの crate の直下）からの相対パスで探す。
pub fn app(state: AppState) -> Router {
    let session_layer = SessionManagerLayer::new(MemoryStore::default()).with_secure(false);
    web::routes()
        .with_state(state)
        .nest_service("/css", ServeDir::new("static/css"))
        .layer(axum_messages::MessagesManagerLayer)
        .layer(session_layer)
        .layer(TraceLayer::new_for_http())
}
