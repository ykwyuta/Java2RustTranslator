package io.github.ykwyuta.j2r.rir;

/** Rust の文。 */
public sealed interface RStmt {

    /** type / init は null 可。 */
    record Let(String name, boolean mut, RType type, RExpr init) implements RStmt {}

    /** semi が false の場合、ブロック様の式（if / loop など）をセミコロンなしで置く。 */
    record ExprStmt(RExpr expr, boolean semi) implements RStmt {}

    record Comment(String text) implements RStmt {}

    /** {@code let pattern = init else { elseBlock };} */
    record LetElse(String pattern, RExpr init, RExpr.Block elseBlock) implements RStmt {}
}
