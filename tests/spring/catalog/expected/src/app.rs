//! アプリの組み立て（Spring Boot の自動構成・DI コンテナ・DispatcherServlet に当たる）。Translated by Java2RustTranslator.

use std::sync::Arc;

use axum::Router;
use axum::handler::HandlerWithoutStateExt;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;
use tower_http::services::ServeDir;
use tower_http::trace::TraceLayer;
use tower_sessions::{MemoryStore, SessionManagerLayer};

use crate::AppConfig;
use crate::clock::Clock;
use crate::error::Error;
use crate::service::{AuditService, ItemService, PricingService};
use crate::spring_web::{BadRequest, render};
use crate::views::Error404View;
use crate::web::{catalog_exception_handler, item_controller};

/// `@Configuration` の `@Bean`。テストでは差し替えたものを `AppState::new` に渡す。
pub struct Beans {
    pub clock: Arc<dyn Clock>,
}

impl Beans {
    pub fn new() -> Self {
        Self {
            clock: AppConfig::clock(),
        }
    }
}

impl Default for Beans {
    fn default() -> Self {
        Self::new()
    }
}

/// コントローラが使う Bean（Spring の ApplicationContext に当たる）。
#[derive(Clone)]
pub struct AppState {
    pub clock: Arc<dyn Clock>,
    pub audit_service: AuditService,
    pub pricing_service: PricingService,
    pub item_service: ItemService,
}

impl AppState {
    pub fn new(pool: PgPool, beans: Beans) -> Self {
        let clock = beans.clock;
        let audit_service = AuditService::new(pool.clone(), clock.clone());
        let pricing_service = PricingService::new(pool.clone());
        let item_service = ItemService::new(
            pool.clone(),
            audit_service.clone(),
            pricing_service.clone(),
        );
        Self {
            clock,
            audit_service,
            pricing_service,
            item_service,
        }
    }
}

/// `@GetMapping` / `@PostMapping` などのルート（DispatcherServlet のハンドラマッピング）。
pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/items", get(item_controller::list).post(item_controller::create))
        .route("/items/new", get(item_controller::new_form))
        .route("/items/{id}/discount", post(item_controller::discount))
}

/// ルートに静的リソース（`static/`）・セッション・アクセスログを足したアプリ。
pub fn app(state: AppState) -> Router {
    let sessions = SessionManagerLayer::new(MemoryStore::default())
        .with_secure(false)
        .with_name("JSESSIONID");
    let resources = ServeDir::new("static")
        .call_fallback_on_method_not_allowed(true)
        .fallback(not_found.into_service());
    routes()
        .with_state(state)
        .fallback_service(resources)
        .layer(sessions)
        .layer(TraceLayer::new_for_http())
}

/// どのルートにも当たらないリクエスト（Spring Boot の既定のエラーページ templates/error/404.html）。
async fn not_found() -> Response {
    (StatusCode::NOT_FOUND, render("error/404", Error404View {
    })).into_response()
}

/// DataSource（HikariCP）の作成と、schema.sql（spring.sql.init.mode=always）の実行。
pub async fn connect(config: &crate::config::AppConfig) -> Result<PgPool, sqlx::Error> {
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&config.database_url)
        .await?;
    crate::MIGRATOR.run(&pool).await?;
    Ok(pool)
}

/// ハンドラのエラー（サービスの例外と、リクエストの不正）。
#[derive(Debug)]
pub enum AppError {
    Service(Error),
    BadRequest(BadRequest),
}

impl From<Error> for AppError {
    fn from(e: Error) -> Self {
        Self::Service(e)
    }
}

impl From<BadRequest> for AppError {
    fn from(e: BadRequest) -> Self {
        Self::BadRequest(e)
    }
}

impl IntoResponse for AppError {
    /// 例外をレスポンスにする（`@ControllerAdvice` の `@ExceptionHandler`。ほかの例外は 500）。
    fn into_response(self) -> Response {
        match self {
            AppError::Service(e) => match &e {
                Error::ItemNotFound(..) => catalog_exception_handler::not_found(),
                _ => {
                    tracing::error!("{e}");
                    (StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error").into_response()
                }
            },
            AppError::BadRequest(e) => e.into_response(),
        }
    }
}
