//! TodoControllerTest.java に相当する結合テスト。
//!
//! テスト対象のアプリ（src/）は j2r が Spring Boot 版から生成したもの。このテストは手で書いたもので、作り直しても残る。
//!
//! #[sqlx::test] がテストごとに空のデータベースを作り、todo::MIGRATOR（schema.sql）を流してから PgPool を渡す。
//! 接続先は環境変数 DATABASE_URL（例: postgres://todo:todo@localhost:5432/todo。CREATEDB 権限が必要）。

use std::sync::Arc;

use axum::Router;
use axum::body::Body;
use axum::http::header::{CONTENT_TYPE, COOKIE, LOCATION, SET_COOKIE};
use axum::http::{Request, StatusCode};
use chrono::NaiveDate;
use http_body_util::BodyExt;
use sqlx::PgPool;
use todo::clock::FixedClock;
use todo::domain::TodoFilter;
use todo::service::TodoService;
use todo::{AppState, Beans, app};
use tower::ServiceExt;

/// テスト用の固定時計（2026-09-24 12:00）。
fn fixed_clock() -> FixedClock {
    FixedClock(
        NaiveDate::from_ymd_opt(2026, 9, 24)
            .unwrap()
            .and_hms_opt(12, 0, 0)
            .unwrap(),
    )
}

/// MockMvc に相当する。セッション Cookie を持ち回ってフラッシュ属性を確かめられるようにする。
struct Client {
    app: Router,
    cookie: Option<String>,
}

struct Page {
    status: StatusCode,
    location: Option<String>,
    body: String,
}

impl Client {
    fn new(pool: PgPool) -> (Self, TodoService) {
        let state = AppState::new(pool, Beans { clock: Arc::new(fixed_clock()) });
        let service = state.todo_service.clone();
        (
            Self {
                app: app(state),
                cookie: None,
            },
            service,
        )
    }

    async fn get(&mut self, uri: &str) -> Page {
        self.send(Request::get(uri), Body::empty()).await
    }

    async fn post(&mut self, uri: &str, form: &[(&str, &str)]) -> Page {
        let body = form
            .iter()
            .map(|(k, v)| format!("{k}={}", urlencode(v)))
            .collect::<Vec<_>>()
            .join("&");
        let request = Request::post(uri).header(CONTENT_TYPE, "application/x-www-form-urlencoded");
        self.send(request, Body::from(body)).await
    }

    async fn send(&mut self, mut request: axum::http::request::Builder, body: Body) -> Page {
        if let Some(cookie) = &self.cookie {
            request = request.header(COOKIE, cookie);
        }
        let response = self
            .app
            .clone()
            .oneshot(request.body(body).unwrap())
            .await
            .unwrap();
        if let Some(set_cookie) = response.headers().get(SET_COOKIE) {
            let pair = set_cookie.to_str().unwrap().split(';').next().unwrap();
            self.cookie = Some(pair.to_string());
        }
        let status = response.status();
        let location = response
            .headers()
            .get(LOCATION)
            .map(|v| v.to_str().unwrap().to_string());
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        Page {
            status,
            location,
            body: String::from_utf8(bytes.to_vec()).unwrap(),
        }
    }
}

fn urlencode(value: &str) -> String {
    value
        .bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn create_and_list(pool: PgPool) {
    let (mut client, _) = Client::new(pool);

    let page = client
        .post(
            "/todos",
            &[
                ("title", "  牛乳を買う "),
                ("description", ""),
                ("dueDate", "2026-10-01"),
            ],
        )
        .await;
    assert_eq!(page.status, StatusCode::FOUND);
    assert_eq!(page.location.as_deref(), Some("/todos"));

    let page = client.get("/todos").await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(
        page.body.contains("「牛乳を買う」を追加しました"),
        "{}",
        page.body
    );
    assert!(page.body.contains("2026/10/01"));

    // フラッシュ属性は 1 回しか表示されない
    let page = client.get("/todos").await;
    assert!(!page.body.contains("を追加しました"));
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn validation_errors_rerender_the_form(pool: PgPool) {
    let (mut client, _) = Client::new(pool);

    let page = client
        .post("/todos", &[("title", " "), ("dueDate", "not-a-date")])
        .await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(page.body.contains("タイトルを入力してください"));
    assert!(
        page.body
            .contains("期限は yyyy-MM-dd 形式で入力してください")
    );
    assert!(page.body.contains(r#"value="not-a-date""#));
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn toggle_and_filter(pool: PgPool) {
    let (mut client, service) = Client::new(pool);
    let done = service.create("完了するもの", None, None).await.unwrap();
    service.create("残すもの", None, None).await.unwrap();

    let page = client
        .post(
            &format!("/todos/{}/toggle", done.id),
            &[("filter", "active")],
        )
        .await;
    assert_eq!(page.location.as_deref(), Some("/todos?filter=active"));

    let page = client.get("/todos?filter=active").await;
    assert!(page.body.contains("残すもの"));
    assert!(!page.body.contains("完了するもの"));
    let page = client.get("/todos?filter=completed").await;
    assert!(page.body.contains("完了するもの"));
    assert!(!page.body.contains("残すもの"));
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn search_by_keyword(pool: PgPool) {
    let (mut client, service) = Client::new(pool);
    service.create("Rust を勉強する", None, None).await.unwrap();
    service.create("掃除", None, None).await.unwrap();

    let page = client.get("/todos?q=rust").await;
    assert!(page.body.contains("Rust を勉強する"));
    assert!(!page.body.contains("掃除"));
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn overdue_todo_is_marked(pool: PgPool) {
    let (mut client, service) = Client::new(pool);
    let yesterday = NaiveDate::from_ymd_opt(2026, 9, 23);
    service
        .create("期限切れのもの", None, yesterday)
        .await
        .unwrap();

    let page = client.get("/todos").await;
    assert!(page.body.contains(r#"class="todo overdue""#));
    assert!(page.body.contains("期限切れ</span>"));
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn update_and_delete(pool: PgPool) {
    let (mut client, service) = Client::new(pool);
    let todo = service.create("旧タイトル", None, None).await.unwrap();

    let page = client
        .post(
            &format!("/todos/{}", todo.id),
            &[("title", "新タイトル"), ("done", "true")],
        )
        .await;
    assert_eq!(page.location.as_deref(), Some("/todos"));
    assert!(
        client
            .get("/todos")
            .await
            .body
            .contains("「新タイトル」を更新しました")
    );
    let updated = service.find_by_id(todo.id).await.unwrap();
    assert_eq!(updated.title, "新タイトル");
    assert!(updated.done);

    client.post("/todos/completed/delete", &[]).await;
    assert!(
        client
            .get("/todos")
            .await
            .body
            .contains("完了済みの 1 件を削除しました")
    );
    assert!(
        service
            .find_all(TodoFilter::All, None)
            .await
            .unwrap()
            .is_empty()
    );
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn unknown_todo_is_404(pool: PgPool) {
    let (mut client, _) = Client::new(pool);

    let page = client.get("/todos/999999/edit").await;
    assert_eq!(page.status, StatusCode::NOT_FOUND);
    assert!(page.body.contains("指定された Todo は見つかりませんでした"));
    assert_eq!(
        client.post("/todos/999999/delete", &[]).await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        client.get("/no-such-page").await.status,
        StatusCode::NOT_FOUND
    );
}
