package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.Annotation;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf のテンプレートを askama のテンプレートに変換する。
 *
 * <ul>
 *   <li>処理しない部分（静的な HTML・属性・空白）は元のまま出力する</li>
 *   <li>{@code th:text} → {@code {{ ... }}}、{@code th:if} → {@code {% if %}}（null になりうる値は {@code if let Some}）、
 *       {@code th:each} → {@code {% for %}}、{@code th:href="@{...}"} → URL、{@code th:field} → id / name / value</li>
 *   <li>フラグメント（{@code th:replace="~{fragments/layout :: head('一覧')}"}）は、引数を束縛して呼び出し元に展開する</li>
 *   <li>属性の出力順と空白は Thymeleaf と同じにする（{@code th:*} の属性はその位置で置き換え、
 *       {@code th:field} などが足す属性は最後に付ける）</li>
 * </ul>
 */
final class TemplateTranslator {
    /** askama の式と、その Rust の型。var は変数・フィールド（Copy な値をメソッドに渡すとき * が要る）。 */
    record TV(String code, RT type, boolean var) {}

    /** 表示用の値（{@code date.format(...)} など。型は問わず {{ }} で出力する）。 */
    static final RT DISPLAY = new RT.Named("Display", List.of(), false, RT.Named.Kind.VALUE, "display");
    static final RT BINDING = new RT.Named("BindingResult", List.of(), false, RT.Named.Kind.VALUE, "BindingResult");

    /** テンプレートのルートの変数（ビューの構造体のフィールド）。 */
    interface Root {
        /** 名前 name の変数（なければ null）。 */
        TV resolve(String name);
    }

    /** 変換結果。 */
    record Result(String text, Set<String> formBindings, boolean usesTruthy, boolean usesPresent, boolean usesMessageArg) {}

    /** {@code <select th:field>} の中（option の th:value と比べて selected を付ける）。 */
    private record SelectField(TV value, RT type) {}

    private record Alias(ThymeleafExpr.Node expr, Scope scope) {}

    private record FormCtx(TV form, String binding, String qname) {}

    private static final class Scope {
        final Scope parent;
        final Map<String, Object> vars = new HashMap<>();
        final Map<String, TV> paths = new HashMap<>();
        FormCtx form;
        SelectField select;

        Scope(Scope parent) {
            this.parent = parent;
            this.form = parent == null ? null : parent.form;
            this.select = parent == null ? null : parent.select;
        }

