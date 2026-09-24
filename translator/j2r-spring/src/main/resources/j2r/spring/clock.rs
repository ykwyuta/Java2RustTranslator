//! `java.time.Clock` に当たる時計（Java2RustTranslator の補助モジュール）。

use chrono::{Local, NaiveDate, NaiveDateTime};

/// 現在時刻の取得元。`LocalDateTime.now(clock)` は `clock.now()`、`LocalDate.now(clock)` は `clock.today()` になる。
pub trait Clock: Send + Sync {
    fn now(&self) -> NaiveDateTime;

    fn today(&self) -> NaiveDate {
        self.now().date()
    }
}

/// `Clock.systemDefaultZone()`
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> NaiveDateTime {
        Local::now().naive_local()
    }
}

/// `Clock.systemUTC()`
pub struct UtcClock;

impl Clock for UtcClock {
    fn now(&self) -> NaiveDateTime {
        chrono::Utc::now().naive_utc()
    }
}

/// `Clock.fixed(...)`（テスト用）
pub struct FixedClock(pub NaiveDateTime);

impl Clock for FixedClock {
    fn now(&self) -> NaiveDateTime {
        self.0
    }
}
