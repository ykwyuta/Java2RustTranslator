//! Translated from `LibraryApplication` (@SpringBootApplication) by Java2RustTranslator.

use library::config::AppConfig;
use library::{AppState, Beans, app, connect};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();
    let config = AppConfig::load();
    let pool = connect(&config).await?;
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", config.port)).await?;
    tracing::info!("listening on http://{}", listener.local_addr()?);
    axum::serve(listener, app(AppState::new(pool, Beans::new()))).await?;
    Ok(())
}
