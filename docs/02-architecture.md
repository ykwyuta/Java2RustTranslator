# 02. 実装方式（アーキテクチャ）

## 1. 目標と非目標

### 目標
- Java ソース（Java 21 まで）のプロジェクトを入力し、**`cargo build` が通る Rust の Cargo プロジェクト** を出力する。
- 既定では **実行結果（標準出力・例外・戻り値）が元の Java と一致する** こと（意味保存）。
- 生成コードが人間にとって読める・保守できること（名前・構造・コメントを保持）。
- 変換不能な箇所を **黙って誤訳せず**、診断メッセージと `todo!()` で明示すること。
- トランスパイラ本体は **Java（JDK 21）** で実装する。

### 非目標（少なくとも v1 では扱わない）
- リフレクション、動的クラスロード、`java.lang.invoke`、JNI、シリアライゼーション
- `finalize`、弱参照を前提とした GC 挙動の再現
- JDK 標準ライブラリ全体の移植（よく使うサブセットのみ、ランタイムとマッピングで提供）
- バイトコード入力

## 2. 全体パイプライン

```
 Java sources (+ classpath jars)
        │
        ▼
┌───────────────────────┐
│ ① Frontend (javac)     │  JavacTask.parse() / analyze()
│   型付き AST + Symbol   │  com.sun.source.tree.* / javax.lang.model.*
└──────────┬────────────┘
           ▼
┌───────────────────────┐
│ ② JIR Builder          │  javac AST → JIR（独自の型付き Java IR）
└──────────┬────────────┘
           ▼
┌───────────────────────┐
│ ③ Normalize passes     │  desugar: enhanced-for, autoboxing, 文字列連結,
│   (JIR → JIR)          │  内部クラス捕捉, switch 式, try-with-resources, ...
└──────────┬────────────┘
           ▼
┌───────────────────────┐
│ ④ Whole-program        │  クラス階層(CHA), 呼び出しグラフ, null 性,
│   analyses             │  不変性/可変性, エスケープ/所有権, 例外フロー
└──────────┬────────────┘   → 各ノードに「表現戦略」を注釈
           ▼
┌───────────────────────┐
│ ⑤ Lowering             │  JIR + 注釈 → RIR（Rust AST）
│   (JIR → RIR)          │  型マッピング / JDK API マッピング / 名前付け
└──────────┬────────────┘
           ▼
┌───────────────────────┐
│ ⑥ RIR passes           │  不要な clone 除去, `?` 化, use 整理, 可視性
└──────────┬────────────┘
           ▼
┌───────────────────────┐
│ ⑦ Backend              │  RIR → .rs テキスト, mod ツリー, Cargo.toml,
│                        │  （任意）rustfmt / cargo check 実行
└──────────┬────────────┘
           ▼
 Rust Cargo project  ──(依存)──▶  jrt（Java ランタイム crate）
```

各段は独立したモジュールで、段間のデータ構造（JIR / RIR）はイミュータブルな木として定義する。
パスは `Pass<JIR>` / `Pass<RIR>` インタフェースを実装し、ドライバが順序付きリストとして実行する
（J2ObjC / J2CL と同じ「多数の小さなパス」方式。各パスは単体テスト可能）。

## 3. フロントエンド: javac Compiler Tree API

### 選定
| 候補 | 型解決 | Java 最新構文 | 依存 | 評価 |
|---|---|---|---|---|
| **javac（`jdk.compiler` モジュール）** | 完全（JLS 準拠そのもの） | 常に最新 | JDK 同梱 | **採用** |
| Eclipse JDT Core | 完全 | 追随にタイムラグ | 大きい | 次点 |
| JavaParser + SymbolSolver | 部分的（複雑なジェネリクス・ラムダで失敗しやすい） | やや遅れる | 軽量 | 不採用 |
| tree-sitter | なし | — | ネイティブ | 不採用 |

