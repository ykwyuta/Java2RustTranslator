//! Spring の MockMvc に当たるテストの補助（Java2RustTranslator の補助モジュール）。
//!
//! Router をサーバなしで呼び（`tower::ServiceExt::oneshot`）、`andExpect` の検査を同じ名前の関数で書けるようにする。
//! MockMvc と同じく、リクエストごとに新しいセッションで呼ぶ（Cookie は持ち回らない）。
#![allow(dead_code)]

use std::collections::HashMap;
use std::fmt;

use axum::Router;
use axum::body::Body;
use axum::http::header::{ACCEPT, CONTENT_TYPE, LOCATION};
use axum::http::{HeaderMap, Method, Request, StatusCode};
use http_body_util::BodyExt;
use tower::ServiceExt;

use todo::spring_web::{FlashAttributes, ViewName};

/// `MockMvc`
pub struct MockMvc {
    app: Router,
}

impl MockMvc {
    pub fn new(app: Router) -> Self {
        Self { app }
    }

    /// `mockMvc.perform(request)`
    pub async fn perform(&self, request: MockRequest) -> ResultActions {
        let mut uri = request.uri.clone();
        let form = serde_urlencoded::to_string(&request.params).expect("request parameters");
        let mut builder = Request::builder().method(request.method.clone());
        let body = if let Some(content) = request.content {
            Body::from(content)
        } else if request.method == Method::GET || request.method == Method::DELETE || request.params.is_empty() {
            if !form.is_empty() {
                uri.push(if uri.contains('?') { '&' } else { '?' });
                uri.push_str(&form);
            }
            Body::empty()
        } else {
            builder = builder.header(CONTENT_TYPE, "application/x-www-form-urlencoded");
            Body::from(form)
        };
        for (name, value) in &request.headers {
            builder = builder.header(name.as_str(), value.as_str());
        }
        let response = self
            .app
            .clone()
            .oneshot(builder.uri(uri).body(body).expect("request"))
            .await
            .expect("response");
        let status = response.status();
        let headers = response.headers().clone();
        let view = response.extensions().get::<ViewName>().map(|v| v.0);
        let flash = response.extensions().get::<FlashAttributes>().map(|f| f.0.clone()).unwrap_or_default();
        let bytes = response.into_body().collect().await.expect("response body").to_bytes();
        ResultActions {
            status,
            headers,
            body: String::from_utf8_lossy(&bytes).into_owned(),
            view,
            flash,
        }
    }
}

/// `MockMvcRequestBuilders.get(...)` などで作るリクエスト。
pub struct MockRequest {
    method: Method,
    uri: String,
    params: Vec<(String, String)>,
    headers: Vec<(String, String)>,
    content: Option<String>,
}

fn request(method: Method, uri: &str) -> MockRequest {
    MockRequest {
        method,
        uri: uri.to_string(),
        params: Vec::new(),
        headers: Vec::new(),
        content: None,
    }
}

pub fn get(uri: &str) -> MockRequest {
    request(Method::GET, uri)
}

pub fn post(uri: &str) -> MockRequest {
    request(Method::POST, uri)
}

pub fn put(uri: &str) -> MockRequest {
    request(Method::PUT, uri)
}

pub fn patch(uri: &str) -> MockRequest {
    request(Method::PATCH, uri)
}

pub fn delete(uri: &str) -> MockRequest {
    request(Method::DELETE, uri)
}

impl MockRequest {
    /// リクエストパラメータ（GET はクエリ文字列、POST などはフォームの本体）。
    pub fn param(mut self, name: &str, value: impl ToString) -> Self {
        self.params.push((name.to_string(), value.to_string()));
        self
    }

    pub fn header(mut self, name: &str, value: impl ToString) -> Self {
        self.headers.push((name.to_string(), value.to_string()));
        self
    }

    pub fn accept(self, media_type: &str) -> Self {
        self.header(ACCEPT.as_str(), media_type)
    }

    pub fn content_type(self, media_type: &str) -> Self {
        self.header(CONTENT_TYPE.as_str(), media_type)
    }

