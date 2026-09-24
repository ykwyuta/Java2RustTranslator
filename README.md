# Java2RustTranslator

Java のソースコードを Rust のソースコード（Cargo プロジェクト）に変換するトランスパイラ。本体は Java 21 で実装し、
javac の Compiler Tree API で型付きの構文木を得てから、独自の中間表現を経由して Rust を出力する。

- 設計・先行プロジェクトの調査: [docs/](docs/README.md)
- 使い方の詳細・対応範囲: [docs/06-usage.md](docs/06-usage.md)

現在の対応範囲は、手続き的な Java（プリミティブ型・String・配列・制御構文）に加えて、クラス・継承・インタフェース・
enum・record・内部 / 匿名 / ローカルクラス、null とボクシング、ジェネリクスと主なコレクション、ラムダ・メソッド参照、
例外（Rust の `Result` で伝える。try/catch/finally・try-with-resources・実行時例外の catch）、switch 式・パターン、`String.format` / `printf` と可変長引数。
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
./gradlew build          # ビルドと全テスト（単体・ゴールデン・E2E。E2E には cargo が必要）
cargo test --workspace   # ランタイム crate（runtime/jrt）のテスト
```

必要なもの: JDK 21 以上、Rust（stable）。
