//! Translated from `TodoController` (TodoController.java) by Java2RustTranslator.

use axum::extract::{Path, State};
use axum::response::Response;
use serde::Deserialize;

use crate::app::{AppError, AppState};
use crate::domain::{Priority, TodoFilter};
use crate::spring_web::{BindingResult, Flash, RequestParams, redirect, render};
use crate::views::{TodosFormView, TodosListView};
use crate::web::TodoForm;

/// フォームの優先度の選択肢（このコントローラのすべてのハンドラのモデルに入る）。
///
/// `@ModelAttribute("priorities")`
fn priorities() -> Vec<Priority> {
    Priority::values().to_vec()
}

/// `list` の `@RequestParam`。
#[derive(Debug, Deserialize)]
pub struct ListParams {
    filter: Option<String>,
    q: Option<String>,
}

/// `GET /todos`
pub async fn list(
    State(state): State<AppState>,
    flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let request: ListParams = params.parse()?;
    let filter_param = request.filter.as_deref();
    let keyword = request.q.as_deref();
    let model_priorities = priorities();
    let filter = TodoFilter::from_param(filter_param);
    let model_todos = state.todo_service.find_all(filter, keyword).await?;
    let model_summary = state.todo_service.summary().await?;
    let model_filter = filter.param();
    let model_q = keyword.unwrap_or("").to_string();
    let model_today = state.clock.today();
    let model_activities = state.activity_service.recent(5).await?;
    Ok(
        render(
            "todos/list",
            TodosListView {
                priorities: model_priorities,
                todos: model_todos,
                summary: model_summary,
                filter: model_filter,
                q: model_q,
                today: model_today,
                activities: model_activities,
                message: flash.get("message"),
            },
        ),
    )
}

/// `GET /todos/new`
pub async fn new_form() -> Result<Response, AppError> {
    let model_priorities = priorities();
    let model_todo_form = TodoForm::default();
    Ok(
        render(
            "todos/form",
            TodosFormView {
                priorities: model_priorities,
                todo_form: model_todo_form,
                todo_id: None,
                todo_form_binding: BindingResult::default(),
            },
        ),
    )
}

/// `POST /todos`
pub async fn create(
    State(state): State<AppState>,
    mut flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let (form, mut binding_result) = TodoForm::bind(&params);
    form.validate(&mut binding_result);
    let model_priorities = priorities();
    if binding_result.has_errors() {
        return Ok(
            render(
                "todos/form",
                TodosFormView {
                    priorities: model_priorities,
                    todo_form: form,
                    todo_id: None,
                    todo_form_binding: binding_result,
                },
            ),
        );
    }
    let todo = state.todo_service.create(
        &form.normalized_title(),
        form.normalized_description().as_deref(),
        form.due_date,
        form.priority.unwrap(),
    ).await?;
    flash.set("message", format!("「{}」を追加しました", todo.title));
    Ok(flash.redirect("/todos", &[]).await)
}

/// `GET /todos/{id}/edit`
pub async fn edit_form(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let model_priorities = priorities();
    let model_todo_id = id;
    let model_todo_form = TodoForm::from(&state.todo_service.find_by_id(id).await?);
    Ok(
        render(
            "todos/form",
            TodosFormView {
                priorities: model_priorities,
                todo_form: model_todo_form,
                todo_id: Some(model_todo_id),
                todo_form_binding: BindingResult::default(),
            },
        ),
    )
}

/// `POST /todos/{id}`
pub async fn update(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    mut flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let (form, mut binding_result) = TodoForm::bind(&params);
    form.validate(&mut binding_result);
    let model_priorities = priorities();
    if binding_result.has_errors() {
        let model_todo_id = id;
        return Ok(
            render(
                "todos/form",
                TodosFormView {
                    priorities: model_priorities,
                    todo_form: form,
                    todo_id: Some(model_todo_id),
                    todo_form_binding: binding_result,
                },
            ),
        );
    }
    let todo = state.todo_service.update(
        id,
        &form.normalized_title(),
        form.normalized_description().as_deref(),
        form.due_date,
        form.priority.unwrap(),
        form.done,
    ).await?;
    flash.set("message", format!("「{}」を更新しました", todo.title));
    Ok(flash.redirect("/todos", &[]).await)
}

/// `toggle` の `@RequestParam`。
#[derive(Debug, Deserialize)]
pub struct ToggleParams {
    filter: Option<String>,
}

/// `POST /todos/{id}/toggle`
pub async fn toggle(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    params: RequestParams,
) -> Result<Response, AppError> {
    let request: ToggleParams = params.parse()?;
    let filter_param = request.filter.as_deref();
    let mut redirect_params: Vec<(&str, String)> = Vec::new();
    state.todo_service.toggle(id).await?;
    redirect_params.push(("filter", TodoFilter::from_param(filter_param).param()));
    Ok(redirect("/todos", &redirect_params))
}

/// `POST /todos/{id}/delete`
pub async fn delete(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    mut flash: Flash,
) -> Result<Response, AppError> {
    let todo = state.todo_service.delete(id).await?;
    flash.set("message", format!("「{}」を削除しました", todo.title));
    Ok(flash.redirect("/todos", &[]).await)
}

/// `POST /todos/completed/delete`
pub async fn delete_completed(
    State(state): State<AppState>,
    mut flash: Flash,
) -> Result<Response, AppError> {
    let count = state.todo_service.delete_completed().await?;
    flash.set("message", format!("完了済みの {count} 件を削除しました"));
    Ok(flash.redirect("/todos", &[]).await)
}
