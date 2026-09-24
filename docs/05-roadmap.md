# 05. ロードマップ

## 現在の状況（2026-09）

| マイルストーン | 状況 |
|---|---|
| M0 基盤 | **完了**: Gradle マルチプロジェクト、javac → JIR、RIR とプリンタ、Cargo プロジェクト出力、テストハーネス、CI、Gradle プラグイン |
| M1 手続き的サブセット | **完了**: fall-through する switch、switch 式、`String.format` / `printf`、可変長引数、ボクシングを含む |
| M2 オブジェクト指向 | **完了**（S0 表現のみ）: クラス・継承・インタフェース・enum（本体付きの定数を含む）・record・sealed・内部 / 匿名 / ローカルクラス・null・`instanceof` / record パターン、Java と同じクラスの初期化、JDK クラス（Thread・コレクション）の継承 |
| M3 例外・ジェネリクス・ラムダ | **完了**: 例外は `Result` で伝える（送出しうるメソッドは固定点の例外フロー解析で求める。ランタイム例外も catch できる）、try/catch/finally・try-with-resources、ジェネリクス（消去）、ラムダ・メソッド参照、コレクション・ストリーム API・Optional・正規表現、JUnit 5 のテストの変換 |
| M4 イディオム化 | 未着手 |
| M5 規模対応・周辺機能 | 一部: スレッドと `java.util.concurrent` を決定的なスケジューラで実行する（実際の並列実行は未着手） |
| F1 フレームワーク（Spring） | 一部: `--framework spring` でアプリ全体（コントローラ・フォーム・Thymeleaf・設定・サービス・Mapper・ドメイン）を axum + sqlx + askama に変換する。examples/todo-app の Rust 版はすべて生成で、Spring 版と応答が一致する。未対応の機能は [07-spring-to-rust.md](07-spring-to-rust.md) §7 |

E2E テスト 25 件（`tests/e2e`）と JUnit 変換のテスト（`tests/junit`）がすべて JVM と一致する。

生成コードで原理的に再現しないもの（[06-usage.md §5](06-usage.md#5-現在変換できる範囲)）: 識別ハッシュの値、
NullPointerException の詳細メッセージ、`StackOverflowError`、プラットフォームの文字コードでの出力。

各マイルストーンの完了条件は「該当カテゴリの **差分実行テスト（tests/e2e）がすべて通り、生成物が
`cargo check` で警告なし**」とする。

## M0: 基盤（スケルトン）
- Gradle マルチプロジェクト・Cargo ワークスペース・CI の雛形
- `j2r-frontend`: javac 実行 → JIR 構築（宣言のみ）
- `j2r-rir` + `RustPrinter`、`j2r-backend`: 空の Cargo プロジェクト出力
- テストハーネス（javac/java 実行、cargo run、出力比較）
- **到達点**: `System.out.println("Hello")` だけの `main` が変換・実行できる

## M1: 手続き的サブセット
- プリミティブ型と Java 意味論の演算（`jrt::num`）、ローカル変数、制御構文、ラベル付きジャンプ
- static メソッド・static フィールド、オーバーロードの名前修飾
- `String` の基本操作、文字列連結、`StringBuilder`、`Math`
- 配列（`JArray`）
- **到達点**: 競技プログラミング風の単一クラスプログラム（ソート、DP、探索）が変換・一致

## M2: オブジェクト指向
- クラス・コンストラクタ・インスタンスフィールド（S0 表現）
- 継承・抽象クラス・インタフェース・default メソッド・`super` 呼び出し
- キャスト・`instanceof`・パターンマッチ、`equals`/`hashCode`/`toString`
- null（`Option`）と NPE
- `enum`・`record`
- 内部クラス・匿名クラス
- **到達点**: デザインパターン集程度の OOP プログラムが一致

## M3: 例外・ジェネリクス・ラムダ
- 例外フロー解析と `Result` 化、try/catch/finally、try-with-resources
- ジェネリクス（クラス・メソッド・境界・ワイルドカード）
- ラムダ・メソッド参照・関数型インタフェース
- `jrt::util`: `ArrayList`, `HashMap`, `HashSet`, `ArrayDeque`, `Iterator`, `Collections`, `Arrays`
- **到達点**: 典型的なアルゴリズム/データ構造ライブラリ（テスト込み）が変換・一致、JUnit テスト変換

## M4: イディオム化（`--mode=idiomatic`）
- 不変性解析 → S1、同一性解析 → S2、エスケープ解析 → S3
- null 性解析による `Option` 除去
- `ArrayList` → `Vec`、拡張 for → Rust イテレータ、簡単な Stream → `Iterator` チェーン
- 整数範囲解析による `wrapping_*` 除去
- sealed 階層 → `enum`
- **評価指標**: 生成コード中の `Ref<`/`RefCell`/`wrapping_` の出現数、行数、実行速度（faithful 比）

## M5: 規模対応・周辺機能
- 複数モジュール・外部 jar 依存のあるプロジェクト、インクリメンタル変換
- `cargo check` エラーの Java 位置への逆マッピング
- スレッド（S4）: 実際に並列に実行する（`Arc` / `Mutex`。現在は 1 つの OS スレッドで決まった順に実行する）
- ユーザ定義 API マッピング、`@Weak` 等の変換ヒント注釈（`j2r-annotations` jar として提供）
- 実 OSS コーパス回帰（夜間 CI）

## F1: フレームワーク（Spring Boot + MyBatis + Thymeleaf → axum + sqlx + askama）
- **済**: 宣言のメタ情報（注釈の値・消去前の型・JSpecify の `@Nullable`）、`@Mapper` + Mapper XML / 注釈の SQL → sqlx の関数
  （動的 SQL は `QueryBuilder`）、`@Service` → トランザクションを張る構造体、エンティティ・record・enum・例外
- **済**: Web 層（`@Controller` → axum のハンドラとルート、`@ModelAttribute` のフォームと Bean Validation → `bind`・`validate`、
  `@ControllerAdvice` → `AppError`）、Thymeleaf → askama のテンプレート（フラグメントは展開）、`@Configuration` の `@Bean` →
  `AppState` の組み立て、`application.yml` → 環境変数で上書きできる設定
- **到達点（達成）**: examples/todo-app の Rust 版をすべて生成し、compare.sh で Spring 版と一致する
- 次: MockMvc / `@SpringBootTest` のテストの変換、`<foreach>`・`resultMap`、サービス間の呼び出し（トランザクションの伝播）、
  Thymeleaf の残りの機能（`#{...}` のメッセージ式・`<select>` の `th:field` など）、Spring Data JDBC / JPA

## M6（実験）: LLM 後処理プラグイン
- `todo!()` 箇所や `J2R-PERF` 診断箇所を対象に LLM でリライト案を生成
- 差分実行テストで等価性を確認できたものだけ採用

## 最初に作るべき ADR（docs/adr/）
1. 0001: フロントエンドに javac Compiler Tree API を採用する
2. 0002: 独自 IR（JIR / RIR）を 2 層で持つ
3. 0003: 既定のオブジェクト表現を `Rc<T>` + フィールド単位の内部可変性とする
4. 0004: 例外を `Result` で表現し、ランタイム例外のパニック化はオプションとする
5. 0005: 生成コードに `unsafe` を含めない
