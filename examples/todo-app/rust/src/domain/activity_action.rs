//! Translated from `ActivityAction` (ActivityAction.java) by Java2RustTranslator.

/// 操作履歴の種類。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub enum ActivityAction {
    #[default]
    Create,
    Update,
    Complete,
    Reopen,
    Delete,
}

impl ActivityAction {
    /// すべての定数（`values()`）。
    pub fn values() -> [Self; 5] {
        [Self::Create, Self::Update, Self::Complete, Self::Reopen, Self::Delete]
    }

    /// 定数の名前（`name()`）。
    pub fn name(self) -> &'static str {
        match self {
            Self::Create => "CREATE",
            Self::Update => "UPDATE",
            Self::Complete => "COMPLETE",
            Self::Reopen => "REOPEN",
            Self::Delete => "DELETE",
        }
    }

    /// 定数の位置（`ordinal()`）。
    pub fn ordinal(self) -> i32 {
        match self {
            Self::Create => 0,
            Self::Update => 1,
            Self::Complete => 2,
            Self::Reopen => 3,
            Self::Delete => 4,
        }
    }

    /// 名前の定数（`valueOf(name)`。なければ None）。
    pub fn value_of(name: &str) -> Option<Self> {
        match name {
            "CREATE" => Some(Self::Create),
            "UPDATE" => Some(Self::Update),
            "COMPLETE" => Some(Self::Complete),
            "REOPEN" => Some(Self::Reopen),
            "DELETE" => Some(Self::Delete),
            _ => None,
        }
    }
}

impl sqlx::Type<sqlx::Postgres> for ActivityAction {
    fn type_info() -> sqlx::postgres::PgTypeInfo {
        <str as sqlx::Type<sqlx::Postgres>>::type_info()
    }

    fn compatible(ty: &sqlx::postgres::PgTypeInfo) -> bool {
        <str as sqlx::Type<sqlx::Postgres>>::compatible(ty)
    }
}

impl<'r> sqlx::Decode<'r, sqlx::Postgres> for ActivityAction {
    fn decode(value: sqlx::postgres::PgValueRef<'r>) -> Result<Self, sqlx::error::BoxDynError> {
        let name = <&str as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
        Self::value_of(name).ok_or_else(|| format!("No enum constant com.example.todo.domain.ActivityAction.{name}").into())
    }
}
