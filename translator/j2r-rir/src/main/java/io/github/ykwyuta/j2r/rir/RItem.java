package io.github.ykwyuta.j2r.rir;

import java.util.List;

/** Rust のアイテム（モジュール直下に置けるもの）。 */
public sealed interface RItem {

    /** @param ret 戻り値型。null なら unit。 */
    record Fn(List<String> docs, boolean pub, String name, List<Param> params, RType ret, RExpr.Block body) implements RItem {
        public Fn {
            docs = List.copyOf(docs);
            params = List.copyOf(params);
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

    /** 行コメント（複数行可）。 */
    record Comment(String text) implements RItem {}
}
