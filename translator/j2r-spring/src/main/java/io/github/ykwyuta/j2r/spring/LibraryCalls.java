package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.MethodRef;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import java.util.List;

/**
 * JDK の API（String・Optional・java.time・List・enum・Objects）の呼び出しを、Rust の標準ライブラリと chrono の呼び出しにする。
 * 扱えなければ null を返す。
 */
final class LibraryCalls {
    private LibraryCalls() {}

    static BodyLowerer.RV lower(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx) {
        MethodRef m = c.method();
        String owner = m.owner();
        String name = m.name();
        int argc = c.args().size();
        if (m.isStatic()) {
            return staticCall(l, c, ctx, owner, name, argc);
        }
        // equals は型によらず ==。
        if (name.equals("equals") && argc == 1 && !owner.equals("java.lang.String")) {
            BodyLowerer.RV recv = l.receiver(c, ctx);
            BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
            return rv(new RExpr.Binary("==", recv.expr(), l.coerce(arg, recv.type())), RT.BOOL);
        }
        return switch (owner) {
            case "java.lang.String" -> string(l, c, ctx, name, argc);
            case "java.lang.Enum" -> enumCall(l, c, ctx, name, argc);
            case "java.util.Optional" -> optional(l, c, ctx, name, argc);
            case "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
                 "java.time.chrono.ChronoLocalDate", "java.time.chrono.ChronoLocalDateTime" -> time(l, c, ctx, name, argc);
            case "java.util.List", "java.util.Collection", "java.util.ArrayList" -> list(l, c, ctx, name, argc);
            case "java.lang.Throwable" -> {
                BodyLowerer.RV recv = l.receiver(c, ctx);
                yield (name + "/" + argc).equals("getMessage/0") && recv.type() instanceof RT.ErrorValue
                        ? rv(new RExpr.MethodCall(recv.expr(), "to_string", List.of()), RT.STR) : null;
            }
            default -> null;
        };
    }

