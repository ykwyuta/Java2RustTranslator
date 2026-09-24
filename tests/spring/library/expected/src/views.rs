//! Thymeleaf のテンプレート（templates/）に渡すモデル。Translated by Java2RustTranslator.

use askama::Template;
use askama_web::WebTemplate;

use crate::domain::{Book, StockSummary};
use crate::spring_web::{BindingResult, Present};
use crate::web::BookForm;

/// templates/books/list.html
#[derive(Template, WebTemplate)]
#[template(path = "books/list.html")]
pub struct BooksListView {
    pub books: Vec<Book>,
    pub summary: StockSummary,
    pub keyword: Option<String>,
    pub notice: Option<String>,
}

/// templates/books/detail.html
#[derive(Template, WebTemplate)]
#[template(path = "books/detail.html")]
pub struct BooksDetailView {
    pub label: String,
    pub book: Book,
    pub notice: Option<String>,
}

/// templates/books/form.html
#[derive(Template, WebTemplate)]
#[template(path = "books/form.html")]
pub struct BooksFormView {
    pub book_form: BookForm,
    pub book_form_binding: BindingResult,
}

/// templates/error/404.html
#[derive(Template, WebTemplate)]
#[template(path = "error/404.html")]
pub struct Error404View {
    pub message: Option<String>,
}
