package io.github.ykwyuta.j2r.spring;

import java.util.List;

/**
 * 生成する Rust の値の型。所有する値（{@code String}・{@code Todo}）と借用（{@code &str}・{@code &Todo}）、
 * null を許す値（{@code Option<T>}）を区別し、式の型を合わせるとき（{@link BodyLowerer#coerce}）に使う。
 */
sealed interface RT {

    /** Rust の型の表記。使う型の {@code use} を imports に登録する。 */
    String text(Imports imports);

    /** Copy な型か（値を使っても移動しない）。 */
    boolean copy();

    /** プリミティブ（i32・i64・bool・f64 など）。 */
    record Prim(String name) implements RT {
        @Override
        public String text(Imports imports) {
            return name;
        }

        @Override
        public boolean copy() {
            return true;
        }
    }

    /** {@code String}。 */
    record Str() implements RT {
        @Override
        public String text(Imports imports) {
            return "String";
        }

        @Override
        public boolean copy() {
            return false;
        }
    }

    /** {@code &str}（引数と、文字列リテラル・{@code trim()} などの結果）。 */
    record StrRef() implements RT {
        @Override
        public String text(Imports imports) {
            return "&str";
        }

        @Override
        public boolean copy() {
            return true;
        }
    }

    /** 名前のある型（変換したクラス・enum・record、chrono の型など）。uses はその型を使うための use パス。 */
    record Named(String name, List<String> uses, boolean copy, Kind kind, String javaName) implements RT {
        enum Kind { ENTITY, RECORD, ENUM, VALUE, CLOCK, SERVICE, FORM }

        public Named {
            uses = List.copyOf(uses);
        }

        @Override
        public String text(Imports imports) {
            uses.forEach(imports::add);
            return name;
        }
    }

    /** {@code Option<T>}（{@code @Nullable} な値・Optional）。 */
    record Opt(RT inner) implements RT {
        @Override
        public String text(Imports imports) {
            return "Option<" + inner.text(imports) + ">";
        }

        @Override
        public boolean copy() {
            return inner.copy();
        }
    }

    /** {@code Vec<T>}（List）。 */
    record VecT(RT elem) implements RT {
        @Override
        public String text(Imports imports) {
            return "Vec<" + elem.text(imports) + ">";
        }

        @Override
        public boolean copy() {
            return false;
        }
    }

    /** {@code &[T]}（List の引数）。 */
    record Slice(RT elem) implements RT {
        @Override
        public String text(Imports imports) {
            return "&[" + elem.text(imports) + "]";
        }

        @Override
        public boolean copy() {
            return true;
        }
    }

    /** {@code [T; N]}（enum の values()）。 */
    record ArrayT(RT elem, int length) implements RT {
        @Override
        public String text(Imports imports) {
            return "[" + elem.text(imports) + "; " + length + "]";
        }

        @Override
        public boolean copy() {
            return elem.copy();
        }
    }

    /** {@code &T}（Copy でない値の引数）。 */
    record Ref(RT inner, boolean mut) implements RT {
        @Override
        public String text(Imports imports) {
            return (mut ? "&mut " : "&") + inner.text(imports);
        }

        @Override
        public boolean copy() {
            return !mut;
        }
    }

    /** {@code ()}。 */
    record Unit() implements RT {
        @Override
        public String text(Imports imports) {
            return "()";
        }

        @Override
        public boolean copy() {
            return true;
        }
    }

    /** null リテラル（代入先の型に合わせて None になる）。 */
    record Null() implements RT {
        @Override
        public String text(Imports imports) {
            return "Option<()>";
        }

        @Override
        public boolean copy() {
            return true;
        }
    }

    /** サービスの例外（crate::error::Error の値）。 */
    record ErrorValue() implements RT {
        @Override
        public String text(Imports imports) {
            imports.add("crate::error::Error");
            return "Error";
        }

        @Override
        public boolean copy() {
            return false;
        }
    }

    /** 変換できない型。 */
    record Unknown(String description) implements RT {
        @Override
        public String text(Imports imports) {
            return "()";
        }

        @Override
        public boolean copy() {
            return true;
        }
    }

    RT I32 = new Prim("i32");
    RT I64 = new Prim("i64");
    RT BOOL = new Prim("bool");
    RT STR = new Str();
    RT STR_REF = new StrRef();
    RT UNIT = new Unit();

    /** 所有する型に直す（{@code &str} → {@code String}、{@code &T} → {@code T}、{@code &[T]} → {@code Vec<T>}）。 */
    static RT owned(RT t) {
        return switch (t) {
            case StrRef s -> STR;
            case Ref r -> owned(r.inner());
            case Slice s -> new VecT(s.elem());
            case Opt o -> new Opt(owned(o.inner()));
            case ArrayT a -> new VecT(a.elem());
            default -> t;
        };
    }

    /** 引数として受け取るときの型（Copy でない値は借用にする）。 */
    static RT borrowed(RT t) {
        return switch (t) {
            case Str s -> STR_REF;
            case Named n when !n.copy() && n.kind() != Named.Kind.CLOCK -> new Ref(n, false);
            case VecT v -> new Slice(v.elem());
            case Opt o when !o.inner().copy() && !(o.inner() instanceof VecT) -> new Opt(borrowed(o.inner()));
            default -> t;
        };
    }

    /** Option を外した型。 */
    static RT unwrapOpt(RT t) {
        return t instanceof Opt o ? o.inner() : t;
    }
}
