//! Translated from `TodoForm` (TodoForm.java) by Java2RustTranslator.

use chrono::NaiveDate;

use crate::domain::Todo;
use crate::spring_web::{BindingResult, RequestParams, length, not_blank, parse_bool, parse_date};

/// 登録・編集フォーム。name 属性は title / description / dueDate / done。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TodoForm {
    pub title: Option<String>,
    pub description: Option<String>,
    pub due_date: Option<NaiveDate>,
    pub done: bool,
}

impl TodoForm {
    pub fn from(todo: &Todo) -> TodoForm {
        TodoForm {
            title: Some(todo.title.clone()),
            description: todo.description.clone(),
            due_date: todo.due_date,
            done: todo.done,
        }
    }

    /// 前後の空白を除いたタイトル。
    pub fn normalized_title(&self) -> String {
        self.title.as_deref().map_or("", |title| title.trim()).to_string()
    }

    /// 空の説明は null として保存する。
    pub fn normalized_description(&self) -> Option<String> {
        let description = self.description.as_deref()?;
        if description.trim().is_empty() {
            return None;
        }
        Some(description.trim().to_string())
    }

    /// リクエストパラメータをバインドする（Spring の DataBinder）。型変換に失敗したフィールドはエラーにする。
    pub fn bind(params: &RequestParams) -> (Self, BindingResult) {
        let mut form = Self::default();
        let mut result = BindingResult::default();
        if let Some(v) = params.get("title") {
            form.title = Some(v.to_string());
        }
        if let Some(v) = params.get("description") {
            form.description = Some(v.to_string());
        }
        if let Some(v) = params.get("dueDate") {
            match parse_date(v, "%Y-%m-%d") {
                Ok(x) => {
                    form.due_date = x;
                }
                Err(_) => result.reject("dueDate", "期限は yyyy-MM-dd 形式で入力してください", Some(v)),
            }
        }
        if let Some(v) = params.get("done") {
            match parse_bool(v) {
                Ok(x) => {
                    if let Some(x) = x {
                        form.done = x;
                    }
                }
                Err(_) => result.reject(
                    "done",
                    "Failed to convert property value of type 'java.lang.String' to required type 'boolean' for property 'done'",
                    Some(v),
                ),
            }
        } else if params.contains("_done") {
            form.done = false;
        }
        (form, result)
    }

    /// Bean Validation の検証（`@Valid`）。
    pub fn validate(&self, result: &mut BindingResult) {
        if !not_blank(self.title.as_deref()) {
            result.reject("title", "タイトルを入力してください", None);
        }
        if self.title.as_deref().is_some_and(|v| length(v) > 100) {
            result.reject("title", "タイトルは100文字以内で入力してください", None);
        }
        if self.description.as_deref().is_some_and(|v| length(v) > 1000) {
            result.reject("description", "説明は1000文字以内で入力してください", None);
        }
    }
}
