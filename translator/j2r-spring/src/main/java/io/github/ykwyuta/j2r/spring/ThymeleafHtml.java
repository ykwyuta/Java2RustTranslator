package io.github.ykwyuta.j2r.spring;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Thymeleaf のテンプレート（HTML）を、元の書き方（属性の順序・空白・引用符）を保ったまま要素の木にする。
 * Thymeleaf は処理しない部分をテンプレートのまま出力するので、変換でも同じ文字列を出せるようにする。
 */
final class ThymeleafHtml {
    private ThymeleafHtml() {}

    sealed interface Node {}

    /** テキスト・コメント・DOCTYPE（そのまま出力する）。 */
    record Text(String raw) implements Node {}

    /**
     * 属性。
     *
     * @param ws    属性の前の空白
     * @param raw   値の書き方（引用符を含む。値のない属性は null）
     * @param value 値（文字参照を戻したもの。値のない属性は null）
     */
    record Attr(String ws, String name, String raw, String value) {
        String text() {
            return ws + name + (raw == null ? "" : "=" + raw);
        }
    }

    /**
     * 要素。
     *
     * @param tagEnd   開始タグの終わり（{@code >}・{@code />} とその前の空白）
     * @param endTag   終了タグ（void 要素・{@code />} で閉じた要素は null）
     */
    record Element(String name, List<Attr> attrs, String tagEnd, List<Node> children, String endTag) implements Node {
        Attr attr(String n) {
            for (Attr a : attrs) {
                if (a.name().equals(n)) {
                    return a;
                }
            }
            return null;
        }

        boolean has(String n) {
            return attr(n) != null;
        }
    }

    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
            "source", "track", "wbr");

    static List<Node> parse(String src) {
        List<Node> root = new ArrayList<>();
        Deque<Element> stack = new ArrayDeque<>();
        Deque<List<Node>> children = new ArrayDeque<>();
        children.push(root);
        int i = 0;
        StringBuilder text = new StringBuilder();
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            if (src.startsWith("<!--", i)) {
                int end = src.indexOf("-->", i);
                end = end < 0 ? src.length() : end + 3;
                text.append(src, i, end);
                i = end;
                continue;
            }
            if (src.startsWith("<!", i) || src.startsWith("<?", i)) {
                int end = src.indexOf('>', i);
                end = end < 0 ? src.length() : end + 1;
                text.append(src, i, end);
                i = end;
                continue;
            }
            if (src.startsWith("</", i)) {
                int end = src.indexOf('>', i);
                end = end < 0 ? src.length() : end + 1;
                String name = src.substring(i + 2, end - 1).strip().toLowerCase(Locale.ROOT);
                flush(text, children.peek());
                // 対応する開始タグまで閉じる。
                if (stack.stream().anyMatch(e -> e.name().equalsIgnoreCase(name))) {
                    while (!stack.isEmpty()) {
                        Element open = stack.pop();
                        List<Node> kids = children.pop();
                        Element closed = new Element(open.name(), open.attrs(), open.tagEnd(), kids,
                                open.name().equalsIgnoreCase(name) ? src.substring(i, end) : "");
                        children.peek().add(closed);
                        if (open.name().equalsIgnoreCase(name)) {
                            break;
                        }
                    }
                } else {
                    text.append(src, i, end);
                }
                i = end;
                continue;
            }
            // 開始タグ
            int j = i + 1;
            while (j < src.length() && !Character.isWhitespace(src.charAt(j)) && src.charAt(j) != '>' && src.charAt(j) != '/') {
                j++;
            }
            String name = src.substring(i + 1, j);
            if (name.isEmpty()) {
                text.append(c);
                i++;
                continue;
            }
            List<Attr> attrs = new ArrayList<>();
            while (true) {
                int wsStart = j;
                while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
                    j++;
                }
                String ws = src.substring(wsStart, j);
                if (j >= src.length() || src.charAt(j) == '>' || src.startsWith("/>", j)) {
                    String tagEnd = ws + (src.startsWith("/>", j) ? "/>" : ">");
                    j += src.startsWith("/>", j) ? 2 : 1;
                    flush(text, children.peek());
                    boolean selfClosed = tagEnd.endsWith("/>") || VOID.contains(name.toLowerCase(Locale.ROOT));
                    if (selfClosed) {
                        children.peek().add(new Element(name, attrs, tagEnd, List.of(), null));
                    } else if (name.equalsIgnoreCase("script") || name.equalsIgnoreCase("style")) {
                        int close = src.toLowerCase(Locale.ROOT).indexOf("</" + name.toLowerCase(Locale.ROOT), j);
                        close = close < 0 ? src.length() : close;
                        int closeEnd = src.indexOf('>', close);
                        closeEnd = closeEnd < 0 ? src.length() : closeEnd + 1;
                        children.peek().add(new Element(name, attrs, tagEnd, List.of(new Text(src.substring(j, close))),
                                src.substring(close, closeEnd)));
                        j = closeEnd;
                    } else {
                        stack.push(new Element(name, attrs, tagEnd, List.of(), null));
                        children.push(new ArrayList<>());
                    }
                    i = j;
                    break;
                }
                int nameStart = j;
                while (j < src.length() && !Character.isWhitespace(src.charAt(j)) && "=>".indexOf(src.charAt(j)) < 0
                        && !src.startsWith("/>", j)) {
                    j++;
                }
                String attrName = src.substring(nameStart, j);
                int k = j;
                while (k < src.length() && Character.isWhitespace(src.charAt(k))) {
                    k++;
                }
                if (k < src.length() && src.charAt(k) == '=') {
                    k++;
                    while (k < src.length() && Character.isWhitespace(src.charAt(k))) {
                        k++;
                    }
                    int valueStart = k;
                    String value;
                    if (k < src.length() && (src.charAt(k) == '"' || src.charAt(k) == '\'')) {
                        char q = src.charAt(k);
                        int end = src.indexOf(q, k + 1);
                        end = end < 0 ? src.length() - 1 : end;
                        value = src.substring(k + 1, end);
                        k = end + 1;
                    } else {
                        while (k < src.length() && !Character.isWhitespace(src.charAt(k)) && src.charAt(k) != '>') {
                            k++;
                        }
                        value = src.substring(valueStart, k);
                    }
                    attrs.add(new Attr(ws, attrName, src.substring(valueStart, k), unescape(value)));
                    j = k;
                } else {
                    attrs.add(new Attr(ws, attrName, null, null));
                }
            }
        }
        flush(text, children.peek());
        while (!stack.isEmpty()) {
            Element open = stack.pop();
            List<Node> kids = children.pop();
            children.peek().add(new Element(open.name(), open.attrs(), open.tagEnd(), kids, ""));
        }
        return root;
    }

    private static void flush(StringBuilder text, List<Node> out) {
        if (!text.isEmpty()) {
            out.add(new Text(text.toString()));
            text.setLength(0);
        }
    }

    static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** テキストとして出力する文字列の HTML エスケープ（Thymeleaf と同じ文字）。 */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
