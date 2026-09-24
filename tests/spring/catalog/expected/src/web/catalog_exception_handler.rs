//! Translated from `CatalogExceptionHandler` (CatalogExceptionHandler.java) by Java2RustTranslator.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

use crate::spring_web::render;
use crate::views::Error404View;

/// `@ExceptionHandler(ItemNotFoundException)`
pub fn not_found() -> Response {
    (StatusCode::NOT_FOUND, render(
        "error/404",
        Error404View {
        },
    )).into_response()
}
