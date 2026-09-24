package io.github.ykwyuta.j2r.rir;

import java.util.List;

/**
 * RIR を Rust ソースコードに出力する。括弧は演算子の優先順位から必要な箇所にだけ付ける。
 * 書式はおおむね rustfmt に合わせる（インデント 4、ブロックは複数行）。
 */
public final class RustPrinter {
    // 優先順位（大きいほど強く結合する）。
    static final int P_BLOCKLIKE = 0;
    static final int P_JUMP = 1;
    static final int P_ASSIGN = 2;
    static final int P_OR = 4;
    static final int P_AND = 5;
    static final int P_CMP = 6;
    static final int P_BIT_OR = 7;
    static final int P_BIT_XOR = 8;
    static final int P_BIT_AND = 9;
    static final int P_SHIFT = 10;
    static final int P_ADD = 11;
    static final int P_MUL = 12;
    static final int P_CAST = 13;
    /** 前置単項演算子。テンプレートで {@code &{0}} の位置に置く式が満たすべき優先順位。 */
    public static final int P_UNARY = 14;
    /** 後置（メソッド呼び出し・関数呼び出し）。テンプレートで {@code {0}.foo()} の位置に置く式が満たすべき優先順位。 */
    public static final int P_POSTFIX = 15;
    static final int P_ATOM = 16;

    private static final int INLINE_LIMIT = 80;

    private final StringBuilder out = new StringBuilder();
    private int indent;

    private RustPrinter(int indent) {
        this.indent = indent;
    }

    public static String print(RFile file) {
        RustPrinter p = new RustPrinter(0);
        p.file(file);
        return p.out.toString();
    }

    public static String print(RExpr e) {
        RustPrinter p = new RustPrinter(0);
        p.expr(e, 0);
        return p.out.toString();
    }

    // ------------------------------------------------------------------ ファイル・アイテム

    private void file(RFile f) {
        for (String d : f.innerDocs()) {
            out.append(d.isEmpty() ? "//!" : "//! " + d).append('\n');
        }
        for (String a : f.innerAttrs()) {
            out.append("#![").append(a).append("]\n");
        }
        boolean first = f.innerDocs().isEmpty() && f.innerAttrs().isEmpty();
        RItem prev = null;
        for (RItem item : f.items()) {
            boolean grouped = prev != null && prev.getClass() == item.getClass()
                    && (item instanceof RItem.Use || item instanceof RItem.ModDecl);
            if (!first && !grouped) {
                out.append('\n');
            }
            item(item);
            out.append('\n');
            first = false;
            prev = item;
        }
    }

    private void item(RItem item) {
        switch (item) {
            case RItem.Fn fn -> {
                docs(fn.docs());
                for (String a : fn.attrs()) {
                    out.append("#[").append(a).append(']');
                    newline();
                }
                if (!fn.vis().isEmpty()) {
                    out.append(fn.vis()).append(' ');
                }
                out.append("fn ").append(fn.name()).append('(');
                for (int i = 0; i < fn.params().size(); i++) {
                    RItem.Param p = fn.params().get(i);
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(p.mut() ? "mut " : "").append(p.name());
                    if (p.type() != null) {
                        out.append(": ").append(p.type().text());
                    }
                }
                out.append(')');
                if (fn.ret() != null && !fn.ret().equals(RType.UNIT)) {
                    out.append(" -> ").append(fn.ret().text());
                }
                out.append(' ');
                block(fn.body(), true);
            }
            case RItem.Struct st -> {
                docs(st.docs());
                if (st.fields().isEmpty()) {
                    out.append("pub struct ").append(st.name()).append(';');
                } else {
                    out.append("pub struct ").append(st.name()).append(" {");
                    indent++;
                    for (RItem.Field f : st.fields()) {
                        newline();
                        if (!f.vis().isEmpty()) {
                            out.append(f.vis()).append(' ');
                        }
                        out.append(f.name()).append(": ").append(f.type().text()).append(',');
                    }
                    indent--;
                    newline();
                    out.append('}');
                }
            }
            case RItem.Impl im -> {
                for (String a : im.attrs()) {
                    out.append("#[").append(a).append(']');
                    newline();
                }
                out.append("impl ").append(im.header()).append(" {");
                indent++;
                boolean first = true;
                for (RItem it : im.items()) {
                    if (!first) {
                        out.append('\n');
                    }
                    newline();
                    item(it);
                    first = false;
                }
                indent--;
                newline();
                out.append('}');
            }
            case RItem.Const c -> {
                docs(c.docs());
                out.append(c.pub() ? "pub const " : "const ").append(c.name()).append(": ").append(c.type().text()).append(" = ");
                expr(c.value(), 0);
                out.append(';');
            }
            case RItem.ThreadLocal t -> {
                out.append("thread_local! {");
                indent++;
                for (String d : t.docs()) {
                    newline();
                    out.append(d.isEmpty() ? "///" : "/// " + d);
                }
                newline();
                out.append(t.pub() ? "pub static " : "static ").append(t.name()).append(": ").append(t.type().text()).append(" = ");
                expr(t.init(), 0);
                out.append(';');
                indent--;
                newline();
                out.append('}');
            }
            case RItem.Use u -> {
                for (String a : u.attrs()) {
                    out.append("#[").append(a).append("]\n");
                }
                out.append("use ").append(u.path()).append(';');
            }
            case RItem.ModDecl m -> out.append(m.pub() ? "pub mod " : "mod ").append(m.name()).append(';');
            case RItem.Comment c -> comment(c.text());
        }
    }

