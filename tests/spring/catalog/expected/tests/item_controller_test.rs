//! Translated from `ItemControllerTest` (ItemControllerTest.java) by Java2RustTranslator.
//!
//! `#[sqlx::test]` がテストごとに空のデータベースを作り、MIGRATOR（schema.sql）を流してから PgPool を渡す（接続先は環境変数 DATABASE_URL。CREATEDB 権限が必要）。

mod mock_mvc;

use std::sync::Arc;

use chrono::NaiveDate;
use sqlx::PgPool;

use catalog::{AppState, Beans, app};
use catalog::clock::{Clock, FixedClock};
use catalog::domain::Category;
use catalog::service::ItemService;
use mock_mvc::{MockMvc, contains_string, content, flash, get, not, post, redirected_url, status, view};

/// `@TestConfiguration`
pub struct FixedClockConfig;

impl FixedClockConfig {
    /// `@Bean`（アプリの `clock` を差し替える）
    fn clock() -> Arc<dyn Clock> {
        Arc::new(
            FixedClock(NaiveDate::from_ymd_opt(2026, 1, 2).unwrap().and_hms_opt(3, 4, 0).unwrap()),
        )
    }
}

pub struct ItemControllerTest {
    mock_mvc: MockMvc,
    item_service: ItemService,
    jdbc_template: PgPool,
}

impl ItemControllerTest {
    /// `@SpringBootTest`: アプリを組み立て、`@BeforeEach` を呼んで、テストのフィクスチャを作る。
    async fn new(pool: PgPool) -> Self {
        let state = AppState::new(
            pool.clone(),
            Beans {
                clock: FixedClockConfig::clock(),
            },
        );
        let test = Self {
            mock_mvc: MockMvc::new(app(state.clone())),
            item_service: state.item_service.clone(),
            jdbc_template: pool,
        };
        test.set_up().await;
        test
    }

    async fn set_up(&self) {
        sqlx::query("DELETE FROM items WHERE price < $1")
            .bind(0)
            .execute(&self.jdbc_template)
            .await
            .unwrap();
    }

    async fn list_shows_items(&self) {
        sqlx::query("INSERT INTO items (name, category, price) VALUES ($1, $2, $3)")
            .bind("Pen")
            .bind("TOOL")
            .bind(120)
            .execute(&self.jdbc_template)
            .await
            .unwrap();
        self.mock_mvc
            .perform(get("/items").param("q", "pe"))
            .await
            .and_expect(status().is_ok())
            .and_expect(view().name("items/list"))
            .and_expect(content().string(contains_string("Pen")))
            .and_expect(content().string(not(contains_string("Book"))));
        assert_eq!(1, self.item_service.search(Some("pe"), &Vec::new()).await.unwrap().len() as i32);
    }

    async fn create_redirects(&self) {
        self.mock_mvc
            .perform(
                post("/items").param("name", "Rice").param("category", "FOOD").param("price", "500"),
            )
            .await
            .and_expect(status().is_3xx_redirection())
            .and_expect(redirected_url("/items"))
            .and_expect(flash().attribute("message", "added"));
        self.mock_mvc
            .perform(post(&format!("/items/{}/discount", 999999)))
            .await
            .and_expect(status().is_not_found());
        assert!(!self.item_service.search(None, &vec![Category::Food]).await.unwrap().is_empty());
    }
}

#[sqlx::test(migrator = "catalog::MIGRATOR")]
async fn list_shows_items(pool: PgPool) {
    ItemControllerTest::new(pool).await.list_shows_items().await;
}

#[sqlx::test(migrator = "catalog::MIGRATOR")]
async fn create_redirects(pool: PgPool) {
    ItemControllerTest::new(pool).await.create_redirects().await;
}
