package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.rir.RType;

/** Java の型 → Rust の型（表現戦略 S0、docs/03-translation-rules.md §1）。 */
final class TypeMapper {
    static final String UNSUPPORTED = "jrt::Unsupported";

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
                yield mapped != null ? mapped : UNSUPPORTED;
            }
            default -> UNSUPPORTED;
        };
    }

    boolean isSupported(JType t) {
        return !text(t).contains(UNSUPPORTED);
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

    /** Rust で Copy な型（値の読み出しに clone が要らない）か。 */
    static boolean isCopy(JType t) {
        return t instanceof JType.Primitive;
    }
}
