# Java2RustTranslator

Java のソースコードを Rust のソースコード（Cargo プロジェクト）に変換するトランスパイラ。本体は Java 21 で実装し、
javac の Compiler Tree API で型付きの構文木を得てから、独自の中間表現を経由して Rust を出力する。

- 設計・先行プロジェクトの調査: [docs/](docs/README.md)
- 使い方の詳細・対応範囲: [docs/06-usage.md](docs/06-usage.md)
- Spring Boot の Web アプリを Rust に移すときの標準構成（axum・sqlx・askama）と変換規則: [docs/07-spring-to-rust.md](docs/07-spring-to-rust.md)。
  `--framework spring` で MyBatis の Mapper・`@Service`・ドメインを sqlx の crate に自動変換できる
  （Todo 管理アプリの Spring Boot 版と Rust 版: [examples/todo-app](examples/todo-app)）

現在の対応範囲は、手続き的な Java（プリミティブ型・String・配列・制御構文）に加えて、クラス・継承・インタフェース・
enum・record・sealed・内部 / 匿名 / ローカルクラス、Java と同じクラスの初期化、null とボクシング、ジェネリクスとコレクション、
ストリーム API・Optional、ラムダ・メソッド参照、例外（Rust の `Result` で伝える。try/catch/finally・try-with-resources・
実行時例外の catch）、switch 式・型 / record パターン、`String.format` / `printf`、正規表現、スレッドと
`java.util.concurrent`（決定的なスケジューラで実行）、JDK クラス（Thread・コレクション）の継承、JUnit 5 のテストの変換（`cargo test`）。
詳しくは [docs/06-usage.md §5](docs/06-usage.md#5-現在変換できる範囲)。
Java と同じ実行結果になることを、JVM と `cargo run` の出力を比べる E2E テストで確認している。

## Gradle プラグインで変換する

```kotlin
// build.gradle.kts
plugins {
    java
    id("io.github.ykwyuta.j2r")
}
repositories { mavenCentral() }
j2r {
    crateName.set("hello")
    mainClass.set("com.example.hello.App")
}
```

```sh
./gradlew translateToRust   # build/rust に Cargo プロジェクトを生成
./gradlew cargoRun          # 変換して cargo run
```

プラグインは未公開なので、今は composite build でこのリポジトリを取り込んで使う。例: [examples/hello-gradle](examples/hello-gradle)。

## コマンドラインで変換する

```sh
./gradlew :j2r-cli:installDist
translator/j2r-cli/build/install/j2r/bin/j2r -o out -n hello path/to/src/main/java
cd out && cargo run
```

## 開発

```sh
./gradlew build          # ビルドと全テスト（単体・ゴールデン・E2E・JUnit 変換。E2E と JUnit 変換には cargo が必要）
cargo test --workspace   # ランタイム crate（runtime/jrt）のテスト
```

必要なもの: JDK 21 以上、Rust（stable）。
