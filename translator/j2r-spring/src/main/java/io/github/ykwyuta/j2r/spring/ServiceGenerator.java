package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Annotation;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @Service} のクラスを構造体にする。
 *
 * <ul>
 *   <li>Mapper のフィールドは接続プール {@code pool: PgPool} 1 つにまとめる。ほかのフィールド（Clock など）はそのまま</li>
 *   <li>{@code @Transactional} の public メソッドは {@code pool.begin()} 〜 {@code commit()} で囲む。
 *       途中で {@code ?} で抜けると Transaction の drop でロールバックする</li>
 *   <li>データベースを使うメソッドは async で {@code Result<T>} を返す</li>
 *   <li>サービスの中から呼ぶメソッドは、呼び出し元のトランザクション（接続）を受け取る版（{@code _in}）も作る。
 *       Spring の自己呼び出しがプロキシを通らず、同じトランザクションで動くのと同じ</li>
 * </ul>
 */
final class ServiceGenerator {
    private final SpringTranslator tr;

    ServiceGenerator(SpringTranslator tr) {
        this.tr = tr;
    }

    RFile generate(Decl.TypeDecl t) {
        SpringModel model = tr.model();
        String module = model.rustModule(t) + "::" + SpringModel.fileModule(t);
        Imports imports = new Imports(module);
        String name = SpringModel.rustTypeName(t);
        imports.defineLocal(name);

        // フィールド（Mapper は pool にまとめる）
        Map<String, RT> fields = new LinkedHashMap<>();
        boolean usesPool = false;
        for (Decl.FieldDecl f : t.fields()) {
            if (f.isStatic()) {
                continue;
            }
            if (f.type() instanceof JType.ClassType c && model.is(c.qualifiedName(), SpringModel.Role.MAPPER)) {
                usesPool = true;
                continue;
            }
            RT ft = tr.types().field(t, f);
            if (ft instanceof RT.Unknown u) {
                tr.report(f.pos(), name + "." + f.name() + ": field type " + u.description() + " is not supported yet");
            }
            fields.put(f.name(), ft);
        }
        List<RItem.Field> structFields = new ArrayList<>();
        if (usesPool) {
            imports.add("sqlx::PgPool");
            structFields.add(new RItem.Field("", "pool", new RType("PgPool")));
        }
        fields.forEach((f, ft) -> structFields.add(new RItem.Field("", Naming.valueName(f), new RType(ft.text(imports)))));

        List<RItem> methods = new ArrayList<>();
        methods.add(constructor(t, usesPool, fields, imports));
        for (Decl.MethodDecl m : t.methods()) {
            if (m.isConstructor() || m.isAbstract()) {
                continue;
            }
            Plans.Method plan = tr.plans().methods.get(m.ref().key());
            Annotation tx = model.info(DeclInfo.methodKey(m.ref())).get(SpringModel.TRANSACTIONAL);
            methods.addAll(method(t, m, plan, tr.plans().tx.get(m.ref().key()), tx, imports));
        }
        List<RItem> items = new ArrayList<>(imports.items());
        List<String> docs = new ArrayList<>(SpringTranslator.docs(t.javadoc()));
        items.add(new RItem.Struct(docs, List.of("derive(Clone)"), name, structFields));
        items.add(new RItem.Impl(List.of(), name, methods));
        String path = String.join("/", model.modulePath(t.packageName()));
        return new RFile((path.isEmpty() ? "" : path + "/") + SpringModel.fileModule(t) + ".rs", SpringTranslator.header(t), List.of(), items);
    }

