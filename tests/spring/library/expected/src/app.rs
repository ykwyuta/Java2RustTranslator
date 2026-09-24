//! アプリの組み立て（Spring Boot の自動構成・DI コンテナ・DispatcherServlet に当たる）。Translated by Java2RustTranslator.

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

use crate::config::AppConfig;
use crate::error::Error;
use crate::service::BookService;
use crate::spring_web::BadRequest;
use crate::views::Error404View;
use crate::web::{book_controller, library_exception_handler};

/// `@Configuration` の `@Bean`。テストでは差し替えたものを `AppState::new` に渡す。
pub struct Beans;

impl Beans {
    pub fn new() -> Self {
        Self {
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
    pub book_service: BookService,
}

impl AppState {
    pub fn new(pool: PgPool, _beans: Beans) -> Self {
        let book_service = BookService::new(pool.clone());
        Self {
            book_service,
        }
    }
}

/// `@GetMapping` / `@PostMapping` などのルート（DispatcherServlet のハンドラマッピング）。
pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/books", get(book_controller::list).post(book_controller::register))
        .route("/books/new", get(book_controller::new_form))
        .route("/books/{id}", get(book_controller::detail))
        .route("/books/{id}/archive", post(book_controller::archive))
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
    (StatusCode::NOT_FOUND, Error404View {
        message: None,
    }).into_response()
}

/// DataSource（HikariCP）の作成と、schema.sql（spring.sql.init.mode=always）の実行。
pub async fn connect(config: &AppConfig) -> Result<PgPool, sqlx::Error> {
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
                Error::BookNotFound(..) => library_exception_handler::not_found(&e),
                Error::InvalidBook(..) => library_exception_handler::invalid(),
                _ => {
                    tracing::error!("{e}");
                    (StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error").into_response()
                }
            },
            AppError::BadRequest(e) => e.into_response(),
        }
    }
}
