//! Translated from `BookForm` (BookForm.java) by Java2RustTranslator.

use crate::spring_web::{BindingResult, RequestParams, length, not_blank, parse_number};

/// 蔵書の登録フォーム。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BookForm {
    pub title: Option<String>,
    pub author: Option<String>,
    pub stock: Option<i32>,
}

impl BookForm {
    pub fn get_title(&self) -> String {
        self.title.as_deref().unwrap_or("").to_string()
    }

    pub fn get_stock(&self) -> i32 {
        self.stock.unwrap_or(0)
    }

    /// リクエストパラメータをバインドする（Spring の DataBinder）。型変換に失敗したフィールドはエラーにする。
    pub fn bind(params: &RequestParams) -> (Self, BindingResult) {
        let mut form = Self::default();
        let mut result = BindingResult::default();
        if let Some(v) = params.get("title") {
            form.title = Some(v.to_string());
        }
        if let Some(v) = params.get("author") {
            form.author = Some(v.to_string());
        }
        if let Some(v) = params.get("stock") {
            match parse_number::<i32>(v) {
                Ok(x) => {
                    form.stock = x;
                }
                Err(_) => result.reject("stock", "stock must be a number", Some(v)),
            }
        }
        (form, result)
    }

    /// Bean Validation の検証（`@Valid`）。
    pub fn validate(&self, result: &mut BindingResult) {
        if !not_blank(self.title.as_deref()) {
            result.reject("title", "title is required", None);
        }
        if self.title.as_deref().is_some_and(|v| length(v) > 200) {
            result.reject("title", "size must be between 0 and 200", None);
        }
        if self.stock.is_none() {
            result.reject("stock", "must not be null", None);
        }
        if self.stock.as_ref().is_some_and(|v| *v < 0) {
            result.reject("stock", "must be greater than or equal to 0", None);
        }
    }
}
