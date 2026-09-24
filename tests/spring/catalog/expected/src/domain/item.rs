//! Translated from `Item` (Item.java) by Java2RustTranslator.

use crate::domain::Category;

#[derive(Debug, Clone, Default, PartialEq, Eq, sqlx::FromRow)]
pub struct Item {
    pub id: i64,
    pub name: String,
    pub category: Category,
    pub price: i32,
    pub note: Option<String>,
}
