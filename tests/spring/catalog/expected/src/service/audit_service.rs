//! Translated from `AuditService` (AuditService.java) by Java2RustTranslator.

use std::sync::Arc;

use sqlx::{PgConnection, PgPool};

use crate::clock::Clock;
use crate::domain::AuditEntry;
use crate::error::{Error, Result};
use crate::mapper::audit_mapper;

/// 監査ログ。呼び出し元がロールバックしても残るように、別のトランザクションで書く。
#[derive(Clone)]
pub struct AuditService {
    pool: PgPool,
    clock: Arc<dyn Clock>,
}

impl AuditService {
    pub fn new(pool: PgPool, clock: Arc<dyn Clock>) -> Self {
        Self { pool, clock }
    }

    /// `@Transactional(propagation = REQUIRES_NEW)`
    pub async fn record(&self, action: &str, item_id: Option<i64>) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        let result = self.record_in(&mut tx, action, item_id).await;
        if matches!(result, Ok(_) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `record` の本体。呼び出し元の接続（トランザクション）で実行する。
    pub(crate) async fn record_in(
        &self,
        conn: &mut PgConnection,
        action: &str,
        item_id: Option<i64>,
    ) -> Result<()> {
        let entry = AuditEntry {
            action: action.to_string(),
            item_id,
            created_at: self.clock.now(),
        };
        audit_mapper::insert(conn, &entry).await?;
        Ok(())
    }

    /// `@Transactional(readOnly = true)`
    pub async fn count(&self) -> Result<i64> {
        let mut tx = self.pool.begin().await?;
        let result = self.count_in(&mut tx).await;
        if matches!(result, Ok(_) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `count` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn count_in(&self, conn: &mut PgConnection) -> Result<i64> {
        Ok(audit_mapper::count(conn).await?)
    }
}
