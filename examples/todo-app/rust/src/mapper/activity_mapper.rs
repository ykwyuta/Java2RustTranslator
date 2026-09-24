//! Translated from `ActivityMapper` (ActivityMapper.java, ActivityMapper.xml) by Java2RustTranslator.

use sqlx::{PgConnection, Postgres, QueryBuilder};
use sqlx::postgres::PgRow;

use crate::domain::Activity;
use crate::mybatis::column;

/// `<select id="findRecent">`
pub async fn find_recent(conn: &mut PgConnection, limit: i32) -> sqlx::Result<Vec<Activity>> {
    sqlx::query(
        "SELECT id AS activity_id, todo_id, action, title AS todo_title, created_at \
            FROM activities \
            ORDER BY id DESC \
            LIMIT $1",
    )
    .bind(limit)
    .try_map(activity_result_map)
    .fetch_all(conn)
    .await
}

/// `<insert id="insert">`
pub async fn insert(conn: &mut PgConnection, activity: &Activity) -> sqlx::Result<()> {
    sqlx::query(
        "INSERT INTO activities (todo_id, action, title, created_at) \
            VALUES ($1, $2, $3, $4)",
    )
    .bind(activity.todo_id)
    .bind(activity.action.name())
    .bind(&activity.title)
    .bind(activity.created_at)
    .execute(conn)
    .await?;
    Ok(())
}

/// `<insert id="insertAll">`
pub async fn insert_all(conn: &mut PgConnection, activities: &[Activity]) -> sqlx::Result<()> {
    let mut query = QueryBuilder::<Postgres>::new(
        "INSERT INTO activities (todo_id, action, title, created_at) VALUES",
    );
    for (i, a) in activities.iter().enumerate() {
        if i > 0 {
            query.push(",");
        }
        query
            .push(" (")
            .push_bind(a.todo_id)
            .push(", ")
            .push_bind(a.action.name())
            .push(", ")
            .push_bind(&a.title)
            .push(", ")
            .push_bind(a.created_at)
            .push(")");
    }
    query.build().execute(conn).await?;
    Ok(())
}

/// `<resultMap id="activityResultMap">` の行の対応。
fn activity_result_map(row: PgRow) -> sqlx::Result<Activity> {
    let mut activity = Activity::default();
    if let Some(v) = column(&row, "activity_id")? {
        activity.id = v;
    }
    if let Some(v) = column(&row, "todo_id")? {
        activity.todo_id = v;
    }
    if let Some(v) = column(&row, "action")? {
        activity.action = v;
    }
    if let Some(v) = column(&row, "todo_title")? {
        activity.title = v;
    }
    if let Some(v) = column(&row, "created_at")? {
        activity.created_at = v;
    }
    Ok(activity)
}