    /** コンストラクタ注入のコンストラクタ → new。Mapper の引数は pool 1 つにまとめる。 */
    private RItem constructor(Decl.TypeDecl t, boolean usesPool, Map<String, RT> fields, Imports imports) {
        Decl.MethodDecl ctor = t.methods().stream().filter(Decl.MethodDecl::isConstructor).findFirst().orElse(null);
        List<RItem.Param> params = new ArrayList<>();
        List<RExpr.FieldInit> inits = new ArrayList<>();
        Map<String, String> fieldFromParam = new LinkedHashMap<>();
        if (ctor != null && ctor.body() != null) {
            for (Stmt s : ctor.body().stmts()) {
                if (s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.Assign a && a.target() instanceof Expr.FieldAccess fa
                        && fa.receiver() instanceof Expr.This && SpringTranslator.unwrapCast(a.value()) instanceof Expr.Local l) {
                    fieldFromParam.put(fa.name(), l.name());
                } else if (!(s instanceof Stmt.ExprStmt es2 && es2.expr() instanceof Expr.CtorCall)) {
                    tr.report(s.pos(), t.simpleName() + ": only field assignments are supported in the constructor");
                }
            }
        }
        boolean poolAdded = false;
        if (ctor != null) {
            for (int i = 0; i < ctor.params().size(); i++) {
                Decl.Param p = ctor.params().get(i);
                if (p.type() instanceof JType.ClassType c && tr.model().is(c.qualifiedName(), SpringModel.Role.MAPPER)) {
                    if (!poolAdded) {
                        params.add(new RItem.Param("pool", false, new RType("PgPool")));
                        poolAdded = true;
                    }
                    continue;
                }
                RT pt = tr.types().param(ctor.ref(), i, p.type());
                params.add(new RItem.Param(Naming.valueName(p.name()), false, new RType(pt.text(imports))));
            }
        }
        if (usesPool && !poolAdded) {
            params.add(0, new RItem.Param("pool", false, new RType("PgPool")));
        }
        if (usesPool) {
            inits.add(new RExpr.FieldInit("pool", new RExpr.Path("pool")));
        }
        for (Map.Entry<String, RT> f : fields.entrySet()) {
            String param = fieldFromParam.get(f.getKey());
            if (ctor == null) {
                params.add(new RItem.Param(Naming.valueName(f.getKey()), false, new RType(f.getValue().text(imports))));
                param = f.getKey();
            }
            inits.add(new RExpr.FieldInit(Naming.valueName(f.getKey()),
                    new RExpr.Path(param == null ? "Default::default()" : Naming.valueName(param))));
        }
        RExpr body = new RExpr.StructLit("Self", inits);
        List<String> docs = ctor == null ? List.of() : SpringTranslator.docs(ctor.javadoc());
        return new RItem.Fn(docs, List.of(), "pub", "new", params, new RType("Self"),
                new RExpr.Block(List.of(), shorthand(body), null, false));
    }

    /** {@code Self { pool: pool }} → {@code Self { pool }}（フィールド名と同じ変数の省略形）。 */
    private static RExpr shorthand(RExpr lit) {
        if (!(lit instanceof RExpr.StructLit sl)) {
            return lit;
        }
        List<String> parts = new ArrayList<>();
        for (RExpr.FieldInit f : sl.fields()) {
            parts.add(f.value() instanceof RExpr.Path p && p.path().equals(f.name()) ? f.name()
                    : f.name() + ": " + io.github.ykwyuta.j2r.rir.RustPrinter.print(f.value()));
        }
        String flat = sl.name() + " { " + String.join(", ", parts) + " }";
        return flat.length() <= 80 ? new RExpr.Path(flat) : lit;
    }

