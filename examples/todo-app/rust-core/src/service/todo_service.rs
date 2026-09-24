//! Translated from `TodoService` (TodoService.java) by Java2RustTranslator.

use std::sync::Arc;

use chrono::{NaiveDate, NaiveDateTime};
use sqlx::{PgConnection, PgPool};

use crate::clock::Clock;
use crate::domain::{Todo, TodoFilter};
use crate::error::{Error, Result};
use crate::mapper::todo_mapper;
use crate::service::TodoSummary;

#[derive(Clone)]
pub struct TodoService {
    pool: PgPool,
    clock: Arc<dyn Clock>,
}

impl TodoService {
    pub fn new(pool: PgPool, clock: Arc<dyn Clock>) -> Self {
        Self { pool, clock }
    }

    /// `@Transactional(readOnly = true)`
    pub async fn find_all(&self, filter: TodoFilter, keyword: Option<&str>) -> Result<Vec<Todo>> {
        let mut tx = self.pool.begin().await?;
        let result = todo_mapper::find_all(&mut tx, filter, keyword.map(|keyword| keyword.trim())).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// `@Transactional(readOnly = true)`
    pub async fn find_by_id(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let result = self.find_by_id_in(&mut tx, id).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// `find_by_id` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn find_by_id_in(&self, conn: &mut PgConnection, id: i64) -> Result<Todo> {
        todo_mapper::find_by_id(conn, id).await?.ok_or_else(|| Error::TodoNotFound(id))
    }

    /// `@Transactional(readOnly = true)`
    pub async fn summary(&self) -> Result<TodoSummary> {
        let mut tx = self.pool.begin().await?;
        let result = TodoSummary {
            active: todo_mapper::count_by_done(&mut tx, false).await?,
            completed: todo_mapper::count_by_done(&mut tx, true).await?,
        };
        tx.commit().await?;
        Ok(result)
    }

    pub async fn create(
        &self,
        title: &str,
        description: Option<&str>,
        due_date: Option<NaiveDate>,
    ) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let now = self.now();
        let mut todo = Todo {
            title: title.to_string(),
            description: description.map(str::to_string),
            done: false,
            due_date,
            created_at: now,
            updated_at: now,
            ..Default::default()
        };
        todo_mapper::insert(&mut tx, &mut todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn update(
        &self,
        id: i64,
        title: &str,
        description: Option<&str>,
        due_date: Option<NaiveDate>,
        done: bool,
    ) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let mut todo = self.find_by_id_in(&mut tx, id).await?;
        todo.title = title.to_string();
        todo.description = description.map(str::to_string);
        todo.due_date = due_date;
        todo.done = done;
        todo.updated_at = self.now();
        todo_mapper::update(&mut tx, &todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn toggle(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let mut todo = self.find_by_id_in(&mut tx, id).await?;
        todo.done = !todo.done;
        todo.updated_at = self.now();
        todo_mapper::update(&mut tx, &todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn delete(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let todo = self.find_by_id_in(&mut tx, id).await?;
        todo_mapper::delete_by_id(&mut tx, id).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn delete_completed(&self) -> Result<i32> {
        let mut tx = self.pool.begin().await?;
        let result = todo_mapper::delete_completed(&mut tx).await?;
        tx.commit().await?;
        Ok(result)
    }

    fn now(&self) -> NaiveDateTime {
        self.clock.now()
    }
}
