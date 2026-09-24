//! Translated from `TodoControllerTest` (TodoControllerTest.java) by Java2RustTranslator.
//!
//! `#[sqlx::test]` がテストごとに空のデータベースを作り、MIGRATOR（schema.sql）を流してから PgPool を渡す（接続先は環境変数 DATABASE_URL。CREATEDB 権限が必要）。

mod mock_mvc;

use std::sync::Arc;

use chrono::NaiveDate;
use sqlx::PgPool;

use todo::{AppState, Beans, app};
use todo::clock::{Clock, FixedClock};
use todo::domain::{Priority, TodoFilter};
use todo::service::TodoService;
use mock_mvc::{MockMvc, contains_string, content, flash, get, not, post, redirected_url, status, view};

/// テスト用の固定時計（2026-09-24 12:00）。
///
/// `@TestConfiguration`
pub struct FixedClockConfig;

impl FixedClockConfig {
    /// `@Bean`（アプリの `clock` を差し替える）
    fn fixed_clock() -> Arc<dyn Clock> {
        Arc::new(
            FixedClock(NaiveDate::from_ymd_opt(2026, 9, 24).unwrap().and_hms_opt(12, 0, 0).unwrap()),
        )
    }
}

/// PostgreSQL（application.yml の接続先）に対して動かす結合テスト。
pub struct TodoControllerTest {
    mock_mvc: MockMvc,
    todo_service: TodoService,
    jdbc_template: PgPool,
}

impl TodoControllerTest {
    /// `@SpringBootTest`: アプリを組み立て、`@BeforeEach` を呼んで、テストのフィクスチャを作る。
    async fn new(pool: PgPool) -> Self {
        let state = AppState::new(
            pool.clone(),
            Beans {
                clock: FixedClockConfig::fixed_clock(),
            },
        );
        let test = Self {
            mock_mvc: MockMvc::new(app(state.clone())),
            todo_service: state.todo_service.clone(),
            jdbc_template: pool,
        };
        test.clean_up().await;
        test
    }

    async fn clean_up(&self) {
        sqlx::query("DELETE FROM activities").execute(&self.jdbc_template).await.unwrap();
        sqlx::query("DELETE FROM todos").execute(&self.jdbc_template).await.unwrap();
    }

    /// 一覧の Todo のタイトルの表示（最近の操作の一覧と区別する）。
    fn title(title: &str) -> String {
        format!("<span class=\"title\">{title}</span>")
    }

    async fn create_and_list(&self) {
        self.mock_mvc
            .perform(
                post("/todos")
                    .param("title", "  牛乳を買う ")
                    .param("description", "")
                    .param("dueDate", "2026-10-01"),
            )
            .await
            .and_expect(status().is_3xx_redirection())
            .and_expect(redirected_url("/todos"))
            .and_expect(flash().attribute("message", "「牛乳を買う」を追加しました"));
        self.mock_mvc
            .perform(get("/todos"))
            .await
            .and_expect(status().is_ok())
            .and_expect(content().string(contains_string("牛乳を買う")))
            .and_expect(content().string(contains_string("2026/10/01")));
    }

    async fn validation_errors_rerender_the_form(&self) {
        self.mock_mvc
            .perform(post("/todos").param("title", " ").param("dueDate", "not-a-date"))
            .await
            .and_expect(status().is_ok())
            .and_expect(view().name("todos/form"))
            .and_expect(content().string(contains_string("タイトルを入力してください")))
            .and_expect(content().string(contains_string("期限は yyyy-MM-dd 形式で入力してください")));
    }

    async fn toggle_and_filter(&self) {
        let done = self.todo_service.create("完了するもの", None, None, Priority::Medium).await.unwrap();
        self.todo_service.create("残すもの", None, None, Priority::Medium).await.unwrap();
        self.mock_mvc
            .perform(post(&format!("/todos/{}/toggle", done.id)).param("filter", "active"))
            .await
            .and_expect(redirected_url("/todos?filter=active"));
        self.mock_mvc
            .perform(get("/todos").param("filter", "active"))
            .await
            .and_expect(content().string(contains_string(Self::title("残すもの"))))
            .and_expect(content().string(not(contains_string(Self::title("完了するもの")))));
        self.mock_mvc
            .perform(get("/todos").param("filter", "completed"))
            .await
            .and_expect(content().string(contains_string(Self::title("完了するもの"))))
            .and_expect(content().string(not(contains_string(Self::title("残すもの")))));
    }

