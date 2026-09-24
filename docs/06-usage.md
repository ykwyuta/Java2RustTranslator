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

- E2E テスト（`tests/e2e`）と JUnit 変換のテスト（`tests/junit`）は `cargo` が PATH に無ければスキップされる。
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
    // framework.set("spring")               // Spring Boot + MyBatis の変換規則を使う（§9）
    // resourceDirs.from("src/main/resources") // --framework spring の Mapper XML・schema.sql（既定: main の resources）
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
| `--framework` | `NONE`（既定）/ `SPRING`（Spring Boot + MyBatis のアプリを sqlx の crate に変換する。§9） |
| `--resources` | `--framework SPRING` で Mapper XML と schema.sql を探すディレクトリ |
| `@file` | 引数をファイルから読む（ソースが多い場合） |

終了コードは、変換できれば 0（未対応機能の警告があっても 0）、javac のエラーなどで変換できなければ 1。
診断は標準エラーと `<出力先>/j2r-report.json` に出る。

## 5. 現在変換できる範囲

| 分類 | 対応している | 未対応（診断を出して `todo!()` を生成する） |
|---|---|---|
| クラス | インスタンスフィールド・コンストラクタ（オーバーロード、`this(...)` / `super(...)`）・インスタンスメソッド・static メンバ・static 初期化ブロック・インスタンス初期化子、継承・抽象クラス・`super.m()`、インタフェース（default / static メソッド）、`enum`（フィールド・コンストラクタ・メソッド・本体付きの定数・`values()` / `valueOf` / `ordinal` / `name` / `getDeclaringClass`・`switch`）、`record`（アクセサ・`equals` / `hashCode` / `toString`・コンパクトコンストラクタ）、`sealed` / `permits`、static 入れ子クラス・内部クラス（`outer.new Inner()`、`Outer.this`）・匿名クラス・ローカルクラス（外側のローカル変数の捕捉を含む）、注釈型の宣言（読み飛ばす）、JDK のクラスの継承: 例外クラス・`Thread`・コレクション（`ArrayList` `LinkedList` `Vector` `Stack` `ArrayDeque` `PriorityQueue` `HashSet` `LinkedHashSet` `TreeSet` `HashMap` `LinkedHashMap` `TreeMap` `Hashtable` `ConcurrentHashMap`） | 上記以外の JDK のクラスの継承（`AbstractList`・`InputStream` など）、リフレクション |
| 型 | プリミティブ型、`String`、配列（多次元・共変な代入を含む）、`null`、ボクシング（`Integer` 等。`Integer.valueOf` のキャッシュも Java と同じ）、ジェネリクス（消去して変換）、`List` / `ArrayList` / `LinkedList` / `Vector` / `Stack` / `ArrayDeque` / `PriorityQueue` / `Map` / `HashMap` / `LinkedHashMap` / `TreeMap` / `Hashtable` / `EnumMap` / `WeakHashMap` / `Set` / `HashSet` / `LinkedHashSet` / `TreeSet` / `EnumSet` / `BitSet`、`java.util.concurrent` のコレクション（`ConcurrentHashMap`・`CopyOnWriteArrayList`・`ConcurrentSkipListMap/Set`・`BlockingQueue` など）、`Iterator` / `ListIterator` / `Map.Entry` / `Enumeration`、`Optional`（`OptionalInt` 等を含む）、`Random`、`StringBuilder`、`Scanner`、`Class`（`X.class`・`getClass()` の名前） | — |
| ストリーム | `Stream` / `IntStream` / `LongStream` / `DoubleStream`（生成・中間操作・終端操作を遅延評価で）、`Collectors`（`toList` / `toSet` / `toMap` / `groupingBy` / `partitioningBy` / `joining` / `counting` / `summingInt` / `averagingDouble` など）、`summaryStatistics` | 並列ストリーム（逐次に実行する） |
| 演算 | Java と同じ意味の四則演算・剰余・シフト・ビット演算・比較・キャスト（オーバーフロー、ゼロ除算の例外、`MIN / -1` など）、`instanceof`（型パターン・record パターンを含む）、参照の `==` | — |
| 文 | if / while / do-while / for / 拡張 for（配列・`Iterable`）/ ラベル付き break・continue / switch 文（`->` 形式、fall-through する `:` 形式、int・char・String・enum）/ switch 式（`yield`）/ 型パターン・record パターン（入れ子を含む）・`case null`・ガード（`when`）/ `synchronized` | — |
| 例外 | Rust の `Result` で伝える（パニックは使わない）。`throw`、try / catch（複数の型・マルチキャッチ）/ finally、try-with-resources（suppressed を含む）、ユーザ定義例外クラス、`getMessage` / `getCause` / `getSuppressed` / `printStackTrace`（スタックトレースの行は出ない）、実行時例外（NPE・配列の範囲外・ゼロ除算・`ClassCastException`・数値の解析失敗など）の catch、ラムダ・比較関数・`toString` などから JDK の処理を通って伝わる例外、クラスの初期化の失敗（`ExceptionInInitializerError`、2 回目以降は `NoClassDefFoundError`）。未捕捉なら Java と同じ表示で終了コード 1 | `StackOverflowError`（Rust ではスタックあふれでプロセスが終わる） |
| クラスの初期化 | Java と同じ: 最初の `new`・static メソッドの呼び出し・static フィールドへのアクセスで、スーパークラスから順に static フィールドの初期化子と static 初期化ブロックをテキストの順に 1 回だけ実行する | — |
| ラムダ | ラムダ式・メソッド参照（static・インスタンス・`this::`・`Type::instanceMethod`・`new`）、ユーザ定義の関数型インタフェース、`java.util.function` の各インタフェース、`Comparator`（`comparing` / `thenComparing` / `reversed` / `nullsFirst` など） | — |
| 文字列 | 連結、`String` の主なメソッド、`String.format` / `printf`（`%d` `%s` `%f` `%x` `%c` `%b` `%e` `%n` `%%`、幅・精度・フラグ）、`String.join`、可変長引数、正規表現（`Pattern` / `Matcher`、`String.matches` / `split` / `replaceAll` / `replaceFirst`。Java の構文: 文字クラス・`\p{..}`・量指定子（最短・強欲を含む）・捕捉 / 名前付き / 非捕捉 / アトミックグループ・後方参照・先読み / 後読み・フラグ。`split` の空文字列の扱いも Java と同じ） | — |
| スレッド | `Thread`（`Runnable` を渡す・継承する・匿名クラス）、`ExecutorService` / `Executors` / `Future` / `Callable`、`CompletableFuture`、`CountDownLatch`、`Semaphore`、`AtomicInteger` / `AtomicLong` / `AtomicBoolean` / `AtomicReference` / `LongAdder`、`ThreadLocal`、`ReentrantLock` / `Condition`、`Object.wait` / `notify`、`TimeUnit`。下記の決定的なスケジューラで実行する | 実際に並列に動くことを前提とするプログラム（下記） |
| テスト | JUnit 5（Jupiter）のテストクラスを `cargo test` のテストに変換する（§6） | `@ParameterizedTest` / `@Nested` / `@RepeatedTest`、`assertTimeout`、JUnit 4 の `Assert` |
| JDK API | `System.out` / `System.err` / `System.in`、`Math`、`Objects`、`Collections`、`Arrays`、ボックス型の static メソッド、`Object` のメソッド（一覧は `translator/j2r-mappings/src/main/resources/j2r/mappings/*.yaml`） | 上記以外（YAML にマッピングを追加すれば使える） |