### 使い方
```java
JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
JavacTask task = (JavacTask) compiler.getTask(
        null, fileManager, diagnostics,
        List.of("-proc:none", "--release", "21", "-classpath", cp),
        null, compilationUnits);
Iterable<? extends CompilationUnitTree> units = task.parse();
task.analyze();                       // 属性付け（型・シンボル解決）
Trees trees = Trees.instance(task);   // Tree → Element / TypeMirror
Types types = task.getTypes();
Elements elements = task.getElements();
```
- 公開 API（`com.sun.source.*`, `javax.lang.model.*`）のみで大半は足りる。内部 API（`com.sun.tools.javac.*`）が
  必要になった場合は `--add-exports` をビルド設定に集約し、使用箇所を `frontend` モジュール内に閉じ込める。
- javac がエラーを出した入力は変換しない（「コンパイル可能な Java」が前提）。
- クラスパス上の外部ライブラリは **シグネチャのみ** 参照する。未対応ライブラリの呼び出しは
  診断 + 外部 crate へのバインディング雛形（`extern` 風トレイト）を出力する。

## 4. 中間表現

### 4.1 JIR（Java IR）
javac AST を直接変換しない理由:
- javac の Tree は糖衣構文を多く含み、パスで書き換えにくい（ミュータブル・内部 API 依存）。
- 変換に必要な情報（解決済みメソッド、暗黙の型変換、捕捉変数）を明示的に持たせたい。

JIR の設計要点:
- **すべての式ノードが解決済みの型（`JType`）を持つ。**
- メソッド呼び出しは解決済みの `MethodRef`（宣言クラス・消去後シグネチャ・static/virtual/interface/super の区別）を持つ。
- 暗黙の変換（widening、boxing/unboxing、文字列変換）は **明示ノード** にする。
- 宣言（`TypeDecl`, `FieldDecl`, `MethodDecl`）と本体（`Stmt`, `Expr`）を分離し、
  宣言は `Program` 全体からシンボル ID で引けるようにする（全体解析のため）。
- 元ソース位置（ファイル・行・列）とコメントを保持する（生成コードへのコメント転記・診断に使用）。
- Java の `record` / `sealed interface` を使って代数的データ型として定義し、パスは
  `switch` パターンマッチで書く（Java 21 の機能をトランスパイラ実装自身で活用）。

```java
public sealed interface Expr permits Literal, Local, FieldAccess, Call, New,
        Binary, Unary, Assign, Cast, InstanceOf, Conditional, Lambda, ArrayAccess, ... {
    JType type();
    SourcePos pos();
}
public record Call(Expr receiver, MethodRef method, List<Expr> args,
                   DispatchKind dispatch, JType type, SourcePos pos) implements Expr {}
```

### 4.2 正規化（desugar）パス（JIR → JIR）
コア言語を小さくし、以降の解析・lowering の場合分けを減らす。主なパス（実行順）:

1. `ResolveImplicitConversions` — boxing/unboxing・数値昇格・文字列変換を明示化
2. `DesugarEnhancedFor` — 配列版は添字ループ、`Iterable` 版は `iterator()` ループへ
3. `DesugarStringConcat` — `+` 連結を `StringBuilder` 相当の呼び出し列へ
4. `DesugarCompoundAssignment` — `a[i++] += x` 等を副作用 1 回の一時変数付きへ
5. `DesugarSwitch` — 文字列/enum/パターン switch を正規形へ
6. `DesugarTryWithResources` — try/finally + `close()` 呼び出しへ
7. `LiftInnerClasses` — 内部クラス・匿名クラス・ローカルクラスを捕捉変数付きトップレベル型へ
8. `NormalizeLambdas` — メソッド参照をラムダへ統一、捕捉変数を明示
9. `LowerLabeledJumps` — ラベル付き break/continue を Rust のラベル付きループに対応する形へ
10. `SplitStaticInit` — static 初期化子・フィールド初期化子をクラス初期化関数へ
11. `NormalizeConstructors` — `this(...)`/`super(...)` 連鎖、インスタンス初期化子の展開
12. `MangleOverloads` — オーバーロードに一意な Rust 名を割当（後述）

