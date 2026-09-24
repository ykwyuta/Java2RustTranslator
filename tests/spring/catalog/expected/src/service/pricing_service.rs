//! Translated from `PricingService` (PricingService.java) by Java2RustTranslator.

use sqlx::{PgConnection, PgPool};

use crate::domain::Item;
use crate::error::{Error, Result};
use crate::mapper::item_mapper;

/// 呼び出し元のトランザクションに参加する（propagation = REQUIRED）。
#[derive(Clone)]
pub struct PricingService {
    pool: PgPool,
}

impl PricingService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn discount(&self, item: &mut Item, percent: i32) -> Result<i32> {
        let mut tx = self.pool.begin().await?;
        let result = self.discount_in(&mut tx, item, percent).await;
        if matches!(result, Ok(_) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `discount` の本体。呼び出し元の接続（トランザクション）で実行する。
    pub(crate) async fn discount_in(
        &self,
        conn: &mut PgConnection,
        item: &mut Item,
        percent: i32,
    ) -> Result<i32> {
        item.price = item.price * (100 - percent) / 100;
        item_mapper::update_selective(conn, item).await?;
        Ok(item.price)
    }
}
