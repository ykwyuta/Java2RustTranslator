//! Translated from `ActivityService` (ActivityService.java) by Java2RustTranslator.

use std::sync::Arc;

use sqlx::{PgConnection, PgPool};

use crate::clock::Clock;
use crate::domain::{Activity, ActivityAction, Todo};
use crate::error::Result;
use crate::mapper::activity_mapper;

/// Todo の操作履歴。TodoService から呼ばれ、呼び出し元のトランザクションに参加する（propagation = REQUIRED）。
/// 履歴は todos を外部キーで参照するので、まだコミットしていない Todo の履歴は同じトランザクションでしか書けない。
#[derive(Clone)]
pub struct ActivityService {
    pool: PgPool,
    clock: Arc<dyn Clock>,
}

impl ActivityService {
    pub fn new(pool: PgPool, clock: Arc<dyn Clock>) -> Self {
        Self { pool, clock }
    }

    pub async fn record(&self, action: ActivityAction, todo: &Todo) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        self.record_in(&mut tx, action, todo).await?;
        tx.commit().await?;
        Ok(())
    }

    /// `record` の本体。呼び出し元の接続（トランザクション）で実行する。
    pub(crate) async fn record_in(
        &self,
        conn: &mut PgConnection,
        action: ActivityAction,
        todo: &Todo,
    ) -> Result<()> {
        let activity = Activity {
            todo_id: Some(todo.id),
            action,
            title: todo.title.clone(),
            created_at: self.clock.now(),
            ..Default::default()
        };
        activity_mapper::insert(conn, &activity).await?;
        Ok(())
    }

    pub async fn record_all(&self, action: ActivityAction, todos: &[Todo]) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        self.record_all_in(&mut tx, action, todos).await?;
        tx.commit().await?;
        Ok(())
    }

    /// `record_all` の本体。呼び出し元の接続（トランザクション）で実行する。
    pub(crate) async fn record_all_in(
        &self,
        conn: &mut PgConnection,
        action: ActivityAction,
        todos: &[Todo],
    ) -> Result<()> {
        let now = self.clock.now();
        let mut activities = Vec::new();
        for todo in todos {
            let activity = Activity {
                todo_id: Some(todo.id),
                action,
                title: todo.title.clone(),
                created_at: now,
                ..Default::default()
            };
            activities.push(activity);
        }
        activity_mapper::insert_all(conn, &activities).await?;
        Ok(())
    }

    /// `@Transactional(readOnly = true)`
    pub async fn recent(&self, limit: i32) -> Result<Vec<Activity>> {
        let mut tx = self.pool.begin().await?;
        let result = activity_mapper::find_recent(&mut tx, limit).await?;
        tx.commit().await?;
        Ok(result)
    }
}
