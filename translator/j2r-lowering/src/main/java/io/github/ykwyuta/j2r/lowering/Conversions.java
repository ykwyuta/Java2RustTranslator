package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.MethodRef;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.List;

/**
 * 型の変換（docs/03-translation-rules.md §1〜§4）:
 * プリミティブ型どうし、ボクシング・アンボクシング、String / 配列と Object の間、参照型のキャスト（checkcast）。
 */
final class Conversions {
    private final LowerContext cx;
    private final Imports imp;
    /** {@code ?}（例外の伝播）を生成したときに呼ぶ（呼び出し側の関数が JResult を返す必要があることを記録する）。 */
    private final Runnable onTry;

    Conversions(LowerContext cx, Imports imp, Runnable onTry) {
        this.cx = cx;
        this.imp = imp;
        this.onTry = onTry;
    }

    /** {@code Ok(v)}（v が {@code x?} なら、同じ意味の {@code x} にする）。 */
    static RExpr ok(RExpr v) {
        if (v instanceof RExpr.Try t) {
            return t.expr();
        }
        return new RExpr.Call(new RExpr.Path("Ok"), List.of(v));
    }

    /** {@code e?}。 */
    RExpr tryOp(RExpr e) {
        onTry.run();
        return new RExpr.Try(e);
    }

    /** ボックス型ならプリミティブ型、それ以外はそのまま。 */
    static JType unboxed(JType t) {
        if (t instanceof JType.ClassType c) {
            return switch (c.qualifiedName()) {
                case "java.lang.Integer" -> JType.INT;
                case "java.lang.Long" -> JType.LONG;
                case "java.lang.Short" -> JType.SHORT;
                case "java.lang.Byte" -> JType.BYTE;
                case "java.lang.Character" -> JType.CHAR;
                case "java.lang.Boolean" -> JType.BOOLEAN;
                case "java.lang.Double" -> JType.DOUBLE;
                case "java.lang.Float" -> JType.FLOAT;
                default -> t;
            };
        }
        return t;
    }

    /**
     * e（型 from）を型 to に変換する。explicit はソースに書かれたキャスト（参照型なら checkcast を行う）。
     */
    RExpr convert(RExpr e, JType from, JType to, boolean explicit) {
        if (from.equals(to) || to instanceof JType.Void) {
            return e;
        }
        if (from instanceof JType.NullType) {
            return cx.types().nullValue(to);
        }
        TypeMapper.Kind kf = cx.types().kind(from);
        TypeMapper.Kind kt = cx.types().kind(to);
        if (kf == TypeMapper.Kind.PRIMITIVE && kt == TypeMapper.Kind.PRIMITIVE) {
            return primitiveCast(e, from, to);
        }
        if (kf == TypeMapper.Kind.PRIMITIVE) {
            // ボクシング。to が別のボックス型なら、先にそのプリミティブ型に変換する。
            JType prim = unboxed(to);
            JType.Primitive p = (JType.Primitive) (prim instanceof JType.Primitive ? prim : from);
            RExpr v = prim instanceof JType.Primitive ? primitiveCast(e, from, prim) : e;
            RExpr boxed = new RExpr.Call(new RExpr.Path("jrt::box_" + TypeMapper.boxSuffix(p.kind())), List.of(v));
            return kt == TypeMapper.Kind.OBJECT ? boxed : convert(boxed, JType.OBJECT, to, explicit);
        }
        if (kt == TypeMapper.Kind.PRIMITIVE) {
            JType prim = unboxed(from);
            if (!(prim instanceof JType.Primitive)) {
                prim = to;
            }
            RExpr obj = kf == TypeMapper.Kind.OBJECT ? e : toObject(e, from);
            RExpr unboxed = tryOp(new RExpr.MethodCall(obj, "unbox_" + TypeMapper.boxSuffix(((JType.Primitive) prim).kind()), List.of()));
            return primitiveCast(unboxed, prim, to);
        }
        if (kf == TypeMapper.Kind.OBJECT && kt == TypeMapper.Kind.OBJECT) {
            if (explicit && to instanceof JType.ClassType c && !c.equals(JType.OBJECT)) {
                return tryOp(new RExpr.MethodCall(e, "checkcast", List.of(new RExpr.Lit("\"" + c.qualifiedName() + "\""))));
            }
            return e;
        }
        if (kt == TypeMapper.Kind.OBJECT) {
            return toObject(e, from);
        }
        if (kf == TypeMapper.Kind.OBJECT) {
            return fromObject(e, to);
        }
        if (cx.types().text(from).equals(cx.types().text(to))) {
            return e;
        }
        if (kf == TypeMapper.Kind.ARRAY && kt == TypeMapper.Kind.ARRAY) {
            // String[] と Object[] の間など: 要素ごとに変換した新しい配列にする（Rust では要素の型ごとに配列の型が違う）。
            JType fc = ((JType.ArrayType) from).component();
            JType tc = ((JType.ArrayType) to).component();
            RExpr x = TypeMapper.isCopy(fc) ? new RExpr.Unary("*", new RExpr.Path("x")) : new RExpr.MethodCall(new RExpr.Path("x"), "clone", List.of());
            Conversions inner = new Conversions(cx, imp, () -> { });
            RExpr body = ok(inner.convert(x, fc, tc, explicit));
            RExpr f = new RExpr.Closure(false, List.of("x: &" + cx.types().text(fc)), new RType("JResult<" + cx.types().text(tc) + ">"),
                    new RExpr.Block(List.of(), body, null, false));
            return tryOp(new RExpr.MethodCall(e, "convert_elements", List.of(f)));
        }
        cx.diags().report(DiagnosticCode.UNSUPPORTED_TYPE, SourcePos.UNKNOWN,
                "conversion from " + from.javaName() + " to " + to.javaName() + " is not supported yet");
        return FnLowerer.todo("conversion " + from.javaName() + " -> " + to.javaName());
    }

