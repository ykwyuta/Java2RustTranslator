//! Exceptions thrown by the services, translated by Java2RustTranslator.

/// サービスが返すエラー。例外クラスごとのバリアントを持つ。
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// BookNotFoundException
    #[error("book {0} not found")]
    BookNotFound(i64),
    /// InvalidBookException
    #[error("invalid title: '{0}'")]
    InvalidBook(String),
    /// データベースのエラー（Spring の DataAccessException に当たる）。
    #[error(transparent)]
    Database(#[from] sqlx::Error),
}

pub type Result<T> = std::result::Result<T, Error>;
