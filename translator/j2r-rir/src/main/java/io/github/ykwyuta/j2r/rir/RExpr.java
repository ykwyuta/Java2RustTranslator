package io.github.ykwyuta.j2r.rir;

import java.util.List;

/** Rust の式。括弧の要否は {@link RustPrinter} が優先順位から決める。 */
public sealed interface RExpr {

    /** リテラル（数値・bool・文字列リテラル・char リテラル）。 */
    record Lit(String text) implements RExpr {}

    /** パス（変数名、{@code crate::a::b}、{@code i32::MAX} など）。 */
    record Path(String path) implements RExpr {}

    record Call(RExpr fn, List<RExpr> args) implements RExpr {
        public Call {
            args = List.copyOf(args);
        }
    }

    record MethodCall(RExpr receiver, String method, List<RExpr> args) implements RExpr {
        public MethodCall {
            args = List.copyOf(args);
        }
    }

    /** マクロ呼び出し {@code name!(args)}。 */
    record Macro(String name, List<RExpr> args) implements RExpr {
        public Macro {
            args = List.copyOf(args);
        }
    }

    record Binary(String op, RExpr left, RExpr right) implements RExpr {}

    /** 前置演算子（{@code -}, {@code !}, {@code &}, {@code *}）。 */
    record Unary(String op, RExpr operand) implements RExpr {}

    record Cast(RExpr expr, RType type) implements RExpr {}

    /** 代入・複合代入（op は {@code =}, {@code +=} など）。値は unit。 */
    record Assign(RExpr place, String op, RExpr value) implements RExpr {}

    /**
     * ブロック。label は null 可（{@code 'label: { ... }}）。
     * inline が true の場合、短ければ 1 行で出力する（式中の一時ブロック用）。
     */
    record Block(List<RStmt> stmts, RExpr tail, String label, boolean inline) implements RExpr {
        public Block {
            stmts = List.copyOf(stmts);
        }

        public static Block of(List<RStmt> stmts) {
            return new Block(stmts, null, null, false);
        }
    }

    /** elseBranch は null / Block / If。 */
    record If(RExpr condition, Block then, RExpr elseBranch) implements RExpr {}

    record While(String label, RExpr condition, Block body) implements RExpr {}

    record Loop(String label, Block body) implements RExpr {}

    record Break(String label, RExpr value) implements RExpr {}

    record Continue(String label) implements RExpr {}

    record Return(RExpr value) implements RExpr {}

    record Match(RExpr scrutinee, List<Arm> arms) implements RExpr {
        public Match {
            arms = List.copyOf(arms);
        }
    }

    record Arm(String pattern, RExpr body) {}

    /** クロージャ {@code [move] |params| [-> ret] body}（ret を書く場合、body はブロック）。 */
    record Closure(boolean move, List<String> params, RType ret, RExpr body) implements RExpr {
        public Closure {
            params = List.copyOf(params);
        }

        public static Closure of(List<String> params, RExpr body) {
            return new Closure(false, params, null, body);
        }
    }

    /** フィールド参照 {@code receiver.name}。 */
    record Field(RExpr receiver, String name) implements RExpr {}

    /** {@code if let pattern = value { then } [else elseBranch]}。 */
    record IfLet(String pattern, RExpr value, Block then, RExpr elseBranch) implements RExpr {}

    /** 構造体リテラル {@code Name { a: x, b: y }}。 */
    record StructLit(String name, List<FieldInit> fields) implements RExpr {
        public StructLit {
            fields = List.copyOf(fields);
        }
    }

    record FieldInit(String name, RExpr value) {}

    /** 例外（Err）の伝播 {@code expr?}。 */
    record Try(RExpr expr) implements RExpr {}

    /** 添字 {@code receiver[index]}。 */
    record Index(RExpr receiver, int index) implements RExpr {}

    /** 配列式 {@code [a, b, c]}。 */
    record Array(List<RExpr> elements) implements RExpr {
        public Array {
            elements = List.copyOf(elements);
        }
    }

    /**
     * API マッピング規則のテンプレート展開結果。parts は文字列断片と式が交互に並ぶ。
     * 全体は後置式（呼び出し・パス）とみなす（マッピング規則側の約束）。
     */
    record Template(List<Object> parts) implements RExpr {
        public Template {
            parts = List.copyOf(parts);
        }
    }

    /** 最低限の優先順位を要求する式の断片（テンプレート内のプレースホルダ用）。 */
    record Group(RExpr expr, int minPrecedence) implements RExpr {}
}