    async fn search_by_keyword(&self) {
        self.todo_service.create("Rust を勉強する", None, None, Priority::Medium).await.unwrap();
        self.todo_service.create("掃除", None, None, Priority::Medium).await.unwrap();
        self.mock_mvc
            .perform(get("/todos").param("q", "rust"))
            .await
            .and_expect(content().string(contains_string(Self::title("Rust を勉強する"))))
            .and_expect(content().string(not(contains_string(Self::title("掃除")))));
    }

    async fn overdue_todo_is_marked(&self) {
        self.todo_service
            .create(
                "期限切れのもの",
                None,
                Some(NaiveDate::from_ymd_opt(2026, 9, 23).unwrap()),
                Priority::Medium,
            )
            .await
            .unwrap();
        self.mock_mvc
            .perform(get("/todos"))
            .await
            .and_expect(content().string(contains_string("class=\"todo overdue\"")))
            .and_expect(content().string(contains_string("期限切れ</span>")));
    }

    async fn update_and_delete(&self) {
        let todo = self.todo_service.create("旧タイトル", None, None, Priority::Medium).await.unwrap();
        self.mock_mvc
            .perform(
                post(&format!("/todos/{}", todo.id)).param("title", "新タイトル").param("done", "true"),
            )
            .await
            .and_expect(redirected_url("/todos"))
            .and_expect(flash().attribute("message", "「新タイトル」を更新しました"));
        let updated = self.todo_service.find_by_id(todo.id).await.unwrap();
        assert_eq!("新タイトル", updated.title);
        assert!(updated.done);
        self.mock_mvc
            .perform(post("/todos/completed/delete"))
            .await
            .and_expect(flash().attribute("message", "完了済みの 1 件を削除しました"));
        assert!(self.todo_service.find_all(TodoFilter::All, None).await.unwrap().is_empty());
        self.mock_mvc
            .perform(get("/todos"))
            .await
            .and_expect(content().string(contains_string("削除: 新タイトル")))
            .and_expect(content().string(contains_string("更新: 新タイトル")));
    }

    async fn priority_and_activities(&self) {
        self.mock_mvc
            .perform(get("/todos/new"))
            .await
            .and_expect(status().is_ok())
            .and_expect(view().name("todos/form"))
            .and_expect(
                content().string(
                    contains_string("<option value=\"MEDIUM\" selected=\"selected\">中</option>"),
                ),
            );
        self.mock_mvc
            .perform(post("/todos").param("title", "急ぎの用事").param("priority", "HIGH"))
            .await
            .and_expect(redirected_url("/todos"));
        self.mock_mvc
            .perform(post("/todos").param("title", "優先度なし").param("priority", "URGENT"))
            .await
            .and_expect(status().is_ok())
            .and_expect(content().string(contains_string("優先度が正しくありません")));
        self.mock_mvc
            .perform(get("/todos"))
            .await
            .and_expect(content().string(contains_string("class=\"priority priority-high\"")))
            .and_expect(content().string(contains_string("追加: 急ぎの用事")))
            .and_expect(content().string(contains_string("全 1 件（うち完了 0 件）")));
    }

    async fn unknown_todo_is404(&self) {
        self.mock_mvc
            .perform(get(&format!("/todos/{}/edit", 999999)))
            .await
            .and_expect(status().is_not_found())
            .and_expect(content().string(contains_string("指定された Todo は見つかりませんでした")));
        self.mock_mvc
            .perform(post(&format!("/todos/{}/delete", 999999)))
            .await
            .and_expect(status().is_not_found());
        self.mock_mvc
            .perform(get("/no-such-page").accept("text/html"))
            .await
            .and_expect(status().is_not_found());
    }
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn create_and_list(pool: PgPool) {
    TodoControllerTest::new(pool).await.create_and_list().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn validation_errors_rerender_the_form(pool: PgPool) {
    TodoControllerTest::new(pool).await.validation_errors_rerender_the_form().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn toggle_and_filter(pool: PgPool) {
    TodoControllerTest::new(pool).await.toggle_and_filter().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn search_by_keyword(pool: PgPool) {
    TodoControllerTest::new(pool).await.search_by_keyword().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn overdue_todo_is_marked(pool: PgPool) {
    TodoControllerTest::new(pool).await.overdue_todo_is_marked().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn update_and_delete(pool: PgPool) {
    TodoControllerTest::new(pool).await.update_and_delete().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn priority_and_activities(pool: PgPool) {
    TodoControllerTest::new(pool).await.priority_and_activities().await;
}

#[sqlx::test(migrator = "todo::MIGRATOR")]
async fn unknown_todo_is404(pool: PgPool) {
    TodoControllerTest::new(pool).await.unknown_todo_is404().await;
}