### 4.3 RIR（Rust IR）
- Rust の構文要素（`Item::Struct/Enum/Trait/Impl/Fn/Mod/Use`、`Expr`、`Pat`、`Type`）を表すイミュータブルな木。
- 文字列テンプレートで直接 Rust を書き出さず、必ず RIR を経由する（括弧・優先順位・`;` の扱いを
  プリンタに一元化し、構文的に正しい出力を保証する。java2rs が `quote`/`prettyplease` を使うのと同じ理由）。
- RIR パス: 不要な `.clone()` の除去、`match` の単純化、`Result` 伝播の `?` 化、`use` 文の整理。

## 5. 全体解析と「表現戦略」

Java と Rust のギャップ（GC・null・継承・例外・共有可変参照）は、**型/変数ごとに表現戦略を選ぶ** ことで埋める。
解析は保守的で、証明できない場合は常に「意味保存の既定表現」にフォールバックする。

| 解析 | 入力 | 出力（注釈） | 用途 |
|---|---|---|---|
| クラス階層解析 (CHA) | 全型宣言 | サブクラス集合、final 化可能性、実装者の閉包 | 仮想呼び出しの静的化、sealed → enum 化 |
| 呼び出しグラフ | CHA + 本体 | 到達可能メソッド | 不要コード除去、例外フロー |
| 不変性解析 | フィールド書込み箇所 | 型が deeply-immutable か | `Rc<T>`（RefCell 不要）化 |
| 同一性解析 | `==`, `synchronized`, `IdentityHashMap` 等の使用 | 同一性が観測されるか | 値型（`Clone`/`Copy`）化の可否 |
| エスケープ/所有権解析 | 参照の流れ | 参照が単一所有か・共有か | `Box<T>`/値/`&mut` への格上げ |
| null 性解析 | 代入・比較・注釈（`@Nullable`/`@NonNull`） | 各変数/フィールド/戻り値が nullable か | `Option<_>` の要否 |
| 例外フロー解析 | 呼び出しグラフ + throw/catch | メソッドが例外を送出しうるか | `Result` 戻り値の要否 |
| スレッド解析 | `Thread`/`Executor`/`synchronized` 使用 | 型がスレッド間共有されるか | `Rc`→`Arc`、`RefCell`→`Mutex` |

### オブジェクト表現戦略（段階）

| 戦略 | 生成表現 | 条件 |
|---|---|---|
| S0 共有可変（既定） | `jrt::Ref<T>`（= `Rc<T>`）+ **フィールド単位の内部可変性**（`Cell<_>` / `RefCell<_>`） | 常に安全 |
| S1 共有不変 | `Rc<T>`（フィールドは素の値） | 型が deeply-immutable（record、全 final フィールドで可変参照なし） |
| S2 値型 | `T`（`#[derive(Clone)]`、場合により `Copy`） | 不変 かつ 同一性が観測されない |
| S3 単一所有 | `Box<T>` / 所有値 / `&mut T` 引数 | エスケープ解析で共有されないことを証明 |
| S4 スレッド共有 | `jrt::SyncRef<T>`（= `Arc<T>` + フィールド単位の `Mutex`/Atomic） | スレッド解析で共有を検出 |

**S0 で `Rc<RefCell<T>>`（オブジェクト単位のロック）を採らない理由**: Java では「メソッド実行中に
自分自身が別経路から呼び戻される」（リスナ、コールバック、再帰的データ構造の走査）が日常的で、
オブジェクト全体を `borrow_mut()` すると実行時の `BorrowMutError` パニックになる。
フィールド単位で `Cell`（Copy 型）/ `RefCell`（その他、値を clone して即座に借用を解放）にすれば、
メソッドは常に `&self` で呼べ、再入しても借用が衝突しない。副次的に `Rc<dyn Trait>` への
アップキャストが標準の unsized coercion でそのまま機能する（`Ref<T>` は型エイリアスとする）。

