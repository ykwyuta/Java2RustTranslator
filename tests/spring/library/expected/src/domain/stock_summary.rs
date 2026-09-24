//! Translated from `StockSummary` (StockSummary.java) by Java2RustTranslator.

/// 在庫の集計（select の結果になる record）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, sqlx::FromRow)]
pub struct StockSummary {
    pub books: i64,
    pub total_stock: i64,
}

impl StockSummary {
    pub fn is_empty(&self) -> bool {
        self.books == 0
    }
}
