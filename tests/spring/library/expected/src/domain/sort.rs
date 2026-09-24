//! Translated from `Sort` (Sort.java) by Java2RustTranslator.

/// 検索結果の並び順。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub enum Sort {
    #[default]
    Title,
    StockDesc,
}

impl Sort {
    /// すべての定数（`values()`）。
    pub fn values() -> [Self; 2] {
        [Self::Title, Self::StockDesc]
    }

    /// 定数の名前（`name()`）。
    pub fn name(self) -> &'static str {
        match self {
            Self::Title => "TITLE",
            Self::StockDesc => "STOCK_DESC",
        }
    }

    /// 定数の位置（`ordinal()`）。
    pub fn ordinal(self) -> i32 {
        match self {
            Self::Title => 0,
            Self::StockDesc => 1,
        }
    }

    /// 名前の定数（`valueOf(name)`。なければ None）。
    pub fn value_of(name: &str) -> Option<Self> {
        match name {
            "TITLE" => Some(Self::Title),
            "STOCK_DESC" => Some(Self::StockDesc),
            _ => None,
        }
    }
}