`this` を外部へ渡す（リスナ登録など）クラスは、エスケープ解析で検出して `Rc::new_cyclic` で
`Weak<Self>` を自己参照フィールドに保持し、`jrt::this(&self)` で `Rc<Self>` を復元する。

- 循環参照（双方向リンクリスト、親子ポインタ）は S0 ではリークする。対策として
  (a) `@Weak` 相当のユーザ注釈で `Weak<_>` に、(b) ランタイムの参照型を GC 実装（`gc` crate 等）に
  差し替えるビルドオプション、を用意する（J2ObjC の `@Weak` と同じ思想）。
- 戦略は `--mode=faithful`（S0 中心、最大限安全）/ `--mode=idiomatic`（S1〜S3 を積極適用）で制御する。

## 6. ランタイム crate `jrt`

生成コードが依存する Rust crate を本リポジトリ内で開発する（J2ObjC の jre_emul に相当）。

| モジュール | 内容 |
|---|---|
| `jrt::object` | `Ref<T>`（`Rc<T>` エイリアス）, `SyncRef<T>`, 自己参照ヘルパ, `JObject` トレイト（`equals`/`hash_code`/`to_string`/`as_any` によるダウンキャスト） |
| `jrt::lang` | `JString`（不変、Java の UTF-16 意味論の `charAt`/`length` を提供）、`StringBuilder`、`Math`、ボックス型、`Integer.parseInt` 等 |
| `jrt::num` | Java 意味論の整数演算（ラップアラウンド、シフト量マスク、`/`・`%` の丸めとゼロ除算例外、`Integer.MIN_VALUE / -1`） |
| `jrt::array` | `JArray<T>`（固定長・共有・添字境界例外） |
| `jrt::exception` | `Throwable` 階層（`JException` enum + trait）、スタックトレース相当、`catch` 用ダウンキャスト |
| `jrt::util` | `ArrayList`, `HashMap`, `HashSet`, `LinkedList`, `Iterator` 等（Java の `equals`/`hashCode` 契約で動作） |
| `jrt::io` | `System.out` / `System.err`、`PrintStream`、簡易ファイル I/O |
| `jrt::init` | クラス初期化（`static` 初期化の遅延実行・一度きり）用ヘルパ |

方針:
- `jrt` は安全な Rust のみで書く（`#![forbid(unsafe_code)]`）。
- 生成コードは可能な限り **Rust 標準型を直接使い**（例: 非共有の `ArrayList<Integer>` → `Vec<i32>`）、
  `jrt` 型は意味保存に必要な場合のみ使う。

## 7. JDK API マッピング

JDK クラスの呼び出しは lowering 時に **宣言的なマッピング規則** で変換する（JSweet adapter / Sharpen 方式）。
規則はリソースファイル（YAML）で管理し、ユーザ定義規則でも拡張可能にする。

```yaml
# mappings/java.lang.Math.yaml
class: java.lang.Math
methods:
  - sig: "abs(int)"
    rust: "{0}.wrapping_abs()"        # Math.abs(Integer.MIN_VALUE) == MIN_VALUE
  - sig: "max(int,int)"
    rust: "std::cmp::max({0}, {1})"
  - sig: "sqrt(double)"
    rust: "{0}.sqrt()"
# mappings/java.util.List.yaml
class: java.util.List
methods:
  - sig: "size()"
    rust: "({this}.len() as i32)"
    when: { strategy: owned-vec }      # 表現戦略ごとに規則を切り替え
  - sig: "size()"
    rust: "{this}.borrow().size()"
```
マッピングの無い JDK API は `jrt` 側の実装を探し、それも無ければ診断 `J2R-UNSUPPORTED-API` を出し `todo!()` を生成する。

