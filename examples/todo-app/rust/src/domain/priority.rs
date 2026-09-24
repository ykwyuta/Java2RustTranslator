//! Translated from `Priority` (Priority.java) by Java2RustTranslator.

/// 優先度。データベースには名前（LOW / MEDIUM / HIGH）で保存する（MyBatis の EnumTypeHandler）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub enum Priority {
    #[default]
    Low,
    Medium,
    High,
}

impl Priority {
    /// すべての定数（`values()`）。
    pub fn values() -> [Self; 3] {
        [Self::Low, Self::Medium, Self::High]
    }

    /// 定数の名前（`name()`）。
    pub fn name(self) -> &'static str {
        match self {
            Self::Low => "LOW",
            Self::Medium => "MEDIUM",
            Self::High => "HIGH",
        }
    }

    /// 定数の位置（`ordinal()`）。
    pub fn ordinal(self) -> i32 {
        match self {
            Self::Low => 0,
            Self::Medium => 1,
            Self::High => 2,
        }
    }

    /// 名前の定数（`valueOf(name)`。なければ None）。
    pub fn value_of(name: &str) -> Option<Self> {
        match name {
            "LOW" => Some(Self::Low),
            "MEDIUM" => Some(Self::Medium),
            "HIGH" => Some(Self::High),
            _ => None,
        }
    }
}

impl sqlx::Type<sqlx::Postgres> for Priority {
    fn type_info() -> sqlx::postgres::PgTypeInfo {
        <str as sqlx::Type<sqlx::Postgres>>::type_info()
    }

    fn compatible(ty: &sqlx::postgres::PgTypeInfo) -> bool {
        <str as sqlx::Type<sqlx::Postgres>>::compatible(ty)
    }
}

impl<'r> sqlx::Decode<'r, sqlx::Postgres> for Priority {
    fn decode(value: sqlx::postgres::PgValueRef<'r>) -> Result<Self, sqlx::error::BoxDynError> {
        let name = <&str as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
        Self::value_of(name).ok_or_else(|| format!("No enum constant com.example.todo.domain.Priority.{name}").into())
    }
}
