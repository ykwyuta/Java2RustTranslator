//! Translated from `TodoService` (TodoService.java) by Java2RustTranslator.

use std::sync::Arc;

use chrono::{NaiveDate, NaiveDateTime};
use sqlx::{PgConnection, PgPool};

use crate::clock::Clock;
use crate::domain::{ActivityAction, Priority, Todo, TodoFilter};
use crate::error::{Error, Result};
use crate::mapper::todo_mapper;
use crate::service::{ActivityService, TodoSummary};

#[derive(Clone)]
pub struct TodoService {
    pool: PgPool,
    activity_service: ActivityService,
    clock: Arc<dyn Clock>,
}

impl TodoService {
    pub fn new(pool: PgPool, activity_service: ActivityService, clock: Arc<dyn Clock>) -> Self {
        Self { pool, activity_service, clock }
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
        priority: Priority,
    ) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let now = self.now();
        let mut todo = Todo {
            title: title.to_string(),
            description: description.map(str::to_string),
            done: false,
            due_date,
            priority,
            created_at: now,
            updated_at: now,
            ..Default::default()
        };
        todo_mapper::insert(&mut tx, &mut todo).await?;
        self.activity_service.record_in(&mut tx, ActivityAction::Create, &todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn update(
        &self,
        id: i64,
        title: &str,
        description: Option<&str>,
        due_date: Option<NaiveDate>,
        priority: Priority,
        done: bool,
    ) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let mut todo = self.find_by_id_in(&mut tx, id).await?;
        todo.title = title.to_string();
        todo.description = description.map(str::to_string);
        todo.due_date = due_date;
        todo.priority = priority;
        todo.done = done;
        todo.updated_at = self.now();
        todo_mapper::update(&mut tx, &todo).await?;
        self.activity_service.record_in(&mut tx, ActivityAction::Update, &todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn toggle(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let mut todo = self.find_by_id_in(&mut tx, id).await?;
        todo.done = !todo.done;
        todo.updated_at = self.now();
        todo_mapper::update(&mut tx, &todo).await?;
        self.activity_service.record_in(
            &mut tx,
            if todo.done {
                ActivityAction::Complete
            } else {
                ActivityAction::Reopen
            },
            &todo,
        ).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn delete(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let todo = self.find_by_id_in(&mut tx, id).await?;
        self.activity_service.record_in(&mut tx, ActivityAction::Delete, &todo).await?;
        todo_mapper::delete_by_id(&mut tx, id).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn delete_completed(&self) -> Result<i32> {
        let mut tx = self.pool.begin().await?;
        let result = self.delete_completed_in(&mut tx).await?;
        tx.commit().await?;
        Ok(result)
    }

    /// `delete_completed` の本体。呼び出し元の接続（トランザクション）で実行する。
    async fn delete_completed_in(&self, conn: &mut PgConnection) -> Result<i32> {
        let completed = todo_mapper::find_all(conn, TodoFilter::Completed, None).await?;
        if completed.is_empty() {
            return Ok(0);
        }
        self.activity_service.record_all_in(conn, ActivityAction::Delete, &completed).await?;
        let mut ids = Vec::new();
        for todo in &completed {
            ids.push(todo.id);
        }
        Ok(todo_mapper::delete_by_ids(conn, &ids).await?)
    }

    fn now(&self) -> NaiveDateTime {
        self.clock.now()
    }
}
