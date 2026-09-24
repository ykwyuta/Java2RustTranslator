//! Translated from `TodoFilter` (TodoFilter.java) by Java2RustTranslator.

/// 一覧の絞り込み。クエリパラメータ filter=all|active|completed に対応する。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub enum TodoFilter {
    #[default]
    All,
    Active,
    Completed,
}

impl TodoFilter {
    /// すべての定数（`values()`）。
    pub fn values() -> [Self; 3] {
        [Self::All, Self::Active, Self::Completed]
    }

    /// 定数の名前（`name()`）。
    pub fn name(self) -> &'static str {
        match self {
            Self::All => "ALL",
            Self::Active => "ACTIVE",
            Self::Completed => "COMPLETED",
        }
    }

    /// 定数の位置（`ordinal()`）。
    pub fn ordinal(self) -> i32 {
        match self {
            Self::All => 0,
            Self::Active => 1,
            Self::Completed => 2,
        }
    }

    /// 名前の定数（`valueOf(name)`。なければ None）。
    pub fn value_of(name: &str) -> Option<Self> {
        match name {
            "ALL" => Some(Self::All),
            "ACTIVE" => Some(Self::Active),
            "COMPLETED" => Some(Self::Completed),
            _ => None,
        }
    }

    /// 不正な値や未指定は ALL として扱う。
    pub fn from_param(value: Option<&str>) -> TodoFilter {
        let Some(value) = value else {
            return TodoFilter::All;
        };
        for filter in TodoFilter::values() {
            if filter.name().to_lowercase() == value.to_lowercase() {
                return filter;
            }
        }
        TodoFilter::All
    }

    pub fn param(self) -> String {
        self.name().to_lowercase()
    }
}
