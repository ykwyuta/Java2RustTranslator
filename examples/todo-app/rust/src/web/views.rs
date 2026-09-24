//! Thymeleaf のテンプレートとモデル（Model#addAttribute）に相当する。
//! Model の属性は、テンプレートごとの構造体のフィールドになる。

use askama::Template;
use askama_web::WebTemplate;
use chrono::NaiveDate;

use super::todo_form::{FieldErrors, TodoForm};
use crate::domain::Todo;
use crate::service::TodoSummary;

/// templates/todos/list.html
#[derive(Template, WebTemplate)]
#[template(path = "todos/list.html")]
pub struct TodoListView {
    pub todos: Vec<Todo>,
    pub summary: TodoSummary,
    pub filter: &'static str,
    pub q: String,
    pub today: NaiveDate,
    /// フラッシュ属性 "message"
    pub message: Option<String>,
}

/// templates/todos/form.html
#[derive(Template, WebTemplate)]
#[template(path = "todos/form.html")]
pub struct TodoFormView {
    /// 編集時だけ Some
    pub todo_id: Option<i64>,
    pub form: TodoForm,
    pub errors: FieldErrors,
}

/// templates/error/404.html
#[derive(Template, WebTemplate)]
#[template(path = "error/404.html")]
pub struct NotFoundView {
    pub message: Option<String>,
}
