//! Translated from `BookController` (BookController.java) by Java2RustTranslator.

use axum::extract::{Path, State};
use axum::response::{IntoResponse, Response};
use serde::Deserialize;

use crate::app::{AppError, AppState};
use crate::domain::Sort;
use crate::spring_web::{BindingResult, Flash, RequestParams, redirect};
use crate::views::{BooksDetailView, BooksFormView, BooksListView};
use crate::web::BookForm;

/// `list` の `@RequestParam`。
#[derive(Debug, Deserialize)]
pub struct ListParams {
    keyword: Option<String>,
    #[serde(rename = "inStock")] in_stock: Option<bool>,
}

/// `GET /books`
pub async fn list(
    State(state): State<AppState>,
    flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let request: ListParams = params.parse()?;
    let keyword = request.keyword.as_deref();
    let in_stock_only = request.in_stock.unwrap_or(false);
    let model_books = state.book_service.search(keyword, in_stock_only, Sort::Title).await?;
    let model_summary = state.book_service.summary().await?;
    let model_keyword = keyword.map(str::to_string);
    Ok(
        BooksListView {
            books: model_books,
            summary: model_summary,
            keyword: model_keyword,
            notice: flash.get("notice"),
        }.into_response(),
    )
}

/// `GET /books/{id}`
pub async fn detail(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    flash: Flash,
) -> Result<Response, AppError> {
    let book = state.book_service.get(id).await?;
    let model_label = state.book_service.label(&book);
    let model_book = book;
    Ok(
        BooksDetailView {
            label: model_label,
            book: model_book,
            notice: flash.get("notice"),
        }.into_response(),
    )
}

/// `GET /books/new`
pub async fn new_form() -> Result<Response, AppError> {
    let model_book_form = BookForm::default();
    Ok(
        BooksFormView {
            book_form: model_book_form,
            book_form_binding: BindingResult::default(),
        }.into_response(),
    )
}

/// `POST /books`
pub async fn register(
    State(state): State<AppState>,
    mut flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let (form, mut binding_result) = BookForm::bind(&params);
    form.validate(&mut binding_result);
    if binding_result.has_errors() {
        return Ok(
            BooksFormView {
                book_form: form,
                book_form_binding: binding_result,
            }.into_response(),
        );
    }
    let book = state.book_service.register(
        &form.get_title(),
        form.author.as_deref(),
        form.get_stock(),
    ).await?;
    flash.set("notice", format!("registered {}", book.title)).await;
    Ok(redirect(&format!("/books/{}", book.id), &[]))
}

/// `POST /books/{id}/archive`
pub async fn archive(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    mut flash: Flash,
) -> Result<Response, AppError> {
    let mut redirect_params: Vec<(&str, String)> = Vec::new();
    let archived = state.book_service.archive(id).await?;
    flash.set(
        "notice",
        (if archived {
            "archived"
        } else {
            "already archived"
        }).to_string(),
    ).await;
    redirect_params.push(("inStock", false.to_string()));
    Ok(redirect("/books", &redirect_params))
}