    private List<RItem> method(Decl.TypeDecl t, Decl.MethodDecl m, Plans.Method plan, Plans.Tx txPlan, Annotation txAnnotation,
                               Imports imports) {
        boolean transactional = txPlan.begins();
        BodyLowerer lowerer = new BodyLowerer(tr.model(), tr.types(), tr.plans(), tr.diags(), imports);
        List<String> docs = new ArrayList<>(SpringTranslator.docs(m.javadoc()));
        List<String> attrs = new ArrayList<>();
        if (txAnnotation != null && txAnnotation.booleanValue("readOnly", false)) {
            attrs.add("readOnly = true");
        }
        if (txPlan.propagation() != null && !txPlan.propagation().equals("REQUIRED")) {
            attrs.add("propagation = " + txPlan.propagation());
        }
        if (!attrs.isEmpty()) {
            if (!docs.isEmpty()) {
                docs.add("");
            }
            docs.add("`@Transactional(" + String.join(", ", attrs) + ")`");
        }
        String vis = m.isPrivate() ? "" : "pub";
        RType ret = returnType(plan, imports);
        List<RItem> out = new ArrayList<>();
        if (!plan.needsConn()) {
            BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, true, plan.ret(), plan.fallible(), null, BodyLowerer.Tail.VALUE, null);
            List<RItem.Param> params = params(m, plan, ctx, imports, false);
            out.add(new RItem.Fn(docs, List.of(), vis, false, plan.rustName(), params, ret, lowerer.body(m, ctx)));
            return out;
        }
        imports.add("crate::error::Result");
        String begin = transactional ? "begin" : "acquire";
        String var = transactional ? "tx" : "conn";
        RStmt open = new RStmt.Let(var, true, null, new RExpr.Try(new RExpr.Await(new RExpr.MethodCall(new RExpr.Field(
                new RExpr.Path("self"), "pool"), begin, List.of()))));
        if (plan.helperName() == null) {
            // 本体をトランザクションの中にそのまま展開する。
            BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, true, plan.ret(), true, "&mut " + var,
                    transactional ? BodyLowerer.Tail.COMMIT : BodyLowerer.Tail.VALUE, var);
            ctx.inTx = transactional;
            List<RItem.Param> params = params(m, plan, ctx, imports, false);
            RExpr.Block body = lowerer.body(m, ctx);
            List<RStmt> stmts = new ArrayList<>();
            stmts.add(open);
            stmts.addAll(body.stmts());
            out.add(new RItem.Fn(docs, List.of(), vis, true, plan.rustName(), params,
                    ret, new RExpr.Block(stmts, body.tail(), null, false)));
            return out;
        }
        // 接続を受け取る版（helper）と、トランザクションを張ってそれを呼ぶ版。
        imports.add("sqlx::PgConnection");
        BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, true, plan.ret(), true, "conn", BodyLowerer.Tail.VALUE, null);
        ctx.inTx = txPlan.inTx();
        List<RItem.Param> helperParams = params(m, plan, ctx, imports, true);
        RExpr.Block helperBody = lowerer.body(m, ctx);
        if (!m.isPrivate()) {
            List<RItem.Param> params = params(m, plan, null, imports, false);
            List<RExpr> args = new ArrayList<>();
            args.add(new RExpr.Path("&mut " + var));
            for (int i = 1; i < params.size(); i++) {
                args.add(new RExpr.Path(params.get(i).name()));
            }
            RExpr call = new RExpr.Try(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("self"), plan.helperName(), args)));
            List<RStmt> stmts = new ArrayList<>();
            stmts.add(open);
            RExpr tail;
            if (transactional && !txPlan.commitOnError().isEmpty()) {
                // ロールバックしない例外（検査例外・noRollbackFor）は、コミットしてから返す。
                imports.add("crate::error::Error");
                stmts.add(new RStmt.Let("result", false, null, new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("self"), plan.helperName(),
                        args))));
                List<String> patterns = new ArrayList<>(List.of("Ok(_)"));
                for (String ex : txPlan.commitOnError()) {
                    Plans.ErrorVariant v = tr.plans().errors.get(ex);
                    patterns.add("Err(Error::" + v.name() + (v.fields().isEmpty() ? "" : "(..)") + ")");
                }
                stmts.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.Macro("matches", List.of(new RExpr.Path("result"),
                        new RExpr.Path(String.join(" | ", patterns)))), RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Try(new RExpr.Await(
                        new RExpr.MethodCall(new RExpr.Path(var), "commit", List.of()))), true))), null), false));
                tail = new RExpr.Path("result");
            } else if (transactional) {
                boolean unit = plan.ret() instanceof RT.Unit;
                if (unit) {
                    stmts.add(new RStmt.ExprStmt(call, true));
                } else {
                    stmts.add(new RStmt.Let("result", false, null, call));
                }
                stmts.add(new RStmt.ExprStmt(new RExpr.Try(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path(var), "commit", List.of()))), true));
                tail = new RExpr.Call(new RExpr.Path("Ok"), List.of(new RExpr.Path(unit ? "()" : "result")));
            } else {
                tail = new RExpr.Await(new RExpr.MethodCall(new RExpr.Path("self"), plan.helperName(), args));
            }
            out.add(new RItem.Fn(docs, List.of(), vis, true, plan.rustName(), params, ret, new RExpr.Block(stmts, tail, null, false)));
            docs = List.of("`" + plan.rustName() + "` の本体。呼び出し元の接続（トランザクション）で実行する。");
        }
        out.add(new RItem.Fn(docs, List.of(), txPlan.calledByOther() && !m.isPrivate() ? "pub(crate)" : "", true, plan.helperName(),
                helperParams, ret, helperBody));
        return out;
    }

    private static RType returnType(Plans.Method plan, Imports imports) {
        String inner = plan.ret().text(imports);
        if (plan.fallible()) {
            imports.add("crate::error::Result");
            return new RType("Result<" + inner + ">");
        }
        return plan.ret() instanceof RT.Unit ? null : new RType(inner);
    }

    /** &self（と conn）と引数。ctx があれば引数をローカル変数として宣言する。 */
    private List<RItem.Param> params(Decl.MethodDecl m, Plans.Method plan, BodyLowerer.Ctx ctx, Imports imports, boolean withConn) {
        List<RItem.Param> params = new ArrayList<>();
        params.add(new RItem.Param("&self", false, null));
        if (withConn) {
            params.add(new RItem.Param("conn", false, new RType("&mut PgConnection")));
        }
        for (int i = 0; i < m.params().size(); i++) {
            Decl.Param p = m.params().get(i);
            String rust = Naming.valueName(p.name());
            if (ctx != null) {
                ctx.declare(p.name(), rust, plan.params().get(i));
            }
            params.add(new RItem.Param(rust, false, new RType(plan.params().get(i).text(imports))));
        }
        return params;
    }
}
