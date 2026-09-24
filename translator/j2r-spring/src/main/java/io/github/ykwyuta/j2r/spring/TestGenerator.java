package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Annotation;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} の結合テストを、Rust の結合テスト（tests/）にする。
 *
 * <ul>
 *   <li>テストのクラス → フィクスチャの構造体（{@code @Autowired} のフィールド）。{@code new} がアプリを組み立て、
 *       {@code @BeforeEach} を呼ぶ</li>
 *   <li>{@code @Test} のメソッド → フィクスチャのメソッドと、それを呼ぶ {@code #[sqlx::test]} の関数（テストごとに空のデータベース）</li>
 *   <li>{@code @TestConfiguration} の {@code @Bean} → {@code Beans} の差し替え</li>
 *   <li>MockMvc・ResultMatcher・Hamcrest・JUnit の Assertions・JdbcTemplate → tests/mock_mvc の同じ名前の関数・assert! など</li>
 * </ul>
 */
final class TestGenerator {
    private static final String SERVLET = "org.springframework.test.web.servlet.";
    private static final String REQUEST_BUILDERS = SERVLET + "request.MockMvcRequestBuilders";
    private static final String RESULT_MATCHERS = SERVLET + "result.MockMvcResultMatchers";
    private static final Set<String> HAMCREST = Set.of("org.hamcrest.Matchers", "org.hamcrest.CoreMatchers");
    private static final String ASSERTIONS = "org.junit.jupiter.api.Assertions";
    private static final Set<String> JDBC = Set.of("org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.JdbcOperations");
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String TEST_CONFIGURATION = "org.springframework.boot.test.context.TestConfiguration";
    private static final String JUNIT = "org.junit.jupiter.api.";
    /** tests/mock_mvc にある関数・メソッド（Java の名前）。 */
    private static final Set<String> SUPPORTED = Set.of(
            "get", "post", "put", "patch", "delete", "param", "header", "accept", "contentType", "content",
            "status", "view", "flash", "redirectedUrl", "containsString", "not", "equalTo", "is",
            "isOk", "isCreated", "isNoContent", "isFound", "isBadRequest", "isUnauthorized", "isForbidden", "isNotFound",
            "isMethodNotAllowed", "isInternalServerError", "is2xxSuccessful", "is3xxRedirection", "is4xxClientError", "is5xxServerError",
            "string", "name", "attribute", "attributeExists", "attributeCount", "exists", "andExpect", "perform", "andDo", "andReturn",
            "getResponse", "getContentAsString");

    private final SpringTranslator tr;
    private final SpringModel model;
    private final WebGenerator web;
    private final String crate;
    private final boolean migrator;

    TestGenerator(SpringTranslator tr, WebGenerator web, String crateName, boolean migrator) {
        this.tr = tr;
        this.model = tr.model();
        this.web = web;
        this.crate = Naming.crateName(crateName);
        this.migrator = migrator;
    }

    /** テストのファイルと tests/mock_mvc/mod.rs（テストがなければ空）。 */
    List<SpringTranslator.GeneratedFile> generate() {
        List<SpringTranslator.GeneratedFile> out = new ArrayList<>();
        for (Decl.TypeDecl t : model.typesWith(SpringModel.Role.TEST)) {
            if (t.outer() != null || !model.info(DeclInfo.typeKey(t.qualifiedName())).has(SpringModel.SPRING_BOOT_TEST)) {
                continue;
            }
            if (!web.enabled()) {
                tr.report(t.pos(), t.simpleName() + ": MockMvc tests need controllers");
                continue;
            }
            out.add(testFile(t));
        }
        if (!out.isEmpty()) {
            out.add(new SpringTranslator.GeneratedFile("tests/mock_mvc/mod.rs",
                    SpringTranslator.resource("j2r/spring/mock_mvc.rs").replace("__CRATE__", crate)));
        }
        return out;
    }

    // ================================================================== テストのファイル

    /** フィクスチャのフィールド（{@code @Autowired}）。init は new の中の初期化の式。 */
    private record Field(String name, String rust, RT type, String rustType, String init, boolean usesPool) {}

    private SpringTranslator.GeneratedFile testFile(Decl.TypeDecl t) {
        Imports imports = Imports.forTests(crate);
        String name = SpringModel.rustTypeName(t);
        imports.defineLocal(name);
        List<RItem> items = new ArrayList<>();

        // @TestConfiguration の @Bean → Beans の差し替え
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Decl.TypeDecl cfg : testConfigurations(t)) {
            items.addAll(configuration(cfg, imports, overrides));
        }

        // @Autowired のフィールド
        List<Field> fields = new ArrayList<>();
        for (Decl.FieldDecl f : t.fields()) {
            if (f.isStatic()) {
                continue;
            }
            if (!model.info(DeclInfo.fieldKey(t.qualifiedName(), f.name())).has(AUTOWIRED)) {
                tr.report(f.pos(), t.simpleName() + "." + f.name() + ": only @Autowired fields are supported in tests");
                continue;
            }
            Field field = field(t, f, imports);
            if (field != null) {
                fields.add(field);
            }
        }
        List<RItem.Field> structFields = new ArrayList<>();
        for (Field f : fields) {
            structFields.add(new RItem.Field("", f.rust(), new RType(f.rustType())));
        }
        items.add(new RItem.Struct(SpringTranslator.docs(t.javadoc()), List.of(), name, structFields));

        // メソッド
        List<Decl.MethodDecl> befores = new ArrayList<>();
        List<Decl.MethodDecl> afters = new ArrayList<>();
        List<Decl.MethodDecl> tests = new ArrayList<>();
        for (Decl.MethodDecl m : t.methods()) {
            DeclInfo info = model.info(DeclInfo.methodKey(m.ref()));
            if (info.has(JUNIT + "BeforeEach")) {
                befores.add(m);
            } else if (info.has(JUNIT + "AfterEach")) {
                afters.add(m);
            } else if (info.has(JUNIT + "Test")) {
                tests.add(m);
            } else if (info.has(JUNIT + "BeforeAll") || info.has(JUNIT + "AfterAll") || info.has(JUNIT + "ParameterizedTest")) {
                tr.report(m.pos(), t.simpleName() + "." + m.name() + ": @BeforeAll / @AfterAll / @ParameterizedTest are not supported yet");
            }
        }
        List<RItem> methods = new ArrayList<>();
        methods.add(constructor(t, fields, overrides, befores, imports));
        for (Decl.MethodDecl m : t.methods()) {
            if (!m.isConstructor() && m.body() != null) {
                methods.add(method(t, m, imports));
            }
        }
        items.add(new RItem.Impl(List.of(), name, methods));

        // #[sqlx::test] の関数
        imports.add("sqlx::PgPool");
        String attr = migrator ? "sqlx::test(migrator = \"" + crate + "::MIGRATOR\")" : "sqlx::test";
        for (Decl.MethodDecl m : tests) {
            DeclInfo info = model.info(DeclInfo.methodKey(m.ref()));
            List<String> attrs = new ArrayList<>(List.of(attr));
            if (info.has(JUNIT + "Disabled")) {
                attrs.add("ignore");
            }
            List<String> docs = new ArrayList<>();
            Annotation display = info.get(JUNIT + "DisplayName");
            if (display != null && display.stringValue("value") != null) {
                docs.add(display.stringValue("value"));
            }
            String fn = Naming.valueName(m.name());
            RExpr.Block body;
            if (afters.isEmpty()) {
                body = RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Await(new RExpr.MethodCall(new RExpr.Await(
                        new RExpr.Call(new RExpr.Path(name + "::new"), List.of(new RExpr.Path("pool")))), fn, List.of())), true)));
            } else {
                List<RStmt> stmts = new ArrayList<>();
                stmts.add(new RStmt.Let("test", false, null, new RExpr.Await(new RExpr.Call(new RExpr.Path(name + "::new"),
                        List.of(new RExpr.Path("pool"))))));
                stmts.add(new RStmt.ExprStmt(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("test"), fn, List.of())), true));
                for (Decl.MethodDecl a : afters) {
                    stmts.add(new RStmt.ExprStmt(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("test"), Naming.valueName(a.name()),
                            List.of())), true));
                }
                body = RExpr.Block.of(stmts);
            }
            items.add(new RItem.Fn(docs, attrs, "", true, fn, List.of(new RItem.Param("pool", false, new RType("PgPool"))), null, body));
        }

        List<RItem> all = new ArrayList<>();
        all.add(new RItem.ModDecl(false, "mock_mvc"));
        all.addAll(imports.items());
        all.addAll(items);
        String file = Naming.moduleName(t.simpleName());
        List<String> header = List.of(
                "Translated from `" + t.simpleName() + "` (" + java.nio.file.Path.of(t.pos().file()).getFileName() + ") by Java2RustTranslator.",
                "",
                "`#[sqlx::test]` がテストごとに空のデータベースを作り" + (migrator ? "、MIGRATOR（schema.sql）を流して" : "")
                        + "から PgPool を渡す（接続先は環境変数 DATABASE_URL。CREATEDB 権限が必要）。");
        return new SpringTranslator.GeneratedFile("tests/" + file + ".rs", SpringTranslator.print(new RFile(file + ".rs", header, List.of(), all)));
    }

    /** テストに使う @TestConfiguration（入れ子のものと @Import したもの）。 */
    private List<Decl.TypeDecl> testConfigurations(Decl.TypeDecl t) {
        List<Decl.TypeDecl> out = new ArrayList<>();
        for (Decl.TypeDecl c : model.types.values()) {
            if (t.qualifiedName().equals(c.outer()) && model.info(DeclInfo.typeKey(c.qualifiedName())).has(TEST_CONFIGURATION)) {
                out.add(c);
            }
        }
        Annotation imp = model.info(DeclInfo.typeKey(t.qualifiedName())).get("org.springframework.context.annotation.Import");
        if (imp != null) {
            Object v = imp.value("value");
            for (Object x : v instanceof List<?> l ? l : List.of(v)) {
                Decl.TypeDecl c = model.types.get(String.valueOf(x));
                if (c == null) {
                    tr.report(t.pos(), t.simpleName() + ": @Import(" + x + ") is not supported yet");
                } else if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** @TestConfiguration のクラス → @Bean を関連関数にした構造体。差し替える Beans のフィールドを overrides に入れる。 */
    private List<RItem> configuration(Decl.TypeDecl cfg, Imports imports, Map<String, String> overrides) {
        String name = cfg.simpleName();
        imports.defineLocal(name);
        BodyLowerer lowerer = new BodyLowerer(model, tr.types(), tr.plans(), tr.diags(), imports);
        List<RItem> fns = new ArrayList<>();
        for (Decl.MethodDecl m : cfg.methods()) {
            if (!model.info(DeclInfo.methodKey(m.ref())).has("org.springframework.context.annotation.Bean") || m.body() == null) {
                continue;
            }
            RT ret = tr.types().returnType(m.ref());
            WebGenerator.Bean bean = web.bean(m.ref().returnType().javaName());
            if (bean == null || !web.isConfigBean(bean)) {
                tr.report(m.pos(), name + "." + m.name() + ": only @Bean methods that replace a @Bean of the application are supported");
                continue;
            }
            if (!m.params().isEmpty()) {
                tr.report(m.pos(), name + "." + m.name() + ": @Bean methods with parameters are not supported yet");
                continue;
            }
            overrides.put(bean.field(), name + "::" + Naming.valueName(m.name()) + "()");
            BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(cfg, false, ret, false, null, BodyLowerer.Tail.VALUE, null);
            List<String> docs = new ArrayList<>(SpringTranslator.docs(m.javadoc()));
            if (!docs.isEmpty()) {
                docs.add("");
            }
            docs.add("`@Bean`（アプリの `" + bean.field() + "` を差し替える）");
            fns.add(new RItem.Fn(docs, List.of(), "", Naming.valueName(m.name()), List.of(), new RType(ret.text(imports)),
                    lowerer.body(m, ctx)));
        }
        List<RItem> out = new ArrayList<>();
        List<String> docs = new ArrayList<>(SpringTranslator.docs(cfg.javadoc()));
        docs.add((docs.isEmpty() ? "" : "\n") + "`@TestConfiguration`");
        out.add(new RItem.Struct(docs.stream().flatMap(d -> List.of(d.split("\n", -1)).stream()).toList(), List.of(), name, List.of()));
        out.add(new RItem.Impl(List.of(), name, fns));
        return out;
    }

    private Field field(Decl.TypeDecl t, Decl.FieldDecl f, Imports imports) {
        String rust = Naming.valueName(f.name());
        String q = f.type() instanceof JType.ClassType c ? c.qualifiedName() : f.type().javaName();
        if (q.equals(SERVLET + "MockMvc")) {
            imports.add("mock_mvc::MockMvc");
            imports.add("crate::app");
            return new Field(f.name(), rust, new RT.Unknown(q), "MockMvc", "MockMvc::new(app(state.clone()))", false);
        }
        if (JDBC.contains(q) || q.equals("javax.sql.DataSource")) {
            imports.add("sqlx::PgPool");
            return new Field(f.name(), rust, new RT.Unknown(q), "PgPool", "pool", true);
        }
        WebGenerator.Bean bean = web.bean(q);
        if (bean != null) {
            RT type = bean.type();
            return new Field(f.name(), rust, type, type.text(imports), "state." + bean.field() + ".clone()", false);
        }
        tr.report(f.pos(), t.simpleName() + "." + f.name() + ": injecting " + q + " into a test is not supported yet");
        return null;
    }

    /** new（アプリを組み立て、@BeforeEach を呼ぶ）。 */
    private RItem constructor(Decl.TypeDecl t, List<Field> fields, Map<String, String> overrides, List<Decl.MethodDecl> befores,
                              Imports imports) {
        imports.add("crate::AppState");
        imports.add("crate::Beans");
        List<RStmt> stmts = new ArrayList<>();
        RExpr beans;
        if (overrides.isEmpty()) {
            beans = new RExpr.Path("Beans::new()");
        } else {
            List<RExpr.FieldInit> inits = new ArrayList<>();
            overrides.forEach((field, init) -> inits.add(new RExpr.FieldInit(field, new RExpr.Path(init))));
            if (overrides.size() < web.configBeanCount()) {
                inits.add(new RExpr.FieldInit("..", new RExpr.Path("Beans::new()")));
            }
            beans = new RExpr.StructLit("Beans", inits);
        }
        boolean poolField = fields.stream().anyMatch(Field::usesPool);
        stmts.add(new RStmt.Let("state", false, null, new RExpr.Call(new RExpr.Path("AppState::new"),
                List.of(new RExpr.Path(poolField ? "pool.clone()" : "pool"), beans))));
        List<RExpr.FieldInit> inits = new ArrayList<>();
        for (Field f : fields) {
            inits.add(new RExpr.FieldInit(f.rust(), new RExpr.Path(f.init())));
        }
        RExpr self = new RExpr.StructLit("Self", inits);
        RExpr tail;
        if (befores.isEmpty()) {
            tail = self;
        } else {
            stmts.add(new RStmt.Let("test", false, null, self));
            for (Decl.MethodDecl b : befores) {
                stmts.add(new RStmt.ExprStmt(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("test"), Naming.valueName(b.name()),
                        List.of())), true));
            }
            tail = new RExpr.Path("test");
        }
        if (fields.stream().noneMatch(f -> f.init().contains("state"))) {
            stmts.set(0, new RStmt.Let("_state", false, null, ((RStmt.Let) stmts.get(0)).init()));
        }
        return new RItem.Fn(List.of("`@SpringBootTest`: アプリを組み立て、" + (befores.isEmpty() ? "" : "`@BeforeEach` を呼んで、")
                + "テストのフィクスチャを作る。"), List.of(), "", true, "new", List.of(new RItem.Param("pool", false, new RType("PgPool"))),
                new RType("Self"), new RExpr.Block(stmts, tail, null, false));
    }

    /** テストのクラスのメソッド。インスタンスメソッドは async fn(&self)、static メソッドは関連関数。 */
    private RItem method(Decl.TypeDecl t, Decl.MethodDecl m, Imports imports) {
        BodyLowerer lowerer = new BodyLowerer(model, tr.types(), tr.plans(), tr.diags(), imports);
        RT ret = tr.types().returnType(m.ref());
        BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, !m.isStatic(), ret, false, null, BodyLowerer.Tail.VALUE, null);
        ctx.unwrapErrors = true;
        ctx.hooks = new Hooks(t, lowerer, imports);
        List<RItem.Param> params = new ArrayList<>();
        if (!m.isStatic()) {
            params.add(new RItem.Param("&self", false, null));
        }
        List<RT> types = tr.paramTypes(m);
        for (int i = 0; i < m.params().size(); i++) {
            String rust = Naming.valueName(m.params().get(i).name());
            ctx.declare(m.params().get(i).name(), rust, types.get(i));
            params.add(new RItem.Param(rust, false, new RType(types.get(i).text(imports))));
        }
        RExpr.Block body = lowerer.body(m, ctx);
        return new RItem.Fn(SpringTranslator.docs(m.javadoc()), List.of(), "", !m.isStatic(), Naming.valueName(m.name()), params,
                ret instanceof RT.Unit ? null : new RType(ret.text(imports)), body);
    }

    // ================================================================== 本体の変換（MockMvc・Assertions・JdbcTemplate）

    private final class Hooks implements BodyLowerer.Hooks {
        final Decl.TypeDecl test;
        final BodyLowerer lowerer;
        final Imports imports;

        Hooks(Decl.TypeDecl test, BodyLowerer lowerer, Imports imports) {
            this.test = test;
            this.lowerer = lowerer;
            this.imports = imports;
        }

        @Override
        public boolean stmt(Stmt s, BodyLowerer.Ctx ctx, List<RStmt> out) {
            if (s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.Call c && JDBC.contains(c.method().owner())
                    && c.method().name().equals("update")) {
                out.add(new RStmt.ExprStmt(jdbc(c, ctx, false).expr(), true));
                return true;
            }
            if (s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.Call c && c.method().owner().equals(ASSERTIONS)) {
                RStmt a = assertion(c, ctx);
                if (a != null) {
                    out.add(a);
                }
                return true;
            }
            return false;
        }

        @Override
        public BodyLowerer.RV call(Expr.Call c, BodyLowerer.Ctx ctx) {
            String owner = c.method().owner();
            String name = c.method().name();
            if (owner.equals(test.qualifiedName())) {
                return ownMethod(c, ctx);
            }
            if ((owner.equals(REQUEST_BUILDERS) || owner.equals(RESULT_MATCHERS) || HAMCREST.contains(owner)
                    || owner.startsWith(SERVLET) && !owner.startsWith(SERVLET + "result.MockMvcResultHandlers")) && !SUPPORTED.contains(name)) {
                return BodyLowerer.RV.of(lowerer.unsupported(c.pos(), owner.substring(owner.lastIndexOf('.') + 1) + "." + name),
                        new RT.Unknown(name));
            }
            if (owner.equals(REQUEST_BUILDERS)) {
                imports.add("mock_mvc::" + snake(name));
                return unknown(new RExpr.Call(new RExpr.Path(snake(name)), List.of(uri(c, ctx))));
            }
            if (owner.equals(RESULT_MATCHERS) || HAMCREST.contains(owner)) {
                String fn = switch (name) {
                    case "is" -> "equal_to";
                    default -> snake(name);
                };
                imports.add("mock_mvc::" + fn);
                return unknown(new RExpr.Call(new RExpr.Path(fn), args(c.args(), ctx)));
            }
            if (owner.startsWith(SERVLET) && c.receiver() != null) {
                BodyLowerer.RV recv = lowerer.expr(c.receiver(), ctx);
                switch (name) {
                    case "perform" -> {
                        return unknown(new RExpr.Await(new RExpr.MethodCall(recv.expr(), "perform", args(c.args(), ctx))));
                    }
                    case "andDo", "andReturn", "getResponse" -> {
                        // andDo(print()) などは何もしない。andReturn().getResponse() は結果そのもの。
                        return recv;
                    }
                    case "getContentAsString" -> {
                        return BodyLowerer.RV.of(new RExpr.MethodCall(new RExpr.MethodCall(recv.expr(), "content_as_string", List.of()),
                                "to_string", List.of()), RT.STR);
                    }
                    default -> {
                        return unknown(new RExpr.MethodCall(recv.expr(), snake(name), args(c.args(), ctx)));
                    }
                }
            }
            if (owner.startsWith("org.springframework.test.web.servlet.result.MockMvcResultHandlers")) {
                return unknown(new RExpr.Path("()"));
            }
            if (JDBC.contains(owner)) {
                return jdbc(c, ctx, true);
            }
            if (owner.equals(ASSERTIONS)) {
                return BodyLowerer.RV.of(lowerer.unsupported(c.pos(), "Assertions." + name + " as a value"), RT.UNIT);
            }
            return null;
        }

        private BodyLowerer.RV unknown(RExpr e) {
            return BodyLowerer.RV.of(e, new RT.Unknown("mockmvc"));
        }

        /** テストのクラス自身のメソッド（インスタンスメソッドは async）。 */
        private BodyLowerer.RV ownMethod(Expr.Call c, BodyLowerer.Ctx ctx) {
            Decl.MethodDecl target = test.methods().stream().filter(m -> m.ref().key().equals(c.method().key())).findFirst().orElse(null);
            if (target == null) {
                return null;
            }
            List<RT> types = tr.paramTypes(target);
            List<RExpr> args = new ArrayList<>();
            for (int i = 0; i < c.args().size(); i++) {
                args.add(lowerer.coerce(lowerer.expr(c.args().get(i), ctx), types.get(i)));
            }
            RT ret = tr.types().returnType(target.ref());
            String name = Naming.valueName(target.name());
            if (target.isStatic()) {
                return BodyLowerer.RV.of(new RExpr.Call(new RExpr.Path("Self::" + name), args), ret);
            }
            return BodyLowerer.RV.of(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("self"), name, args)), ret);
        }

        /** {@code get("/todos/{id}", id)} の URI（URI テンプレートの変数を埋める）。 */
        private RExpr uri(Expr.Call c, BodyLowerer.Ctx ctx) {
            List<Expr> vars = new ArrayList<>();
            for (Expr a : c.args().subList(1, c.args().size())) {
                if (a instanceof Expr.NewArray na && na.initializer() != null) {
                    vars.addAll(na.initializer());
                } else {
                    vars.add(a);
                }
            }
            Expr template = SpringTranslator.unwrapCast(c.args().get(0));
            if (vars.isEmpty() || !(template instanceof Expr.Literal l && l.value() instanceof String s)) {
                return lowerer.coerce(lowerer.expr(c.args().get(0), ctx), RT.STR_REF);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^}]*}").matcher(s);
            StringBuilder fmt = new StringBuilder();
            int last = 0;
            List<RExpr> args = new ArrayList<>();
            int i = 0;
            while (m.find() && i < vars.size()) {
                fmt.append(s, last, m.start()).append("{}");
                args.add(display(lowerer.expr(SpringTranslator.unwrapCast(vars.get(i++)), ctx)));
                last = m.end();
            }
            fmt.append(s.substring(last));
            List<RExpr> macroArgs = new ArrayList<>();
            BodyLowerer.inlineFormatArgs(fmt, args);
            macroArgs.add(new RExpr.Lit(BodyLowerer.rustString(fmt.toString())));
            macroArgs.addAll(args);
            return new RExpr.Unary("&", new RExpr.Macro("format", macroArgs));
        }

        /** 表示できる値（enum は名前）。 */
        private RExpr display(BodyLowerer.RV v) {
            RT t = v.type() instanceof RT.Ref r ? r.inner() : v.type();
            return t instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM ? new RExpr.MethodCall(v.expr(), "name", List.of()) : v.expr();
        }

        /** MockMvc の API の引数（String は借用、enum は名前）。 */
        private List<RExpr> args(List<Expr> args, BodyLowerer.Ctx ctx) {
            List<Expr> flat = new ArrayList<>();
            for (Expr a : args) {
                // 可変長引数（param(name, values...)）は並べて渡す。
                if (a instanceof Expr.NewArray na && na.initializer() != null) {
                    flat.addAll(na.initializer());
                } else {
                    flat.add(a);
                }
            }
            List<RExpr> out = new ArrayList<>();
            for (Expr a : flat) {
                BodyLowerer.RV v = lowerer.expr(SpringTranslator.unwrapCast(a), ctx);
                RT t = v.type() instanceof RT.Ref r ? r.inner() : v.type();
                if (t instanceof RT.Str && v.place() != BodyLowerer.Place.NONE) {
                    out.add(lowerer.coerce(v, RT.STR_REF));
                } else {
                    out.add(display(v));
                }
            }
            return out;
        }

        /** JUnit の Assertions → assert! / assert_eq!。 */
        private RStmt assertion(Expr.Call c, BodyLowerer.Ctx ctx) {
            String name = c.method().name();
            List<Expr> args = c.args();
            switch (name) {
                case "assertTrue", "assertFalse" -> {
                    RExpr cond = lowerer.cond(args.get(0), ctx);
                    if (name.equals("assertFalse")) {
                        cond = new RExpr.Unary("!", cond);
                    }
                    return new RStmt.ExprStmt(new RExpr.Macro("assert", withMessage(List.of(cond), args, 1, ctx)), true);
                }
                case "assertEquals", "assertNotEquals" -> {
                    BodyLowerer.RV expected = lowerer.expr(args.get(0), ctx);
                    BodyLowerer.RV actual = lowerer.expr(args.get(1), ctx);
                    RExpr e = comparable(expected, actual);
                    RExpr a = actual.expr();
                    return new RStmt.ExprStmt(new RExpr.Macro(name.equals("assertEquals") ? "assert_eq" : "assert_ne",
                            withMessage(List.of(e, a), args, 2, ctx)), true);
                }
                case "assertNull", "assertNotNull" -> {
                    BodyLowerer.RV v = lowerer.expr(args.get(0), ctx);
                    if (!(v.type() instanceof RT.Opt)) {
                        return name.equals("assertNotNull") ? new RStmt.Let("_", false, null, v.expr())
                                : new RStmt.ExprStmt(new RExpr.Macro("panic", List.of(new RExpr.Lit("\"expected null\""))), true);
                    }
                    RExpr test = new RExpr.MethodCall(v.expr(), name.equals("assertNull") ? "is_none" : "is_some", List.of());
                    return new RStmt.ExprStmt(new RExpr.Macro("assert", withMessage(List.of(test), args, 1, ctx)), true);
                }
                default -> {
                    return new RStmt.ExprStmt(lowerer.unsupported(c.pos(), "Assertions." + name), true);
                }
            }
        }

        /** assertEquals の期待値を実際の値と比べられる形にする（Option なら Some、数値の型を合わせる）。 */
        private RExpr comparable(BodyLowerer.RV expected, BodyLowerer.RV actual) {
            RT a = actual.type();
            RT e = expected.type();
            if (a instanceof RT.Opt o && !(e instanceof RT.Opt) && !(e instanceof RT.Null)) {
                return new RExpr.Call(new RExpr.Path("Some"), List.of(lowerer.coerce(expected, RT.owned(o.inner()))));
            }
            if (a instanceof RT.Prim && e instanceof RT.Prim && !a.equals(e)) {
                return lowerer.coerce(expected, a);
            }
            return expected.expr();
        }

        private List<RExpr> withMessage(List<RExpr> base, List<Expr> args, int messageIndex, BodyLowerer.Ctx ctx) {
            List<RExpr> out = new ArrayList<>(base);
            if (args.size() > messageIndex) {
                out.add(new RExpr.Lit("\"{}\""));
                out.add(lowerer.expr(args.get(messageIndex), ctx).expr());
            }
            return out;
        }

        /**
         * JdbcTemplate の update（value なら更新件数）→ sqlx。{@code ?} のプレースホルダは {@code $1} などにする。
         */
        private BodyLowerer.RV jdbc(Expr.Call c, BodyLowerer.Ctx ctx, boolean value) {
            String name = c.method().name();
            BodyLowerer.RV target = c.receiver() == null ? null : lowerer.expr(c.receiver(), ctx);
            Expr sqlExpr = SpringTranslator.unwrapCast(c.args().get(0));
            if (target == null || !(sqlExpr instanceof Expr.Literal l && l.value() instanceof String sql)) {
                return BodyLowerer.RV.of(lowerer.unsupported(c.pos(), "JdbcTemplate." + name + " with a non-literal SQL"), RT.UNIT);
            }
            List<Expr> binds = new ArrayList<>();
            for (Expr a : c.args().subList(1, c.args().size())) {
                if (a instanceof Expr.NewArray na && na.initializer() != null) {
                    binds.addAll(na.initializer());
                } else {
                    binds.add(a);
                }
            }
            StringBuilder converted = new StringBuilder();
            int n = 0;
            boolean quoted = false;
            for (char ch : sql.toCharArray()) {
                if (ch == '\'') {
                    quoted = !quoted;
                }
                if (ch == '?' && !quoted) {
                    converted.append('$').append(++n);
                } else {
                    converted.append(ch);
                }
            }
            RExpr q;
            switch (name) {
                case "update" -> q = new RExpr.Call(new RExpr.Path("sqlx::query"), List.of(new RExpr.Lit(BodyLowerer.rustString(converted.toString()))));
                default -> {
                    return BodyLowerer.RV.of(lowerer.unsupported(c.pos(), "JdbcTemplate." + name), RT.UNIT);
                }
            }
            for (Expr b : binds) {
                BodyLowerer.RV v = lowerer.expr(SpringTranslator.unwrapCast(b), ctx);
                q = new RExpr.MethodCall(q, "bind", List.of(display(v)));
            }
            RExpr pool = new RExpr.Unary("&", target.expr());
            RExpr exec = new RExpr.MethodCall(new RExpr.Await(new RExpr.MethodCall(q, "execute", List.of(pool))), "unwrap", List.of());
            if (!value) {
                return BodyLowerer.RV.of(exec, RT.UNIT);
            }
            return BodyLowerer.RV.of(new RExpr.Cast(new RExpr.MethodCall(exec, "rows_affected", List.of()), new RType("i32")), RT.I32);
        }
    }

    /** Java のメソッド名 → Rust の名前（{@code is3xxRedirection} → {@code is_3xx_redirection}）。 */
    static String snake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            char prev = i > 0 ? name.charAt(i - 1) : ' ';
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else if (Character.isDigit(c) && Character.isLetter(prev)) {
                sb.append('_').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
