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

    /** 拡張 for（DesugarEnhancedFor で、配列は添字ループ、Iterable は Iterator のループに展開される）。 */
    record ForEach(String varName, JType varType, Expr iterable, Stmt body, SourcePos pos) implements Stmt {}

    /** switch 文。 */
    record Switch(Expr selector, List<SwitchCase> cases, SourcePos pos) implements Stmt {
        public Switch {
            cases = List.copyOf(cases);
        }
    }

    /** enum 定数の case ラベル。 */
    record EnumLabel(String name, int ordinal) {}

    /** 型パターンの case ラベル {@code case T name}。 */
    record TypePattern(JType type, String binding) {}

    /** {@code case null}。 */
    record NullLabel() {}

    /**
     * switch の case。labels は定数値（Integer / Character / String）、{@link EnumLabel}、{@link TypePattern}、
     * {@link NullLabel}。isDefault は default を含むか。guard は {@code when} 条件（なければ null）。
     * arrow は {@code case X ->} 形式（fall-through しない）。
     */
    record SwitchCase(List<Object> labels, boolean isDefault, Expr guard, List<Stmt> body, boolean arrow, SourcePos pos) {
        public SwitchCase {
            labels = List.copyOf(labels);
            body = List.copyOf(body);
        }
    }

    /** switch 式の {@code yield value}。 */
    record Yield(Expr value, SourcePos pos) implements Stmt {}

    record Return(Expr value, SourcePos pos) implements Stmt {}

    /** label は null 可。 */
    record Break(String label, SourcePos pos) implements Stmt {}

    record Continue(String label, SourcePos pos) implements Stmt {}

    record Labeled(String label, Stmt body, SourcePos pos) implements Stmt {}

    /** {@code throw expr}。 */
    record Throw(Expr exception, SourcePos pos) implements Stmt {}

    /** catch 節（types は捕捉する例外クラスの完全修飾名。マルチキャッチなら複数）。 */
    record Catch(List<String> types, String var, Block body, SourcePos pos) {
        public Catch {
            types = List.copyOf(types);
        }
    }

    /** try 文（try-with-resources は frontend で try/finally に展開する）。finallyBlock は null 可。 */
    record Try(Block body, List<Catch> catches, Block finallyBlock, SourcePos pos) implements Stmt {
        public Try {
            catches = List.copyOf(catches);
        }
    }

    record Unsupported(String description, SourcePos pos) implements Stmt {}
}
