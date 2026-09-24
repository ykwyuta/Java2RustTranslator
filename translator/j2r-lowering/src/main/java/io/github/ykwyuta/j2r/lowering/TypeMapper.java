package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.List;

/**
 * Java の型 → Rust の型（docs/03-translation-rules.md §1〜§3）。
 * String は JString、配列は JArray、マッピング規則で Rust の型が決まっている JDK のクラス（StringBuilder など）はその型、
 * それ以外の参照型（ユーザーのクラス・インタフェース・Object・ボックス型・コレクションなど）はすべて JObject。
 */
final class TypeMapper {
    static final String UNSUPPORTED = "jrt::Unsupported";

    /** Rust 上の表現の種類。 */
    enum Kind { PRIMITIVE, STRING, ARRAY, OBJECT, MAPPED, VOID, UNSUPPORTED }

    private final ApiMappings mappings;

    TypeMapper(ApiMappings mappings) {
        this.mappings = mappings;
    }

    RType rust(JType t) {
        return new RType(text(t));
    }

    String text(JType t) {
        return switch (t) {
            case JType.Primitive p -> primitive(p.kind());
            case JType.Void v -> "()";
            case JType.ArrayType a -> "JArray<" + text(a.component()) + ">";
            case JType.ClassType c -> {
                if (JType.isString(c)) {
                    yield "JString";
                }
                String mapped = mappings.rustType(c.qualifiedName());
                yield mapped != null ? mapped : "JObject";
            }
            case JType.NullType n -> "JObject";
            case JType.Unsupported u -> UNSUPPORTED;
        };
    }

    Kind kind(JType t) {
        return switch (t) {
            case JType.Primitive p -> Kind.PRIMITIVE;
            case JType.Void v -> Kind.VOID;
            case JType.ArrayType a -> Kind.ARRAY;
            case JType.ClassType c -> JType.isString(c) ? Kind.STRING
                    : mappings.rustType(c.qualifiedName()) != null ? Kind.MAPPED : Kind.OBJECT;
            case JType.NullType n -> Kind.OBJECT;
            case JType.Unsupported u -> Kind.UNSUPPORTED;
        };
    }

    static String primitive(JType.Kind k) {
        return switch (k) {
            case BOOLEAN -> "bool";
            case BYTE -> "i8";
            case SHORT -> "i16";
            case CHAR -> "u16";
            case INT -> "i32";
            case LONG -> "i64";
            case FLOAT -> "f32";
            case DOUBLE -> "f64";
        };
    }

    /** ボクシング・アンボクシング関数の接尾辞（box_i32 / unbox_i32 など）。 */
    static String boxSuffix(JType.Kind k) {
        return switch (k) {
            case BOOLEAN -> "bool";
            case CHAR -> "u16";
            default -> primitive(k);
        };
    }

    /** Rust で Copy な型（値の読み出しに clone が要らない）か。 */
    static boolean isCopy(JType t) {
        return t instanceof JType.Primitive;
    }

    /** 型の既定値（フィールド・配列要素の初期値。参照型は null）。 */
    RExpr defaultValue(JType t) {
        return switch (t) {
            case JType.Primitive p -> switch (p.kind()) {
                case BOOLEAN -> new RExpr.Lit("false");
                case FLOAT -> new RExpr.Lit("0.0f32");
                case DOUBLE -> new RExpr.Lit("0.0");
                case CHAR -> new RExpr.Lit("0u16");
                default -> RustLiterals.integer(0, p.kind(), false);
            };
            default -> nullValue(t);
        };
    }

    /** 型 t の null。 */
    RExpr nullValue(JType t) {
        return switch (kind(t)) {
            case STRING -> new RExpr.Call(new RExpr.Path("JString::null"), List.of());
            case ARRAY -> new RExpr.Call(new RExpr.Path("JArray::null"), List.of());
            case MAPPED -> new RExpr.Call(new RExpr.Path(text(t) + "::default"), List.of());
            case UNSUPPORTED -> new RExpr.Path(UNSUPPORTED);
            default -> new RExpr.Call(new RExpr.Path("JObject::null"), List.of());
        };
    }
}
