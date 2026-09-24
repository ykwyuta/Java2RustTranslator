# 04. ディレクトリ構成

Gradle マルチプロジェクト（Java で書くトランスパイラ本体）と、Cargo ワークスペース（Rust のランタイム crate）を
1 つのリポジトリに置く。モジュールの境界は [02-architecture.md](02-architecture.md) のパイプラインの段に対応させ、
**依存の向きを一方向に保つ**（§2 の図で上から下へのみ依存する）。

`（予定）` と書いた項目はまだ存在しない。いつ作るかは [05-roadmap.md](05-roadmap.md) を参照。

## 1. ツリー

```
Java2RustTranslator/
├── README.md
├── LICENSE
├── settings.gradle.kts              # Gradle サブプロジェクトの定義（translator/*, tests/harness）
├── build.gradle.kts                 # 全 Java モジュール共通の設定（Java 21 toolchain、-Werror、JUnit 5）
├── gradle/
│   ├── libs.versions.toml           # 依存ライブラリのバージョン一元管理（picocli, snakeyaml-engine, junit, assertj）
│   └── wrapper/                     # Gradle Wrapper（8.14.3）
├── gradlew / gradlew.bat
├── Cargo.toml                       # Cargo ワークスペース（members = ["runtime/jrt"]）
├── rust-toolchain.toml              # Rust は stable を使う
│
├── docs/                            # 設計ドキュメント（本書を含む）
│   ├── README.md
│   ├── 01-prior-art.md … 06-usage.md
│   └── adr/                         # Architecture Decision Records（予定）
│
├── translator/                      # ===== トランスパイラ本体（Java 21）=====
│   ├── j2r-common/                  # 診断（Diagnostic）、ソース位置、命名規則の変換（Naming）、TranslatorOptions
│   ├── j2r-jir/                     # JIR（Java IR）の定義: JType / Expr / Stmt / Decl、JirRewriter
│   ├── j2r-frontend/                # javac の実行と、javac の構文木 → JIR の変換（javac への依存はここだけ）
│   ├── j2r-passes/                  # JIR → JIR の正規化パス（DesugarEnhancedFor, DesugarStringConcat, MangleOverloads）
│   ├── j2r-analysis/                # プログラム全体の解析（ProgramIndex、LocalMutability。CHA・null 性などは予定）
│   ├── j2r-rir/                     # RIR（Rust IR）の定義と RustPrinter（優先順位に基づく括弧付け）
│   ├── j2r-mappings/                # JDK API のマッピング規則（YAML リソースのみ）
│   ├── j2r-lowering/                # JIR → RIR の変換（Lowerer）、型の対応（TypeMapper）、マッピング規則の読み込み
│   ├── j2r-backend/                 # Cargo プロジェクトの書き出し、jrt ソースの同梱、cargo の実行
│   ├── j2r-driver/                  # パイプライン全体の実行（ライブラリ API: Translator.translate(options)）
│   ├── j2r-cli/                     # コマンドライン `j2r`（picocli）。installDist で配布物を作る
│   └── j2r-gradle-plugin/           # Gradle プラグイン `io.github.ykwyuta.j2r`（translateToRust / cargoRun など）
│
├── runtime/                         # ===== 生成コードが依存する Rust crate =====
│   └── jrt/
│       ├── Cargo.toml
│       ├── src/
│       │   ├── lib.rs               # prelude, jstr! / jconcat! マクロ
│       │   ├── num.rs               # Java の意味の整数除算・剰余
│       │   ├── array.rs             # JArray<T>（共有・固定長・境界検査）、arraycopy
│       │   ├── rt.rs                # JResult・例外の送出と try、run_main（未捕捉例外の表示と終了コード）、クラスの初期化、exit
│       │   ├── object.rs            # JObject と Object トレイト、JDK クラスを継承したクラスの委譲
│       │   ├── io.rs                # System.out / System.err / System.in
│       │   ├── junit.rs             # JUnit の Assertions / Assumptions、変換したテストの実行
│       │   ├── lang/                # JString, StringBuilder, Math, ボックス型, Character, enum, 例外, 書式, 正規表現,
│       │   │                        # スレッドと java.util.concurrent（thread.rs: 決定的なスケジューラ）
│       │   └── util/                # コレクション, ストリーム, Optional, 関数合成, Arrays / Collections, Scanner
│       └── tests/semantics.rs       # Java の意味論の単体テスト
│
├── tests/                           # ===== 変換全体のテスト =====
│   ├── harness/                     # テストハーネス（Gradle サブプロジェクト :j2r-test-harness）
│   ├── e2e/<カテゴリ>/<ケース>/     # 差分実行テスト: src/**/*.java（+ 任意の stdin.txt）
│   ├── junit/<カテゴリ>/<ケース>/   # JUnit テストの変換: src/**/*.java（JUnit と cargo test の結果を比べる）
│   ├── golden/<カテゴリ>/<ケース>/  # ゴールデンテスト: src/**/*.java + expected/（生成される src/ の中身）
│   └── corpus/                      # 実プロジェクトの回帰コーパス（予定）
│
├── examples/
│   └── hello-gradle/                # Gradle プラグインの利用例（./gradlew translateToRust cargoRun）
│
└── .github/workflows/ci.yml         # cargo test + gradle build（単体・ゴールデン・E2E）+ 利用例の実行
```

