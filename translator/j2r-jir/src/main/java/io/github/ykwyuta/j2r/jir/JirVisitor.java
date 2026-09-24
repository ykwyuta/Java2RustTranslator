package io.github.ykwyuta.j2r.jir;

import java.util.function.Consumer;

/** JIR の木の全ノードを後順（子が先）に訪問するユーティリティ（解析用）。 */
public final class JirVisitor {
    private JirVisitor() {}

    public static void walk(Stmt s, Consumer<Stmt> onStmt, Consumer<Expr> onExpr) {
        new JirRewriter() {
            @Override
            protected Expr rewriteExpr(Expr e) {
                onExpr.accept(e);
                return e;
            }

            @Override
            protected Stmt rewriteStmt(Stmt st) {
                onStmt.accept(st);
                return st;
            }
        }.stmt(s);
    }
}
