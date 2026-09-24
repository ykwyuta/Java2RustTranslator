//! Translated from `Todo` (Todo.java) by Java2RustTranslator.

use chrono::{NaiveDate, NaiveDateTime};

/// todos テーブルの 1 行。MyBatis が列名（snake_case）をプロパティ（camelCase）に対応付ける。
#[derive(Debug, Clone, Default, PartialEq, Eq, sqlx::FromRow)]
pub struct Todo {
    pub id: i64,
    pub title: String,
    pub description: Option<String>,
    pub done: bool,
    pub due_date: Option<NaiveDate>,
    pub created_at: NaiveDateTime,
    pub updated_at: NaiveDateTime,
}

impl Todo {
    /// 未完了で、期限が今日より前なら期限切れ。
    pub fn is_overdue(&self, today: NaiveDate) -> bool {
        !self.done && self.due_date.is_some_and(|due_date| due_date < today)
    }
}
