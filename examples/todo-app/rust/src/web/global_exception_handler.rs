//! GlobalExceptionHandler.java（@ControllerAdvice）と、Spring Boot の既定のエラーページ
//! （templates/error/404.html）に相当する。

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

use super::views::NotFoundView;
use crate::service::ServiceError;

/// ハンドラが Err(ServiceError) を返したときのレスポンス。@ExceptionHandler に当たる。
impl IntoResponse for ServiceError {
    fn into_response(self) -> Response {
        match self {
            ServiceError::TodoNotFound(_) => (
                StatusCode::NOT_FOUND,
                NotFoundView {
                    message: Some("指定された Todo は見つかりませんでした".into()),
                },
            )
                .into_response(),
            ServiceError::Database(e) => {
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
