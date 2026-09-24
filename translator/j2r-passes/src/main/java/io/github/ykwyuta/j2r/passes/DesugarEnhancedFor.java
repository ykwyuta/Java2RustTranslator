package io.github.ykwyuta.j2r.passes;

import io.github.ykwyuta.j2r.jir.BinaryOp;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.JirRewriter;
import io.github.ykwyuta.j2r.jir.Stmt;
import java.util.List;

/**
 * 配列に対する拡張 for を添字ループに展開する。
 * <pre>
 * for (T x : arr) body
 *   ⇒ for (T[] $a = arr, int $i = 0; $i < $a.length; $i++) { T x = $a[$i]; body }
 * </pre>
 * 配列式は一度だけ評価され、要素は各反復の時点で読まれる（Java の意味と同じ）。
 */
public final class DesugarEnhancedFor implements Pass {
    @Override
    public String name() {
        return "DesugarEnhancedFor";
    }

    @Override
    public Decl.Program run(Decl.Program program) {
        int[] counter = {0};
        return new JirRewriter() {
            @Override
            protected Stmt rewriteStmt(Stmt s) {
                if (!(s instanceof Stmt.ForEach fe) || !(fe.iterable().type() instanceof JType.ArrayType at)) {
                    return s;
                }
                int n = counter[0]++;
                String arr = "__j2r_arr" + n;
                String idx = "__j2r_i" + n;
                var pos = fe.pos();
                Expr arrRef = new Expr.Local(arr, at, pos);
                Expr idxRef = new Expr.Local(idx, JType.INT, pos);
                Stmt declArr = new Stmt.LocalVar(arr, at, fe.iterable(), true, pos);
                Stmt declIdx = new Stmt.LocalVar(idx, JType.INT, new Expr.Literal(0, JType.INT, pos), true, pos);
                Expr cond = new Expr.Binary(BinaryOp.LT, idxRef, new Expr.ArrayLength(arrRef, JType.INT, pos), JType.INT, JType.BOOLEAN, pos);
                Expr update = new Expr.IncDec(true, false, idxRef, JType.INT, pos);
                Expr element = new Expr.ArrayAccess(arrRef, idxRef, at.component(), pos);
                Expr init = at.component().equals(fe.varType()) || !(fe.varType() instanceof JType.Primitive)
                        ? element : new Expr.Cast(element, fe.varType(), true, pos);
                // Java はローカル変数のシャドーイングを許さないので、元の本体ブロックは平坦化してよい。
                List<Stmt> stmts = new java.util.ArrayList<>();
                stmts.add(new Stmt.LocalVar(fe.varName(), fe.varType(), init, false, pos));
                if (fe.body() instanceof Stmt.Block b) {
                    stmts.addAll(b.stmts());
                } else {
                    stmts.add(fe.body());
                }
                Stmt body = new Stmt.Block(stmts, pos);
                return new Stmt.For(List.of(declArr, declIdx), cond, List.of(update), body, pos);
            }
        }.apply(program);
    }
}
