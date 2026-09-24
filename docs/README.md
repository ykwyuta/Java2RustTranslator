# Java2RustTranslator 設計ドキュメント

Java ソースコードを Rust ソースコードへ変換するトランスパイラ（source-to-source compiler）を
**Java で実装する** ための調査・設計資料です。

| ドキュメント | 内容 |
|---|---|
| [01-prior-art.md](01-prior-art.md) | 先行プロジェクト・研究の調査結果と、そこから得た教訓 |
| [02-architecture.md](02-architecture.md) | 実装方式（パイプライン、IR、解析、ランタイム、検証戦略） |
| [03-translation-rules.md](03-translation-rules.md) | Java 言語機能 → Rust への変換規則（型・クラス・例外・null 等） |
| [04-directory-structure.md](04-directory-structure.md) | 本プロジェクトのディレクトリ構成・モジュール分割・命名規約 |
| [05-roadmap.md](05-roadmap.md) | 段階的な実装計画と現在の状況 |
| [06-usage.md](06-usage.md) | ビルド方法、Gradle プラグイン・コマンドラインでの変換、現在の対応範囲 |
| [07-spring-to-rust.md](07-spring-to-rust.md) | Spring Boot（MVC・MyBatis・Thymeleaf）の Web アプリの移行先の標準構成（axum・sqlx・askama）と変換規則 |

## 要約（TL;DR）

- **フロントエンドは javac（Compiler Tree API）を使う。** J2ObjC / J2CL / JSweet と同じ選択で、
  型解決・オーバーロード解決・ジェネリクス推論を javac に任せられる。構文だけのパーサ
  （JavaParser 単体、tree-sitter）で作られた既存の Java→Rust 変換器はここで行き詰まっている。
- **javac の AST を直接 Rust に出力しない。** 独自の型付き中間表現 **JIR**（Java IR）に変換し、
  desugar パス → 解析 → **RIR**（Rust IR）への lowering → プリティプリント、の多段構成にする。
- **「まず正しく、次に Rust らしく」**（C2Rust の方針）。既定では意味を保存する安全な Rust
  （`Rc<T>` + フィールド単位の `Cell`/`RefCell` によるオブジェクト表現 + 小さなランタイム crate `jrt`）を生成し、
  解析（不変性・エスケープ・null 性・クラス階層）で証明できた箇所だけを所有権ベースの
  イディオマティックな表現に格上げする。`unsafe` は生成しない。
- **検証は差分テスト。** 同じ入力を JVM と `cargo run` で実行し出力を比較する E2E テストを
  開発の中心に置く。
