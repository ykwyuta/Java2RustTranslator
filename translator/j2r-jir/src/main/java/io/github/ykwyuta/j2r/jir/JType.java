package io.github.ykwyuta.j2r.jir;

/** JIR の型。javac の TypeMirror から変換した、変換に必要な情報だけを持つ型表現。 */
public sealed interface JType {

    enum Kind { BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE }

    record Primitive(Kind kind) implements JType {
        @Override
        public String javaName() {
            return kind.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record Void() implements JType {
        @Override
        public String javaName() {
            return "void";
        }
    }

    /** クラス・インタフェース型（型引数は消去済み）。 */
    record ClassType(String qualifiedName) implements JType {
        @Override
        public String javaName() {
            return qualifiedName;
        }
    }

    record ArrayType(JType component) implements JType {
        @Override
        public String javaName() {
            return component.javaName() + "[]";
        }
    }

    /** null リテラルの型。 */
    record NullType() implements JType {
        @Override
        public String javaName() {
            return "null";
        }
    }

    /**
     * 型引数付きのクラス型（{@code List<Todo>}）。JIR の式・文の型には現れず、{@link DeclInfo#genericType()} でだけ使う。
     * 型変数・ワイルドカードは上限の型にする。
     */
    record Parameterized(String qualifiedName, java.util.List<JType> args) implements JType {
        public Parameterized {
            args = java.util.List.copyOf(args);
        }

        @Override
        public String javaName() {
            return qualifiedName + args.stream().map(JType::javaName).collect(java.util.stream.Collectors.joining(",", "<", ">"));
        }
    }

    /** 変換器が扱えない型（型変数、交差型など）。 */
    record Unsupported(String description) implements JType {
        @Override
        public String javaName() {
            return description;
        }
    }

    /** シグネチャのキーやマッピング規則で使う Java 表記（消去後）。 */
    String javaName();

    JType BOOLEAN = new Primitive(Kind.BOOLEAN);
    JType BYTE = new Primitive(Kind.BYTE);
    JType SHORT = new Primitive(Kind.SHORT);
    JType CHAR = new Primitive(Kind.CHAR);
    JType INT = new Primitive(Kind.INT);
    JType LONG = new Primitive(Kind.LONG);
    JType FLOAT = new Primitive(Kind.FLOAT);
    JType DOUBLE = new Primitive(Kind.DOUBLE);
    JType VOID = new Void();
    JType STRING = new ClassType("java.lang.String");
    JType OBJECT = new ClassType("java.lang.Object");

    static boolean isPrimitive(JType t, Kind kind) {
        return t instanceof Primitive p && p.kind() == kind;
    }

    static boolean isNumeric(JType t) {
        return t instanceof Primitive p && p.kind() != Kind.BOOLEAN;
    }

    static boolean isIntegral(JType t) {
        return t instanceof Primitive p && switch (p.kind()) {
            case BYTE, SHORT, CHAR, INT, LONG -> true;
            default -> false;
        };
    }

    static boolean isFloating(JType t) {
        return t instanceof Primitive p && (p.kind() == Kind.FLOAT || p.kind() == Kind.DOUBLE);
    }

    static boolean isString(JType t) {
        return STRING.equals(t);
    }

    /** 単項数値昇格（JLS 5.6）。 */
    static JType unaryPromotion(JType t) {
        if (t instanceof Primitive p) {
            return switch (p.kind()) {
                case BYTE, SHORT, CHAR -> INT;
                default -> t;
            };
        }
        return t;
    }

    /** 二項数値昇格（JLS 5.6）。 */
    static JType binaryPromotion(JType a, JType b) {
        if (isPrimitive(a, Kind.DOUBLE) || isPrimitive(b, Kind.DOUBLE)) {
            return DOUBLE;
        }
        if (isPrimitive(a, Kind.FLOAT) || isPrimitive(b, Kind.FLOAT)) {
            return FLOAT;
        }
        if (isPrimitive(a, Kind.LONG) || isPrimitive(b, Kind.LONG)) {
            return LONG;
        }
        return INT;
    }
}
