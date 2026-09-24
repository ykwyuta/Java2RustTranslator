//! Translated from `AuditEntry` (AuditEntry.java) by Java2RustTranslator.

use chrono::NaiveDateTime;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AuditEntry {
    pub action: String,
    pub item_id: Option<i64>,
    pub created_at: NaiveDateTime,
}
