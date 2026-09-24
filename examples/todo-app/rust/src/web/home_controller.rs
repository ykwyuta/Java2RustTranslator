//! HomeController.java に相当する。

use axum::response::Response;

use super::redirect;

pub async fn home() -> Response {
    redirect("/todos")
}
