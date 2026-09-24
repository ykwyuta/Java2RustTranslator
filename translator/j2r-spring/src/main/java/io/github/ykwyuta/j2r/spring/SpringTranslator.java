package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JirVisitor;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RType;
import io.github.ykwyuta.j2r.rir.RustPrinter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Spring Boot + MyBatis のアプリ（の Mapper・サービス・ドメイン）を、sqlx を使う Rust のライブラリ crate に変換する
 * （{@code --framework spring}）。構成と変換規則は docs/07-spring-to-rust.md。
 *
 * <pre>
 * src/lib.rs            pub mod ...; と MIGRATOR（schema.sql）
 * src/error.rs          例外クラス → Error のバリアント
 * src/&lt;パッケージ&gt;/*.rs  エンティティ・record・enum → 構造体・列挙型、Mapper → sqlx の関数、サービス → 構造体
 * src/clock.rs          java.time.Clock を使う場合
 * src/mybatis.rs        動的 SQL（&lt;where&gt; / &lt;set&gt;）を使う場合
 * migrations/           schema.sql
 * </pre>
 */
public final class SpringTranslator {
    /** 生成したファイル（出力先のルートからの相対パスと内容）。 */
    public record GeneratedFile(String path, String content) {}

    private final Diagnostics diags;
    private final SpringModel model;
    private final TypeResolver types;
    private final Plans plans = new Plans();
    private final Map<String, MyBatisXml.Mapper> xml;
    private final List<Path> resourceDirs;
    /** sqlx::FromRow を derive する型（select の結果になる型）。 */
    final Set<String> rowTypes = new HashSet<>();
    /** 生成したファイルが使う補助モジュール（clock / mybatis）。 */
    final Set<String> supportModules = new TreeSet<>();
    /** messages.properties（Spring Boot の既定と同じく UTF-8）。 */
    private final java.util.Properties messages = new java.util.Properties();
    /** application.yml / application.properties を平らにしたもの（spring.datasource.url → 値）。 */
    private final Map<String, String> properties = new java.util.TreeMap<>();

