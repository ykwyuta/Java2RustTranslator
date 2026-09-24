//! Translated from `LibraryExceptionHandler` (LibraryExceptionHandler.java) by Java2RustTranslator.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

use crate::error::Error;
use crate::views::Error404View;

/// `@ExceptionHandler(BookNotFoundException)`
pub fn not_found(e: &Error) -> Response {
    let model_message = e.to_string();
    (StatusCode::NOT_FOUND, Error404View {
        message: Some(model_message),
    }).into_response()
}

/// `@ExceptionHandler(InvalidBookException)`
pub fn invalid() -> Response {
    let model_message = "invalid book".to_string();
    (StatusCode::BAD_REQUEST, Error404View {
        message: Some(model_message),
    }).into_response()
}