    private void docs(List<String> docs) {
        for (String d : docs) {
            out.append(d.isEmpty() ? "///" : "/// " + d);
            newline();
        }
    }

    private void comment(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                newline();
            }
            out.append(lines[i].isEmpty() ? "//" : "// " + lines[i]);
        }
    }

    // ------------------------------------------------------------------ 文・ブロック

    private void stmt(RStmt s) {
        switch (s) {
            case RStmt.Let l -> {
                out.append("let ").append(l.mut() ? "mut " : "").append(l.name());
                if (l.type() != null) {
                    out.append(": ").append(l.type().text());
                }
                if (l.init() != null) {
                    out.append(" = ");
                    expr(l.init(), 0);
                }
                out.append(';');
            }
            case RStmt.ExprStmt e -> {
                expr(e.expr(), 0);
                if (e.semi()) {
                    out.append(';');
                }
            }
            case RStmt.Comment c -> comment(c.text());
        }
    }

    private void block(RExpr.Block b, boolean forceMultiline) {
        if (b.label() != null) {
            out.append('\'').append(b.label()).append(": ");
        }
        if (b.stmts().isEmpty() && b.tail() == null) {
            out.append("{}");
            return;
        }
        if (b.inline() && !forceMultiline) {
            String flat = flatBlock(b);
            if (flat != null) {
                out.append(flat);
                return;
            }
        }
        out.append('{');
        indent++;
        for (RStmt s : b.stmts()) {
            newline();
            stmt(s);
        }
        if (b.tail() != null) {
            newline();
            expr(b.tail(), 0);
        }
        indent--;
        newline();
        out.append('}');
    }

    /** 1 行に収まるなら {@code { a; b; c }} 形式の文字列を返す。 */
    private String flatBlock(RExpr.Block b) {
        StringBuilder sb = new StringBuilder("{ ");
        for (RStmt s : b.stmts()) {
            RustPrinter p = new RustPrinter(0);
            p.stmt(s);
            sb.append(p.out).append(' ');
        }
        if (b.tail() != null) {
            RustPrinter p = new RustPrinter(0);
            p.expr(b.tail(), 0);
            sb.append(p.out).append(' ');
        }
        sb.append('}');
        String s = sb.toString();
        return s.indexOf('\n') < 0 && s.length() <= INLINE_LIMIT ? s : null;
    }

    private void newline() {
        out.append('\n');
        out.append("    ".repeat(indent));
    }

    // ------------------------------------------------------------------ 式

    static int precedence(RExpr e) {
        return switch (e) {
            case RExpr.Lit l -> l.text().startsWith("-") ? P_UNARY : P_ATOM;
            case RExpr.Path p -> P_ATOM;
            case RExpr.Call c -> P_POSTFIX;
            case RExpr.MethodCall m -> P_POSTFIX;
            case RExpr.Macro m -> P_POSTFIX;
            case RExpr.Template t -> P_POSTFIX;
            case RExpr.Group g -> precedence(g.expr());
            case RExpr.Unary u -> P_UNARY;
            case RExpr.Cast c -> P_CAST;
            case RExpr.Binary b -> binaryPrecedence(b.op());
            case RExpr.Assign a -> P_ASSIGN;
            case RExpr.Break b -> P_JUMP;
            case RExpr.Continue c -> P_JUMP;
            case RExpr.Return r -> P_JUMP;
            case RExpr.Closure c -> P_JUMP;
            case RExpr.Field f -> P_POSTFIX;
            case RExpr.Index i -> P_POSTFIX;
            case RExpr.IfLet i -> P_BLOCKLIKE;
            case RExpr.StructLit sl -> P_ATOM;
            case RExpr.Array a -> P_ATOM;
            case RExpr.Block b -> P_BLOCKLIKE;
            case RExpr.If i -> P_BLOCKLIKE;
            case RExpr.While w -> P_BLOCKLIKE;
            case RExpr.Loop l -> P_BLOCKLIKE;
            case RExpr.Match m -> P_BLOCKLIKE;
        };
    }

    static int binaryPrecedence(String op) {
        return switch (op) {
            case "*", "/", "%" -> P_MUL;
            case "+", "-" -> P_ADD;
            case "<<", ">>" -> P_SHIFT;
            case "&" -> P_BIT_AND;
            case "^" -> P_BIT_XOR;
            case "|" -> P_BIT_OR;
            case "==", "!=", "<", ">", "<=", ">=" -> P_CMP;
            case "&&" -> P_AND;
            case "||" -> P_OR;
            default -> throw new IllegalArgumentException("unknown operator " + op);
        };
    }

    private void expr(RExpr e, int minPrec) {
        if (minPrec == 0 && e instanceof RExpr.Template) {
            // テンプレートが全体を括弧で囲んでいても、どんな式でもよい位置では括弧を外す（unused_parens 警告を避ける）。
            int start = out.length();
            exprNoParen(e);
            stripOuterParens(start);
            return;
        }
        if (precedence(e) < minPrec) {
            out.append('(');
            exprNoParen(e);
            out.append(')');
        } else {
            exprNoParen(e);
        }
    }

    /** out の start 以降が、対応する 1 組の括弧で全体を囲んだ式なら、その括弧を外す。 */
    private void stripOuterParens(int start) {
        int end = out.length();
        if (end - start < 2 || out.charAt(start) != '(' || out.charAt(end - 1) != ')') {
            return;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < end; i++) {
            char c = out.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0 && i != end - 1) {
                    return;
                }
            } else if (c == ',' && depth == 1) {
                return; // タプル
            }
        }
        out.deleteCharAt(end - 1);
        out.deleteCharAt(start);
    }

    private void exprNoParen(RExpr e) {
        switch (e) {
            case RExpr.Lit l -> out.append(l.text());
            case RExpr.Path p -> out.append(p.path());
            case RExpr.Call c -> {
                expr(c.fn(), P_POSTFIX);
                args(c.args(), '(', ')');
            }
            case RExpr.MethodCall m -> {
                expr(m.receiver(), P_POSTFIX);
                out.append('.').append(m.method());
                args(m.args(), '(', ')');
            }
            case RExpr.Macro m -> {
                out.append(m.name()).append('!');
                if (m.name().equals("vec")) {
                    args(m.args(), '[', ']');
                } else {
                    args(m.args(), '(', ')');
                }
            }
            case RExpr.Template t -> {
                for (Object part : t.parts()) {
                    if (part instanceof String s) {
                        out.append(s);
                    } else if (part instanceof RExpr.Group g) {
                        expr(g.expr(), g.minPrecedence());
                    } else {
                        expr((RExpr) part, 0);
                    }
                }
            }
            case RExpr.Group g -> expr(g.expr(), g.minPrecedence());
            case RExpr.Unary u -> {
                out.append(u.op());
                expr(u.operand(), P_UNARY);
            }
            case RExpr.Cast c -> {
                expr(c.expr(), P_CAST);
                out.append(" as ").append(c.type().text());
            }
            case RExpr.Binary b -> {
                int p = binaryPrecedence(b.op());
                int leftMin = p == P_CMP ? p + 1 : p;
                // `x as i32 < y` / `x as i32 << y` は `<` が型引数の開始と解釈されるため括弧が必要。
                if (b.op().startsWith("<") && b.left() instanceof RExpr.Cast) {
                    leftMin = P_ATOM;
                }
                expr(b.left(), leftMin);
                out.append(' ').append(b.op()).append(' ');
                expr(b.right(), p + 1);
            }
            case RExpr.Assign a -> {
                expr(a.place(), P_UNARY);
                out.append(' ').append(a.op()).append(' ');
                // 代入の右辺には if / match などのブロック様の式も括弧なしで置ける。
                expr(a.value(), 0);
            }
            case RExpr.Block b -> block(b, false);
            case RExpr.If i -> {
                out.append("if ");
                expr(i.condition(), 0);
                out.append(' ');
                block(i.then(), true);
                if (i.elseBranch() != null) {
                    out.append(" else ");
                    if (i.elseBranch() instanceof RExpr.If nested) {
                        exprNoParen(nested);
                    } else {
                        block((RExpr.Block) i.elseBranch(), true);
                    }
                }
            }
            case RExpr.While w -> {
                label(w.label());
                out.append("while ");
                expr(w.condition(), 0);
                out.append(' ');
                block(w.body(), true);
            }
            case RExpr.Loop l -> {
                label(l.label());
                out.append("loop ");
                block(l.body(), true);
            }
            case RExpr.Break b -> {
                out.append("break");
                if (b.label() != null) {
                    out.append(" '").append(b.label());
                }
                if (b.value() != null) {
                    out.append(' ');
                    expr(b.value(), 0);
                }
            }
            case RExpr.Continue c -> {
                out.append("continue");
                if (c.label() != null) {
                    out.append(" '").append(c.label());
                }
            }
            case RExpr.Return r -> {
                out.append("return");
                if (r.value() != null) {
                    out.append(' ');
                    expr(r.value(), 0);
                }
            }
            case RExpr.Match m -> {
                out.append("match ");
                expr(m.scrutinee(), 0);
                out.append(" {");
                indent++;
                for (RExpr.Arm arm : m.arms()) {
                    newline();
                    out.append(arm.pattern()).append(" => ");
                    if (arm.body() instanceof RExpr.Block b) {
                        block(b, true);
                    } else {
                        expr(arm.body(), 0);
                        out.append(',');
                    }
                }
                indent--;
                newline();
                out.append('}');
            }
            case RExpr.Closure c -> {
                if (c.move()) {
                    out.append("move ");
                }
                out.append('|').append(String.join(", ", c.params())).append("| ");
                if (c.ret() != null) {
                    out.append("-> ").append(c.ret().text()).append(' ');
                    block((RExpr.Block) c.body(), true);
                } else {
                    expr(c.body(), 0);
                }
            }
            case RExpr.Field f -> {
                expr(f.receiver(), P_POSTFIX);
                out.append('.').append(f.name());
            }
            case RExpr.Array a -> args(a.elements(), '[', ']');
            case RExpr.IfLet i -> {
                out.append("if let ").append(i.pattern()).append(" = ");
                expr(i.value(), 0);
                out.append(' ');
                block(i.then(), true);
                if (i.elseBranch() != null) {
                    out.append(" else ");
                    if (i.elseBranch() instanceof RExpr.Block b) {
                        block(b, true);
                    } else {
                        exprNoParen(i.elseBranch());
                    }
                }
            }
            case RExpr.StructLit sl -> {
                out.append(sl.name()).append(" {");
                indent++;
                for (RExpr.FieldInit f : sl.fields()) {
                    newline();
                    out.append(f.name()).append(": ");
                    expr(f.value(), 0);
                    out.append(',');
                }
                indent--;
                newline();
                out.append('}');
            }
            case RExpr.Index i -> {
                expr(i.receiver(), P_POSTFIX);
                out.append('[').append(i.index()).append(']');
            }
        }
    }

    private void label(String label) {
        if (label != null) {
            out.append('\'').append(label).append(": ");
        }
    }

    private void args(List<RExpr> args, char open, char close) {
        out.append(open);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            expr(args.get(i), 0);
        }
        out.append(close);
    }
}
