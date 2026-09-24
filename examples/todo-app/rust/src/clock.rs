//! ClockConfig.java（java.time.Clock の Bean）に相当する。

use chrono::{Local, NaiveDate, NaiveDateTime};

/// 現在時刻の取得元。テストで差し替えられるようにトレイトにしておく。
pub trait Clock: Send + Sync {
    fn now(&self) -> NaiveDateTime;

    fn today(&self) -> NaiveDate {
        self.now().date()
    }
}

/// Clock.systemDefaultZone()
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> NaiveDateTime {
        Local::now().naive_local()
    }
}
