package io.github.ykwyuta.j2r.spring;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis の {@code test} 属性（OGNL）のうち、よく使う部分だけを読む。
 *
 * <pre>
 * or   := and (("or" | "||") and)*
 * and  := not (("and" | "&amp;&amp;") not)*
 * not  := ("not" | "!") not | cmp
 * cmp  := add (("==" | "!=" | "&lt;" | "&gt;" | "&lt;=" | "&gt;=" | "eq" | "neq" | "lt" | "gt" | "lte" | "gte") add)?
 * add  := primary ("+" primary)*
 * primary := "null" | "true" | "false" | 数値 | '文字列' | "文字列" | "(" or ")" | 名前 ("." 名前 ("(" ")")?)*
 * </pre>
 */
final class Ognl {
    sealed interface Node {}

    record Or(Node left, Node right) implements Node {}

    record And(Node left, Node right) implements Node {}

    record Not(Node operand) implements Node {}

    /** op は Java / Rust の記法（==, !=, <, >, <=, >=）。 */
    record Compare(String op, Node left, Node right) implements Node {}

    /** {@code a + b}（{@code <bind>} の文字列の連結）。 */
    record Add(Node left, Node right) implements Node {}

    record NullLit() implements Node {}

    record BoolLit(boolean value) implements Node {}

    record NumLit(String text) implements Node {}

    record StrLit(String value) implements Node {}

    /** 引数・プロパティの参照 {@code a.b.c}。 */
    record Path(List<String> segments) implements Node {
        Path {
            segments = List.copyOf(segments);
        }
    }

    /** 引数なしのメソッド呼び出し {@code target.name()}。 */
    record MethodCall(Node target, String name) implements Node {}

    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private Ognl(String src) {
        this.src = src;
    }

    static Node parse(String src) throws ParseException {
        Ognl p = new Ognl(src);
        Node n = p.or();
        p.skipWs();
        if (p.pos != src.length()) {
            throw new ParseException("unexpected '" + src.substring(p.pos) + "' in OGNL expression: " + src);
        }
        return n;
    }

    private Node or() throws ParseException {
        Node left = and();
        while (true) {
            if (word("or") || symbol("||")) {
                left = new Or(left, and());
            } else {
                return left;
            }
        }
    }

    private Node and() throws ParseException {
        Node left = not();
        while (true) {
            if (word("and") || symbol("&&")) {
                left = new And(left, not());
            } else {
                return left;
            }
        }
    }

    private Node not() throws ParseException {
        if (word("not") || (peekSymbol("!") && !peekSymbol("!=") && symbol("!"))) {
            return new Not(not());
        }
        return cmp();
    }

    private Node cmp() throws ParseException {
        Node left = add();
        String[][] ops = {{"==", "=="}, {"!=", "!="}, {"<=", "<="}, {">=", ">="}, {"<", "<"}, {">", ">"}};
        for (String[] op : ops) {
            if (symbol(op[0])) {
                return new Compare(op[1], left, add());
            }
        }
        String[][] words = {{"eq", "=="}, {"neq", "!="}, {"lte", "<="}, {"gte", ">="}, {"lt", "<"}, {"gt", ">"}};
        for (String[] op : words) {
            if (word(op[0])) {
                return new Compare(op[1], left, add());
            }
        }
        return left;
    }

    private Node add() throws ParseException {
        Node left = primary();
        while (symbol("+")) {
            left = new Add(left, primary());
        }
        return left;
    }

    private Node primary() throws ParseException {
        skipWs();
        if (pos >= src.length()) {
            throw new ParseException("unexpected end of OGNL expression: " + src);
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Node n = or();
            if (!symbol(")")) {
                throw new ParseException("missing ')' in OGNL expression: " + src);
            }
            return n;
        }
        if (c == '\'' || c == '"') {
            int end = src.indexOf(c, pos + 1);
            if (end < 0) {
                throw new ParseException("unterminated string in OGNL expression: " + src);
            }
            String s = src.substring(pos + 1, end);
            pos = end + 1;
            return new StrLit(s);
        }
        if (Character.isDigit(c) || c == '-') {
            int start = pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            return new NumLit(src.substring(start, pos));
        }
        if (Character.isJavaIdentifierStart(c)) {
            String name = ident();
            switch (name) {
                case "null" -> {
                    return new NullLit();
                }
                case "true" -> {
                    return new BoolLit(true);
                }
                case "false" -> {
                    return new BoolLit(false);
                }
                default -> { }
            }
            List<String> segs = new ArrayList<>(List.of(name));
            Node node = null;
            while (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                String seg = ident();
                if (pos < src.length() && src.charAt(pos) == '(') {
                    pos++;
                    if (!symbol(")")) {
                        throw new ParseException("method calls with arguments are not supported: " + src);
                    }
                    node = new MethodCall(node == null ? new Path(segs) : node, seg);
                } else if (node != null) {
                    throw new ParseException("property access on a method result is not supported: " + src);
                } else {
                    segs.add(seg);
                }
            }
            return node == null ? new Path(segs) : node;
        }
        throw new ParseException("unexpected '" + c + "' in OGNL expression: " + src);
    }

    private String ident() throws ParseException {
        skipWs();
        int start = pos;
        while (pos < src.length() && Character.isJavaIdentifierPart(src.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw new ParseException("identifier expected in OGNL expression: " + src);
        }
        return src.substring(start, pos);
    }

    private boolean word(String w) {
        skipWs();
        int end = pos + w.length();
        if (src.startsWith(w, pos) && (end == src.length() || !Character.isJavaIdentifierPart(src.charAt(end)))) {
            pos = end;
            return true;
        }
        return false;
    }

    private boolean peekSymbol(String s) {
        skipWs();
        return src.startsWith(s, pos);
    }

    private boolean symbol(String s) {
        if (peekSymbol(s)) {
            pos += s.length();
            return true;
        }
        return false;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
