//! Translated from `TodoSummary` (TodoSummary.java) by Java2RustTranslator.

/// 一覧画面に出す件数。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TodoSummary {
    pub active: i64,
    pub completed: i64,
}

impl TodoSummary {
    pub fn total(&self) -> i64 {
        self.active + self.completed
    }
}
