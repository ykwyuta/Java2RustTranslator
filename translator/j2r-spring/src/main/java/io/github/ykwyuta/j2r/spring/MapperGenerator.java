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
        java.util.Map<String, RItem> rowMappers = new java.util.LinkedHashMap<>();
        for (Decl.MethodDecl m : t.methods()) {
            Plans.MapperFn fn = tr.plans().mapperFns.get(m.ref().key());
            if (fn == null) {
                if (!m.isAbstract()) {
                    tr.report(m.pos(), t.simpleName() + "." + m.name() + ": default methods in Mappers are not supported yet");
                }
                continue;
            }
            fns.add(function(t, mapper, m, fn, imports, rowMappers));
        }
        List<RItem> items = new ArrayList<>(imports.items());
        items.addAll(fns);
        items.addAll(rowMappers.values());
        String path = String.join("/", model.modulePath(t.packageName()));
        List<String> header = mapper == null ? SpringTranslator.header(t)
                : SpringTranslator.header(t, mapper.file().getFileName().toString());
        return new RFile((path.isEmpty() ? "" : path + "/") + SpringModel.fileModule(t) + ".rs", header, List.of(), items);
    }

    // ------------------------------------------------------------------ 引数

    /** SQL から参照できる引数。 */
    /** owned は {@code <bind>} の値（関数の中で作った値なので、渡すときは借用する）。 */
    private record Param(String name, String rust, RT type, boolean owned) {}

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
            BodyLowerer.Place place = p.owned() ? BodyLowerer.Place.FIELD : BodyLowerer.Place.LOCAL;
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

    private RItem function(Decl.TypeDecl t, MyBatisXml.Mapper mapper, Decl.MethodDecl m, Plans.MapperFn fn, Imports imports,
                           java.util.Map<String, RItem> rowMappers) {
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
            env.params.add(new Param(name, rust, fn.params().get(i), false));
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
            String rowMapper = null;
            MyBatisXml.ResultMap rm = st.kind() == MyBatisXml.Kind.SELECT ? tr.resultMap(t, mapper, m, st) : null;
            if (rm != null) {
                rowMapper = Naming.toSnakeCase(rm.id());
                if (!rowMappers.containsKey(rowMapper)) {
                    rowMappers.put(rowMapper, rowMapperFn(rowMapper, rm, rowType(fn.ret()), imports, m));
                }
            }
            body = st.isDynamic() ? dynamic(st, fn, env, m, rowMapper) : staticStatement(st, fn, env, m, rowMapper);
        }
        List<String> javadoc = SpringTranslator.docs(m.javadoc());
        if (!javadoc.isEmpty()) {
            docs.addAll(0, javadoc);
            docs.add(javadoc.size(), "");
        }
        return new RItem.Fn(docs, List.of(), "pub", true, fn.rustName(), params, ret, body);
    }

    // ------------------------------------------------------------------ 静的な SQL

    private RExpr.Block staticStatement(MyBatisXml.Statement st, Plans.MapperFn fn, Env env, Decl.MethodDecl m, String rowMapper) {
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
        String kind = queryKind(st, fn, rowMapper);
        RExpr lit = new RExpr.Lit(sqlLiteral(sql.toString()));
        switch (kind) {
            case "query_as" -> base = new RExpr.Call(new RExpr.Path("sqlx::query_as::<_, " + rowType(fn.ret()).text(env.imports) + ">"), List.of(lit));
            case "query_scalar" -> base = new RExpr.Call(new RExpr.Path("sqlx::query_scalar::<_, " + scalarType(st, fn, env) + ">"), List.of(lit));
            default -> base = new RExpr.Call(new RExpr.Path("sqlx::query"), List.of(lit));
        }
        for (RExpr b : binds) {
            base = new RExpr.MethodCall(base, "bind", List.of(b));
        }
        if (kind.equals("query_map")) {
            base = new RExpr.MethodCall(base, "try_map", List.of(new RExpr.Path(rowMapper)));
        }
        return execute(st, fn, env, m, base, stmts);
    }

    /**
     * query_as（エンティティ・record の行）/ query_map（resultMap で対応付ける行）/ query_scalar（1 列の値・生成されたキー）/
     * query（更新系）。
     */
    private static String queryKind(MyBatisXml.Statement st, Plans.MapperFn fn, String rowMapper) {
        if (st.kind() == MyBatisXml.Kind.SELECT) {
            if (rowMapper != null) {
                return "query_map";
            }
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

    /**
     * {@code <resultMap>}（{@code @Results}）の行の対応。書いた列をプロパティに入れ、autoMapping なら残りのプロパティも
     * 同じ名前（snake_case）の列から入れる。結果にない列は無視する（プロパティは既定値のまま）。
     */
    private RItem rowMapperFn(String name, MyBatisXml.ResultMap rm, RT rowType, Imports imports, Decl.MethodDecl m) {
        tr.supportModules.add("mybatis");
        imports.add("sqlx::postgres::PgRow");
        imports.add("crate::mybatis::column");
        for (String u : rm.unsupported()) {
            tr.report(m.pos(), "resultMap " + rm.id() + ": " + u + " is not supported yet");
        }
        Decl.TypeDecl decl = rowType instanceof RT.Named n ? tr.model().types.get(n.javaName()) : null;
        if (decl == null || !BodyLowerer.isEntityLike(tr.model().role(decl.qualifiedName()))) {
            tr.report(m.pos(), "resultMap " + rm.id() + ": mapping to " + rowType.text(imports)
                    + " is not supported yet (only classes with setters)");
            return new RItem.Fn(List.of(), List.of(), "", name, List.of(new RItem.Param("_row", false, new RType("PgRow"))),
                    new RType("sqlx::Result<" + rowType.text(imports) + ">"), new RExpr.Block(List.of(), new RExpr.Macro("todo",
                    List.of(new RExpr.Lit(BodyLowerer.rustString("resultMap " + rm.id())))), null, false));
        }
        String var = Naming.valueName(Character.toLowerCase(decl.simpleName().charAt(0)) + decl.simpleName().substring(1));
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let(var, true, null, new RExpr.Call(new RExpr.Path(rowType.text(imports) + "::default"), List.of())));
        java.util.Set<String> mapped = new java.util.HashSet<>();
        List<String[]> columns = new ArrayList<>();
        for (MyBatisXml.ResultMapping rmap : rm.mappings()) {
            if (tr.types().field(decl.qualifiedName(), rmap.property()) == null) {
                tr.report(m.pos(), "resultMap " + rm.id() + ": unknown property '" + rmap.property() + "'");
                continue;
            }
            mapped.add(rmap.property());
            columns.add(new String[] {rmap.property(), rmap.column().toLowerCase(Locale.ROOT)});
        }
        if (rm.autoMapping()) {
            for (Decl.FieldDecl f : decl.fields()) {
                if (!f.isStatic() && !mapped.contains(f.name())) {
                    columns.add(new String[] {f.name(), Naming.toSnakeCase(f.name())});
                }
            }
        }
        for (String[] c : columns) {
            RExpr get = new RExpr.Try(new RExpr.Call(new RExpr.Path("column"), List.of(new RExpr.Path("&row"),
                    new RExpr.Lit(BodyLowerer.rustString(c[1])))));
            stmts.add(new RStmt.ExprStmt(new RExpr.IfLet("Some(v)", get, RExpr.Block.of(List.of(new RStmt.ExprStmt(
                    new RExpr.Assign(new RExpr.Field(new RExpr.Path(var), Naming.valueName(c[0])), "=", new RExpr.Path("v")), true))), null),
                    false));
        }
        String source = rm.type().isEmpty() ? "`@Results`" : "`<resultMap id=\"" + rm.id() + "\">`";
        return new RItem.Fn(List.of(source + " の行の対応。"), List.of(), "", name, List.of(new RItem.Param("row", false, new RType("PgRow"))),
                new RType("sqlx::Result<" + rowType.text(imports) + ">"),
                new RExpr.Block(stmts, new RExpr.Call(new RExpr.Path("Ok"), List.of(new RExpr.Path(var))), null, false));
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

    private RExpr.Block dynamic(MyBatisXml.Statement st, Plans.MapperFn fn, Env env, Decl.MethodDecl m, String rowMapper) {
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
        String kind = queryKind(st, fn, rowMapper);
        RExpr q = new RExpr.Path("query");
        RExpr base = switch (kind) {
            case "query_as" -> new RExpr.MethodCall(q, "build_query_as::<" + rowType(fn.ret()).text(env.imports) + ">", List.of());
            case "query_scalar" -> new RExpr.MethodCall(q, "build_query_scalar::<" + scalarType(st, fn, env) + ">", List.of());
            case "query_map" -> new RExpr.MethodCall(new RExpr.MethodCall(q, "build", List.of()), "try_map", List.of(new RExpr.Path(rowMapper)));
            default -> new RExpr.MethodCall(q, "build", List.of());
        };
        return execute(st, fn, env, m, base, d.stmts);
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /** {@code <trim>}（{@code <where>}・{@code <set>}）の中の出力先。var は mybatis::Trim の変数。 */
    private record TrimCtx(String var, MyBatisXml.Trim trim) {}

    /** 動的 SQL の組み立て。 */
    private final class Dyn {
        final Env env;
        final Decl.MethodDecl method;
        final List<RStmt> stmts = new ArrayList<>();
        final java.util.Map<String, Integer> names = new java.util.HashMap<>();
        /** 次の断片の前に空白を入れない（{@code <foreach>} の open の直後）。 */
        boolean noSpace;

        Dyn(Env env, Decl.MethodDecl method) {
            this.env = env;
            this.method = method;
        }

        private String fresh(String base) {
            int n = names.merge(base, 1, Integer::sum);
            return n == 1 ? base : base + n;
        }

        /** nodes を out に出力する。trim が null でなければ {@code <trim>} の中（Text・Bind などの並びが 1 つの断片）。 */
        void nodes(List<MyBatisXml.SqlNode> nodes, List<RStmt> out, TrimCtx trim) {
            List<MyBatisXml.SqlNode> run = new ArrayList<>();
            for (MyBatisXml.SqlNode n : nodes) {
                if (n instanceof MyBatisXml.Text || n instanceof MyBatisXml.Bind || n instanceof MyBatisXml.Subst
                        || n instanceof MyBatisXml.Foreach) {
                    run.add(n);
                    continue;
                }
                flush(run, out, trim);
                switch (n) {
                    case MyBatisXml.If i -> out.add(new RStmt.ExprStmt(new RExpr.If(test(i.test()), block(i.body(), trim), null), false));
                    case MyBatisXml.Choose c -> {
                        RExpr chain = c.otherwise() == null ? null : block(c.otherwise(), trim);
                        for (int k = c.whens().size() - 1; k >= 0; k--) {
                            MyBatisXml.If w = c.whens().get(k);
                            chain = new RExpr.If(test(w.test()), block(w.body(), trim), chain);
                        }
                        if (chain != null) {
                            out.add(new RStmt.ExprStmt(chain, false));
                        }
                    }
                    case MyBatisXml.Trim t -> {
                        if (trim != null) {
                            tr.report(method.pos(), method.name() + ": <" + t.tag() + "> inside <" + trim.trim().tag() + "> is not supported yet");
                        }
                        tr.supportModules.add("mybatis");
                        env.imports.add("crate::mybatis::Trim");
                        String var = fresh(switch (t.tag()) {
                            case "where" -> "where_clause";
                            case "set" -> "set_clause";
                            default -> "trim";
                        });
                        out.add(new RStmt.Let(var, true, null, new RExpr.Call(new RExpr.Path("Trim::new"),
                                List.of(new RExpr.Lit(BodyLowerer.rustString(t.prefix())), new RExpr.Lit(BodyLowerer.rustString(t.suffix()))))));
                        nodes(t.body(), out, new TrimCtx(var, t));
                        if (!t.suffix().isEmpty()) {
                            out.add(new RStmt.ExprStmt(new RExpr.MethodCall(new RExpr.Path(var), "finish", List.of(new RExpr.Path("&mut query"))),
                                    true));
                        }
                    }
                    case MyBatisXml.BindVar b -> bindVar(b, out);
                    case MyBatisXml.Unsupported u -> {
                        tr.report(method.pos(), method.name() + ": " + u.description() + " is not supported yet");
                        out.add(new RStmt.ExprStmt(new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(u.description())))), true));
                    }
                    default -> throw new IllegalStateException(n.toString());
                }
            }
            flush(run, out, trim);
        }

        private RExpr.Block block(List<MyBatisXml.SqlNode> body, TrimCtx trim) {
            List<RStmt> inner = new ArrayList<>();
            int params = env.params.size();
            nodes(body, inner, trim);
            // <bind> の名前はブロックの中だけで使える。
            while (env.params.size() > params) {
                env.params.remove(env.params.size() - 1);
            }
            return new RExpr.Block(inner, null, null, false);
        }

        /**
         * Text・Bind・${}・foreach の並びを文にする（push / push_bind の連鎖）。{@code <trim>} の中なら、先頭の prefixOverrides と
         * 末尾の suffixOverrides を取り除いて Trim::part に渡す。
         */
        private void flush(List<MyBatisXml.SqlNode> run, List<RStmt> out, TrimCtx trim) {
            if (run.isEmpty()) {
                return;
            }
            List<MyBatisXml.SqlNode> parts = new ArrayList<>(run);
            run.clear();
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i) instanceof MyBatisXml.Text t) {
                    String s = collapse(t.sql());
                    if (i == 0) {
                        s = s.stripLeading();
                    }
                    if (i == parts.size() - 1) {
                        s = s.stripTrailing();
                    }
                    parts.set(i, new MyBatisXml.Text(s));
                }
            }
            String lead = "";
            String trail = "";
            if (trim != null) {
                if (parts.get(0) instanceof MyBatisXml.Text t) {
                    String o = override(t.sql(), trim.trim().prefixOverrides(), true);
                    if (o != null) {
                        lead = t.sql().substring(0, o.length());
                        parts.set(0, new MyBatisXml.Text(t.sql().substring(o.length()).stripLeading()));
                    }
                }
                if (parts.get(parts.size() - 1) instanceof MyBatisXml.Text t) {
                    String o = override(t.sql(), trim.trim().suffixOverrides(), false);
                    if (o != null) {
                        trail = t.sql().substring(t.sql().length() - o.length());
                        parts.set(parts.size() - 1, new MyBatisXml.Text(t.sql().substring(0, t.sql().length() - o.length()).stripTrailing()));
                    }
                }
            }
            RExpr part = trim == null ? null : new RExpr.MethodCall(new RExpr.Path(trim.var()), "part", List.of(new RExpr.Path("&mut query"),
                    new RExpr.Lit(BodyLowerer.rustString(lead)), new RExpr.Lit(BodyLowerer.rustString(trail))));
            if (parts.size() == 1 && parts.get(0) instanceof MyBatisXml.Foreach f && part != null) {
                // <where> の中の <foreach> だけの断片は、要素があるときだけ断片にする。
                foreach(f, out, part);
                return;
            }
            RExpr chain = part != null ? part : new RExpr.Path("query");
            boolean space = trim == null && !noSpace;
            noSpace = false;
            for (int i = 0; i < parts.size(); i++) {
                switch (parts.get(i)) {
                    case MyBatisXml.Text t -> {
                        String s = (i == 0 && space ? " " : "") + t.sql();
                        if (!s.isEmpty()) {
                            chain = new RExpr.MethodCall(chain, "push", List.of(new RExpr.Lit(BodyLowerer.rustString(s))));
                        }
                    }
                    case MyBatisXml.Bind b -> chain = new RExpr.MethodCall(chain, "push_bind", List.of(bindArg(env, b.expr(), method)));
                    case MyBatisXml.Subst x -> chain = new RExpr.MethodCall(chain, "push", List.of(substArg(x.expr())));
                    case MyBatisXml.Foreach f -> {
                        if (!(chain instanceof RExpr.Path p && p.path().equals("query"))) {
                            out.add(new RStmt.ExprStmt(chain, true));
                        }
                        foreach(f, out, null);
                        chain = new RExpr.Path("query");
                    }
                    default -> throw new IllegalStateException();
                }
            }
            if (!(chain instanceof RExpr.Path p && p.path().equals("query"))) {
                out.add(new RStmt.ExprStmt(chain, true));
            }
        }

        /** text の先頭（prefix なら）か末尾に overrides のどれかがあれば、その文字列（大文字と小文字は区別しない）。 */
        private static String override(String text, List<String> overrides, boolean prefix) {
            String upper = text.toUpperCase(Locale.ROOT);
            for (String o : overrides) {
                String u = o.toUpperCase(Locale.ROOT);
                boolean word = Character.isLetterOrDigit(u.charAt(0));
                if (prefix && upper.startsWith(u)
                        && (!word || upper.length() == u.length() || !Character.isLetterOrDigit(upper.charAt(u.length())))) {
                    return text.substring(0, o.length());
                }
                if (!prefix && upper.endsWith(u)
                        && (!word || upper.length() == u.length() || !Character.isLetterOrDigit(upper.charAt(upper.length() - u.length() - 1)))) {
                    return text.substring(text.length() - o.length());
                }
            }
            return null;
        }

        /**
         * {@code <foreach>}。要素がなければ何も書かない（open・close も）。part があれば（{@code <where>} の中）要素があるときだけ
         * Trim::part を呼ぶ。
         */
        private void foreach(MyBatisXml.Foreach f, List<RStmt> out, RExpr part) {
            BodyLowerer.RV coll = env.resolve(f.collection());
            if (coll == null) {
                tr.report(method.pos(), method.name() + ": unknown collection '" + f.collection() + "' in <foreach>");
                out.add(new RStmt.ExprStmt(new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(f.collection())))), true));
                return;
            }
            RT type = coll.type() instanceof RT.Ref r ? r.inner() : coll.type();
            RT elem = switch (type) {
                case RT.VecT v -> v.elem();
                case RT.Slice v -> v.elem();
                case RT.ArrayT v -> v.elem();
                default -> null;
            };
            if (elem == null) {
                tr.report(method.pos(), method.name() + ": <foreach> over " + type.text(env.imports) + " is not supported yet");
                out.add(new RStmt.ExprStmt(new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(f.collection())))), true));
                return;
            }
            boolean ref = coll.place() == BodyLowerer.Place.FIELD || coll.type() instanceof RT.VecT;
            boolean enumerate = !f.separator().isEmpty() || !f.index().isEmpty();
            RExpr iter = elem.copy()
                    ? new RExpr.MethodCall(new RExpr.MethodCall(coll.expr(), "iter", List.of()), "copied", List.of())
                    : enumerate ? new RExpr.MethodCall(coll.expr(), "iter", List.of())
                    : ref ? new RExpr.Unary("&", coll.expr()) : coll.expr();
            String item = f.item().isEmpty() ? "item" : f.item();
            String itemVar = fresh(Naming.valueName(item));
            String indexVar = f.index().isEmpty() ? "i" : fresh(Naming.valueName(f.index()));
            List<RStmt> body = new ArrayList<>();
            if (!f.separator().isEmpty()) {
                body.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.Binary(">", new RExpr.Path(indexVar), new RExpr.Lit("0")),
                        RExpr.Block.of(List.of(push(f.separator()))), null), false));
            }
            int params = env.params.size();
            env.params.add(new Param(item, itemVar, elem.copy() ? elem : new RT.Ref(elem, false), false));
            if (!f.index().isEmpty()) {
                env.params.add(new Param(f.index(), "(" + indexVar + " as i32)", RT.I32, false));
            }
            noSpace = !f.open().isEmpty();
            nodes(f.body(), body, null);
            noSpace = false;
            while (env.params.size() > params) {
                env.params.remove(env.params.size() - 1);
            }
            RExpr loop = new RExpr.Template(List.of("for " + (enumerate ? "(" + indexVar + ", " + itemVar + ")" : itemVar) + " in ",
                    enumerate ? new RExpr.MethodCall(iter, "enumerate", List.of()) : iter, " ", RExpr.Block.of(body)));
            List<RStmt> guarded = new ArrayList<>();
            if (part != null) {
                guarded.add(new RStmt.ExprStmt(part, true));
            }
            if (!f.open().isEmpty()) {
                guarded.add(push((part != null ? "" : " ") + f.open()));
            }
            guarded.add(new RStmt.ExprStmt(loop, false));
            if (!f.close().isEmpty()) {
                guarded.add(push(f.close()));
            }
            if (part == null && f.open().isEmpty() && f.close().isEmpty()) {
                out.addAll(guarded);
            } else {
                out.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.Unary("!", new RExpr.MethodCall(coll.expr(), "is_empty", List.of())),
                        RExpr.Block.of(guarded), null), false));
            }
        }

        private RStmt push(String sql) {
            return new RStmt.ExprStmt(new RExpr.MethodCall(new RExpr.Path("query"), "push", List.of(new RExpr.Lit(BodyLowerer.rustString(sql)))),
                    true);
        }

        /** {@code <bind name="pattern" value="'%' + keyword + '%'"/>} → {@code let pattern = format!("%{}%", keyword);}。 */
        private void bindVar(MyBatisXml.BindVar b, List<RStmt> out) {
            BodyLowerer.RV v;
            try {
                v = new OgnlLowerer(env).value(Ognl.parse(b.value()));
            } catch (Ognl.ParseException e) {
                tr.report(method.pos(), method.name() + ": " + e.getMessage());
                v = BodyLowerer.RV.of(new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString(b.value())))), RT.STR);
            }
            String rust = fresh(Naming.valueName(b.name()));
            RT type = v.type() instanceof RT.StrRef ? RT.STR : v.type();
            RExpr value = v.type() instanceof RT.StrRef ? new RExpr.MethodCall(v.expr(), "to_string", List.of()) : v.expr();
            out.add(new RStmt.Let(rust, false, null, value));
            env.params.add(new Param(b.name(), rust, type, true));
        }

        /** {@code ${expr}} の値（SQL にそのまま書く文字列。null は空文字列）。 */
        private RExpr substArg(String expr) {
            BodyLowerer.RV v = env.resolve(expr);
            if (v == null) {
                tr.report(method.pos(), method.name() + ": unknown parameter ${" + expr + "}");
                return new RExpr.Macro("todo", List.of(new RExpr.Lit(BodyLowerer.rustString("${" + expr + "}"))));
            }
            RT t = v.type() instanceof RT.Ref r ? r.inner() : v.type();
            RExpr e = v.expr();
            if (t instanceof RT.Opt o) {
                RT inner = o.inner() instanceof RT.Ref r ? r.inner() : o.inner();
                if (inner instanceof RT.Str) {
                    return new RExpr.MethodCall(new RExpr.MethodCall(e, "as_deref", List.of()), "unwrap_or", List.of(new RExpr.Lit("\"\"")));
                }
                if (inner instanceof RT.StrRef) {
                    return new RExpr.MethodCall(e, "unwrap_or", List.of(new RExpr.Lit("\"\"")));
                }
                RExpr some = inner instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM
                        ? new RExpr.MethodCall(new RExpr.Path("v"), "name", List.of()) : new RExpr.Path("v");
                return new RExpr.MethodCall(e, "map_or_else", List.of(RExpr.Closure.of(List.of(), new RExpr.Path("String::new()")),
                        RExpr.Closure.of(List.of("v"), new RExpr.MethodCall(some, "to_string", List.of()))));
            }
            if (t instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
                return new RExpr.MethodCall(e, "name", List.of());
            }
            return v.place() == BodyLowerer.Place.FIELD && !t.copy() ? new RExpr.Unary("&", e) : e;
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

        /** {@code a + b + ...}（文字列の連結。OGNL と同じく null は "null"）→ format!。 */
        private BodyLowerer.RV concat(Ognl.Add add) {
            List<Ognl.Node> parts = new ArrayList<>();
            flattenAdd(add, parts);
            StringBuilder fmt = new StringBuilder();
            List<RExpr> args = new ArrayList<>();
            for (Ognl.Node p : parts) {
                if (p instanceof Ognl.StrLit s) {
                    fmt.append(s.value().replace("{", "{{").replace("}", "}}"));
                    continue;
                }
                BodyLowerer.RV v = value(p);
                fmt.append("{}");
                RT t = v.type() instanceof RT.Ref r ? r.inner() : v.type();
                if (t instanceof RT.Opt o) {
                    RT inner = o.inner() instanceof RT.Ref r ? r.inner() : o.inner();
                    RExpr view = inner instanceof RT.Str ? new RExpr.MethodCall(v.expr(), "as_deref", List.of()) : v.expr();
                    args.add(inner instanceof RT.Str || inner instanceof RT.StrRef
                            ? new RExpr.MethodCall(view, "unwrap_or", List.of(new RExpr.Lit("\"null\"")))
                            : new RExpr.MethodCall(view, "map_or_else", List.of(RExpr.Closure.of(List.of(), new RExpr.Path("\"null\".to_string()")),
                            RExpr.Closure.of(List.of("v"), new RExpr.MethodCall(new RExpr.Path("v"), "to_string", List.of())))));
                } else if (t instanceof RT.Named n && n.kind() == RT.Named.Kind.ENUM) {
                    args.add(new RExpr.MethodCall(v.expr(), "name", List.of()));
                } else {
                    args.add(v.expr());
                }
            }
            List<RExpr> macroArgs = new ArrayList<>();
            macroArgs.add(new RExpr.Lit(BodyLowerer.rustString(fmt.toString())));
            macroArgs.addAll(args);
            return BodyLowerer.RV.of(new RExpr.Macro("format", macroArgs), RT.STR);
        }

        private static void flattenAdd(Ognl.Node n, List<Ognl.Node> out) {
            if (n instanceof Ognl.Add a) {
                flattenAdd(a.left(), out);
                flattenAdd(a.right(), out);
            } else {
                out.add(n);
            }
        }

        private BodyLowerer.RV value(Ognl.Node n) {
            return switch (n) {
                case Ognl.Add a -> concat(a);
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
