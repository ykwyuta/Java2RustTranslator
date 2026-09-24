//! Translated from `ItemForm` (ItemForm.java) by Java2RustTranslator.

use crate::domain::Category;
use crate::spring_web::{BindingResult, RequestParams, not_blank, parse_enum, parse_number};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ItemForm {
    pub name: Option<String>,
    pub category: Category,
    pub price: i32,
}

impl Default for ItemForm {
    /// フィールドの初期化子の値（初期化子のないフィールドは既定値）。
    fn default() -> Self {
        Self {
            name: Default::default(),
            category: Category::Book,
            price: 100,
        }
    }
}

impl ItemForm {
    /// リクエストパラメータをバインドする（Spring の DataBinder）。型変換に失敗したフィールドはエラーにする。
    pub fn bind(params: &RequestParams) -> (Self, BindingResult) {
        let mut form = Self::default();
        let mut result = BindingResult::default();
        if let Some(v) = params.get("name") {
            form.name = Some(v.to_string());
        }
        if let Some(v) = params.get("category") {
            match parse_enum(v, Category::value_of) {
                Ok(x) => {
                    if let Some(x) = x {
                        form.category = x;
                    }
                }
                Err(_) => result.reject(
                    "category",
                    "Failed to convert property value of type 'java.lang.String' to required type 'com.example.catalog.domain.Category' for property 'category'",
                    Some(v),
                ),
            }
        }
        if let Some(v) = params.get("price") {
            match parse_number::<i32>(v) {
                Ok(x) => {
                    if let Some(x) = x {
                        form.price = x;
                    }
                }
                Err(_) => result.reject(
                    "price",
                    "Failed to convert property value of type 'java.lang.String' to required type 'int' for property 'price'",
                    Some(v),
                ),
            }
        }
        (form, result)
    }

    /// Bean Validation の検証（`@Valid`）。
    pub fn validate(&self, result: &mut BindingResult) {
        if !not_blank(self.name.as_deref()) {
            result.reject("name", "Enter a name", None);
        }
        if Some(&self.price).is_some_and(|v| *v < 0) {
            result.reject("price", "Must not be negative", None);
        }
    }
}