    private static BodyLowerer.RV staticCall(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, String owner, String name, int argc) {
        switch (owner + "#" + name + "/" + argc) {
            case "java.time.LocalDate#now/1", "java.time.LocalDateTime#now/1" -> {
                BodyLowerer.RV clock = l.expr(c.args().get(0), ctx);
                if (!(clock.type() instanceof RT.Named n && n.kind() == RT.Named.Kind.CLOCK)) {
                    return null;
                }
                boolean date = owner.equals("java.time.LocalDate");
                return rv(new RExpr.MethodCall(clock.expr(), date ? "today" : "now", List.of()),
                        date ? TypeResolver.NAIVE_DATE : TypeResolver.NAIVE_DATE_TIME);
            }
            case "java.time.LocalDate#now/0" -> {
                l.imports().add("chrono::Local");
                return rv(new RExpr.MethodCall(new RExpr.Call(new RExpr.Path("Local::now"), List.of()), "date_naive", List.of()),
                        TypeResolver.NAIVE_DATE);
            }
            case "java.time.LocalDateTime#now/0" -> {
                l.imports().add("chrono::Local");
                return rv(new RExpr.MethodCall(new RExpr.Call(new RExpr.Path("Local::now"), List.of()), "naive_local", List.of()),
                        TypeResolver.NAIVE_DATE_TIME);
            }
            case "java.time.Clock#systemDefaultZone/0", "java.time.Clock#systemUTC/0" -> {
                l.imports().add("std::sync::Arc");
                String clock = name.equals("systemUTC") ? "UtcClock" : "SystemClock";
                l.imports().add("crate::clock::" + clock);
                return rv(new RExpr.Call(new RExpr.Path("Arc::new"), List.of(new RExpr.Path(clock))), TypeResolver.CLOCK);
            }
            case "java.util.Objects#equals/2" -> {
                BodyLowerer.RV a = l.expr(c.args().get(0), ctx);
                BodyLowerer.RV b = l.expr(c.args().get(1), ctx);
                return rv(new RExpr.Binary("==", a.expr(), l.coerce(b, a.type())), RT.BOOL);
            }
            case "java.util.Objects#isNull/1", "java.util.Objects#nonNull/1" -> {
                BodyLowerer.RV a = l.expr(c.args().get(0), ctx);
                boolean isNull = name.equals("isNull");
                if (!(a.type() instanceof RT.Opt)) {
                    return rv(new RExpr.Lit(isNull ? "false" : "true"), RT.BOOL);
                }
                return rv(new RExpr.MethodCall(a.expr(), isNull ? "is_none" : "is_some", List.of()), RT.BOOL);
            }
            case "java.util.Objects#requireNonNull/1" -> {
                BodyLowerer.RV a = l.expr(c.args().get(0), ctx);
                return rv(l.coerce(a, RT.unwrapOpt(a.type())), RT.unwrapOpt(a.type()));
            }
            case "java.util.Optional#empty/0" -> {
                return rv(new RExpr.Path("None"), new RT.Null());
            }
            case "java.util.Optional#of/1" -> {
                BodyLowerer.RV a = l.expr(c.args().get(0), ctx);
                RT inner = RT.owned(a.type());
                return rv(new RExpr.Call(new RExpr.Path("Some"), List.of(l.coerce(a, inner))), new RT.Opt(inner));
            }
            case "java.util.Optional#ofNullable/1" -> {
                BodyLowerer.RV a = l.expr(c.args().get(0), ctx);
                RT t = a.type() instanceof RT.Opt ? RT.owned(a.type()) : new RT.Opt(RT.owned(a.type()));
                return rv(l.coerce(a, t), t);
            }
            case "java.time.LocalDate#of/3" -> {
                l.imports().add("chrono::NaiveDate");
                RExpr date = new RExpr.Call(new RExpr.Path("NaiveDate::from_ymd_opt"), List.of(intArg(l, c, ctx, 0, "i32"),
                        intArg(l, c, ctx, 1, "u32"), intArg(l, c, ctx, 2, "u32")));
                return rv(new RExpr.MethodCall(date, "unwrap", List.of()), TypeResolver.NAIVE_DATE);
            }
            case "java.time.LocalDateTime#of/5", "java.time.LocalDateTime#of/6" -> {
                if (!(c.method().paramTypes().get(1) instanceof io.github.ykwyuta.j2r.jir.JType.Primitive)) {
                    return null;
                }
                l.imports().add("chrono::NaiveDate");
                RExpr date = new RExpr.MethodCall(new RExpr.Call(new RExpr.Path("NaiveDate::from_ymd_opt"), List.of(intArg(l, c, ctx, 0, "i32"),
                        intArg(l, c, ctx, 1, "u32"), intArg(l, c, ctx, 2, "u32"))), "unwrap", List.of());
                RExpr time = new RExpr.MethodCall(date, "and_hms_opt", List.of(intArg(l, c, ctx, 3, "u32"), intArg(l, c, ctx, 4, "u32"),
                        argc == 6 ? intArg(l, c, ctx, 5, "u32") : new RExpr.Lit("0")));
                return rv(new RExpr.MethodCall(time, "unwrap", List.of()), TypeResolver.NAIVE_DATE_TIME);
            }
            case "java.time.Clock#fixed/2" -> {
                // Clock.fixed(ldt.atZone(zone).toInstant(), zone)（Rust ではタイムゾーンのない日時の時計）
                Expr instant = c.args().get(0);
                while (instant instanceof Expr.Cast cast) {
                    instant = cast.expr();
                }
                if (instant instanceof Expr.Call toInstant && toInstant.method().name().equals("toInstant")
                        && toInstant.receiver() instanceof Expr.Call atZone && atZone.method().name().equals("atZone")) {
                    BodyLowerer.RV ldt = l.expr(atZone.receiver(), ctx);
                    l.imports().add("std::sync::Arc");
                    l.imports().add("crate::clock::FixedClock");
                    return rv(new RExpr.Call(new RExpr.Path("Arc::new"), List.of(new RExpr.Call(new RExpr.Path("FixedClock"),
                            List.of(l.coerce(ldt, TypeResolver.NAIVE_DATE_TIME))))), TypeResolver.CLOCK);
                }
                return null;
            }
            case "java.util.List#of/0" -> {
                return rv(new RExpr.Path("Vec::new()"), new RT.VecT(new RT.Unknown("raw List")));
            }
            case "java.util.List#of/1", "java.util.List#of/2", "java.util.List#of/3", "java.util.List#of/4", "java.util.List#of/5" -> {
                List<BodyLowerer.RV> items = new java.util.ArrayList<>();
                for (Expr a : c.args()) {
                    items.add(l.expr(a, ctx));
                }
                RT elem = RT.owned(items.get(0).type());
                List<RExpr> values = items.stream().map(v -> l.coerce(v, elem)).toList();
                return rv(new RExpr.Macro("vec", values), new RT.VecT(elem));
            }
            case "java.lang.String#valueOf/1" -> {
                BodyLowerer.RV a = l.expr(c.args().get(0), ctx);
                return rv(new RExpr.MethodCall(a.expr(), "to_string", List.of()), RT.STR);
            }
            default -> {
                return null;
            }
        }
    }

