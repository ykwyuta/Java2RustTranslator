# 03. 変換規則（Java → Rust）

本書は lowering（JIR → RIR）の仕様である。各規則には **既定（faithful）** と、解析で条件を満たした場合の
**格上げ（idiomatic）** を記す。表現戦略 S0〜S4 は [02-architecture.md §5](02-architecture.md) を参照。

> **現在の実装との対応**: 本書の規則のうち、現在の lowering が生成するのは次の既定表現である（格上げは未実装）。
> 設計当初の `Ref<T>` + `trait CApi` の案は、多重継承（インタフェース）と null の扱いを単純にするため次の形に置き換えた。
>
> | Java | 生成する Rust |
> |---|---|
> | 参照型（String・配列以外） | `JObject`（null は `JObject::null()`）。`String` は `JString`、配列は `JArray<T>`（どちらも null あり） |
> | クラス `C` | `struct C { __super: S, フィールド: Cell<_>/RefCell<_> }` と `impl jrt::Object for C` |
> | インスタンスメソッド `m` | `C::m(this: &JObject, ...)`。オーバーライドがあれば `this.is::<D>()` で分岐するディスパッチ関数 `C::m` と本体 `C::m_impl` |
> | コンストラクタ | `C::new(...) -> JObject`（確保して `__init`）と `C::__init(this, ...)`。オーバーロードは `new_<型>` |
> | インタフェース | 本体を持たない `struct I;` とディスパッチ関数・default メソッド |
> | `enum` / `record` | 定数ごとの `JObject`（`values()` / `valueOf`）/ アクセサと `equals`・`hashCode`・`toString` を生成したクラス |
> | 内部・匿名・ローカルクラス | 外側のクラスのモジュールの入れ子の型。外側のインスタンスは `__outer`、捕捉したローカル変数は `__cap_名前` フィールド |
> | ボクシング | `jrt::box_i32(x)` など（`Integer` のキャッシュも Java と同じ）/ `o.unbox_i32()` |
> | ジェネリクス | 消去して `JObject`。javac が挿入する検査キャストに相当する `checkcast` を入れる |
> | 例外 | 送出しうるメソッドは `JResult<T>` を返し、`?` で伝える（§5）。try は `jrt::try_block(|| -> JResult<Flow<R>> {..})`。`return` / `break` / `continue` は `jrt::Flow` で try の外へ運ぶ |
> | ラムダ・メソッド参照 | `jrt::lambda(&["インタフェース名"], move |args| ...)`。呼び出しは `invoke("メソッド名", &[..])` |
> | 可変長引数 | 呼び出し側で配列にまとめる |

## 1. プリミティブ型と演算

| Java | Rust | 備考 |
|---|---|---|
| `boolean` | `bool` | |
| `byte` | `i8` | |
| `short` | `i16` | |
| `char` | `u16` | Rust の `char`（Unicode スカラ値）とは別物。表示時に `jrt` で UTF-16 → 文字列変換 |
| `int` | `i32` | |
| `long` | `i64` | |
| `float` / `double` | `f32` / `f64` | |
| `void` | `()` | |

演算の意味保存（Rust は debug ビルドで整数オーバーフローがパニックになるため必須）:

| Java | Rust（faithful） |
|---|---|
| `a + b`, `a - b`, `a * b`（整数） | `a.wrapping_add(b)` 等 |
| `-a`（整数） | `a.wrapping_neg()` |
| `a / b`, `a % b`（整数） | `jrt::num::idiv(a, b)?` / `irem` — ゼロ除算で `ArithmeticException`、`MIN / -1` は `MIN` |
| `a << n`, `a >> n` | `a.wrapping_shl(n as u32)` / `wrapping_shr` — シフト量は下位 5/6 ビットでマスク（Java と一致） |
| `a >>> n` | `((a as u32).wrapping_shr(n as u32)) as i32` |
| `a % b`（浮動小数） | `a % b`（両言語とも truncated remainder） |
| `(int) d`, `(long) d` | `d as i32` / `d as i64` — Rust の `as` は飽和・NaN→0 で Java と一致 |
| `(byte) d`, `(short) d`, `(char) d` | `(d as i32) as i8` 等 — Java は一旦 int に変換してから縮小するため 2 段階 |
| `(byte) i` 等の整数縮小 | `i as i8`（ビット切り捨て、Java と一致） |
| `++i`, `i++` | 一時変数を使った式ブロック `{ i = i.wrapping_add(1); i }` |

