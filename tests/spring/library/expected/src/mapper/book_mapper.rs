//! Translated from `BookMapper` (BookMapper.java, BookMapper.xml) by Java2RustTranslator.

use sqlx::{PgConnection, Postgres, QueryBuilder};

use crate::domain::{Book, Sort, StockSummary};
use crate::mybatis::Clause;

/// `@Select`
pub async fn find_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<Option<Book>> {
    sqlx::query_as::<_, Book>("SELECT id, title, author, stock, archived FROM books WHERE id = $1")
        .bind(id)
        .fetch_optional(conn)
        .await
}

/// `@Insert`
pub async fn insert(conn: &mut PgConnection, book: &mut Book) -> sqlx::Result<i32> {
    book.id = sqlx::query_scalar::<_, i64>(
        "INSERT INTO books (title, author, stock, archived) VALUES ($1, $2, $3, $4) \
            RETURNING id",
    )
    .bind(&book.title)
    .bind(&book.author)
    .bind(book.stock)
    .bind(book.archived)
    .fetch_one(conn)
    .await?;
    Ok(1)
}

/// `<select id="search">`
pub async fn search(
    conn: &mut PgConnection,
    keyword: Option<&str>,
    in_stock_only: bool,
    sort: Sort,
) -> sqlx::Result<Vec<Book>> {
    let mut query = QueryBuilder::<Postgres>::new(
        "SELECT id, title, author, stock, archived FROM books",
    );
    let mut where_clause = Clause::where_clause();
    where_clause.part(&mut query, " AND ").push("archived = FALSE");
    if keyword.is_some() && keyword != Some("") {
        where_clause
            .part(&mut query, " AND ")
            .push("(title ILIKE '%' || ")
            .push_bind(keyword)
            .push(" || '%' OR author ILIKE '%' || ")
            .push_bind(keyword)
            .push(" || '%')");
    }
    if in_stock_only {
        where_clause.part(&mut query, " AND ").push("stock > 0");
    }
    if sort.name() == "STOCK_DESC" {
        query.push(" ORDER BY stock DESC, id");
    } else {
        query.push(" ORDER BY title, id");
    }
    query.build_query_as::<Book>().fetch_all(conn).await
}

/// `<select id="findIdsByAuthor">`
pub async fn find_ids_by_author(conn: &mut PgConnection, author: &str) -> sqlx::Result<Vec<i64>> {
    sqlx::query_scalar::<_, i64>("SELECT id FROM books WHERE author = $1 ORDER BY id")
        .bind(author)
        .fetch_all(conn)
        .await
}

/// `<update id="updateSelective">`
pub async fn update_selective(conn: &mut PgConnection, book: &Book) -> sqlx::Result<i32> {
    let mut query = QueryBuilder::<Postgres>::new("UPDATE books");
    let mut set_clause = Clause::set_clause();
    set_clause.part(&mut query, ", ").push("title = ").push_bind(&book.title);
    if book.author.is_some() {
        set_clause.part(&mut query, ", ").push("author = ").push_bind(&book.author);
    }
    if book.stock >= 0 {
        set_clause.part(&mut query, ", ").push("stock = ").push_bind(book.stock);
    }
    query.push(" WHERE id = ").push_bind(book.id);
    let result = query.build().execute(conn).await?;
    Ok(result.rows_affected() as i32)
}

/// `<update id="archive">`
pub async fn archive(conn: &mut PgConnection, id: i64) -> sqlx::Result<bool> {
    let result = sqlx::query("UPDATE books SET archived = TRUE WHERE id = $1 AND archived = FALSE")
        .bind(id)
        .execute(conn)
        .await?;
    Ok(result.rows_affected() > 0)
}

/// `@Select`
pub async fn summary(conn: &mut PgConnection) -> sqlx::Result<StockSummary> {
    sqlx::query_as::<_, StockSummary>(
        "SELECT COUNT(*) AS books, COALESCE(SUM(stock), 0) AS total_stock FROM books WHERE archived = FALSE",
    ).fetch_one(conn).await
}