## 8. 名前付け

- パッケージ `com.example.foo` → モジュール `com::example::foo`、クラス `FooBar` → ファイル `foo_bar.rs` / 型 `FooBar`。
- メソッド・フィールド・ローカル変数は `snake_case`、定数（static final）は `SCREAMING_SNAKE_CASE`。
- Rust 予約語との衝突は raw identifier（`r#type`）で回避。
- オーバーロードは引数型から接尾辞を生成（`append_i32`, `append_str`）。オーバーロードされていない
  メソッドには接尾辞を付けない。衝突時は連番。
- 変換後の名前対応表（`j2r-names.json`）を出力し、差分翻訳・デバッグに使う。

## 9. 出力（Backend）

```
out/
├── Cargo.toml          # [dependencies] jrt = { path = "jrt" }、独立した [workspace]
├── jrt/                # ランタイム crate のソース（同梱。--runtime-path 指定時は作らない）
├── src/
│   ├── lib.rs          # mod 宣言のツリー
│   ├── bin/main.rs     # public static void main がある場合のみ
│   └── com/example/...
└── j2r-report.json     # 診断・未対応箇所の一覧
```
- 構成の詳細は [04-directory-structure.md §6](04-directory-structure.md) を参照。
- `--cargo-check` を指定すると、生成後に `cargo check` を実行して失敗を診断として報告する。
- （予定）Rust 側のエラーを元の Java ソースの位置に対応付けて報告する（RIR が元の位置を保持しているため可能）、
  JUnit テストを変換した `tests/`、Java 名と Rust 名の対応表 `j2r-names.json`。

## 10. 診断

- 診断は `Diagnostic(code, severity, javaPos, message, hint)`。コード体系:
  `J2R-UNSUPPORTED-*`（未対応機能）, `J2R-LOSSY-*`（意味が変わりうる変換）, `J2R-PERF-*`（非効率な既定表現）。
- `--strict` では `LOSSY` をエラー扱いにする。

## 11. 検証戦略

| レベル | 内容 |
|---|---|
| 単体テスト | 各パス・各解析を JIR 断片で単体テスト（JUnit 5） |
| ゴールデンテスト | `Input.java` → 期待 `expected.rs` の比較（スナップショット） |
| コンパイルテスト | 生成物に `cargo check` を実行し、警告・エラーゼロを確認 |
| **差分実行テスト** | 同じプログラムを `java` と `cargo run` で実行し、stdout/stderr/終了コードを比較。E2E の主軸 |
| ランタイムテスト | `jrt` の Java 意味論（整数・文字列・コレクション）を `cargo test` で検証 |
| 既存ベンチマーク | 小規模 OSS・競プロ系コード・教科書的アルゴリズム集を回帰コーパスにする |

## 12. 拡張ポイント

- **パスの追加**: `Pass` インタフェース + ServiceLoader で外部パスを差し込める。
- **API マッピング**: ユーザ定義 YAML を `--mapping-dir` で追加。
- **LLM 後処理（任意・実験的）**: 生成 Rust + 診断を LLM に渡し、イディオム化・`todo!()` 解消を試みる
  プラグイン。**出力は必ず差分実行テストで再検証** し、不一致なら採用しない（C2Rust postprocess / FLOURINE の考え方）。

## 13. 技術スタック

| 項目 | 選定 |
|---|---|
| 実装言語 | Java 21（record / sealed / パターンマッチを IR とパスに活用） |
| ビルド | Gradle（Kotlin DSL、マルチプロジェクト、version catalog） |
| CLI | picocli |
| 設定/マッピング | YAML（SnakeYAML Engine） |
| テスト | JUnit 5, AssertJ, スナップショットテスト |
| Rust 側 | stable Rust、edition 2021、`rustfmt`、`cargo` |
| CI | GitHub Actions（JDK 21 + Rust stable） |
