//! Thymeleaf のテンプレート（templates/）に渡すモデル。Translated by Java2RustTranslator.

use askama::Template;
use askama_web::WebTemplate;

use crate::domain::{Category, Item};
use crate::spring_web::{BindingResult, MessageArg};
use crate::web::ItemForm;

/// templates/items/list.html
#[derive(Template, WebTemplate)]
#[template(path = "items/list.html")]
pub struct ItemsListView {
    pub categories: Vec<Category>,
    pub count: i32,
    pub items: Vec<Item>,
    pub message: Option<String>,
}

/// templates/items/form.html
#[derive(Template, WebTemplate)]
#[template(path = "items/form.html")]
pub struct ItemsFormView {
    pub categories: Vec<Category>,
    pub item_form: ItemForm,
    pub item_form_binding: BindingResult,
}

/// templates/error/404.html
#[derive(Template, WebTemplate)]
#[template(path = "error/404.html")]
pub struct Error404View;