意味の違い（診断で知らせるものを含む）:
- `String` どうしの `==` は値の比較に変換する（`J2R-LOSSY-STRING-IDENTITY`）。
- スレッドは 1 つの OS スレッドの上で決まった順に実行する（生成コードのオブジェクトは `Rc` で共有し、static
  フィールドはスレッドローカルに持つため）。`start()` / `submit()` はキューに入れるだけで、`join()` / `sleep()` /
  `Future.get()` / `CountDownLatch.await()` などで待つときと main の終わりに、入れた順に最後まで実行する。
  結果は JVM で起こりうる実行順の 1 つと一致し、毎回同じになる。ほかのスレッドが書き換えるフラグを待つ busy wait や、
  タイムアウト・実時間に依存するプログラムは Java と違う動きになる（`Thread.sleep` は実際には待たない）。
- JDK のクラスを継承したクラスでは、JDK の状態を委譲先のオブジェクトが持つ。上書きしたメソッドは、そのクラスの型の
  変数からも、JDK の型（`List` など）の変数からも呼ばれるが、JDK の実装の内部から呼ばれる上書き
  （`LinkedHashMap.removeEldestEntry` など）は呼ばれない。
- 生成コードで再現できないもの:
  - `Object.hashCode()` の既定値（識別ハッシュ）と、それを使う `toString()` の値（`Foo@1b6d3586` の数字）。
  - NullPointerException の詳細メッセージ（JDK 14 以降の `Cannot invoke "..." because "..." is null`）。
    javac の `-g` のデバッグ情報とバイトコードに基づく JVM の機能なので、メッセージは null にする。
  - `StackOverflowError`: Rust ではスタックあふれを捕捉できない（main は 1 GiB のスタックで実行する）。
  - 出力の文字コード: 常に UTF-8（Java はプラットフォームの文字コードに従う）。