格上げ: 範囲解析でオーバーフローしないと示せたループカウンタ等は `+` / `+=` を直接使う。

## 2. 参照型全般と null

| 状況 | 表現 |
|---|---|
| nullable な参照（既定） | `Option<Ref<T>>` |
| null 性解析で non-null と証明、または `@NonNull` | `Ref<T>` |
| `x.f()`（x が nullable） | `jrt::nn(&x)?.f()` — `None` なら `NullPointerException` |
| `x == null` | `x.is_none()` |
| `a == b`（参照） | `jrt::same(&a, &b)`（`Rc::ptr_eq` ベース） |
| `a.equals(b)` | `JObject::equals` 経由（ユーザ定義 `equals` を尊重） |

## 3. クラス・継承・インタフェース

### 3.1 基本形
Java の各クラス `C` から次を生成する:
- `struct C` — `C` 自身が宣言したフィールド + スーパークラスを埋め込む `__super: Super`
- `trait CApi` — `C` の非 private インスタンスメソッド（仮想ディスパッチ対象）。`trait CApi: SuperApi` で継承関係を表現
- `impl CApi for C` — メソッド本体。オーバーライドしないメソッドは `__super` へ委譲するコードを生成
- `impl C { fn new(...) -> Ref<C> }` — コンストラクタ（オーバーロードは名前修飾）

```java
class Animal {
    protected String name;
    Animal(String name) { this.name = name; }
    String speak() { return "..."; }
    String describe() { return name + ": " + speak(); }
}
class Dog extends Animal {
    Dog(String name) { super(name); }
    @Override String speak() { return "Woof"; }
}
```
```rust
pub struct Animal { name: RefCell<JString> }
pub trait AnimalApi: JObject {
    fn __animal(&self) -> &Animal;                    // フィールドアクセス用
    fn speak(&self) -> JString { JString::from("...") }
    fn describe(&self) -> JString {
        jrt::concat!(self.__animal().name.borrow().clone(), ": ", self.speak())
    }
}
impl Animal {
    pub fn new(name: JString) -> Ref<Animal> { Ref::new(Animal::__init(name)) }
    pub(crate) fn __init(name: JString) -> Animal { Animal { name: RefCell::new(name) } }
}
impl AnimalApi for Animal { fn __animal(&self) -> &Animal { self } }

pub struct Dog { __super: Animal }
impl Dog {
    pub fn new(name: JString) -> Ref<Dog> { Ref::new(Dog { __super: Animal::__init(name) }) }
}
impl AnimalApi for Dog {
    fn __animal(&self) -> &Animal { &self.__super }
    fn speak(&self) -> JString { JString::from("Woof") }
}
```
- 静的型が `Animal` の変数は `Ref<dyn AnimalApi>`、`final` クラスや CHA でサブクラスが無いクラスは `Ref<Animal>`（静的ディスパッチ）。
- メソッド本体は **トレイトのデフォルトメソッド** に置くことで、サブクラスは上書きしたいものだけ実装すればよい
  （Java の仮想ディスパッチと `this` の動的型の意味論が保たれる）。
- `super.m()` 呼び出しは、スーパークラス実装を `fn __animal_speak(this: &dyn AnimalApi)` のような
  自由関数に切り出しておき、それを直接呼ぶ。
- `abstract` メソッドはデフォルト実装のないトレイトメソッド。
- 格上げ: `sealed` 階層、または CHA で閉じた階層は `enum` + `match` に変換できる（`--mode=idiomatic`）。

### 3.2 インタフェース
- `interface I` → `trait I: JObject`。`default` メソッド → トレイトのデフォルトメソッド。
- `static` メソッド → モジュールレベルの関数。
- 関数型インタフェース（`@FunctionalInterface` または SAM）→ トレイト + クロージャ用のブランケット実装:
  ```rust
  pub trait Comparator<T> { fn compare(&self, a: &T, b: &T) -> i32; }
  impl<T, F: Fn(&T, &T) -> i32> Comparator<T> for F { fn compare(&self, a: &T, b: &T) -> i32 { self(a, b) } }
  ```

