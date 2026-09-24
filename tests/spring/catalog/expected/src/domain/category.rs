//! Translated from `Category` (Category.java) by Java2RustTranslator.

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub enum Category {
    #[default]
    Book,
    Food,
    Tool,
}

impl Category {
    /// すべての定数（`values()`）。
    pub fn values() -> [Self; 3] {
        [Self::Book, Self::Food, Self::Tool]
    }

    /// 定数の名前（`name()`）。
    pub fn name(self) -> &'static str {
        match self {
            Self::Book => "BOOK",
            Self::Food => "FOOD",
            Self::Tool => "TOOL",
        }
    }

    /// 定数の位置（`ordinal()`）。
    pub fn ordinal(self) -> i32 {
        match self {
            Self::Book => 0,
            Self::Food => 1,
            Self::Tool => 2,
        }
    }

    /// 名前の定数（`valueOf(name)`。なければ None）。
    pub fn value_of(name: &str) -> Option<Self> {
        match name {
            "BOOK" => Some(Self::Book),
            "FOOD" => Some(Self::Food),
            "TOOL" => Some(Self::Tool),
            _ => None,
        }
    }
}

impl sqlx::Type<sqlx::Postgres> for Category {
    fn type_info() -> sqlx::postgres::PgTypeInfo {
        <str as sqlx::Type<sqlx::Postgres>>::type_info()
    }

    fn compatible(ty: &sqlx::postgres::PgTypeInfo) -> bool {
        <str as sqlx::Type<sqlx::Postgres>>::compatible(ty)
    }
}

impl<'r> sqlx::Decode<'r, sqlx::Postgres> for Category {
    fn decode(value: sqlx::postgres::PgValueRef<'r>) -> Result<Self, sqlx::error::BoxDynError> {
        let name = <&str as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
        Self::value_of(name).ok_or_else(|| format!("No enum constant com.example.catalog.domain.Category.{name}").into())
    }
}
