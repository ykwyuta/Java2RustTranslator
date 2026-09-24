//! MyBatis の動的 SQL を `QueryBuilder` で組み立てる補助（Java2RustTranslator の補助モジュール）。

use sqlx::{Postgres, QueryBuilder};

/// `<where>` / `<set>`: 最初の断片の前にキーワード（WHERE / SET）、2 つ目からは区切り（AND / OR / ,）を付ける。
/// 断片の先頭の AND / OR と末尾の , は変換時に取り除いてある。
pub struct Clause {
    keyword: &'static str,
    started: bool,
}

impl Clause {
    pub fn where_clause() -> Self {
        Self {
            keyword: " WHERE ",
            started: false,
        }
    }

    pub fn set_clause() -> Self {
        Self {
            keyword: " SET ",
            started: false,
        }
    }

    /// 次の断片の前にキーワードか区切りを書き、続きを書くための `QueryBuilder` を返す。
    pub fn part<'a>(
        &mut self,
        query: &'a mut QueryBuilder<Postgres>,
        separator: &str,
    ) -> &'a mut QueryBuilder<Postgres> {
        query.push(if self.started {
            separator
        } else {
            self.keyword
        });
        self.started = true;
        query
    }
}
