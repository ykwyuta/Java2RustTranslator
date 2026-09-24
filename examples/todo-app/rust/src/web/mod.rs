//! Spring MVC の層（@Controller・フォーム・@ControllerAdvice・テンプレート）。

pub mod global_exception_handler;
pub mod home_controller;
pub mod todo_controller;
pub mod todo_form;
pub mod views;

use axum::Router;
use axum::http::header::LOCATION;
use axum::http::{HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};

use crate::AppState;

/// @GetMapping / @PostMapping の一覧。
pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/", get(home_controller::home))
        .route(
            "/todos",
            get(todo_controller::list).post(todo_controller::create),
        )
        .route("/todos/new", get(todo_controller::new_form))
        .route("/todos/{id}", post(todo_controller::update))
        .route("/todos/{id}/edit", get(todo_controller::edit_form))
        .route("/todos/{id}/toggle", post(todo_controller::toggle))
        .route("/todos/{id}/delete", post(todo_controller::delete))
        .route(
            "/todos/completed/delete",
            post(todo_controller::delete_completed),
        )
        .fallback(global_exception_handler::not_found)
}

/// "redirect:/todos"。Spring MVC と同じく 302 Found を返す（axum の Redirect::to は 303）。
pub fn redirect(uri: &str) -> Response {
    let location = HeaderValue::from_str(uri).expect("redirect URI must be a valid header value");
    (StatusCode::FOUND, [(LOCATION, location)]).into_response()
}
