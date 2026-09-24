//! Translated from `TodoMapper` (TodoMapper.java, TodoMapper.xml) by Java2RustTranslator.

use sqlx::{PgConnection, Postgres, QueryBuilder};

use crate::domain::{Todo, TodoFilter};
use crate::mybatis::Trim;

/// `<select id="findAll">`
pub async fn find_all(
    conn: &mut PgConnection,
    filter: TodoFilter,
    keyword: Option<&str>,
) -> sqlx::Result<Vec<Todo>> {
    let mut query = QueryBuilder::<Postgres>::new(
        "SELECT id, title, description, done, priority, due_date, created_at, updated_at FROM todos",
    );
    let mut where_clause = Trim::new("WHERE", "");
    if filter.name() == "ACTIVE" {
        where_clause.part(&mut query, "AND", "").push("done = FALSE");
    }
    if filter.name() == "COMPLETED" {
        where_clause.part(&mut query, "AND", "").push("done = TRUE");
    }
    if keyword.is_some() && keyword != Some("") {
        where_clause
            .part(&mut query, "AND", "")
            .push("title ILIKE '%' || ")
            .push_bind(keyword)
            .push(" || '%'");
    }
    query.push(" ORDER BY done, due_date NULLS LAST, id");
    query.build_query_as::<Todo>().fetch_all(conn).await
}

/// `<select id="findById">`
pub async fn find_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<Option<Todo>> {
    sqlx::query_as::<_, Todo>(
        "SELECT \
            id, title, description, done, priority, due_date, created_at, updated_at \
            FROM todos \
            WHERE id = $1",
    )
    .bind(id)
    .fetch_optional(conn)
    .await
}

/// `<select id="countByDone">`
pub async fn count_by_done(conn: &mut PgConnection, done: bool) -> sqlx::Result<i64> {
    sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM todos WHERE done = $1")
        .bind(done)
        .fetch_one(conn)
        .await
}

/// `<insert id="insert">`
pub async fn insert(conn: &mut PgConnection, todo: &mut Todo) -> sqlx::Result<()> {
    todo.id = sqlx::query_scalar::<_, i64>(
        "INSERT INTO todos (title, description, done, priority, due_date, created_at, updated_at) \
            VALUES ($1, $2, $3, $4, $5, $6, $7) \
            RETURNING id",
    )
    .bind(&todo.title)
    .bind(&todo.description)
    .bind(todo.done)
    .bind(todo.priority.name())
    .bind(todo.due_date)
    .bind(todo.created_at)
    .bind(todo.updated_at)
    .fetch_one(conn)
    .await?;
    Ok(())
}

/// `<update id="update">`
pub async fn update(conn: &mut PgConnection, todo: &Todo) -> sqlx::Result<i32> {
    let result = sqlx::query(
        "UPDATE todos \
            SET title = $1, \
            description = $2, \
            done = $3, \
            priority = $4, \
            due_date = $5, \
            updated_at = $6 \
            WHERE id = $7",
    )
    .bind(&todo.title)
    .bind(&todo.description)
    .bind(todo.done)
    .bind(todo.priority.name())
    .bind(todo.due_date)
    .bind(todo.updated_at)
    .bind(todo.id)
    .execute(conn)
    .await?;
    Ok(result.rows_affected() as i32)
}

/// `<delete id="deleteById">`
pub async fn delete_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<i32> {
    let result = sqlx::query("DELETE FROM todos WHERE id = $1").bind(id).execute(conn).await?;
    Ok(result.rows_affected() as i32)
}

/// `<delete id="deleteByIds">`
pub async fn delete_by_ids(conn: &mut PgConnection, ids: &[i64]) -> sqlx::Result<i32> {
    let mut query = QueryBuilder::<Postgres>::new("DELETE FROM todos WHERE id IN");
    if !ids.is_empty() {
        query.push(" (");
        for (i, id) in ids.iter().copied().enumerate() {
            if i > 0 {
                query.push(", ");
            }
            query.push_bind(id);
        }
        query.push(")");
    }
    let result = query.build().execute(conn).await?;
    Ok(result.rows_affected() as i32)
}
