//! TodoService.java（+ TodoNotFoundException・TodoSummary）に相当する。
//!
//! @Transactional は明示的なトランザクション（pool.begin() 〜 commit()）になる。
//! commit() せずに Err で抜けると、Transaction の drop でロールバックされる
//! （実行時例外でロールバックする Spring の既定の動作と同じ）。

use std::sync::Arc;

use chrono::{NaiveDate, NaiveDateTime};
use sqlx::PgPool;

use crate::clock::Clock;
use crate::domain::{NewTodo, Todo, TodoFilter};
use crate::mapper::todo_mapper;

/// サービス層の例外。TodoNotFoundException と、DataAccessException に当たる DB エラー。
#[derive(Debug, thiserror::Error)]
pub enum ServiceError {
    #[error("Todo not found: id={0}")]
    TodoNotFound(i64),
    #[error(transparent)]
    Database(#[from] sqlx::Error),
}

pub type Result<T> = std::result::Result<T, ServiceError>;

/// 一覧画面に出す件数。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TodoSummary {
    pub active: i64,
    pub completed: i64,
}

impl TodoSummary {
    pub fn total(&self) -> i64 {
        self.active + self.completed
    }
}

/// @Service。PgPool は内部で Arc を持つので、TodoService の clone は安価。
#[derive(Clone)]
pub struct TodoService {
    pool: PgPool,
    clock: Arc<dyn Clock>,
}

impl TodoService {
    pub fn new(pool: PgPool, clock: Arc<dyn Clock>) -> Self {
        Self { pool, clock }
    }

    /// @Transactional(readOnly = true)
    pub async fn find_all(&self, filter: TodoFilter, keyword: Option<&str>) -> Result<Vec<Todo>> {
        let mut conn = self.pool.acquire().await?;
        Ok(todo_mapper::find_all(&mut conn, filter, keyword.map(str::trim)).await?)
    }

    /// @Transactional(readOnly = true)
    pub async fn find_by_id(&self, id: i64) -> Result<Todo> {
        let mut conn = self.pool.acquire().await?;
        todo_mapper::find_by_id(&mut conn, id)
            .await?
            .ok_or(ServiceError::TodoNotFound(id))
    }

    /// @Transactional(readOnly = true)
    pub async fn summary(&self) -> Result<TodoSummary> {
        let mut tx = self.pool.begin().await?;
        let active = todo_mapper::count_by_done(&mut tx, false).await?;
        let completed = todo_mapper::count_by_done(&mut tx, true).await?;
        tx.commit().await?;
        Ok(TodoSummary { active, completed })
    }

    pub async fn create(
        &self,
        title: String,
        description: Option<String>,
        due_date: Option<NaiveDate>,
    ) -> Result<Todo> {
        let now = self.now();
        let new_todo = NewTodo {
            title,
            description,
            done: false,
            due_date,
            created_at: now,
            updated_at: now,
        };
        let mut tx = self.pool.begin().await?;
        let id = todo_mapper::insert(&mut tx, &new_todo).await?;
        tx.commit().await?;
        let NewTodo {
            title,
            description,
            done,
            due_date,
            created_at,
            updated_at,
        } = new_todo;
        Ok(Todo {
            id,
            title,
            description,
            done,
            due_date,
            created_at,
            updated_at,
        })
    }

    pub async fn update(
        &self,
        id: i64,
        title: String,
        description: Option<String>,
        due_date: Option<NaiveDate>,
        done: bool,
    ) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let mut todo = todo_mapper::find_by_id(&mut tx, id)
            .await?
            .ok_or(ServiceError::TodoNotFound(id))?;
        todo.title = title;
        todo.description = description;
        todo.due_date = due_date;
        todo.done = done;
        todo.updated_at = self.now();
        todo_mapper::update(&mut tx, &todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn toggle(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let mut todo = todo_mapper::find_by_id(&mut tx, id)
            .await?
            .ok_or(ServiceError::TodoNotFound(id))?;
        todo.done = !todo.done;
        todo.updated_at = self.now();
        todo_mapper::update(&mut tx, &todo).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn delete(&self, id: i64) -> Result<Todo> {
        let mut tx = self.pool.begin().await?;
        let todo = todo_mapper::find_by_id(&mut tx, id)
            .await?
            .ok_or(ServiceError::TodoNotFound(id))?;
        todo_mapper::delete_by_id(&mut tx, id).await?;
        tx.commit().await?;
        Ok(todo)
    }

    pub async fn delete_completed(&self) -> Result<u64> {
        let mut tx = self.pool.begin().await?;
        let count = todo_mapper::delete_completed(&mut tx).await?;
        tx.commit().await?;
        Ok(count)
    }

    fn now(&self) -> NaiveDateTime {
        self.clock.now()
    }
}
