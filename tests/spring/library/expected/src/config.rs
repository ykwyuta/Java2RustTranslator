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
        let url = property("SPRING_DATASOURCE_URL", None, "jdbc:postgresql://localhost:5432/library");
        let username = property("SPRING_DATASOURCE_USERNAME", None, "library");
        let password = property("SPRING_DATASOURCE_PASSWORD", Some("LIBRARY_PASSWORD"), "secret");
        let port = property("SERVER_PORT", None, "9090");
        Self {
            database_url: database_url(&url, &username, &password),
            port: port.parse().expect("server.port must be a port number"),
        }
    }
}
