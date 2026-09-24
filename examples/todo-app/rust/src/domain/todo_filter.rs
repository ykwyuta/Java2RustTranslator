//! TodoFilter.java に相当する。

/// 一覧の絞り込み。クエリパラメータ filter=all|active|completed に対応する。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TodoFilter {
    All,
    Active,
    Completed,
}

impl TodoFilter {
    /// 不正な値や未指定は All として扱う。
    pub fn from_param(value: Option<&str>) -> Self {
        match value {
            Some(v) if v.eq_ignore_ascii_case("active") => Self::Active,
            Some(v) if v.eq_ignore_ascii_case("completed") => Self::Completed,
            _ => Self::All,
        }
    }

    pub fn param(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Active => "active",
            Self::Completed => "completed",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_param_is_case_insensitive_and_defaults_to_all() {
        assert_eq!(TodoFilter::from_param(Some("ACTIVE")), TodoFilter::Active);
        assert_eq!(
            TodoFilter::from_param(Some("completed")),
            TodoFilter::Completed
        );
        assert_eq!(TodoFilter::from_param(Some("unknown")), TodoFilter::All);
        assert_eq!(TodoFilter::from_param(None), TodoFilter::All);
    }
}
