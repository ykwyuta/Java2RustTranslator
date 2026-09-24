package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Annotation;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.JirVisitor;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Web 層（{@code @Controller}・{@code @ControllerAdvice}・Thymeleaf のテンプレート）と、アプリの組み立て
 * （{@code @Configuration} の {@code @Bean}・{@code application.yml}・{@code @SpringBootApplication}）を変換する。
 *
 * <ul>
 *   <li>ハンドラ → axum のハンドラ（{@code @PathVariable} → {@code Path}、{@code @RequestParam} と {@code @ModelAttribute} →
 *       {@code RequestParams}）。ルートは {@code app.rs} の {@code routes()} にまとめる</li>
 *   <li>{@code Model#addAttribute} と {@code return "view"} → ビューの構造体（askama のテンプレート）。
 *       {@code "redirect:..."} → 302、{@code RedirectAttributes} → フラッシュ属性とリダイレクト先のパラメータ</li>
 *   <li>{@code @ExceptionHandler} → {@code AppError} の {@code IntoResponse}</li>
 *   <li>DI コンテナ → {@code AppState}（サービスと Bean）と {@code Beans}（{@code @Bean}。テストで差し替えられる）</li>
 * </ul>
 */
final class WebGenerator {
    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final String MODEL = "org.springframework.ui.Model";
    private static final String MODEL_MAP = "org.springframework.ui.ModelMap";
    private static final String REDIRECT_ATTRIBUTES = "org.springframework.web.servlet.mvc.support.RedirectAttributes";
    private static final String BINDING_RESULT = "org.springframework.validation.BindingResult";
    private static final String ERRORS = "org.springframework.validation.Errors";
    private static final Set<String> SIMPLE = Set.of("java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Boolean",
            "java.lang.Short", "java.lang.Double", "java.lang.Float");

    /** ハンドラ（コントローラのメソッド、または例外ハンドラ）。 */
    record Handler(Decl.TypeDecl owner, Decl.MethodDecl method, String http, String path, boolean advice, List<String> exceptions,
                   String status) {
        String rustName() {
            return Naming.valueName(method.name());
        }

        String module() {
            return SpringModel.fileModule(owner);
        }
    }

    /** ビュー（テンプレート）と、それに渡すモデル。 */
    record View(String name, String struct, LinkedHashMap<String, RT> attrs, List<String> flash, List<String> bindings, String template,
                boolean truthy, boolean present) {}

    /** AppState のフィールド（サービスと @Bean）。 */
    record Bean(String field, RT type, String javaType, String init) {}

    private final SpringTranslator tr;
    private final SpringModel model;
    private final Diagnostics diags;
    private final TemplateTranslator templates;
    private final List<Handler> handlers = new ArrayList<>();
    private final Map<String, List<Map<String, RT>>> sites = new LinkedHashMap<>();
    private final Set<String> flashNames = new LinkedHashSet<>();
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Bean> beansByType = new LinkedHashMap<>();
    private final List<Bean> configBeans = new ArrayList<>();
    private final String crateName;

    WebGenerator(SpringTranslator tr, String crateName) {
        this.tr = tr;
        this.model = tr.model();
        this.diags = tr.diags();
        this.crateName = crateName;
        Path dir = tr.resourceDirs().stream().map(d -> d.resolve("templates")).filter(Files::isDirectory).findFirst()
                .orElse(Path.of("templates"));
        this.templates = new TemplateTranslator(tr, dir);
    }

    boolean enabled() {
        return !model.typesWith(SpringModel.Role.CONTROLLER).isEmpty();
    }

    // ================================================================== 準備（ハンドラ・Bean・ビュー）

    void prepare() {
        collectBeans();
        for (Decl.TypeDecl t : model.typesWith(SpringModel.Role.CONTROLLER)) {
            String prefix = mappingPath(model.info(DeclInfo.typeKey(t.qualifiedName())).get(WEB + "RequestMapping"));
            for (Decl.MethodDecl m : t.methods()) {
                DeclInfo info = model.info(DeclInfo.methodKey(m.ref()));
                for (Annotation a : info.annotations()) {
                    String http = switch (a.type().substring(a.type().lastIndexOf('.') + 1)) {
                        case "GetMapping" -> "get";
                        case "PostMapping" -> "post";
                        case "PutMapping" -> "put";
                        case "DeleteMapping" -> "delete";
                        case "PatchMapping" -> "patch";
                        case "RequestMapping" -> {
                            String method = a.stringValue("method");
                            yield method == null ? "any" : method.toLowerCase(Locale.ROOT);
                        }
                        default -> null;
                    };
                    if (http != null && a.type().startsWith(WEB)) {
                        handlers.add(new Handler(t, m, http, join(prefix, mappingPath(a)), false, List.of(), status(info)));
                    }
                }
            }
        }
        for (Decl.TypeDecl t : model.typesWith(SpringModel.Role.ADVICE)) {
            for (Decl.MethodDecl m : t.methods()) {
                DeclInfo info = model.info(DeclInfo.methodKey(m.ref()));
                Annotation eh = info.get(WEB + "ExceptionHandler");
                if (eh == null) {
                    continue;
                }
                List<String> exceptions = new ArrayList<>();
                if (eh.value("value") instanceof List<?> l) {
                    l.forEach(x -> exceptions.add(x.toString()));
                } else if (eh.value("value") != null) {
                    exceptions.add(eh.value("value").toString());
                }
                if (exceptions.isEmpty()) {
                    m.params().stream().filter(p -> p.type() instanceof JType.ClassType c && isThrowable(c.qualifiedName()))
                            .forEach(p -> exceptions.add(((JType.ClassType) p.type()).qualifiedName()));
                }
                handlers.add(new Handler(t, m, null, null, true, exceptions, status(info)));
            }
        }
        // 1 回目: モデルの属性の型を集める（出力は捨てる）。
        for (Handler h : handlers) {
            lower(h, true);
        }
        if (templates.exists("error/404")) {
            sites.computeIfAbsent("error/404", k -> new ArrayList<>()).add(Map.of());
        }
        // テンプレートを変換し、ビューの構造体を決める。
        for (Map.Entry<String, List<Map<String, RT>>> e : sites.entrySet()) {
            translateView(e.getKey(), e.getValue());
        }
    }

    private boolean isThrowable(String qname) {
        return model.is(qname, SpringModel.Role.EXCEPTION) || qname.startsWith("java.lang.") && qname.endsWith("Exception")
                || qname.equals("java.lang.Throwable") || qname.equals("org.springframework.dao.DataAccessException");
    }

    private static String status(DeclInfo info) {
        Annotation a = info.get(WEB + "ResponseStatus");
        if (a == null) {
            return null;
        }
        String v = a.stringValue("value") != null ? a.stringValue("value") : a.stringValue("code");
        return v == null ? "INTERNAL_SERVER_ERROR" : v;
    }

    private static String mappingPath(Annotation a) {
        if (a == null) {
            return "";
        }
        String v = a.stringValue("value") != null ? a.stringValue("value") : a.stringValue("path");
        return v == null ? "" : v;
    }

    private static String join(String prefix, String path) {
        String p = (prefix.isEmpty() || prefix.startsWith("/") ? prefix : "/" + prefix)
                + (path.isEmpty() || path.startsWith("/") ? path : "/" + path);
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "/" : p;
    }

    /** AppState に入れる Bean（@Configuration の @Bean とサービス）。 */
    private void collectBeans() {
        for (Decl.TypeDecl c : model.typesWith(SpringModel.Role.CONFIGURATION)) {
            for (Decl.MethodDecl m : c.methods()) {
                if (!model.info(DeclInfo.methodKey(m.ref())).has("org.springframework.context.annotation.Bean")) {
                    continue;
                }
                RT type = tr.types().returnType(m.ref());
                if (!m.params().isEmpty()) {
                    tr.report(m.pos(), c.simpleName() + "." + m.name() + ": @Bean methods with parameters are not supported yet");
                }
                String javaType = m.ref().returnType().javaName();
                Bean b = new Bean(Naming.valueName(m.name()), type, javaType,
                        SpringModel.rustTypeName(c) + "::" + Naming.valueName(m.name()) + "()");
                configBeans.add(b);
                beansByType.putIfAbsent(javaType, b);
            }
        }
        for (Decl.TypeDecl s : model.typesWith(SpringModel.Role.SERVICE)) {
            RT type = tr.types().owned(new JType.ClassType(s.qualifiedName()), false);
            beansByType.put(s.qualifiedName(), new Bean(Naming.valueName(s.simpleName()), type, s.qualifiedName(), null));
        }
    }

    // ================================================================== ハンドラの変換

    /** ハンドラの引数の扱い。 */
    private enum ParamKind { MODEL, REDIRECT, BINDING, PATH, REQUEST, FORM, EXCEPTION, UNSUPPORTED }

    /** defaultValue は @RequestParam の defaultValue（なければ null）。 */
    private record HandlerParam(Decl.Param param, ParamKind kind, String name, RT type, boolean valid, String defaultValue) {}

    private List<HandlerParam> params(Handler h) {
        List<HandlerParam> out = new ArrayList<>();
        Decl.MethodDecl m = h.method();
        for (int i = 0; i < m.params().size(); i++) {
            Decl.Param p = m.params().get(i);
            DeclInfo info = model.info(DeclInfo.paramKey(m.ref(), i));
            String qname = p.type() instanceof JType.ClassType c ? c.qualifiedName() : null;
            RT type = tr.types().param(m.ref(), i, p.type());
            boolean valid = info.has("jakarta.validation.Valid") || info.has("org.springframework.validation.annotation.Validated");
            ParamKind kind;
            String name = p.name();
            String defaultValue = null;
            if (MODEL.equals(qname) || MODEL_MAP.equals(qname)) {
                kind = ParamKind.MODEL;
            } else if (REDIRECT_ATTRIBUTES.equals(qname)) {
                kind = ParamKind.REDIRECT;
            } else if (BINDING_RESULT.equals(qname) || ERRORS.equals(qname)) {
                kind = ParamKind.BINDING;
            } else if (h.advice() && qname != null && isThrowable(qname)) {
                kind = ParamKind.EXCEPTION;
            } else if (info.has(WEB + "PathVariable")) {
                kind = ParamKind.PATH;
                name = annotatedName(info.get(WEB + "PathVariable"), p.name());
            } else if (info.has(WEB + "RequestParam") || p.type() instanceof JType.Primitive || SIMPLE.contains(qname)) {
                kind = ParamKind.REQUEST;
                Annotation a = info.get(WEB + "RequestParam");
                name = a == null ? p.name() : annotatedName(a, p.name());
                defaultValue = a == null ? null : a.stringValue("defaultValue");
                if (a != null && defaultValue == null && !a.booleanValue("required", true) && !(type instanceof RT.Opt)
                        && !(p.type() instanceof JType.Primitive)) {
                    type = new RT.Opt(type);
                }
            } else if (qname != null && model.is(qname, SpringModel.Role.FORM)) {
                kind = ParamKind.FORM;
                Annotation a = info.get(SpringModel.MODEL_ATTRIBUTE);
                String simple = qname.substring(qname.lastIndexOf('.') + 1);
                name = a == null ? Character.toLowerCase(simple.charAt(0)) + simple.substring(1) : annotatedName(a, p.name());
            } else {
                kind = ParamKind.UNSUPPORTED;
            }
            out.add(new HandlerParam(p, kind, name, type, valid, defaultValue));
        }
        return out;
    }

    private static String annotatedName(Annotation a, String fallback) {
        String v = a.stringValue("value") != null ? a.stringValue("value") : a.stringValue("name");
        return v == null || v.isEmpty() ? fallback : v;
    }

    /** 変換したハンドラ。 */
    private record Lowered(RItem fn, List<RItem> extra) {}

    /** ハンドラを変換する（dryRun ならモデルの属性の型を集めるだけ）。 */
    private Lowered lower(Handler h, boolean dryRun) {
        Imports imports = dryRun ? new Imports("dry") : currentImports;
        Diagnostics d = dryRun ? new Diagnostics() : diags;
        BodyLowerer lowerer = new BodyLowerer(model, tr.types(), tr.plans(), d, imports);
        List<HandlerParam> params = params(h);
        Hooks hooks = new Hooks(h, params, dryRun, imports, lowerer);
        BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(h.owner(), false, new RT.Unknown("response"), !h.advice(), null,
                BodyLowerer.Tail.VALUE, null);
        ctx.hooks = hooks;
        List<RItem.Param> rustParams = new ArrayList<>();
        List<RStmt> prelude = new ArrayList<>();
        List<RItem> extra = new ArrayList<>();
        if (!h.advice()) {
            imports.add("axum::extract::State");
            imports.add("crate::app::AppState");
            imports.add("crate::app::AppError");
            imports.add("axum::response::Response");
            rustParams.add(new RItem.Param("State(state)", false, new RType("State<AppState>")));
        }
        // @PathVariable
        List<HandlerParam> pathParams = params.stream().filter(p -> p.kind() == ParamKind.PATH).toList();
        if (!pathParams.isEmpty()) {
            imports.add("axum::extract::Path");
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (HandlerParam p : pathParams) {
                String rust = Naming.valueName(p.param().name());
                names.add(rust);
                types.add(p.type().text(imports));
                ctx.declare(p.param().name(), rust, p.type());
            }
            rustParams.add(names.size() == 1 ? new RItem.Param("Path(" + names.get(0) + ")", false, new RType("Path<" + types.get(0) + ">"))
                    : new RItem.Param("Path((" + String.join(", ", names) + "))", false,
                    new RType("Path<(" + String.join(", ", types) + ")>")));
        }
        boolean usesFlash = !h.advice() && (hooks.setsFlash || !dryRun && rendersFlash(h));
        if (usesFlash) {
            imports.add("crate::spring_web::Flash");
            rustParams.add(new RItem.Param(hooks.setsFlash ? "mut flash" : "flash", false, new RType("Flash")));
        }
        // @RequestParam と @ModelAttribute（リクエストパラメータ）
        List<HandlerParam> requestParams = params.stream().filter(p -> p.kind() == ParamKind.REQUEST).toList();
        boolean needsParams = !requestParams.isEmpty() || params.stream().anyMatch(p -> p.kind() == ParamKind.FORM);
        if (needsParams) {
            imports.add("crate::spring_web::RequestParams");
            rustParams.add(new RItem.Param("params", false, new RType("RequestParams")));
        }
        if (!requestParams.isEmpty()) {
            String struct = toPascal(h.method().name()) + "Params";
            List<RItem.Field> fields = new ArrayList<>();
            for (HandlerParam p : requestParams) {
                RT owned = RT.owned(p.type());
                String field = Naming.valueName(p.name());
                RT fieldType = p.defaultValue() != null && !(owned instanceof RT.Opt) ? new RT.Opt(owned) : owned;
                fields.add(new RItem.Field("", (field.equals(p.name()) ? "" : "#[serde(rename = " + BodyLowerer.rustString(p.name()) + ")] ")
                        + field, new RType(fieldType.text(imports))));
                String rust = Naming.valueName(p.param().name());
                RExpr value = new RExpr.Field(new RExpr.Path("request"), field);
                RT local = owned;
                if (p.defaultValue() != null) {
                    // @RequestParam(defaultValue = "...")
                    boolean string = owned instanceof RT.Str;
                    RExpr fallback = new RExpr.Lit(string ? BodyLowerer.rustString(p.defaultValue()) : p.defaultValue());
                    value = string ? new RExpr.MethodCall(new RExpr.MethodCall(value, "as_deref", List.of()), "unwrap_or", List.of(fallback))
                            : new RExpr.MethodCall(value, "unwrap_or", List.of(fallback));
                    local = string ? RT.STR_REF : owned;
                } else if (owned instanceof RT.Opt o && o.inner() instanceof RT.Str) {
                    value = new RExpr.MethodCall(value, "as_deref", List.of());
                    local = new RT.Opt(RT.STR_REF);
                } else if (owned instanceof RT.Str) {
                    value = new RExpr.MethodCall(value, "as_str", List.of());
                    local = RT.STR_REF;
                }
                ctx.declare(p.param().name(), rust, local);
                prelude.add(new RStmt.Let(rust, false, null, value));
            }
            imports.add("serde::Deserialize");
            extra.add(new RItem.Struct(List.of("`" + h.method().name() + "` の `@RequestParam`。"), List.of("derive(Debug, Deserialize)"),
                    struct, fields));
            prelude.add(0, new RStmt.Let("request", false, new RType(struct), new RExpr.Try(new RExpr.MethodCall(new RExpr.Path("params"),
                    "parse", List.of()))));
        }
        // フォーム（@ModelAttribute + BindingResult）
        for (int i = 0; i < params.size(); i++) {
            HandlerParam p = params.get(i);
            switch (p.kind()) {
                case FORM -> {
                    String rust = Naming.valueName(p.param().name());
                    boolean hasBinding = i + 1 < params.size() && params.get(i + 1).kind() == ParamKind.BINDING;
                    String binding = hasBinding ? Naming.valueName(params.get(i + 1).param().name()) : rust + "_binding";
                    RT formType = RT.owned(p.type());
                    ctx.declare(p.param().name(), rust, formType);
                    if (hasBinding) {
                        ctx.declare(params.get(i + 1).param().name(), binding, TemplateTranslator.BINDING);
                    }
                    prelude.add(new RStmt.Let("(" + rust + ", " + (p.valid() ? "mut " : "") + binding + ")", false, null,
                            new RExpr.Call(new RExpr.Path(formType.text(imports) + "::bind"), List.of(new RExpr.Path("&params")))));
                    if (p.valid()) {
                        prelude.add(new RStmt.ExprStmt(new RExpr.MethodCall(new RExpr.Path(rust), "validate",
                                List.of(new RExpr.Path("&mut " + binding))), true));
                    }
                    if (!hasBinding) {
                        // BindingResult を受け取らないハンドラは、エラーがあれば 400（Spring の BindException）。
                        imports.add("crate::spring_web::BadRequest");
                        prelude.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.MethodCall(new RExpr.Path(binding), "has_errors", List.of()),
                                RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(new RExpr.Call(new RExpr.Path("Err"),
                                        List.of(new RExpr.Path("BadRequest(\"binding errors\".to_string()).into()")))), true))), null), false));
                    }
                    hooks.forms.put(p.name(), new FormLocal(rust, binding, formType));
                }
                case EXCEPTION -> {
                    imports.add("crate::error::Error");
                    ctx.declare(p.param().name(), Naming.valueName(p.param().name()), new RT.ErrorValue());
                    boolean used = containsLocal(h.method().body(), p.param().name());
                    rustParams.add(new RItem.Param((used ? "" : "_") + Naming.valueName(p.param().name()), false, new RType("&Error")));
                }
                case BINDING, MODEL, REDIRECT, PATH, REQUEST -> { }
                case UNSUPPORTED -> tr.report(p.param().type() == null ? h.method().pos() : h.method().pos(),
                        h.owner().simpleName() + "." + h.method().name() + ": parameter type " + p.param().type().javaName()
                                + " is not supported yet");
            }
        }
        if (hooks.usesRedirectParams) {
            prelude.add(new RStmt.Let("redirect_params", true, new RType("Vec<(&str, String)>"), new RExpr.Path("Vec::new()")));
        }
        RExpr.Block body = lowerer.body(h.method(), ctx);
        List<RStmt> stmts = new ArrayList<>(prelude);
        stmts.addAll(body.stmts());
        RType ret = h.advice() ? new RType("Response") : new RType("Result<Response, AppError>");
        if (h.advice()) {
            imports.add("axum::response::Response");
        }
        List<String> docs = new ArrayList<>(SpringTranslator.docs(h.method().javadoc()));
        if (!docs.isEmpty()) {
            docs.add("");
        }
        docs.add(h.advice() ? "`@ExceptionHandler(" + String.join(", ", h.exceptions().stream().map(x -> x.substring(x.lastIndexOf('.') + 1))
                .toList()) + ")`" : "`" + h.http().toUpperCase(Locale.ROOT) + " " + h.path() + "`");
        RExpr.Block fullBody = new RExpr.Block(stmts, body.tail(), null, false);
        if (!h.advice() && !io.github.ykwyuta.j2r.rir.RustPrinter.print(fullBody).contains("state.")) {
            // AppState を使わないハンドラは State を受け取らない。
            rustParams.remove(0);
            imports.remove("axum::extract::State");
            imports.remove("crate::app::AppState");
        }
        RItem fn = new RItem.Fn(docs, List.of(), "pub", !h.advice(), h.rustName(), rustParams, ret, fullBody);
        return new Lowered(fn, extra);
    }

    private static boolean containsLocal(Stmt.Block body, String name) {
        boolean[] found = {false};
        JirVisitor.walk(body, s -> { }, e -> {
            if (e instanceof Expr.Local l && l.name().equals(name)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private boolean rendersFlash(Handler h) {
        return hooksRendered(h).stream().anyMatch(v -> views.containsKey(v) && !views.get(v).flash().isEmpty());
    }

    /** ハンドラが返すビューの名前。 */
    private static Set<String> hooksRendered(Handler h) {
        Set<String> out = new HashSet<>();
        JirVisitor.walk(h.method().body(), s -> {
            if (s instanceof Stmt.Return r && SpringTranslator.unwrapCast(r.value()) instanceof Expr.Literal l && l.value() instanceof String v
                    && !v.startsWith("redirect:")) {
                out.add(v);
            }
        }, e -> { });
        return out;
    }

    private record FormLocal(String rust, String binding, RT type) {}

    /** コントローラの Model・RedirectAttributes・BindingResult・ビュー名の return の変換。 */
    private final class Hooks implements BodyLowerer.Hooks {
        final Handler handler;
        final Map<String, HandlerParam> byName = new HashMap<>();
        final boolean dryRun;
        final Imports imports;
        final BodyLowerer lowerer;
        final Deque<Map<String, RT>> modelScopes = new ArrayDeque<>();
        final Map<String, FormLocal> forms = new LinkedHashMap<>();
        boolean setsFlash;
        boolean usesRedirectParams;

        Hooks(Handler handler, List<HandlerParam> params, boolean dryRun, Imports imports, BodyLowerer lowerer) {
            this.handler = handler;
            this.dryRun = dryRun;
            this.imports = imports;
            this.lowerer = lowerer;
            params.forEach(p -> byName.put(p.param().name(), p));
            JirVisitor.walk(handler.method().body(), s -> { }, e -> {
                if (e instanceof Expr.Call c && c.receiver() instanceof Expr.Local l && byName.containsKey(l.name())) {
                    ParamKind k = byName.get(l.name()).kind();
                    if (k == ParamKind.REDIRECT && c.method().name().equals("addFlashAttribute")) {
                        setsFlash = true;
                    } else if (k == ParamKind.REDIRECT && c.method().name().equals("addAttribute")) {
                        usesRedirectParams = true;
                    }
                }
            });
            modelScopes.push(new LinkedHashMap<>());
        }

        private ParamKind kindOf(Expr receiver) {
            if (receiver instanceof Expr.Local l && byName.containsKey(l.name())) {
                return byName.get(l.name()).kind();
            }
            return null;
        }

        @Override
        public void enterBlock() {
            modelScopes.push(new LinkedHashMap<>(modelScopes.peek()));
        }

        @Override
        public void exitBlock() {
            modelScopes.pop();
        }

        @Override
        public boolean stmt(Stmt s, BodyLowerer.Ctx ctx, List<RStmt> out) {
            if (!(s instanceof Stmt.ExprStmt es) || !(es.expr() instanceof Expr.Call c)) {
                return false;
            }
            ParamKind kind = kindOf(c.receiver());
            if (kind == ParamKind.MODEL && c.method().name().equals("addAttribute") && c.args().size() == 2
                    && SpringTranslator.unwrapCast(c.args().get(0)) instanceof Expr.Literal l && l.value() instanceof String name) {
                BodyLowerer.RV v = lowerer.expr(c.args().get(1), ctx);
                RT type = v.type() instanceof RT.Null ? new RT.Opt(new RT.Unknown("null")) : RT.owned(v.type());
                out.add(new RStmt.Let(modelLocal(name), false, null, lowerer.coerce(v, type)));
                modelScopes.peek().put(name, type);
                return true;
            }
            if (kind == ParamKind.REDIRECT && c.args().size() == 2
                    && SpringTranslator.unwrapCast(c.args().get(0)) instanceof Expr.Literal l && l.value() instanceof String name) {
                BodyLowerer.RV v = lowerer.expr(c.args().get(1), ctx);
                RExpr value = v.type() instanceof RT.Str || v.type() instanceof RT.StrRef ? lowerer.coerce(v, RT.STR)
                        : new RExpr.MethodCall(v.expr(), v.type() instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM ? "name" : "to_string",
                        List.of());
                if (v.type() instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
                    value = new RExpr.MethodCall(value, "to_string", List.of());
                }
                if (c.method().name().equals("addFlashAttribute")) {
                    flashNames.add(name);
                    out.add(new RStmt.ExprStmt(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("flash"), "set",
                            List.of(new RExpr.Lit(BodyLowerer.rustString(name)), value))), true));
                    return true;
                }
                if (c.method().name().equals("addAttribute")) {
                    out.add(new RStmt.ExprStmt(new RExpr.MethodCall(new RExpr.Path("redirect_params"), "push",
                            List.of(new RExpr.Template(List.of("(" + BodyLowerer.rustString(name) + ", ", value, ")")))), true));
                    return true;
                }
            }
            return false;
        }

        @Override
        public BodyLowerer.RV call(Expr.Call c, BodyLowerer.Ctx ctx) {
            if (kindOf(c.receiver()) == ParamKind.BINDING) {
                String binding = forms.values().stream().map(FormLocal::binding).findFirst().orElse("binding_result");
                switch (c.method().name() + "/" + c.args().size()) {
                    case "hasErrors/0" -> {
                        return BodyLowerer.RV.of(new RExpr.MethodCall(new RExpr.Path(binding), "has_errors", List.of()), RT.BOOL);
                    }
                    case "hasFieldErrors/1" -> {
                        return BodyLowerer.RV.of(new RExpr.MethodCall(new RExpr.Path(binding), "has_field_errors",
                                List.of(lowerer.expr(c.args().get(0), ctx).expr())), RT.BOOL);
                    }
                    default -> {
                        return BodyLowerer.RV.of(lowerer.unsupported(c.pos(), "BindingResult." + c.method().name()), RT.UNIT);
                    }
                }
            }
            ParamKind kind = kindOf(c.receiver());
            if (kind == ParamKind.MODEL || kind == ParamKind.REDIRECT) {
                return BodyLowerer.RV.of(lowerer.unsupported(c.pos(), c.method().name() + " (use it as a statement)"), RT.UNIT);
            }
            return null;
        }

        @Override
        public BodyLowerer.RV fieldAccess(Expr.FieldAccess fa, BodyLowerer.Ctx ctx) {
            if (!(fa.receiver() instanceof Expr.This)) {
                return null;
            }
            Decl.FieldDecl f = handler.owner().fields().stream().filter(x -> x.name().equals(fa.name())).findFirst().orElse(null);
            if (f == null || !(f.type() instanceof JType.ClassType c)) {
                return null;
            }
            Bean b = beansByType.get(c.qualifiedName());
            if (b == null || handler.advice()) {
                return BodyLowerer.RV.of(lowerer.unsupported(fa.pos(), "injected field " + fa.name() + " (" + c.qualifiedName() + ")"),
                        new RT.Unknown(fa.name()));
            }
            return new BodyLowerer.RV(new RExpr.Field(new RExpr.Path("state"), b.field()), b.type(), BodyLowerer.Place.FIELD, false, false);
        }

        @Override
        public RExpr returnValue(Expr value, BodyLowerer.Ctx ctx) {
            if (SpringTranslator.unwrapCast(value) instanceof Expr.StringConcat sc && !sc.parts().isEmpty()
                    && sc.parts().get(0) instanceof Expr.Literal first && first.value() instanceof String s && s.startsWith("redirect:")) {
                // "redirect:/books/" + id
                List<Expr> parts = new ArrayList<>(sc.parts());
                parts.set(0, new Expr.Literal(s.substring("redirect:".length()), first.type(), first.pos()));
                BodyLowerer.RV path = lowerer.expr(new Expr.StringConcat(parts, sc.type(), sc.pos()), ctx);
                imports.add("crate::spring_web::redirect");
                RExpr response = new RExpr.Call(new RExpr.Path("redirect"), List.of(new RExpr.Unary("&", path.expr()),
                        new RExpr.Path(usesRedirectParams ? "&redirect_params" : "&[]")));
                return handler.advice() ? response : new RExpr.Call(new RExpr.Path("Ok"), List.of(response));
            }
            if (!(SpringTranslator.unwrapCast(value) instanceof Expr.Literal l) || !(l.value() instanceof String view)) {
                return lowerer.unsupported(value.pos(), "handlers returning something other than a view name");
            }
            RExpr response;
            if (view.startsWith("redirect:")) {
                imports.add("crate::spring_web::redirect");
                RExpr path = new RExpr.Lit(BodyLowerer.rustString(view.substring("redirect:".length())));
                response = new RExpr.Call(new RExpr.Path("redirect"), List.of(path, new RExpr.Path(usesRedirectParams ? "&redirect_params" : "&[]")));
            } else if (view.startsWith("forward:")) {
                return lowerer.unsupported(value.pos(), "forward:");
            } else {
                response = render(view);
            }
            return handler.advice() ? response : new RExpr.Call(new RExpr.Path("Ok"), List.of(response));
        }

        /** ビューの構造体を作ってレスポンスにする。 */
        private RExpr render(String view) {
            Map<String, RT> attrs = new LinkedHashMap<>(modelScopes.peek());
            forms.forEach((name, f) -> attrs.putIfAbsent(name, f.type()));
            if (dryRun) {
                sites.computeIfAbsent(view, k -> new ArrayList<>()).add(attrs);
                return new RExpr.Path("todo!()");
            }
            View v = views.get(view);
            if (v == null) {
                return lowerer.unsupported(SpringTranslatorPos.of(handler), "view " + view);
            }
            imports.add("crate::views::" + v.struct());
            imports.add("axum::response::IntoResponse");
            List<RExpr.FieldInit> fields = new ArrayList<>();
            for (Map.Entry<String, RT> a : v.attrs().entrySet()) {
                String name = a.getKey();
                RT fieldType = a.getValue();
                RExpr value;
                if (modelScopes.peek().containsKey(name)) {
                    RT localType = modelScopes.peek().get(name);
                    value = lowerer.coerce(new BodyLowerer.RV(new RExpr.Path(modelLocal(name)), localType, BodyLowerer.Place.LOCAL, true, false),
                            fieldType);
                } else if (forms.containsKey(name)) {
                    FormLocal f = forms.get(name);
                    value = lowerer.coerce(new BodyLowerer.RV(new RExpr.Path(f.rust()), f.type(), BodyLowerer.Place.LOCAL, true, false), fieldType);
                } else if (v.flash().contains(name)) {
                    value = flashValue(name);
                } else {
                    value = new RExpr.Path("None");
                }
                fields.add(new RExpr.FieldInit(Naming.valueName(name), value));
            }
            for (String name : v.flash()) {
                if (!v.attrs().containsKey(name)) {
                    fields.add(new RExpr.FieldInit(Naming.valueName(name), flashValue(name)));
                }
            }
            for (String form : v.bindings()) {
                FormLocal f = forms.get(form);
                imports.add("crate::spring_web::BindingResult");
                fields.add(new RExpr.FieldInit(Naming.valueName(form) + "_binding",
                        new RExpr.Path(f != null ? f.binding() : "BindingResult::default()")));
            }
            RExpr lit = new RExpr.StructLit(v.struct(), fields);
            if (handler.status() != null) {
                imports.add("axum::http::StatusCode");
                return new RExpr.MethodCall(new RExpr.Template(List.of("(StatusCode::" + handler.status() + ", ", lit, ")")), "into_response",
                        List.of());
            }
            return new RExpr.MethodCall(lit, "into_response", List.of());
        }

        private RExpr flashValue(String name) {
            if (handler.advice()) {
                return new RExpr.Path("None");
            }
            return new RExpr.MethodCall(new RExpr.Path("flash"), "get", List.of(new RExpr.Lit(BodyLowerer.rustString(name))));
        }
    }

    /** 診断の位置（ハンドラのメソッド）。 */
    private static final class SpringTranslatorPos {
        private SpringTranslatorPos() {}

        static io.github.ykwyuta.j2r.common.SourcePos of(Handler h) {
            return h.method().pos();
        }
    }

    private static String modelLocal(String attr) {
        return "model_" + Naming.toSnakeCase(attr);
    }

    // ================================================================== ビュー（テンプレート）

    private void translateView(String view, List<Map<String, RT>> viewSites) {
        if (!templates.exists(view)) {
            diags.report(DiagnosticCode.SPRING_UNSUPPORTED, io.github.ykwyuta.j2r.common.SourcePos.UNKNOWN,
                    "template templates/" + view + ".html not found");
            return;
        }
        // すべてのハンドラで入れる属性はそのままの型、入れないハンドラがあれば Option。
        LinkedHashMap<String, RT> attrs = new LinkedHashMap<>();
        for (Map<String, RT> site : viewSites) {
            for (Map.Entry<String, RT> e : site.entrySet()) {
                attrs.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, RT> e : attrs.entrySet()) {
            boolean everywhere = viewSites.stream().allMatch(s -> s.containsKey(e.getKey()));
            if (!everywhere && !(e.getValue() instanceof RT.Opt)) {
                e.setValue(new RT.Opt(e.getValue()));
            }
        }
        Set<String> usedFlash = new LinkedHashSet<>();
        TemplateTranslator.Result r = templates.translate(view, name -> {
            if (attrs.containsKey(name)) {
                return new TemplateTranslator.TV(Naming.valueName(name), attrs.get(name), true);
            }
            if (flashNames.contains(name)) {
                usedFlash.add(name);
                return new TemplateTranslator.TV(Naming.valueName(name), new RT.Opt(RT.STR), true);
            }
            return null;
        });
        // 属性とフラッシュ属性の両方にあるものは、モデルを優先してフラッシュ属性から補う（Option にする）。
        List<String> flash = new ArrayList<>(usedFlash);
        for (String name : flashNames) {
            if (attrs.containsKey(name) && templatesUse(r.text(), Naming.valueName(name))) {
                flash.add(name);
                if (!(attrs.get(name) instanceof RT.Opt)) {
                    attrs.put(name, new RT.Opt(attrs.get(name)));
                }
            }
        }
        List<String> bindings = new ArrayList<>();
        for (String form : r.formBindings()) {
            String javaName = attrs.keySet().stream().filter(a -> Naming.valueName(a).equals(form)).findFirst().orElse(form);
            bindings.add(javaName);
        }
        views.put(view, new View(view, viewStruct(view), attrs, flash, bindings, r.text(), r.usesTruthy(), r.usesPresent()));
    }

    private static boolean templatesUse(String text, String name) {
        return text.contains(" " + name + " ") || text.contains(" " + name + ".") || text.contains("= " + name + " ");
    }

    private static String viewStruct(String view) {
        return toPascal(view.replace('/', '_').replace('-', '_')) + "View";
    }

    private static String toPascal(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : Naming.toSnakeCase(s).split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    // ================================================================== 出力

    private Imports currentImports;

    /** コントローラ・例外ハンドラのファイル。 */
    RFile handlerFile(Decl.TypeDecl t) {
        String module = model.rustModule(t) + "::" + SpringModel.fileModule(t);
        currentImports = new Imports(module);
        List<RItem> items = new ArrayList<>();
        for (Handler h : handlers) {
            if (h.owner() == t) {
                Lowered l = lower(h, false);
                items.addAll(l.extra());
                items.add(l.fn());
            }
        }
        List<RItem> all = new ArrayList<>(currentImports.items());
        all.addAll(items);
        String path = String.join("/", model.modulePath(t.packageName()));
        return new RFile((path.isEmpty() ? "" : path + "/") + SpringModel.fileModule(t) + ".rs", SpringTranslator.header(t), List.of(), all);
    }

    /** @Configuration のクラス（@Bean のメソッドを関連関数にする）。 */
    RFile configFile(Decl.TypeDecl t) {
        String module = model.rustModule(t) + "::" + SpringModel.fileModule(t);
        Imports imports = new Imports(module);
        String name = SpringModel.rustTypeName(t);
        imports.defineLocal(name);
        BodyLowerer lowerer = new BodyLowerer(model, tr.types(), tr.plans(), diags, imports);
        List<RItem> methods = new ArrayList<>();
        for (Decl.MethodDecl m : t.methods()) {
            if (!model.info(DeclInfo.methodKey(m.ref())).has("org.springframework.context.annotation.Bean") || m.body() == null) {
                continue;
            }
            RT ret = tr.types().returnType(m.ref());
            BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, false, ret, false, null, BodyLowerer.Tail.VALUE, null);
            List<String> docs = new ArrayList<>(SpringTranslator.docs(m.javadoc()));
            docs.add((docs.isEmpty() ? "" : "\n") + "`@Bean`");
            methods.add(new RItem.Fn(docs.stream().flatMap(d -> List.of(d.split("\n", -1)).stream()).toList(), List.of(), "pub",
                    Naming.valueName(m.name()), List.of(), new RType(ret.text(imports)), lowerer.body(m, ctx)));
        }
        List<RItem> items = new ArrayList<>(imports.items());
        items.add(new RItem.Struct(SpringTranslator.docs(t.javadoc()), name, List.of()));
        items.add(new RItem.Impl(List.of(), name, methods));
        String path = String.join("/", model.modulePath(t.packageName()));
        return new RFile((path.isEmpty() ? "" : path + "/") + SpringModel.fileModule(t) + ".rs", SpringTranslator.header(t), List.of(), items);
    }

    /** views.rs と templates/ と static/。 */
    List<SpringTranslator.GeneratedFile> viewFiles() {
        List<SpringTranslator.GeneratedFile> out = new ArrayList<>();
        Imports imports = new Imports("crate::views");
        imports.add("askama::Template");
        imports.add("askama_web::WebTemplate");
        List<RItem> items = new ArrayList<>();
        boolean truthy = false;
        boolean present = false;
        for (View v : views.values()) {
            List<RItem.Field> fields = new ArrayList<>();
            v.attrs().forEach((name, type) -> fields.add(new RItem.Field("pub", Naming.valueName(name), new RType(type.text(imports)))));
            for (String f : v.flash()) {
                if (!v.attrs().containsKey(f)) {
                    fields.add(new RItem.Field("pub", Naming.valueName(f), new RType("Option<String>")));
                }
            }
            for (String form : v.bindings()) {
                imports.add("crate::spring_web::BindingResult");
                fields.add(new RItem.Field("pub", Naming.valueName(form) + "_binding", new RType("BindingResult")));
            }
            items.add(new RItem.Struct(List.of("templates/" + v.name() + ".html"),
                    List.of("derive(Template, WebTemplate)", "template(path = " + BodyLowerer.rustString(v.name() + ".html") + ")"),
                    v.struct(), fields));
            out.add(new SpringTranslator.GeneratedFile("templates/" + v.name() + ".html", v.template()));
            truthy |= v.truthy();
            present |= v.present();
        }
        if (truthy) {
            imports.add("crate::spring_web::Truthy");
        }
        if (present) {
            imports.add("crate::spring_web::Present");
        }
        List<RItem> all = new ArrayList<>(imports.items());
        all.addAll(items);
        out.add(new SpringTranslator.GeneratedFile("src/views.rs", SpringTranslator.print(new RFile("views.rs",
                List.of("Thymeleaf のテンプレート（templates/）に渡すモデル。Translated by Java2RustTranslator."), List.of(), all))));
        for (Path dir : tr.resourceDirs()) {
            Path stat = dir.resolve("static");
            if (Files.isDirectory(stat)) {
                try (var walk = Files.walk(stat)) {
                    for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                        out.add(new SpringTranslator.GeneratedFile("static/" + stat.relativize(f).toString().replace('\\', '/'),
                                Files.readString(f)));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return out;
    }

    /** app.rs（AppState・Beans・ルート・AppError・接続）。 */
    SpringTranslator.GeneratedFile appFile(boolean migrate) {
        Imports imports = new Imports("crate::app");
        List<RItem> items = new ArrayList<>();
        // Beans
        List<RItem.Field> beanFields = new ArrayList<>();
        List<RExpr.FieldInit> beanInits = new ArrayList<>();
        for (Bean b : configBeans) {
            beanFields.add(new RItem.Field("pub", b.field(), new RType(b.type().text(imports))));
            Decl.TypeDecl cfg = model.typesWith(SpringModel.Role.CONFIGURATION).stream()
                    .filter(c -> b.init().startsWith(SpringModel.rustTypeName(c) + "::")).findFirst().orElseThrow();
            imports.add(model.rustModule(cfg) + "::" + SpringModel.rustTypeName(cfg));
            beanInits.add(new RExpr.FieldInit(b.field(), new RExpr.Path(b.init())));
        }
        items.add(new RItem.Struct(List.of("`@Configuration` の `@Bean`。テストでは差し替えたものを `AppState::new` に渡す。"), List.of(),
                "Beans", beanFields));
        {
            items.add(new RItem.Impl(List.of(), "Beans", List.of(new RItem.Fn(List.of(), List.of(), "pub", "new", List.of(), new RType("Self"),
                    new RExpr.Block(List.of(), new RExpr.StructLit("Self", beanInits), null, false)))));
            items.add(new RItem.Impl(List.of(), "Default for Beans", List.of(new RItem.Fn(List.of(), List.of(), "", "default", List.of(),
                    new RType("Self"), new RExpr.Block(List.of(), new RExpr.Path("Self::new()"), null, false)))));
        }
        // AppState
        imports.add("sqlx::PgPool");
        List<RItem.Field> stateFields = new ArrayList<>();
        List<RStmt> stateStmts = new ArrayList<>();
        List<RExpr.FieldInit> stateInits = new ArrayList<>();
        for (Bean b : configBeans) {
            stateFields.add(new RItem.Field("pub", b.field(), new RType(b.type().text(imports))));
            stateStmts.add(new RStmt.Let(b.field(), false, null, new RExpr.Path("beans." + b.field())));
            stateInits.add(new RExpr.FieldInit(b.field(), new RExpr.Path(b.field())));
        }
        boolean usesPool = false;
        for (Decl.TypeDecl s : orderedServices()) {
            Bean b = beansByType.get(s.qualifiedName());
            stateFields.add(new RItem.Field("pub", b.field(), new RType(b.type().text(imports))));
            List<RExpr> args = new ArrayList<>();
            Decl.MethodDecl ctor = s.methods().stream().filter(Decl.MethodDecl::isConstructor).findFirst().orElse(null);
            boolean poolAdded = false;
            boolean hasMapperField = s.fields().stream().anyMatch(f -> f.type() instanceof JType.ClassType c
                    && model.is(c.qualifiedName(), SpringModel.Role.MAPPER));
            if (ctor != null) {
                for (Decl.Param p : ctor.params()) {
                    String q = p.type() instanceof JType.ClassType c ? c.qualifiedName() : "";
                    if (model.is(q, SpringModel.Role.MAPPER)) {
                        if (!poolAdded) {
                            args.add(new RExpr.Path("pool.clone()"));
                            poolAdded = true;
                        }
                    } else if (beansByType.containsKey(q)) {
                        args.add(new RExpr.Path(beansByType.get(q).field() + ".clone()"));
                    } else {
                        tr.report(ctor.pos(), s.simpleName() + ": cannot inject " + q);
                        args.add(new RExpr.Path("todo!()"));
                    }
                }
            }
            if (hasMapperField && !poolAdded) {
                args.add(0, new RExpr.Path("pool.clone()"));
                poolAdded = true;
            }
            usesPool |= poolAdded;
            imports.add(model.rustModule(s) + "::" + SpringModel.rustTypeName(s));
            stateStmts.add(new RStmt.Let(b.field(), false, null, new RExpr.Call(new RExpr.Path(SpringModel.rustTypeName(s) + "::new"), args)));
            stateInits.add(new RExpr.FieldInit(b.field(), new RExpr.Path(b.field())));
        }
        items.add(new RItem.Struct(List.of("コントローラが使う Bean（Spring の ApplicationContext に当たる）。"), List.of("derive(Clone)"), "AppState",
                stateFields));
        items.add(new RItem.Impl(List.of(), "AppState", List.of(new RItem.Fn(List.of(), List.of(), "pub", "new",
                List.of(new RItem.Param(usesPool ? "pool" : "_pool", false, new RType("PgPool")),
                        new RItem.Param(configBeans.isEmpty() ? "_beans" : "beans", false, new RType("Beans"))),
                new RType("Self"), new RExpr.Block(stateStmts, new RExpr.StructLit("Self", stateInits), null, false)))));
        items.add(routes(imports));
        items.add(appFn(imports));
        items.add(notFound(imports));
        items.add(connect(imports, migrate));
        items.addAll(appError(imports));
        List<RItem> all = new ArrayList<>(imports.items());
        all.addAll(items);
        return new SpringTranslator.GeneratedFile("src/app.rs", SpringTranslator.print(new RFile("app.rs",
                List.of("アプリの組み立て（Spring Boot の自動構成・DI コンテナ・DispatcherServlet に当たる）。Translated by Java2RustTranslator."),
                List.of(), all)));
    }

    /** サービスをコンストラクタの依存の順に並べる。 */
    private List<Decl.TypeDecl> orderedServices() {
        List<Decl.TypeDecl> services = model.typesWith(SpringModel.Role.SERVICE);
        List<Decl.TypeDecl> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (out.size() < services.size()) {
            boolean progress = false;
            for (Decl.TypeDecl s : services) {
                if (done.contains(s.qualifiedName())) {
                    continue;
                }
                boolean ready = s.methods().stream().filter(Decl.MethodDecl::isConstructor).flatMap(m -> m.params().stream())
                        .allMatch(p -> !(p.type() instanceof JType.ClassType c) || !model.is(c.qualifiedName(), SpringModel.Role.SERVICE)
                                || done.contains(c.qualifiedName()));
                if (ready) {
                    out.add(s);
                    done.add(s.qualifiedName());
                    progress = true;
                }
            }
            if (!progress) {
                services.stream().filter(s -> !done.contains(s.qualifiedName())).forEach(out::add);
                break;
            }
        }
        return out;
    }

    private RItem routes(Imports imports) {
        imports.add("axum::Router");
        Map<String, List<Handler>> byPath = new TreeMap<>();
        for (Handler h : handlers) {
            if (!h.advice()) {
                byPath.computeIfAbsent(h.path(), k -> new ArrayList<>()).add(h);
            }
        }
        RExpr router = new RExpr.Call(new RExpr.Path("Router::new"), List.of());
        for (Map.Entry<String, List<Handler>> e : byPath.entrySet()) {
            RExpr method = null;
            for (Handler h : e.getValue()) {
                String fn = h.http().equals("any") ? "any" : h.http();
                RExpr handler = new RExpr.Path(h.module() + "::" + h.rustName());
                if (method == null) {
                    imports.add("axum::routing::" + fn);
                    method = new RExpr.Call(new RExpr.Path(fn), List.of(handler));
                } else {
                    method = new RExpr.MethodCall(method, fn, List.of(handler));
                }
                imports.add(model.rustModule(h.owner()) + "::" + h.module());
            }
            router = new RExpr.MethodCall(router, "route", List.of(new RExpr.Lit(BodyLowerer.rustString(e.getKey())), method));
        }
        return new RItem.Fn(List.of("`@GetMapping` / `@PostMapping` などのルート（DispatcherServlet のハンドラマッピング）。"), List.of(), "pub",
                "routes", List.of(), new RType("Router<AppState>"), new RExpr.Block(List.of(), router, null, false));
    }

    private RItem appFn(Imports imports) {
        imports.add("tower_http::services::ServeDir");
        imports.add("tower_http::trace::TraceLayer");
        imports.add("tower_sessions::MemoryStore");
        imports.add("tower_sessions::SessionManagerLayer");
        imports.add("axum::handler::HandlerWithoutStateExt");
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let("sessions", false, null, new RExpr.Path(
                "SessionManagerLayer::new(MemoryStore::default())\n        .with_secure(false)\n        .with_name(\"JSESSIONID\")")));
        stmts.add(new RStmt.Let("resources", false, null, new RExpr.Path(
                "ServeDir::new(\"static\")\n        .call_fallback_on_method_not_allowed(true)\n        .fallback(not_found.into_service())")));
        RExpr body = new RExpr.MethodCall(new RExpr.MethodCall(new RExpr.MethodCall(new RExpr.MethodCall(
                new RExpr.Call(new RExpr.Path("routes"), List.of()), "with_state", List.of(new RExpr.Path("state"))),
                "fallback_service", List.of(new RExpr.Path("resources"))), "layer", List.of(new RExpr.Path("sessions"))),
                "layer", List.of(new RExpr.Path("TraceLayer::new_for_http()")));
        return new RItem.Fn(List.of("ルートに静的リソース（`static/`）・セッション・アクセスログを足したアプリ。"), List.of(), "pub", "app",
                List.of(new RItem.Param("state", false, new RType("AppState"))), new RType("Router"), new RExpr.Block(stmts, body, null, false));
    }

    private RItem notFound(Imports imports) {
        imports.add("axum::http::StatusCode");
        imports.add("axum::response::IntoResponse");
        imports.add("axum::response::Response");
        RExpr body;
        View v = views.get("error/404");
        if (v != null) {
            imports.add("crate::views::" + v.struct());
            List<RExpr.FieldInit> fields = new ArrayList<>();
            v.attrs().keySet().forEach(a -> fields.add(new RExpr.FieldInit(Naming.valueName(a), new RExpr.Path("None"))));
            v.flash().stream().filter(f -> !v.attrs().containsKey(f))
                    .forEach(f -> fields.add(new RExpr.FieldInit(Naming.valueName(f), new RExpr.Path("None"))));
            v.bindings().forEach(b -> fields.add(new RExpr.FieldInit(Naming.valueName(b) + "_binding", new RExpr.Path("Default::default()"))));
            body = new RExpr.MethodCall(new RExpr.Template(List.of("(StatusCode::NOT_FOUND, ", new RExpr.StructLit(v.struct(), fields), ")")),
                    "into_response", List.of());
        } else {
            body = new RExpr.MethodCall(new RExpr.Path("(StatusCode::NOT_FOUND, \"Not Found\")"), "into_response", List.of());
        }
        return new RItem.Fn(List.of("どのルートにも当たらないリクエスト（Spring Boot の既定のエラーページ templates/error/404.html）。"), List.of(), "",
                true, "not_found", List.of(), new RType("Response"), new RExpr.Block(List.of(), body, null, false));
    }

    private RItem connect(Imports imports, boolean migrate) {
        imports.add("sqlx::postgres::PgPoolOptions");
        imports.add("crate::config::AppConfig");
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let("pool", false, null, new RExpr.Path(
                "PgPoolOptions::new()\n        .max_connections(10)\n        .connect(&config.database_url)\n        .await?")));
        if (migrate) {
            stmts.add(new RStmt.ExprStmt(new RExpr.Path("crate::MIGRATOR.run(&pool).await?"), true));
        }
        String doc = migrate ? "DataSource（HikariCP）の作成と、schema.sql（spring.sql.init.mode=always）の実行。" : "DataSource（HikariCP）の作成。";
        return new RItem.Fn(List.of(doc), List.of(), "pub", true, "connect", List.of(new RItem.Param("config", false, new RType("&AppConfig"))),
                new RType("Result<PgPool, sqlx::Error>"), new RExpr.Block(stmts, new RExpr.Path("Ok(pool)"), null, false));
    }

    /** AppError と、@ExceptionHandler へのふり分け。 */
    private List<RItem> appError(Imports imports) {
        imports.add("crate::error::Error");
        imports.add("crate::spring_web::BadRequest");
        List<RItem> out = new ArrayList<>();
        out.add(new RItem.Enum(List.of("ハンドラのエラー（サービスの例外と、リクエストの不正）。"), List.of("derive(Debug)"), "AppError", List.of(
                new RItem.Variant(List.of(), List.of(), "Service(Error)"), new RItem.Variant(List.of(), List.of(), "BadRequest(BadRequest)"))));
        out.add(new RItem.Impl(List.of(), "From<Error> for AppError", List.of(new RItem.Fn(List.of(), List.of(), "", "from",
                List.of(new RItem.Param("e", false, new RType("Error"))), new RType("Self"),
                new RExpr.Block(List.of(), new RExpr.Path("Self::Service(e)"), null, false)))));
        out.add(new RItem.Impl(List.of(), "From<BadRequest> for AppError", List.of(new RItem.Fn(List.of(), List.of(), "", "from",
                List.of(new RItem.Param("e", false, new RType("BadRequest"))), new RType("Self"),
                new RExpr.Block(List.of(), new RExpr.Path("Self::BadRequest(e)"), null, false)))));
        List<RExpr.Arm> arms = new ArrayList<>();
        boolean catchAll = false;
        for (Handler h : handlers) {
            if (!h.advice()) {
                continue;
            }
            imports.add(model.rustModule(h.owner()) + "::" + h.module());
            RExpr call = new RExpr.Call(new RExpr.Path(h.module() + "::" + h.rustName()),
                    h.method().params().stream().anyMatch(p -> p.type() instanceof JType.ClassType c && isThrowable(c.qualifiedName()))
                            ? List.of(new RExpr.Path("&e")) : List.of());
            for (String ex : h.exceptions()) {
                String pattern;
                if (model.is(ex, SpringModel.Role.EXCEPTION)) {
                    Plans.ErrorVariant v = tr.plans().errors.get(ex);
                    pattern = "Error::" + v.name() + (v.fields().isEmpty() ? "" : "(..)");
                } else if (ex.equals("org.springframework.dao.DataAccessException")) {
                    pattern = "Error::Database(..)";
                } else {
                    pattern = "_";
                    catchAll = true;
                }
                arms.add(new RExpr.Arm(pattern, call));
            }
        }
        if (!catchAll) {
            imports.add("axum::http::StatusCode");
            arms.add(new RExpr.Arm("_", RExpr.Block.of(List.of(
                    new RStmt.ExprStmt(new RExpr.Path("tracing::error!(\"{e}\")"), true),
                    new RStmt.ExprStmt(new RExpr.Path("(StatusCode::INTERNAL_SERVER_ERROR, \"Internal Server Error\").into_response()"), false)))));
        }
        RExpr match = new RExpr.Match(new RExpr.Path("self"), List.of(
                new RExpr.Arm("AppError::Service(e)", new RExpr.Match(new RExpr.Path("&e"), arms)),
                new RExpr.Arm("AppError::BadRequest(e)", new RExpr.MethodCall(new RExpr.Path("e"), "into_response", List.of()))));
        out.add(new RItem.Impl(List.of(), "IntoResponse for AppError", List.of(new RItem.Fn(
                List.of("例外をレスポンスにする（`@ControllerAdvice` の `@ExceptionHandler`。ほかの例外は 500）。"), List.of(), "",
                "into_response", List.of(new RItem.Param("self", false, null)), new RType("Response"),
                new RExpr.Block(List.of(), match, null, false)))));
        return out;
    }

    /** config.rs（application.yml）。 */
    SpringTranslator.GeneratedFile configRs() {
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(property("url", "spring.datasource.url"));
        stmts.add(property("username", "spring.datasource.username"));
        stmts.add(property("password", "spring.datasource.password"));
        stmts.add(property("port", "server.port"));
        RExpr body = new RExpr.StructLit("Self", List.of(
                new RExpr.FieldInit("database_url", new RExpr.Path("database_url(&url, &username, &password)")),
                new RExpr.FieldInit("port", new RExpr.Path("port.parse().expect(\"server.port must be a port number\")"))));
        RFile file = new RFile("config.rs", List.of("application.yml の設定（Spring の緩いバインドと ${ENV:default} の環境変数で上書きできる）。"
                + " Translated by Java2RustTranslator."), List.of(), List.of(
                new RItem.Use(List.of(), "crate::spring_web::{database_url, property}"),
                new RItem.Struct(List.of("application.yml のうち、アプリの起動に使う値。"), List.of("derive(Debug, Clone)"), "AppConfig", List.of(
                        new RItem.Field("pub", "database_url", new RType("String")), new RItem.Field("pub", "port", new RType("u16")))),
                new RItem.Impl(List.of(), "AppConfig", List.of(new RItem.Fn(List.of(), List.of(), "pub", "load", List.of(), new RType("Self"),
                        new RExpr.Block(stmts, body, null, false))))));
        return new SpringTranslator.GeneratedFile("src/config.rs", SpringTranslator.print(file));
    }

    /** property("SPRING_DATASOURCE_URL", Some("DATABASE_URL"), "jdbc:...")。 */
    private RStmt property(String local, String key) {
        String raw = tr.property(key);
        String defaults = switch (key) {
            case "server.port" -> "8080";
            default -> "";
        };
        String env = null;
        String value = raw == null ? defaults : raw;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?}$").matcher(value);
        if (m.matches()) {
            env = m.group(1);
            value = m.group(2) == null ? "" : m.group(2);
        }
        String relaxed = key.toUpperCase(Locale.ROOT).replace('.', '_').replace("-", "");
        return new RStmt.Let(local, false, null, new RExpr.Path("property(" + BodyLowerer.rustString(relaxed) + ", "
                + (env == null ? "None" : "Some(" + BodyLowerer.rustString(env) + ")") + ", " + BodyLowerer.rustString(value) + ")"));
    }

    /** main.rs（@SpringBootApplication の main）。 */
    SpringTranslator.GeneratedFile mainRs() {
        String krate = Naming.crateName(crateName);
        Decl.TypeDecl app = model.typesWith(SpringModel.Role.APPLICATION).stream().findFirst().orElse(null);
        List<RStmt> stmts = List.of(
                new RStmt.ExprStmt(new RExpr.Path("tracing_subscriber::fmt()\n        .with_env_filter(EnvFilter::try_from_default_env()"
                        + ".unwrap_or_else(|_| EnvFilter::new(\"info\")))\n        .init()"), true),
                new RStmt.Let("config", false, null, new RExpr.Path("AppConfig::load()")),
                new RStmt.Let("pool", false, null, new RExpr.Path("connect(&config).await?")),
                new RStmt.Let("listener", false, null, new RExpr.Path("tokio::net::TcpListener::bind((\"0.0.0.0\", config.port)).await?")),
                new RStmt.ExprStmt(new RExpr.Path("tracing::info!(\"listening on http://{}\", listener.local_addr()?)"), true),
                new RStmt.ExprStmt(new RExpr.Path("axum::serve(listener, app(AppState::new(pool, Beans::new()))).await?"), true));
        RFile file = new RFile("main.rs", List.of(app == null ? "アプリの起動。Translated by Java2RustTranslator."
                : "Translated from `" + app.simpleName() + "` (@SpringBootApplication) by Java2RustTranslator."), List.of(), List.of(
                new RItem.Use(List.of(), krate + "::config::AppConfig"),
                new RItem.Use(List.of(), krate + "::{AppState, Beans, app, connect}"),
                new RItem.Use(List.of(), "tracing_subscriber::EnvFilter"),
                new RItem.Fn(List.of(), List.of("tokio::main"), "", true, "main", List.of(), new RType("Result<(), Box<dyn std::error::Error>>"),
                        new RExpr.Block(stmts, new RExpr.Path("Ok(())"), null, false))));
        return new SpringTranslator.GeneratedFile("src/main.rs", SpringTranslator.print(file));
    }
}