        Object lookup(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars.containsKey(name)) {
                    return s.vars.get(name);
                }
            }
            return null;
        }

        TV path(String key) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.paths.containsKey(key)) {
                    return s.paths.get(key);
                }
            }
            return null;
        }
    }

    private final SpringTranslator tr;
    private final Path templatesDir;
    private final Map<String, List<ThymeleafHtml.Node>> cache = new HashMap<>();
    private final Diagnostics diags;
    private Root root;
    private String currentFile;
    private final Set<String> formBindings = new LinkedHashSet<>();
    private final Map<String, Integer> fieldIds = new HashMap<>();
    private boolean usesTruthy;
    private boolean usesPresent;
    private boolean usesMessageArg;
    private final Map<String, Integer> names = new HashMap<>();

    TemplateTranslator(SpringTranslator tr, Path templatesDir) {
        this.tr = tr;
        this.templatesDir = templatesDir;
        this.diags = tr.diags();
    }

    boolean exists(String view) {
        return Files.isRegularFile(templatesDir.resolve(view + ".html"));
    }

    Result translate(String view, Root root) {
        this.root = root;
        this.currentFile = view + ".html";
        formBindings.clear();
        fieldIds.clear();
        usesTruthy = false;
        usesPresent = false;
        usesMessageArg = false;
        names.clear();
        String text = nodes(load(view), new Scope(null));
        return new Result(text, Set.copyOf(formBindings), usesTruthy, usesPresent, usesMessageArg);
    }

    private List<ThymeleafHtml.Node> load(String view) {
        return cache.computeIfAbsent(view, v -> {
            try {
                return ThymeleafHtml.parse(Files.readString(templatesDir.resolve(v + ".html"), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ================================================================== 要素

    private String nodes(List<ThymeleafHtml.Node> nodes, Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (ThymeleafHtml.Node n : nodes) {
            switch (n) {
                case ThymeleafHtml.Text t -> sb.append(staticText(t.raw()));
                case ThymeleafHtml.Element e -> sb.append(element(e, scope));
            }
        }
        return sb.toString();
    }

    /** 属性の処理順（Thymeleaf の標準方言の優先順位）。 */
    private static int precedence(String name) {
        return switch (name) {
            case "th:insert", "th:replace" -> 100;
            case "th:each" -> 200;
            case "th:if" -> 300;
            case "th:unless" -> 400;
            case "th:object" -> 500;
            case "th:with" -> 600;
            case "th:classappend", "th:styleappend" -> 1100;
            case "th:text" -> 1300;
            case "th:utext" -> 1400;
            case "th:fragment" -> 1500;
            case "th:remove" -> 1600;
            case "th:field", "th:errors" -> 1700;
            case "th:errorclass" -> 1800;
            case "xmlns:th" -> 10000;
            default -> 1000;
        };
    }

    private static boolean isTh(String name) {
        return name.startsWith("th:") || name.equals("xmlns:th");
    }

    private String element(ThymeleafHtml.Element e, Scope scope) {
        ThymeleafHtml.Attr replace = e.has("th:replace") ? e.attr("th:replace") : e.attr("th:insert");
        if (replace != null) {
            return fragment(e, replace, scope);
        }
        ThymeleafHtml.Attr each = e.attr("th:each");
        if (each != null) {
            return each(e, each, scope);
        }
        return conditional(e, scope);
    }

    private String each(ThymeleafHtml.Element e, ThymeleafHtml.Attr each, Scope scope) {
        String spec = each.value();
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return unsupported("th:each=\"" + spec + "\"");
        }
        String[] names = spec.substring(0, colon).split(",");
        String var = names[0].strip();
        String stat = names.length > 1 ? names[1].strip() : null;
        TV iterable = value(parse(spec.substring(colon + 1)), scope);
        RT elem = switch (iterable.type()) {
            case RT.VecT v -> v.elem().copy() ? v.elem() : new RT.Ref(v.elem(), false);
            case RT.Slice v -> v.elem().copy() ? v.elem() : new RT.Ref(v.elem(), false);
            default -> null;
        };
        if (elem == null) {
            return unsupported("th:each over " + iterable.code());
        }
        Scope inner = new Scope(scope);
        String rust = fresh(Naming.valueName(var));
        inner.vars.put(var, new TV(rust, elem, true));
        if (stat != null) {
            inner.vars.put(stat, new TV("loop", new RT.Named("LoopStatus", List.of(), true, RT.Named.Kind.VALUE, "stat"), true));
        }
        ThymeleafHtml.Element rest = without(e, "th:each");
        return "{% for " + rust + " in " + iterable.code() + " %}" + conditional(rest, inner) + "{% endfor %}";
    }

    private String conditional(ThymeleafHtml.Element e, Scope scope) {
        ThymeleafHtml.Attr ifAttr = e.attr("th:if");
        ThymeleafHtml.Attr unless = e.attr("th:unless");
        if (ifAttr == null && unless == null) {
            return tag(e, scope);
        }
        ThymeleafHtml.Element rest = without(without(e, "th:if"), "th:unless");
        Scope inner = new Scope(scope);
        String open;
        if (ifAttr != null) {
            ThymeleafExpr.Node cond = parse(ifAttr.value());
            String key = pathKey(cond);
            TV v = value(cond, scope);
            String binding = key != null && v.type() instanceof RT.Opt && unless == null
                    ? fresh(Naming.valueName(key.substring(key.lastIndexOf('.') + 1))) : null;
            if (binding != null) {
                // null になりうる値の th:if は if let で中身を束縛し、中の同じ参照をその変数にする。
                inner.paths.put(key, new TV(binding, ((RT.Opt) v.type()).inner(), true));
                String body = tag(rest, inner);
                if (usesVar(body, binding)) {
                    return "{% if let Some(" + binding + ") = " + v.code() + " %}" + body + "{% endif %}";
                }
                return "{% if " + cond(cond, scope) + " %}" + body + "{% endif %}";
            }
            open = "{% if " + cond(cond, scope) + " %}";
            if (unless != null) {
                open += "{% if !(" + cond(parse(unless.value()), inner) + ") %}";
                return open + tag(rest, inner) + "{% endif %}{% endif %}";
            }
        } else {
            open = "{% if !(" + cond(parse(unless.value()), scope) + ") %}";
        }
        return open + tag(rest, inner) + "{% endif %}";
    }

    private static ThymeleafHtml.Element without(ThymeleafHtml.Element e, String attr) {
        if (!e.has(attr)) {
            return e;
        }
        List<ThymeleafHtml.Attr> attrs = new ArrayList<>(e.attrs());
        int i = -1;
        for (int k = 0; k < attrs.size(); k++) {
            if (attrs.get(k).name().equals(attr)) {
                i = k;
            }
        }
        removeAttr(attrs, i);
        return new ThymeleafHtml.Element(e.name(), attrs, e.tagEnd(), e.children(), e.endTag());
    }

    /** 属性を消す。後ろに属性があればその属性が消した属性の前の空白を引き継ぎ、なければ前の空白ごと消す（Thymeleaf と同じ）。 */
    private static void removeAttr(List<ThymeleafHtml.Attr> attrs, int i) {
        ThymeleafHtml.Attr removed = attrs.remove(i);
        if (i < attrs.size()) {
            ThymeleafHtml.Attr next = attrs.get(i);
            attrs.set(i, new ThymeleafHtml.Attr(removed.ws(), next.name(), next.raw(), next.value()));
        }
    }

    /** 出力する属性（生成したものは text に askama の構文を含む）。 */
    private record OutAttr(String ws, String name, String text) {}

    private String tag(ThymeleafHtml.Element e, Scope scope) {
        if (e.has("th:object")) {
            scope = new Scope(scope);
            TV form = value(parse(e.attr("th:object").value()), scope);
            RT ft = form.type() instanceof RT.Ref r ? r.inner() : form.type();
            if (ft instanceof RT.Named n && form.var()) {
                scope.form = new FormCtx(form, form.code() + "_binding", n.javaName());
                formBindings.add(form.code());
            } else {
                diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "th:object must be a model attribute: " + e.attr("th:object").value());
            }
        }
        // 属性を優先順位の順に処理する。
        List<OutAttr> attrs = new ArrayList<>();
        for (ThymeleafHtml.Attr a : e.attrs()) {
            attrs.add(new OutAttr(a.ws(), a.name(), isTh(a.name()) ? null : staticText(a.text().substring(a.ws().length()))));
        }
        List<ThymeleafHtml.Attr> ordered = e.attrs().stream().filter(a -> isTh(a.name()))
                .sorted(java.util.Comparator.comparingInt(a -> precedence(a.name()))).toList();
        String content = null;
        String after = "";
        boolean block = e.name().equals("th:block");
        for (ThymeleafHtml.Attr a : ordered) {
            int i = indexOf(attrs, a.name());
            String name = a.name();
            switch (name) {
                case "th:text", "th:utext" -> {
                    content = output(parse(a.value()), scope, name.equals("th:utext"));
                    remove(attrs, i);
                }
                case "th:classappend", "th:errorclass" -> {
                    String appended = name.equals("th:classappend") ? appended(parse(a.value()), scope)
                            : errorClass(a.value(), e, scope);
                    remove(attrs, i);
                    appendClass(attrs, appended);
                }
                case "th:field" -> {
                    remove(attrs, i);
                    String[] result = field(e, a.value(), attrs, scope);
                    if (result[0] != null) {
                        content = result[0];
                    }
                    after = result[1];
                    if (e.name().equalsIgnoreCase("select") && scope.form != null) {
                        // 中の option の th:value と、フォームのプロパティの値を比べる。
                        String prop = a.value().replaceAll("^\\*\\{\\s*|\\s*}$", "");
                        RT type = tr.types().field(scope.form.qname(), prop);
                        if (type != null) {
                            scope = new Scope(scope);
                            scope.select = new SelectField(new TV(scope.form.form().code() + "." + Naming.valueName(prop), type, true), type);
                        }
                    }
                }
                case "th:errors" -> {
                    remove(attrs, i);
                    content = errors(a.value(), scope);
                }
                case "th:object", "th:fragment", "xmlns:th", "th:remove", "th:inline" -> remove(attrs, i);
                case "th:with" -> {
                    remove(attrs, i);
                    diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "th:with is not supported yet");
                }
                default -> {
                    String attrName = name.substring("th:".length());
                    attrs.set(i, new OutAttr(attrs.get(i).ws(), attrName, attribute(attrs.get(i).ws(), attrName, parse(a.value()), scope)));
                }
            }
        }
        if (e.name().equalsIgnoreCase("option") && scope.select != null && e.has("th:value")) {
            attrs.add(new OutAttr(" ", "selected", selected(scope.select, parse(e.attr("th:value").value()), scope)));
            scope = new Scope(scope);
            scope.select = null;
        }
        String children = content != null ? content : nodes(e.children(), scope);
        if (block) {
            return children + after;
        }
        StringBuilder sb = new StringBuilder("<").append(e.name());
        for (OutAttr a : attrs) {
            if (a.text() == null) {
                continue;
            }
            // 生成した属性（ws 込みで条件付きの場合がある）
            sb.append(a.text().startsWith("{%") || a.text().startsWith(" ") ? a.text() : a.ws() + a.text());
        }
        sb.append(e.tagEnd());
        if (e.endTag() != null) {
            sb.append(children).append(e.endTag());
        }
        return sb.append(after).toString();
    }

    private static int indexOf(List<OutAttr> attrs, String name) {
        for (int i = 0; i < attrs.size(); i++) {
            if (attrs.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException(name);
    }

    private static void remove(List<OutAttr> attrs, int i) {
        OutAttr removed = attrs.remove(i);
        if (i < attrs.size()) {
            OutAttr next = attrs.get(i);
            attrs.set(i, new OutAttr(removed.ws(), next.name(), next.text()));
        }
    }

    /** 属性 name="value"。値が null になりうる（else のない条件）なら属性ごと条件付きにする。 */
    private String attribute(String ws, String name, ThymeleafExpr.Node expr, Scope scope) {
        boolean bool = Set.of("checked", "selected", "disabled", "readonly", "required", "multiple").contains(name);
        if (bool) {
            return "{% if " + cond(expr, scope) + " %}" + ws + name + "=\"" + name + "\"{% endif %}";
        }
        if (expr instanceof ThymeleafExpr.Cond c && c.whenFalse() != null) {
            String narrowed = narrowed(c, scope, (branch, s) -> output(branch, s, false));
            if (narrowed != null) {
                return name + "=\"" + narrowed + "\"";
            }
        }
        if (expr instanceof ThymeleafExpr.Cond c && c.whenFalse() == null) {
            return "{% if " + cond(c.condition(), scope) + " %}" + ws + name + "=\"" + output(c.whenTrue(), scope, false) + "\"{% endif %}";
        }
        return name + "=\"" + output(expr, scope, false) + "\"";
    }

    /** th:classappend の値（空でなければ前に空白を付ける）。 */
    private String appended(ThymeleafExpr.Node expr, Scope scope) {
        return switch (expr) {
            case ThymeleafExpr.Cond c -> "{% if " + cond(c.condition(), scope) + " %}" + appended(c.whenTrue(), scope)
                    + (c.whenFalse() == null ? "" : "{% else %}" + appended(c.whenFalse(), scope)) + "{% endif %}";
            case ThymeleafExpr.Str s -> s.value().isEmpty() ? "" : " " + ThymeleafHtml.escape(s.value());
            case ThymeleafExpr.Null n -> "";
            default -> " " + output(expr, scope, false);
        };
    }

    /** class 属性に追記する（なければ最後に class 属性を足す）。 */
    private void appendClass(List<OutAttr> attrs, String appended) {
        if (appended.isEmpty()) {
            return;
        }
        for (int i = 0; i < attrs.size(); i++) {
            OutAttr a = attrs.get(i);
            if (a.name().equals("class") && a.text() != null) {
                String text = a.text();
                int close = text.lastIndexOf('"');
                attrs.set(i, new OutAttr(a.ws(), a.name(), text.substring(0, close) + appended + text.substring(close)));
                return;
            }
        }
        // class がなければ、値が空でないときだけ class 属性を付ける。
        attrs.add(new OutAttr(" ", "class", classAttr(appended)));
    }

    /** 追記する値（先頭に空白）から class 属性を作る。条件付きの値は条件ごとに属性を出す。 */
    private static String classAttr(String appended) {
        if (appended.startsWith("{% if ")) {
            // {% if c %} x{% else %} y{% endif %} → {% if c %} class="x"{% else %} class="y"{% endif %}
            return appended.replaceAll("%\\} ([^{]+?)\\{%", "%} class=\"$1\"{%");
        }
        return " class=\"" + appended.substring(1) + "\"";
    }

    // ================================================================== フラグメント

    private String fragment(ThymeleafHtml.Element host, ThymeleafHtml.Attr attr, Scope scope) {
        ThymeleafExpr.Fragment f;
        try {
            f = ThymeleafExpr.parseFragment(attr.value());
        } catch (ThymeleafExpr.ParseException ex) {
            return unsupported(ex.getMessage());
        }
        if (!exists(f.template())) {
            return unsupported("fragment template " + f.template());
        }
        ThymeleafHtml.Element frag = findFragment(load(f.template()), f.selector());
        if (frag == null) {
            return unsupported("fragment " + f.template() + " :: " + f.selector());
        }
        String decl = frag.attr("th:fragment").value();
        List<String> params = new ArrayList<>();
        int paren = decl.indexOf('(');
        if (paren >= 0) {
            for (String p : decl.substring(paren + 1, decl.lastIndexOf(')')).split(",")) {
                if (!p.isBlank()) {
                    params.add(p.strip());
                }
            }
        }
        Scope inner = new Scope(scope);
        List<ThymeleafExpr.Node> args = f.args() == null ? List.of() : f.args();
        for (int i = 0; i < params.size(); i++) {
            inner.vars.put(params.get(i), i < args.size() ? new Alias(args.get(i), scope) : new TV("None", new RT.Null(), false));
        }
        String saved = currentFile;
        currentFile = f.template() + ".html";
        String body = element(frag, inner);
        currentFile = saved;
        if (host.has("th:insert")) {
            ThymeleafHtml.Element h = without(host, "th:insert");
            return tag(new ThymeleafHtml.Element(h.name(), h.attrs(), h.tagEnd(), List.of(new ThymeleafHtml.Text(RAW_MARK + body)),
                    h.endTag()), scope);
        }
        return body;
    }

    /** 変換済みの文字列を子として渡す目印（staticText でエスケープしない）。 */
    private static final String RAW_MARK = "\u0000raw:";

    private static ThymeleafHtml.Element findFragment(List<ThymeleafHtml.Node> nodes, String name) {
        for (ThymeleafHtml.Node n : nodes) {
            if (n instanceof ThymeleafHtml.Element e) {
                ThymeleafHtml.Attr a = e.attr("th:fragment");
                if (a != null) {
                    String decl = a.value().strip();
                    String declName = decl.contains("(") ? decl.substring(0, decl.indexOf('(')).strip() : decl;
                    if (declName.equals(name)) {
                        return e;
                    }
                }
                ThymeleafHtml.Element found = findFragment(e.children(), name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ================================================================== フォーム（th:field・th:errors）

    /** th:field。結果は [子の内容（textarea）, 要素の後ろに足す文字列（checkbox の隠しフィールド）]。 */
    private String[] field(ThymeleafHtml.Element e, String spec, List<OutAttr> attrs, Scope scope) {
        FormCtx form = scope.form;
        ThymeleafExpr.Node node = parse(spec);
        if (form == null || !(node instanceof ThymeleafExpr.Prop p) || !(p.target() instanceof ThymeleafExpr.SelectionRoot)) {
            return new String[] {unsupported("th:field=\"" + spec + "\""), ""};
        }
        String prop = p.name();
        RT type = tr.types().field(form.qname(), prop);
        if (type == null) {
            return new String[] {unsupported("form property " + prop), ""};
        }
        String rust = form.form().code() + "." + Naming.valueName(prop);
        String input = e.name().toLowerCase(Locale.ROOT);
        String kind = e.attr("type") == null ? "text" : e.attr("type").value().toLowerCase(Locale.ROOT);
        if (input.equals("select")) {
            if (!hasAttr(attrs, "id")) {
                attrs.add(new OutAttr(" ", "id", " id=\"" + prop + "\""));
            }
            attrs.add(new OutAttr(" ", "name", " name=\"" + prop + "\""));
            return new String[] {null, ""};
        }
        if (input.equals("input") && kind.equals("checkbox")) {
            int n = fieldIds.merge(prop, 1, Integer::sum);
            if (!hasAttr(attrs, "id")) {
                attrs.add(new OutAttr(" ", "id", " id=\"" + prop + n + "\""));
            }
            attrs.add(new OutAttr(" ", "name", " name=\"" + prop + "\""));
            if (!hasAttr(attrs, "value")) {
                attrs.add(new OutAttr(" ", "value", " value=\"true\""));
            }
            String checked = type instanceof RT.Opt ? rust + " == Some(true)" : rust;
            attrs.add(new OutAttr(" ", "checked", "{% if " + checked + " %} checked=\"checked\"{% endif %}"));
            return new String[] {null, "<input type=\"hidden\" name=\"_" + prop + "\" value=\"on\"/>"};
        }
        if (!hasAttr(attrs, "id")) {
            attrs.add(new OutAttr(" ", "id", " id=\"" + prop + "\""));
        }
        attrs.add(new OutAttr(" ", "name", " name=\"" + prop + "\""));
        String value = fieldValue(form, prop, rust, type);
        if (input.equals("textarea")) {
            return new String[] {value, ""};
        }
        attrs.add(new OutAttr(" ", "value", " value=\"" + value + "\""));
        return new String[] {null, ""};
    }

    /**
     * {@code <select th:field>} の中の option の selected（Spring の SelectedValueComparator と同じく、プロパティの値と th:value を
     * 比べる。enum は名前で比べる）。
     */
    private String selected(SelectField select, ThymeleafExpr.Node optionValue, Scope scope) {
        TV option = value(optionValue, scope);
        RT fieldType = RT.unwrapOpt(select.type());
        String v = select.type() instanceof RT.Opt ? fresh("selected") : select.value().code();
        boolean fieldEnum = fieldType instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM;
        RT optionType = option.type() instanceof RT.Ref r ? r.inner() : option.type();
        boolean optionEnum = optionType instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM;
        String left = fieldEnum ? v + ".name()" : v + ".to_string()";
        String right = optionEnum ? option.code() + ".name()" : option.type() instanceof RT.StrRef && !option.var() ? option.code()
                : option.code() + ".to_string()";
        String test = "{% if " + left + " == " + right + " %} selected=\"selected\"{% endif %}";
        return select.type() instanceof RT.Opt ? "{% if let Some(" + v + ") = " + select.value().code() + " %}" + test + "{% endif %}" : test;
    }

    private static boolean hasAttr(List<OutAttr> attrs, String name) {
        return attrs.stream().anyMatch(a -> a.name().equals(name) && a.text() != null);
    }

    /** フォームの値の表示（型変換に失敗した値があればそれ、なければ @DateTimeFormat などで書式化した値）。 */
    private String fieldValue(FormCtx form, String prop, String rust, RT type) {
        RT inner = RT.unwrapOpt(type);
        String var = type instanceof RT.Opt ? fresh("value") : rust;
        String display = "{{ " + var + " }}";
        if (inner instanceof RT.Named n && n.kind() == RT.Named.Kind.VALUE) {
            display = "{{ " + var + ".format(" + BodyLowerer.rustString(chronoPattern(datePattern(form.qname(), prop, n))) + ") }}";
        }
        String formatted = type instanceof RT.Opt ? "{% if let Some(" + var + ") = " + rust + " %}" + display + "{% endif %}" : display;
        if (type instanceof RT.Str || type instanceof RT.Opt o && o.inner() instanceof RT.Str) {
            return formatted;
        }
        String rejected = fresh("rejected");
        return "{% if let Some(" + rejected + ") = " + form.binding() + ".rejected_value(\"" + prop + "\") %}{{ " + rejected + " }}{% else %}"
                + formatted + "{% endif %}";
    }

    /** {@code @DateTimeFormat} の書式（なければ ISO）。 */
    private String datePattern(String owner, String prop, RT.Named type) {
        Annotation a = tr.model().info(DeclInfo.fieldKey(owner, prop)).get("org.springframework.format.annotation.DateTimeFormat");
        if (a != null && a.stringValue("pattern") != null) {
            return a.stringValue("pattern");
        }
        return type.name().equals("NaiveDateTime") ? "yyyy-MM-dd'T'HH:mm:ss" : type.name().equals("NaiveTime") ? "HH:mm:ss" : "yyyy-MM-dd";
    }

    private String errorClass(String cls, ThymeleafHtml.Element e, Scope scope) {
        ThymeleafHtml.Attr field = e.attr("th:field");
        if (scope.form == null || field == null) {
            return unsupported("th:errorclass without th:field");
        }
        String prop = field.value().replaceAll("^\\*\\{\\s*|\\s*}$", "");
        return "{% if " + scope.form.binding() + ".has_field_errors(\"" + prop + "\") %} " + ThymeleafHtml.escape(cls) + "{% endif %}";
    }

    private String errors(String spec, Scope scope) {
        String prop = spec.replaceAll("^\\*\\{\\s*|\\s*}$", "");
        if (scope.form == null) {
            return unsupported("th:errors outside th:object");
        }
        return "{% for e in " + scope.form.binding() + ".field_errors(\"" + prop + "\") %}{% if !loop.first %}<br />{% endif %}{{ e }}{% endfor %}";
    }

    // ================================================================== 式

    private ThymeleafExpr.Node parse(String src) {
        try {
            return ThymeleafExpr.parse(src);
        } catch (ThymeleafExpr.ParseException e) {
            diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), e.getMessage());
            return new ThymeleafExpr.Null();
        }
    }

    /** 変数・プロパティの参照のキー（th:if で束縛した参照を置き換えるため）。 */
    private static String pathKey(ThymeleafExpr.Node n) {
        return switch (n) {
            case ThymeleafExpr.Var v -> v.name();
            case ThymeleafExpr.SelectionRoot s -> "*";
            case ThymeleafExpr.Prop p -> {
                String t = pathKey(p.target());
                yield t == null ? null : t + "." + p.name();
            }
            default -> null;
        };
    }

    /** 値としての式。 */
    TV value(ThymeleafExpr.Node n, Scope scope) {
        String key = pathKey(n);
        if (key != null) {
            TV narrowed = scope.path(key);
            if (narrowed != null) {
                return narrowed;
            }
        }
        return switch (n) {
            case ThymeleafExpr.Str s -> new TV(BodyLowerer.rustString(s.value()), RT.STR_REF, false);
            case ThymeleafExpr.Num x -> new TV(x.text(), x.text().contains(".") ? new RT.Prim("f64") : RT.I64, false);
            case ThymeleafExpr.Bool b -> new TV(Boolean.toString(b.value()), RT.BOOL, false);
            case ThymeleafExpr.Null x -> new TV("None", new RT.Null(), false);
            case ThymeleafExpr.Var v -> variable(v.name(), scope);
            case ThymeleafExpr.SelectionRoot s -> scope.form == null ? unknown("*{...} outside th:object") : scope.form.form();
            case ThymeleafExpr.Prop p -> property(value(p.target(), scope), p.name());
            case ThymeleafExpr.Call c -> call(value(c.target(), scope), c.name(), c.args(), scope);
            case ThymeleafExpr.Util u -> util(u, scope);
            case ThymeleafExpr.Unary u when u.op().equals("!") -> new TV("!(" + cond(u.operand(), scope) + ")", RT.BOOL, false);
            case ThymeleafExpr.Unary u -> {
                TV v = value(u.operand(), scope);
                yield new TV("-" + v.code(), v.type(), false);
            }
            case ThymeleafExpr.Binary b -> binary(b, scope);
            default -> unknown(n.getClass().getSimpleName() + " as a value");
        };
    }

    private TV variable(String name, Scope scope) {
        Object v = scope.lookup(name);
        if (v instanceof TV tv) {
            return tv;
        }
        if (v instanceof Alias a) {
            return value(a.expr(), a.scope());
        }
        TV r = root.resolve(name);
        if (r == null) {
            diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "unknown template variable '" + name + "' (treated as null)");
            return new TV("None", new RT.Null(), false);
        }
        return r;
    }

    private TV binary(ThymeleafExpr.Binary b, Scope scope) {
        switch (b.op()) {
            case "&&", "||" -> {
                return new TV("(" + cond(b.left(), scope) + " " + b.op() + " " + cond(b.right(), scope) + ")", RT.BOOL, false);
            }
            case "==", "!=" -> {
                boolean eq = b.op().equals("==");
                if (b.right() instanceof ThymeleafExpr.Null || b.left() instanceof ThymeleafExpr.Null) {
                    TV v = value(b.right() instanceof ThymeleafExpr.Null ? b.left() : b.right(), scope);
                    if (v.type() instanceof RT.Opt || v.type() instanceof RT.Null) {
                        return new TV(v.code() + (eq ? ".is_none()" : ".is_some()"), RT.BOOL, false);
                    }
                    return new TV(eq ? "false" : "true", RT.BOOL, false);
                }
                TV l = value(b.left(), scope);
                TV r = value(b.right(), scope);
                if (l.type() instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM && r.type() instanceof RT.StrRef) {
                    return new TV(l.code() + ".name() " + b.op() + " " + r.code(), RT.BOOL, false);
                }
                if (l.type() instanceof RT.Opt && !(r.type() instanceof RT.Opt)) {
                    return new TV(l.code() + " " + b.op() + " Some(" + r.code() + ")", RT.BOOL, false);
                }
                return new TV(l.code() + " " + b.op() + " " + r.code(), RT.BOOL, false);
            }
            default -> {
                TV l = value(b.left(), scope);
                TV r = value(b.right(), scope);
                boolean cmp = Set.of("<", ">", "<=", ">=").contains(b.op());
                return new TV((cmp ? "" : "(") + l.code() + " " + b.op() + " " + r.code() + (cmp ? "" : ")"), cmp ? RT.BOOL : l.type(), false);
            }
        }
    }

    /** 条件としての式（Thymeleaf の真偽値の評価）。 */
    String cond(ThymeleafExpr.Node n, Scope scope) {
        TV v = value(n, scope);
        RT t = v.type();
        if (t.equals(RT.BOOL)) {
            return v.code();
        }
        if (t instanceof RT.Null) {
            return "false";
        }
        RT inner = RT.unwrapOpt(t instanceof RT.Ref r ? r.inner() : t);
        boolean truthy = inner instanceof RT.Prim || inner instanceof RT.Str || inner instanceof RT.StrRef;
        if (truthy) {
            usesTruthy = true;
            return v.code() + ".truthy()";
        }
        return t instanceof RT.Opt ? v.code() + ".is_some()" : "true";
    }

    private TV property(TV target, String prop) {
        RT t = target.type() instanceof RT.Ref r ? r.inner() : target.type();
        if (t instanceof RT.Named n && n.javaName().equals("stat")) {
            return switch (prop) {
                case "index" -> new TV("loop.index0", RT.I64, false);
                case "count" -> new TV("loop.index", RT.I64, false);
                case "first" -> new TV("loop.first", RT.BOOL, false);
                case "last" -> new TV("loop.last", RT.BOOL, false);
                case "even" -> new TV("(loop.index % 2 == 0)", RT.BOOL, false);
                case "odd" -> new TV("(loop.index % 2 == 1)", RT.BOOL, false);
                default -> unknown("iteration status ." + prop);
            };
        }
        if (!(t instanceof RT.Named n) || tr.model().types.get(n.javaName()) == null) {
            return unknown("property '" + prop + "' of " + target.code());
        }
        Decl.TypeDecl d = tr.model().types.get(n.javaName());
        String cap = Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (Decl.MethodDecl m : d.methods()) {
            if (m.isStatic() || m.isConstructor() || !m.params().isEmpty()) {
                continue;
            }
            boolean match = m.name().equals("get" + cap) || m.name().equals("is" + cap)
                    || (d.kind() == Decl.TypeKind.RECORD || m.name().equals(prop)) && m.name().equals(prop);
            if (match) {
                return invoke(target, m, List.of(), null);
            }
        }
        RT field = tr.types().field(n.javaName(), prop);
        if (field != null) {
            return new TV(target.code() + "." + Naming.valueName(prop), field, true);
        }
        return unknown("property '" + prop + "' of " + n.name());
    }

    private TV invoke(TV target, Decl.MethodDecl m, List<ThymeleafExpr.Node> args, Scope scope) {
        String getter = tr.plans().getters.get(m.ref().key());
        if (getter != null) {
            return new TV(target.code() + "." + Naming.valueName(getter), tr.types().field(m.ref().owner(), getter), true);
        }
        Plans.Method plan = tr.plans().methods.get(m.ref().key());
        if (plan == null || plan.needsConn()) {
            return unknown("method " + m.name());
        }
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            TV a = value(args.get(i), scope);
            RT p = plan.params().get(i);
            codes.add(p.copy() && a.var() && a.type().copy() && !(a.type() instanceof RT.Ref) ? "*" + a.code() : a.code());
        }
        return new TV(target.code() + "." + plan.rustName() + "(" + String.join(", ", codes) + ")", plan.ret(), false);
    }

    private TV call(TV target, String name, List<ThymeleafExpr.Node> args, Scope scope) {
        RT t = target.type() instanceof RT.Ref r ? r.inner() : target.type();
        switch (t) {
            case RT.VecT v -> {
                return listMethod(target, name, args);
            }
            case RT.Slice v -> {
                return listMethod(target, name, args);
            }
            case RT.Str s -> {
                return stringMethod(target, name, args);
            }
            case RT.StrRef s -> {
                return stringMethod(target, name, args);
            }
            case RT.Named n when n.kind() == RT.Named.Kind.ENUM && name.equals("name") -> {
                return new TV(target.code() + ".name()", RT.STR_REF, false);
            }
            case RT.Named n when tr.model().types.get(n.javaName()) != null -> {
                for (Decl.MethodDecl m : tr.model().types.get(n.javaName()).methods()) {
                    if (m.name().equals(name) && m.params().size() == args.size() && !m.isStatic()) {
                        return invoke(target, m, args, scope);
                    }
                }
                return unknown("method " + name + " of " + n.name());
            }
            default -> {
                return unknown("method " + name + " of " + target.code());
            }
        }
    }

    private TV listMethod(TV target, String name, List<ThymeleafExpr.Node> args) {
        return switch (name) {
            case "isEmpty" -> new TV(target.code() + ".is_empty()", RT.BOOL, false);
            case "size" -> new TV(target.code() + ".len()", RT.I64, false);
            default -> unknown("List." + name);
        };
    }

    private TV stringMethod(TV target, String name, List<ThymeleafExpr.Node> args) {
        return switch (name) {
            case "isEmpty" -> new TV(target.code() + ".is_empty()", RT.BOOL, false);
            case "length" -> new TV(target.code() + ".chars().count()", RT.I64, false);
            case "toUpperCase" -> new TV(target.code() + ".to_uppercase()", RT.STR, false);
            case "toLowerCase" -> new TV(target.code() + ".to_lowercase()", RT.STR, false);
            default -> unknown("String." + name);
        };
    }

    private TV util(ThymeleafExpr.Util u, Scope scope) {
        switch (u.object() + "." + u.method()) {
            case "fields.errors", "fields.hasErrors" -> {
                if (scope.form == null || u.args().size() != 1 || !(u.args().get(0) instanceof ThymeleafExpr.Str s)) {
                    return unknown("#fields." + u.method());
                }
                return u.method().equals("errors")
                        ? new TV(scope.form.binding() + ".field_errors(\"" + s.value() + "\")", new RT.VecT(RT.STR_REF), false)
                        : new TV(scope.form.binding() + ".has_field_errors(\"" + s.value() + "\")", RT.BOOL, false);
            }
            case "temporals.format" -> {
                TV date = value(u.args().get(0), scope);
                String pattern = u.args().size() > 1 && u.args().get(1) instanceof ThymeleafExpr.Str s ? s.value() : "yyyy-MM-dd";
                if (date.type() instanceof RT.Opt) {
                    return unknown("#temporals.format of a nullable value outside th:if");
                }
                return new TV(date.code() + ".format(" + BodyLowerer.rustString(chronoPattern(pattern)) + ")", DISPLAY, false);
            }
            case "lists.isEmpty", "strings.isEmpty" -> {
                TV v = value(u.args().get(0), scope);
                return new TV(v.code() + ".is_empty()", RT.BOOL, false);
            }
            default -> {
                return unknown("#" + u.object() + "." + u.method());
            }
        }
    }

    /** Java の日付の書式 → chrono の書式。 */
    static String chronoPattern(String java) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < java.length()) {
            char c = java.charAt(i);
            int n = 1;
            while (i + n < java.length() && java.charAt(i + n) == c) {
                n++;
            }
            String run = java.substring(i, i + n);
            i += n;
            switch (run) {
                case "yyyy", "uuuu" -> sb.append("%Y");
                case "yy", "uu" -> sb.append("%y");
                case "MM" -> sb.append("%m");
                case "M" -> sb.append("%-m");
                case "dd" -> sb.append("%d");
                case "d" -> sb.append("%-d");
                case "HH" -> sb.append("%H");
                case "H" -> sb.append("%-H");
                case "mm" -> sb.append("%M");
                case "ss" -> sb.append("%S");
                case "'" -> {
                    int end = java.indexOf('\'', i);
                    end = end < 0 ? java.length() : end;
                    sb.append(java, i, end);
                    i = end + 1;
                }
                default -> sb.append(run.replace("%", "%%"));
            }
        }
        return sb.toString();
    }

    // ================================================================== 出力（th:text・属性の値）

    /** 式の値をテンプレートの文字列として出力する（null は空文字列）。 */
    String output(ThymeleafExpr.Node n, Scope scope, boolean raw) {
        switch (n) {
            case ThymeleafExpr.Str s -> {
                return staticText(ThymeleafHtml.escape(s.value()));
            }
            case ThymeleafExpr.Num x -> {
                return x.text();
            }
            case ThymeleafExpr.Bool b -> {
                return Boolean.toString(b.value());
            }
            case ThymeleafExpr.Null x -> {
                return "";
            }
            case ThymeleafExpr.Cond c -> {
                String narrowed = narrowed(c, scope, (branch, s) -> output(branch, s, raw));
                if (narrowed != null) {
                    return narrowed;
                }
                return "{% if " + cond(c.condition(), scope) + " %}" + output(c.whenTrue(), scope, raw)
                        + (c.whenFalse() == null ? "" : "{% else %}" + output(c.whenFalse(), scope, raw)) + "{% endif %}";
            }
            case ThymeleafExpr.Elvis e -> {
                TV v = value(e.value(), scope);
                String binding = fresh("value");
                String test = v.type() instanceof RT.Opt o && o.inner() instanceof RT.Str || v.type() instanceof RT.Str
                        ? v.code() + ".present()" : v.code();
                if (test.endsWith(".present()")) {
                    usesPresent = true;
                }
                return "{% if let Some(" + binding + ") = " + test + " %}{{ " + binding + " }}{% else %}" + output(e.orElse(), scope, raw)
                        + "{% endif %}";
            }
            case ThymeleafExpr.Binary b when b.op().equals("+") && (isText(b.left(), scope) || isText(b.right(), scope)) -> {
                return output(b.left(), scope, raw) + output(b.right(), scope, raw);
            }
            case ThymeleafExpr.Link l -> {
                return link(l, scope);
            }
            case ThymeleafExpr.Message m -> {
                return message(m, scope, raw);
            }
            case ThymeleafExpr.Var v when scope.lookup(v.name()) instanceof Alias a -> {
                return output(a.expr(), a.scope(), raw);
            }
            case ThymeleafExpr.Util u when u.object().equals("temporals") && u.method().equals("format") -> {
                TV date = value(u.args().get(0), scope);
                if (date.type() instanceof RT.Opt) {
                    String binding = fresh("date");
                    String pattern = u.args().size() > 1 && u.args().get(1) instanceof ThymeleafExpr.Str s ? s.value() : "yyyy-MM-dd";
                    return "{% if let Some(" + binding + ") = " + date.code() + " %}{{ " + binding + ".format("
                            + BodyLowerer.rustString(chronoPattern(pattern)) + ") }}{% endif %}";
                }
                return display(value(n, scope), raw);
            }
            default -> {
                return display(value(n, scope), raw);
            }
        }
    }

    /**
     * {@code x == null ? a : b}（x は null になりうる参照）を {@code {% if let Some(x) = x %}b{% else %}a{% endif %}} にする
     * （null でない側で x を使うため）。null でない側で x を使わなければ null。
     */
    private String narrowed(ThymeleafExpr.Cond c, Scope scope, java.util.function.BiFunction<ThymeleafExpr.Node, Scope, String> out) {
        if (!(c.condition() instanceof ThymeleafExpr.Binary b) || !(b.op().equals("==") || b.op().equals("!="))) {
            return null;
        }
        ThymeleafExpr.Node other = b.right() instanceof ThymeleafExpr.Null ? b.left() : b.left() instanceof ThymeleafExpr.Null ? b.right() : null;
        String key = other == null ? null : pathKey(other);
        if (key == null) {
            return null;
        }
        TV v = value(other, scope);
        if (!(v.type() instanceof RT.Opt o)) {
            return null;
        }
        boolean isNull = b.op().equals("==");
        ThymeleafExpr.Node some = isNull ? c.whenFalse() : c.whenTrue();
        ThymeleafExpr.Node none = isNull ? c.whenTrue() : c.whenFalse();
        String binding = fresh(Naming.valueName(key.substring(key.lastIndexOf('.') + 1)));
        Scope inner = new Scope(scope);
        inner.paths.put(key, new TV(binding, o.inner(), true));
        String someText = some == null ? "" : out.apply(some, inner);
        if (!usesVar(someText, binding)) {
            return null;
        }
        return "{% if let Some(" + binding + ") = " + v.code() + " %}" + someText
                + (none == null ? "" : "{% else %}" + out.apply(none, scope)) + "{% endif %}";
    }

    /** 変換したテンプレートの askama の構文（{{ }}・{% %}）の中で変数 name を使っているか。 */
    private static boolean usesVar(String text, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{.*?}}|\\{%.*?%}", java.util.regex.Pattern.DOTALL).matcher(text);
        java.util.regex.Pattern var = java.util.regex.Pattern.compile("(?<![\\w.\"])" + java.util.regex.Pattern.quote(name) + "(?![\\w\"])");
        while (m.find()) {
            if (var.matcher(m.group()).find()) {
                return true;
            }
        }
        return false;
    }

    /** 文字列の連結になる + か（片方が文字列・リテラル）。 */
    private boolean isText(ThymeleafExpr.Node n, Scope scope) {
        return switch (n) {
            case ThymeleafExpr.Str s -> true;
            case ThymeleafExpr.Message m -> true;
            case ThymeleafExpr.Binary b when b.op().equals("+") -> isText(b.left(), scope) || isText(b.right(), scope);
            case ThymeleafExpr.Num x -> false;
            case ThymeleafExpr.Cond c -> true;
            case ThymeleafExpr.Var v when scope.lookup(v.name()) instanceof Alias a -> isText(a.expr(), a.scope());
            default -> {
                RT t = value(n, scope).type();
                yield !(t instanceof RT.Prim);
            }
        };
    }

    private String display(TV v, boolean raw) {
        String filter = raw ? "|safe" : "";
        RT t = v.type();
        if (t instanceof RT.Null) {
            return "";
        }
        if (t instanceof RT.Opt o) {
            String binding = fresh("value");
            return "{% if let Some(" + binding + ") = " + v.code() + " %}" + display(new TV(binding, o.inner(), true), raw) + "{% endif %}";
        }
        RT inner = t instanceof RT.Ref r ? r.inner() : t;
        if (inner instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
            return "{{ " + v.code() + ".name()" + filter + " }}";
        }
        return "{{ " + v.code() + filter + " }}";
    }

    // ================================================================== メッセージ（#{...}）

    /**
     * メッセージ式。messages.properties の値を変換時に埋め込む。キーが {@code ${'priority.' + p}} のように値で決まるなら、
     * その値で分岐する（{@code {% match p.name() %}{% when "LOW" %}低...}）。
     */
    private String message(ThymeleafExpr.Message m, Scope scope, boolean raw) {
        if (m.key() instanceof ThymeleafExpr.Str k) {
            String text = tr.messages().getProperty(k.value());
            if (text == null) {
                diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "message '" + k.value() + "' is not in messages.properties");
                return staticText(ThymeleafHtml.escape("??" + k.value() + "??"));
            }
            return formatMessage(text, m.args(), scope, raw);
        }
        // キー = 文字列の連結（前後は文字列リテラル、間に 1 つの値）
        List<ThymeleafExpr.Node> parts = new ArrayList<>();
        flattenPlus(m.key(), parts);
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        ThymeleafExpr.Node dynamic = null;
        for (ThymeleafExpr.Node p : parts) {
            if (p instanceof ThymeleafExpr.Str str) {
                (dynamic == null ? prefix : suffix).append(str.value());
            } else if (dynamic == null) {
                dynamic = p;
            } else {
                return unsupported("message key " + m.key());
            }
        }
        if (dynamic == null) {
            return message(new ThymeleafExpr.Message(new ThymeleafExpr.Str(prefix + suffix.toString()), m.args()), scope, raw);
        }
        TV v = value(dynamic, scope);
        RT t = v.type() instanceof RT.Ref r ? r.inner() : v.type();
        String code;
        if (t instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
            code = v.code() + ".name()";
        } else if (t instanceof RT.Str) {
            code = v.code() + ".as_str()";
        } else if (t instanceof RT.StrRef) {
            code = v.code();
        } else {
            return unsupported("message key from " + v.code());
        }
        StringBuilder sb = new StringBuilder("{% match " + code + " %}");
        for (String key : new java.util.TreeSet<>(tr.messages().stringPropertyNames())) {
            if (key.length() > prefix.length() + suffix.length() && key.startsWith(prefix.toString()) && key.endsWith(suffix.toString())) {
                String middle = key.substring(prefix.length(), key.length() - suffix.length());
                sb.append("{% when ").append(BodyLowerer.rustString(middle)).append(" %}")
                        .append(formatMessage(tr.messages().getProperty(key), m.args(), scope, raw));
            }
        }
        sb.append("{% else %}").append(staticText(ThymeleafHtml.escape("??" + prefix))).append("{{ ").append(code).append(" }}")
                .append(staticText(ThymeleafHtml.escape(suffix + "??"))).append("{% endmatch %}");
        return sb.toString();
    }

    private static void flattenPlus(ThymeleafExpr.Node n, List<ThymeleafExpr.Node> out) {
        if (n instanceof ThymeleafExpr.Binary b && b.op().equals("+")) {
            flattenPlus(b.left(), out);
            flattenPlus(b.right(), out);
        } else {
            out.add(n);
        }
    }

    /**
     * メッセージの書式。引数がなければそのまま（Spring の MessageSource は MessageFormat を通さない）、あれば
     * java.text.MessageFormat と同じく {@code {0}} を引数で置き換え、{@code '...'} を引用として扱う。
     */
    private String formatMessage(String pattern, List<ThymeleafExpr.Node> args, Scope scope, boolean raw) {
        if (args.isEmpty()) {
            return staticText(raw ? pattern : ThymeleafHtml.escape(pattern));
        }
        StringBuilder out = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        int i = 0;
        boolean quoted = false;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                    lit.append('\'');
                    i += 2;
                } else {
                    quoted = !quoted;
                    i++;
                }
                continue;
            }
            if (c == '{' && !quoted) {
                int end = pattern.indexOf('}', i);
                if (end < 0) {
                    lit.append(pattern.substring(i));
                    break;
                }
                String spec = pattern.substring(i + 1, end).strip();
                String[] fields = spec.split(",", 2);
                int index;
                try {
                    index = Integer.parseInt(fields[0].strip());
                } catch (NumberFormatException e) {
                    diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "message format {" + spec + "} is not supported yet");
                    lit.append(pattern, i, end + 1);
                    i = end + 1;
                    continue;
                }
                if (fields.length > 1) {
                    diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "message format type {" + spec + "} is not supported yet");
                }
                out.append(staticText(raw ? lit.toString() : ThymeleafHtml.escape(lit.toString())));
                lit.setLength(0);
                out.append(index < args.size() ? messageArg(value(args.get(index), scope), raw) : "{" + index + "}");
                i = end + 1;
                continue;
            }
            lit.append(c);
            i++;
        }
        out.append(staticText(raw ? lit.toString() : ThymeleafHtml.escape(lit.toString())));
        return out.toString();
    }

    /** MessageFormat の引数の表示（数値は 3 桁ごとに区切る。null は "null"）。 */
    private String messageArg(TV v, boolean raw) {
        RT t = v.type() instanceof RT.Ref r ? r.inner() : v.type();
        if (t instanceof RT.Null) {
            return "null";
        }
        if (t instanceof RT.Opt o) {
            String binding = fresh("arg");
            return "{% if let Some(" + binding + ") = " + v.code() + " %}" + messageArg(new TV(binding, o.inner(), true), raw)
                    + "{% else %}null{% endif %}";
        }
        if (t instanceof RT.Prim p && !p.equals(RT.BOOL)) {
            usesMessageArg = true;
            return "{{ " + v.code() + ".message_arg() }}";
        }
        if (t instanceof RT.Named n && n.name().equals("NaiveDateTime")) {
            diags.report(DiagnosticCode.LOSSY_STRING_IDENTITY, pos(),
                    "LocalDateTime in a message is printed as 'yyyy-MM-dd HH:mm:ss' in Rust ('yyyy-MM-ddTHH:mm' in Java)");
        }
        return display(v, raw);
    }

    /** リンク式 @{/path/{var}(var=..., q=...)} の URL。 */
    private String link(ThymeleafExpr.Link l, Scope scope) {
        String path = l.path();
        List<ThymeleafExpr.Param> query = new ArrayList<>();
        for (ThymeleafExpr.Param p : l.params()) {
            String placeholder = "{" + p.name() + "}";
            if (path.contains(placeholder)) {
                path = path.replace(placeholder, "\u0001" + p.name() + "\u0001");
            } else {
                query.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("\u0001", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 0) {
                sb.append(staticText(ThymeleafHtml.escape(parts[i])));
            } else {
                String name = parts[i];
                ThymeleafExpr.Node value = l.params().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().value();
                sb.append(urlValue(value, scope));
            }
        }
        for (int i = 0; i < query.size(); i++) {
            ThymeleafExpr.Param p = query.get(i);
            sb.append(i == 0 ? (path.contains("?") ? "&amp;" : "?") : "&amp;");
            TV v = value(p.value(), scope);
            if (v.type() instanceof RT.Opt || v.type() instanceof RT.Null) {
                String binding = fresh("param");
                sb.append("{% if let Some(").append(binding).append(") = ").append(v.code()).append(" %}").append(p.name()).append("=")
                        .append(urlValue(new ThymeleafExpr.Var(binding), withVar(scope, binding, ((RT.Opt) v.type()).inner())))
                        .append("{% else %}").append(p.name()).append("{% endif %}");
            } else {
                sb.append(p.name()).append('=').append(urlValue(p.value(), scope));
            }
        }
        return sb.toString();
    }

    private Scope withVar(Scope scope, String name, RT type) {
        Scope s = new Scope(scope);
        s.vars.put(name, new TV(name, type, true));
        return s;
    }

    private String urlValue(ThymeleafExpr.Node value, Scope scope) {
        if (value instanceof ThymeleafExpr.Str s) {
            return ThymeleafHtml.escape(URLEncoder.encode(s.value(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        TV v = value(value, scope);
        RT inner = v.type() instanceof RT.Ref r ? r.inner() : v.type();
        if (inner instanceof RT.Prim) {
            return "{{ " + v.code() + " }}";
        }
        if (inner instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
            return "{{ " + v.code() + ".name()|urlencode }}";
        }
        return "{{ " + v.code() + "|urlencode }}";
    }

    // ================================================================== 補助

    /** テンプレートの静的な文字列（askama の構文と解釈される文字列はエスケープする）。 */
    private static String staticText(String s) {
        if (s.startsWith(RAW_MARK)) {
            return s.substring(RAW_MARK.length());
        }
        if (!s.contains("{{") && !s.contains("{%") && !s.contains("{#")) {
            return s;
        }
        return "{% raw %}" + s + "{% endraw %}";
    }

    /** テンプレートの中で重ならない変数名。 */
    private String fresh(String base) {
        int n = names.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + n;
    }

    private TV unknown(String what) {
        diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "template: " + what + " is not supported yet");
        return new TV("None", new RT.Null(), false);
    }

    private String unsupported(String what) {
        diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos(), "template: " + what + " is not supported yet");
        return "";
    }

    private SourcePos pos() {
        return new SourcePos("templates/" + currentFile, 0, 0);
    }
}
