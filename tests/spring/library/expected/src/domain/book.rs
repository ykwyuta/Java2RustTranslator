//! Translated from `Book` (Book.java) by Java2RustTranslator.

/// 蔵書。
#[derive(Debug, Clone, Default, PartialEq, Eq, sqlx::FromRow)]
pub struct Book {
    pub id: i64,
    pub title: String,
    pub author: Option<String>,
    pub stock: i32,
    pub archived: bool,
}

impl Book {
    /// 在庫があって、アーカイブされていない。
    pub fn is_available(&self) -> bool {
        self.stock > 0 && !self.archived
    }
}