## 2. モジュールの依存関係

```
j2r-cli ─▶ j2r-driver ─┬─▶ j2r-backend ───▶ j2r-rir ─▶ j2r-common
                       ├─▶ j2r-lowering ─┬─▶ j2r-rir
                       │                 ├─▶ j2r-jir ─▶ j2r-common
                       │                 ├─▶ j2r-analysis ─▶ j2r-jir
                       │                 └─▶ j2r-mappings（YAML リソース）
                       ├─▶ j2r-passes ───▶ j2r-jir
                       └─▶ j2r-frontend ─▶ j2r-jir

j2r-gradle-plugin（コンパイル時の依存なし。実行時に j2r-cli を別 JVM で起動する）
j2r-test-harness ─▶ j2r-driver, j2r-backend
```

ルール:
- javac（`jdk.compiler`）を参照してよいのは **`j2r-frontend` だけ**。後段は JIR だけを見る。
- `j2r-jir` と `j2r-rir` は互いに依存しない。両方を知っているのは `j2r-lowering` だけ。
- `j2r-analysis` は JIR を読むだけで書き換えない。
- モジュール間の依存は Gradle の `implementation` / `api` で宣言したものに限られるため、宣言していない方向の依存は
  コンパイルエラーになる。
- Gradle プラグインは変換器を **別 JVM**（`j2rTranslator` 構成で解決した j2r-cli）で動かす。Gradle デーモンに
  javac や変換器のクラスを読み込ませないため。

## 3. Java パッケージ名

ベースパッケージは `io.github.ykwyuta.j2r`。

| モジュール | パッケージ | 主なクラス |
|---|---|---|
| j2r-common | `…j2r.common` | `Diagnostic`, `DiagnosticCode`, `Diagnostics`, `SourcePos`, `Naming`, `TranslatorOptions` |
| j2r-jir | `…j2r.jir` | `JType`, `Expr`, `Stmt`, `Decl`, `MethodRef`, `JirRewriter` |
| j2r-frontend | `…j2r.frontend` | `JavacFrontend`, `JirBuilder` |
| j2r-passes | `…j2r.passes` | `Pass`, `PassManager`, `DesugarEnhancedFor`, `DesugarStringConcat`, `MangleOverloads` |
| j2r-analysis | `…j2r.analysis` | `ProgramIndex`, `LocalMutability` |
| j2r-rir | `…j2r.rir` | `RItem`, `RStmt`, `RExpr`, `RType`, `RFile`, `RustPrinter` |
| j2r-lowering | `…j2r.lowering` | `Lowerer`, `TypeMapper`, `ApiMappings`, `LoweredCrate` |
| j2r-backend | `…j2r.backend` | `CargoProjectWriter`, `CargoRunner` |
| j2r-driver | `…j2r.driver` | `Translator` |
| j2r-cli | `…j2r.cli` | `Main` |
| j2r-gradle-plugin | `…j2r.gradle` | `J2rPlugin`, `J2rExtension`, `TranslateToRustTask` |

