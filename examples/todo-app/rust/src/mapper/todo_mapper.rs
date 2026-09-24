//! TodoMapper.java + TodoMapper.xml に相当する。
//!
//! MyBatis の Mapper は Spring 管理のトランザクションに暗黙に参加するが、Rust では接続
//! （プールから借りた接続、またはトランザクション）を引数で明示的に受け取る。

use sqlx::{PgConnection, Postgres, QueryBuilder};

use crate::domain::{NewTodo, Todo, TodoFilter};

/// <sql id="columns">。SQL を文字列リテラルのまま組み立てられるようにマクロにする。
macro_rules! columns {
    () => {
        "id, title, description, done, due_date, created_at, updated_at"
    };
}

/// <select id="findAll">。<where> と <if> による動的 SQL は QueryBuilder で組み立てる。
pub async fn find_all(
    conn: &mut PgConnection,
    filter: TodoFilter,
    keyword: Option<&str>,
) -> sqlx::Result<Vec<Todo>> {
    let mut query = QueryBuilder::<Postgres>::new(concat!("SELECT ", columns!(), " FROM todos"));
    let mut conditions = Conditions::default();
    match filter {
        TodoFilter::All => {}
        TodoFilter::Active => {
            conditions.and(&mut query).push("done = FALSE");
        }
        TodoFilter::Completed => {
            conditions.and(&mut query).push("done = TRUE");
        }
    }
    if let Some(keyword) = keyword.filter(|k| !k.is_empty()) {
        conditions
            .and(&mut query)
            .push("title ILIKE '%' || ")
            .push_bind(keyword)
            .push(" || '%'");
    }
    query.push(" ORDER BY done, due_date NULLS LAST, id");
    query.build_query_as::<Todo>().fetch_all(conn).await
}

/// <select id="findById">。Optional<Todo> は Option<Todo> になる。
pub async fn find_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<Option<Todo>> {
    sqlx::query_as::<_, Todo>(concat!("SELECT ", columns!(), " FROM todos WHERE id = $1"))
        .bind(id)
        .fetch_optional(conn)
        .await
}

/// <select id="countByDone">
pub async fn count_by_done(conn: &mut PgConnection, done: bool) -> sqlx::Result<i64> {
    sqlx::query_scalar("SELECT COUNT(*) FROM todos WHERE done = $1")
        .bind(done)
        .fetch_one(conn)
        .await
}

/// <insert id="insert" useGeneratedKeys="true" keyProperty="id">。
/// 生成されたキーは RETURNING で受け取って返す。
pub async fn insert(conn: &mut PgConnection, todo: &NewTodo) -> sqlx::Result<i64> {
    sqlx::query_scalar(
        "INSERT INTO todos (title, description, done, due_date, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id",
    )
    .bind(&todo.title)
    .bind(&todo.description)
    .bind(todo.done)
    .bind(todo.due_date)
    .bind(todo.created_at)
    .bind(todo.updated_at)
    .fetch_one(conn)
    .await
}

/// <update id="update">。戻り値は更新件数。
pub async fn update(conn: &mut PgConnection, todo: &Todo) -> sqlx::Result<u64> {
    let result = sqlx::query(
        "UPDATE todos
         SET title = $1,
             description = $2,
             done = $3,
             due_date = $4,
             updated_at = $5
         WHERE id = $6",
    )
    .bind(&todo.title)
    .bind(&todo.description)
    .bind(todo.done)
    .bind(todo.due_date)
    .bind(todo.updated_at)
    .bind(todo.id)
    .execute(conn)
    .await?;
    Ok(result.rows_affected())
}

/// <delete id="deleteById">
pub async fn delete_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<u64> {
    let result = sqlx::query("DELETE FROM todos WHERE id = $1")
        .bind(id)
        .execute(conn)
        .await?;
    Ok(result.rows_affected())
}

/// <delete id="deleteCompleted">
pub async fn delete_completed(conn: &mut PgConnection) -> sqlx::Result<u64> {
    let result = sqlx::query("DELETE FROM todos WHERE done = TRUE")
        .execute(conn)
        .await?;
    Ok(result.rows_affected())
}

/// MyBatis の <where>: 最初の条件の前に WHERE、以降の条件の前に AND を付ける。
#[derive(Default)]
struct Conditions {
    started: bool,
}

impl Conditions {
    fn and<'a>(&mut self, query: &'a mut QueryBuilder<Postgres>) -> &'a mut QueryBuilder<Postgres> {
        query.push(if self.started { " AND " } else { " WHERE " });
        self.started = true;
        query
    }
}
