//! Thymeleaf のテンプレート（templates/）に渡すモデル。Translated by Java2RustTranslator.

use askama::Template;
use askama_web::WebTemplate;
use chrono::NaiveDate;

use crate::domain::Todo;
use crate::service::TodoSummary;
use crate::spring_web::{BindingResult, Present};
use crate::web::TodoForm;

/// templates/todos/list.html
#[derive(Template, WebTemplate)]
#[template(path = "todos/list.html")]
pub struct TodosListView {
    pub todos: Vec<Todo>,
    pub summary: TodoSummary,
    pub filter: String,
    pub q: String,
    pub today: NaiveDate,
    pub message: Option<String>,
}

/// templates/todos/form.html
#[derive(Template, WebTemplate)]
#[template(path = "todos/form.html")]
pub struct TodosFormView {
    pub todo_form: TodoForm,
    pub todo_id: Option<i64>,
    pub todo_form_binding: BindingResult,
}

/// templates/error/404.html
#[derive(Template, WebTemplate)]
#[template(path = "error/404.html")]
pub struct Error404View {
    pub message: Option<String>,
}