## 4. モジュール内の標準レイアウト

```
translator/j2r-xxx/
├── build.gradle.kts
└── src/
    ├── main/java/io/github/ykwyuta/j2r/xxx/...
    ├── main/resources/            # j2r-mappings: j2r/mappings/*.yaml
    └── test/java/...              # 単体テスト（JUnit 5 + AssertJ）
```

生成されるリソース:
- `j2r-backend` のビルドは `runtime/jrt` のソースをリソース `j2r/runtime/jrt/` に同梱する（`bundleJrt` タスク）。
  変換時に出力先へコピー（vendor）するため、変換結果は単体で `cargo build` できる。
- `j2r-gradle-plugin` のビルドは、プラグインが既定で使う j2r-cli のバージョンを `j2r-plugin.properties` に埋め込む。

## 5. テストケースの置き方

### 差分実行テスト（`tests/e2e/<カテゴリ>/<ケース>/`）
```
tests/e2e/io/scanner_sum/
├── src/ScannerSum.java     # main を持つクラスを 1 つ含む
└── stdin.txt               # 任意: 標準入力
```
ハーネスは `javac && java` の結果と、変換 → `cargo build` → 実行した結果の **標準出力と終了コード** を比べる。
期待値ファイルは置かない（JVM の結果を正とする）。生成コードで `cargo build` が警告を出した場合も失敗にする。

### JUnit テストの変換（`tests/junit/<カテゴリ>/<ケース>/`）
```
tests/junit/basics/calculator/
└── src/calc/{Calculator.java, CalculatorTest.java}
```
ハーネスはテストクラスを JUnit Platform（JVM）で実行した結果と、変換 → `cargo test` した結果を、
テストごとの **成功 / 失敗 / 無効（ignored）** で比べる。意図的に失敗するテストも置いてよい。

### ゴールデンテスト（`tests/golden/<カテゴリ>/<ケース>/`）
```
tests/golden/control/loops/
├── src/Loops.java
└── expected/               # 生成される src/ 配下の .rs（lib.rs, bin/main.rs, クラスごとのファイル）
```
変換規則を変えたら `./gradlew :j2r-test-harness:test -PupdateGolden=true` で `expected/` を作り直し、
差分をレビューしてからコミットする。

## 6. 変換結果（出力）の構成

入力 `src/main/java/com/example/hello/App.java` を crate 名 `hello` で変換した場合:
```
<出力先>/                     # Gradle プラグインの既定は build/rust
├── Cargo.toml                # [workspace] を持つ独立したプロジェクト。jrt は path 依存
├── jrt/                      # 同梱したランタイム crate（--runtime-path を指定した場合は作らない）
├── src/
│   ├── lib.rs                # pub mod com; と、生成コード向けの #![allow(...)]
│   ├── bin/main.rs           # fn main() { jrt::run_main(|args| hello::com::example::hello::app::main(args)); }
│   └── com/
│       ├── mod.rs            # pub mod example;
│       └── example/
│           ├── mod.rs
│           └── hello/
│               ├── mod.rs
│               └── app.rs    # クラス App（関数・定数・thread_local の static）
└── j2r-report.json           # 診断（未対応機能・意味が変わりうる変換）の一覧
```
- クラス名が `Main` でも `src/main.rs` がバイナリと誤認されないよう、`Cargo.toml` では `autobins = false` とし、
  バイナリは `src/bin/main.rs` に置く。
- パッケージと同名のクラスがあるとモジュール名が衝突する（診断 `J2R-NAME-COLLISION`）。
