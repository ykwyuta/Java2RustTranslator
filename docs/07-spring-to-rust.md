# 07. Spring Boot Web アプリの Rust への移行（標準構成と変換規則）

Spring Boot 4.1 + Spring MVC + MyBatis + Thymeleaf + PostgreSQL で作った Web アプリを Rust に移すときの
**移行先の標準構成** と、層ごとの **変換規則** をまとめる。題材として同じ Todo 管理アプリを両方で実装した。

- 移行元: [examples/todo-app/spring-boot](../examples/todo-app/spring-boot)
- 移行先: [examples/todo-app/rust](../examples/todo-app/rust)
- 動かし方: [examples/todo-app/README.md](../examples/todo-app/README.md)

現在のトランスパイラ（j2r）は JDK だけを使う Java を対象にしており、Spring や MyBatis のようなフレームワークの
コード（アノテーションで構成された Bean、XML の SQL、テンプレート）はまだ変換できない。
今回の Rust 版は、この文書の規則に沿って **手で変換した参照実装** で、将来 j2r に
フレームワークの変換規則を入れるときの正解データ（ゴールデン）として使う（[§7](#7-トランスパイラへの取り込み)）。

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

| Spring Boot 版 | Rust 版 |
|---|---|
| `build.gradle.kts` | `Cargo.toml` |
| `TodoApplication.java`（`@SpringBootApplication`） | `src/main.rs`、`src/lib.rs`（自動構成に当たる組み立て） |
| `application.yml` | `config/application.toml`、`src/lib.rs` の `AppConfig` |
| `schema.sql` | `migrations/0001_create_todos.sql` |
| `ClockConfig.java`（`Clock` の Bean） | `src/clock.rs`（`Clock` トレイト） |
| `domain/Todo.java` | `src/domain/todo.rs`（`Todo`・`NewTodo`） |
| `domain/TodoFilter.java` | `src/domain/todo_filter.rs` |
| `mapper/TodoMapper.java` + `mapper/TodoMapper.xml` | `src/mapper/todo_mapper.rs` |
| `service/TodoService.java`・`TodoSummary`・`TodoNotFoundException` | `src/service/todo_service.rs`（`ServiceError`） |
| `web/TodoController.java`・`HomeController.java` | `src/web/todo_controller.rs`・`home_controller.rs`、ルートは `src/web/mod.rs` |
| `web/TodoForm.java`（+ `messages.properties`） | `src/web/todo_form.rs` |
| `web/GlobalExceptionHandler.java` | `src/web/global_exception_handler.rs` |
| `templates/**/*.html`（モデルは `Model`） | `templates/**/*.html` + `src/web/views.rs`（テンプレートごとの構造体） |
| `templates/fragments/layout.html` | `templates/base.html`（`{% extends %}` と `{% block %}`） |
| `static/css/app.css` | `static/css/app.css`（同じファイル） |
| `TodoControllerTest.java` | `tests/todo_controller_test.rs` |

パッケージ（`domain` / `mapper` / `service` / `web`）はそのままモジュールにし、クラスはファイル（snake_case）に対応させる。

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
`AppState::with_clock` に別の実装を渡す）。

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
| 例外を投げる | `Err(ServiceError)` を返す（ハンドラの戻り値は `Result<_, ServiceError>`） |

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

### 3.4 サービスとトランザクション

- `@Transactional` のメソッドは `let mut tx = pool.begin().await?; ...; tx.commit().await?;` にする。
  `?` で途中で抜けると `Transaction` の drop でロールバックされ、実行時例外でロールバックする Spring の既定と同じになる。
- `@Transactional(readOnly = true)` で 1 文しか流さないメソッドは、トランザクションを張らず `pool.acquire()` の接続を使う。
- 例外クラスは thiserror の enum の値にする（`TodoNotFoundException` → `ServiceError::TodoNotFound(id)`、
  `DataAccessException` → `ServiceError::Database(sqlx::Error)`）。
- `Optional#orElseThrow` は `ok_or(ServiceError::TodoNotFound(id))?`。

### 3.5 Mapper（MyBatis → sqlx）

Mapper インタフェースと XML は、接続を引数に取る関数の集まり（モジュール）にする。
MyBatis の Mapper は Spring のトランザクションに暗黙に参加するが、Rust では接続（`&mut PgConnection`）を明示的に渡す。
プールの接続もトランザクションも `&mut PgConnection` として渡せる。

| MyBatis | sqlx |
|---|---|
| `#{title}` | `$1` + `.bind(&todo.title)` |
| `resultType="Todo"` + `map-underscore-to-camel-case` | `#[derive(sqlx::FromRow)]`（Rust のフィールドも snake_case なので変換は不要） |
| `List<Todo>` / `Optional<Todo>` / `long` | `fetch_all` / `fetch_optional` / `query_scalar(...).fetch_one` |
| `<insert useGeneratedKeys="true" keyProperty="id">` | `INSERT ... RETURNING id` を `query_scalar` で受け取る |
| `int update(...)`（更新件数） | `execute(...).await?.rows_affected()` |
| `<sql id="columns">` + `<include>` | 文字列リテラルを返す `macro_rules!` + `concat!`（下記） |
| `<where>` `<if test="...">` | `QueryBuilder` に条件を足していく（最初だけ `WHERE`、以降は `AND`） |
| `'%' \|\| #{keyword} \|\| '%'` | `.push("title ILIKE '%' || ").push_bind(keyword).push(" || '%'")` |

sqlx 0.9 は SQL インジェクション対策として、`query()` に渡す SQL を文字列リテラル（`&'static str`）に限る
（`format!` で作った文字列はコンパイルエラーになる）。共通の列リストは `concat!` で組み立てる。

```rust
macro_rules! columns {
    () => { "id, title, description, done, due_date, created_at, updated_at" };
}
sqlx::query_as::<_, Todo>(concat!("SELECT ", columns!(), " FROM todos WHERE id = $1"))
```

SQL を DB に対してコンパイル時に検査する `query_as!` マクロもあるが、ビルドに DB（または `cargo sqlx prepare` で作る
`.sqlx/`）が要るので、この例では実行時に対応付ける `query_as` を使っている。

### 3.6 ドメインクラス

| Java | Rust |
|---|---|
| getter / setter のある POJO | 公開フィールドの `struct` |
| null になりうる参照（`String description`、`LocalDate dueDate`） | `Option<String>`、`Option<NaiveDate>` |
| INSERT 前は `id` が null の同じクラス | id を持たない別の型（`NewTodo`） |
| `LocalDate` / `LocalDateTime` | `chrono::NaiveDate` / `NaiveDateTime`（列は `DATE` / `TIMESTAMP`） |
| `enum` + `valueOf` の代わりの `fromParam` | `enum` + `from_param(Option<&str>)` |
| `record TodoSummary` | `#[derive(Clone, Copy)] struct TodoSummary` |

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

- `@ControllerAdvice` の `@ExceptionHandler` は、サービスのエラー型に `IntoResponse` を実装して書く
  （`TodoNotFound` → 404 と `error/404.html`、DB エラー → ログを出して 500）。
- Spring Boot がどのマッピングにも当たらないリクエストに `templates/error/404.html` を返すのは、
  `Router::fallback` で同じテンプレートを返して再現する。

### 3.9 テスト

- MockMvc は、`app(state)` で作った `Router` を `tower::ServiceExt::oneshot` で直接呼ぶ。
  フラッシュ属性を確かめるには、セッション Cookie を次のリクエストに持ち回る（`tests/todo_controller_test.rs` の `Client`）。
- 実 DB を使う `@SpringBootTest` は `#[sqlx::test(migrations = "./migrations")]` にする。テストごとに空の DB を作って
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
| 空白の除去 | `String#strip` | `str::trim`（どちらも Unicode の空白） |
| エラーページ | `Accept` に `text/html` が無いと JSON を返す | 常に HTML |
| 不正なパス変数（`/todos/abc/edit`） | 400（ホワイトラベルページ） | 400（axum の平文メッセージ） |
| セッションの保存先 | サーバのメモリ（Tomcat） | サーバのメモリ（`MemoryStore`）。複数台にするなら tower-sessions の Redis / PostgreSQL ストア |
| トランザクション分離レベル | PostgreSQL の既定（READ COMMITTED） | 同じ |
| CSRF 対策 | この例では Spring Security を使っていない | 同じく無し。入れるなら axum 側は CSRF トークンを発行するミドルウェアを足す |

## 6. 検証

| 対象 | コマンド | 内容 |
|---|---|---|
| Spring Boot 版 | `./gradlew build` | MockMvc の結合テスト 7 件（PostgreSQL を使う） |
| Rust 版 | `DATABASE_URL=... cargo test` | 単体テスト 5 件、Router の結合テスト 7 件（Spring 版と同じシナリオ） |
| 両方 | `./compare.sh` | 同じ 25 リクエストへの応答が一致すること |

CI（`.github/workflows/ci.yml` の `todo-app` ジョブ）は PostgreSQL のサービスコンテナを立てて、この 3 つを実行する。

## 7. トランスパイラへの取り込み

フレームワークのコードを j2r で変換するには、JDK の API の対応表（`j2r-mappings`）と同じように、
フレームワークの API とアノテーションの対応表が要る。この文書の §3 がその仕様の下書きになる。

1. アノテーションで構成されたクラス（`@Controller` `@Service` `@Mapper` `@Configuration`）を javac の型情報から集め、
   `AppState` とルート定義を生成する。
2. MyBatis の XML を読み、`#{}` をバインド変数に、`<where>` `<if>` を `QueryBuilder` の呼び出しに変換する
   （`test` 属性は OGNL なので、単純な比較だけを対象にする）。
3. Thymeleaf のテンプレートを askama のテンプレートに変換する（`th:*` 属性 → 制御構文）。
4. Spring 版と Rust 版を同じシナリオで動かして比べる（`compare.py` を E2E テストの一種として使う）。

今回の Rust 版がこの変換の出力の目標になる。
