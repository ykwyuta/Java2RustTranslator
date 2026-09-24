//! Todo.java に相当する。getter / setter は公開フィールドになる。

use chrono::{NaiveDate, NaiveDateTime};

/// todos テーブルの 1 行。列名とフィールド名が同じなので sqlx::FromRow でそのまま対応付く
/// （MyBatis の map-underscore-to-camel-case に相当する変換は不要）。
#[derive(Debug, Clone, PartialEq, sqlx::FromRow)]
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
        !self.done && self.due_date.is_some_and(|due| due < today)
    }
}

/// INSERT する前の Todo。Java では id が null の Todo を使うが、Rust では id を持たない型に分ける。
#[derive(Debug, Clone)]
pub struct NewTodo {
    pub title: String,
    pub description: Option<String>,
    pub done: bool,
    pub due_date: Option<NaiveDate>,
    pub created_at: NaiveDateTime,
    pub updated_at: NaiveDateTime,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn todo(done: bool, due_date: Option<NaiveDate>) -> Todo {
        let now = NaiveDate::from_ymd_opt(2026, 9, 1)
            .unwrap()
            .and_hms_opt(0, 0, 0)
            .unwrap();
        Todo {
            id: 1,
            title: "t".into(),
            description: None,
            done,
            due_date,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn overdue_only_when_not_done_and_due_date_passed() {
        let today = NaiveDate::from_ymd_opt(2026, 9, 24).unwrap();
        let yesterday = today.pred_opt();
        assert!(todo(false, yesterday).is_overdue(today));
        assert!(!todo(true, yesterday).is_overdue(today));
        assert!(!todo(false, Some(today)).is_overdue(today));
        assert!(!todo(false, None).is_overdue(today));
    }
}
