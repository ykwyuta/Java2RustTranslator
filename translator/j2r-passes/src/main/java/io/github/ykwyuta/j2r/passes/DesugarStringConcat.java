package io.github.ykwyuta.j2r.passes;

import io.github.ykwyuta.j2r.jir.BinaryOp;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.JirRewriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 文字列型の二項 + を {@link Expr.StringConcat} に平坦化する。
 * {@code 1 + 2 + "x"} は {@code (1 + 2) + "x"} なので、左辺の数値加算は平坦化しない。
 */
public final class DesugarStringConcat implements Pass {
    @Override
    public String name() {
        return "DesugarStringConcat";
    }

    @Override
    public Decl.Program run(Decl.Program program) {
        return new JirRewriter() {
            @Override
            protected Expr rewriteExpr(Expr e) {
                if (e instanceof Expr.Binary b && b.op() == BinaryOp.ADD && JType.isString(b.type())) {
                    List<Expr> parts = new ArrayList<>();
                    addParts(b.left(), parts);
                    addParts(b.right(), parts);
                    return new Expr.StringConcat(parts, JType.STRING, b.pos());
                }
                return e;
            }
        }.apply(program);
    }

    private static void addParts(Expr e, List<Expr> out) {
        // 子は先に書き換え済みなので、左側の連結は StringConcat になっている。
        if (e instanceof Expr.StringConcat c) {
            out.addAll(c.parts());
        } else {
            out.add(e);
        }
    }
}
