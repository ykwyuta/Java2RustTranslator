//! Translated from `HomeController` (HomeController.java) by Java2RustTranslator.

use axum::response::Response;

use crate::app::AppError;
use crate::spring_web::redirect;

/// `GET /`
pub async fn home() -> Result<Response, AppError> {
    Ok(redirect("/todos", &[]))
}
