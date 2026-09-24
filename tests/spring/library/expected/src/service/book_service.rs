//! Translated from `BookService` (BookService.java) by Java2RustTranslator.

use sqlx::{PgConnection, PgPool};

use crate::domain::{Book, Sort, StockSummary};
use crate::error::{Error, Result};
use crate::mapper::book_mapper;

/// トランザクションはメソッドごとに付ける（クラスには付けない）。
#[derive(Clone)]
pub struct BookService {
    pool: PgPool,
}

impl BookService {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn register(&self, title: &str, author: Option<&str>, stock: i32) -> Result<Book> {
        let mut tx = self.pool.begin().await?;
        if title.trim().is_empty() {
            return Err(Error::InvalidBook(title.to_string()));
        }
        let mut book = Book {
            title: title.trim().to_string(),
            author: author.map(|author| author.trim()).map(str::to_string),
            stock,
            ..Default::default()
        };
        book_mapper::insert(&mut tx, &mut book).await?;
        tx.commit().await?;
        Ok(book)
    }

    /// `@Transactional(readOnly = true)`
    pub async fn search(
        &self,
        keyword: Option<&str>,
        in_stock_only: bool,
        sort: Sort,
    ) -> Result<Vec<Book>> {
        let mut tx = self.pool.begin().await?;
        let result = book_mapper::search(
            &mut tx,
            self.normalize(keyword).as_deref(),
            in_stock_only,
            sort,
        ).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// トランザクションを張らずに読む（Mapper の呼び出しごとに自動コミット）。
    pub async fn get(&self, id: i64) -> Result<Book> {
        let mut conn = self.pool.acquire().await?;
        self.get_in(&mut conn, id).await
    }

    /// `get` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn get_in(&self, conn: &mut PgConnection, id: i64) -> Result<Book> {
        let book = book_mapper::find_by_id(conn, id).await?;
        let Some(book) = book else {
            return Err(Error::BookNotFound(id));
        };
        Ok(book)
    }

    /// 在庫を amount 増やし、更新した冊数を返す。
    pub async fn restock(&self, ids: &[i64], amount: i32) -> Result<i32> {
        let mut tx = self.pool.begin().await?;
        let mut count = 0;
        for id in ids.iter().copied() {
            let mut book = self.get_in(&mut tx, id).await?;
            book.stock = book.stock + amount;
            book_mapper::update_selective(&mut tx, &book).await?;
            count += 1;
        }
        tx.commit().await?;
        Ok(count)
    }

    pub async fn archive(&self, id: i64) -> Result<bool> {
        let mut tx = self.pool.begin().await?;
        let result = self.archive_in(&mut tx, id).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// `archive` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn archive_in(&self, conn: &mut PgConnection, id: i64) -> Result<bool> {
        if !book_mapper::archive(conn, id).await? {
            return Ok(false);
        }
        Ok(true)
    }

    /// `@Transactional(readOnly = true)`
    pub async fn books_by(&self, author: &str) -> Result<Vec<i64>> {
        let mut tx = self.pool.begin().await?;
        let result = book_mapper::find_ids_by_author(&mut tx, author).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// `@Transactional(readOnly = true)`
    pub async fn summary(&self) -> Result<StockSummary> {
        let mut tx = self.pool.begin().await?;
        let result = book_mapper::summary(&mut tx).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// 画面に出す名前。
    pub fn label(&self, book: &Book) -> String {
        if let Some(author) = book.author.as_deref() {
            format!("{} / {author}", book.title)
        } else {
            book.title.clone()
        }
    }

    fn normalize(&self, keyword: Option<&str>) -> Option<String> {
        let keyword = keyword?;
        let s = keyword.trim().to_string();
        if s.is_empty() {
            None
        } else {
            Some(s.to_lowercase())
        }
    }
}
