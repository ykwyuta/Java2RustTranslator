package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MyBatis の Mapper（インタフェース + XML）を、接続を引数に取る sqlx の関数のモジュールにする。
 *
 * <ul>
 *   <li>{@code #{x}} は {@code $n} + {@code .bind(x)}。enum は MyBatis の EnumTypeHandler と同じく name() を渡す</li>
 *   <li>{@code <where>} {@code <if>} {@code <set>} {@code <choose>} は {@code QueryBuilder} で組み立てる</li>
 *   <li>{@code useGeneratedKeys} + {@code keyProperty} は {@code RETURNING} で受け取ってエンティティに入れる</li>
 *   <li>戻り値: {@code List<T>} → fetch_all、{@code Optional<T>}・{@code @Nullable T} → fetch_optional、
 *       更新系の int / long → 更新件数</li>
 * </ul>
 */
final class MapperGenerator {
    private final SpringTranslator tr;

    MapperGenerator(SpringTranslator tr) {
        this.tr = tr;
    }

    /** select の 1 行の型（List・Option の中身）。 */
    static RT rowType(RT ret) {
        return switch (ret) {
            case RT.VecT v -> v.elem();
            case RT.Opt o -> o.inner();
            default -> ret;
        };
    }

    RFile generate(Decl.TypeDecl t, MyBatisXml.Mapper mapper) {
        SpringModel model = tr.model();
        String module = model.rustModule(t) + "::" + SpringModel.fileModule(t);
        Imports imports = new Imports(module);
        imports.add("sqlx::PgConnection");
        List<RItem> fns = new ArrayList<>();
        for (Decl.MethodDecl m : t.methods()) {
            Plans.MapperFn fn = tr.plans().mapperFns.get(m.ref().key());
            if (fn == null) {
                if (!m.isAbstract()) {
                    tr.report(m.pos(), t.simpleName() + "." + m.name() + ": default methods in Mappers are not supported yet");
                }
                continue;
            }
            fns.add(function(t, mapper, m, fn, imports));
        }
        List<RItem> items = new ArrayList<>(imports.items());
        items.addAll(fns);
        String path = String.join("/", model.modulePath(t.packageName()));
        List<String> header = mapper == null ? SpringTranslator.header(t)
                : SpringTranslator.header(t, mapper.file().getFileName().toString());
        return new RFile((path.isEmpty() ? "" : path + "/") + SpringModel.fileModule(t) + ".rs", header, List.of(), items);
    }

    // ------------------------------------------------------------------ 引数

    /** SQL から参照できる引数。 */
    private record Param(String name, String rust, RT type) {}

    /** {@code #{...}} と test の名前を解決する環境。 */
    private final class Env {
        final List<Param> params = new ArrayList<>();
        /** 引数が 1 つで @Param がない（エンティティならプロパティ名、それ以外は任意の名前でその引数を指す）。 */
        boolean single;
        final Imports imports;

        Env(Imports imports) {
            this.imports = imports;
        }

        BodyLowerer.RV resolve(String expr) {
            List<String> segs = List.of(expr.split("\\."));
            Param p = null;
            int from = 1;
            for (Param q : params) {
                if (q.name().equals(segs.get(0))) {
                    p = q;
                }
            }
            if (p == null && single) {
                p = params.get(0);
                from = isEntity(p.type()) ? 0 : 1;
                if (!isEntity(p.type()) && segs.size() > 1) {
                    from = 1;
                }
            }
            if (p == null) {
                return null;
            }
            RExpr e = new RExpr.Path(p.rust());
            RT type = p.type();
            BodyLowerer.Place place = BodyLowerer.Place.LOCAL;
            for (int i = from; i < segs.size(); i++) {
                RT owner = type instanceof RT.Ref r ? r.inner() : type;
                if (!(owner instanceof RT.Named n)) {
                    return null;
                }
                RT ft = tr.types().field(n.javaName(), segs.get(i));
                if (ft == null) {
                    return null;
                }
                e = new RExpr.Field(e, Naming.valueName(segs.get(i)));
                type = ft;
                place = BodyLowerer.Place.FIELD;
            }
            return new BodyLowerer.RV(e, type, place, false, false);
        }
    }

    private static boolean isEntity(RT t) {
        RT inner = t instanceof RT.Ref r ? r.inner() : t;
        return inner instanceof RT.Named n && (n.kind() == RT.Named.Kind.ENTITY || n.kind() == RT.Named.Kind.RECORD);
    }

    // ------------------------------------------------------------------ 関数

    private RItem function(Decl.TypeDecl t, MyBatisXml.Mapper mapper, Decl.MethodDecl m, Plans.MapperFn fn, Imports imports) {
        Env env = new Env(imports);
        List<RItem.Param> params = new ArrayList<>();
        params.add(new RItem.Param("conn", false, new RType("&mut PgConnection")));
        boolean anyParamAnnotation = false;
        for (int i = 0; i < m.params().size(); i++) {
            Decl.Param p = m.params().get(i);
            var a = tr.model().info(DeclInfo.paramKey(m.ref(), i)).get(SpringModel.PARAM);
            anyParamAnnotation |= a != null;
            String name = a != null ? a.stringValue("value") : p.name();
            String rust = Naming.valueName(p.name());
            env.params.add(new Param(name, rust, fn.params().get(i)));
            params.add(new RItem.Param(rust, false, new RType(fn.params().get(i).text(imports))));
        }
        env.single = m.params().size() == 1 && !anyParamAnnotation;
        RType ret = new RType("sqlx::Result<" + fn.ret().text(imports) + ">");
        MyBatisXml.Statement st = tr.statement(t, mapper, m);
        List<String> docs = new ArrayList<>();
        RExpr.Block body;
        if (st == null) {
            tr.report(m.pos(), t.simpleName() + "." + m.name() + ": no SQL statement found (Mapper XML or @Select etc.)");
            body = new RExpr.Block(List.of(), new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString("SQL for " + m.name())))),
                    null, false);
        } else {
            docs.add("`" + st.source() + "`");
            if (st.resultMap() != null) {
                tr.report(m.pos(), m.name() + ": resultMap is not supported yet (columns are mapped to fields by name)");
            }
            body = st.isDynamic() ? dynamic(st, fn, env, m) : staticStatement(st, fn, env, m);
        }
        List<String> javadoc = SpringTranslator.docs(m.javadoc());
        if (!javadoc.isEmpty()) {
            docs.addAll(0, javadoc);
            docs.add(javadoc.size(), "");
        }
        return new RItem.Fn(docs, List.of(), "pub", true, fn.rustName(), params, ret, body);
    }

    // ------------------------------------------------------------------ 静的な SQL

    private RExpr.Block staticStatement(MyBatisXml.Statement st, Plans.MapperFn fn, Env env, Decl.MethodDecl m) {
        StringBuilder sql = new StringBuilder();
        List<RExpr> binds = new ArrayList<>();
        for (MyBatisXml.SqlNode n : st.body()) {
            switch (n) {
                case MyBatisXml.Text t -> sql.append(t.sql());
                case MyBatisXml.Bind b -> {
                    binds.add(bindArg(env, b.expr(), m));
                    sql.append('$').append(binds.size());
                }
                case MyBatisXml.Unsupported u -> tr.report(m.pos(), m.name() + ": " + u.description() + " is not supported yet");
                default -> throw new IllegalStateException();
            }
        }
        if (st.keyProperty() != null) {
            sql.append("\nRETURNING ").append(columnName(st.keyColumn()));
        }
        List<RStmt> stmts = new ArrayList<>();
        RExpr base = null;
        String kind = queryKind(st, fn);
        RExpr lit = new RExpr.Lit(sqlLiteral(sql.toString()));
        switch (kind) {
            case "query_as" -> base = new RExpr.Call(new RExpr.Path("sqlx::query_as::<_, " + rowType(fn.ret()).text(env.imports) + ">"), List.of(lit));
            case "query_scalar" -> base = new RExpr.Call(new RExpr.Path("sqlx::query_scalar::<_, " + scalarType(st, fn, env) + ">"), List.of(lit));
            default -> base = new RExpr.Call(new RExpr.Path("sqlx::query"), List.of(lit));
        }
        for (RExpr b : binds) {
            base = new RExpr.MethodCall(base, "bind", List.of(b));
        }
        return execute(st, fn, env, m, base, stmts);
    }

    /** query_as（エンティティ・record の行）/ query_scalar（1 列の値・生成されたキー）/ query（更新系）。 */
    private static String queryKind(MyBatisXml.Statement st, Plans.MapperFn fn) {
        if (st.kind() == MyBatisXml.Kind.SELECT) {
            return isEntity(rowType(fn.ret())) ? "query_as" : "query_scalar";
        }
        return st.keyProperty() != null ? "query_scalar" : "query";
    }

    private String scalarType(MyBatisXml.Statement st, Plans.MapperFn fn, Env env) {
        if (st.kind() == MyBatisXml.Kind.SELECT) {
            return rowType(fn.ret()).text(env.imports);
        }
        RT key = keyType(st, env);
        return key == null ? "i64" : key.text(env.imports);
    }

    private RT keyType(MyBatisXml.Statement st, Env env) {
        for (Param p : env.params) {
            if (p.type() instanceof RT.Ref r && r.mut() && r.inner() instanceof RT.Named n) {
                return tr.types().field(n.javaName(), st.keyProperty());
            }
        }
        return null;
    }

    /** 文を実行して、Java の戻り値の型にする。 */
    private RExpr.Block execute(MyBatisXml.Statement st, Plans.MapperFn fn, Env env, Decl.MethodDecl m, RExpr base, List<RStmt> stmts) {
        RExpr conn = new RExpr.Path("conn");
        if (st.kind() == MyBatisXml.Kind.SELECT) {
            String fetch = fn.ret() instanceof RT.VecT ? "fetch_all" : fn.ret() instanceof RT.Opt ? "fetch_optional" : "fetch_one";
            return new RExpr.Block(stmts, new RExpr.Await(new RExpr.MethodCall(base, fetch, List.of(conn))), null, false);
        }
        if (st.keyProperty() != null) {
            Param entity = env.params.stream().filter(p -> p.type() instanceof RT.Ref r && r.mut()).findFirst().orElse(null);
            if (entity == null) {
                tr.report(m.pos(), m.name() + ": keyProperty needs an entity parameter");
                return new RExpr.Block(stmts, new RExpr.Macro("todo", List.of(new RExpr.Lit("\"keyProperty\""))), null, false);
            }
            RExpr fetch = new RExpr.Try(new RExpr.Await(new RExpr.MethodCall(base, "fetch_one", List.of(conn))));
            stmts.add(new RStmt.ExprStmt(new RExpr.Assign(new RExpr.Field(new RExpr.Path(entity.rust()), Naming.valueName(st.keyProperty())),
                    "=", fetch), true));
            return new RExpr.Block(stmts, ok(countValue(fn.ret(), new RExpr.Lit("1"), true)), null, false);
        }
        RExpr exec = new RExpr.Try(new RExpr.Await(new RExpr.MethodCall(base, "execute", List.of(conn))));
        if (fn.ret() instanceof RT.Unit) {
            stmts.add(new RStmt.ExprStmt(exec, true));
            return new RExpr.Block(stmts, ok(new RExpr.Path("()")), null, false);
        }
        stmts.add(new RStmt.Let("result", false, null, exec));
        RExpr rows = new RExpr.MethodCall(new RExpr.Path("result"), "rows_affected", List.of());
        return new RExpr.Block(stmts, ok(countValue(fn.ret(), rows, false)), null, false);
    }

    /** 更新件数を Java の戻り値の型にする（int → as i32、boolean → > 0）。 */
    private static RExpr countValue(RT ret, RExpr rows, boolean literal) {
        if (ret instanceof RT.Unit) {
            return new RExpr.Path("()");
        }
        if (ret instanceof RT.Prim p) {
            return switch (p.name()) {
                case "bool" -> literal ? new RExpr.Lit("true") : new RExpr.Binary(">", rows, new RExpr.Lit("0"));
                case "u64" -> rows;
                default -> literal ? rows : new RExpr.Cast(rows, new RType(p.name()));
            };
        }
        return rows;
    }

    private static RExpr ok(RExpr v) {
        return new RExpr.Call(new RExpr.Path("Ok"), List.of(v));
    }

    /** {@code #{x}} に渡す値。 */
    private RExpr bindArg(Env env, String expr, Decl.MethodDecl m) {
        BodyLowerer.RV v = env.resolve(expr);
        if (v == null) {
            tr.report(m.pos(), m.name() + ": unknown parameter #{" + expr + "}");
            return new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString("#{" + expr + "}"))));
        }
        RT t = v.type();
        if (t instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
            return new RExpr.MethodCall(v.expr(), "name", List.of());
        }
        if (t instanceof RT.Opt o && o.inner() instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
            return new RExpr.MethodCall(v.expr(), "map", List.of(new RExpr.Path(n.text(env.imports) + "::name")));
        }
        if (v.place() == BodyLowerer.Place.FIELD && !t.copy()) {
            return new RExpr.Unary("&", v.expr());
        }
        return v.expr();
    }

    /** SQL の文字列リテラル。短ければ 1 行、長ければ行ごとに行継続（\ + 改行）で分ける。 */
    static String sqlLiteral(String sql) {
        List<String> lines = new ArrayList<>();
        for (String line : sql.split("\n")) {
            String s = line.strip().replaceAll("\\s+", " ");
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        String oneLine = String.join(" ", lines);
        if (oneLine.length() <= 72 || lines.size() == 1) {
            return BodyLowerer.rustString(oneLine);
        }
        List<String> escaped = lines.stream().map(l -> {
            String q = BodyLowerer.rustString(l);
            return q.substring(1, q.length() - 1);
        }).toList();
        return "\"" + String.join(" \\\n", escaped) + "\"";
    }

    private static String columnName(String keyColumn) {
        return Naming.toSnakeCase(keyColumn);
    }

    // ------------------------------------------------------------------ 動的な SQL（QueryBuilder）

    private RExpr.Block dynamic(MyBatisXml.Statement st, Plans.MapperFn fn, Env env, Decl.MethodDecl m) {
        env.imports.add("sqlx::Postgres");
        env.imports.add("sqlx::QueryBuilder");
        List<MyBatisXml.SqlNode> nodes = new ArrayList<>(st.body());
        String first = "";
        if (!nodes.isEmpty() && nodes.get(0) instanceof MyBatisXml.Text t) {
            first = collapse(t.sql()).strip();
            nodes.remove(0);
        }
        Dyn d = new Dyn(env, m);
        d.stmts.add(new RStmt.Let("query", true, null, new RExpr.Call(new RExpr.Path("QueryBuilder::<Postgres>::new"),
                List.of(new RExpr.Lit(BodyLowerer.rustString(first))))));
        d.nodes(nodes, d.stmts, null);
        if (st.keyProperty() != null) {
            d.stmts.add(new RStmt.ExprStmt(new RExpr.MethodCall(new RExpr.Path("query"), "push",
                    List.of(new RExpr.Lit(BodyLowerer.rustString(" RETURNING " + columnName(st.keyColumn()))))), true));
        }
        String kind = queryKind(st, fn);
        RExpr q = new RExpr.Path("query");
        RExpr base = switch (kind) {
            case "query_as" -> new RExpr.MethodCall(q, "build_query_as::<" + rowType(fn.ret()).text(env.imports) + ">", List.of());
            case "query_scalar" -> new RExpr.MethodCall(q, "build_query_scalar::<" + scalarType(st, fn, env) + ">", List.of());
            default -> new RExpr.MethodCall(q, "build", List.of());
        };
        return execute(st, fn, env, m, base, d.stmts);
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /** 動的 SQL の組み立て。 */
    private final class Dyn {
        final Env env;
        final Decl.MethodDecl method;
        final List<RStmt> stmts = new ArrayList<>();
        int clauses;

        Dyn(Env env, Decl.MethodDecl method) {
            this.env = env;
            this.method = method;
        }

        /**
         * nodes を out に出力する。clause が null でなければ &lt;where&gt; / &lt;set&gt; の中（Text と Bind の並びが 1 つの断片）。
         */
        void nodes(List<MyBatisXml.SqlNode> nodes, List<RStmt> out, Clause clause) {
            List<MyBatisXml.SqlNode> run = new ArrayList<>();
            for (MyBatisXml.SqlNode n : nodes) {
                if (n instanceof MyBatisXml.Text || n instanceof MyBatisXml.Bind) {
                    run.add(n);
                    continue;
                }
                flush(run, out, clause);
                switch (n) {
                    case MyBatisXml.If i -> out.add(new RStmt.ExprStmt(new RExpr.If(test(i.test()), block(i.body(), clause), null), false));
                    case MyBatisXml.Choose c -> {
                        RExpr chain = c.otherwise() == null ? null : block(c.otherwise(), clause);
                        for (int k = c.whens().size() - 1; k >= 0; k--) {
                            MyBatisXml.If w = c.whens().get(k);
                            chain = new RExpr.If(test(w.test()), block(w.body(), clause), chain);
                        }
                        if (chain != null) {
                            out.add(new RStmt.ExprStmt(chain, false));
                        }
                    }
                    case MyBatisXml.Clause cl -> {
                        tr.supportModules.add("mybatis");
                        env.imports.add("crate::mybatis::Clause");
                        clauses++;
                        String var = (cl.where() ? "where_clause" : "set_clause") + (clauses > 1 ? Integer.toString(clauses) : "");
                        out.add(new RStmt.Let(var, true, null, new RExpr.Call(
                                new RExpr.Path(cl.where() ? "Clause::where_clause" : "Clause::set_clause"), List.of())));
                        nodes(cl.body(), out, new Clause(var, cl.where()));
                    }
                    case MyBatisXml.Unsupported u -> {
                        tr.report(method.pos(), method.name() + ": " + u.description() + " is not supported yet");
                        out.add(new RStmt.ExprStmt(new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(u.description())))), true));
                    }
                    default -> throw new IllegalStateException(n.toString());
                }
            }
            flush(run, out, clause);
        }

        private RExpr.Block block(List<MyBatisXml.SqlNode> body, Clause clause) {
            List<RStmt> inner = new ArrayList<>();
            nodes(body, inner, clause);
            return new RExpr.Block(inner, null, null, false);
        }

        /** Text と Bind の並びを 1 つの文（push / push_bind の連鎖）にする。 */
        private void flush(List<MyBatisXml.SqlNode> run, List<RStmt> out, Clause clause) {
            if (run.isEmpty()) {
                return;
            }
            List<MyBatisXml.SqlNode> parts = new ArrayList<>(run);
            run.clear();
            RExpr chain;
            if (clause != null) {
                String sep = clause.where() ? " AND " : ", ";
                if (parts.get(0) instanceof MyBatisXml.Text t) {
                    String s = collapse(t.sql()).stripLeading();
                    String upper = s.toUpperCase(Locale.ROOT);
                    if (clause.where() && (upper.startsWith("AND ") || upper.equals("AND"))) {
                        s = s.substring(3).stripLeading();
                    } else if (clause.where() && (upper.startsWith("OR ") || upper.equals("OR"))) {
                        s = s.substring(2).stripLeading();
                        sep = " OR ";
                    }
                    parts.set(0, new MyBatisXml.Text(s));
                }
                if (!clause.where() && parts.get(parts.size() - 1) instanceof MyBatisXml.Text t) {
                    String s = collapse(t.sql()).stripTrailing();
                    if (s.endsWith(",")) {
                        s = s.substring(0, s.length() - 1).stripTrailing();
                    }
                    parts.set(parts.size() - 1, new MyBatisXml.Text(s));
                }
                chain = new RExpr.MethodCall(new RExpr.Path(clause.var()), "part",
                        List.of(new RExpr.Path("&mut query"), new RExpr.Lit(BodyLowerer.rustString(sep))));
            } else {
                chain = new RExpr.Path("query");
                if (parts.get(0) instanceof MyBatisXml.Text t) {
                    parts.set(0, new MyBatisXml.Text(" " + collapse(t.sql()).stripLeading()));
                }
            }
            if (parts.get(parts.size() - 1) instanceof MyBatisXml.Text t && clause == null) {
                parts.set(parts.size() - 1, new MyBatisXml.Text(collapse(t.sql()).stripTrailing()));
            }
            for (int i = 0; i < parts.size(); i++) {
                MyBatisXml.SqlNode p = parts.get(i);
                if (p instanceof MyBatisXml.Text t) {
                    String s = collapse(t.sql());
                    if (i == parts.size() - 1) {
                        s = s.stripTrailing();
                    }
                    if (!s.isEmpty()) {
                        chain = new RExpr.MethodCall(chain, "push", List.of(new RExpr.Lit(BodyLowerer.rustString(s))));
                    }
                } else if (p instanceof MyBatisXml.Bind b) {
                    chain = new RExpr.MethodCall(chain, "push_bind", List.of(bindArg(env, b.expr(), method)));
                }
            }
            out.add(new RStmt.ExprStmt(chain, true));
        }

        private RExpr test(String src) {
            try {
                return new OgnlLowerer(env).cond(Ognl.parse(src));
            } catch (Ognl.ParseException e) {
                tr.report(method.pos(), method.name() + ": " + e.getMessage());
                return new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(src))));
            }
        }
    }

    private record Clause(String var, boolean where) {}

    // ------------------------------------------------------------------ test 属性（OGNL）

    private final class OgnlLowerer {
        final Env env;

        OgnlLowerer(Env env) {
            this.env = env;
        }

        RExpr cond(Ognl.Node n) {
            return switch (n) {
                case Ognl.Or o -> new RExpr.Binary("||", cond(o.left()), cond(o.right()));
                case Ognl.And a -> new RExpr.Binary("&&", cond(a.left()), cond(a.right()));
                case Ognl.Not x -> new RExpr.Unary("!", cond(x.operand()));
                case Ognl.Compare c -> compare(c);
                default -> {
                    BodyLowerer.RV v = value(n);
                    if (v.type() instanceof RT.Opt) {
                        yield new RExpr.Binary("==", v.expr(), new RExpr.Lit("Some(true)"));
                    }
                    yield v.expr();
                }
            };
        }

        private RExpr compare(Ognl.Compare c) {
            if (c.right() instanceof Ognl.NullLit || c.left() instanceof Ognl.NullLit) {
                Ognl.Node other = c.right() instanceof Ognl.NullLit ? c.left() : c.right();
                BodyLowerer.RV v = value(other);
                boolean eq = c.op().equals("==");
                if (v.type() instanceof RT.Opt) {
                    return new RExpr.MethodCall(v.expr(), eq ? "is_none" : "is_some", List.of());
                }
                return new RExpr.Lit(eq ? "false" : "true");
            }
            BodyLowerer.RV left = value(c.left());
            BodyLowerer.RV right = value(c.right());
            RExpr l = left.expr();
            RExpr r = right.expr();
            if (left.type() instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM && right.type() instanceof RT.StrRef) {
                l = new RExpr.MethodCall(l, "name", List.of());
            }
            if (left.type() instanceof RT.Opt o) {
                RExpr view = o.inner() instanceof RT.Str ? new RExpr.MethodCall(l, "as_deref", List.of()) : l;
                if (c.op().equals("==") || c.op().equals("!=")) {
                    return new RExpr.Binary(c.op(), view, new RExpr.Call(new RExpr.Path("Some"), List.of(r)));
                }
                return new RExpr.MethodCall(view, "is_some_and", List.of(RExpr.Closure.of(List.of("v"),
                        new RExpr.Binary(c.op(), new RExpr.Path("v"), r))));
            }
            return new RExpr.Binary(c.op(), l, r);
        }

        private BodyLowerer.RV value(Ognl.Node n) {
            return switch (n) {
                case Ognl.StrLit s -> BodyLowerer.RV.of(new RExpr.Lit(BodyLowerer.rustString(s.value())), RT.STR_REF);
                case Ognl.NumLit x -> BodyLowerer.RV.of(new RExpr.Lit(x.text()), RT.I64);
                case Ognl.BoolLit b -> BodyLowerer.RV.of(new RExpr.Lit(Boolean.toString(b.value())), RT.BOOL);
                case Ognl.NullLit x -> BodyLowerer.RV.of(new RExpr.Path("None"), new RT.Null());
                case Ognl.Path p -> {
                    BodyLowerer.RV v = env.resolve(String.join(".", p.segments()));
                    yield v != null ? v : unknown(String.join(".", p.segments()));
                }
                case Ognl.MethodCall mc -> {
                    BodyLowerer.RV target = value(mc.target());
                    yield switch (mc.name()) {
                        case "name" -> BodyLowerer.RV.of(new RExpr.MethodCall(target.expr(), "name", List.of()), RT.STR_REF);
                        case "size", "length" -> BodyLowerer.RV.of(new RExpr.MethodCall(target.expr(), "len", List.of()), RT.I64);
                        case "isEmpty" -> BodyLowerer.RV.of(new RExpr.MethodCall(target.expr(), "is_empty", List.of()), RT.BOOL);
                        default -> unknown(mc.name() + "()");
                    };
                }
                default -> unknown(n.toString());
            };
        }

        private BodyLowerer.RV unknown(String what) {
            tr.report(io.github.ykwyuta.j2r.common.SourcePos.UNKNOWN, "OGNL: cannot resolve '" + what + "'");
            return BodyLowerer.RV.of(new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(what)))), new RT.Unknown(what));
        }
    }
}