### 3.3 `Object` のメソッド
- すべての生成型は `jrt::JObject` を実装: `equals`, `hash_code`, `to_string`, `get_class_name`, `as_any`。
- ユーザが `equals`/`hashCode` をオーバーライドした場合、`PartialEq`/`Eq`/`Hash` もそれに委譲して実装し、
  `HashMap` 等で Java と同じ振る舞いにする。

### 3.4 キャスト・`instanceof`
- アップキャスト: 暗黙（`Rc<Dog>` → `Rc<dyn AnimalApi>` の unsized coercion）。
- ダウンキャスト `(Dog) a` → `jrt::cast::<Dog>(&a)?`（`as_any()` + `Rc::downcast`、失敗時 `ClassCastException`）。
- `a instanceof Dog d` → `if let Some(d) = jrt::try_cast::<Dog>(&a) { ... }`。
- インタフェースへのダウンキャストは型ごとに生成する「インタフェース表」（`jrt::iface_table!`）で解決。

### 3.5 フィールド
| フィールド | 表現 |
|---|---|
| `final` | 素の値 |
| 非 final かつ Copy 型（プリミティブ） | `Cell<T>` |
| 非 final かつ参照型 | `RefCell<T>`（読み出しは `borrow().clone()` で即座に借用解放） |
| `static final` 定数（コンパイル時定数） | `pub const` |
| その他の `static` | `thread_local!` + `jrt::init` による遅延初期化（S4 では `static` + `OnceLock`/`Mutex`） |

### 3.6 内部クラス・匿名クラス・ローカルクラス
- `LiftInnerClasses` パスで捕捉変数（外側インスタンス `this$0`、実質 final なローカル変数）を明示フィールドに持つ
  トップレベル struct にしてから通常規則で変換する。
- 外側インスタンスへの参照が循環を生む典型パターン（リスナが外側を参照し、外側がリスナを保持）は、
  `J2R-PERF-CYCLE` 診断を出し、`@Weak` 注釈での回避を案内する。

### 3.7 `enum`・`record`
- `enum`（フィールド・メソッドを持たない）→ Rust の `enum` + `#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]`。
  `ordinal()`, `name()`, `values()` は生成。
- フィールド付き `enum` → Rust `enum` + 定数データを返すメソッド（または `static` 配列）。
- `record` → S1（`Rc<T>`）または S2（値型、`#[derive(Clone, PartialEq, Eq, Hash)]`）。

## 4. ジェネリクス

- 型パラメータ `<T>` → Rust の型パラメータ `<T>`。境界 `<T extends Comparable<T>>` → `T: Comparable<T>`。
- Java の参照型引数は Rust 上では `Ref<_>` / `Option<Ref<_>>` であり `Clone` なので、全型パラメータに
  既定で `T: Clone + JObject + 'static` を付ける。
- ワイルドカード `List<? extends Animal>` → 要素型を `Ref<dyn AnimalApi>` とする。`? super` は使用箇所に応じて
  型パラメータへ昇格、不可能なら `Ref<dyn JObject>`。
- raw 型、型消去に依存したコード（`(T) obj` の unchecked キャスト）→ `Ref<dyn JObject>` + 実行時キャスト、
  `J2R-LOSSY-ERASURE` 診断。
- ジェネリックメソッドの仮想ディスパッチ（トレイトのジェネリックメソッドは object-safe でない）→ 該当メソッドを
  `where Self: Sized` で分離するか、型引数を `dyn JObject` に消去したブリッジを生成。

## 5. 例外

Java の例外は Rust の `Result` で伝える。パニックは Java の例外には使わない（変換できなかったコードの `todo!()` と、
ランタイムの内部エラーだけ）。

### 5.1 基本方式
- 例外は `JObject`（`Throwable` のサブクラスのオブジェクト）。ユーザ定義例外クラスも通常のクラス規則で生成し、
  ルートの構造体に `jrt::lang::throwable::Throwable`（メッセージ・原因・suppressed）を持たせる。
- 例外を送出しうるメソッドは `JResult<T>`（= `Result<T, JObject>`）を返す。checked / unchecked の区別はしない。
  送出しえないメソッドは素の `T` を返す（下の「例外フロー解析」）。
