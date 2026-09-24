# Todo 管理アプリ（Spring Boot → Rust の移行例）

同じ Todo 管理アプリを、移行元の構成と移行先の構成でそれぞれ実装したもの。
構成の選定理由と変換規則は [docs/07-spring-to-rust.md](../../docs/07-spring-to-rust.md)。

| ディレクトリ | 構成 |
|---|---|
| [spring-boot/](spring-boot) | Spring Boot 4.1 + Spring MVC + MyBatis 3.5 + Thymeleaf + PostgreSQL（Java 21、Gradle） |
| [rust-core/](rust-core) | Mapper・サービス・ドメイン（sqlx 0.9）。**j2r が spring-boot/ から生成**する（手で編集しない） |
| [rust/](rust) | Web 層（axum 0.8 + askama 0.16）とアプリの組み立て。手で変換した。rust-core を path 依存で使う（Rust 1.94 以上） |

機能: 一覧（すべて / 未完了 / 完了の絞り込み、タイトルの検索、件数、期限切れの表示）、追加・編集（入力検証）、
完了 / 未完了の切り替え、削除、完了済みの一括削除、操作後のメッセージ表示（フラッシュ属性）。

| URL | 内容 |
|---|---|
| `GET /todos?filter=all\|active\|completed&q=...` | 一覧 |
| `GET /todos/new`、`POST /todos` | 追加 |
| `GET /todos/{id}/edit`、`POST /todos/{id}` | 編集 |
| `POST /todos/{id}/toggle` | 完了 / 未完了の切り替え |
| `POST /todos/{id}/delete` | 削除 |
| `POST /todos/completed/delete` | 完了済みの一括削除 |

## データベースの準備

```sh
createuser -P todo          # パスワードは todo
createdb -O todo todo
psql -c 'ALTER USER todo CREATEDB'   # Rust 版のテスト（#[sqlx::test]）と compare.sh が DB を作るため
```

表は起動時に作られる（Spring は `schema.sql`、Rust はそれを j2r がコピーした `rust-core/migrations/`。`CREATE TABLE IF NOT EXISTS`）。

## Spring Boot 版

```sh
cd spring-boot
./gradlew bootRun           # http://localhost:8080
./gradlew build             # テストも実行（PostgreSQL が必要）
```

接続先は環境変数 `DATABASE_URL`（JDBC URL）・`DATABASE_USERNAME`・`DATABASE_PASSWORD`、ポートは `PORT` で変えられる。

## Rust 版を生成し直す

```sh
cd spring-boot
./gradlew translateToRust   # j2r --framework spring で ../rust-core を作り直す
```

`build.gradle.kts` の `j2r { framework.set("spring") ... }` で設定している（このリポジトリの変換器を composite build で使う）。
`@Controller` などの Web 層と設定クラスは変換しないので、情報の診断（J2R-SPRING-SKIPPED）が出る。

## Rust 版

```sh
cd rust
cargo run                   # http://localhost:8080
DATABASE_URL=postgres://todo:todo@localhost:5432/todo cargo test
```

接続先は `config/application.toml`、または環境変数 `DATABASE_URL`・`PORT` で変えられる。
ログの出し方は `RUST_LOG`（例: `RUST_LOG=debug`）。

## 2 つの実装を比べる

```sh
./compare.sh    # 両方を空の DB で起動し、同じ 25 リクエストへの応答が一致することを確かめる
```

`PG_ADMIN_URL`（既定: `postgres://todo:todo@localhost:5432/postgres`）の利用者で比較用の DB を作り直す。
