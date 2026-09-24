//! Exceptions thrown by the services, translated by Java2RustTranslator.

/// サービスが返すエラー。例外クラスごとのバリアントを持つ。
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// ItemNotFoundException
    #[error("Item not found: {0}")]
    ItemNotFound(i64),
    /// SoldOutException
    #[error("Sold out: {0}")]
    SoldOut(i64),
    /// データベースのエラー（Spring の DataAccessException に当たる）。
    #[error(transparent)]
    Database(#[from] sqlx::Error),
}

pub type Result<T> = std::result::Result<T, Error>;