- `throw e;` → `return jrt::throw_obj(e);`（e が null なら NullPointerException）。`throw new X(..)` は `return Err(..)`。
- 送出しうるメソッドの呼び出し → `callee(...)?`。`return v` → `return Ok(v)`。
- `try { A } catch (IOException e) { B } finally { C }` → 本体を `JResult<Flow<R>>` を返すクロージャにする
  （クロージャの中の `?` はクロージャが受け止める）。`return` / `break` / `continue` は `jrt::Flow` で外へ運ぶ:
  ```rust
  let __t0 = jrt::try_block(|| -> JResult<jrt::Flow<R>> {
      match jrt::try_block(|| -> JResult<jrt::Flow<R>> { A; Ok(jrt::Flow::Normal) }) {
          Ok(__f) => Ok(__f),
          Err(__e) => {
              if __e.instance_of("java.io.IOException") { let e = __e; B; Ok(jrt::Flow::Normal) }
              else { return Err(__e); }
          }
      }
  });
  C;
  match __t0 {
      Ok(jrt::Flow::Return(__v)) => return Ok(__v),
      Ok(_) => {}
      Err(__e) => return Err(__e),
  }
  ```
- try-with-resources は JLS 14.20.3 のとおり try/catch/finally に展開し、`close()` の例外は本体の例外の suppressed に加える。
- 未捕捉の例外は `jrt::run_main` が Java と同じ `Exception in thread "main" X: msg`（`Caused by:` / `Suppressed:` を含む）
  を標準エラーに出し、終了コード 1 で終わる（スタックトレースの行は出ない）。

### 5.2 ランタイム例外
- NPE・配列の範囲外・ゼロ除算・`ClassCastException`・`NegativeArraySizeException`・数値の解析失敗なども `Err` として返すので、
  Java と同じく catch で捕捉できる。`jrt` の操作は例外を送出しうるものが `JResult` を返す
  （`a.get(i)?`、`jrt::num::div_i32(a, b)?`、`x.nn()?`（null 検査）、`o.unbox_i32()?`、`o.checkcast("C")?` など）。
- 0 でない定数での整数の除算（`x / 2`）は例外にならないので `wrapping_div` にする。
- ユーザーのコードを実行する JDK の処理（`toString` / `equals` / `hashCode` / `compareTo`、比較関数・ラムダを受け取るコレクションの
  操作、文字列連結での参照型の文字列化）も `JResult` を返し、ユーザーのコードの例外をそのまま伝える。
- static 初期化（static フィールドの初期化子・static 初期化ブロック・enum 定数の生成）で起きた例外は、Java の未捕捉の
  `ExceptionInInitializerError` と同じ表示をして終了する（static フィールドは最初に使われたときに初期化するので、
  呼び出し元へ伝えられない。`ExceptionInInitializerError` の catch は再現しない）。
- `StackOverflowError` / `OutOfMemoryError` は再現しない（Rust ではプロセスが異常終了する）。

### 5.3 例外フロー解析
どのメソッドが `JResult` を返すかは、lowering の結果から固定点で求める（`j2r-lowering` の `Throwing`）。

1. 最初はどのメソッドも例外を送出しないと仮定して、プログラム全体を変換する。
2. 関数の本体（try のクロージャの外）に `?` や `return Err(..)` を生成したのに `JResult` を返さない関数があれば、
   そのメソッドを「送出しうる」集合に加えて、もう一度変換する。
3. 加えるものがなくなったら終わり（集合は単調に増えるので、呼び出しの深さ程度の回数で止まる）。

オーバーライドの関係にあるメソッド（とそれが上書きする JDK のメソッド）は、振り分け関数から同じ形で呼ぶため 1 つの系列として扱い、
系列のどれかが送出しうるなら系列のすべてが `JResult` を返す。`impl jrt::Object` の `to_jstring` などは常に `JResult` を返し、
上書きしたメソッドが `JResult` を返さなければ `Ok(..)` で包む。ラムダの本体は常に `JResult<JObject>` を返す。

## 6. 文字列

- `String` → `jrt::JString`（不変・`Rc<str>` 相当で clone が安価）。`length()` / `charAt()` / `substring()` は Java の
  UTF-16 単位の意味論で実装（ASCII のみの文字列は高速経路）。
- 文字列リテラル → `jrt::jstr!("...")`（静的領域を参照）。
- 連結 → `jrt::concat!(...)`（`DesugarStringConcat` の結果）。数値・`char`・`null` の文字列化規則は Java に合わせる
  （`double` の表示形式 `1.0E10` 等も `jrt` で再現）。
