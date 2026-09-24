//! Translated from `ItemController` (ItemController.java) by Java2RustTranslator.

use axum::extract::{Path, State};
use axum::response::Response;
use serde::Deserialize;

use crate::app::{AppError, AppState};
use crate::domain::{Category, Item};
use crate::spring_web::{BindingResult, Flash, RequestParams, render};
use crate::views::{ItemsFormView, ItemsListView};
use crate::web::ItemForm;

/// `@ModelAttribute("categories")`
fn categories() -> Vec<Category> {
    Category::values().to_vec()
}

/// `list` の `@RequestParam`。
#[derive(Debug, Deserialize)]
pub struct ListParams {
    q: Option<String>,
}

/// `GET /items`
pub async fn list(
    State(state): State<AppState>,
    flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let request: ListParams = params.parse()?;
    let q = request.q.as_deref();
    let model_categories = categories();
    let items = state.item_service.search(q, &Vec::new()).await?;
    let model_count = items.len() as i32;
    let model_items = items;
    Ok(
        render(
            "items/list",
            ItemsListView {
                categories: model_categories,
                count: model_count,
                items: model_items,
                message: flash.get("message"),
            },
        ),
    )
}

/// `GET /items/new`
pub async fn new_form() -> Result<Response, AppError> {
    let model_categories = categories();
    let model_item_form = ItemForm::default();
    Ok(
        render(
            "items/form",
            ItemsFormView {
                categories: model_categories,
                item_form: model_item_form,
                item_form_binding: BindingResult::default(),
            },
        ),
    )
}

/// `POST /items`
pub async fn create(
    State(state): State<AppState>,
    mut flash: Flash,
    params: RequestParams,
) -> Result<Response, AppError> {
    let (form, mut binding_result) = ItemForm::bind(&params);
    form.validate(&mut binding_result);
    let model_categories = categories();
    if binding_result.has_errors() {
        return Ok(
            render(
                "items/form",
                ItemsFormView {
                    categories: model_categories,
                    item_form: form,
                    item_form_binding: binding_result,
                },
            ),
        );
    }
    let item = Item {
        name: form.name.as_deref().unwrap_or("").to_string(),
        category: form.category,
        price: form.price,
        ..Default::default()
    };
    state.item_service.import_all(&vec![item]).await?;
    flash.set("message", "added".to_string());
    Ok(flash.redirect("/items", &[]).await)
}

/// `POST /items/{id}/discount`
pub async fn discount(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    mut flash: Flash,
) -> Result<Response, AppError> {
    let item = state.item_service.discount(id, 10).await?;
    flash.set("message", format!("discounted {}", item.name));
    Ok(flash.redirect("/items", &[]).await)
}
