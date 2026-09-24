//! Translated from `AuditMapper` (AuditMapper.java) by Java2RustTranslator.

use sqlx::PgConnection;

use crate::domain::AuditEntry;

/// `@Insert`
pub async fn insert(conn: &mut PgConnection, entry: &AuditEntry) -> sqlx::Result<()> {
    sqlx::query("INSERT INTO audit (action, item_id, created_at) VALUES ($1, $2, $3)")
        .bind(&entry.action)
        .bind(entry.item_id)
        .bind(entry.created_at)
        .execute(conn)
        .await?;
    Ok(())
}

/// `@Select`
pub async fn count(conn: &mut PgConnection) -> sqlx::Result<i64> {
    sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM audit").fetch_one(conn).await
}
