# 07. Spring Boot Web アプリの Rust への移行（標準構成と変換規則）

Spring Boot 4.1 + Spring MVC + MyBatis + Thymeleaf + PostgreSQL で作った Web アプリを Rust に移すときの
**移行先の標準構成** と、層ごとの **変換規則** をまとめる。題材として同じ Todo 管理アプリを両方で実装した。

- 移行元: [examples/todo-app/spring-boot](../examples/todo-app/spring-boot)
- 移行先: [examples/todo-app/rust](../examples/todo-app/rust)（**j2r が移行元から生成**。手で書いたのは結合テストだけ）
- 動かし方: [examples/todo-app/README.md](../examples/todo-app/README.md)

j2r は `--framework spring` で、Spring Boot のアプリ（コントローラ・フォーム・Thymeleaf のテンプレート・例外ハンドラ・
サービス・MyBatis の Mapper・ドメイン・設定クラス・application.yml）を、この文書の構成の Rust のアプリに変換する
（[§7](#7-トランスパイラでの変換--framework-spring)）。§3 はその変換規則で、j2r が出力するコードの説明でもある。

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
| Bean Validation（`@NotBlank` `@Size`） | **検証のコード**（`validate(&self, &mut BindingResult)`） | 注釈ごとの検査をそのまま書く。メッセージ・対象（null の扱い）を Bean Validation と同じにできる | validator・garde（注釈と同じく宣言的だが、null の扱いと BindingResult との対応が合わない） |
| フォームのバインド（`@ModelAttribute`・`@RequestParam`） | **serde（serde_urlencoded）+ バインドのコード**（`bind(&RequestParams)`） | 型変換に失敗した値を BindingResult に残して画面に戻せる（Spring の DataBinder と同じ） | `axum::Form`（失敗するとリクエスト全体が 422 になる） |
| `RedirectAttributes#addFlashAttribute` | **tower-sessions**（セッションに入れて次のリクエストで取り出す `Flash`） | Spring の FlashMap と同じくセッションに置く | axum-messages（名前付きの属性を持てない） |
| 静的リソース（`static/`） | **tower-http `ServeDir`** | — | — |
| `application.yml` | **環境変数で上書きできる既定値**（`config.rs` の `AppConfig::load`） | `${ENV:default}` と緩いバインド（`SPRING_DATASOURCE_URL`）を同じ規則で読む | config・figment（ファイルを実行時に読む） |
| SLF4J + Logback・アクセスログ | **tracing + tracing-subscriber**、tower-http `TraceLayer` | — | log + env_logger |
| 例外・`@ControllerAdvice` | **thiserror** のエラー型 + `IntoResponse` | ハンドラが `Result` を返し、`Err` をレスポンスに変える | anyhow（型で分岐できない） |
| DI コンテナ（Bean） | **`AppState` + `State` 抽出子** | コンテナを使わず、起動時に組み立てて Router に渡す | shaku 等の DI crate（使われていない） |
| `java.time` | **chrono** | sqlx の `chrono` 機能で `DATE` / `TIMESTAMP` と対応する | time |
| MockMvc / `@SpringBootTest` | **`tower::ServiceExt::oneshot`** / **`#[sqlx::test]`** | Router をサーバなしで呼べる。`#[sqlx::test]` はテストごとに空の DB を作る | Testcontainers（どちらでも使える） |

Rust は 1.94 以上（sqlx 0.9 の MSRV）。

## 2. ファイルの対応

`rust/` はテストを除いてすべて j2r が生成する（`./gradlew j2r` で作り直す）。

| Spring Boot 版 | Rust 版（`rust/`） | |
|---|---|---|
| `build.gradle.kts` | `Cargo.toml`（`# j2r:keep` より後ろは作り直しても残る） | 生成 |
| `TodoApplication.java`（`@SpringBootApplication`） | `src/main.rs`、`src/app.rs`（自動構成に当たる組み立て・`AppState`・`AppError`）、`src/lib.rs` | 生成 |
| `application.yml` | `src/config.rs`（環境変数で上書きできる設定値） | 生成 |
| `schema.sql` | `migrations/0001_schema.sql`、`src/lib.rs` の `MIGRATOR` | 生成 |
| `ClockConfig.java`（`@Configuration` の `@Bean`） | `src/clock_config.rs` + `src/clock.rs`（`Clock` トレイト。j2r の補助モジュール） | 生成 |
| `domain/Todo.java`・`domain/TodoFilter.java` | `src/domain/todo.rs`・`todo_filter.rs` | 生成 |
| `mapper/TodoMapper.java` + `mapper/TodoMapper.xml` | `src/mapper/todo_mapper.rs`（+ 動的 SQL の補助 `mybatis.rs`） | 生成 |
| `service/TodoService.java`・`TodoSummary.java` | `src/service/todo_service.rs`・`todo_summary.rs` | 生成 |
| `service/TodoNotFoundException.java` | `src/error.rs` の `Error::TodoNotFound` | 生成 |
| `web/TodoController.java`・`HomeController.java` | `src/web/todo_controller.rs`・`home_controller.rs`、ルートは `src/app.rs` の `routes()` | 生成 |
| `web/TodoForm.java`（+ `messages.properties`） | `src/web/todo_form.rs`（`bind`・`validate`。メッセージは変換時に埋め込む） | 生成 |
| `web/GlobalExceptionHandler.java` | `src/web/global_exception_handler.rs` + `src/app.rs` の `AppError` | 生成 |
| `templates/**/*.html`（モデルは `Model`） | `templates/**/*.html`（askama）+ `src/views.rs`（テンプレートごとの構造体） | 生成 |
| `templates/fragments/layout.html` | 呼び出し側のテンプレートに展開する | 生成 |
| `static/css/app.css` | `static/css/app.css`（同じファイル） | コピー |
| （Spring MVC の仕組み） | `src/spring_web.rs`（リクエストパラメータ・フラッシュ・`BindingResult` など。j2r の補助モジュール） | 生成 |
| `TodoControllerTest.java` | `tests/todo_controller_test.rs` | 手書き |

パッケージ（`domain` / `mapper` / `service`）はそのままモジュールにし、クラスはファイル（snake_case）に対応させる。
`@SpringBootApplication` のクラスのパッケージ（`com.example.todo`）が crate のルートになる。

## 3. 変換規則

### 3.1 アプリの組み立て（Bean・自動構成）

Spring が自動構成する DataSource・DispatcherServlet・静的リソース・セッションを `src/app.rs` で組み立てる。
DI コンテナの代わりに、Bean を `AppState` のフィールドにし、ハンドラは `State<AppState>` で受け取る。

```rust
/// `@Configuration` の `@Bean`。テストでは差し替えたものを `AppState::new` に渡す。
pub struct Beans {
    pub clock: Arc<dyn Clock>,          // ClockConfig::clock()（@Bean）
}

#[derive(Clone)]
pub struct AppState {
    pub clock: Arc<dyn Clock>,
    pub todo_service: TodoService,      // @Service（コンストラクタの引数を型で解決して作る）
}

pub fn app(state: AppState) -> Router {
    routes()                            // @GetMapping / @PostMapping
        .with_state(state)
        .fallback_service(resources)    // static/、どれにも当たらなければ error/404.html
        .layer(sessions)                // HttpSession（Cookie 名は JSESSIONID）
        .layer(TraceLayer::new_for_http())
}
```

- `@SpringBootApplication` の main は `src/main.rs`（設定を読み、DB に接続し、`app` を起動する）。
- `application.yml` は `src/config.rs` の `AppConfig::load()`。`spring.datasource.*` と `server.port` の値・プレースホルダ
  （`${DATABASE_URL:jdbc:postgresql://...}`）・緩いバインドの環境変数（`SPRING_DATASOURCE_URL`）を Spring と同じ順に見る。
  JDBC の URL は sqlx の URL に直す。
- `spring.sql.init.mode=always` なら、起動時に schema.sql（`migrations/`）を流す。
- テストで差し替える Bean（`@Primary`）は `Beans` のフィールドを差し替えて渡す（例: `Beans { clock: Arc::new(FixedClock(...)) }`）。

### 3.2 コントローラ

| Spring MVC | axum（j2r が出力するコード） |
|---|---|
| `@RequestMapping("/todos")` + `@GetMapping("/{id}/edit")` | `.route("/todos/{id}/edit", get(todo_controller::edit_form))`（ルートは `app.rs` の `routes()` にまとめる） |
| `@PathVariable long id` | `Path(id): Path<i64>` |
| `@RequestParam(required = false) @Nullable String filter` | `params: RequestParams`（クエリ文字列と urlencoded の本体）から、ハンドラごとの構造体 `ListParams { filter: Option<String> }` に変換する。`defaultValue` は `unwrap_or` |
| `@Valid @ModelAttribute TodoForm form, BindingResult result` | `let (form, mut binding_result) = TodoForm::bind(&params); form.validate(&mut binding_result);` |
| `model.addAttribute("todos", ...)` + `return "todos/list"` | `let model_todos = ...;` + `TodosListView { todos: model_todos, ... }.into_response()`（ビューの構造体は `src/views.rs`） |
| `return "redirect:/todos"`・`"redirect:/books/" + id` | `redirect("/todos", &[])`（302 Found。axum の `Redirect::to` は 303） |
| `redirectAttributes.addFlashAttribute("message", ...)` | `flash.set("message", ...).await`（読む側のビューは `message: flash.get("message")`） |
| `redirectAttributes.addAttribute("filter", ...)` | `redirect_params.push(("filter", ...))` → リダイレクト先のクエリ文字列 |
| コントローラのフィールド（注入された Bean） | `state.todo_service`・`state.clock` |
| 例外を投げる（サービスの `Err`） | ハンドラは `Result<Response, AppError>` を返し、`?` で `AppError` にする |

ビューの構造体のフィールドは、そのビューを返すすべてのハンドラが入れるモデルの属性（入れないハンドラがあれば `Option`）と、
テンプレートが参照するフラッシュ属性、`th:object` のフォームの `BindingResult`（`todo_form_binding`）。

### 3.3 フォームと検証

フォームのクラス（ハンドラが `@ModelAttribute` で受け取るクラス）には、Spring の DataBinder と Bean Validation に当たる
`bind` と `validate` を生成する。

```rust
impl TodoForm {
    pub fn bind(params: &RequestParams) -> (Self, BindingResult) {
        let mut form = Self::default();
        let mut result = BindingResult::default();
        if let Some(v) = params.get("dueDate") {
            match parse_date(v, "%Y-%m-%d") {            // @DateTimeFormat(iso = DATE)
                Ok(x) => { form.due_date = x; }
                Err(_) => result.reject("dueDate", "期限は yyyy-MM-dd 形式で入力してください", Some(v)),
            }
        }
        if let Some(v) = params.get("done") { ... } else if params.contains("_done") { form.done = false; }
        (form, result)
    }

    pub fn validate(&self, result: &mut BindingResult) {
        if !not_blank(self.title.as_deref()) {             // @NotBlank(message = "...")
            result.reject("title", "タイトルを入力してください", None);
        }
        if self.title.as_deref().is_some_and(|v| length(v) > 100) { ... }   // @Size(max = 100)
    }
}
```

- 型変換に失敗したときのメッセージは messages.properties の `typeMismatch.<フォーム>.<フィールド>`・`typeMismatch.<フィールド>`・
  `typeMismatch.<型>`・`typeMismatch` の順に探す。失敗した値はテンプレートの `th:field` で入力欄に戻す。
- `@NotBlank`・`@NotNull`・`@NotEmpty`・`@Size`・`@Min`・`@Max` に対応する。`message` がなければ Hibernate Validator の既定の英語の
  メッセージ。`@Size` の長さは Java と同じ UTF-16 の単位で数える。
- チェックボックスは、送られないときは `th:field` が出力する `_done` の目印で false にする（Spring と同じ）。
- `@Valid` のフォームを `BindingResult` なしで受け取るハンドラは、エラーがあれば 400 を返す。

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

テンプレートは askama のテンプレート（`templates/`）に変換する。処理しない部分（HTML・属性・空白）は元のまま出し、
`th:*` の属性は Thymeleaf と同じ位置・順序で出力する（`th:href` などはその位置で置き換え、`th:field`・`th:errorclass` などが
足す属性は最後に付ける。消した属性の前後の空白の扱いも Thymeleaf に合わせる）。フラグメントは呼び出し元に展開する。

| Thymeleaf | askama |
|---|---|
| `th:text="${todo.title}"` | `{{ todo.title }}`（HTML エスケープは既定で有効） |
| `th:text="${title} + ' | Todo'"` | `{{ title }} | Todo` |
| `th:if="${todo.description}"`（`@Nullable`） | `{% if let Some(description) = todo.description %}`（中の `${todo.description}` はこの変数） |
| `th:if` / `th:unless`（それ以外） | `{% if %}`（null・false・0・"false" などが偽。`Truthy` トレイト） |
| `th:each="todo : ${todos}"`・`th:each="x, stat : ..."` | `{% for todo in todos %}`（`stat.count` → `loop.index`、`stat.index` → `loop.index0`） |
| `th:classappend="${todo.done} ? 'done'"` | `class="todo{% if todo.done %} done{% endif %}"`（class がなければ条件付きで class 属性を足す） |
| `a ? b : c`・`${x} == null ? a : b` | `{% if a %}b{% else %}c{% endif %}`・`{% if let Some(x) = x %}b{% else %}a{% endif %}` |
| `${message} ?: '既定'` | `{% if let Some(value) = message.present() %}{{ value }}{% else %}既定{% endif %}`（空文字列も「値がない」） |
| `@{/todos(filter='all', q=${q})}` | `/todos?filter=all&amp;q={{ q\|urlencode }}` |
| `@{/todos/{id}/edit(id=${todo.id})}` | `/todos/{{ todo.id }}/edit` |
| `${#temporals.format(todo.dueDate, 'yyyy/MM/dd')}` | `{{ due_date.format("%Y/%m/%d") }}` |
| `${summary.total}`・`${todo.isOverdue(today)}` | `summary.total()`・`todo.is_overdue(*today)`（getter はフィールド、それ以外はメソッド） |
| `th:replace="~{fragments/layout :: head('一覧')}"` | フラグメントの要素を、引数を束縛して展開する |
| `th:object="${todoForm}"` + `th:field="*{title}"` | `id="title" name="title" value="..."`（型変換に失敗した値があればそれ、`@DateTimeFormat` で書式化） |
| `th:errors`・`#fields.errors('title')`・`#fields.hasErrors('title')`・`th:errorclass` | `todo_form_binding.field_errors("title")`・`has_field_errors("title")` |

### 3.8 例外ハンドラ・エラーページ

- `@ControllerAdvice` の `@ExceptionHandler` は関数になり、`AppError` の `IntoResponse` が例外（`Error` のバリアント）ごとに
  呼び分ける。`@ResponseStatus` はレスポンスのステータス。対応する例外ハンドラのない例外は 500。
- Spring Boot がどのマッピングにも当たらないリクエストに `templates/error/404.html` を返すのは、ルータの fallback で
  同じテンプレートを返して再現する。

### 3.9 テスト

Spring のテスト（MockMvc）はまだ変換しないので、Rust 版の結合テスト（`examples/todo-app/rust/tests/`）は手で書く。
j2r で作り直しても `tests/` と Cargo.toml の `# j2r:keep` から後ろ（dev-dependencies）は残る。

- MockMvc は、`app(state)` で作った `Router` を `tower::ServiceExt::oneshot` で直接呼ぶ。
  フラッシュ属性を確かめるには、セッション Cookie を次のリクエストに持ち回る（`tests/todo_controller_test.rs` の `Client`）。
- 実 DB を使う `@SpringBootTest` は `#[sqlx::test(migrator = "todo::MIGRATOR")]` にする。テストごとに空の DB を作って
  マイグレーションを流すので、`@BeforeEach` で表を空にする処理は要らない（`DATABASE_URL` の利用者に CREATEDB 権限が必要）。

## 4. 移行で変えないもの

画面・URL・フォームの `name` 属性・リダイレクト先・ステータスコード・メッセージ文言は変えない。
[examples/todo-app/compare.sh](../examples/todo-app/compare.sh) が両方のアプリに同じ 28 個のリクエストを送り、
ステータス・リダイレクト先・HTML（空白の違いと文字参照の書き方の違いだけを吸収）が一致することを確かめる。

## 5. 挙動の違いと注意点

| 項目 | Spring | Rust 版での扱い |
|---|---|---|
| リダイレクトのステータス | `redirect:` は 302 Found | axum の `Redirect::to` は 303 なので、302 を返す `spring_web::redirect` を使う |
| 初回リダイレクトの URL | セッション作成直後は `;jsessionid=...` を付ける | Spring 側で `server.servlet.session.tracking-modes: cookie` にして付けないようにした |
| HTML エスケープ | `&lt;` `&amp;` | `&#60;` `&#38;`（ブラウザでの表示は同じ） |
| 文字数の数え方 | `@Size` は UTF-16 のコード単位 | 同じ（`spring_web::length`） |
| 空白の除去 | `String#strip` / `String#trim` | どちらも `str::trim`（Unicode の空白。`trim` は Java では U+0020 以下の文字だけを除く） |
| 大文字小文字を無視した比較 | `equalsIgnoreCase` | `to_lowercase()` どうしの比較 |
| エラーページ | `Accept` に `text/html` が無いと JSON を返す | 常に HTML |
| 不正なパス変数（`/todos/abc/edit`）・パラメータ | 400（ホワイトラベルページ） | 400（axum の平文メッセージ） |
| フラッシュ属性の寿命 | 次のリクエスト（リダイレクト先）で消える | `Flash` を受け取るハンドラ（ビューを返すハンドラなど）が取り出したときに消える |
| 静的リソース・テンプレート | jar に入る | テンプレートはバイナリに入る。`static/` は作業ディレクトリから読む |
| セッションの保存先 | サーバのメモリ（Tomcat） | サーバのメモリ（`MemoryStore`）。複数台にするなら tower-sessions の Redis / PostgreSQL ストア |
| トランザクション分離レベル | PostgreSQL の既定（READ COMMITTED） | 同じ |
| CSRF 対策 | この例では Spring Security を使っていない | 同じく無し。入れるなら axum 側は CSRF トークンを発行するミドルウェアを足す |

## 6. 検証

| 対象 | コマンド | 内容 |
|---|---|---|
| Spring Boot 版 | `./gradlew build` | MockMvc の結合テスト 7 件（PostgreSQL を使う） |
| Rust 版の生成 | `./gradlew translateToRust`（spring-boot で） | `rust/` を作り直す。CI は作り直した結果がコミットしたものと同じことを確かめる |
| Rust 版 | `DATABASE_URL=... cargo test` | Router の結合テスト 7 件（Spring 版と同じシナリオ。手で書いたもの） |
| 両方 | `./compare.sh` | 同じ 28 リクエストへの応答（ステータス・リダイレクト先・HTML）が一致すること |
| j2r | `./gradlew build`（リポジトリのルートで） | `tests/spring` のゴールデンテストと、生成した crate の `cargo check`（警告なし） |

CI（`.github/workflows/ci.yml` の `todo-app` ジョブ）は PostgreSQL のサービスコンテナを立てて、上の 4 つを実行する。

## 7. トランスパイラでの変換（--framework spring）

### 7.1 使い方

Gradle プラグインでは、Spring Boot のプロジェクトに次のように書いて `./gradlew translateToRust` を実行する
（例: [examples/todo-app/spring-boot/build.gradle.kts](../examples/todo-app/spring-boot/build.gradle.kts)）。
javac で型検査するため、main ソースセットのコンパイルクラスパス（Spring・MyBatis の jar）を使う。
Mapper XML・テンプレート・静的リソース・schema.sql・application.yml・messages.properties は main ソースセットの resources から読む。

```kotlin
j2r {
    framework.set("spring")
    crateName.set("todo")
    outputDir.set(layout.projectDirectory.dir("../rust"))
}
```

コマンドラインでは次のようにする。

```sh
j2r --framework SPRING -o rust -n todo -cp <コンパイルクラスパス> --resources src/main/resources src/main/java
```

出力はアプリの crate（ライブラリ + バイナリ）。`cargo run` で起動し、Spring 版と同じ環境変数（`DATABASE_URL`・`PORT` など
application.yml のプレースホルダ）で設定する。作り直すと `src/`・`templates/`・`static/`・`migrations/`・Cargo.toml を
書き換え、`tests/` と Cargo.toml の `# j2r:keep` から後ろは残す。

```
rust/
├── Cargo.toml
├── migrations/0001_schema.sql   # schema.sql
├── templates/                   # Thymeleaf → askama（フラグメントは展開済み）
├── static/                      # static/ のコピー
└── src/
    ├── main.rs                  # @SpringBootApplication
    ├── lib.rs                   # pub mod ...;、MIGRATOR
    ├── app.rs                   # AppState・Beans・routes()・app()・AppError・connect()
    ├── config.rs                # application.yml
    ├── views.rs                 # テンプレートに渡すモデル（ビューの構造体）
    ├── error.rs                 # 例外クラス → Error のバリアント
    ├── web/  service/  mapper/  domain/   # パッケージごとのモジュール（クラスごとのファイル）
    ├── clock_config.rs          # @Configuration
    ├── spring_web.rs            # 補助: RequestParams・Flash・BindingResult・redirect など
    ├── clock.rs                 # 補助: java.time.Clock
    └── mybatis.rs               # 補助: 動的 SQL（<where> / <set>）
```

コントローラがなければ（Mapper・サービスだけのプロジェクト）、Web 層なしのライブラリ crate を出力する。

### 7.2 変換する範囲

| 対象 | 変換 |
|---|---|
| `@Controller`（`@GetMapping` など）・フォームのクラス・Thymeleaf のテンプレート | axum のハンドラ・`bind` / `validate` のあるフォーム・askama のテンプレート（§3.2・§3.3・§3.7） |
| `@ControllerAdvice` の `@ExceptionHandler` | `AppError` の `IntoResponse`（§3.8） |
| `@Configuration` の `@Bean`・`@SpringBootApplication`・application.yml | `Beans`・`AppState`・`main.rs`・`config.rs`（§3.1） |
| `@Mapper` のインタフェース + Mapper XML（namespace が一致するもの）・注釈の SQL | sqlx の関数のモジュール（§3.5） |
| `@Service` のクラス | 構造体とメソッド（§3.4） |
| それらが使うクラス・record・enum・例外クラス | 構造体・列挙型・`crate::error::Error` のバリアント（§3.4・§3.6） |
| `@RestController`・`@Component`、どこからも使われないクラス | 変換しない（情報の診断 `J2R-SPRING-SKIPPED`） |

メソッド本体で使える Java の構文・API は、ローカル変数、代入、if・拡張 for、return・throw、条件演算子、文字列連結、
算術・比較・論理演算、getter / setter・record のアクセサ・自分のクラスのメソッドの呼び出しと、
`String`（`strip` `trim` `toLowerCase` `isEmpty` `isBlank` `equals` `equalsIgnoreCase` `startsWith` など）・
`Optional`（`orElseThrow` `orElse` `isPresent` など）・`LocalDate` / `LocalDateTime`（`now(clock)` `isBefore` `isAfter` など）・
`Clock`（`systemDefaultZone` `systemUTC`）・`List`（`size` `isEmpty`）・`Objects`・enum（`name` `ordinal` `values`）の主なメソッド。
変換できない構文・API・MyBatis や Thymeleaf の機能は `todo!()`（テンプレートでは空）にして警告（`J2R-SPRING-UNSUPPORTED`）を出す。

### 7.3 まだ対応していないもの

- Web: `@RestController`（JSON）、`@RequestBody`、`@SessionAttributes`、インターセプタ・フィルタ、Spring Security、国際化
  （`#{...}` のメッセージ式）、`th:with`・`th:switch`・`th:block` 以外の Thymeleaf の拡張、`<select>` の `th:field`、
  レイアウト方言（`layout:decorate`）
- テスト: MockMvc のテストの変換（Rust 版の結合テストは手で書く）
- MyBatis: `<foreach>`・`<trim>`・`<bind>`・`${...}`（文字列の埋め込み）・`resultMap`・`@Results`、
  enum や入れ子のオブジェクトの列へのマッピング（`map-underscore-to-camel-case: true` の前提で列名とフィールド名を対応付ける）
- サービス: 別のサービスの呼び出し（トランザクションの伝播）、try / catch、`@Transactional` の `propagation`・`rollbackFor`、
  エンティティ以外のクラスの生成、ストリーム API、ラムダ（`Optional` の引数を除く）
- エンティティ・フォーム: 引数のあるコンストラクタ、継承、enum のフィールド、フィールドの初期化子
- 設定: `@Bean` メソッドの引数、`application.yml` の `spring.datasource.*`・`server.port`・`spring.sql.init.mode` 以外の値

### 7.4 実装

変換器は `translator/j2r-spring`（パッケージ `io.github.ykwyuta.j2r.spring`）にある。frontend が JIR に加えて
宣言のメタ情報（注釈とその値・消去前の型。`DeclInfo`）を記録し、j2r-spring がそれを使って RIR を組み立てる。

| クラス | 役割 |
|---|---|
| `SpringModel` | 型を役割（コントローラ・例外ハンドラ・設定・フォーム・Mapper・サービス・エンティティ・record・enum・例外）に分類する |
| `SpringTranslator` | 関数の形（async か・Result を返すか・引数の借用）を先に決め（`Plans`）、ファイルと lib.rs・Cargo.toml を出力する |
| `TypeResolver` / `RT` | Java の型と `@Nullable` から Rust の型（所有・借用・Option）を決める |
| `BodyLowerer` / `LibraryCalls` | メソッド本体を変換する（型合わせ・最後の使用での移動・null の検査のパターン・JDK API）。`Hooks` でコントローラの Model などを置き換える |
| `MyBatisXml` / `Ognl` / `MapperGenerator` | Mapper XML と OGNL を読み、sqlx の関数にする |
| `ServiceGenerator` / `DomainGenerator` / `FormGenerator` | サービス・ドメインの型・フォームのバインドと検証・Error を生成する |
| `WebGenerator` | ハンドラ・ルート・ビューの構造体・AppState / Beans・例外ハンドラ・config.rs・main.rs |
| `ThymeleafHtml` / `ThymeleafExpr` / `TemplateTranslator` | テンプレートを（元の書き方を保って）読み、Thymeleaf の式を型付きで askama に変換する |

テストは `tests/spring`（Spring・MyBatis・JSpecify・Jakarta Validation の注釈はスタブで型検査する）のゴールデンテストと、
生成した crate の `cargo check`（`SpringGoldenTest`）。askama のテンプレートもこの cargo check で型検査される。