    /** 整数の引数（リテラルならそのまま、それ以外は as でキャストする）。 */
    private static RExpr intArg(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, int i, String type) {
        BodyLowerer.RV v = l.expr(c.args().get(i), ctx);
        if (v.expr() instanceof RExpr.Lit lit && lit.text().matches("[0-9_]+")) {
            return v.expr();
        }
        return new RExpr.Cast(v.expr(), new io.github.ykwyuta.j2r.rir.RType(type));
    }

    private static BodyLowerer.RV string(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, String name, int argc) {
        BodyLowerer.RV recv = l.receiver(c, ctx);
        RExpr r = recv.expr();
        switch (name + "/" + argc) {
            case "strip/0", "trim/0" -> {
                return rv(call(r, "trim"), RT.STR_REF);
            }
            case "stripLeading/0" -> {
                return rv(call(r, "trim_start"), RT.STR_REF);
            }
            case "stripTrailing/0" -> {
                return rv(call(r, "trim_end"), RT.STR_REF);
            }
            case "toLowerCase/0" -> {
                return rv(call(r, "to_lowercase"), RT.STR);
            }
            case "toUpperCase/0" -> {
                return rv(call(r, "to_uppercase"), RT.STR);
            }
            case "isEmpty/0" -> {
                return rv(call(r, "is_empty"), RT.BOOL);
            }
            case "isBlank/0" -> {
                return rv(new RExpr.MethodCall(call(r, "trim"), "is_empty", List.of()), RT.BOOL);
            }
            case "length/0" -> {
                l.diags().report(DiagnosticCode.LOSSY_SURROGATE, c.pos(), "String.length() counts UTF-16 code units");
                return rv(new RExpr.Cast(new RExpr.MethodCall(call(r, "encode_utf16"), "count", List.of()),
                        new io.github.ykwyuta.j2r.rir.RType("i32")), RT.I32);
            }
            case "equals/1" -> {
                BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
                if (arg.type() instanceof RT.Opt) {
                    return rv(new RExpr.Binary("==", new RExpr.Call(new RExpr.Path("Some"), List.of(l.coerce(recv, RT.STR_REF))),
                            l.coerce(arg, new RT.Opt(RT.STR_REF))), RT.BOOL);
                }
                return rv(new RExpr.Binary("==", r, arg.expr()), RT.BOOL);
            }
            case "equalsIgnoreCase/1" -> {
                BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
                return rv(new RExpr.Binary("==", call(r, "to_lowercase"), call(l.coerce(arg, RT.STR_REF), "to_lowercase")), RT.BOOL);
            }
            case "startsWith/1", "endsWith/1", "contains/1" -> {
                BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
                String method = switch (name) {
                    case "startsWith" -> "starts_with";
                    case "endsWith" -> "ends_with";
                    default -> "contains";
                };
                return rv(new RExpr.MethodCall(r, method, List.of(l.coerce(arg, RT.STR_REF))), RT.BOOL);
            }
            default -> {
                return null;
            }
        }
    }

    private static BodyLowerer.RV enumCall(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, String name, int argc) {
        BodyLowerer.RV recv = l.receiver(c, ctx);
        return switch (name + "/" + argc) {
            case "name/0", "toString/0" -> rv(call(recv.expr(), "name"), RT.STR_REF);
            case "ordinal/0" -> rv(call(recv.expr(), "ordinal"), RT.I32);
            default -> null;
        };
    }

