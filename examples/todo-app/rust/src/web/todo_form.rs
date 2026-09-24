//! TodoForm.java に相当する。
//!
//! Spring はバインドと検証の結果を BindingResult に集めるが、ここでは文字列のまま受け取った
//! フォームを validate() で検証・変換し、エラーは FieldErrors で返す。

use std::collections::BTreeMap;

use chrono::NaiveDate;
use serde::Deserialize;
use validator::{Validate, ValidationError};

use crate::domain::Todo;

/// 登録・編集フォーム。name 属性は title / description / dueDate / done。
#[derive(Debug, Default, Clone, Deserialize, Validate)]
#[serde(rename_all = "camelCase", default)]
pub struct TodoForm {
    #[validate(
        custom(function = "not_blank", message = "タイトルを入力してください"),
        length(max = 100, message = "タイトルは100文字以内で入力してください")
    )]
    pub title: String,

    #[validate(length(max = 1000, message = "説明は1000文字以内で入力してください"))]
    pub description: String,

    /// @DateTimeFormat(iso = DATE)。不正な値を入力し直せるよう文字列のまま持つ。
    pub due_date: String,

    pub done: bool,
}

/// 検証を通ったフォームの値。
#[derive(Debug, Clone, PartialEq)]
pub struct TodoInput {
    pub title: String,
    pub description: Option<String>,
    pub due_date: Option<NaiveDate>,
    pub done: bool,
}

/// BindingResult のフィールドエラー（フィールド名 → メッセージ）。
#[derive(Debug, Default, Clone)]
pub struct FieldErrors(BTreeMap<&'static str, Vec<String>>);

impl FieldErrors {
    /// #fields.errors('title')
    pub fn of(&self, field: &str) -> &[String] {
        self.0.get(field).map_or(&[], Vec::as_slice)
    }

    /// #fields.hasErrors('title')
    pub fn has(&self, field: &str) -> bool {
        !self.of(field).is_empty()
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    fn add(&mut self, field: &'static str, message: String) {
        self.0.entry(field).or_default().push(message);
    }
}

impl TodoForm {
    pub fn from_todo(todo: &Todo) -> Self {
        Self {
            title: todo.title.clone(),
            description: todo.description.clone().unwrap_or_default(),
            due_date: todo
                .due_date
                .map(|d| d.format("%Y-%m-%d").to_string())
                .unwrap_or_default(),
            done: todo.done,
        }
    }

    /// @Valid による検証と型変換。成功すれば正規化した値を返す。
    pub fn validated(&self) -> Result<TodoInput, FieldErrors> {
        let mut errors = FieldErrors::default();
        if let Err(e) = self.validate() {
            for (field, field_errors) in e.field_errors() {
                let name = match field.as_ref() {
                    "title" => "title",
                    "description" => "description",
                    other => unreachable!("no validation rule on {other}"),
                };
                for error in field_errors {
                    errors.add(
                        name,
                        error
                            .message
                            .as_deref()
                            .unwrap_or("入力が正しくありません")
                            .to_string(),
                    );
                }
            }
        }
        let due_date = self.parse_due_date().unwrap_or_else(|message| {
            errors.add("dueDate", message.into());
            None
        });
        if !errors.is_empty() {
            return Err(errors);
        }
        Ok(TodoInput {
            title: self.normalized_title(),
            description: self.normalized_description(),
            due_date,
            done: self.done,
        })
    }

    /// 前後の空白を除いたタイトル。
    fn normalized_title(&self) -> String {
        self.title.trim().to_string()
    }

    /// 空の説明は None（NULL）として保存する。
    fn normalized_description(&self) -> Option<String> {
        let description = self.description.trim();
        (!description.is_empty()).then(|| description.to_string())
    }

    /// 空は None、yyyy-MM-dd 以外は typeMismatch のエラー（messages.properties の文言）。
    fn parse_due_date(&self) -> Result<Option<NaiveDate>, &'static str> {
        let value = self.due_date.trim();
        if value.is_empty() {
            return Ok(None);
        }
        NaiveDate::parse_from_str(value, "%Y-%m-%d")
            .map(Some)
            .map_err(|_| "期限は yyyy-MM-dd 形式で入力してください")
    }
}

/// @NotBlank
fn not_blank(value: &str) -> Result<(), ValidationError> {
    if value.trim().is_empty() {
        Err(ValidationError::new("not_blank"))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn form(title: &str, description: &str, due_date: &str) -> TodoForm {
        TodoForm {
            title: title.into(),
            description: description.into(),
            due_date: due_date.into(),
            done: false,
        }
    }

    #[test]
    fn valid_form_is_normalized() {
        let input = form("  牛乳を買う ", "  ", "2026-10-01")
            .validated()
            .unwrap();
        assert_eq!(input.title, "牛乳を買う");
        assert_eq!(input.description, None);
        assert_eq!(input.due_date, NaiveDate::from_ymd_opt(2026, 10, 1));
    }

    #[test]
    fn errors_are_collected_per_field() {
        let errors = form(" ", &"x".repeat(1001), "not-a-date")
            .validated()
            .unwrap_err();
        assert_eq!(errors.of("title"), ["タイトルを入力してください"]);
        assert_eq!(
            errors.of("description"),
            ["説明は1000文字以内で入力してください"]
        );
        assert_eq!(
            errors.of("dueDate"),
            ["期限は yyyy-MM-dd 形式で入力してください"]
        );
    }

    #[test]
    fn length_is_counted_in_characters() {
        assert!(form(&"あ".repeat(100), "", "").validated().is_ok());
        assert!(
            form(&"あ".repeat(101), "", "")
                .validated()
                .unwrap_err()
                .has("title")
        );
    }
}