## 6. JUnit のテストを変換する

`@Test` を付けたメソッドを持つクラスには、`#[cfg(test)] mod __tests` の中に `#[test]` 関数を生成する。
変換には JUnit の jar をクラスパスに渡す（javac がテストクラスをコンパイルするため）。

```sh
translator/j2r-cli/build/install/j2r/bin/j2r -o out -n mylib \
    -cp junit-jupiter-api-5.11.4.jar:opentest4j-1.3.0.jar src/main/java src/test/java
cd out && cargo test
```

- JUnit と同じく、テストごとに新しいインスタンスを作り、`@BeforeEach` / `@AfterEach` で囲む（スーパークラスのものを含む）。
  `@AfterEach` はテストが失敗しても実行する。`@Disabled` は `#[ignore]` にする。
- `@BeforeAll` / `@AfterAll` はテストごとに実行する（static フィールドはスレッドローカルで、Rust のテストは
  別々のスレッドで動くため、テストごとにクラスの状態が新しくなる）。
- `Assertions` のメソッド（`assertEquals`（delta を含む）/ `assertNotEquals` / `assertTrue` / `assertFalse` /
  `assertNull` / `assertNotNull` / `assertSame` / `assertNotSame` / `assertArrayEquals` / `assertIterableEquals` /
  `assertThrows` / `assertThrowsExactly` / `assertDoesNotThrow` / `assertInstanceOf` / `assertAll` / `fail`）は
  `jrt::junit` に変換する。失敗は JUnit と同じメッセージ（`message ==> expected: <1> but was: <2>`）の
  `AssertionFailedError` で、テストは失敗（パニック）になる。
- `Assumptions.assumeTrue` / `assumeFalse` が成り立たないテストは中断し、`cargo test` では成功として数える
  （JUnit では skipped）。

## 7. 独自の API マッピングを追加する

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
呼ぶ関数が例外を送出しうる（`jrt::JResult<T>` を返す）なら、テンプレートに `?` を付ける
（例: `"my_crate::parse({&0})?"`）。`?` を含むテンプレートを使ったメソッドは、例外を送出しうるメソッドとして変換される。

## 8. Maven について

最初は Maven での利用を検討したが、ビルドは Gradle に統一した。Maven から使いたい場合は、同じ `Translator` API を
呼ぶ Maven プラグイン（`j2r-maven-plugin`）を追加するか、`exec-maven-plugin` で j2r-cli を実行すればよい
（どちらも未実装）。

## 9. Spring Boot + MyBatis のアプリを変換する（--framework spring）

`--framework spring`（Gradle プラグインでは `framework.set("spring")`）を指定すると、jrt の上に変換する代わりに、
Spring Boot + MyBatis のアプリの **`@Mapper`（+ Mapper XML）・`@Service`・それらが使うクラス / record / enum / 例外**を、
sqlx を使う Rust のライブラリ crate に変換する。Web 層（`@Controller` など）と設定クラスは変換しない。

- 型検査に Spring・MyBatis の jar が要るので、コンパイルクラスパスを渡す（Gradle プラグインは自動で渡す）。
- 参照型の null は JSpecify の `@Nullable`（`@NullMarked` のパッケージ）で決める。`@Nullable` の型が `Option<T>` になる。
- 変換規則・変換する範囲・未対応の機能は [07-spring-to-rust.md](07-spring-to-rust.md) の §3・§7。
- 利用例: [examples/todo-app](../examples/todo-app)（`spring-boot/` で `./gradlew translateToRust` を実行すると `rust-core/` を作り直す）。