    private static BodyLowerer.RV optional(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, String name, int argc) {
        BodyLowerer.RV recv = l.receiver(c, ctx);
        RT inner = RT.unwrapOpt(recv.type());
        switch (name + "/" + argc) {
            case "orElseThrow/1" -> {
                RExpr error = supplierValue(l, c.args().get(0), ctx);
                if (error == null) {
                    return null;
                }
                RExpr okOr = new RExpr.MethodCall(recv.expr(), "ok_or_else", List.of(RExpr.Closure.of(List.of(), error)));
                return new BodyLowerer.RV(new RExpr.Try(okOr), inner, BodyLowerer.Place.NONE, false, true);
            }
            case "orElse/1" -> {
                BodyLowerer.RV other = l.expr(c.args().get(0), ctx);
                if (other.type() instanceof RT.Null) {
                    return recv;
                }
                return rv(new RExpr.MethodCall(recv.expr(), "unwrap_or", List.of(l.coerce(other, inner))), inner);
            }
            case "orElseGet/1" -> {
                RExpr value = supplierValue(l, c.args().get(0), ctx);
                return value == null ? null
                        : rv(new RExpr.MethodCall(recv.expr(), "unwrap_or_else", List.of(RExpr.Closure.of(List.of(), value))), inner);
            }
            case "isPresent/0" -> {
                return rv(call(recv.expr(), "is_some"), RT.BOOL);
            }
            case "isEmpty/0" -> {
                return rv(call(recv.expr(), "is_none"), RT.BOOL);
            }
            case "get/0", "orElseThrow/0" -> {
                l.diags().report(DiagnosticCode.LOSSY_NULL, c.pos(), "Optional." + name + "() panics when the value is absent");
                return rv(call(recv.expr(), "unwrap"), inner);
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code () -> expr} の expr（ラムダの本体が return 1 つのとき）。 */
    private static RExpr supplierValue(BodyLowerer l, Expr supplier, BodyLowerer.Ctx ctx) {
        if (supplier instanceof Expr.Lambda lambda && lambda.params().isEmpty() && lambda.body().stmts().size() == 1
                && lambda.body().stmts().get(0) instanceof Stmt.Return r && r.value() != null) {
            return l.expr(r.value(), ctx).expr();
        }
        return null;
    }

    private static BodyLowerer.RV time(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, String name, int argc) {
        BodyLowerer.RV recv = l.receiver(c, ctx);
        String op = switch (name + "/" + argc) {
            case "isBefore/1" -> "<";
            case "isAfter/1" -> ">";
            case "isEqual/1" -> "==";
            default -> null;
        };
        if (op != null) {
            BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
            return rv(new RExpr.Binary(op, recv.expr(), l.coerce(arg, RT.owned(recv.type()))), RT.BOOL);
        }
        if ((name + "/" + argc).equals("toLocalDate/0")) {
            return rv(call(recv.expr(), "date"), TypeResolver.NAIVE_DATE);
        }
        return null;
    }

    private static BodyLowerer.RV list(BodyLowerer l, Expr.Call c, BodyLowerer.Ctx ctx, String name, int argc) {
        BodyLowerer.RV recv = l.receiver(c, ctx);
        RT elem = switch (recv.type()) {
            case RT.VecT v -> v.elem();
            case RT.Slice s -> s.elem();
            default -> null;
        };
        if (elem == null) {
            return null;
        }
        return switch (name + "/" + argc) {
            case "size/0" -> rv(new RExpr.Cast(call(recv.expr(), "len"), new io.github.ykwyuta.j2r.rir.RType("i32")), RT.I32);
            case "isEmpty/0" -> rv(call(recv.expr(), "is_empty"), RT.BOOL);
            case "add/1" -> {
                // 戻り値（常に true）は使わない前提。
                BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
                yield rv(new RExpr.MethodCall(recv.expr(), "push", List.of(l.coerce(arg, elem))), RT.UNIT);
            }
            case "get/1" -> {
                BodyLowerer.RV index = l.expr(c.args().get(0), ctx);
                RExpr at = new RExpr.Template(List.of(recv.expr(), "[", new RExpr.Cast(index.expr(), new io.github.ykwyuta.j2r.rir.RType("usize")), "]"));
                yield new BodyLowerer.RV(at, elem, BodyLowerer.Place.FIELD, false, false);
            }
            case "contains/1" -> {
                BodyLowerer.RV arg = l.expr(c.args().get(0), ctx);
                yield rv(new RExpr.MethodCall(recv.expr(), "contains", List.of(new RExpr.Unary("&", l.coerce(arg, elem)))), RT.BOOL);
            }
            default -> null;
        };
    }

    /** 受け取ったコレクションを変更するメソッド（変数を let mut にする）。 */
    static boolean mutates(io.github.ykwyuta.j2r.jir.MethodRef m) {
        return !m.isStatic() && List.of("java.util.List", "java.util.Collection", "java.util.ArrayList").contains(m.owner())
                && List.of("add", "addAll", "remove", "removeIf", "clear", "set", "sort").contains(m.name());
    }

    private static RExpr call(RExpr recv, String method) {
        return new RExpr.MethodCall(recv, method, List.of());
    }

    private static BodyLowerer.RV rv(RExpr e, RT t) {
        return BodyLowerer.RV.of(e, t);
    }
}
