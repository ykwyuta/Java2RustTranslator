//! Translated from `Activity` (Activity.java) by Java2RustTranslator.

use chrono::NaiveDateTime;

use crate::domain::ActivityAction;

/// activities テーブルの 1 行（Todo の操作履歴）。Todo を削除しても履歴は残る（todo_id は null になる）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Activity {
    pub id: Option<i64>,
    pub todo_id: Option<i64>,
    pub action: ActivityAction,
    pub title: String,
    pub created_at: NaiveDateTime,
}