    /** 型 from の値 e を JObject にする（ボクシング・String / 配列の包み込み）。 */
    RExpr toObject(RExpr e, JType from) {
        return switch (cx.types().kind(from)) {
            case OBJECT -> e;
            case PRIMITIVE -> new RExpr.Call(new RExpr.Path("jrt::box_" + TypeMapper.boxSuffix(((JType.Primitive) from).kind())), List.of(e));
            case STRING -> new RExpr.Call(new RExpr.Path("JObject::from"), List.of(e));
            case ARRAY -> new RExpr.Call(new RExpr.Path("JObject::from_array"), List.of(e));
            case MAPPED -> new RExpr.Call(new RExpr.Path("JObject::from_native"), List.of(e));
            case VOID -> new RExpr.Block(List.of(new io.github.ykwyuta.j2r.rir.RStmt.ExprStmt(e, true)),
                    new RExpr.Call(new RExpr.Path("JObject::null"), List.of()), null, true);
            default -> {
                cx.diags().report(DiagnosticCode.UNSUPPORTED_TYPE, SourcePos.UNKNOWN,
                        "conversion from " + from.javaName() + " to Object is not supported yet");
                yield FnLowerer.todo("conversion " + from.javaName() + " -> Object");
            }
        };
    }

    /** JObject の値 e を型 to にする（アンボクシング・String / 配列の取り出し）。 */
    RExpr fromObject(RExpr e, JType to) {
        return switch (cx.types().kind(to)) {
            case OBJECT -> e;
            case VOID -> e;
            case PRIMITIVE -> tryOp(new RExpr.MethodCall(e, "unbox_" + TypeMapper.boxSuffix(((JType.Primitive) to).kind()), List.of()));
            case STRING -> tryOp(new RExpr.MethodCall(e, "cast_string", List.of()));
            case ARRAY -> tryOp(new RExpr.MethodCall(e, "cast_array::<" + cx.types().text(((JType.ArrayType) to).component()) + ">", List.of()));
            case MAPPED -> tryOp(new RExpr.MethodCall(e, "cast_native::<" + cx.types().text(to) + ">", List.of()));
            default -> {
                cx.diags().report(DiagnosticCode.UNSUPPORTED_TYPE, SourcePos.UNKNOWN,
                        "conversion from Object to " + to.javaName() + " is not supported yet");
                yield FnLowerer.todo("conversion Object -> " + to.javaName());
            }
        };
    }

    /** プリミティブ型間の変換（docs/03-translation-rules.md §1）。 */
    static RExpr primitiveCast(RExpr e, JType from, JType to) {
        if (from.equals(to)) {
            return e;
        }
        JType.Kind tk = ((JType.Primitive) to).kind();
        RType target = new RType(TypeMapper.primitive(tk));
        boolean narrowFromFloat = JType.isFloating(from)
                && (tk == JType.Kind.BYTE || tk == JType.Kind.SHORT || tk == JType.Kind.CHAR);
        // `-8 as u32` のようにリテラルの型が変換先から推論されないよう、リテラルには元の型の接尾辞を付ける。
        RExpr src = withSuffix(e, from);
        if (narrowFromFloat) {
            // Java は浮動小数点数 → byte/short/char をいったん int に変換してから縮小する。
            return new RExpr.Cast(new RExpr.Cast(src, new RType("i32")), target);
        }
        return new RExpr.Cast(src, target);
    }

    /** メソッド呼び出しのレシーバになる数値リテラルには型接尾辞が必要（`1.wrapping_add(x)` は型が決まらない）。 */
    static RExpr withSuffix(RExpr e, JType type) {
        if (e instanceof RExpr.Lit lit && type instanceof JType.Primitive p && p.kind() != JType.Kind.BOOLEAN
                && lit.text().matches("-?[0-9][0-9.eE+-]*")) {
            return new RExpr.Lit(lit.text() + TypeMapper.primitive(p.kind()));
        }
        return e;
    }

    /**
     * プログラムのメソッドの本体 impl を直接呼ぶ。呼び出し側の型（called の引数・戻り値の型）と本体の宣言の型が
     * 違う場合（ジェネリクスの消去・共変戻り値）は変換する。args の先頭はレシーバ（インスタンスメソッドの場合）。
     * 呼び先が例外を送出しうる（JResult を返す）なら {@code ?} を付けた値にする。
     */
    RExpr callImpl(ProgramIndex.MethodInfo impl, MethodRef called, List<RExpr> args) {
        Decl.MethodDecl d = impl.decl();
        List<RExpr> out = new ArrayList<>();
        int offset = d.isStatic() ? 0 : 1;
        if (offset == 1) {
            out.add(args.get(0));
        }
        for (int i = 0; i < d.params().size() && i + offset < args.size(); i++) {
            JType from = i < called.paramTypes().size() ? called.paramTypes().get(i) : d.params().get(i).type();
            out.add(convert(args.get(i + offset), from, d.params().get(i).type(), false));
        }
        RExpr call = new RExpr.Call(new RExpr.Path(imp.type(impl.owner()) + "::" + cx.hierarchy().bodyName(impl)), out);
        if (cx.throwing().isThrowing(d)) {
            call = tryOp(call);
        }
        return convert(call, d.ref().returnType(), called.returnType(), false);
    }
}
