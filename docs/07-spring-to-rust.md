# 07. Spring Boot Web アプリの Rust への移行（標準構成と変換規則）

Spring Boot 4.1 + Spring MVC + MyBatis + Thymeleaf + PostgreSQL で作った Web アプリを Rust に移すときの
**移行先の標準構成** と、層ごとの **変換規則** をまとめる。題材として同じ Todo 管理アプリを両方で実装した。

- 移行元: [examples/todo-app/spring-boot](../examples/todo-app/spring-boot)
- 移行先: [examples/todo-app/rust](../examples/todo-app/rust)（Web 層。手で変換）と
  [examples/todo-app/rust-core](../examples/todo-app/rust-core)（Mapper・サービス・ドメイン。**j2r で自動変換**）
- 動かし方: [examples/todo-app/README.md](../examples/todo-app/README.md)

j2r は `--framework spring` で、**`@Mapper`（+ MyBatis の Mapper XML）・`@Service`・それらが使うクラス / record / enum / 例外**を
sqlx を使う Rust のライブラリ crate に変換する（[§7](#7-トランスパイラでの変換--framework-spring)）。
Web 層（`@Controller`・フォーム・Thymeleaf のテンプレート）と設定クラスはまだ変換せず、この文書の規則に沿って手で書く。

## 1. 移行先の標準構成

| 移行元（Java） | 移行先（Rust） | 選んだ理由 | 検討したほかの候補 |
|---|---|---|---|
| Spring MVC（DispatcherServlet・`@Controller`） | **axum 0.8** | tokio / tower / hyper 上に作られた事実上の標準。ハンドラが普通の async 関数で、引数の型（抽出子）で `@RequestParam` などを表せる | actix-web（性能は同等だが独自ランタイム）、Loco（Rails 風のフルスタック。規約が Spring と違いすぎる） |
| 組み込み Tomcat・スレッドプール | **tokio** | axum・sqlx が前提とする非同期ランタイム | — |
| MyBatis（Mapper・XML の SQL・動的 SQL） | **sqlx 0.9** | MyBatis と同じ「SQL を自分で書き、結果を型に対応付ける」方式。`QueryBuilder` で `<where>` `<if>` を書ける | Diesel・SeaORM（どちらも ORM で、JPA / Hibernate の移行先に向く）、tokio-postgres（低水準すぎる） |
| HikariCP（DataSource） | **sqlx `PgPool`** | sqlx に内蔵 | deadpool-postgres |
| `@Transactional` | **sqlx `Transaction`**（`pool.begin()` 〜 `commit()`） | 明示的に書く。`commit()` せずに抜けると drop でロールバック | — |
| `spring.sql.init`（schema.sql）/ Flyway | **`sqlx::migrate!`**（migrations/） | 適用済みのバージョンを記録する点は Flyway と同じ | refinery |
| Thymeleaf | **askama 0.16**（+ askama_web） | Jinja 風の構文。テンプレートをコンパイル時に Rust に変換するので、モデルの属性名の誤りや型の誤りがビルドで分かる | minijinja・Tera（実行時に読むので再ビルドなしで直せるが、型検査がない）、maud（HTML を Rust のマクロで書く。デザイナーが触れない） |
| Bean Validation（`@NotBlank` `@Size`） | **validator 0.21**（`#[derive(Validate)]`） | アノテーションと同じく宣言的に書ける | garde |
| フォームのバインド（`@ModelAttribute`） | **serde + `axum::Form`** | — | — |
| `RedirectAttributes#addFlashAttribute` | **tower-sessions + axum-messages** | セッションに入れて次のリクエストで 1 回だけ読む仕組みが同じ | 独自の Cookie |
| 静的リソース（`static/`） | **tower-http `ServeDir`** | — | — |
| `application.yml` | **config 0.15**（config/application.toml + 環境変数） | ファイルと環境変数を重ねる点が同じ | figment、dotenvy |
| SLF4J + Logback・アクセスログ | **tracing + tracing-subscriber**、tower-http `TraceLayer` | — | log + env_logger |
| 例外・`@ControllerAdvice` | **thiserror** のエラー型 + `IntoResponse` | ハンドラが `Result` を返し、`Err` をレスポンスに変える | anyhow（型で分岐できない） |
| DI コンテナ（Bean） | **`AppState` + `State` 抽出子** | コンテナを使わず、起動時に組み立てて Router に渡す | shaku 等の DI crate（使われていない） |
| `java.time` | **chrono** | sqlx の `chrono` 機能で `DATE` / `TIMESTAMP` と対応する | time |
| MockMvc / `@SpringBootTest` | **`tower::ServiceExt::oneshot`** / **`#[sqlx::test]`** | Router をサーバなしで呼べる。`#[sqlx::test]` はテストごとに空の DB を作る | Testcontainers（どちらでも使える） |

Rust は 1.94 以上（sqlx 0.9 の MSRV）。

## 2. ファイルの対応

| Spring Boot 版 | Rust 版 | |
|---|---|---|
| `build.gradle.kts` | `rust/Cargo.toml`、`rust-core/Cargo.toml` | rust-core は生成 |
| `TodoApplication.java`（`@SpringBootApplication`） | `rust/src/main.rs`、`rust/src/lib.rs`（自動構成に当たる組み立て） | 手書き |
| `application.yml` | `rust/config/application.toml`、`rust/src/lib.rs` の `AppConfig` | 手書き |
| `schema.sql` | `rust-core/migrations/0001_schema.sql`、`rust-core/src/lib.rs` の `MIGRATOR` | 生成 |
| `ClockConfig.java`（`Clock` の Bean） | `rust-core/src/clock.rs`（`Clock` トレイト。j2r の補助モジュール） | 生成 |
| `domain/Todo.java`・`domain/TodoFilter.java` | `rust-core/src/domain/todo.rs`・`todo_filter.rs` | 生成 |
| `mapper/TodoMapper.java` + `mapper/TodoMapper.xml` | `rust-core/src/mapper/todo_mapper.rs`（+ 動的 SQL の補助 `mybatis.rs`） | 生成 |
| `service/TodoService.java`・`TodoSummary.java` | `rust-core/src/service/todo_service.rs`・`todo_summary.rs` | 生成 |
| `service/TodoNotFoundException.java` | `rust-core/src/error.rs` の `Error::TodoNotFound` | 生成 |
| `web/TodoController.java`・`HomeController.java` | `rust/src/web/todo_controller.rs`・`home_controller.rs`、ルートは `rust/src/web/mod.rs` | 手書き |
| `web/TodoForm.java`（+ `messages.properties`） | `rust/src/web/todo_form.rs` | 手書き |
| `web/GlobalExceptionHandler.java` | `rust/src/web/global_exception_handler.rs` | 手書き |
| `templates/**/*.html`（モデルは `Model`） | `rust/templates/**/*.html` + `rust/src/web/views.rs`（テンプレートごとの構造体） | 手書き |
| `templates/fragments/layout.html` | `rust/templates/base.html`（`{% extends %}` と `{% block %}`） | 手書き |
| `static/css/app.css` | `rust/static/css/app.css`（同じファイル） | — |
| `TodoControllerTest.java` | `rust/tests/todo_controller_test.rs` | 手書き |

パッケージ（`domain` / `mapper` / `service`）はそのままモジュールにし、クラスはファイル（snake_case）に対応させる。
`@SpringBootApplication` のクラスのパッケージ（`com.example.todo`）が crate のルートになる。

## 3. 変換規則

### 3.1 アプリの組み立て（Bean・自動構成）

Spring が自動構成する DataSource・DispatcherServlet・静的リソース・セッションを、`src/lib.rs` で明示的に組み立てる。
Bean は `AppState` のフィールドになり、ハンドラは `State<AppState>` で受け取る。

```rust
#[derive(Clone)]
pub struct AppState {
    pub todo_service: TodoService,   // @Service
    pub clock: Arc<dyn Clock>,       // @Bean Clock
}

pub fn app(state: AppState) -> Router {
    web::routes()
        .with_state(state)
        .nest_service("/css", ServeDir::new(".../static/css"))  // static/
        .layer(axum_messages::MessagesManagerLayer)           // フラッシュ属性
        .layer(SessionManagerLayer::new(MemoryStore::default())) // HttpSession
        .layer(TraceLayer::new_for_http())
}
```

テストで差し替える Bean（`Clock`）はトレイトオブジェクトにする（`@Primary` の Bean で差し替える代わりに
`AppState::with_clock` に別の実装、たとえば `todo_core::clock::FixedClock` を渡す）。
`TodoService` などのサービスは j2r が生成した todo_core のものを使う。

### 3.2 コントローラ

| Spring MVC | axum |
|---|---|
| `@GetMapping("/todos/{id}/edit")` | `.route("/todos/{id}/edit", get(edit_form))`（ルートは 1 か所にまとめる） |
| `@PathVariable long id` | `Path(id): Path<i64>` |
| `@RequestParam(required = false) String filter` | `Query<Params>`（`filter: Option<String>`）。POST の本体なら `Form<Params>` |
| `@Valid @ModelAttribute TodoForm form, BindingResult result` | `Form(form): Form<TodoForm>` + `form.validated()` → `Result<TodoInput, FieldErrors>` |
| `Model` + ビュー名 `"todos/list"` | テンプレートの構造体（`TodoListView { todos, summary, ... }`）を返す |
| `"redirect:/todos"` | `redirect("/todos")`（302 を返す関数。§5） |
| `redirectAttributes.addFlashAttribute("message", ...)` | `messages.info(...)`（`Messages` 抽出子）。読む側は `messages.into_iter()` |
| `redirectAttributes.addAttribute("filter", ...)` | リダイレクト先の URL に自分で付ける |
| 例外を投げる | `Err(AppError)` を返す（ハンドラの戻り値は `Result<_, AppError>`。サービスの `Error` から `?` で変換） |

### 3.3 フォームと検証

- フォームの `name` 属性（`dueDate` など）は変えない。`#[serde(rename_all = "camelCase")]` で Rust のフィールド名と対応させる。
- Bean Validation のアノテーションは validator の属性にする。`@NotBlank` に当たるものはないので関数で書く。

  | Bean Validation | validator |
  |---|---|
  | `@NotBlank(message = "...")` | `#[validate(custom(function = "not_blank", message = "..."))]` |
  | `@Size(max = 100, message = "...")` | `#[validate(length(max = 100, message = "..."))]` |

- 型変換（`@DateTimeFormat` の `LocalDate`）はフォームでは文字列のまま受け取り、検証のときに変換する。
  変換に失敗した値を画面に戻せるようにするためで、Spring の `typeMismatch`（`messages.properties`）に当たる。
- `BindingResult` は `FieldErrors`（フィールド名 → メッセージの一覧）にし、テンプレートでは
  `#fields.errors('title')` を `errors.of("title")`、`th:errorclass` を `errors.has("title")` にする。
- `th:field` のチェックボックスが出力する `_done` の隠しフィールドは、serde が知らないフィールドとして無視する。

### 3.4 サービスとトランザクション（j2r が変換）

```rust
#[derive(Clone)]
pub struct TodoService {
    pool: PgPool,              // Mapper のフィールドは接続プール 1 つにまとめる
    clock: Arc<dyn Clock>,     // ほかの注入されるフィールドはそのまま
}
```

- コンストラクタ注入のコンストラクタは `new` になる（Mapper の引数は `pool: PgPool` 1 つにまとめる）。
- `@Transactional` の public メソッドは `let mut tx = self.pool.begin().await?; ...; tx.commit().await?;` で囲む。
  `?` で途中で抜けると `Transaction` の drop でロールバックされ、実行時例外でロールバックする Spring の既定と同じになる。
  クラスに付いた `@Transactional` は public メソッドすべてに効く。`readOnly = true` もトランザクションを張る（doc コメントに残す）。
- `@Transactional` の付いていないメソッドは `self.pool.acquire()` の接続を使う（Mapper の呼び出しごとに自動コミット）。
- データベースを使うメソッドは `async fn` で `Result<T>`（`crate::error::Result`）を返す。使わないメソッドは普通の `fn`。
- サービスの中から呼ぶメソッド（`update` の中の `findById(id)` など）には、呼び出し元の接続を受け取る版
  `find_by_id_in(&self, conn: &mut PgConnection, ...)` も作り、中からの呼び出しはそちらを使う。
  Spring の自己呼び出しがプロキシを通らず、呼び出し元と同じトランザクションで動くのと同じになる。
  途中に return があるメソッドも、本体をこの版に置いて、public な版がトランザクションを張って呼ぶ。
- 例外クラスは `crate::error::Error` のバリアントになる。コンストラクタの引数がバリアントの値に、
  `super("Todo not found: id=" + id)` が thiserror の `#[error("Todo not found: id={0}")]` になる。
  データベースのエラー（Spring の `DataAccessException`）は `Error::Database(sqlx::Error)`。
- `throw new X(...)` は `return Err(Error::X(...))`、`Optional#orElseThrow(() -> new X(...))` は
  `.ok_or_else(|| Error::X(...))?`（戻り値ならそのまま `Result` を返す）。

### 3.5 Mapper（MyBatis → sqlx。j2r が変換）

Mapper インタフェースと XML は、接続を引数に取る関数の集まり（モジュール）にする。
MyBatis の Mapper は Spring のトランザクションに暗黙に参加するが、Rust では接続（`&mut PgConnection`）を明示的に渡す。
プールの接続もトランザクションも `&mut PgConnection` として渡せる。

```rust
pub async fn find_by_id(conn: &mut PgConnection, id: i64) -> sqlx::Result<Option<Todo>> {
    sqlx::query_as::<_, Todo>("SELECT ... FROM todos WHERE id = $1").bind(id).fetch_optional(conn).await
}
```

| MyBatis | sqlx |
|---|---|
| `#{title}`（引数が 1 つのエンティティならそのプロパティ、`@Param("x")` なら引数 x） | `$1` + `.bind(&todo.title)` |
| enum の引数 `#{filter}` | `.bind(filter.name())`（MyBatis の EnumTypeHandler と同じく名前を渡す） |
| `resultType="Todo"` + `map-underscore-to-camel-case` | `query_as::<_, Todo>` と `#[derive(sqlx::FromRow)]`（Rust のフィールドも snake_case なので列名と一致する） |
| 戻り値 `List<T>` / `Optional<T>`・`@Nullable T` / `T` | `fetch_all` / `fetch_optional` / `fetch_one`（1 列の値は `query_scalar`） |
| `<insert useGeneratedKeys="true" keyProperty="id">`・`@Options(useGeneratedKeys = true, ...)` | `RETURNING id` を受け取って `todo.id` に入れる（引数は `&mut Todo`） |
| 更新系の戻り値 `int` / `long` / `boolean` / `void` | `rows_affected()` を `as i32` / `as i64` / `> 0` / 捨てる |
| `<sql id>` + `<include refid>` | 変換時に展開する |
| `<where>` `<set>` `<if test>` `<choose>` | `QueryBuilder` に断片を足していく（`mybatis::Clause` が最初だけ `WHERE` / `SET` を付け、以降は `AND` / `OR` / `,`） |
| `@Select` / `@Insert` / `@Update` / `@Delete`（注釈の SQL） | XML と同じ |

`test` 属性の OGNL は、`and` / `or` / `not`、比較（`==` `!=` `<` `>` `eq` `gte` など）、`null`・文字列・数値のリテラル、
引数とプロパティの参照、引数なしのメソッド呼び出し（`name()` `size()` `isEmpty()`）に対応する。
`@Nullable` な引数は `Option` なので、`keyword != null and keyword != ''` は `keyword.is_some() && keyword != Some("")` になる。

sqlx 0.9 は SQL インジェクション対策として、`query()` に渡す SQL を文字列リテラル（`&'static str`）に限る
（`format!` で作った文字列はコンパイルエラーになる）。j2r は SQL を文字列リテラルとして生成し、
動的 SQL は `QueryBuilder`（値は `push_bind`）で組み立てる。

SQL を DB に対してコンパイル時に検査する `query_as!` マクロもあるが、ビルドに DB（または `cargo sqlx prepare` で作る
`.sqlx/`）が要るので、実行時に対応付ける `query_as` を使っている。

### 3.6 ドメインクラス・null（j2r が変換）

参照型の null は **JSpecify の注釈**で決める。Spring Framework 7 / Spring Boot 4 と同じく、パッケージに
`@NullMarked`（`package-info.java`）を付け、null になりうる箇所にだけ `@Nullable` を付ける。
j2r は `@Nullable` の付いた型を `Option<T>` にし、付いていない参照型は null にならない値として扱う。

| Java | Rust |
|---|---|
| getter / setter のある POJO | 公開フィールドの `struct`。getter / setter は消え、呼び出しはフィールドの読み書きになる |
| `@Nullable String description`、`@Nullable LocalDate dueDate` | `Option<String>`、`Option<NaiveDate>` |
| `Long id`（INSERT 前は未設定） | `i64`（`Default` で 0。INSERT で生成されたキーが入る） |
| `new Todo()` のあとに setter が続く | `Todo { title: ..., ..Default::default() }` |
| `LocalDate` / `LocalDateTime` / `java.time.Clock` | `chrono::NaiveDate` / `NaiveDateTime` / `Arc<dyn Clock>`（`LocalDate.now(clock)` → `clock.today()`） |
| `enum` | Copy な `enum` と `values()`・`name()`・`ordinal()` |
| `record TodoSummary(long active, long completed)` | `#[derive(Clone, Copy, ...)] struct TodoSummary`（アクセサの呼び出しはフィールドの読み出し） |
| 引数の `String` / エンティティ / `List<T>` | `&str` / `&Todo` / `&[T]`（借用）。戻り値・フィールド・ローカル変数は所有する値 |

型を合わせるための変換（`.to_string()`、`&x`、`Some(x)`、`as_deref()`、`.clone()`）は j2r が入れる。
ローカル変数は最後に使うところで移動し、それより前は clone する。null の検査はパターンにする。

| Java | Rust |
|---|---|
| `!done && dueDate != null && dueDate.isBefore(today)` | `!self.done && self.due_date.is_some_and(\|due_date\| due_date < today)` |
| `keyword == null ? null : keyword.strip()` | `keyword.map(\|keyword\| keyword.trim())` |
| `if (value == null) { return ALL; }` | `let Some(value) = value else { return TodoFilter::All; };` |
| `x.getAuthor() == null ? a : b` | `if let Some(author) = x.author.as_deref() { b } else { a }` |

### 3.7 テンプレート（Thymeleaf → askama）

Model の属性は、テンプレートごとの構造体（`src/web/views.rs`）のフィールドになる。テンプレートの記法は次のように置き換える。

| Thymeleaf | askama |
|---|---|
| `th:text="${todo.title}"` | `{{ todo.title }}`（HTML エスケープは既定で有効） |
| `th:if="${todo.description}"`（null でない） | `{% if let Some(description) = todo.description %}` |
| `th:if` / `th:unless` | `{% if %}` / `{% else %}` |
| `th:each="todo : ${todos}"` | `{% for todo in todos %}` |
| `th:classappend="${todo.done} ? 'done'"` | `class="todo{% if todo.done %} done{% endif %}"` |
| `@{/todos(filter='all', q=${q})}` | `/todos?filter=all&amp;q={{ q\|urlencode }}` |
| `@{/todos/{id}/edit(id=${todo.id})}` | `/todos/{{ todo.id }}/edit` |
| `${#temporals.format(todo.dueDate, 'yyyy/MM/dd')}` | `{{ due_date.format("%Y/%m/%d") }}` |
| `${todo.isOverdue(today)}` | `todo.is_overdue(*today)`（フィールドは参照で渡るので `*` で値にする） |
| `th:replace="~{fragments/layout :: head('一覧')}"` | `{% extends "base.html" %}` + `{% block title %}一覧{% endblock %}` |
| `th:field="*{title}"` | `id="title" name="title" value="{{ form.title }}"` を自分で書く |
| `${message} ?: '既定'` | `{% if let Some(message) = message %}...{% else %}既定{% endif %}` |

### 3.8 例外ハンドラ・エラーページ

- `@ControllerAdvice` の `@ExceptionHandler` は、サービスのエラー型（`todo_core::error::Error`）を包む `AppError` に
  `IntoResponse` を実装して書く（`TodoNotFound` → 404 と `error/404.html`、DB エラー → ログを出して 500）。
  別の crate の型に別の crate のトレイトは実装できないので、Web 層の型で包む。
- Spring Boot がどのマッピングにも当たらないリクエストに `templates/error/404.html` を返すのは、
  `Router::fallback` で同じテンプレートを返して再現する。

### 3.9 テスト

- MockMvc は、`app(state)` で作った `Router` を `tower::ServiceExt::oneshot` で直接呼ぶ。
  フラッシュ属性を確かめるには、セッション Cookie を次のリクエストに持ち回る（`tests/todo_controller_test.rs` の `Client`）。
- 実 DB を使う `@SpringBootTest` は `#[sqlx::test(migrator = "todo_core::MIGRATOR")]` にする。テストごとに空の DB を作って
  マイグレーションを流すので、`@BeforeEach` で表を空にする処理は要らない（`DATABASE_URL` の利用者に CREATEDB 権限が必要）。

## 4. 移行で変えないもの

画面・URL・フォームの `name` 属性・リダイレクト先・ステータスコード・メッセージ文言は変えない。
[examples/todo-app/compare.sh](../examples/todo-app/compare.sh) が両方のアプリに同じ 25 個のリクエストを送り、
ステータス・リダイレクト先・HTML（空白の違いと文字参照の書き方の違いだけを吸収）が一致することを確かめる。

## 5. 挙動の違いと注意点

| 項目 | Spring | Rust 版での扱い |
|---|---|---|
| リダイレクトのステータス | `redirect:` は 302 Found | axum の `Redirect::to` は 303 なので、302 を返す `web::redirect` を用意した |
| 初回リダイレクトの URL | セッション作成直後は `;jsessionid=...` を付ける | Spring 側で `server.servlet.session.tracking-modes: cookie` にして付けないようにした |
| HTML エスケープ | `&lt;` `&amp;` | `&#60;` `&#38;`（ブラウザでの表示は同じ） |
| 文字数の数え方 | `@Size` は UTF-16 のコード単位 | validator の `length` は Unicode スカラ値（char）。絵文字などサロゲートペアの文字で結果が変わる |
| 空白の除去 | `String#strip` / `String#trim` | どちらも `str::trim`（Unicode の空白。`trim` は Java では U+0020 以下の文字だけを除く） |
| 大文字小文字を無視した比較 | `equalsIgnoreCase` | `to_lowercase()` どうしの比較 |
| エラーページ | `Accept` に `text/html` が無いと JSON を返す | 常に HTML |
| 不正なパス変数（`/todos/abc/edit`） | 400（ホワイトラベルページ） | 400（axum の平文メッセージ） |
| セッションの保存先 | サーバのメモリ（Tomcat） | サーバのメモリ（`MemoryStore`）。複数台にするなら tower-sessions の Redis / PostgreSQL ストア |
| トランザクション分離レベル | PostgreSQL の既定（READ COMMITTED） | 同じ |
| CSRF 対策 | この例では Spring Security を使っていない | 同じく無し。入れるなら axum 側は CSRF トークンを発行するミドルウェアを足す |

## 6. 検証

| 対象 | コマンド | 内容 |
|---|---|---|
| Spring Boot 版 | `./gradlew build` | MockMvc の結合テスト 7 件（PostgreSQL を使う） |
| 生成した crate | `./gradlew translateToRust`（spring-boot で） | `rust-core` を作り直す。CI は作り直した結果がコミットしたものと同じことを確かめる |
| Rust 版 | `DATABASE_URL=... cargo test` | Web 層の単体テスト 3 件、Router の結合テスト 7 件（Spring 版と同じシナリオ。生成した todo_core を使う） |
| 両方 | `./compare.sh` | 同じ 25 リクエストへの応答が一致すること |
| j2r | `./gradlew build`（リポジトリのルートで） | `tests/spring` のゴールデンテストと、生成した crate の `cargo check`（警告なし） |

CI（`.github/workflows/ci.yml` の `todo-app` ジョブ）は PostgreSQL のサービスコンテナを立てて、上の 4 つを実行する。

## 7. トランスパイラでの変換（--framework spring）

### 7.1 使い方

Gradle プラグインでは、Spring Boot のプロジェクトに次のように書いて `./gradlew translateToRust` を実行する
（例: [examples/todo-app/spring-boot/build.gradle.kts](../examples/todo-app/spring-boot/build.gradle.kts)）。
javac で型検査するため、main ソースセットのコンパイルクラスパス（Spring・MyBatis の jar）を使う。
Mapper XML と schema.sql は main ソースセットの resources から探す。

```kotlin
j2r {
    framework.set("spring")
    crateName.set("todo-core")
    outputDir.set(layout.projectDirectory.dir("../rust-core"))
}
```

コマンドラインでは次のようにする。

```sh
j2r --framework SPRING -o rust-core -n todo-core -cp <コンパイルクラスパス> --resources src/main/resources src/main/java
```

出力は sqlx・chrono・thiserror に依存するライブラリ crate で、Web 層の crate から path 依存で使う。

```
rust-core/
├── Cargo.toml
├── migrations/0001_schema.sql   # schema.sql（lib.rs の MIGRATOR で流す）
└── src/
    ├── lib.rs                   # pub mod ...; と MIGRATOR
    ├── error.rs                 # 例外クラス → Error のバリアント
    ├── domain/  mapper/  service/   # パッケージごとのモジュール（クラスごとのファイル）
    ├── clock.rs                 # java.time.Clock を使う場合の補助モジュール
    └── mybatis.rs               # 動的 SQL（<where> / <set>）を使う場合の補助モジュール
```

### 7.2 変換する範囲

| 対象 | 変換 |
|---|---|
| `@Mapper` のインタフェース + Mapper XML（namespace が一致するもの）・注釈の SQL | sqlx の関数のモジュール（§3.5） |
| `@Service` のクラス | 構造体とメソッド（§3.4） |
| それらが使うクラス・record・enum | 構造体・列挙型（§3.6） |
| それらが投げる例外クラス | `crate::error::Error` のバリアント（§3.4） |
| `@Controller`・`@ControllerAdvice`・`@Configuration`・`@SpringBootApplication`・`@Component` | 変換しない（情報の診断 `J2R-SPRING-SKIPPED`） |
| サービスからも Mapper からも使われないクラス（フォームなど） | 変換しない（`J2R-SPRING-SKIPPED`） |

メソッド本体で使える Java の構文・API は、ローカル変数、代入、if・拡張 for、return・throw、条件演算子、文字列連結、
算術・比較・論理演算、getter / setter・record のアクセサ・自分のクラスのメソッドの呼び出しと、
`String`（`strip` `trim` `toLowerCase` `isEmpty` `isBlank` `equals` `equalsIgnoreCase` `startsWith` など）・
`Optional`（`orElseThrow` `orElse` `isPresent` など）・`LocalDate` / `LocalDateTime`（`now(clock)` `isBefore` `isAfter` など）・
`List`（`size` `isEmpty`）・`Objects`・enum（`name` `ordinal` `values`）の主なメソッド。
変換できない構文・API・MyBatis の機能は `todo!()` にして警告（`J2R-SPRING-UNSUPPORTED`）を出す。

### 7.3 まだ対応していないもの

- Web 層（コントローラ・フォーム・Thymeleaf のテンプレート）と設定クラス（`@Configuration` の `@Bean`）。
  Web 層は §3.1〜3.3・§3.7 の規則で手で書く
- MyBatis: `<foreach>`・`<trim>`・`<bind>`・`${...}`（文字列の埋め込み）・`resultMap`・`@Results`、
  enum や入れ子のオブジェクトの列へのマッピング（`map-underscore-to-camel-case: true` の前提で列名とフィールド名を対応付ける）
- サービス: 別のサービスの呼び出し（トランザクションの伝播）、try / catch、`@Transactional` の `propagation`・`rollbackFor`、
  エンティティ以外のクラスの生成、ストリーム API、ラムダ（`Optional` の引数を除く）
- エンティティ: 引数のあるコンストラクタ、継承、enum のフィールド

### 7.4 実装

変換器は `translator/j2r-spring`（パッケージ `io.github.ykwyuta.j2r.spring`）にある。frontend が JIR に加えて
宣言のメタ情報（注釈とその値・消去前の型。`DeclInfo`）を記録し、j2r-spring がそれを使って RIR を組み立てる。

| クラス | 役割 |
|---|---|
| `SpringModel` | 型を役割（Mapper・サービス・エンティティ・record・enum・例外・変換しない）に分類する |
| `SpringTranslator` | 関数の形（async か・Result を返すか・引数の借用）を先に決め（`Plans`）、ファイルと lib.rs・Cargo.toml を出力する |
| `TypeResolver` / `RT` | Java の型と `@Nullable` から Rust の型（所有・借用・Option）を決める |
| `BodyLowerer` / `LibraryCalls` | メソッド本体を変換する（型合わせ・最後の使用での移動・null の検査のパターン・JDK API） |
| `MyBatisXml` / `Ognl` / `MapperGenerator` | Mapper XML と OGNL を読み、sqlx の関数にする |
| `ServiceGenerator` / `DomainGenerator` | サービス・ドメインの型・Error を生成する |

テストは `tests/spring`（Spring・MyBatis・JSpecify の注釈はスタブで型検査する）のゴールデンテストと、
生成した crate の `cargo check`（`SpringGoldenTest`）。
