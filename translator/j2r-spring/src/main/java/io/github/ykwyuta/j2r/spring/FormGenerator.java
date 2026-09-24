package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Annotation;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * フォーム（{@code @ModelAttribute} で受け取るクラス）に、リクエストパラメータのバインド（Spring の DataBinder）と
 * Bean Validation の検証を足す。
 *
 * <pre>
 * pub fn bind(params: &amp;RequestParams) -&gt; (Self, BindingResult)   // 型変換に失敗したフィールドは typeMismatch のエラー
 * pub fn validate(&amp;self, result: &amp;mut BindingResult)                // @NotBlank・@Size など
 * </pre>
 */
final class FormGenerator {
    private static final String CONSTRAINTS = "jakarta.validation.constraints.";

    /** Hibernate Validator の既定のメッセージ（英語）。 */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "NotBlank", "must not be blank",
            "NotNull", "must not be null",
            "NotEmpty", "must not be empty",
            "Size", "size must be between {min} and {max}",
            "Min", "must be greater than or equal to {value}",
            "Max", "must be less than or equal to {value}");

    private final SpringTranslator tr;

    FormGenerator(SpringTranslator tr) {
        this.tr = tr;
    }

    List<RItem> methods(Decl.TypeDecl t, Imports imports) {
        List<RItem> out = new ArrayList<>();
        out.add(bind(t, imports));
        RItem validate = validate(t, imports);
        if (validate != null) {
            out.add(validate);
        }
        return out;
    }

    // ------------------------------------------------------------------ バインド

    private RItem bind(Decl.TypeDecl t, Imports imports) {
        imports.add("crate::spring_web::BindingResult");
        imports.add("crate::spring_web::RequestParams");
        if (!t.instanceInit().isEmpty()) {
            tr.report(t.pos(), t.simpleName() + ": field initializers of forms are not supported yet (fields start with their default values)");
        }
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let("form", true, null, new RExpr.Path("Self::default()")));
        stmts.add(new RStmt.Let("result", true, null, new RExpr.Path("BindingResult::default()")));
        String objectName = Character.toLowerCase(t.simpleName().charAt(0)) + t.simpleName().substring(1);
        for (Decl.FieldDecl f : t.fields()) {
            if (f.isStatic()) {
                continue;
            }
            RT type = tr.types().field(t, f);
            String name = f.name();
            String rust = "form." + Naming.valueName(name);
            RT inner = RT.unwrapOpt(type);
            boolean opt = type instanceof RT.Opt;
            RExpr get = new RExpr.MethodCall(new RExpr.Path("params"), "get", List.of(new RExpr.Lit(BodyLowerer.rustString(name))));
            RExpr.Block body;
            if (inner instanceof RT.Str) {
                RExpr value = new RExpr.MethodCall(new RExpr.Path("v"), "to_string", List.of());
                body = block(assign(rust, opt ? some(value) : value));
            } else {
                RExpr parse;
                String javaType = f.type().javaName();
                if (inner.equals(RT.BOOL)) {
                    imports.add("crate::spring_web::parse_bool");
                    parse = new RExpr.Call(new RExpr.Path("parse_bool"), List.of(new RExpr.Path("v")));
                } else if (inner instanceof RT.Prim p) {
                    imports.add("crate::spring_web::parse_number");
                    parse = new RExpr.Call(new RExpr.Path("parse_number::<" + p.name() + ">"), List.of(new RExpr.Path("v")));
                } else if (inner instanceof RT.Named n && n.kind() == RT.Named.Kind.VALUE && !n.name().equals("NaiveTime")) {
                    boolean date = n.name().equals("NaiveDate");
                    imports.add(date ? "crate::spring_web::parse_date" : "crate::spring_web::parse_date_time");
                    parse = new RExpr.Call(new RExpr.Path(date ? "parse_date" : "parse_date_time"), List.of(new RExpr.Path("v"),
                            new RExpr.Lit(BodyLowerer.rustString(TemplateTranslator.chronoPattern(datePattern(t, f, date))))));
                } else {
                    tr.report(f.pos(), t.simpleName() + "." + name + ": binding request parameters to " + javaType + " is not supported yet");
                    continue;
                }
                // 空文字列は null（Option でないフィールドは変えない）
                RExpr ok = opt ? block(assign(rust, new RExpr.Path("x")))
                        : block(new RStmt.ExprStmt(new RExpr.IfLet("Some(x)", new RExpr.Path("x"), block(assign(rust, new RExpr.Path("x"))), null), false));
                RExpr err = new RExpr.MethodCall(new RExpr.Path("result"), "reject", List.of(new RExpr.Lit(BodyLowerer.rustString(name)),
                        new RExpr.Lit(BodyLowerer.rustString(typeMismatch(objectName, name, javaType))), some(new RExpr.Path("v"))));
                body = block(new RStmt.ExprStmt(new RExpr.Match(parse, List.of(new RExpr.Arm("Ok(x)", ok), new RExpr.Arm("Err(_)", err))), false));
            }
            RExpr orElse = null;
            if (inner.equals(RT.BOOL)) {
                // チェックボックス: チェックされていないと送られないので、_name（th:field が出力する目印）があれば false にする。
                orElse = new RExpr.If(new RExpr.MethodCall(new RExpr.Path("params"), "contains",
                        List.of(new RExpr.Lit(BodyLowerer.rustString("_" + name)))),
                        block(assign(rust, opt ? some(new RExpr.Lit("false")) : new RExpr.Lit("false"))), null);
            }
            stmts.add(new RStmt.ExprStmt(new RExpr.IfLet("Some(v)", get, body, orElse), false));
        }
        return new RItem.Fn(List.of("リクエストパラメータをバインドする（Spring の DataBinder）。型変換に失敗したフィールドはエラーにする。"),
                List.of(), "pub", "bind", List.of(new RItem.Param("params", false, new RType("&RequestParams"))),
                new RType("(Self, BindingResult)"), new RExpr.Block(stmts, new RExpr.Path("(form, result)"), null, false));
    }

    private static RExpr.Block block(RStmt... stmts) {
        return RExpr.Block.of(List.of(stmts));
    }

    private static RStmt assign(String place, RExpr value) {
        return new RStmt.ExprStmt(new RExpr.Assign(new RExpr.Path(place), "=", value), true);
    }

    private static RExpr some(RExpr value) {
        return new RExpr.Call(new RExpr.Path("Some"), List.of(value));
    }

    private String datePattern(Decl.TypeDecl t, Decl.FieldDecl f, boolean date) {
        Annotation a = tr.model().info(DeclInfo.fieldKey(t.qualifiedName(), f.name()))
                .get("org.springframework.format.annotation.DateTimeFormat");
        if (a != null && a.stringValue("pattern") != null) {
            return a.stringValue("pattern");
        }
        if (a == null) {
            tr.report(f.pos(), t.simpleName() + "." + f.name() + ": without @DateTimeFormat the ISO format is used");
        }
        return date ? "yyyy-MM-dd" : "yyyy-MM-dd'T'HH:mm[:ss]";
    }

    /** 型変換のエラーのメッセージ（messages.properties の typeMismatch.* の順に探す）。 */
    private String typeMismatch(String object, String field, String javaType) {
        for (String code : List.of("typeMismatch." + object + "." + field, "typeMismatch." + field, "typeMismatch." + javaType,
                "typeMismatch")) {
            String m = tr.messages().getProperty(code);
            if (m != null) {
                return m;
            }
        }
        return "Failed to convert property value of type 'java.lang.String' to required type '" + javaType + "' for property '"
                + field + "'";
    }

    // ------------------------------------------------------------------ 検証

    private RItem validate(Decl.TypeDecl t, Imports imports) {
        List<RStmt> stmts = new ArrayList<>();
        for (Decl.FieldDecl f : t.fields()) {
            if (f.isStatic()) {
                continue;
            }
            RT type = tr.types().field(t, f);
            String rust = "self." + Naming.valueName(f.name());
            String field = BodyLowerer.rustString(f.name());
            RT inner = RT.unwrapOpt(type);
            boolean opt = type instanceof RT.Opt;
            boolean string = inner instanceof RT.Str;
            String view = !opt ? (string ? "Some(" + rust + ".as_str())" : "Some(&" + rust + ")") : string ? rust + ".as_deref()" : rust + ".as_ref()";
            for (Annotation a : tr.model().info(DeclInfo.fieldKey(t.qualifiedName(), f.name())).annotations()) {
                if (!a.type().startsWith(CONSTRAINTS)) {
                    continue;
                }
                String kind = a.type().substring(CONSTRAINTS.length());
                String message = message(kind, a);
                RExpr.Block reject = block(new RStmt.ExprStmt(new RExpr.MethodCall(new RExpr.Path("result"), "reject",
                        List.of(new RExpr.Lit(field), new RExpr.Lit(BodyLowerer.rustString(message)), new RExpr.Path("None"))), true));
                RExpr check = switch (kind) {
                    case "NotNull" -> opt ? new RExpr.If(new RExpr.Path(rust + ".is_none()"), reject, null) : null;
                    case "NotBlank" -> {
                        imports.add("crate::spring_web::not_blank");
                        yield new RExpr.If(new RExpr.Path("!not_blank(" + (string ? view : "None") + ")"), reject, null);
                    }
                    case "NotEmpty" -> string ? new RExpr.If(new RExpr.Path(view + ".is_none_or(str::is_empty)"), reject, null) : null;
                    case "Size" -> {
                        long min = number(a, "min", 0);
                        long max = number(a, "max", Integer.MAX_VALUE);
                        if (!string) {
                            yield null;
                        }
                        imports.add("crate::spring_web::length");
                        String cond = (min > 0 ? "length(v) < " + min + " || " : "") + "length(v) > " + max;
                        yield new RExpr.If(new RExpr.Path(view + ".is_some_and(|v| " + cond + ")"), reject, null);
                    }
                    case "Min", "Max" -> {
                        if (!(inner instanceof RT.Prim)) {
                            yield null;
                        }
                        String op = kind.equals("Min") ? "<" : ">";
                        yield new RExpr.If(new RExpr.Path(view + ".is_some_and(|v| *v " + op + " " + number(a, "value", 0) + ")"), reject, null);
                    }
                    default -> null;
                };
                if (check == null) {
                    tr.report(f.pos(), t.simpleName() + "." + f.name() + ": @" + kind + " is not supported yet");
                    continue;
                }
                stmts.add(new RStmt.ExprStmt(check, false));
            }
        }
        if (stmts.isEmpty()) {
            return new RItem.Fn(List.of("Bean Validation の検証（制約はない）。"), List.of(), "pub", "validate",
                    List.of(new RItem.Param("&self", false, null), new RItem.Param("_result", false, new RType("&mut BindingResult"))),
                    null, RExpr.Block.of(List.of()));
        }
        return new RItem.Fn(List.of("Bean Validation の検証（`@Valid`）。"), List.of(), "pub", "validate",
                List.of(new RItem.Param("&self", false, null), new RItem.Param("result", false, new RType("&mut BindingResult"))),
                null, RExpr.Block.of(stmts));
    }

    private static String message(String kind, Annotation a) {
        String m = a.stringValue("message");
        if (m == null || m.startsWith("{")) {
            m = DEFAULT_MESSAGES.getOrDefault(kind, "is invalid");
        }
        for (String key : List.of("min", "max", "value")) {
            if (m.contains("{" + key + "}")) {
                m = m.replace("{" + key + "}", Long.toString(number(a, key, key.equals("max") ? Integer.MAX_VALUE : 0)));
            }
        }
        return m;
    }

    private static long number(Annotation a, String name, long defaultValue) {
        return a.value(name) instanceof Number n ? n.longValue() : defaultValue;
    }
}
