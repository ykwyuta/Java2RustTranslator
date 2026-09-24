package io.github.ykwyuta.j2r.spring;

import java.util.ArrayList;
import java.util.List;

/**
 * Thymeleaf の標準式（{@code ${...}}・{@code *{...}}・{@code @{...}}・{@code ~{...}}・リテラル・演算子）と、
 * {@code ${...}} の中の SpEL のよく使う部分を 1 つの文法で読む。
 */
final class ThymeleafExpr {
    sealed interface Node {}

    /** 変数（{@code ${todo}} の todo）。 */
    record Var(String name) implements Node {}

    /** 選択式の対象（{@code *{title}} の th:object）。 */
    record SelectionRoot() implements Node {}

    /** プロパティ {@code target.name}。 */
    record Prop(Node target, String name) implements Node {}

    /** メソッド呼び出し {@code target.name(args)}。 */
    record Call(Node target, String name, List<Node> args) implements Node {
        Call {
            args = List.copyOf(args);
        }
    }

    /** 式ユーティリティ {@code #temporals.format(...)}。 */
    record Util(String object, String method, List<Node> args) implements Node {
        Util {
            args = List.copyOf(args);
        }
    }

    record Str(String value) implements Node {}

    record Num(String text) implements Node {}

    record Bool(boolean value) implements Node {}

    record Null() implements Node {}

    /** op: ! / -（単項）。 */
    record Unary(String op, Node operand) implements Node {}

    /** op: + - * / % == != &lt; &gt; &lt;= &gt;= &amp;&amp; ||。 */
    record Binary(String op, Node left, Node right) implements Node {}

    /** 条件演算子（whenFalse は null 可: 偽なら null）。 */
    record Cond(Node condition, Node whenTrue, Node whenFalse) implements Node {}

    /** Elvis 演算子 {@code a ?: b}。 */
    record Elvis(Node value, Node orElse) implements Node {}

    /** リンク式 {@code @{/todos/{id}(id=${todo.id}, q=${q})}}。 */
    record Link(String path, List<Param> params) implements Node {
        Link {
            params = List.copyOf(params);
        }
    }

    record Param(String name, Node value) {}

    /** フラグメント式 {@code ~{fragments/layout :: head('一覧')}}（args は null なら引数の括弧なし）。 */
    record Fragment(String template, String selector, List<Node> args) implements Node {}

    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private ThymeleafExpr(String src) {
        this.src = src;
    }

    static Node parse(String src) throws ParseException {
        ThymeleafExpr p = new ThymeleafExpr(src);
        Node n = p.expr();
        p.ws();
        if (p.pos != src.length()) {
            throw new ParseException("unexpected '" + src.substring(p.pos) + "' in expression: " + src);
        }
        return n;
    }

    /** th:replace / th:insert の値（{@code ~{...}} を省略した書き方も読む）。 */
    static Fragment parseFragment(String src) throws ParseException {
        String s = src.strip();
        if (!s.startsWith("~{")) {
            s = "~{" + s + "}";
        }
        Node n = parse(s);
        if (n instanceof Fragment f) {
            return f;
        }
        throw new ParseException("fragment expression expected: " + src);
    }

    private Node expr() throws ParseException {
        Node c = or();
        ws();
        if (src.startsWith("?:", pos)) {
            pos += 2;
            return new Elvis(c, expr());
        }
        if (peek('?')) {
            pos++;
            Node t = expr();
            ws();
            Node e = null;
            if (peek(':')) {
                pos++;
                e = expr();
            }
            return new Cond(c, t, e);
        }
        return c;
    }

    private Node or() throws ParseException {
        Node l = and();
        while (true) {
            if (symbol("||") || word("or")) {
                l = new Binary("||", l, and());
            } else {
                return l;
            }
        }
    }

    private Node and() throws ParseException {
        Node l = equality();
        while (true) {
            if (symbol("&&") || word("and")) {
                l = new Binary("&&", l, equality());
            } else {
                return l;
            }
        }
    }

    private Node equality() throws ParseException {
        Node l = relational();
        while (true) {
            if (symbol("==") || word("eq")) {
                l = new Binary("==", l, relational());
            } else if (symbol("!=") || word("ne") || word("neq")) {
                l = new Binary("!=", l, relational());
            } else {
                return l;
            }
        }
    }

    private Node relational() throws ParseException {
        Node l = additive();
        String[][] ops = {{"<=", "<="}, {">=", ">="}, {"<", "<"}, {">", ">"}};
        for (String[] op : ops) {
            if (symbol(op[0])) {
                return new Binary(op[1], l, additive());
            }
        }
        String[][] words = {{"le", "<="}, {"ge", ">="}, {"lt", "<"}, {"gt", ">"}};
        for (String[] op : words) {
            if (word(op[0])) {
                return new Binary(op[1], l, additive());
            }
        }
        return l;
    }

    private Node additive() throws ParseException {
        Node l = multiplicative();
        while (true) {
            ws();
            if (peek('+')) {
                pos++;
                l = new Binary("+", l, multiplicative());
            } else if (peek('-') && !src.startsWith("->", pos)) {
                pos++;
                l = new Binary("-", l, multiplicative());
            } else {
                return l;
            }
        }
    }

    private Node multiplicative() throws ParseException {
        Node l = unary();
        while (true) {
            ws();
            if (peek('*') && !src.startsWith("*{", pos)) {
                pos++;
                l = new Binary("*", l, unary());
            } else if (peek('/')) {
                pos++;
                l = new Binary("/", l, unary());
            } else if (peek('%')) {
                pos++;
                l = new Binary("%", l, unary());
            } else {
                return l;
            }
        }
    }

