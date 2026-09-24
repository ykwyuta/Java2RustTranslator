# 04. ディレクトリ構成

Gradle マルチプロジェクト（Java 実装のトランスパイラ本体）と、Cargo ワークスペース（Rust ランタイム crate）を
1 リポジトリに同居させる。モジュール境界は [02-architecture.md](02-architecture.md) のパイプライン段に対応させ、
**依存方向を一方向に保つ**（下図の上から下へのみ依存）。

## 1. ツリー

```
Java2RustTranslator/
├── README.md
├── LICENSE
├── settings.gradle.kts              # Gradle サブプロジェクト定義
├── build.gradle.kts                 # 共通設定（Java 21 toolchain、テスト、フォーマッタ）
├── gradle/
│   ├── libs.versions.toml           # 依存バージョン一元管理（picocli, junit, assertj, snakeyaml-engine）
│   └── wrapper/
├── gradlew / gradlew.bat
├── Cargo.toml                       # Cargo ワークスペース（members = ["runtime/*"]）
├── rust-toolchain.toml              # Rust バージョン固定（stable）
│
├── docs/                            # 設計ドキュメント（本書を含む）
│   ├── README.md
│   ├── 01-prior-art.md
│   ├── 02-architecture.md
│   ├── 03-translation-rules.md
│   ├── 04-directory-structure.md
│   ├── 05-roadmap.md
│   └── adr/                         # Architecture Decision Records（0001-use-javac-frontend.md …）
│
├── translator/                      # ===== トランスパイラ本体（Java）=====
│   ├── j2r-common/                  # 共通基盤: 診断(Diagnostic)、ソース位置、名前変換ユーティリティ、オプション
│   ├── j2r-jir/                     # JIR（Java IR）のデータ型定義、Visitor/Rewriter、ダンプ用プリンタ
│   ├── j2r-frontend/                # javac 呼び出し、javac Tree → JIR 変換（javac 依存はここに閉じ込める）
│   ├── j2r-passes/                  # JIR → JIR の正規化（desugar）パス群
│   ├── j2r-analysis/                # 全体解析: CHA、呼び出しグラフ、null 性、不変性、エスケープ、例外フロー、スレッド
│   ├── j2r-rir/                     # RIR（Rust IR）のデータ型定義、RIR パス、Rust ソースプリンタ
│   ├── j2r-lowering/                # JIR(+解析結果) → RIR 変換、型マッピング、JDK API マッピングエンジン
│   ├── j2r-mappings/                # JDK API マッピング規則（YAML リソースのみ）
│   ├── j2r-backend/                 # Cargo プロジェクト出力、rustfmt / cargo check 実行、エラー逆マッピング
│   ├── j2r-driver/                  # パイプライン組み立て・実行（ライブラリ API: Translator.translate(...)）
│   └── j2r-cli/                     # コマンドライン（picocli）、fat jar / 配布物生成
│
├── runtime/                         # ===== 生成コードが依存する Rust crate =====
│   ├── jrt/                         # Java ランタイム（object, lang, num, array, exception, util, io, init）
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   └── tests/                   # Java 意味論の単体テスト（整数演算・文字列・コレクション）
│   └── jrt-junit/                   # 変換後テスト用のアサーション関数（assert_equals 等）
│
├── tests/                           # ===== 横断テスト =====
│   ├── golden/                      # ゴールデンテスト: <case>/Input.java + expected/*.rs
│   │   └── primitives/int_overflow/ ...
│   ├── e2e/                         # 差分実行テスト: <case>/src/**/*.java (+ 任意の stdin.txt, args.txt)
│   │   └── algorithms/quicksort/ ...
│   ├── corpus/                      # 実プロジェクト回帰コーパス（git submodule または取得スクリプト）
│   └── harness/                     # テストハーネス（Gradle サブプロジェクト j2r-test-harness）
│                                    #   javac+java 実行 → 変換 → cargo run → 出力比較
│
├── examples/                        # 利用例（Hello World、小さなアプリ）と変換結果
│
├── tools/                           # 開発用スクリプト
│   ├── update-golden.sh             # ゴールデンファイル再生成
│   └── run-e2e.sh
│
└── .github/
    └── workflows/
        ├── ci.yml                   # gradle build + cargo test + golden + e2e
        └── corpus.yml               # 定期実行: コーパス回帰（夜間）
```

## 2. モジュール依存関係

```
j2r-cli ─▶ j2r-driver ─┬─▶ j2r-backend ──▶ j2r-rir
                       ├─▶ j2r-lowering ─┬─▶ j2r-rir
                       │                 ├─▶ j2r-analysis ─▶ j2r-jir
                       │                 └─▶ j2r-mappings（リソース）
                       ├─▶ j2r-passes ────▶ j2r-jir
                       └─▶ j2r-frontend ──▶ j2r-jir
                 （全モジュール）──▶ j2r-common
```

