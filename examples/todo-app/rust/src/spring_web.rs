//! Spring MVC の仕組み（リクエストパラメータ・フラッシュ属性・BindingResult・リダイレクト・設定値）に当たる補助
//! （Java2RustTranslator の補助モジュール）。

use std::collections::HashMap;

use axum::extract::{FromRequest, FromRequestParts, Request};
use axum::http::header::{CONTENT_TYPE, LOCATION};
use axum::http::request::Parts;
use axum::http::{HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use serde::de::DeserializeOwned;
use tower_sessions::Session;

/// 400 Bad Request（パラメータの型変換・必須パラメータの欠落）。
#[derive(Debug)]
pub struct BadRequest(pub String);

impl IntoResponse for BadRequest {
    fn into_response(self) -> Response {
        (StatusCode::BAD_REQUEST, self.0).into_response()
    }
}

/// リクエストパラメータ（クエリ文字列と、application/x-www-form-urlencoded の本体）。
/// `@RequestParam` と `@ModelAttribute` のバインドの元になる。
#[derive(Debug, Default, Clone)]
pub struct RequestParams {
    pairs: Vec<(String, String)>,
}

impl RequestParams {
    /// 最初の値（`request.getParameter(name)`）。
    pub fn get(&self, name: &str) -> Option<&str> {
        self.pairs.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }

    pub fn contains(&self, name: &str) -> bool {
        self.pairs.iter().any(|(k, _)| k == name)
    }

    /// `@RequestParam` の構造体に変換する。
    pub fn parse<T: DeserializeOwned>(&self) -> Result<T, BadRequest> {
        let query = serde_urlencoded::to_string(&self.pairs).map_err(|e| BadRequest(e.to_string()))?;
        serde_urlencoded::from_str(&query).map_err(|e| BadRequest(e.to_string()))
    }
}

impl<S: Send + Sync> FromRequest<S> for RequestParams {
    type Rejection = BadRequest;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        let mut pairs: Vec<(String, String)> = match req.uri().query() {
            Some(q) => serde_urlencoded::from_str(q).map_err(|e| BadRequest(e.to_string()))?,
            None => Vec::new(),
        };
        let form = req
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.starts_with("application/x-www-form-urlencoded"));
        if form {
            let body = String::from_request(req, state).await.map_err(|e| BadRequest(e.to_string()))?;
            let more: Vec<(String, String)> = serde_urlencoded::from_str(&body).map_err(|e| BadRequest(e.to_string()))?;
            pairs.extend(more);
        }
        Ok(Self { pairs })
    }
}

const FLASH_KEY: &str = "j2r.flash";

/// フラッシュ属性（`RedirectAttributes#addFlashAttribute` で入れ、リダイレクト先の次のリクエストで 1 回だけ読む）。
/// 取り出すときにセッションから消す。
pub struct Flash {
    session: Session,
    incoming: HashMap<String, String>,
    outgoing: HashMap<String, String>,
}

impl Flash {
    /// 前のリクエストで入れられたフラッシュ属性。
    pub fn get(&self, name: &str) -> Option<String> {
        self.incoming.get(name).cloned()
    }

    /// 次のリクエストに渡すフラッシュ属性を入れる。
    pub async fn set(&mut self, name: &str, value: String) {
        self.outgoing.insert(name.to_string(), value);
        if let Err(e) = self.session.insert(FLASH_KEY, &self.outgoing).await {
            tracing::error!("failed to store flash attributes: {e}");
        }
    }
}

impl<S: Send + Sync> FromRequestParts<S> for Flash {
    type Rejection = (StatusCode, &'static str);

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let session = Session::from_request_parts(parts, state).await?;
        let incoming = session.remove::<HashMap<String, String>>(FLASH_KEY).await.ok().flatten().unwrap_or_default();
        Ok(Self { session, incoming, outgoing: HashMap::new() })
    }
}

/// `"redirect:/path"`（Spring MVC と同じ 302 Found）。params は `RedirectAttributes#addAttribute` の値。
pub fn redirect(path: &str, params: &[(&str, String)]) -> Response {
    let mut location = path.to_string();
    if !params.is_empty() {
        location.push(if path.contains('?') { '&' } else { '?' });
        location.push_str(&serde_urlencoded::to_string(params).unwrap_or_default());
    }
    let value = HeaderValue::from_str(&location).unwrap_or_else(|_| HeaderValue::from_static("/"));
    (StatusCode::FOUND, [(LOCATION, value)]).into_response()
}

/// フィールドのエラー。
#[derive(Debug, Clone)]
pub struct FieldError {
    pub field: &'static str,
    pub message: String,
    /// 型変換に失敗した値（画面に入力し直させるために表示する）。
    pub rejected: Option<String>,
}

/// `BindingResult`（バインドと検証のエラー）。
#[derive(Debug, Clone, Default)]
pub struct BindingResult {
    errors: Vec<FieldError>,
}

impl BindingResult {
    pub fn has_errors(&self) -> bool {
        !self.errors.is_empty()
    }

    pub fn has_field_errors(&self, field: &str) -> bool {
        self.errors.iter().any(|e| e.field == field)
    }

