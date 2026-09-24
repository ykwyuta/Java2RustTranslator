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
    /** これより長い行は、メソッドの連鎖・引数・関数の仮引数を折り返す（rustfmt の max_width）。 */
    private static final int MAX_WIDTH = 100;

    private final StringBuilder out = new StringBuilder();
    private int indent;
    /** 長い行を折り返すか（{@link #printWrapped}）。 */
    private final boolean wrap;

    private RustPrinter(int indent) {
        this(indent, false);
    }

    private RustPrinter(int indent, boolean wrap) {
        this.indent = indent;
        this.wrap = wrap;
    }

    public static String print(RFile file) {
        RustPrinter p = new RustPrinter(0);
        p.file(file);
        return p.out.toString();
    }

    /** 1 行が {@value #MAX_WIDTH} 文字を超える場合に、メソッドの連鎖・引数・関数の仮引数を rustfmt のように折り返して出力する。 */
    public static String printWrapped(RFile file) {
        RustPrinter p = new RustPrinter(0, true);
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
                if (fn.async()) {
                    out.append("async ");
                }
                out.append("fn ").append(fn.name()).append('(');
                List<String> params = new java.util.ArrayList<>();
                for (RItem.Param p : fn.params()) {
                    params.add((p.mut() ? "mut " : "") + p.name() + (p.type() != null ? ": " + p.type().text() : ""));
                }
                String ret = fn.ret() != null && !fn.ret().equals(RType.UNIT) ? " -> " + fn.ret().text() : "";
                if (wrap && column() + String.join(", ", params).length() + 1 + ret.length() + 2 > MAX_WIDTH && !params.isEmpty()) {
                    indent++;
                    for (String p : params) {
                        newline();
                        out.append(p).append(',');
                    }
                    indent--;
                    newline();
                } else {
                    out.append(String.join(", ", params));
                }
                out.append(')');
                out.append(ret);
                out.append(' ');
                block(fn.body(), true);
            }
            case RItem.Struct st -> {
                docs(st.docs());
                attrs(st.attrs());
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
            case RItem.Enum en -> {
                docs(en.docs());
                attrs(en.attrs());
                out.append("pub enum ").append(en.name()).append(" {");
                indent++;
                for (RItem.Variant v : en.variants()) {
                    newline();
                    docs(v.docs());
                    attrs(v.attrs());
                    out.append(v.text()).append(',');
                }
                indent--;
                newline();
                out.append('}');
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
                out.append(u.pub() ? "pub use " : "use ").append(u.path()).append(';');
            }
            case RItem.ModDecl m -> out.append(m.pub() ? "pub mod " : "mod ").append(m.name()).append(';');
            case RItem.TypeAlias t -> {
                docs(t.docs());
                out.append(t.pub() ? "pub type " : "type ").append(t.name()).append(" = ").append(t.type().text()).append(';');
            }
            case RItem.Static st -> {
                docs(st.docs());
                out.append(st.pub() ? "pub static " : "static ").append(st.name()).append(": ").append(st.type().text()).append(" = ");
                expr(st.value(), 0);
                out.append(';');
            }
            case RItem.Mod m -> {
                for (String a : m.attrs()) {
                    out.append("#[").append(a).append(']');
                    newline();
                }
                out.append("mod ").append(m.name()).append(" {");
                indent++;
                boolean first = true;
                for (RItem it : m.items()) {
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
            case RItem.Comment c -> comment(c.text());
        }
    }

    private void attrs(List<String> attrs) {
        for (String a : attrs) {
            out.append("#[").append(a).append(']');
            newline();
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
            case RStmt.LetElse l -> {
                out.append("let ").append(l.pattern()).append(" = ");
                expr(l.init(), 0);
                out.append(" else ");
                block(l.elseBlock(), true);
                out.append(';');
            }
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
            case RExpr.Try t -> P_POSTFIX;
            case RExpr.Await a -> P_POSTFIX;
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
            case RExpr.Lit l -> lit(l.text());
            case RExpr.Path p -> out.append(p.path());
            case RExpr.Call c -> {
                expr(c.fn(), P_POSTFIX);
                args(c.args(), '(', ')');
            }
            case RExpr.MethodCall m when chain(m) -> { }
            case RExpr.MethodCall m -> {
                expr(m.receiver(), P_POSTFIX);
                out.append('.').append(m.method());
                args(m.args(), '(', ')');
            }
            case RExpr.Macro m -> {
                out.append(m.name()).append('!');
                if (m.name().equals("vec")) {
                    args(m.args(), '[', ']', false);
                } else {
                    args(m.args(), '(', ')', false);
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
                    if (f.name().equals("..")) {
                        // 構造体更新構文 ..base（末尾のカンマは付けない）
                        out.append("..");
                        expr(f.value(), 0);
                        continue;
                    }
                    if (wrap && f.value() instanceof RExpr.Path p && p.path().equals(f.name())) {
                        // フィールド名と同じ変数の省略形
                        out.append(f.name()).append(',');
                        continue;
                    }
                    out.append(f.name()).append(": ");
                    expr(f.value(), 0);
                    out.append(',');
                }
                indent--;
                newline();
                out.append('}');
            }
            case RExpr.Try t when chain(t) -> { }
            case RExpr.Try t -> {
                expr(t.expr(), P_POSTFIX);
                out.append('?');
            }
            case RExpr.Await a when chain(a) -> { }
            case RExpr.Await a -> {
                expr(a.expr(), P_POSTFIX);
                out.append(".await");
            }
            case RExpr.Index i -> {
                expr(i.receiver(), P_POSTFIX);
                out.append('[').append(i.index()).append(']');
            }
        }
    }

    /**
     * リテラル。文字列リテラルの行継続（{@code \} + 改行。次の行の先頭の空白は Rust が捨てる）は、
     * 続きの行を 1 段深くインデントする。
     */
    private void lit(String text) {
        String[] lines = text.split("\\\\\n *", -1);
        out.append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            out.append("\\\n").append("    ".repeat(indent + 1)).append(lines[i]);
        }
    }

    /** 1 行に書いたときの式。 */
    private static String flat(RExpr e) {
        RustPrinter p = new RustPrinter(0);
        p.expr(e, 0);
        return p.out.toString();
    }

    /** 現在の行の桁（0 始まり）。 */
    private int column() {
        return out.length() - out.lastIndexOf("\n") - 1;
    }

    /**
     * メソッド呼び出し・.await・? が 3 つ以上続く連鎖が 1 行に収まらない場合、要素ごとに改行して出力する（rustfmt と同じ）。
     * 出力したら true。
     */
    private boolean chain(RExpr e) {
        if (!wrap) {
            return false;
        }
        List<RExpr> elems = new java.util.ArrayList<>();
        RExpr root = e;
        int calls = 0;
        while (true) {
            if (root instanceof RExpr.MethodCall m) {
                elems.add(m);
                calls++;
                root = m.receiver();
            } else if (root instanceof RExpr.Await a) {
                elems.add(a);
                root = a.expr();
            } else if (root instanceof RExpr.Try t) {
                elems.add(t);
                root = t.expr();
            } else {
                break;
            }
        }
        if (calls < 2 || elems.size() < 3) {
            return false;
        }
        String flat = flat(e);
        if (!flat.contains("\n") && column() + flat.length() <= MAX_WIDTH) {
            return false;
        }
        int start = out.length();
        expr(root, P_POSTFIX);
        // 根が複数行（閉じ括弧だけの行で終わる）なら、rustfmt と同じく連鎖を根と同じ深さに置く。
        boolean deeper = out.indexOf("\n", start) < 0;
        if (deeper) {
            indent++;
        }
        for (int i = elems.size() - 1; i >= 0; i--) {
            switch (elems.get(i)) {
                case RExpr.MethodCall m -> {
                    newline();
                    out.append('.').append(m.method());
                    args(m.args(), '(', ')');
                }
                case RExpr.Await a -> {
                    newline();
                    out.append(".await");
                }
                case RExpr.Try t -> out.append('?');
                default -> throw new IllegalStateException();
            }
        }
        if (deeper) {
            indent--;
        }
        return true;
    }

    private void label(String label) {
        if (label != null) {
            out.append('\'').append(label).append(": ");
        }
    }

    private void args(List<RExpr> args, char open, char close) {
        args(args, open, close, wrap);
    }

    private void args(List<RExpr> args, char open, char close, boolean mayBreak) {
        if (mayBreak && !args.isEmpty() && open == '(') {
            List<String> flat = args.stream().map(RustPrinter::flat).toList();
            boolean multiline = flat.stream().anyMatch(f -> f.contains("\n"));
            boolean blocky = args.stream().anyMatch(a -> a instanceof RExpr.Closure c && c.body() instanceof RExpr.Block
                    || a instanceof RExpr.Block);
            if (!blocky && (multiline || column() + String.join(", ", flat).length() + 2 > MAX_WIDTH)) {
                out.append(open);
                indent++;
                for (RExpr a : args) {
                    newline();
                    expr(a, 0);
                    out.append(',');
                }
                indent--;
                newline();
                out.append(close);
                return;
            }
        }
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
