# 01. 先行プロジェクト調査

調査日: 2026-09。調査対象は大きく 4 系統に分けた。

1. Java → Rust を直接対象にした変換器
2. Java → 他言語の実用トランスパイラ（アーキテクチャの参考）
3. X → Rust の変換器（Rust 側の難しさへの対処の参考）
4. LLM / ニューロシンボリック手法の研究

---

## 1. Java → Rust 変換器

### 1.1 aschoerk/converter-page（jrconverter）
- URL: https://github.com/aschoerk/converter-page
- 実装: Java（Web アプリ、WAR）。**JavaParser** で構文木を作り、その上で Iterator 群を回して
  テキスト変換する。2016 年ごろ開始。
- 変換内容: 変数宣言、配列 → `Vec`、camelCase → snake_case、プリミティブ型の対応付け、
  メソッドへの `&self` 付与、`throws` → `Result`、interface → trait、`@Test` → `#[test]` など。
- 限界: 作者自身が「生成コードは "unrusty" で、Rust として構文的に正しいとは限らない」と明言。
  継承の扱いは実験的。**移植作業の下書きを作る補助ツール** という位置付け。

### 1.2 cguz/java-to-rust
- URL: https://github.com/cguz/java-to-rust
- 上記 converter-page を CLI 化したもの（Java / Maven、`java2rust.jar`）。ファイル・ディレクトリ
  単位で変換できるが、変換ロジック・限界は 1.1 と同じ（スーパークラスの扱いは
  "certainly wrongly done" と README に記載）。

### 1.3 JonasCir/java2rs
- URL: https://github.com/JonasCir/java2rs
- 実装: Rust。**tree-sitter-java** でパースし、独自 IR に変換（static メソッドのグルーピング等）、
  `quote` / `syn` / `prettyplease` で Rust コードを生成。
- 状態: WIP、README の FAQ で「本番利用可能か？ → No!」。
- 教訓: **IR を挟み、Rust 生成に `quote`/`prettyplease` のような構造化出力を使う** 構成は正しいが、
  tree-sitter には型情報がないため、オーバーロード解決や式の型に依存する変換ができない。

### 1.4 vgnogueira/java2rust
- 参考: Rust フォーラム「Java 2 Rust "compiler"」 https://users.rust-lang.org/t/java-2-rust-compiler/55777
- 作者自身が「single pass の toy project」と述べている。フォーラムでは次の本質的課題が指摘された:
  - Rust には GC がない → オブジェクト共有・循環参照をどう表現するか
  - 継承ベースの OOP が Rust に直接対応しない
  - Java で普通の「共有された可変参照」が借用チェッカに拒否される
  - 動いても C 風の非イディオマティックなコードになる

### 1 系統のまとめ
既存の Java→Rust 変換器はすべて **構文レベルの変換** に留まっており、**型情報を持たない・
意味論（null、例外、整数オーバーフロー、参照共有）を保存しない** ため、コンパイル可能な Rust を
安定して出せていない。本プロジェクトはこの点を根本的に変える必要がある。

---

## 2. Java → 他言語の実用トランスパイラ

### 2.1 Google J2ObjC（Java → Objective-C）
- URL: https://github.com/google/j2objc , https://developers.google.com/j2objc
- **javac のフロントエンド全体** を使って型注釈付き AST を得て、それを J2ObjC 独自の内部表現
  （tree パッケージ）に変換、数十段の translation pass（desugar、名前付け、メモリ管理注釈付与など）を
  経てから Objective-C を出力する。javac は jarjar で再パッケージして同梱。
- JRE のクラス群は **jre_emul**（ランタイムライブラリ）として別途提供。
- GC のない言語（ARC）へ GC 言語を移すという点で本プロジェクトに最も近い。循環参照は
  `@WeakOuter` / `@Weak` 注釈でユーザに解消させる設計。
- 教訓: **javac + 独自 IR + 多段パス + JRE エミュレーションライブラリ** が実用品の定石。

### 2.2 Google J2CL（Java → Closure JavaScript）
- URL: https://github.com/google/j2cl
- J2ObjC の後継世代。javac（旧 JDT）でパース → 独自の AST（`j2cl.common.ast`）→ 多数の
  normalization pass → JS 出力。JRE エミュレーションも同様に持つ。意味論（整数演算、
  文字列、例外、初期化順序）を厳密に保存する設計ドキュメント（docs/semantics.md）がある。
- 教訓: **「意味論の仕様書」を先に書き、正規化パスで Java を小さなコア言語に落としてから出力する。**

### 2.3 JSweet（Java → TypeScript/JavaScript）
- javac を使用。Java API の呼び出しを **アダプタ（マッピング規則）** で差し替える仕組みを持ち、
  ユーザが拡張可能。→ 本プロジェクトの JDK API マッピング設計の参考。

### 2.4 Sharpen（Java → C#, db4o）
- Eclipse JDT ベース。マッピング設定ファイルで API 変換を記述。

### 2.5 TeaVM / GraalVM Native Image（参考）
- バイトコードを入力にする方式。バイトコードは言語機能が簡略化され扱いやすいが、
  **ソースの構造・名前・コメントが失われ、人が保守できる Rust にならない** ため本プロジェクトでは不採用。

---

## 3. X → Rust の変換器

