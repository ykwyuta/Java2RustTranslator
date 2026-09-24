package io.github.ykwyuta.j2r.rir;

import java.util.List;

/** Rust のアイテム（モジュール直下に置けるもの）。 */
public sealed interface RItem {

    /**
     * 関数（impl ブロックの中なら関連関数）。
     *
     * @param attrs {@code #[...]} 属性（例: {@code allow(non_snake_case)}）
     * @param vis   可視性（{@code pub} / {@code pub(crate)} / 空文字列）
     * @param ret   戻り値型。null なら unit
     */
    record Fn(List<String> docs, List<String> attrs, String vis, String name, List<Param> params, RType ret, RExpr.Block body)
            implements RItem {
        public Fn {
            docs = List.copyOf(docs);
            attrs = List.copyOf(attrs);
            params = List.copyOf(params);
        }

        public static Fn of(boolean pub, String name, List<Param> params, RType ret, RExpr.Block body) {
            return new Fn(List.of(), List.of(), pub ? "pub" : "", name, params, ret, body);
        }
    }

    /** 構造体。fields が空なら unit 構造体（{@code pub struct Name;}）。 */
    record Struct(List<String> docs, String name, List<Field> fields) implements RItem {
        public Struct {
            docs = List.copyOf(docs);
            fields = List.copyOf(fields);
        }
    }

    record Field(String vis, String name, RType type) {}

    /** {@code impl Type { ... }} / {@code impl Trait for Type { ... }}。header は "impl" の後ろ。 */
    record Impl(List<String> attrs, String header, List<RItem> items) implements RItem {
        public Impl {
            attrs = List.copyOf(attrs);
            items = List.copyOf(items);
        }
    }

    record Param(String name, boolean mut, RType type) {}

    record Const(List<String> docs, boolean pub, String name, RType type, RExpr value) implements RItem {
        public Const {
            docs = List.copyOf(docs);
        }
    }

    /** {@code thread_local! { pub static NAME: TYPE = INIT; }} */
    record ThreadLocal(List<String> docs, boolean pub, String name, RType type, RExpr init) implements RItem {
        public ThreadLocal {
            docs = List.copyOf(docs);
        }
    }

    /** {@code use path;}（attrs は {@code #[allow(unused_imports)]} など） */
    record Use(List<String> attrs, String path) implements RItem {
        public Use {
            attrs = List.copyOf(attrs);
        }
    }

    record ModDecl(boolean pub, String name) implements RItem {}

    /** インラインのモジュール {@code #[attrs] mod name { items }}（テストモジュールなど）。 */
    record Mod(List<String> attrs, String name, List<RItem> items) implements RItem {
        public Mod {
            attrs = List.copyOf(attrs);
            items = List.copyOf(items);
        }
    }

    /** 行コメント（複数行可）。 */
    record Comment(String text) implements RItem {}
}
