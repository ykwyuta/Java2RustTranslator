# 06. ビルドと使い方

## 1. 必要なもの

| ツール | バージョン | 用途 |
|---|---|---|
| JDK | 21 以上 | トランスパイラ本体のビルドと実行（javac を使うため JRE ではなく JDK） |
| Gradle | 同梱の Wrapper（8.14.3）を使う | ビルド |
| Rust（cargo） | stable | 変換結果のビルド・実行、E2E テスト |

## 2. トランスパイラ自体のビルドとテスト

```sh
./gradlew build                 # 全モジュールのビルド + 単体テスト + ゴールデンテスト + E2E テスト
./gradlew :j2r-cli:installDist  # コマンドライン j2r を translator/j2r-cli/build/install/j2r/ に作る
cargo test --workspace          # ランタイム crate jrt の単体テスト
```

- E2E テスト（`tests/e2e`）は `cargo` が PATH に無ければスキップされる。
- 変換規則を変えたら `./gradlew :j2r-test-harness:test -PupdateGolden=true` でゴールデンファイルを更新する。

## 3. Gradle プラグインで変換する（推奨）

利用する側の Java プロジェクトにプラグイン `io.github.ykwyuta.j2r` を適用すると、次のタスクが追加される。

| タスク | 内容 |
|---|---|
| `translateToRust` | main ソースセットの Java を Rust の Cargo プロジェクトに変換する（既定の出力先: `build/rust`） |
| `cargoCheck` | 変換してから `cargo check` |
| `cargoBuild` | 変換してから `cargo build --release` |
| `cargoRun` | 変換してから `cargo run --quiet` |

```kotlin
// build.gradle.kts
plugins {
    java
    id("io.github.ykwyuta.j2r")
}

repositories {
    mavenCentral()   // 変換器（j2r-cli）の依存ライブラリの取得に使う
}

j2r {
    crateName.set("hello")                   // 既定: プロジェクト名
    mainClass.set("com.example.hello.App")   // 省略時は main メソッドを持つクラスを自動で探す
    // outputDir.set(layout.buildDirectory.dir("rust"))
    // mappingDirs.from("j2r-mappings")      // 独自の API マッピング（YAML）
    // cargoCheck.set(true)                  // 変換後に cargo check も実行する
}
```

