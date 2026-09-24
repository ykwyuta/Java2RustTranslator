//! GlobalExceptionHandler.java（@ControllerAdvice）と、Spring Boot の既定のエラーページ
//! （templates/error/404.html）に相当する。

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

use todo_core::error::Error;

use super::views::NotFoundView;

/// ハンドラのエラー。サービスのエラー（todo_core::error::Error）を包む
/// （別の crate の型に IntoResponse を実装できないため）。
#[derive(Debug)]
pub struct AppError(pub Error);

impl From<Error> for AppError {
    fn from(e: Error) -> Self {
        Self(e)
    }
}

/// ハンドラが Err を返したときのレスポンス。@ExceptionHandler に当たる。
impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        match self.0 {
            Error::TodoNotFound(_) => (
                StatusCode::NOT_FOUND,
                NotFoundView {
                    message: Some("指定された Todo は見つかりませんでした".into()),
                },
            )
                .into_response(),
            Error::Database(e) => {
                tracing::error!("database error: {e}");
                (StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error").into_response()
            }
        }
    }
}

/// どのルートにも当たらないリクエスト。
pub async fn not_found() -> Response {
    (StatusCode::NOT_FOUND, NotFoundView { message: None }).into_response()
}