    private Node unary() throws ParseException {
        ws();
        if ((peek('!') && !src.startsWith("!=", pos)) || word("not")) {
            if (peek('!')) {
                pos++;
            }
            return new Unary("!", unary());
        }
        if (peek('-') && pos + 1 < src.length() && !Character.isDigit(src.charAt(pos + 1))) {
            pos++;
            return new Unary("-", unary());
        }
        return postfix(primary());
    }

    private Node postfix(Node n) throws ParseException {
        while (true) {
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                String name = ident();
                if (pos < src.length() && src.charAt(pos) == '(') {
                    pos++;
                    n = new Call(n, name, args(')'));
                } else {
                    n = new Prop(n, name);
                }
            } else {
                return n;
            }
        }
    }

    private Node primary() throws ParseException {
        ws();
        if (pos >= src.length()) {
            throw new ParseException("unexpected end of expression: " + src);
        }
        char c = src.charAt(pos);
        if (src.startsWith("${", pos)) {
            pos += 2;
            Node n = expr();
            expect('}');
            return n;
        }
        if (src.startsWith("*{", pos)) {
            pos += 2;
            ws();
            Node n = postfix(new Prop(new SelectionRoot(), ident()));
            n = continueExpr(n);
            expect('}');
            return n;
        }
        if (src.startsWith("@{", pos)) {
            pos += 2;
            return link();
        }
        if (src.startsWith("~{", pos)) {
            pos += 2;
            return fragment();
        }
        if (c == '(') {
            pos++;
            Node n = expr();
            expect(')');
            return n;
        }
        if (c == '\'') {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (pos < src.length() && src.charAt(pos) != '\'') {
                if (src.charAt(pos) == '\\' && pos + 1 < src.length()) {
                    pos++;
                }
                sb.append(src.charAt(pos++));
            }
            expect('\'');
            return new Str(sb.toString());
        }
        if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
            int start = pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            return new Num(src.substring(start, pos));
        }
        if (c == '#') {
            pos++;
            String object = ident();
            expect('.');
            String method = ident();
            expect('(');
            return new Util(object, method, args(')'));
        }
        if (Character.isJavaIdentifierStart(c)) {
            String name = ident();
            return switch (name) {
                case "true" -> new Bool(true);
                case "false" -> new Bool(false);
                case "null" -> new Null();
                default -> {
                    if (pos < src.length() && src.charAt(pos) == '(') {
                        throw new ParseException("function calls are not supported: " + src);
                    }
                    yield new Var(name);
                }
            };
        }
        throw new ParseException("unexpected '" + c + "' in expression: " + src);
    }

    /** {@code *{a.b > 0}} のように選択式の中に続く演算子。 */
    private Node continueExpr(Node first) throws ParseException {
        ws();
        if (pos < src.length() && src.charAt(pos) != '}') {
            throw new ParseException("only property paths are supported in selection expressions: " + src);
        }
        return first;
    }

    private Node link() throws ParseException {
        StringBuilder path = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '(' && src.charAt(pos) != '}') {
            if (src.charAt(pos) == '{') {
                int end = src.indexOf('}', pos);
                if (end < 0) {
                    throw new ParseException("unterminated path variable in link: " + src);
                }
                path.append(src, pos, end + 1);
                pos = end + 1;
            } else {
                path.append(src.charAt(pos++));
            }
        }
        List<Param> params = new ArrayList<>();
        if (peek('(')) {
            pos++;
            while (true) {
                ws();
                String name = ident();
                ws();
                expect('=');
                params.add(new Param(name, expr()));
                ws();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect(')');
                break;
            }
        }
        expect('}');
        return new Link(path.toString().strip(), params);
    }

    private Node fragment() throws ParseException {
        int sep = src.indexOf("::", pos);
        if (sep < 0) {
            throw new ParseException("fragment selector expected: " + src);
        }
        String template = src.substring(pos, sep).strip();
        pos = sep + 2;
        ws();
        String selector = ident();
        List<Node> args = null;
        ws();
        if (peek('(')) {
            pos++;
            args = args(')');
        }
        expect('}');
        return new Fragment(template, selector, args);
    }

    private List<Node> args(char close) throws ParseException {
        List<Node> out = new ArrayList<>();
        ws();
        if (peek(close)) {
            pos++;
            return out;
        }
        while (true) {
            out.add(expr());
            ws();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect(close);
            return out;
        }
    }

    private String ident() throws ParseException {
        ws();
        int start = pos;
        while (pos < src.length() && (Character.isJavaIdentifierPart(src.charAt(pos)) || src.charAt(pos) == '-')) {
            pos++;
        }
        if (start == pos) {
            throw new ParseException("identifier expected in expression: " + src);
        }
        return src.substring(start, pos);
    }

    private boolean word(String w) {
        ws();
        int end = pos + w.length();
        if (src.startsWith(w, pos) && (end == src.length() || !Character.isJavaIdentifierPart(src.charAt(end)))) {
            pos = end;
            return true;
        }
        return false;
    }

    private boolean symbol(String s) {
        ws();
        if (src.startsWith(s, pos)) {
            pos += s.length();
            return true;
        }
        return false;
    }

    private boolean peek(char c) {
        ws();
        return pos < src.length() && src.charAt(pos) == c;
    }

    private void expect(char c) throws ParseException {
        ws();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new ParseException("'" + c + "' expected in expression: " + src);
        }
        pos++;
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