    /// `#fields.errors('field')`
    pub fn field_errors(&self, field: &str) -> Vec<&str> {
        self.errors.iter().filter(|e| e.field == field).map(|e| e.message.as_str()).collect()
    }

    /// 型変換に失敗した値（`th:field` はフォームの値の代わりにこれを表示する）。
    pub fn rejected_value(&self, field: &str) -> Option<&str> {
        self.errors.iter().find(|e| e.field == field).and_then(|e| e.rejected.as_deref())
    }

    pub fn reject(&mut self, field: &'static str, message: impl Into<String>, rejected: Option<&str>) {
        self.errors.push(FieldError { field, message: message.into(), rejected: rejected.map(str::to_string) });
    }
}

/// 型変換の失敗（Spring の TypeMismatchException）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TypeMismatch;

/// 真偽値への変換（Spring の StringToBooleanConverter）。
pub fn parse_bool(value: &str) -> Result<Option<bool>, TypeMismatch> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" => Ok(None),
        "true" | "on" | "yes" | "1" => Ok(Some(true)),
        "false" | "off" | "no" | "0" => Ok(Some(false)),
        _ => Err(TypeMismatch),
    }
}

/// 数値への変換（空文字列は null）。
pub fn parse_number<T: std::str::FromStr>(value: &str) -> Result<Option<T>, TypeMismatch> {
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    value.parse().map(Some).map_err(|_| TypeMismatch)
}

/// 日付・日時への変換（`@DateTimeFormat`。空文字列は null）。
pub fn parse_date(value: &str, format: &str) -> Result<Option<chrono::NaiveDate>, TypeMismatch> {
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    chrono::NaiveDate::parse_from_str(value, format).map(Some).map_err(|_| TypeMismatch)
}

pub fn parse_date_time(value: &str, format: &str) -> Result<Option<chrono::NaiveDateTime>, TypeMismatch> {
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    chrono::NaiveDateTime::parse_from_str(value, format).map(Some).map_err(|_| TypeMismatch)
}

/// `@NotBlank`
pub fn not_blank(value: Option<&str>) -> bool {
    value.is_some_and(|v| !v.trim().is_empty())
}

/// `@Size` の長さ（Java の String#length と同じ UTF-16 の単位）。
pub fn length(value: &str) -> usize {
    value.encode_utf16().count()
}

/// Thymeleaf の真偽値の評価（`th:if="${x}"`）。null・false・0・"false"・"off"・"no" が偽。
pub trait Truthy {
    fn truthy(&self) -> bool;
}

impl Truthy for bool {
    fn truthy(&self) -> bool {
        *self
    }
}

impl Truthy for str {
    fn truthy(&self) -> bool {
        !matches!(self.to_ascii_lowercase().as_str(), "false" | "off" | "no")
    }
}

impl Truthy for String {
    fn truthy(&self) -> bool {
        self.as_str().truthy()
    }
}

impl<T: Truthy + ?Sized> Truthy for &T {
    fn truthy(&self) -> bool {
        (**self).truthy()
    }
}

impl<T: Truthy> Truthy for Option<T> {
    fn truthy(&self) -> bool {
        self.as_ref().is_some_and(Truthy::truthy)
    }
}

macro_rules! truthy_number {
    ($($t:ty),*) => {
        $(impl Truthy for $t {
            fn truthy(&self) -> bool {
                *self != (0 as $t)
            }
        })*
    };
}

truthy_number!(i8, i16, i32, i64, u16, u64, usize, f32, f64);

/// SpEL の Elvis 演算子（`a ?: b`）は null と空文字列を「値がない」とみなす。
pub trait Present {
    fn present(&self) -> Option<&str>;
}

impl Present for Option<String> {
    fn present(&self) -> Option<&str> {
        self.as_deref().filter(|s| !s.is_empty())
    }
}

impl Present for String {
    fn present(&self) -> Option<&str> {
        Some(self.as_str()).filter(|s| !s.is_empty())
    }
}

/// application.yml の値。Spring の緩いバインド（`spring.datasource.url` → 環境変数 `SPRING_DATASOURCE_URL`）と
/// プレースホルダ `${ENV:default}` の環境変数で上書きできる。
pub fn property(relaxed_env: &str, placeholder_env: Option<&str>, default: &str) -> String {
    std::env::var(relaxed_env)
        .ok()
        .or_else(|| placeholder_env.and_then(|e| std::env::var(e).ok()))
        .unwrap_or_else(|| default.to_string())
}

/// JDBC の URL（`jdbc:postgresql://host:port/db`）を sqlx の URL にする。
pub fn database_url(url: &str, username: &str, password: &str) -> String {
    let Some(rest) = url.strip_prefix("jdbc:postgresql://") else {
        return url.to_string();
    };
    let user: String = serde_urlencoded::to_string([("", username)]).unwrap_or_default().trim_start_matches('=').to_string();
    let pass: String = serde_urlencoded::to_string([("", password)]).unwrap_or_default().trim_start_matches('=').to_string();
    if username.is_empty() {
        format!("postgres://{rest}")
    } else {
        format!("postgres://{user}:{pass}@{rest}")
    }
}