プラグインはまだどのリポジトリにも公開していないため、現在は **composite build** でこのリポジトリをソースから使う
（`examples/hello-gradle/settings.gradle.kts` を参照）。

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("path/to/Java2RustTranslator")   // プラグイン本体
}
includeBuild("path/to/Java2RustTranslator")       // プラグインが実行時に使う j2r-cli
```

利用例:

```sh
cd examples/hello-gradle
./gradlew translateToRust cargoRun
# 1 2 Fizz 4 Buzz Fizz 7 8 Fizz Buzz 11 Fizz 13 14 FizzBuzz
# primes below 100: 25
```

仕組み: `translateToRust` は変換器を Gradle デーモンとは **別の JVM** で動かす。変換器のクラスパスは
`j2rTranslator` 構成（既定の依存は `io.github.ykwyuta.j2r:j2r-cli:<プラグインと同じバージョン>`）から解決し、
JDK は Java toolchain（21）から選ぶ。入力（ソース・クラスパス・設定）が変わらなければタスクは UP-TO-DATE になる。

## 4. コマンドライン（j2r）で変換する

```sh
./gradlew :j2r-cli:installDist
translator/j2r-cli/build/install/j2r/bin/j2r -o out -n hello src/main/java
cd out && cargo run
```

| オプション | 内容 |
|---|---|
| `SOURCE...` | Java のソースファイル、またはディレクトリ（配下の *.java をすべて変換） |
| `-o, --output` | 出力先（Cargo プロジェクトのルート）。必須 |
| `-n, --crate-name` | crate 名（既定: translated） |
| `-m, --main-class` | バイナリの入口にするクラス（完全修飾名） |
| `-cp, --classpath` | 入力のコンパイルに必要なクラスパス |
| `--mapping-dir` | 追加の API マッピング定義（YAML）のディレクトリ |
| `--runtime-path` | jrt を同梱せず、指定したディレクトリの jrt を path 依存で参照する |
| `--cargo-check` | 変換後に `cargo check` を実行し、エラーを診断として報告する |
| `--release` | 入力の Java 言語レベル（既定: 21） |
| `@file` | 引数をファイルから読む（ソースが多い場合） |

終了コードは、変換できれば 0（未対応機能の警告があっても 0）、javac のエラーなどで変換できなければ 1。
診断は標準エラーと `<出力先>/j2r-report.json` に出る。

## 5. 現在変換できる範囲（M0〜M1）

| 分類 | 対応している | 未対応（診断を出して `todo!()` を生成する） |
|---|---|---|
| クラス | static メソッド・static フィールドだけを持つクラス（複数クラス・複数パッケージ可） | インスタンスフィールド・コンストラクタ・インスタンスメソッド・継承・インタフェース・enum・record・内部クラス |
| 型 | プリミティブ型、`String`、配列（多次元を含む）、`StringBuilder`、`Scanner` | ボクシング、ジェネリクス、コレクション（`List` / `Map` など）、`null` |
| 演算 | Java と同じ意味の四則演算・剰余・シフト・ビット演算・比較・キャスト（オーバーフロー、ゼロ除算の例外、`MIN / -1` など） | — |
| 文 | if / while / do-while / for / 拡張 for（配列）/ ラベル付き break・continue / switch（`->` 形式、fall-through しない `:` 形式、int・char・String） | fall-through する switch、switch 式、try/catch/finally、ラムダ |
| 例外 | `throw new X()` / `throw new X("msg")`、実行時例外（配列の範囲外、ゼロ除算、数値の解析失敗など）は未捕捉例外として終了（終了コード 1） | 例外の捕捉 |
| JDK API | `System.out` / `System.err` / `System.in`、`Math`、`String`、`StringBuilder`、`Integer` / `Long` / `Double` / `Boolean` / `Character` の static メソッド、`Arrays`、`Scanner`（一覧は `translator/j2r-mappings/src/main/resources/j2r/mappings/*.yaml`） | 上記以外（YAML にマッピングを追加すれば使える） |

意味の違い（診断で知らせる）:
- `String` どうしの `==` は値の比較に変換する（`J2R-LOSSY-STRING-IDENTITY`）。
- 初期化子のない参照型の static フィールドや `new String[n]` の要素は、`null` の代わりに空文字列・長さ 0 の配列になる（`J2R-LOSSY-NULL`）。
- static フィールドの初期化は Java のクラス初期化（まとめて一度）ではなく、フィールドごとに最初のアクセス時に行う。
- 出力は常に UTF-8（Java はプラットフォームの文字コードに従う）。

## 6. 独自の API マッピングを追加する

未対応の JDK API やライブラリの呼び出しは、YAML でマッピングを追加すると変換できる。

```yaml
# my-mappings/java.util.yaml
classes:
  - class: java.util.Objects
    methods:
      - { sig: "hash(java.lang.Object[])", rust: "my_crate::hash({&0})" }
```

プレースホルダ: `{this}`（レシーバ）、`{0}`（引数の値）、`{&0}`（引数への参照）、`{s0}`（文字列化する引数）。
シグネチャは消去後の型の完全修飾名で書く（例: `indexOf(java.lang.String,int)`）。テンプレート全体は
関数呼び出し・メソッド呼び出し・パス、または括弧で囲んだ式にする。

## 7. Maven について

最初は Maven での利用を検討したが、ビルドは Gradle に統一した。Maven から使いたい場合は、同じ `Translator` API を
呼ぶ Maven プラグイン（`j2r-maven-plugin`）を追加するか、`exec-maven-plugin` で j2r-cli を実行すればよい
（どちらも未実装）。
