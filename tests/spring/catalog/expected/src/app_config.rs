//! Translated from `AppConfig` (AppConfig.java) by Java2RustTranslator.

use std::sync::Arc;

use crate::clock::{Clock, UtcClock};

pub struct AppConfig;

impl AppConfig {
    /// `@Bean`
    pub fn clock() -> Arc<dyn Clock> {
        Arc::new(UtcClock)
    }
}