- `StringBuilder` → `jrt::lang::StringBuilder`（格上げ: 非共有なら `String` + `push_str`）。
- `switch` on String → `match s.as_str()`。

## 7. 配列

- `T[]` → `jrt::JArray<T>`（`Rc<[Cell<T>]>` / `Rc<[RefCell<T>]>`、固定長・共有・境界外で `ArrayIndexOutOfBoundsException`）。
- `int[][]` → `JArray<Option<JArray<i32>>>`（Java の多次元配列は配列の配列なので同じ構造）。
- 格上げ: エスケープしない局所配列 → `Vec<T>` / `[T; N]`、境界チェックが自明なループは直接添字。

## 8. 制御構文

| Java | Rust |
|---|---|
| `if` / `while` / `do-while` | `if` / `while` / `loop { ...; if !cond { break } }` |
| 基本 `for` | `loop` + 明示的な更新（`continue` が更新を飛ばさないよう、更新をラベル付きブロック後に配置） |
| 拡張 `for`（配列） | `for i in 0..arr.len()`（格上げ: `for x in arr.iter()`） |
| 拡張 `for`（Iterable） | `let it = c.iterator(); while it.has_next()? { let x = it.next()?; ... }`（格上げ: Rust イテレータ） |
| ラベル付き `break`/`continue` | `'label: loop` |
| `switch`（fall-through あり） | ラベル付きブロックの連鎖に展開 |
| `switch`（fall-through なし）/ switch 式 | `match` |
| パターン `switch`（sealed） | `match` + ダウンキャスト、格上げ時は enum の `match` |
| 三項演算子 | `if c { a } else { b }` |
| `synchronized` | S4 の型では `jrt::monitor(&obj).lock()` のガード、単一スレッドと判定されれば除去 |

## 9. ラムダ・メソッド参照

- ラムダ → Rust クロージャ。関数型インタフェース型の値としては `Ref<dyn FnTrait>`（`Rc<dyn ...>`）。
- 捕捉する実質 final なローカル変数は `clone()` して `move` クロージャへ。
- メソッド参照 `Foo::bar`, `obj::bar`, `Foo::new` は `NormalizeLambdas` でラムダ化してから変換。
- Stream API: `jrt::stream` の最小実装で意味保存変換。格上げ: `map/filter/collect(toList())` 程度のパイプラインは
  Rust の `Iterator` チェーンに変換。

## 10. パッケージ・可視性・プログラムエントリ

| Java | Rust |
|---|---|
| `package a.b` | `mod a { mod b { ... } }`（ファイル `src/a/b/mod.rs` / `src/a/b/<class>.rs`） |
| `import` | `use crate::a::b::X;`（未使用は RIR パスで除去） |
| `public` | `pub` |
| `protected` / パッケージプライベート | `pub(crate)`（Rust にパッケージ単位の可視性がないため緩める。`J2R-LOSSY-VISIBILITY` は出さない） |
| `private` | 非 `pub`（ただし内部クラスからアクセスされるものは `pub(crate)`） |
| `public static void main(String[] args)` | `src/main.rs` の `fn main()` から `jrt::run_main(|args| X::main(args))` を呼ぶ（未捕捉例外は Java 同様に stderr に出して終了コード 1） |

## 11. テストコード

- JUnit 5 / 4 の `@Test` メソッド → `#[test] fn`（`tests/` 配下）。`assertEquals` 等は `jrt::junit` の関数へ。
- `@BeforeEach` / `@AfterEach` → 各テスト関数の前後に呼び出しを生成。
- `@ParameterizedTest` 等は v1 対象外。

## 12. 未対応機能の扱い

| 機能 | 扱い |
|---|---|
| リフレクション（`Class.forName`, `getMethod` 等） | `todo!("J2R-UNSUPPORTED-REFLECTION")` + 診断 |
| 動的プロキシ、`invokedynamic` の直接利用 | 同上 |
| JNI (`native`) | `extern` 関数宣言の雛形を生成 |
| `finalize`, `WeakReference`, `SoftReference` | `Drop` / `rc::Weak` に近似、`J2R-LOSSY-GC` |
| アノテーションプロセッサ生成コード | javac に処理させた後の生成ソースを入力に含める（`-s` ディレクトリ） |
| スレッド | v1: `Thread`/`Runnable`/`synchronized`/`ExecutorService` の基本のみ（S4）。`java.util.concurrent` の大半は後続 |
