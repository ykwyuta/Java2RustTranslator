//! application.yml の設定（Spring の緩いバインドと ${ENV:default} の環境変数で上書きできる）。 Translated by Java2RustTranslator.

use crate::spring_web::{database_url, property};

/// application.yml のうち、アプリの起動に使う値。
#[derive(Debug, Clone)]
pub struct AppConfig {
    pub database_url: String,
    pub port: u16,
}

impl AppConfig {
    pub fn load() -> Self {
        let url = property("SPRING_DATASOURCE_URL", Some("DATABASE_URL"), "jdbc:postgresql://localhost:5432/catalog");
        let username = property("SPRING_DATASOURCE_USERNAME", None, "catalog");
        let password = property("SPRING_DATASOURCE_PASSWORD", None, "catalog");
        let port = property("SERVER_PORT", None, "8080");
        Self {
            database_url: database_url(&url, &username, &password),
            port: port.parse().expect("server.port must be a port number"),
        }
    }
}
