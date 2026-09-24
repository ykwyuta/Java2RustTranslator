package io.github.ykwyuta.j2r.spring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * MyBatis の Mapper XML（{@code <mapper namespace="...">}）を読み、文（select / insert / update / delete）を
 * {@link SqlNode} の木にする。{@code <include>} はこの段階で展開する。
 */
final class MyBatisXml {
    private MyBatisXml() {}

    /** SQL の木。 */
    sealed interface SqlNode {}

    /** SQL の断片（{@code #{...}} を含まない）。 */
    record Text(String sql) implements SqlNode {}

    /** {@code #{expr}}（{@code , jdbcType=...} などのオプションは除く）。 */
    record Bind(String expr) implements SqlNode {}

    /** {@code <if test="...">}。 */
    record If(String test, List<SqlNode> body) implements SqlNode {}

    /** {@code <where>}・{@code <set>}。 */
    record Clause(boolean where, List<SqlNode> body) implements SqlNode {}

    /** {@code <choose>}（otherwise は null 可）。 */
    record Choose(List<If> whens, List<SqlNode> otherwise) implements SqlNode {}

    /** 変換できない要素（{@code <foreach>}・{@code ${...}} など）。 */
    record Unsupported(String description) implements SqlNode {}

    enum Kind { SELECT, INSERT, UPDATE, DELETE }

    /**
     * 1 つの文。
     *
     * @param keyProperty useGeneratedKeys="true" のときの keyProperty（なければ null）
     * @param keyColumn   keyColumn（なければ keyProperty と同じ列名）
     */
    record Statement(String id, Kind kind, List<SqlNode> body, String keyProperty, String keyColumn, String resultType,
                     String resultMap, String source) {
        boolean isDynamic() {
            return body.stream().anyMatch(n -> !(n instanceof Text) && !(n instanceof Bind));
        }
    }

    /** 1 つの Mapper XML。 */
    record Mapper(String namespace, Path file, Map<String, Statement> statements) {}

    /** ディレクトリ配下の *.xml のうち、MyBatis の Mapper XML を namespace → Mapper で返す。 */
    static Map<String, Mapper> scan(List<Path> dirs) throws IOException {
        Map<String, Mapper> out = new HashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (var walk = Files.walk(dir)) {
                files = walk.filter(p -> p.toString().endsWith(".xml") && Files.isRegularFile(p)).sorted().toList();
            }
            for (Path f : files) {
                Mapper m = parse(f);
                if (m != null) {
                    out.put(m.namespace(), m);
                }
            }
        }
        return out;
    }

    static Mapper parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Document doc = builder().parse(in);
            Element root = doc.getDocumentElement();
            if (!root.getTagName().equals("mapper") || root.getAttribute("namespace").isEmpty()) {
                return null;
            }
            Map<String, Element> fragments = new HashMap<>();
            for (Element e : children(root)) {
                if (e.getTagName().equals("sql")) {
                    fragments.put(e.getAttribute("id"), e);
                }
            }
            Map<String, Statement> statements = new HashMap<>();
            for (Element e : children(root)) {
                Kind kind = switch (e.getTagName()) {
                    case "select" -> Kind.SELECT;
                    case "insert" -> Kind.INSERT;
                    case "update" -> Kind.UPDATE;
                    case "delete" -> Kind.DELETE;
                    default -> null;
                };
                if (kind == null) {
                    continue;
                }
                String keyProperty = "true".equals(e.getAttribute("useGeneratedKeys")) && !e.getAttribute("keyProperty").isEmpty()
                        ? e.getAttribute("keyProperty") : null;
                String keyColumn = e.getAttribute("keyColumn").isEmpty() ? keyProperty : e.getAttribute("keyColumn");
                statements.put(e.getAttribute("id"), new Statement(e.getAttribute("id"), kind, nodes(e, fragments),
                        keyProperty, keyColumn, emptyToNull(e.getAttribute("resultType")), emptyToNull(e.getAttribute("resultMap")),
                        "<" + e.getTagName() + " id=\"" + e.getAttribute("id") + "\">"));
            }
            return new Mapper(root.getAttribute("namespace"), file, statements);
        } catch (SAXException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    /** アノテーション（{@code @Select} など）に書いた SQL の文。 */
    static Statement annotated(String id, Kind kind, String sql, String keyProperty, String keyColumn) {
        List<SqlNode> nodes = new ArrayList<>();
        text(sql, nodes);
        return new Statement(id, kind, nodes, keyProperty, keyColumn == null ? keyProperty : keyColumn, null, null,
                "@" + kind.name().charAt(0) + kind.name().substring(1).toLowerCase(Locale.ROOT));
    }

    private static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setValidating(false);
            f.setNamespaceAware(false);
            // mybatis-3-mapper.dtd をネットワークから取りに行かない。
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<SqlNode> nodes(Element parent, Map<String, Element> fragments) {
        List<SqlNode> out = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            switch (n.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text(n.getNodeValue(), out);
                case Node.ELEMENT_NODE -> {
                    Element e = (Element) n;
                    switch (e.getTagName()) {
                        case "include" -> {
                            Element frag = fragments.get(e.getAttribute("refid"));
                            if (frag == null) {
                                out.add(new Unsupported("<include refid=\"" + e.getAttribute("refid") + "\"> (unknown fragment)"));
                            } else {
                                out.addAll(nodes(frag, fragments));
                            }
                        }
                        case "if" -> out.add(new If(e.getAttribute("test"), nodes(e, fragments)));
                        case "where" -> out.add(new Clause(true, nodes(e, fragments)));
                        case "set" -> out.add(new Clause(false, nodes(e, fragments)));
                        case "choose" -> {
                            List<If> whens = new ArrayList<>();
                            List<SqlNode> otherwise = null;
                            for (Element c : children(e)) {
                                if (c.getTagName().equals("when")) {
                                    whens.add(new If(c.getAttribute("test"), nodes(c, fragments)));
                                } else if (c.getTagName().equals("otherwise")) {
                                    otherwise = nodes(c, fragments);
                                }
                            }
                            out.add(new Choose(whens, otherwise));
                        }
                        default -> out.add(new Unsupported("<" + e.getTagName() + ">"));
                    }
                }
                default -> { }
            }
        }
        return merge(out);
    }

    private static final Pattern PARAM = Pattern.compile("([#$])\\{([^}]*)}");

    /** SQL の文字列を Text と Bind に分ける。 */
    private static void text(String s, List<SqlNode> out) {
        Matcher m = PARAM.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(new Text(s.substring(last, m.start())));
            }
            String expr = m.group(2).split(",")[0].strip();
            out.add(m.group(1).equals("#") ? new Bind(expr) : new Unsupported("${" + m.group(2) + "} (string substitution)"));
            last = m.end();
        }
        if (last < s.length()) {
            out.add(new Text(s.substring(last)));
        }
    }

    /** 隣り合う Text をまとめ、空白だけの Text を除く。 */
    private static List<SqlNode> merge(List<SqlNode> nodes) {
        List<SqlNode> out = new ArrayList<>();
        for (SqlNode n : nodes) {
            if (n instanceof Text t && !out.isEmpty() && out.get(out.size() - 1) instanceof Text prev) {
                out.set(out.size() - 1, new Text(prev.sql() + t.sql()));
            } else {
                out.add(n);
            }
        }
        out.removeIf(n -> n instanceof Text t && t.sql().isBlank());
        return out;
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList list = e.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
