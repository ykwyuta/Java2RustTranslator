//! Translated from `ClockConfig` (ClockConfig.java) by Java2RustTranslator.

use std::sync::Arc;

use crate::clock::{Clock, SystemClock};

pub struct ClockConfig;

impl ClockConfig {
    /// 現在時刻の取得元。テストで差し替えられるように Bean にしておく。
    ///
    /// `@Bean`
    pub fn clock() -> Arc<dyn Clock> {
        Arc::new(SystemClock)
    }
}
