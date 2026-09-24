//! MyBatis の動的 SQL（`<where>` / `<set>` / `<trim>`）と `<resultMap>` の補助（Java2RustTranslator の補助モジュール）。

use sqlx::postgres::PgRow;
use sqlx::{Decode, Postgres, QueryBuilder, Row, Type};

/// `<trim>`（`<where>` と `<set>` を含む）。中身が空でなければ最初の断片の前に prefix を書き、最後に suffix を書く。
///
/// 断片の先頭の prefixOverrides（`AND` / `OR` など）と末尾の suffixOverrides（`,` など）は変換時に取り除いてあり、
/// `part` に `lead` / `trail` として渡す。最初の断片の `lead` と最後の断片の `trail` は書かない
/// （MyBatis が中身の先頭と末尾から取り除くのと同じ）。それ以外はそのまま書く。
pub struct Trim {
    prefix: &'static str,
    suffix: &'static str,
    pending: &'static str,
    started: bool,
}

impl Trim {
    pub fn new(prefix: &'static str, suffix: &'static str) -> Self {
        Self {
            prefix,
            suffix,
            pending: "",
            started: false,
        }
    }

    /// 次の断片の前にキーワードか前の断片の trail・この断片の lead を書き、続きを書くための `QueryBuilder` を返す。
    pub fn part<'a>(
        &mut self,
        query: &'a mut QueryBuilder<Postgres>,
        lead: &'static str,
        trail: &'static str,
    ) -> &'a mut QueryBuilder<Postgres> {
        if !self.started {
            self.started = true;
            if !self.prefix.is_empty() {
                query.push(" ").push(self.prefix);
            }
        } else {
            query.push(self.pending);
            if !lead.is_empty() {
                query.push(" ").push(lead);
            }
        }
        self.pending = trail;
        query.push(" ")
    }

    /// 中身があれば suffix を書く。
    pub fn finish(&mut self, query: &mut QueryBuilder<Postgres>) {
        if self.started && !self.suffix.is_empty() {
            query.push(" ").push(self.suffix);
        }
    }
}

/// `<resultMap>` の列の値。列がなければ None（MyBatis は結果にない列の対応を無視する）。
pub fn column<'r, T>(row: &'r PgRow, name: &str) -> sqlx::Result<Option<T>>
where
    T: Decode<'r, Postgres> + Type<Postgres>,
{
    match row.try_get::<T, _>(name) {
        Ok(value) => Ok(Some(value)),
        Err(sqlx::Error::ColumnNotFound(_)) => Ok(None),
        Err(e) => Err(e),
    }
}
