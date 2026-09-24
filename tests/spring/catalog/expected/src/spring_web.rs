//! Spring MVC の仕組み（リクエストパラメータ・フラッシュ属性・BindingResult・リダイレクト・設定値）に当たる補助
//! （Java2RustTranslator の補助モジュール）。

use std::collections::HashMap;

use axum::extract::{FromRequest, FromRequestParts, Request};
use axum::http::header::{CONTENT_TYPE, LOCATION};
use axum::http::request::Parts;
use axum::http::{HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
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
/// フラッシュ属性を残しておく時間（Spring の FlashMapManager の既定と同じ 180 秒）。
const FLASH_TIMEOUT_SECS: u64 = 180;

/// リダイレクト先に渡すフラッシュ属性（Spring の FlashMap）。リダイレクト先のパスとパラメータが一致するリクエストで取り出す。
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct FlashMap {
    path: String,
    params: Vec<(String, String)>,
    attrs: HashMap<String, String>,
    expires: u64,
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

/// フラッシュ属性（`RedirectAttributes#addFlashAttribute` で入れ、リダイレクト先のリクエストで 1 回だけ読む）。
pub struct Flash {
    session: Session,
    incoming: HashMap<String, String>,
    outgoing: HashMap<String, String>,
}

/// テストで確かめるための、リダイレクトのレスポンスに付けたフラッシュ属性（レスポンスの extensions）。
#[derive(Debug, Clone, Default)]
pub struct FlashAttributes(pub HashMap<String, String>);

impl Flash {
    /// このリクエストに渡されたフラッシュ属性。
    pub fn get(&self, name: &str) -> Option<String> {
        self.incoming.get(name).cloned()
    }

    /// リダイレクト先に渡すフラッシュ属性を入れる。
    pub fn set(&mut self, name: &str, value: String) {
        self.outgoing.insert(name.to_string(), value);
    }

    /// `"redirect:/path"`。フラッシュ属性があれば、リダイレクト先（パスとパラメータ）に渡すものとしてセッションに入れる。
    pub async fn redirect(self, path: &str, params: &[(&str, String)]) -> Response {
        let mut response = redirect(path, params);
        if self.outgoing.is_empty() {
            return response;
        }
        let (target, query) = path.split_once('?').unwrap_or((path, ""));
        let mut target_params: Vec<(String, String)> = serde_urlencoded::from_str(query).unwrap_or_default();
        target_params.extend(params.iter().map(|(k, v)| (k.to_string(), v.clone())));
        let mut maps: Vec<FlashMap> = self.session.get(FLASH_KEY).await.ok().flatten().unwrap_or_default();
        maps.push(FlashMap {
            path: target.to_string(),
            params: target_params,
            attrs: self.outgoing.clone(),
            expires: now_secs() + FLASH_TIMEOUT_SECS,
        });
        if let Err(e) = self.session.insert(FLASH_KEY, &maps).await {
            tracing::error!("failed to store flash attributes: {e}");
        }
        response.extensions_mut().insert(FlashAttributes(self.outgoing));
        response
    }
}

impl<S: Send + Sync> FromRequestParts<S> for Flash {
    type Rejection = (StatusCode, &'static str);

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let session = Session::from_request_parts(parts, state).await?;
        let mut maps: Vec<FlashMap> = session.get(FLASH_KEY).await.ok().flatten().unwrap_or_default();
        let count = maps.len();
        let now = now_secs();
        maps.retain(|m| m.expires > now);
        // パスとパラメータが一致するもののうち、パラメータの多いもの（Spring の FlashMap の順序）
        let path = percent_decode(parts.uri.path());
        let query: Vec<(String, String)> = parts.uri.query().and_then(|q| serde_urlencoded::from_str(q).ok()).unwrap_or_default();
        let found = maps
            .iter()
            .enumerate()
            .filter(|(_, m)| m.path == path && m.params.iter().all(|p| query.contains(p)))
            .max_by_key(|(i, m)| (m.params.len(), std::cmp::Reverse(*i)))
            .map(|(i, _)| i);
        let incoming = found.map(|i| maps.remove(i).attrs).unwrap_or_default();
        if maps.len() != count {
            let result = if maps.is_empty() {
                session.remove::<Vec<FlashMap>>(FLASH_KEY).await.map(|_| ())
            } else {
                session.insert(FLASH_KEY, &maps).await
            };
            if let Err(e) = result {
                tracing::error!("failed to update flash attributes: {e}");
            }
        }
        Ok(Self {
            session,
            incoming,
            outgoing: HashMap::new(),
        })
    }
}

fn percent_decode(path: &str) -> String {
    let bytes = path.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && let Some(Ok(b)) = path.get(i + 1..i + 3).map(|hex| u8::from_str_radix(hex, 16))
        {
            out.push(b);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// ビュー名（テンプレートの名前）。テストで確かめるため、レスポンスの extensions に入れる。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ViewName(pub &'static str);

/// ビュー（askama のテンプレート）をレスポンスにする。
pub fn render(view: &'static str, template: impl IntoResponse) -> Response {
    let mut response = template.into_response();
    response.extensions_mut().insert(ViewName(view));
    response
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

/// enum への変換（Spring の StringToEnumConverterFactory。空文字列は null、それ以外は前後の空白を除いた名前）。
pub fn parse_enum<T>(value: &str, value_of: fn(&str) -> Option<T>) -> Result<Option<T>, TypeMismatch> {
    if value.is_empty() {
        return Ok(None);
    }
    value_of(value.trim()).map(Some).ok_or(TypeMismatch)
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

/// メッセージ（`#{key(${n})}`）の引数の表示。java.text.MessageFormat と同じく、数値は 3 桁ごとに `,` で区切る。
pub trait MessageArg {
    fn message_arg(&self) -> String;
}

macro_rules! message_arg_integer {
    ($($t:ty),*) => {
        $(impl MessageArg for $t {
            fn message_arg(&self) -> String {
                group_digits(&self.to_string())
            }
        })*
    };
}

message_arg_integer!(i8, i16, i32, i64, u16, u64, usize);

impl MessageArg for f64 {
    /// 小数は 3 桁まで（NumberFormat の既定）。
    fn message_arg(&self) -> String {
        let text = format!("{self:.3}");
        let text = text.trim_end_matches('0').trim_end_matches('.');
        match text.split_once('.') {
            Some((int, frac)) => format!("{}.{frac}", group_digits(int)),
            None => group_digits(text),
        }
    }
}

impl MessageArg for f32 {
    fn message_arg(&self) -> String {
        f64::from(*self).message_arg()
    }
}

impl<T: MessageArg + ?Sized> MessageArg for &T {
    fn message_arg(&self) -> String {
        (**self).message_arg()
    }
}

fn group_digits(digits: &str) -> String {
    let (sign, digits) = digits.strip_prefix('-').map_or(("", digits), |d| ("-", d));
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    format!("{sign}{out}")
}

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