    pub fn content(mut self, body: impl ToString) -> Self {
        self.content = Some(body.to_string());
        self
    }
}

/// `perform` の結果（`ResultActions` と `MvcResult`）。
pub struct ResultActions {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: String,
    /// 描画したテンプレートの名前（Spring のビュー名）
    pub view: Option<&'static str>,
    /// リダイレクト先に渡すフラッシュ属性
    pub flash: HashMap<String, String>,
}

impl ResultActions {
    /// `andExpect(matcher)`: 満たさなければ panic する。
    pub fn and_expect(&self, matcher: ResultMatcher) -> &Self {
        (matcher.0)(self);
        self
    }

    /// `andReturn().getResponse().getContentAsString()`
    pub fn content_as_string(&self) -> &str {
        &self.body
    }
}

/// `ResultMatcher`
pub struct ResultMatcher(Box<dyn Fn(&ResultActions)>);

impl ResultMatcher {
    fn new(f: impl Fn(&ResultActions) + 'static) -> Self {
        Self(Box::new(f))
    }
}

/// `status()`
pub fn status() -> StatusMatchers {
    StatusMatchers
}

pub struct StatusMatchers;

fn status_is(expected: u16) -> ResultMatcher {
    ResultMatcher::new(move |r| assert_eq!(r.status.as_u16(), expected, "Status"))
}

fn status_class(class: u16, name: &'static str) -> ResultMatcher {
    ResultMatcher::new(move |r| {
        assert!(
            r.status.as_u16() / 100 == class,
            "Range for response status value {} expected:<{name}> but was:<{}>",
            r.status.as_u16(),
            r.status.as_u16() / 100
        )
    })
}

impl StatusMatchers {
    pub fn is(&self, status: i32) -> ResultMatcher {
        status_is(u16::try_from(status).expect("HTTP status"))
    }

    pub fn is_ok(&self) -> ResultMatcher {
        status_is(200)
    }

    pub fn is_created(&self) -> ResultMatcher {
        status_is(201)
    }

    pub fn is_no_content(&self) -> ResultMatcher {
        status_is(204)
    }

    pub fn is_found(&self) -> ResultMatcher {
        status_is(302)
    }

    pub fn is_bad_request(&self) -> ResultMatcher {
        status_is(400)
    }

    pub fn is_unauthorized(&self) -> ResultMatcher {
        status_is(401)
    }

    pub fn is_forbidden(&self) -> ResultMatcher {
        status_is(403)
    }

    pub fn is_not_found(&self) -> ResultMatcher {
        status_is(404)
    }

    pub fn is_method_not_allowed(&self) -> ResultMatcher {
        status_is(405)
    }

    pub fn is_internal_server_error(&self) -> ResultMatcher {
        status_is(500)
    }

    pub fn is_2xx_successful(&self) -> ResultMatcher {
        status_class(2, "SUCCESSFUL")
    }

    pub fn is_3xx_redirection(&self) -> ResultMatcher {
        status_class(3, "REDIRECTION")
    }

    pub fn is_4xx_client_error(&self) -> ResultMatcher {
        status_class(4, "CLIENT_ERROR")
    }

    pub fn is_5xx_server_error(&self) -> ResultMatcher {
        status_class(5, "SERVER_ERROR")
    }
}

/// `redirectedUrl(url)`
pub fn redirected_url(url: &str) -> ResultMatcher {
    let expected = url.to_string();
    ResultMatcher::new(move |r| {
        let actual = r.headers.get(LOCATION).and_then(|v| v.to_str().ok());
        assert!(r.status.is_redirection(), "Redirected URL: not a redirect (status {})", r.status);
        assert_eq!(actual, Some(expected.as_str()), "Redirected URL");
    })
}

/// `content()`
pub fn content() -> ContentMatchers {
    ContentMatchers
}

pub struct ContentMatchers;

impl ContentMatchers {
    /// `content().string(expected)` / `content().string(containsString(...))`
    pub fn string(&self, matcher: impl Into<StringMatcher>) -> ResultMatcher {
        let matcher = matcher.into();
        ResultMatcher::new(move |r| {
            assert!(matcher.matches(&r.body), "Response content\nExpected: {matcher}\n     but: was \"{}\"", r.body)
        })
    }

