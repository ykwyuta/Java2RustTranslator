//! Exceptions thrown by the services, translated by Java2RustTranslator.

/// サービスが返すエラー。例外クラスごとのバリアントを持つ。
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// TodoNotFoundException
    #[error("Todo not found: id={0}")]
    TodoNotFound(i64),
    /// データベースのエラー（Spring の DataAccessException に当たる）。
    #[error(transparent)]
    Database(#[from] sqlx::Error),
}

pub type Result<T> = std::result::Result<T, Error>;
