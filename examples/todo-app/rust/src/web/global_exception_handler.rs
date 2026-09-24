//! Translated from `GlobalExceptionHandler` (GlobalExceptionHandler.java) by Java2RustTranslator.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

use crate::error::Error;
use crate::views::Error404View;

/// `@ExceptionHandler(TodoNotFoundException)`
pub fn handle_not_found(_e: &Error) -> Response {
    let model_message = "指定された Todo は見つかりませんでした".to_string();
    (StatusCode::NOT_FOUND, Error404View {
        message: Some(model_message),
    }).into_response()
}