    /// `content().contentType(...)`（パラメータを除いた型で比べる）
    pub fn content_type(&self, media_type: &str) -> ResultMatcher {
        let expected = media_type.to_string();
        ResultMatcher::new(move |r| {
            let actual = r.headers.get(CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("");
            assert_eq!(actual.split(';').next().map(str::trim), Some(expected.split(';').next().unwrap_or("").trim()), "Content type");
        })
    }
}

/// `view()`
pub fn view() -> ViewMatchers {
    ViewMatchers
}

pub struct ViewMatchers;

impl ViewMatchers {
    pub fn name(&self, name: &str) -> ResultMatcher {
        let expected = name.to_string();
        ResultMatcher::new(move |r| assert_eq!(r.view, Some(expected.as_str()), "View name"))
    }
}

/// `flash()`
pub fn flash() -> FlashMatchers {
    FlashMatchers
}

pub struct FlashMatchers;

impl FlashMatchers {
    pub fn attribute(&self, name: &str, value: impl ToString) -> ResultMatcher {
        let name = name.to_string();
        let expected = value.to_string();
        ResultMatcher::new(move |r| assert_eq!(r.flash.get(&name), Some(&expected), "Flash attribute '{name}'"))
    }

    pub fn attribute_exists(&self, name: &str) -> ResultMatcher {
        let name = name.to_string();
        ResultMatcher::new(move |r| assert!(r.flash.contains_key(&name), "Flash attribute '{name}' does not exist"))
    }

    pub fn attribute_count(&self, count: i32) -> ResultMatcher {
        ResultMatcher::new(move |r| assert_eq!(r.flash.len() as i32, count, "FlashMap size"))
    }
}

/// `header()`
pub fn header() -> HeaderMatchers {
    HeaderMatchers
}

pub struct HeaderMatchers;

impl HeaderMatchers {
    pub fn string(&self, name: &str, value: impl Into<StringMatcher>) -> ResultMatcher {
        let name = name.to_string();
        let matcher = value.into();
        ResultMatcher::new(move |r| {
            let actual = r.headers.get(name.as_str()).and_then(|v| v.to_str().ok()).unwrap_or("");
            assert!(matcher.matches(actual), "Response header '{name}'\nExpected: {matcher}\n     but: was \"{actual}\"")
        })
    }

    pub fn exists(&self, name: &str) -> ResultMatcher {
        let name = name.to_string();
        ResultMatcher::new(move |r| assert!(r.headers.contains_key(name.as_str()), "Response should contain header '{name}'"))
    }
}

/// Hamcrest の文字列の Matcher（`containsString`・`not`・`equalTo`）。
pub enum StringMatcher {
    Equal(String),
    Contains(String),
    Not(Box<StringMatcher>),
}

impl StringMatcher {
    pub fn matches(&self, actual: &str) -> bool {
        match self {
            Self::Equal(s) => actual == s,
            Self::Contains(s) => actual.contains(s.as_str()),
            Self::Not(m) => !m.matches(actual),
        }
    }
}

impl fmt::Display for StringMatcher {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Equal(s) => write!(f, "\"{s}\""),
            Self::Contains(s) => write!(f, "a string containing \"{s}\""),
            Self::Not(m) => write!(f, "not {m}"),
        }
    }
}

impl From<&str> for StringMatcher {
    fn from(s: &str) -> Self {
        Self::Equal(s.to_string())
    }
}

impl From<String> for StringMatcher {
    fn from(s: String) -> Self {
        Self::Equal(s)
    }
}

pub fn contains_string(s: impl Into<String>) -> StringMatcher {
    StringMatcher::Contains(s.into())
}

pub fn equal_to(s: impl Into<String>) -> StringMatcher {
    StringMatcher::Equal(s.into())
}

pub fn not(matcher: impl Into<StringMatcher>) -> StringMatcher {
    StringMatcher::Not(Box::new(matcher.into()))
}
