//! TodoController.java に相当する。

use axum::Form;
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use axum_messages::Messages;
use serde::Deserialize;

use super::redirect;
use super::todo_form::{FieldErrors, TodoForm};
use super::views::{TodoFormView, TodoListView};
use crate::AppState;
use crate::domain::TodoFilter;
use crate::service::ServiceError;

type Result<T> = std::result::Result<T, ServiceError>;

/// @RequestParam(name = "filter", required = false) と @RequestParam(name = "q", required = false)
#[derive(Debug, Default, Deserialize)]
pub struct ListParams {
    filter: Option<String>,
    q: Option<String>,
}

/// GET /todos
pub async fn list(
    State(state): State<AppState>,
    Query(params): Query<ListParams>,
    messages: Messages,
) -> Result<TodoListView> {
    let filter = TodoFilter::from_param(params.filter.as_deref());
    Ok(TodoListView {
        todos: state
            .todo_service
            .find_all(filter, params.q.as_deref())
            .await?,
        summary: state.todo_service.summary().await?,
        filter: filter.param(),
        q: params.q.unwrap_or_default(),
        today: state.clock.today(),
        message: messages.into_iter().last().map(|m| m.message),
    })
}

/// GET /todos/new
pub async fn new_form() -> TodoFormView {
    TodoFormView {
        todo_id: None,
        form: TodoForm::default(),
        errors: FieldErrors::default(),
    }
}

/// POST /todos
pub async fn create(
    State(state): State<AppState>,
    messages: Messages,
    Form(form): Form<TodoForm>,
) -> Result<Response> {
    let input = match form.validated() {
        Ok(input) => input,
        Err(errors) => {
            return Ok(TodoFormView {
                todo_id: None,
                form,
                errors,
            }
            .into_response());
        }
    };
    let todo = state
        .todo_service
        .create(input.title, input.description, input.due_date)
        .await?;
    messages.info(format!("「{}」を追加しました", todo.title));
    Ok(redirect("/todos"))
}

/// GET /todos/{id}/edit
pub async fn edit_form(State(state): State<AppState>, Path(id): Path<i64>) -> Result<TodoFormView> {
    let todo = state.todo_service.find_by_id(id).await?;
    Ok(TodoFormView {
        todo_id: Some(id),
        form: TodoForm::from_todo(&todo),
        errors: FieldErrors::default(),
    })
}

/// POST /todos/{id}
pub async fn update(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    messages: Messages,
    Form(form): Form<TodoForm>,
) -> Result<Response> {
    let input = match form.validated() {
        Ok(input) => input,
        Err(errors) => {
            return Ok(TodoFormView {
                todo_id: Some(id),
                form,
                errors,
            }
            .into_response());
        }
    };
    let todo = state
        .todo_service
        .update(
            id,
            input.title,
            input.description,
            input.due_date,
            input.done,
        )
        .await?;
    messages.info(format!("「{}」を更新しました", todo.title));
    Ok(redirect("/todos"))
}

/// @RequestParam(name = "filter", required = false)
#[derive(Debug, Default, Deserialize)]
pub struct ToggleParams {
    filter: Option<String>,
}

/// POST /todos/{id}/toggle
pub async fn toggle(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(params): Form<ToggleParams>,
) -> Result<Response> {
    state.todo_service.toggle(id).await?;
    let filter = TodoFilter::from_param(params.filter.as_deref());
    Ok(redirect(&format!("/todos?filter={}", filter.param())))
}

/// POST /todos/{id}/delete
pub async fn delete(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    messages: Messages,
) -> Result<Response> {
    let todo = state.todo_service.delete(id).await?;
    messages.info(format!("「{}」を削除しました", todo.title));
    Ok(redirect("/todos"))
}

/// POST /todos/completed/delete
pub async fn delete_completed(
    State(state): State<AppState>,
    messages: Messages,
) -> Result<Response> {
    let count = state.todo_service.delete_completed().await?;
    messages.info(format!("完了済みの {count} 件を削除しました"));
    Ok(redirect("/todos"))
}
