//! Translated from `ItemService` (ItemService.java) by Java2RustTranslator.

use sqlx::{PgConnection, PgPool};

use crate::domain::{Category, Item};
use crate::error::{Error, Result};
use crate::mapper::item_mapper;
use crate::service::{AuditService, PricingService};

#[derive(Clone)]
pub struct ItemService {
    pool: PgPool,
    audit_service: AuditService,
    pricing_service: PricingService,
}

impl ItemService {
    pub fn new(pool: PgPool, audit_service: AuditService, pricing_service: PricingService) -> Self {
        Self { pool, audit_service, pricing_service }
    }

    /// `@Transactional(readOnly = true)`
    pub async fn search(
        &self,
        keyword: Option<&str>,
        categories: &[Category],
    ) -> Result<Vec<Item>> {
        let mut tx = self.pool.begin().await?;
        let result = self.search_in(&mut tx, keyword, categories).await;
        if matches!(result, Ok(_) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `search` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn search_in(
        &self,
        conn: &mut PgConnection,
        keyword: Option<&str>,
        categories: &[Category],
    ) -> Result<Vec<Item>> {
        Ok(item_mapper::search(conn, keyword, categories, "price DESC, id").await?)
    }

    /// `@Transactional(readOnly = true)`
    pub async fn by_ids(&self, ids: &[i64]) -> Result<Vec<Item>> {
        let mut tx = self.pool.begin().await?;
        let result = self.by_ids_in(&mut tx, ids).await;
        if matches!(result, Ok(_) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `by_ids` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn by_ids_in(&self, conn: &mut PgConnection, ids: &[i64]) -> Result<Vec<Item>> {
        Ok(item_mapper::find_by_ids(conn, ids).await?)
    }

    pub async fn import_all(&self, items: &[Item]) -> Result<i32> {
        let mut tx = self.pool.begin().await?;
        let result = self.import_all_in(&mut tx, items).await;
        if matches!(result, Ok(_) | Err(Error::ItemNotFound(..)) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `import_all` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn import_all_in(&self, conn: &mut PgConnection, items: &[Item]) -> Result<i32> {
        let count = item_mapper::insert_all(conn, items).await?;
        self.audit_service.record("import", None).await?;
        Ok(count)
    }

    pub async fn discount(&self, id: i64, percent: i32) -> Result<Item> {
        let mut tx = self.pool.begin().await?;
        let result = self.discount_in(&mut tx, id, percent).await;
        if matches!(result, Ok(_) | Err(Error::ItemNotFound(..)) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `discount` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn discount_in(&self, conn: &mut PgConnection, id: i64, percent: i32) -> Result<Item> {
        let item = item_mapper::find_by_id(conn, id).await?;
        let Some(mut item) = item else {
            return Err(Error::ItemNotFound(id));
        };
        if item.price <= 0 {
            return Err(Error::SoldOut(id));
        }
        self.pricing_service.discount_in(conn, &mut item, percent).await?;
        self.audit_service.record("discount", Some(item.id)).await?;
        Ok(item)
    }

    /// `@Transactional(readOnly = true)`
    pub async fn category_of(&self, id: i64) -> Result<Option<Category>> {
        let mut tx = self.pool.begin().await?;
        let result = self.category_of_in(&mut tx, id).await;
        if matches!(result, Ok(_) | Err(Error::SoldOut(..))) {
            tx.commit().await?;
        }
        result
    }

    /// `category_of` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn category_of_in(&self, conn: &mut PgConnection, id: i64) -> Result<Option<Category>> {
        Ok(item_mapper::category_of(conn, id).await?)
    }
}