### 3.1 C2Rust（C → Rust, Immunant/Galois）
- URL: https://github.com/immunant/c2rust
- clang の AST をエクスポート → **まず C の意味をそのまま保った unsafe Rust を出力**（テストが
  そのまま通ることを優先）→ `c2rust refactor` / `postprocess`（LLM）で段階的に安全・
  イディオマティックに「持ち上げる」。cross-check による元コードとの比較検証機能を持つ。
- 教訓: **正しさ優先の機械的変換 と イディオム化 を別段階に分離する。**

### 3.2 Crown: Ownership Guided C to Rust Translation
- https://arxiv.org/abs/2303.10515
- C のポインタに対して所有権解析を行い、安全な Rust ポインタ型（`Box` 等）に変換。
  50 万行規模を 10 秒未満で解析できるスケーラブルな解析。
- 教訓: Java の参照も **所有権・エイリアス解析** によって共有参照（`Rc`）→ `Box<T>` / 値型に
  格上げできる。

### 3.3 C++ → Rust（Preto, IST 修士論文）
- https://web.ist.utl.pt/nuno.lopes/students/Henrique_Preto_MSc.pdf
- クラスとポインタを `Rc` / `RefCell` で表す安全な Rust への変換。オブジェクト指向言語から
  Rust への変換で `Rc` + `RefCell` を基本表現にするのは妥当な出発点であることを示す
  （本プロジェクトでは再入時の借用衝突を避けるため、`RefCell` をオブジェクト単位ではなく
  フィールド単位に置く。02-architecture.md §5 参照）。

### 3.4 GC for Rust（Alloy 等）
- https://arxiv.org/abs/2504.01841 : `Rc<T>` 風 API の `Gc<T>` を提供する GC。
- 循環参照を持つ Java のオブジェクトグラフをそのまま表現するための **選択肢** として、
  ランタイムの参照型を差し替え可能にしておく価値がある。

---

## 4. LLM / ニューロシンボリック研究

| 研究 | 概要 | 本プロジェクトへの示唆 |
|---|---|---|
| Towards Translating Real-World Code with LLMs: A Study of Translating to Rust (FLOURINE) https://arxiv.org/abs/2405.11514 | 複数 LLM の Rust 翻訳能力を評価。**差分ファジングで I/O 等価性を検証** するツール FLOURINE を提案。 | 検証に差分テスト/ファジングを使う |
| AlphaTrans (FSE 2025) https://arxiv.org/abs/2410.24117 | Java リポジトリ全体をテスト込みで翻訳。静的解析で言語固有機能を解決し、**コンパイル可能なスケルトンを先に生成**、呼び出しの逆順でフラグメントを翻訳・検証。 | スケルトン先行生成、依存逆順の処理順 |
| SafeTrans https://arxiv.org/abs/2505.10708 ほか C→Rust 系多数 | LLM + コンパイラエラーによる反復修正 | 生成後 `cargo check` の診断を使った修正ループ（オプション） |

LLM 単体では意味の保存を保証できない一方、ルールベースだけではイディオマティックさに限界がある。
**本プロジェクトは決定的（deterministic）なルールベース変換をコアとし、LLM は任意の後処理
プラグイン（C2Rust の postprocess と同じ位置付け）とする。**

---

## 5. 調査から導いた設計方針

| # | 方針 | 根拠 |
|---|---|---|
| P1 | 入力はバイトコードではなく **ソース**、パーサは **javac（Compiler Tree API）** | 既存 Java→Rust 変換器が型情報不足で失敗 / J2ObjC・J2CL・JSweet の実績 |
| P2 | javac AST → **独自 IR（JIR）** → 正規化パス → **Rust IR（RIR）** → 出力 | J2ObjC / J2CL / java2rs |
| P3 | **意味保存モードを既定** とし、イディオム化は解析で安全と示せた箇所のみ | C2Rust、J2CL の semantics 文書 |
| P4 | オブジェクトは既定で共有参照型（`jrt::Ref<T>` = `Rc<T>` + フィールド単位の内部可変性）。解析で格上げ | Preto、Crown、Rust フォーラムの議論 |
| P5 | JRE は **ランタイム crate（jrt）+ 宣言的 API マッピング** で提供 | J2ObjC jre_emul、JSweet adapters |
| P6 | 生成物は **`cargo check` が通る完全な Cargo プロジェクト**。スケルトン先行生成 | AlphaTrans |
| P7 | 検証は **JVM 実行結果との差分テスト** | FLOURINE、C2Rust cross-check |
| P8 | `unsafe` を生成しない。対応不能な構文は `todo!()` + 診断で明示し、黙って誤訳しない | 既存変換器の「構文的に正しくない出力」問題 |

## 参考リンク
- https://github.com/aschoerk/converter-page
- https://github.com/cguz/java-to-rust
- https://github.com/JonasCir/java2rs
- https://users.rust-lang.org/t/java-to-rust-converter/6546
- https://users.rust-lang.org/t/java-2-rust-compiler/55777
- https://github.com/google/j2objc
- https://github.com/google/j2cl
- https://github.com/immunant/c2rust
- https://arxiv.org/abs/2303.10515
- https://arxiv.org/abs/2504.01841
- https://arxiv.org/abs/2405.11514
- https://arxiv.org/abs/2410.24117
- https://arxiv.org/abs/2505.10708
