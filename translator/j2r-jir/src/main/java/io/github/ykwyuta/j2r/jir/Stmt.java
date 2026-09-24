package io.github.ykwyuta.j2r.jir;

import io.github.ykwyuta.j2r.common.SourcePos;
import java.util.List;

/** JIR の文。 */
public sealed interface Stmt {
    SourcePos pos();

    record Block(List<Stmt> stmts, SourcePos pos) implements Stmt {
        public Block {
            stmts = List.copyOf(stmts);
        }
    }

    /** ローカル変数宣言。init は null 可。synthetic はパスが導入した一時変数（名前変換しない）。 */
    record LocalVar(String name, JType type, Expr init, boolean synthetic, SourcePos pos) implements Stmt {}

    record ExprStmt(Expr expr, SourcePos pos) implements Stmt {}

    /** elseStmt は null 可。 */
    record If(Expr condition, Stmt thenStmt, Stmt elseStmt, SourcePos pos) implements Stmt {}

    record While(Expr condition, Stmt body, SourcePos pos) implements Stmt {}

    record DoWhile(Stmt body, Expr condition, SourcePos pos) implements Stmt {}

    /** 基本 for。condition は null 可（無限ループ）。 */
    record For(List<Stmt> init, Expr condition, List<Expr> update, Stmt body, SourcePos pos) implements Stmt {
        public For {
            init = List.copyOf(init);
            update = List.copyOf(update);
        }
    }

    /** 拡張 for（DesugarEnhancedFor で For に展開される）。 */
    record ForEach(String varName, JType varType, Expr iterable, Stmt body, SourcePos pos) implements Stmt {}

    /** switch 文。 */
    record Switch(Expr selector, List<SwitchCase> cases, SourcePos pos) implements Stmt {
        public Switch {
            cases = List.copyOf(cases);
        }
    }

    /**
     * switch の case。labels は定数値（Integer / Character / String）で、空なら default。
     * arrow は {@code case X ->} 形式（fall-through しない）。
     */
    record SwitchCase(List<Object> labels, List<Stmt> body, boolean arrow, SourcePos pos) {
        public SwitchCase {
            labels = List.copyOf(labels);
            body = List.copyOf(body);
        }

        public boolean isDefault() {
            return labels.isEmpty();
        }
    }

    record Return(Expr value, SourcePos pos) implements Stmt {}

    /** label は null 可。 */
    record Break(String label, SourcePos pos) implements Stmt {}

    record Continue(String label, SourcePos pos) implements Stmt {}

    record Labeled(String label, Stmt body, SourcePos pos) implements Stmt {}

    /** {@code throw new X(msg)}。例外処理は未実装のため、未捕捉例外としてプログラムを終了させる。 */
    record ThrowNew(String exceptionClass, Expr message, SourcePos pos) implements Stmt {}

    record Unsupported(String description, SourcePos pos) implements Stmt {}
}