ルール:
- `jdk.compiler`（javac）を参照してよいのは **`j2r-frontend` のみ**。以降の段は JIR だけを見る。
- `j2r-jir` と `j2r-rir` は互いに依存しない。両者を知るのは `j2r-lowering` のみ。
- `j2r-analysis` は JIR を読むだけで書き換えない（結果は別オブジェクトの注釈マップとして返す）。
- 依存違反は ArchUnit テスト（`j2r-driver` のテスト）で検出する。

## 3. Java パッケージ命名

ベースパッケージ: `io.github.ykwyuta.j2r`

| モジュール | パッケージ | 主要クラス例 |
|---|---|---|
| j2r-common | `…j2r.common` | `Diagnostic`, `DiagnosticCode`, `SourcePos`, `Naming`, `TranslatorOptions` |
| j2r-jir | `…j2r.jir`, `…j2r.jir.expr`, `…j2r.jir.stmt`, `…j2r.jir.decl`, `…j2r.jir.type` | `Program`, `TypeDecl`, `MethodDecl`, `Expr`, `Stmt`, `JType`, `JirRewriter` |
| j2r-frontend | `…j2r.frontend` | `JavacRunner`, `JirBuilder`, `TypeConverter` |
| j2r-passes | `…j2r.passes` | `Pass`, `PassManager`, `DesugarEnhancedFor`, `LiftInnerClasses`, … |
| j2r-analysis | `…j2r.analysis.{cha,callgraph,nullness,mutability,escape,exceptions,threads}` | `ClassHierarchy`, `NullnessAnalysis`, `RepresentationPlanner` |
| j2r-rir | `…j2r.rir`, `…j2r.rir.print` | `RItem`, `RExpr`, `RType`, `RustPrinter` |
| j2r-lowering | `…j2r.lowering`, `…j2r.lowering.mapping` | `Lowerer`, `TypeMapper`, `ApiMappingEngine` |
| j2r-backend | `…j2r.backend` | `CargoProjectWriter`, `CargoCheckRunner`, `ErrorBackMapper` |
| j2r-driver | `…j2r.driver` | `Translator`, `Pipeline` |
| j2r-cli | `…j2r.cli` | `Main` |

各モジュールは JPMS の `module-info.java` を持ち、公開パッケージを明示する（`internal` サブパッケージは非公開）。

## 4. モジュール内の標準レイアウト

```
translator/j2r-passes/
├── build.gradle.kts
└── src/
    ├── main/java/io/github/ykwyuta/j2r/passes/...
    ├── main/resources/
    ├── test/java/...              # 単体テスト（JUnit 5 + AssertJ）
    └── test/resources/            # 小さな Java 入力断片
```

## 5. テストケースの配置規約

### ゴールデンテスト（`tests/golden/<カテゴリ>/<ケース>/`）
```
tests/golden/classes/simple_inheritance/
├── Input.java             # 1 ファイル（または src/ 以下に複数）
├── options.txt            # 任意: 変換オプション（--mode=idiomatic 等）
└── expected/
    ├── src/lib.rs
    └── src/simple_inheritance.rs
```
`tools/update-golden.sh` で `expected/` を再生成し、差分をレビューしてコミットする。

### 差分実行テスト（`tests/e2e/<カテゴリ>/<ケース>/`）
```
tests/e2e/exceptions/nested_finally/
├── src/Main.java          # main を持つ
├── stdin.txt              # 任意
└── meta.yaml              # 任意: 期待終了コード、既知の非互換（skip 理由）
```
ハーネスは `javac && java` の出力と、変換 → `cargo run` の出力を比較する。期待出力ファイルは置かない
（JVM を正とする）。

## 6. 生成物（トランスパイラの出力）の構成

入力 `src/main/java/com/example/app/Main.java` 等に対し:
```
<out>/
├── Cargo.toml
├── src/
│   ├── main.rs            # main クラスがある場合
│   ├── lib.rs             # pub mod com;
│   └── com/
│       ├── mod.rs         # pub mod example;
│       └── example/
│           ├── mod.rs
│           └── app/
│               ├── mod.rs
│               └── main.rs    # クラス Main（予約語と衝突する名前は r#… または接尾辞 _）
├── tests/                 # 変換された JUnit テスト
├── j2r-names.json         # Java 名 ⇔ Rust 名 対応表
└── j2r-report.json        # 診断・未対応箇所・表現戦略
```