    private SpringTranslator(Decl.Program program, List<Path> resourceDirs, Diagnostics diags) {
        this.diags = diags;
        this.resourceDirs = resourceDirs;
        this.model = new SpringModel(program, diags);
        this.types = new TypeResolver(model);
        try {
            this.xml = MyBatisXml.scan(resourceDirs);
            Path msg = findResource("messages.properties");
            if (msg != null) {
                try (var reader = Files.newBufferedReader(msg, StandardCharsets.UTF_8)) {
                    messages.load(reader);
                }
            }
            loadApplicationProperties();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadApplicationProperties() throws IOException {
        Path props = findResource("application.properties");
        if (props != null) {
            java.util.Properties p = new java.util.Properties();
            try (var reader = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
            p.stringPropertyNames().forEach(k -> properties.put(k, p.getProperty(k)));
        }
        for (String name : List.of("application.yml", "application.yaml")) {
            Path yml = findResource(name);
            if (yml != null) {
                Object root = new org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
                        .loadFromString(Files.readString(yml, StandardCharsets.UTF_8));
                flatten("", root);
            }
        }
    }

    private void flatten(String prefix, Object node) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v));
        } else if (node instanceof List<?> l) {
            properties.put(prefix, String.join(",", l.stream().map(String::valueOf).toList()));
        } else if (node != null) {
            properties.put(prefix, String.valueOf(node));
        }
    }

    java.util.Properties messages() {
        return messages;
    }

    /** application.yml の値（なければ null）。 */
    String property(String key) {
        return properties.get(key);
    }

    List<Path> resourceDirs() {
        return resourceDirs;
    }

    /**
     * 変換する。
     *
     * @param program      正規化済みの JIR（DesugarStringConcat を適用したもの）
     * @param resourceDirs Mapper XML と schema.sql を探すディレクトリ
     * @param crateName    生成する crate 名
     */
    public static List<GeneratedFile> translate(Decl.Program program, List<Path> resourceDirs, String crateName, Diagnostics diags) {
        return new SpringTranslator(program, resourceDirs, diags).run(crateName);
    }

    private List<GeneratedFile> run(String crateName) {
        plan();
        // 型ごとのファイル（モジュールパス → ファイル）
        Map<List<String>, List<RFile>> files = new LinkedHashMap<>();
        Map<List<String>, List<String>> reexports = new TreeMap<>(SpringTranslator::compareLists);
        Map<List<String>, Set<String>> children = new TreeMap<>(SpringTranslator::compareLists);
        DomainGenerator domain = new DomainGenerator(this);
        WebGenerator web = new WebGenerator(this, crateName);
        if (web.enabled()) {
            web.prepare();
        }
        MapperGenerator mappers = new MapperGenerator(this);
        ServiceGenerator services = new ServiceGenerator(this);
        for (Decl.TypeDecl t : model.types.values()) {
            SpringModel.Role role = model.role(t.qualifiedName());
            RFile file = switch (role) {
                case MAPPER -> mappers.generate(t, xml.get(t.qualifiedName()));
                case SERVICE -> services.generate(t);
                case ENTITY, RECORD, ENUM, FORM -> t.outer() == null ? domain.generate(t) : null;
                case CONTROLLER, ADVICE -> web.enabled() ? web.handlerFile(t) : null;
                case CONFIGURATION -> web.enabled() ? web.configFile(t) : null;
                default -> null;
            };
            if (file == null) {
                continue;
            }
            List<String> mods = model.modulePath(t.packageName());
            files.computeIfAbsent(mods, k -> new ArrayList<>()).add(file);
            registerModule(children, mods);
            children.get(mods).add(SpringModel.fileModule(t));
            if (role != SpringModel.Role.MAPPER && role != SpringModel.Role.CONTROLLER && role != SpringModel.Role.ADVICE) {
                reexports.computeIfAbsent(mods, k -> new ArrayList<>())
                        .add(SpringModel.fileModule(t) + "::" + SpringModel.rustTypeName(t));
            }
        }
        List<GeneratedFile> out = new ArrayList<>();
        for (List<RFile> list : files.values()) {
            for (RFile f : list) {
                out.add(new GeneratedFile("src/" + f.path(), print(f)));
            }
        }
        if (!plans.errors.isEmpty() || usesError()) {
            out.add(new GeneratedFile("src/error.rs", print(domain.errorFile(plans.errors))));
            registerModule(children, List.of());
            children.get(List.of()).add("error");
        }
        Path schema = findResource("schema.sql");
        if (web.enabled()) {
            out.addAll(web.viewFiles());
            out.add(web.appFile(schema != null && "always".equals(property("spring.sql.init.mode"))));
            out.add(web.configRs());
            out.add(web.mainRs());
            registerModule(children, List.of());
            children.get(List.of()).addAll(List.of("app", "config", "views"));
        }
        for (GeneratedFile f : List.copyOf(out)) {
            for (String support : List.of("clock", "mybatis", "spring_web")) {
                if (f.content().contains("crate::" + support + "::")) {
                    supportModules.add(support);
                }
            }
        }
        for (String support : supportModules) {
            out.add(new GeneratedFile("src/" + support + ".rs", resource("j2r/spring/" + support + ".rs")));
            registerModule(children, List.of());
            children.get(List.of()).add(support);
        }
        // mod.rs / lib.rs
        for (Map.Entry<List<String>, Set<String>> e : children.entrySet()) {
            List<String> mods = e.getKey();
            List<RItem> items = new ArrayList<>();
            for (String child : e.getValue()) {
                items.add(new RItem.ModDecl(true, child));
            }
            for (String re : reexports.getOrDefault(mods, List.of())) {
                items.add(new RItem.Use(List.of(), true, re));
            }
            if (mods.isEmpty()) {
                if (web.enabled()) {
                    items.add(new RItem.Use(List.of(), true, "app::{AppError, AppState, Beans, app, connect}"));
                }
                if (schema != null) {
                    items.add(new RItem.Static(List.of("schema.sql（spring.sql.init）に当たるマイグレーション。起動時に `MIGRATOR.run(&pool)` で流す。"),
                            true, "MIGRATOR", new RType("sqlx::migrate::Migrator"),
                            new RExpr.Macro("sqlx::migrate", List.of(new RExpr.Lit("\"./migrations\"")))));
                }
                out.add(new GeneratedFile("src/lib.rs", print(new RFile("lib.rs",
                        List.of("Generated by Java2RustTranslator (--framework spring). Do not edit by hand; re-run the translator instead."),
                        List.of(), items))));
            } else {
                out.add(new GeneratedFile("src/" + String.join("/", mods) + "/mod.rs", print(new RFile("mod.rs", List.of(), List.of(), items))));
            }
        }
        if (schema != null) {
            try {
                out.add(new GeneratedFile("migrations/0001_schema.sql", Files.readString(schema, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        out.add(new GeneratedFile("Cargo.toml", cargoToml(crateName, web.enabled())));
        out.add(new GeneratedFile(".gitignore", "/target\n/j2r-report.json\n"));
        out.sort(java.util.Comparator.comparing(GeneratedFile::path));
        return out;
    }

    private boolean usesError() {
        return plans.methods.values().stream().anyMatch(Plans.Method::fallible);
    }

    private static void registerModule(Map<List<String>, Set<String>> children, List<String> mods) {
        children.computeIfAbsent(mods, k -> new TreeSet<>());
        for (int i = mods.size(); i > 0; i--) {
            List<String> parent = mods.subList(0, i - 1);
            children.computeIfAbsent(List.copyOf(parent), k -> new TreeSet<>()).add(mods.get(i - 1));
        }
    }

    private static int compareLists(List<String> a, List<String> b) {
        return String.join("/", a).compareTo(String.join("/", b));
    }

    private Path findResource(String name) {
        for (Path d : resourceDirs) {
            Path p = d.resolve(name);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    static String print(RFile f) {
        return RustPrinter.printWrapped(f).replace("// " + Imports.GROUP_BREAK + "\n\n", "");
    }

    private String cargoToml(String crateName, boolean web) {
        String deps = cargoToml(crateName);
        if (!web) {
            return deps;
        }
        return deps + """
                # Spring MVC・Thymeleaf・セッション・ログ
                askama = "0.16"
                askama_web = { version = "0.16", features = ["axum-0.8"] }
                axum = "0.8"
                serde = { version = "1", features = ["derive"] }
                serde_urlencoded = "0.7"
                tokio = { version = "1", features = ["full"] }
                tower-http = { version = "0.7", features = ["fs", "trace"] }
                tower-sessions = "0.14"
                tracing = "0.1"
                tracing-subscriber = { version = "0.3", features = ["env-filter"] }
                """;
    }

    private String cargoToml(String crateName) {
        return """
                # Generated by Java2RustTranslator (--framework spring). Do not edit by hand; re-run the translator instead.
                [package]
                name = "%s"
                version = "0.1.0"
                edition = "2024"
                publish = false

                # 独立したプロジェクトにする（上位のディレクトリの Cargo ワークスペースに入れない）。
                [workspace]

                [dependencies]
                chrono = { version = "0.4", default-features = false, features = ["clock", "std"] }
                sqlx = { version = "0.9", default-features = false, features = ["runtime-tokio", "postgres", "chrono", "migrate", "macros"] }
                thiserror = "2"
                """.formatted(Naming.crateName(crateName));
    }

    private static String resource(String name) {
        try (InputStream in = SpringTranslator.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ================================================================== 関数の形を決める

    private void plan() {
        for (Decl.TypeDecl t : model.types.values()) {
            switch (model.role(t.qualifiedName())) {
                case ENTITY, RECORD, FORM -> planAccessors(t);
                case EXCEPTION -> planError(t);
                default -> { }
            }
        }
        for (Decl.TypeDecl t : model.typesWith(SpringModel.Role.MAPPER)) {
            planMapper(t);
        }
        for (Decl.TypeDecl t : model.types.values()) {
            SpringModel.Role role = model.role(t.qualifiedName());
            if (role == SpringModel.Role.ENTITY || role == SpringModel.Role.RECORD || role == SpringModel.Role.ENUM
                    || role == SpringModel.Role.FORM) {
                planDomainMethods(t, role);
            }
        }
        for (Decl.TypeDecl t : model.typesWith(SpringModel.Role.SERVICE)) {
            planService(t);
        }
    }

    private void planAccessors(Decl.TypeDecl t) {
        for (Decl.MethodDecl m : t.methods()) {
            String g = getterField(m);
            String s = setterField(m);
            if (g != null) {
                plans.getters.put(m.ref().key(), g);
            } else if (s != null) {
                plans.setters.put(m.ref().key(), s);
            }
        }
    }

    /** {@code return this.f;} だけのメソッドなら f。 */
    static String getterField(Decl.MethodDecl m) {
        if (m.isStatic() || m.isConstructor() || !m.params().isEmpty() || m.body() == null || m.body().stmts().size() != 1) {
            return null;
        }
        if (m.body().stmts().get(0) instanceof Stmt.Return r && r.value() != null
                && unwrapCast(r.value()) instanceof Expr.FieldAccess fa && fa.receiver() instanceof Expr.This) {
            return fa.name();
        }
        return null;
    }

    /** {@code this.f = p;} だけのメソッドなら f。 */
    static String setterField(Decl.MethodDecl m) {
        if (m.isStatic() || m.isConstructor() || m.params().size() != 1 || m.body() == null || m.body().stmts().size() != 1) {
            return null;
        }
        if (m.body().stmts().get(0) instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.Assign a
                && a.target() instanceof Expr.FieldAccess fa && fa.receiver() instanceof Expr.This
                && unwrapCast(a.value()) instanceof Expr.Local l && l.name().equals(m.params().get(0).name())) {
            return fa.name();
        }
        return null;
    }

    static Expr unwrapCast(Expr e) {
        while (e instanceof Expr.Cast c) {
            e = c.expr();
        }
        return e;
    }

    private void planError(Decl.TypeDecl t) {
        Decl.MethodDecl ctor = t.methods().stream().filter(Decl.MethodDecl::isConstructor).findFirst().orElse(null);
        List<RT> fields = new ArrayList<>();
        String message = t.simpleName();
        if (ctor != null) {
            if (t.methods().stream().filter(Decl.MethodDecl::isConstructor).count() > 1) {
                diags.report(DiagnosticCode.SPRING_UNSUPPORTED, t.pos(),
                        t.simpleName() + ": only the first constructor is translated to an Error variant");
            }
            for (int i = 0; i < ctor.params().size(); i++) {
                fields.add(types.param(ctor.ref(), i, ctor.params().get(i).type()));
            }
            message = errorMessage(ctor, t.simpleName());
        }
        plans.errors.put(t.qualifiedName(), new Plans.ErrorVariant(SpringModel.errorVariant(t.simpleName()), fields, message));
    }

    /** {@code super("Todo not found: id=" + id)} → thiserror の書式 {@code "Todo not found: id={0}"}。 */
    private static String errorMessage(Decl.MethodDecl ctor, String fallback) {
        if (ctor.body() == null || ctor.body().stmts().isEmpty()
                || !(ctor.body().stmts().get(0) instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.CtorCall call
                && call.isSuper() && call.args().size() == 1)) {
            return fallback;
        }
        Expr arg = call.args().get(0);
        List<Expr> parts = arg instanceof Expr.StringConcat sc ? sc.parts() : List.of(arg);
        StringBuilder sb = new StringBuilder();
        for (Expr p : parts) {
            p = unwrapCast(p);
            if (p instanceof Expr.Literal l && l.value() != null) {
                sb.append(l.value().toString().replace("{", "{{").replace("}", "}}"));
            } else if (p instanceof Expr.Local l) {
                int index = -1;
                for (int i = 0; i < ctor.params().size(); i++) {
                    if (ctor.params().get(i).name().equals(l.name())) {
                        index = i;
                    }
                }
                if (index < 0) {
                    return fallback;
                }
                sb.append('{').append(index).append('}');
            } else {
                return fallback;
            }
        }
        return sb.toString();
    }

    private void planMapper(Decl.TypeDecl t) {
        MyBatisXml.Mapper mapper = xml.get(t.qualifiedName());
        String module = SpringModel.fileModule(t);
        String modulePath = model.rustModule(t) + "::" + module;
        for (Decl.MethodDecl m : t.methods()) {
            if (!m.isAbstract() || m.isStatic()) {
                continue;
            }
            MyBatisXml.Statement st = statement(t, mapper, m);
            List<RT> params = new ArrayList<>();
            for (int i = 0; i < m.params().size(); i++) {
                RT owned = types.param(m.ref(), i, m.params().get(i).type());
                boolean key = st != null && st.keyProperty() != null && owned instanceof RT.Named n && n.kind() == RT.Named.Kind.ENTITY;
                params.add(key ? new RT.Ref(owned, true) : RT.borrowed(owned));
            }
            RT ret = types.returnType(m.ref());
            plans.mapperFns.put(m.ref().key(), new Plans.MapperFn(modulePath, module, Naming.valueName(m.name()), params, ret));
            if (st != null && st.kind() == MyBatisXml.Kind.SELECT && MapperGenerator.rowType(ret) instanceof RT.Named n
                    && n.kind() != RT.Named.Kind.VALUE && n.kind() != RT.Named.Kind.ENUM) {
                rowTypes.add(n.javaName());
            }
        }
    }

    /** Mapper のメソッドに対応する文（XML、なければ @Select などの注釈）。 */
    MyBatisXml.Statement statement(Decl.TypeDecl t, MyBatisXml.Mapper mapper, Decl.MethodDecl m) {
        if (mapper != null && mapper.statements().containsKey(m.name())) {
            return mapper.statements().get(m.name());
        }
        DeclInfo info = model.info(DeclInfo.methodKey(m.ref()));
        for (MyBatisXml.Kind kind : MyBatisXml.Kind.values()) {
            String type = "org.apache.ibatis.annotations." + kind.name().charAt(0) + kind.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            var a = info.get(type);
            if (a != null) {
                Object v = a.value("value");
                String sql = v instanceof List<?> l ? String.join(" ", l.stream().map(Object::toString).toList()) : String.valueOf(v);
                var options = info.get("org.apache.ibatis.annotations.Options");
                String keyProperty = options != null && options.booleanValue("useGeneratedKeys", false) ? options.stringValue("keyProperty") : null;
                String keyColumn = options == null ? null : options.stringValue("keyColumn");
                return MyBatisXml.annotated(m.name(), kind, sql, keyProperty, keyColumn);
            }
        }
        return null;
    }

    private void planDomainMethods(Decl.TypeDecl t, SpringModel.Role role) {
        for (Decl.MethodDecl m : t.methods()) {
            if (m.isConstructor() || m.isAbstract() || plans.getters.containsKey(m.ref().key()) || plans.setters.containsKey(m.ref().key())) {
                continue;
            }
            if (role == SpringModel.Role.ENUM && m.isStatic() && (m.name().equals("values") || m.name().equals("valueOf"))) {
                continue;
            }
            Plans.SelfKind self = m.isStatic() ? Plans.SelfKind.NONE
                    : role == SpringModel.Role.ENUM ? Plans.SelfKind.VALUE
                    : assignsFields(m) ? Plans.SelfKind.MUT : Plans.SelfKind.REF;
            plans.methods.put(m.ref().key(), new Plans.Method(Naming.valueName(m.name()), null, false, false,
                    paramTypes(m), types.returnType(m.ref()), self));
        }
    }

    private static boolean assignsFields(Decl.MethodDecl m) {
        boolean[] found = {false};
        JirVisitor.walk(m.body(), s -> { }, e -> {
            if (e instanceof Expr.Assign a && a.target() instanceof Expr.FieldAccess fa && fa.receiver() instanceof Expr.This) {
                found[0] = true;
            }
        });
        return found[0];
    }

    List<RT> paramTypes(Decl.MethodDecl m) {
        List<RT> out = new ArrayList<>();
        for (int i = 0; i < m.params().size(); i++) {
            out.add(RT.borrowed(types.param(m.ref(), i, m.params().get(i).type())));
        }
        return out;
    }

    /** サービスのメソッドの解析結果。 */
    record ServiceMethodInfo(boolean usesDb, boolean throwsError, Set<String> selfCalls, boolean earlyReturn) {}

    final Map<String, ServiceMethodInfo> serviceInfo = new LinkedHashMap<>();

    private void planService(Decl.TypeDecl t) {
        Map<String, Decl.MethodDecl> methods = new LinkedHashMap<>();
        for (Decl.MethodDecl m : t.methods()) {
            if (!m.isConstructor() && !m.isAbstract()) {
                methods.put(m.ref().key(), m);
            }
        }
        for (Decl.MethodDecl m : methods.values()) {
            boolean[] db = {false};
            boolean[] throwsError = {false};
            Set<String> calls = new TreeSet<>();
            JirVisitor.walk(m.body(), s -> {
                if (s instanceof Stmt.Throw) {
                    throwsError[0] = true;
                }
            }, e -> {
                if (e instanceof Expr.Call c) {
                    if (plans.mapperFns.containsKey(c.method().key())) {
                        db[0] = true;
                    } else if (methods.containsKey(c.method().key())) {
                        calls.add(c.method().key());
                    } else if (c.method().owner().equals("java.util.Optional") && c.method().name().equals("orElseThrow")
                            && c.args().size() == 1) {
                        throwsError[0] = true;
                    }
                }
            });
            serviceInfo.put(m.ref().key(), new ServiceMethodInfo(db[0], throwsError[0], calls, hasEarlyReturn(m)));
        }
        // データベースを使う・エラーを返すことを、呼び出し関係に沿って伝える。
        Set<String> needsDb = new HashSet<>();
        Set<String> fallible = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, ServiceMethodInfo> e : serviceInfo.entrySet()) {
                if (!methods.containsKey(e.getKey())) {
                    continue;
                }
                ServiceMethodInfo info = e.getValue();
                boolean db = info.usesDb() || info.selfCalls().stream().anyMatch(needsDb::contains);
                boolean fail = db || info.throwsError() || info.selfCalls().stream().anyMatch(fallible::contains);
                if (db && needsDb.add(e.getKey())) {
                    changed = true;
                }
                if (fail && fallible.add(e.getKey())) {
                    changed = true;
                }
            }
        }
        Set<String> calledInternally = new HashSet<>();
        for (String key : methods.keySet()) {
            calledInternally.addAll(serviceInfo.get(key).selfCalls());
        }
        for (Decl.MethodDecl m : methods.values()) {
            String key = m.ref().key();
            boolean db = needsDb.contains(key);
            String name = Naming.valueName(m.name());
            String helper = null;
            if (db) {
                if (m.isPrivate()) {
                    helper = name;
                } else if (calledInternally.contains(key) || serviceInfo.get(key).earlyReturn()) {
                    helper = name + "_in";
                }
            }
            plans.methods.put(key, new Plans.Method(name, helper, db, fallible.contains(key), paramTypes(m), types.returnType(m.ref()),
                    m.isStatic() ? Plans.SelfKind.NONE : Plans.SelfKind.REF));
        }
    }

    /** 最後の文以外に return がある（本体をトランザクションの中にそのまま展開できない）。 */
    private static boolean hasEarlyReturn(Decl.MethodDecl m) {
        int[] returns = {0};
        JirVisitor.walk(m.body(), s -> {
            if (s instanceof Stmt.Return) {
                returns[0]++;
            }
        }, e -> {
            if (e instanceof Expr.Lambda) {
                returns[0] -= countReturns(((Expr.Lambda) e).body());
            }
        });
        List<Stmt> stmts = m.body().stmts();
        boolean lastIsReturn = !stmts.isEmpty() && stmts.get(stmts.size() - 1) instanceof Stmt.Return;
        return returns[0] > (lastIsReturn ? 1 : 0);
    }

    private static int countReturns(Stmt.Block b) {
        int[] n = {0};
        JirVisitor.walk(b, s -> {
            if (s instanceof Stmt.Return) {
                n[0]++;
            }
        }, e -> { });
        return n[0];
    }

    // ================================================================== 生成器から使う

    SpringModel model() {
        return model;
    }

    TypeResolver types() {
        return types;
    }

    Plans plans() {
        return plans;
    }

    Diagnostics diags() {
        return diags;
    }

    void report(SourcePos pos, String message) {
        diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos, message);
    }

    /** Java の javadoc を /// の行にする。 */
    static List<String> docs(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : javadoc.strip().split("\n")) {
            out.add(line.strip());
        }
        return out;
    }

    /** ファイルの先頭の //! の行。 */
    static List<String> header(Decl.TypeDecl t, String... extraSources) {
        String file = Path.of(t.pos().file()).getFileName().toString();
        List<String> sources = new ArrayList<>(List.of(file));
        sources.addAll(List.of(extraSources));
        return List.of("Translated from `" + t.simpleName() + "` (" + String.join(", ", sources) + ") by Java2RustTranslator.");
    }
}
