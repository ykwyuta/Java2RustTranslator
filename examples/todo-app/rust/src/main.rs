//! TodoApplication.java（@SpringBootApplication の main）に相当する。

use todo::{AppConfig, AppState, app};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| "info,todo=debug".into()),
        )
        .init();

    let config = AppConfig::load()?;
    let pool = todo::connect(&config).await?;
    let state = AppState::new(pool);

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", config.port)).await?;
    tracing::info!("listening on http://{}", listener.local_addr()?);
    axum::serve(listener, app(state)).await?;
    Ok(())
}
