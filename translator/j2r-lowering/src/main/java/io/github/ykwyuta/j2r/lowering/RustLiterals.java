package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RType;

/** Java の定数値を Rust のリテラル式にする。 */
final class RustLiterals {
    private RustLiterals() {}

    /**
     * @param suffix 数値リテラルに型接尾辞を付けるか（メソッド呼び出しのレシーバなど、型推論できない位置で必要）
     */
    static RExpr number(Object value, JType type, boolean suffix) {
        JType.Kind kind = ((JType.Primitive) type).kind();
        return switch (kind) {
            case BOOLEAN -> new RExpr.Lit(value.toString());
            case CHAR -> charLiteral(toChar(value));
            case BYTE, SHORT, INT, LONG -> integer(((Number) toNumber(value)).longValue(), kind, suffix);
            case FLOAT -> floating(((Number) toNumber(value)).floatValue(), true);
            case DOUBLE -> floating(((Number) toNumber(value)).doubleValue(), suffix);
        };
    }

    private static Object toNumber(Object v) {
        return v instanceof Character c ? (int) c : v;
    }

    private static char toChar(Object v) {
        return v instanceof Character c ? c : (char) ((Number) v).intValue();
    }

    static RExpr integer(long v, JType.Kind kind, boolean suffix) {
        String rt = TypeMapper.primitive(kind);
        if (kind == JType.Kind.INT && (v == Integer.MAX_VALUE || v == Integer.MIN_VALUE)) {
            return new RExpr.Path(v > 0 ? "i32::MAX" : "i32::MIN");
        }
        if (kind == JType.Kind.LONG && (v == Long.MAX_VALUE || v == Long.MIN_VALUE)) {
            return new RExpr.Path(v > 0 ? "i64::MAX" : "i64::MIN");
        }
        boolean needSuffix = suffix || kind != JType.Kind.INT;
        return new RExpr.Lit(v + (needSuffix ? rt : ""));
    }

    static RExpr floating(double v, boolean suffix) {
        if (Double.isNaN(v)) {
            return new RExpr.Path("f64::NAN");
        }
        if (Double.isInfinite(v)) {
            return new RExpr.Path(v > 0 ? "f64::INFINITY" : "f64::NEG_INFINITY");
        }
        String s = Double.toString(v);
        return new RExpr.Lit(s + (suffix ? "f64" : ""));
    }

    static RExpr floating(float v, boolean suffix) {
        if (Float.isNaN(v)) {
            return new RExpr.Path("f32::NAN");
        }
        if (Float.isInfinite(v)) {
            return new RExpr.Path(v > 0 ? "f32::INFINITY" : "f32::NEG_INFINITY");
        }
        return new RExpr.Lit(Float.toString(v) + "f32");
    }

    /** Java の char（UTF-16 コード単位）→ {@code 'a' as u16} または数値リテラル。 */
    static RExpr charLiteral(char c) {
        if (Character.isSurrogate(c)) {
            return new RExpr.Lit(String.format("0x%04x_u16", (int) c));
        }
        return new RExpr.Cast(new RExpr.Lit("'" + escape(String.valueOf(c), '\'') + "'"), new RType("u16"));
    }

    /** switch の case パターン用（パターンには式を書けないので数値にする）。 */
    static String charPattern(char c) {
        return Integer.toString(c);
    }

    /** Rust の文字列リテラル。孤立サロゲートは U+FFFD に置き換え、置き換えたかどうかを返す配列に記録する。 */
    static String stringLiteral(String s, boolean[] lossy) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                sb.append(c).append(s.charAt(i + 1));
                i++;
            } else if (Character.isSurrogate(c)) {
                sb.append('�');
                lossy[0] = true;
            } else {
                sb.append(escape(String.valueOf(c), '"'));
            }
        }
        return sb.append('"').toString();
    }

    private static String escape(String s, char quote) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\0");
                default -> {
                    if (c == quote) {
                        sb.append('\\').append(c);
                    } else if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u{%x}", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Java の数値型変換（JLS 5.1.2 / 5.1.3）をコンパイル時に適用する（リテラルの畳み込み用）。 */
    static Object convert(Object value, JType.Kind to) {
        Object v = toNumber(value);
        Number n = (Number) v;
        return switch (to) {
            case BYTE -> (int) (byte) narrowToInt(n);
            case SHORT -> (int) (short) narrowToInt(n);
            case CHAR -> (char) narrowToInt(n);
            case INT -> narrowToInt(n);
            case LONG -> n instanceof Double || n instanceof Float ? (long) n.doubleValue() : n.longValue();
            case FLOAT -> n instanceof Double d ? (float) (double) d : n instanceof Long l ? (float) l : n.floatValue();
            case DOUBLE -> n instanceof Long l ? (double) l : n.doubleValue();
            case BOOLEAN -> value;
        };
    }

    private static int narrowToInt(Number n) {
        if (n instanceof Double || n instanceof Float) {
            return (int) n.doubleValue();
        }
        return (int) n.longValue();
    }
}
