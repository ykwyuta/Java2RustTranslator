package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.BinaryOp;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.JirVisitor;
import io.github.ykwyuta.j2r.jir.MethodRef;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.jir.UnaryOp;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RStmt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * メソッド本体（JIR の文・式）を、所有権と借用・Option を意識した Rust に変換する。
 *
 * <ul>
 *   <li>引数は借用（{@code &str}・{@code &Todo}）、ローカル変数とフィールドは所有する値。型の違いは {@link #coerce} で埋める
 *       （{@code .to_string()}・{@code &x}・{@code .clone()}・{@code Some(x)}・{@code as_deref()} など）</li>
 *   <li>ローカル変数は最後に使うところでだけ移動し、それより前は clone する</li>
 *   <li>null の検査はパターンにする（{@code x != null && f(x)} → {@code x.is_some_and(|x| f(x))}、
 *       {@code if (x == null) return ...;} → {@code let Some(x) = x else { return ...; };}）</li>
 *   <li>getter / setter の呼び出しはフィールドの読み書き、Mapper の呼び出しは {@code todo_mapper::f(conn, ...).await?}</li>
 * </ul>
 */
final class BodyLowerer {
    enum Place { NONE, LOCAL, FIELD }

    /**
     * 変換した式。
     *
     * @param movable   最後に使うローカル変数（clone せずに移動してよい）
     * @param errResult 式が {@code expr?} で、expr が {@code Result<T, Error>}（戻り値にするときは ? と Ok を外せる）
     */
    record RV(RExpr expr, RT type, Place place, boolean movable, boolean errResult) {
        static RV of(RExpr expr, RT type) {
            return new RV(expr, type, Place.NONE, false, false);
        }
    }

    /** 関数の最後の値の扱い。 */
    enum Tail { VALUE, COMMIT }

    /**
     * 変換の一部を置き換えるフック（コントローラの Model・RedirectAttributes・ビュー名の return など）。
     * null を返す・false を返すと通常どおり変換する。
     */
    interface Hooks {
        default boolean stmt(Stmt s, Ctx ctx, List<RStmt> out) {
            return false;
        }

        default RV call(Expr.Call c, Ctx ctx) {
            return null;
        }

        default RV fieldAccess(Expr.FieldAccess fa, Ctx ctx) {
            return null;
        }

        /** return の値（Ok などで包んだ関数の戻り値そのもの）。 */
        default RExpr returnValue(Expr value, Ctx ctx) {
            return null;
        }

        default void enterBlock() {
        }

        default void exitBlock() {
        }
    }

    /** 1 つの関数の変換の状態。 */
    static final class Ctx {
        /** 変換の一部を置き換えるフック（なければ null）。 */
        Hooks hooks;
        final Decl.TypeDecl owner;
        final boolean hasSelf;
        final RT ret;
        final boolean fallible;
        /** データベースの接続として渡す式（{@code &mut tx}・{@code conn}）。使えなければ null。 */
        final String conn;
        final Tail tail;
        /** Tail.COMMIT のときのトランザクションの変数名。 */
        final String tx;
        /** conn がトランザクション（ほかのサービスの呼び出しを、このトランザクションに参加させる）。 */
        boolean inTx;
        /** エラーを返す呼び出しを ? ではなく unwrap にする（テスト。Java の throws Exception に当たる）。 */
        boolean unwrapErrors;
        private final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
        private final Map<String, Integer> uses = new HashMap<>();
        /** ローカル変数を変更する箇所の数（再代入・setter・&mut で渡す）。0 より大きければ let mut。 */
        private final Map<String, Integer> mutations = new HashMap<>();
        private final Map<String, RV> overrides = new HashMap<>();
        private int loopDepth;

        Ctx(Decl.TypeDecl owner, boolean hasSelf, RT ret, boolean fallible, String conn, Tail tail, String tx) {
            this.owner = owner;
            this.hasSelf = hasSelf;
            this.ret = ret;
            this.fallible = fallible;
            this.conn = conn;
            this.tail = tail;
            this.tx = tx;
            scopes.push(new HashMap<>());
        }

        void declare(String javaName, String rustName, RT type) {
            scopes.peek().put(javaName, new Local(rustName, type, loopDepth));
        }

        Local lookup(String javaName) {
            for (Map<String, Local> s : scopes) {
                Local l = s.get(javaName);
                if (l != null) {
                    return l;
                }
            }
            return null;
        }

        boolean isMutable(String javaName) {
            return mutations.getOrDefault(javaName, 0) > 0;
        }

        void mutation(String javaName, int delta) {
            mutations.merge(javaName, delta, Integer::sum);
        }
    }

    record Local(String rust, RT type, int loopDepth) {}

    private final SpringModel model;
    private final TypeResolver types;
    private final Plans plans;
    private final Diagnostics diags;
    private final Imports imports;

    BodyLowerer(SpringModel model, TypeResolver types, Plans plans, Diagnostics diags, Imports imports) {
        this.model = model;
        this.types = types;
        this.plans = plans;
        this.diags = diags;
        this.imports = imports;
    }

    // ================================================================== 本体

    /** メソッド本体を変換する（引数は呼び出し側が ctx に宣言しておく）。 */
    RExpr.Block body(Decl.MethodDecl m, Ctx ctx) {
        prescan(m.body(), ctx);
        return block(m.body().stmts(), ctx, true);
    }

    /** ローカル変数の使用回数（最後の使用で移動するため）と、再代入・変更されるローカル変数を調べる。 */
    private void prescan(Stmt.Block body, Ctx ctx) {
        JirVisitor.walk(body, s -> { }, e -> {
            switch (e) {
                case Expr.Local l -> ctx.uses.merge(l.name(), 1, Integer::sum);
                case Expr.Assign a when a.target() instanceof Expr.Local l -> {
                    ctx.mutation(l.name(), 1);
                    ctx.uses.merge(l.name(), -1, Integer::sum);
                }
                case Expr.CompoundAssign a when a.target() instanceof Expr.Local l -> ctx.mutation(l.name(), 1);
                case Expr.IncDec a when a.target() instanceof Expr.Local l -> ctx.mutation(l.name(), 1);
                case Expr.Call c when c.receiver() instanceof Expr.Local l && plans.setters.containsKey(c.method().key()) ->
                        ctx.mutation(l.name(), 1);
                case Expr.Call c when c.receiver() instanceof Expr.Local l && LibraryCalls.mutates(c.method()) -> ctx.mutation(l.name(), 1);
                case Expr.Call c when plans.mapperFns.containsKey(c.method().key()) -> {
                    Plans.MapperFn fn = plans.mapperFns.get(c.method().key());
                    for (int i = 0; i < c.args().size() && i < fn.params().size(); i++) {
                        if (fn.params().get(i) instanceof RT.Ref r && r.mut() && unwrapCast(c.args().get(i)) instanceof Expr.Local l) {
                            ctx.mutation(l.name(), 1);
                        }
                    }
                }
                default -> { }
            }
        });
    }

    private RExpr.Block block(List<Stmt> stmts, Ctx ctx, boolean fnBody) {
        ctx.scopes.push(new HashMap<>());
        if (ctx.hooks != null) {
            ctx.hooks.enterBlock();
        }
        List<RStmt> out = new ArrayList<>();
        RExpr tail = null;
        for (int i = 0; i < stmts.size(); i++) {
            Stmt s = stmts.get(i);
            boolean last = i == stmts.size() - 1;
            int setters = defaultWithSetters(stmts, i);
            if (setters > 0) {
                structUpdate(stmts.subList(i, i + setters + 1), ctx, out);
                i += setters;
                continue;
            }
            if (fnBody && last && s instanceof Stmt.Return r) {
                tail = finalReturn(r, ctx, out);
            } else {
                stmt(s, ctx, out);
            }
        }
        if (fnBody && tail == null && !terminates(stmts)) {
            tail = finishVoid(ctx, out);
        }
        if (ctx.hooks != null) {
            ctx.hooks.exitBlock();
        }
        ctx.scopes.pop();
        // let x = e; x  →  e
        if (tail instanceof RExpr.Path p && !out.isEmpty() && out.get(out.size() - 1) instanceof RStmt.Let let && !let.mut()
                && let.name().equals(p.path()) && let.init() != null) {
            tail = let.init();
            out.remove(out.size() - 1);
        }
        return new RExpr.Block(out, tail, null, false);
    }

    /**
     * {@code Todo todo = new Todo();} の直後に setter の呼び出しが続く場合、その数（なければ 0）。
     * 構造体リテラル {@code Todo { title: ..., ..Default::default() }} にまとめる。
     */
    private int defaultWithSetters(List<Stmt> stmts, int i) {
        if (!(stmts.get(i) instanceof Stmt.LocalVar lv) || !(lv.init() instanceof Expr.New n) || !n.args().isEmpty()
                || !isEntityLike(model.role(n.constructor().owner()))) {
            return 0;
        }
        int count = 0;
        Set<String> seen = new HashSet<>();
        for (int k = i + 1; k < stmts.size(); k++) {
            if (!(stmts.get(k) instanceof Stmt.ExprStmt es) || !(es.expr() instanceof Expr.Call c)
                    || !(c.receiver() instanceof Expr.Local l) || !l.name().equals(lv.name())
                    || !plans.setters.containsKey(c.method().key()) || !seen.add(plans.setters.get(c.method().key()))) {
                break;
            }
            boolean[] selfRef = {false};
            JirVisitor.walk(new Stmt.ExprStmt(c.args().get(0), es.pos()), x -> { }, e -> {
                if (e instanceof Expr.Local r && r.name().equals(lv.name())) {
                    selfRef[0] = true;
                }
            });
            if (selfRef[0]) {
                break;
            }
            count++;
        }
        return count;
    }

    private void structUpdate(List<Stmt> stmts, Ctx ctx, List<RStmt> out) {
        Stmt.LocalVar lv = (Stmt.LocalVar) stmts.get(0);
        Expr.New n = (Expr.New) lv.init();
        String owner = n.constructor().owner();
        RT t = types.owned(new JType.ClassType(owner), false);
        List<RExpr.FieldInit> fields = new ArrayList<>();
        for (Stmt s : stmts.subList(1, stmts.size())) {
            Expr.Call c = (Expr.Call) ((Stmt.ExprStmt) s).expr();
            ctx.uses.merge(lv.name(), -1, Integer::sum);
            ctx.mutation(lv.name(), -1);
            String field = plans.setters.get(c.method().key());
            fields.add(new RExpr.FieldInit(Naming.valueName(field), coerce(expr(c.args().get(0), ctx), types.field(owner, field))));
        }
        long total = model.types.get(owner).fields().stream().filter(f -> !f.isStatic()).count();
        if (fields.size() < total) {
            fields.add(new RExpr.FieldInit("..", new RExpr.Call(new RExpr.Path("Default::default"), List.of())));
        }
        String rust = Naming.valueName(lv.name());
        ctx.declare(lv.name(), rust, t);
        out.add(new RStmt.Let(rust, ctx.isMutable(lv.name()), null, new RExpr.StructLit(t.text(imports), fields)));
    }

    /** 関数の最後の return。 */
    private RExpr finalReturn(Stmt.Return r, Ctx ctx, List<RStmt> out) {
        if (r.value() == null) {
            return finishVoid(ctx, out);
        }
        if (ctx.hooks != null) {
            RExpr hooked = ctx.hooks.returnValue(r.value(), ctx);
            if (hooked != null) {
                return hooked;
            }
        }
        RV v = expr(r.value(), ctx);
        if (ctx.tail == Tail.COMMIT) {
            RExpr value = coerce(v, ctx.ret);
            if (!(value instanceof RExpr.Path)) {
                out.add(new RStmt.Let("result", false, null, value));
                value = new RExpr.Path("result");
            }
            out.add(commit(ctx));
            return ok(value);
        }
        return returnValue(v, ctx);
    }

    /** 戻り値のない関数の最後。 */
    private RExpr finishVoid(Ctx ctx, List<RStmt> out) {
        if (ctx.tail == Tail.COMMIT) {
            out.add(commit(ctx));
        }
        return ctx.fallible ? ok(new RExpr.Path("()")) : null;
    }

    private RStmt commit(Ctx ctx) {
        return new RStmt.ExprStmt(new RExpr.Try(new RExpr.Await(new RExpr.MethodCall(new RExpr.Path(ctx.tx), "commit", List.of()))), true);
    }

    private static RExpr ok(RExpr value) {
        return new RExpr.Call(new RExpr.Path("Ok"), List.of(value));
    }

    /** return の値（Result を返す関数なら Ok で包む。{@code x?} で型が同じなら ? を外す）。 */
    private RExpr returnValue(RV v, Ctx ctx) {
        if (!ctx.fallible) {
            return coerce(v, ctx.ret);
        }
        if (v.errResult() && v.expr() instanceof RExpr.Try t && sameType(v.type(), ctx.ret)) {
            return t.expr();
        }
        return ok(coerce(v, ctx.ret));
    }

    private static boolean terminates(List<Stmt> stmts) {
        if (stmts.isEmpty()) {
            return false;
        }
        Stmt last = stmts.get(stmts.size() - 1);
        return last instanceof Stmt.Return || last instanceof Stmt.Throw
                || last instanceof Stmt.Block b && terminates(b.stmts())
                || last instanceof Stmt.If i && i.elseStmt() != null && terminates(List.of(i.thenStmt())) && terminates(List.of(i.elseStmt()));
    }

    // ================================================================== 文

    private void stmt(Stmt s, Ctx ctx, List<RStmt> out) {
        if (ctx.hooks != null && ctx.hooks.stmt(s, ctx, out)) {
            return;
        }
        if (ctx.hooks != null && s instanceof Stmt.Return r && r.value() != null) {
            RExpr hooked = ctx.hooks.returnValue(r.value(), ctx);
            if (hooked != null) {
                out.add(new RStmt.ExprStmt(new RExpr.Return(hooked), true));
                return;
            }
        }
        switch (s) {
            case Stmt.Block b -> out.add(new RStmt.ExprStmt(block(b.stmts(), ctx, false), false));
            case Stmt.LocalVar lv -> localVar(lv, ctx, out);
            case Stmt.ExprStmt es -> {
                RV v = expr(es.expr(), ctx);
                out.add(new RStmt.ExprStmt(v.expr(), true));
            }
            case Stmt.Return r -> {
                RExpr value = r.value() == null ? (ctx.fallible ? ok(new RExpr.Path("()")) : null) : returnValue(expr(r.value(), ctx), ctx);
                out.add(new RStmt.ExprStmt(new RExpr.Return(value), true));
            }
            case Stmt.Throw t -> out.add(new RStmt.ExprStmt(throwExpr(t.exception(), ctx), true));
            case Stmt.If i -> ifStmt(i, ctx, out);
            case Stmt.ForEach f -> forEach(f, ctx, out);
            case Stmt.For f when iteratorLoop(f) != null -> forEach(iteratorLoop(f), ctx, out);
            default -> out.add(new RStmt.ExprStmt(unsupported(s.pos(), "statement " + s.getClass().getSimpleName()), true));
        }
    }

    private void localVar(Stmt.LocalVar lv, Ctx ctx, List<RStmt> out) {
        if (lv.type() instanceof JType.ClassType zone && zone.qualifiedName().equals("java.time.ZoneId")) {
            // タイムゾーン（Rust ではタイムゾーンのない日時を使う。Clock.fixed の引数などは使わない）
            return;
        }
        String rust = Naming.valueName(lv.name());
        RT type;
        RExpr init = null;
        String key = io.github.ykwyuta.j2r.jir.DeclInfo.localKey(lv.pos(), lv.name());
        if (lv.init() != null) {
            RV v = expr(lv.init(), ctx);
            type = v.type() instanceof RT.Null || vague(v.type()) ? types.declared(key, lv.type()) : RT.owned(v.type());
            if (v.type() instanceof RT.Null && !(type instanceof RT.Opt)) {
                type = new RT.Opt(type);
            }
            init = coerce(v, type);
        } else {
            type = types.declared(key, lv.type());
        }
        ctx.declare(lv.name(), rust, type);
        out.add(new RStmt.Let(rust, ctx.isMutable(lv.name()), null, init));
    }

    /** 型引数がわからない型（{@code new ArrayList<>()} など。宣言の型を使う）。 */
    private static boolean vague(RT t) {
        return t instanceof RT.Unknown || t instanceof RT.VecT v && vague(v.elem()) || t instanceof RT.Opt o && vague(o.inner());
    }

    private RExpr throwExpr(Expr exception, Ctx ctx) {
        RV v = expr(exception, ctx);
        if (!(v.type() instanceof RT.ErrorValue)) {
            return unsupported(exception.pos(), "throwing " + exception.type().javaName());
        }
        if (!ctx.fallible) {
            return unsupported(exception.pos(), "throw in a method that cannot return an error");
        }
        return new RExpr.Return(new RExpr.Call(new RExpr.Path("Err"), List.of(v.expr())));
    }

    private void ifStmt(Stmt.If i, Ctx ctx, List<RStmt> out) {
        // if (x == null) return ...;  →  let Some(x) = x else { return ...; };
        NullCheck check = nullCheck(i.condition(), ctx);
        if (check != null && check.isNull() && i.elseStmt() == null && terminates(List.of(i.thenStmt()))) {
            RExpr.Block orElse = block(stmts(i.thenStmt()), ctx, false);
            if (check.key().startsWith("l:") && !check.key().contains(".")) {
                // ローカル変数は Some の中身を取り出して同じ名前で束縛し直す（元の Option は使えなくなるので移動してよい）。
                String name = check.key().substring(2);
                if (isReturnNone(orElse, ctx)) {
                    out.add(new RStmt.Let(check.binding(), false, null, new RExpr.Try(check.raw().expr())));
                } else {
                    out.add(new RStmt.LetElse("Some(" + check.binding() + ")", check.raw().expr(), orElse));
                }
                ctx.declare(name, check.binding(), ((RT.Opt) check.raw().type()).inner());
            } else {
                if (isReturnNone(orElse, ctx)) {
                    out.add(new RStmt.Let(check.binding(), false, null, new RExpr.Try(check.view().expr())));
                } else {
                    out.add(new RStmt.LetElse("Some(" + check.binding() + ")", check.view().expr(), orElse));
                }
                bind(check, ctx);
            }
            return;
        }
        // if (x == null || rest) return ...;  →  let Some(x) = x else { return ...; }; if rest { return ...; }
        if (i.elseStmt() == null && terminates(List.of(i.thenStmt())) && i.condition() instanceof Expr.Binary b && b.op() == BinaryOp.OR) {
            List<Expr> chain = new ArrayList<>();
            flatten(b, BinaryOp.OR, chain);
            Map<String, Integer> savedUses = new HashMap<>(ctx.uses);
            NullCheck first = nullCheck(chain.get(0), ctx);
            if (first != null && first.isNull()) {
                Expr rest = chain.get(1);
                for (int k = 2; k < chain.size(); k++) {
                    rest = new Expr.Binary(BinaryOp.OR, rest, chain.get(k), JType.BOOLEAN, JType.BOOLEAN, rest.pos());
                }
                ifStmt(new Stmt.If(chain.get(0), i.thenStmt(), null, i.pos()), restoreUses(ctx, savedUses), out);
                ifStmt(new Stmt.If(rest, i.thenStmt(), null, i.pos()), ctx, out);
                return;
            }
            if (first != null) {
                ctx.uses.clear();
                ctx.uses.putAll(savedUses);
            }
        }
        // if (x != null) { ... }  →  if let Some(x) = x { ... }
        if (check != null && !check.isNull()) {
            Map<String, RV> saved = new HashMap<>(ctx.overrides);
            bind(check, ctx);
            RExpr.Block then = block(stmts(i.thenStmt()), ctx, false);
            ctx.overrides.clear();
            ctx.overrides.putAll(saved);
            RExpr orElse = i.elseStmt() == null ? null : elseBranch(i.elseStmt(), ctx);
            out.add(new RStmt.ExprStmt(new RExpr.IfLet("Some(" + check.binding() + ")", check.view().expr(), then, orElse), false));
            return;
        }
        RExpr cond = cond(i.condition(), ctx);
        RExpr.Block then = block(stmts(i.thenStmt()), ctx, false);
        RExpr orElse = i.elseStmt() == null ? null : elseBranch(i.elseStmt(), ctx);
        out.add(new RStmt.ExprStmt(new RExpr.If(cond, then, orElse), false));
    }

    /** {@code { return None; }}（Option を返す関数なら let-else の代わりに ? を使える）。 */
    private static boolean isReturnNone(RExpr.Block b, Ctx ctx) {
        return !ctx.fallible && ctx.ret instanceof RT.Opt && b.tail() == null && b.stmts().size() == 1
                && b.stmts().get(0) instanceof RStmt.ExprStmt es && es.expr() instanceof RExpr.Return r
                && r.value() instanceof RExpr.Path p && p.path().equals("None");
    }

    private static Ctx restoreUses(Ctx ctx, Map<String, Integer> saved) {
        ctx.uses.clear();
        ctx.uses.putAll(saved);
        return ctx;
    }

    private RExpr elseBranch(Stmt s, Ctx ctx) {
        if (s instanceof Stmt.If nested && nullCheck(nested.condition(), ctx) == null) {
            List<RStmt> tmp = new ArrayList<>();
            ifStmt(nested, ctx, tmp);
            if (tmp.size() == 1 && tmp.get(0) instanceof RStmt.ExprStmt e && e.expr() instanceof RExpr.If ri) {
                return ri;
            }
        }
        return block(stmts(s), ctx, false);
    }

    private static List<Stmt> stmts(Stmt s) {
        return s instanceof Stmt.Block b ? b.stmts() : List.of(s);
    }

    /**
     * frontend が Iterable の拡張 for を展開したループ
     * {@code for (Iterator it = xs.iterator(); it.hasNext();) { T x = it.next(); ... }} なら、元の拡張 for に戻す。
     */
    private static Stmt.ForEach iteratorLoop(Stmt.For f) {
        if (f.init().size() != 1 || !(f.init().get(0) instanceof Stmt.LocalVar it) || !it.synthetic()
                || !(it.init() instanceof Expr.Call iterator) || !iterator.method().name().equals("iterator")
                || !f.update().isEmpty() || !(f.body() instanceof Stmt.Block body) || body.stmts().isEmpty()
                || !(body.stmts().get(0) instanceof Stmt.LocalVar var)) {
            return null;
        }
        List<Stmt> rest = body.stmts().subList(1, body.stmts().size());
        return new Stmt.ForEach(var.name(), var.type(), iterator.receiver(), new Stmt.Block(rest, body.pos()), f.pos());
    }

    private void forEach(Stmt.ForEach f, Ctx ctx, List<RStmt> out) {
        RV it = expr(f.iterable(), ctx);
        RExpr iter;
        RT elem;
        switch (it.type()) {
            case RT.ArrayT a -> {
                iter = it.expr();
                elem = a.elem();
            }
            case RT.VecT v -> {
                elem = v.elem().copy() ? v.elem() : new RT.Ref(v.elem(), false);
                iter = v.elem().copy() ? new RExpr.MethodCall(new RExpr.MethodCall(it.expr(), "iter", List.of()), "copied", List.of())
                        : new RExpr.Unary("&", it.expr());
            }
            case RT.Slice v -> {
                elem = v.elem().copy() ? v.elem() : new RT.Ref(v.elem(), false);
                iter = v.elem().copy() ? new RExpr.MethodCall(new RExpr.MethodCall(it.expr(), "iter", List.of()), "copied", List.of())
                        : it.expr();
            }
            default -> {
                out.add(new RStmt.ExprStmt(unsupported(f.pos(), "iterating over " + f.iterable().type().javaName()), true));
                return;
            }
        }
        String rust = Naming.valueName(f.varName());
        ctx.loopDepth++;
        ctx.scopes.push(new HashMap<>());
        ctx.declare(f.varName(), rust, elem);
        RExpr.Block body = block(stmts(f.body()), ctx, false);
        ctx.scopes.pop();
        ctx.loopDepth--;
        out.add(new RStmt.ExprStmt(new RExpr.Template(List.of("for " + rust + " in ", iter, " ", body)), false));
    }

    // ================================================================== null の検査

    /**
     * {@code x == null} / {@code x != null}（x は Option のローカル変数か自分のフィールド）。
     *
     * @param view    x の Option を借用で見る式（{@code x}・{@code self.x.as_deref()}）
     * @param binding Some の中身を束縛する名前
     * @param raw     x の Option そのもの
     * @param key     束縛で置き換える参照（{@link #placeKey}）
     */
    record NullCheck(boolean isNull, RV raw, RV view, String binding, String key, RT inner) {}

    private NullCheck nullCheck(Expr cond, Ctx ctx) {
        if (!(cond instanceof Expr.Binary b) || (b.op() != BinaryOp.EQ && b.op() != BinaryOp.NE)) {
            return null;
        }
        Expr other = isNullLiteral(b.right()) ? b.left() : isNullLiteral(b.left()) ? b.right() : null;
        if (other == null) {
            return null;
        }
        other = unwrapCast(other);
        String key = placeKey(other, ctx);
        if (key == null || key.equals("this") || ctx.overrides.containsKey(key)) {
            return null;
        }
        Map<String, Integer> savedUses = new HashMap<>(ctx.uses);
        RV v = expr(other, ctx);
        if (!(v.type() instanceof RT.Opt)) {
            ctx.uses.clear();
            ctx.uses.putAll(savedUses);
            return null;
        }
        String binding = key.startsWith("l:") && !key.contains(".") ? ctx.lookup(key.substring(2)).rust()
                : Naming.valueName(key.substring(key.lastIndexOf('.') + 1));
        RV view = optView(v);
        return new NullCheck(b.op() == BinaryOp.EQ, v, view, binding, key, ((RT.Opt) view.type()).inner());
    }

    /**
     * null の検査で束縛に置き換えられる参照のキー（ローカル変数 "l:x"、フィールド "this.f"・"l:x.f"、getter の呼び出しも
     * フィールドとして扱う）。それ以外は null。
     */
    private String placeKey(Expr e, Ctx ctx) {
        e = unwrapCast(e);
        return switch (e) {
            case Expr.Local l -> ctx.lookup(l.name()) != null || ctx.overrides.containsKey("l:" + l.name()) ? "l:" + l.name() : null;
            case Expr.This t -> "this";
            case Expr.FieldAccess fa -> {
                String r = placeKey(fa.receiver(), ctx);
                yield r == null ? null : r + "." + fa.name();
            }
            case Expr.Call c when plans.getters.containsKey(c.method().key()) -> {
                String r = c.receiver() == null ? "this" : placeKey(c.receiver(), ctx);
                yield r == null ? null : r + "." + plans.getters.get(c.method().key());
            }
            default -> null;
        };
    }

    /** Option の中身を借用で見る式（Copy ならそのまま、String なら as_deref、それ以外は as_ref）。 */
    private static RV optView(RV v) {
        RT inner = ((RT.Opt) v.type()).inner();
        if (inner.copy()) {
            return RV.of(v.expr(), v.type());
        }
        if (inner instanceof RT.Str) {
            return RV.of(new RExpr.MethodCall(v.expr(), "as_deref", List.of()), new RT.Opt(RT.STR_REF));
        }
        return RV.of(new RExpr.MethodCall(v.expr(), "as_ref", List.of()), new RT.Opt(new RT.Ref(inner, false)));
    }

    private void bind(NullCheck check, Ctx ctx) {
        ctx.overrides.put(check.key(), RV.of(new RExpr.Path(check.binding()), check.inner()));
    }

    private static boolean isNullLiteral(Expr e) {
        return e instanceof Expr.Literal l && l.value() == null;
    }

    private static Expr unwrapCast(Expr e) {
        while (e instanceof Expr.Cast c) {
            e = c.expr();
        }
        return e;
    }

    /** 条件式。&& の連鎖の中の {@code x != null} は、後ろの条件を is_some_and に入れる。 */
    RExpr cond(Expr e, Ctx ctx) {
        if (e instanceof Expr.Binary b && (b.op() == BinaryOp.AND || b.op() == BinaryOp.OR)) {
            List<Expr> chain = new ArrayList<>();
            flatten(e, b.op(), chain);
            return chain(chain, 0, b.op(), ctx);
        }
        RV v = expr(e, ctx);
        return v.expr();
    }

    private static void flatten(Expr e, BinaryOp op, List<Expr> out) {
        if (e instanceof Expr.Binary b && b.op() == op) {
            flatten(b.left(), op, out);
            flatten(b.right(), op, out);
        } else {
            out.add(e);
        }
    }

    private RExpr chain(List<Expr> items, int from, BinaryOp op, Ctx ctx) {
        RExpr acc = null;
        for (int i = from; i < items.size(); i++) {
            Expr item = items.get(i);
            NullCheck check = i + 1 < items.size() ? nullCheck(item, ctx) : null;
            // a && x != null && rest → a && x.is_some_and(|x| rest)、a || x == null || rest → a || x.is_none_or(|x| rest)
            if (check != null && check.isNull() == (op == BinaryOp.OR)) {
                Map<String, RV> saved = new HashMap<>(ctx.overrides);
                ctx.overrides.put(check.key(), RV.of(new RExpr.Path(check.binding()), check.inner()));
                RExpr rest = chain(items, i + 1, op, ctx);
                ctx.overrides.clear();
                ctx.overrides.putAll(saved);
                RExpr call = new RExpr.MethodCall(check.view().expr(), op == BinaryOp.AND ? "is_some_and" : "is_none_or",
                        List.of(RExpr.Closure.of(List.of(check.binding()), rest)));
                return acc == null ? call : new RExpr.Binary(op.symbol(), acc, call);
            }
            RExpr x;
            if (check != null) {
                x = new RExpr.MethodCall(check.view().expr(), check.isNull() ? "is_none" : "is_some", List.of());
            } else {
                x = cond(item, ctx);
            }
            acc = acc == null ? x : new RExpr.Binary(op.symbol(), acc, x);
        }
        return acc;
    }

    // ================================================================== 式

    RV expr(Expr e, Ctx ctx) {
        if (!ctx.overrides.isEmpty() && !(e instanceof Expr.Literal)) {
            String key = placeKey(e, ctx);
            RV o = key == null ? null : ctx.overrides.get(key);
            if (o != null) {
                return o;
            }
        }
        return switch (e) {
            case Expr.Literal l -> literal(l);
            case Expr.Local l -> local(l, ctx);
            case Expr.This t -> RV.of(new RExpr.Path("self"), selfType(ctx));
            case Expr.FieldAccess fa -> fieldAccess(fa, ctx);
            case Expr.StaticField sf -> staticField(sf);
            case Expr.Binary b -> binary(b, ctx);
            case Expr.Unary u -> unary(u, ctx);
            case Expr.Conditional c -> conditional(c, ctx);
            case Expr.Cast c -> cast(c, ctx);
            case Expr.Call c -> call(c, ctx);
            case Expr.New n -> newExpr(n, ctx);
            case Expr.Assign a -> assign(a, ctx);
            case Expr.CompoundAssign a -> compoundAssign(a, ctx);
            case Expr.IncDec i -> incDec(i, ctx);
            case Expr.StringConcat s -> concat(s, ctx);
            default -> RV.of(unsupported(e.pos(), "expression " + e.getClass().getSimpleName()), new RT.Unknown(e.type().javaName()));
        };
    }

    private RT selfType(Ctx ctx) {
        RT t = types.owned(new JType.ClassType(ctx.owner.qualifiedName()), false);
        return t.copy() ? t : new RT.Ref(t, false);
    }

    private RV literal(Expr.Literal l) {
        Object v = l.value();
        return switch (v) {
            case null -> RV.of(new RExpr.Path("None"), new RT.Null());
            case String s -> RV.of(new RExpr.Lit(rustString(s)), RT.STR_REF);
            case Boolean b -> RV.of(new RExpr.Lit(b.toString()), RT.BOOL);
            case Integer i -> RV.of(new RExpr.Lit(i.toString()), RT.I32);
            case Long n -> RV.of(new RExpr.Lit(n.toString()), RT.I64);
            case Double d -> RV.of(new RExpr.Lit(floatText(d.toString())), new RT.Prim("f64"));
            case Float f -> RV.of(new RExpr.Lit(floatText(f.toString())), new RT.Prim("f32"));
            case Character c -> RV.of(new RExpr.Lit(Integer.toString(c)), new RT.Prim("u16"));
            default -> RV.of(unsupported(l.pos(), "literal " + v), new RT.Unknown("literal"));
        };
    }

    private static String floatText(String s) {
        return s.contains(".") || s.contains("E") ? s : s + ".0";
    }

    static String rustString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u{%x}", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private RV local(Expr.Local l, Ctx ctx) {
        RV o = ctx.overrides.get("l:" + l.name());
        if (o != null) {
            return o;
        }
        Local local = ctx.lookup(l.name());
        if (local == null) {
            return RV.of(unsupported(l.pos(), "unknown variable " + l.name()), new RT.Unknown(l.name()));
        }
        int remaining = ctx.uses.merge(l.name(), -1, Integer::sum);
        boolean movable = remaining <= 0 && ctx.loopDepth == local.loopDepth();
        return new RV(new RExpr.Path(local.rust()), local.type(), Place.LOCAL, movable, false);
    }

    private RV fieldAccess(Expr.FieldAccess fa, Ctx ctx) {
        if (ctx.hooks != null) {
            RV hooked = ctx.hooks.fieldAccess(fa, ctx);
            if (hooked != null) {
                return hooked;
            }
        }
        RV recv = expr(fa.receiver(), ctx);
        RT t = types.field(fa.owner(), fa.name());
        if (t == null) {
            return RV.of(unsupported(fa.pos(), "field " + fa.name()), new RT.Unknown(fa.name()));
        }
        return new RV(new RExpr.Field(recv.expr(), Naming.valueName(fa.name())), t, Place.FIELD, false, false);
    }

    private RV staticField(Expr.StaticField sf) {
        if (model.is(sf.owner(), SpringModel.Role.ENUM)) {
            RT t = types.owned(new JType.ClassType(sf.owner()), false);
            return RV.of(new RExpr.Path(t.text(imports) + "::" + variantName(sf.name())), t);
        }
        Decl.TypeDecl owner = model.types.get(sf.owner());
        if (owner != null) {
            for (Decl.FieldDecl f : owner.fields()) {
                if (f.name().equals(sf.name()) && f.constantValue() != null) {
                    return literal(new Expr.Literal(f.constantValue(), f.type(), sf.pos()));
                }
            }
        }
        return RV.of(unsupported(sf.pos(), "static field " + sf.owner() + "." + sf.name()), new RT.Unknown(sf.name()));
    }

    /** enum 定数名 → バリアント名（ACTIVE → Active、IN_PROGRESS → InProgress）。 */
    static String variantName(String constant) {
        if (!constant.equals(constant.toUpperCase(java.util.Locale.ROOT))) {
            return Character.toUpperCase(constant.charAt(0)) + constant.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : constant.split("_")) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private RV binary(Expr.Binary b, Ctx ctx) {
        switch (b.op()) {
            case AND, OR -> {
                return RV.of(cond(b, ctx), RT.BOOL);
            }
            case EQ, NE -> {
                return equality(b, ctx);
            }
            case USHR -> {
                return RV.of(unsupported(b.pos(), "operator >>>"), RT.I32);
            }
            default -> { }
        }
        RT operand = types.owned(b.operandType(), false);
        RExpr left = coerce(expr(b.left(), ctx), operand);
        RExpr right = coerce(expr(b.right(), ctx), operand);
        RT result = b.op().isComparison() ? RT.BOOL : operand;
        return RV.of(new RExpr.Binary(b.op().symbol(), left, right), result);
    }

    private RV equality(Expr.Binary b, Ctx ctx) {
        boolean eq = b.op() == BinaryOp.EQ;
        if (isNullLiteral(b.left()) || isNullLiteral(b.right())) {
            Expr other = isNullLiteral(b.right()) ? b.left() : b.right();
            RV v = expr(other, ctx);
            if (v.type() instanceof RT.Opt) {
                return RV.of(new RExpr.MethodCall(v.expr(), eq ? "is_none" : "is_some", List.of()), RT.BOOL);
            }
            return RV.of(new RExpr.Lit(eq ? "false" : "true"), RT.BOOL);
        }
        RV left = expr(b.left(), ctx);
        RV right = expr(b.right(), ctx);
        if (left.type() instanceof RT.Str || left.type() instanceof RT.StrRef) {
            diags.report(DiagnosticCode.LOSSY_STRING_IDENTITY, b.pos(), "string == compares the contents in Rust (identity in Java)");
        }
        return RV.of(new RExpr.Binary(eq ? "==" : "!=", compareOperand(left, right), compareOperand(right, left)), RT.BOOL);
    }

    /** == の片側（もう片側が Option なら Some で包む）。 */
    private RExpr compareOperand(RV v, RV other) {
        if (other.type() instanceof RT.Opt o && !(v.type() instanceof RT.Opt)) {
            return new RExpr.Call(new RExpr.Path("Some"), List.of(coerce(v, o.inner())));
        }
        if (v.type() instanceof RT.Prim p && other.type() instanceof RT.Prim q && !p.equals(q)) {
            return coerce(v, wider(p, q));
        }
        return v.expr();
    }

    private static RT wider(RT.Prim a, RT.Prim b) {
        List<String> order = List.of("i8", "i16", "u16", "i32", "i64", "f32", "f64");
        return order.indexOf(a.name()) >= order.indexOf(b.name()) ? a : b;
    }

    private RV unary(Expr.Unary u, Ctx ctx) {
        return switch (u.op()) {
            case NOT -> RV.of(new RExpr.Unary("!", cond(u.operand(), ctx)), RT.BOOL);
            case NEG -> {
                RV v = expr(u.operand(), ctx);
                yield RV.of(new RExpr.Unary("-", v.expr()), v.type());
            }
            case PLUS -> expr(u.operand(), ctx);
            case BIT_NOT -> {
                RV v = expr(u.operand(), ctx);
                yield RV.of(new RExpr.Unary("!", v.expr()), v.type());
            }
        };
    }

    private RV conditional(Expr.Conditional c, Ctx ctx) {
        NullCheck check = nullCheck(c.condition(), ctx);
        if (check != null) {
            Expr whenSome = check.isNull() ? c.whenFalse() : c.whenTrue();
            Expr whenNone = check.isNull() ? c.whenTrue() : c.whenFalse();
            Map<String, RV> saved = new HashMap<>(ctx.overrides);
            ctx.overrides.put(check.key(), RV.of(new RExpr.Path(check.binding()), check.inner()));
            RV some = expr(whenSome, ctx);
            ctx.overrides.clear();
            ctx.overrides.putAll(saved);
            if (isNullLiteral(whenNone)) {
                // x == null ? null : f(x)  →  x.map(|x| f(x))
                RT inner = some.type() instanceof RT.Opt o ? o.inner() : some.type();
                RExpr body = some.type() instanceof RT.Opt ? some.expr() : some.expr();
                String method = some.type() instanceof RT.Opt ? "and_then" : "map";
                return RV.of(new RExpr.MethodCall(check.view().expr(), method, List.of(RExpr.Closure.of(List.of(check.binding()), body))),
                        new RT.Opt(inner));
            }
            RV none = expr(whenNone, ctx);
            RT t = unify(some.type(), none.type());
            if (some.expr() instanceof RExpr.Path p && p.path().equals(check.binding()) && none.type().copy()
                    && (none.expr() instanceof RExpr.Lit || none.expr() instanceof RExpr.Path)) {
                // x == null ? default : x  →  x.unwrap_or(default)
                RExpr d = coerce(none, some.type());
                return RV.of(new RExpr.MethodCall(check.view().expr(), "unwrap_or", List.of(d)), some.type());
            }
            if (none.type().copy() && (none.expr() instanceof RExpr.Lit || none.expr() instanceof RExpr.Path) && sameType(some.type(), t)) {
                // x == null ? default : f(x)  →  x.map_or(default, |x| f(x))
                return RV.of(new RExpr.MethodCall(check.view().expr(), "map_or", List.of(coerce(none, t),
                        RExpr.Closure.of(List.of(check.binding()), coerce(some, t)))), t);
            }
            RExpr.Block then = inline(coerce(some, t));
            RExpr.Block orElse = inline(coerce(none, t));
            return RV.of(new RExpr.IfLet("Some(" + check.binding() + ")", check.view().expr(), then, orElse), t);
        }
        RExpr cond = cond(c.condition(), ctx);
        RV a = expr(c.whenTrue(), ctx);
        RV b = expr(c.whenFalse(), ctx);
        RT t = unify(a.type(), b.type());
        return RV.of(new RExpr.If(cond, inline(coerce(a, t)), inline(coerce(b, t))), t);
    }

    private static RExpr.Block inline(RExpr e) {
        return new RExpr.Block(List.of(), e, null, true);
    }

    /** 条件式の 2 つの枝の型を合わせる。 */
    private static RT unify(RT a, RT b) {
        if (a instanceof RT.Null) {
            return b instanceof RT.Opt ? RT.owned(b) : new RT.Opt(RT.owned(b));
        }
        if (b instanceof RT.Null) {
            return a instanceof RT.Opt ? RT.owned(a) : new RT.Opt(RT.owned(a));
        }
        if (sameType(a, b)) {
            return a;
        }
        if (a instanceof RT.Opt || b instanceof RT.Opt) {
            return new RT.Opt(RT.owned(RT.unwrapOpt(a)));
        }
        return RT.owned(a);
    }

    private RV cast(Expr.Cast c, Ctx ctx) {
        RV v = expr(c.expr(), ctx);
        if (c.type() instanceof JType.Primitive p) {
            RT target = TypeResolver.primitive(p.kind());
            if (v.type() instanceof RT.Opt o && o.inner() instanceof RT.Prim) {
                // null になりうるボックス型のアンボクシング
                diags.report(DiagnosticCode.LOSSY_NULL, c.pos(), "unboxing a @Nullable value (panics if null)");
                return RV.of(new RExpr.MethodCall(v.expr(), "unwrap", List.of()), o.inner());
            }
            if (v.type() instanceof RT.Prim) {
                return RV.of(coerce(v, target), target);
            }
            return v;
        }
        if (v.type() instanceof RT.Unknown || v.type() instanceof RT.Null) {
            return new RV(v.expr(), types.owned(c.type(), v.type() instanceof RT.Null), v.place(), v.movable(), v.errResult());
        }
        return v;
    }

    private RV assign(Expr.Assign a, Ctx ctx) {
        Expr target = a.target();
        if (target instanceof Expr.Local l) {
            Local local = ctx.lookup(l.name());
            RV v = expr(a.value(), ctx);
            return RV.of(new RExpr.Assign(new RExpr.Path(local.rust()), "=", coerce(v, local.type())), RT.UNIT);
        }
        if (target instanceof Expr.FieldAccess fa) {
            RV place = fieldAccess(fa, ctx);
            RV v = expr(a.value(), ctx);
            return RV.of(new RExpr.Assign(place.expr(), "=", coerce(v, place.type())), RT.UNIT);
        }
        return RV.of(unsupported(a.pos(), "assignment to " + target.getClass().getSimpleName()), RT.UNIT);
    }

    private RV compoundAssign(Expr.CompoundAssign a, Ctx ctx) {
        RV place = a.target() instanceof Expr.Local l ? RV.of(new RExpr.Path(ctx.lookup(l.name()).rust()), ctx.lookup(l.name()).type())
                : expr(a.target(), ctx);
        RV v = expr(a.value(), ctx);
        if (a.op() == BinaryOp.ADD && place.type() instanceof RT.Str) {
            return RV.of(new RExpr.MethodCall(place.expr(), "push_str", List.of(coerce(v, RT.STR_REF))), RT.UNIT);
        }
        return RV.of(new RExpr.Assign(place.expr(), a.op().symbol() + "=", coerce(v, place.type())), RT.UNIT);
    }

    private RV incDec(Expr.IncDec i, Ctx ctx) {
        RV place = i.target() instanceof Expr.Local l ? RV.of(new RExpr.Path(ctx.lookup(l.name()).rust()), ctx.lookup(l.name()).type())
                : expr(i.target(), ctx);
        return RV.of(new RExpr.Assign(place.expr(), i.increment() ? "+=" : "-=", new RExpr.Lit("1")), RT.UNIT);
    }

    private RV concat(Expr.StringConcat s, Ctx ctx) {
        StringBuilder fmt = new StringBuilder();
        List<RExpr> args = new ArrayList<>();
        for (Expr part : s.parts()) {
            if (part instanceof Expr.Literal l && l.value() != null) {
                fmt.append(l.value().toString().replace("{", "{{").replace("}", "}}"));
                continue;
            }
            RV v = expr(part, ctx);
            fmt.append("{}");
            args.add(displayArg(v, part));
        }
        inlineFormatArgs(fmt, args);
        List<RExpr> macroArgs = new ArrayList<>();
        macroArgs.add(new RExpr.Lit(rustString(fmt.toString())));
        macroArgs.addAll(args);
        return RV.of(new RExpr.Macro("format", macroArgs), RT.STR);
    }

    /** format! の引数のうち変数だけのものを書式の中に書く（{@code format!("{}", x)} → {@code format!("{x}")}）。 */
    static void inlineFormatArgs(StringBuilder fmt, List<RExpr> args) {
        StringBuilder out = new StringBuilder();
        List<RExpr> rest = new ArrayList<>();
        int arg = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '{' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '{') {
                out.append("{{");
                i++;
            } else if (c == '}' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '}') {
                out.append("}}");
                i++;
            } else if (c == '{' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '}') {
                RExpr a = args.get(arg++);
                if (a instanceof RExpr.Path p && p.path().matches("[a-z_][a-z0-9_]*") && !p.path().equals("self")) {
                    out.append('{').append(p.path()).append('}');
                } else {
                    out.append("{}");
                    rest.add(a);
                }
                i++;
            } else {
                out.append(c);
            }
        }
        fmt.setLength(0);
        fmt.append(out);
        args.clear();
        args.addAll(rest);
    }

    /** 文字列連結の値（Java の String.valueOf と同じ表示にする。null は "null"、enum は name()）。 */
    private RExpr displayArg(RV v, Expr part) {
        RT t = v.type();
        if (t instanceof RT.Opt o) {
            RV view = optView(v);
            if (o.inner() instanceof RT.Str || o.inner() instanceof RT.StrRef) {
                return new RExpr.MethodCall(view.expr(), "unwrap_or", List.of(new RExpr.Lit("\"null\"")));
            }
            RExpr some = displayArg(RV.of(new RExpr.Path("v"), ((RT.Opt) view.type()).inner()), part);
            return new RExpr.MethodCall(view.expr(), "map_or_else", List.of(
                    RExpr.Closure.of(List.of(), new RExpr.MethodCall(new RExpr.Lit("\"null\""), "to_string", List.of())),
                    RExpr.Closure.of(List.of("v"), new RExpr.MethodCall(some, "to_string", List.of()))));
        }
        RT inner = t instanceof RT.Ref r ? r.inner() : t;
        if (inner instanceof RT.Named n) {
            switch (n.kind()) {
                case ENUM -> {
                    return new RExpr.MethodCall(v.expr(), "name", List.of());
                }
                case VALUE -> {
                    if (n.name().equals("NaiveDateTime")) {
                        diags.report(DiagnosticCode.LOSSY_STRING_IDENTITY, part.pos(),
                                "LocalDateTime is printed as 'yyyy-MM-dd HH:mm:ss' in Rust ('yyyy-MM-ddTHH:mm' in Java)");
                    }
                    return v.expr();
                }
                default -> {
                    return unsupported(part.pos(), "converting " + n.javaName() + " to a string");
                }
            }
        }
        return v.expr();
    }

    // ================================================================== 呼び出し

    private RV call(Expr.Call c, Ctx ctx) {
        if (ctx.hooks != null) {
            RV hooked = ctx.hooks.call(c, ctx);
            if (hooked != null) {
                return hooked;
            }
        }
        MethodRef m = c.method();
        String key = m.key();
        if (plans.mapperFns.containsKey(key)) {
            return mapperCall(c, plans.mapperFns.get(key), ctx);
        }
        if (plans.getters.containsKey(key)) {
            RV recv = receiver(c, ctx);
            String field = plans.getters.get(key);
            RT t = types.field(m.owner(), field);
            return new RV(new RExpr.Field(recv.expr(), Naming.valueName(field)), t, Place.FIELD, false, false);
        }
        if (plans.setters.containsKey(key)) {
            RV recv = receiver(c, ctx);
            String field = plans.setters.get(key);
            RT t = types.field(m.owner(), field);
            RV v = expr(c.args().get(0), ctx);
            return RV.of(new RExpr.Assign(new RExpr.Field(recv.expr(), Naming.valueName(field)), "=", coerce(v, t)), RT.UNIT);
        }
        Plans.Method plan = plans.methods.get(key);
        if (plan != null) {
            return programCall(c, plan, ctx);
        }
        if (model.is(m.owner(), SpringModel.Role.ENUM) && m.isStatic() && m.name().equals("values") && m.paramTypes().isEmpty()) {
            RT t = types.owned(new JType.ClassType(m.owner()), false);
            int n = model.types.get(m.owner()).enumConstants().size();
            return RV.of(new RExpr.Call(new RExpr.Path(t.text(imports) + "::values"), List.of()), new RT.ArrayT(t, n));
        }
        RV lib = LibraryCalls.lower(this, c, ctx);
        if (lib != null) {
            return lib;
        }
        return RV.of(unsupported(c.pos(), "call to " + m.owner() + "." + m.name()), new RT.Unknown(m.name()));
    }

    RV receiver(Expr.Call c, Ctx ctx) {
        if (c.receiver() == null) {
            return RV.of(new RExpr.Path("self"), selfType(ctx));
        }
        return expr(c.receiver(), ctx);
    }

    private RV mapperCall(Expr.Call c, Plans.MapperFn fn, Ctx ctx) {
        if (ctx.conn == null) {
            return RV.of(unsupported(c.pos(), "Mapper call outside a method that has a database connection"), fn.ret());
        }
        imports.add(fn.modulePath());
        List<RExpr> args = new ArrayList<>();
        args.add(new RExpr.Path(ctx.conn));
        for (int i = 0; i < c.args().size(); i++) {
            args.add(coerce(expr(c.args().get(i), ctx), fn.params().get(i)));
        }
        RExpr call = new RExpr.Call(new RExpr.Path(fn.module() + "::" + fn.rustName()), args);
        return RV.of(propagate(new RExpr.Await(call), ctx), fn.ret());
    }

    private RV programCall(Expr.Call c, Plans.Method plan, Ctx ctx) {
        MethodRef m = c.method();
        List<RExpr> args = new ArrayList<>();
        String name = plan.rustName();
        boolean external = !m.owner().equals(ctx.owner.qualifiedName());
        if (plan.needsConn() && !external) {
            if (ctx.conn == null || plan.helperName() == null) {
                return RV.of(unsupported(c.pos(), "call to " + m.name() + " needs a database connection"), plan.ret());
            }
            name = plan.helperName();
            args.add(new RExpr.Path(ctx.conn));
        } else if (plan.needsConn() && ctx.inTx && ctx.conn != null && plan.helperName() != null && plans.tx.containsKey(m.key())
                && plans.tx.get(m.key()).joins()) {
            // ほかのサービスの @Transactional（propagation = REQUIRED など）は、呼び出し元のトランザクションに参加する。
            name = plan.helperName();
            args.add(new RExpr.Path(ctx.conn));
        }
        for (int i = 0; i < c.args().size(); i++) {
            args.add(coerce(expr(c.args().get(i), ctx), plan.params().get(i)));
        }
        RExpr call;
        if (plan.self() == Plans.SelfKind.NONE) {
            Decl.TypeDecl owner = model.types.get(m.owner());
            RT ownerType = types.owned(new JType.ClassType(owner.qualifiedName()), false);
            String prefix = owner.qualifiedName().equals(ctx.owner.qualifiedName()) ? "Self" : ownerType.text(imports);
            call = new RExpr.Call(new RExpr.Path(prefix + "::" + name), args);
        } else {
            RV recv = receiver(c, ctx);
            call = new RExpr.MethodCall(recv.expr(), name, args);
        }
        if (plan.needsConn()) {
            call = new RExpr.Await(call);
        }
        if (plan.fallible()) {
            return new RV(propagate(call, ctx), plan.ret(), Place.NONE, false, !ctx.unwrapErrors);
        }
        return RV.of(call, plan.ret());
    }

    /** Result を返す呼び出し（{@code call?}、テストでは {@code call.unwrap()}）。 */
    private static RExpr propagate(RExpr call, Ctx ctx) {
        return ctx.unwrapErrors ? new RExpr.MethodCall(call, "unwrap", List.of()) : new RExpr.Try(call);
    }

    private RV newExpr(Expr.New n, Ctx ctx) {
        String owner = n.constructor().owner();
        SpringModel.Role role = model.role(owner);
        if (role == SpringModel.Role.EXCEPTION) {
            Plans.ErrorVariant variant = plans.errors.get(owner);
            imports.add("crate::error::Error");
            List<RExpr> args = new ArrayList<>();
            for (int i = 0; i < n.args().size() && i < variant.fields().size(); i++) {
                args.add(coerce(expr(n.args().get(i), ctx), variant.fields().get(i)));
            }
            RExpr path = new RExpr.Path("Error::" + variant.name());
            return RV.of(args.isEmpty() ? path : new RExpr.Call(path, args), new RT.ErrorValue());
        }
        RT t = types.owned(new JType.ClassType(owner), false);
        if (role == SpringModel.Role.RECORD) {
            Decl.TypeDecl decl = model.types.get(owner);
            List<RExpr.FieldInit> fields = new ArrayList<>();
            for (int i = 0; i < n.args().size(); i++) {
                Decl.Param comp = decl.recordComponents().get(i);
                RT ft = types.field(owner, comp.name());
                fields.add(new RExpr.FieldInit(Naming.valueName(comp.name()), coerce(expr(n.args().get(i), ctx), ft)));
            }
            return RV.of(new RExpr.StructLit(t.text(imports), fields), t);
        }
        if (isEntityLike(role) && n.args().isEmpty()) {
            return RV.of(new RExpr.Call(new RExpr.Path(t.text(imports) + "::default"), List.of()), t);
        }
        if ((owner.equals("java.util.ArrayList") || owner.equals("java.util.LinkedList")) && n.args().isEmpty()) {
            RT elem = n.type() instanceof JType.Parameterized p && !p.args().isEmpty() ? types.owned(p.args().get(0), false)
                    : new RT.Unknown("raw List");
            return RV.of(new RExpr.Path("Vec::new()"), new RT.VecT(elem));
        }
        return RV.of(unsupported(n.pos(), "new " + owner), t);
    }

    // ================================================================== 型を合わせる

    /** 式 v を型 target の値にする。 */
    RExpr coerce(RV v, RT target) {
        RT s = v.type();
        RExpr e = v.expr();
        if (target instanceof RT.Unknown || s instanceof RT.Unknown) {
            return e;
        }
        if (s instanceof RT.Null) {
            return target instanceof RT.Opt ? new RExpr.Path("None") : e;
        }
        if (sameType(s, target)) {
            return moveOrClone(v);
        }
        if (target instanceof RT.Opt to) {
            if (s instanceof RT.Opt so) {
                return optToOpt(v, so.inner(), to.inner());
            }
            return new RExpr.Call(new RExpr.Path("Some"), List.of(coerce(v, to.inner())));
        }
        if (s instanceof RT.Opt so) {
            RV view = optView(v);
            RT inner = ((RT.Opt) view.type()).inner();
            return coerce(RV.of(new RExpr.MethodCall(view.expr(), "unwrap", List.of()), inner), target);
        }
        switch (target) {
            case RT.StrRef x -> {
                if (s instanceof RT.Str) {
                    return new RExpr.Unary("&", e);
                }
            }
            case RT.Str x -> {
                if (s instanceof RT.StrRef) {
                    return new RExpr.MethodCall(e, "to_string", List.of());
                }
            }
            case RT.Ref r -> {
                if (sameType(s, r.inner())) {
                    return new RExpr.Unary(r.mut() ? "&mut " : "&", e);
                }
                if (s instanceof RT.Ref sr && sameType(sr.inner(), r.inner())) {
                    return e;
                }
            }
            case RT.Named n -> {
                if (s instanceof RT.Ref sr && sameType(sr.inner(), n)) {
                    return n.copy() ? new RExpr.Unary("*", e) : new RExpr.MethodCall(e, "clone", List.of());
                }
            }
            case RT.Slice sl -> {
                if (s instanceof RT.VecT) {
                    return new RExpr.Unary("&", e);
                }
            }
            case RT.VecT vt -> {
                if (s instanceof RT.Slice || s instanceof RT.ArrayT) {
                    return new RExpr.MethodCall(e, "to_vec", List.of());
                }
            }
            case RT.Prim p -> {
                if (s instanceof RT.Prim sp) {
                    return numeric(e, sp, p);
                }
            }
            default -> { }
        }
        return e;
    }

    private RExpr optToOpt(RV v, RT from, RT to) {
        RExpr e = v.expr();
        if (sameType(from, to)) {
            return moveOrClone(v);
        }
        if (from instanceof RT.Str && to instanceof RT.StrRef) {
            return new RExpr.MethodCall(e, "as_deref", List.of());
        }
        if (from instanceof RT.StrRef && to instanceof RT.Str) {
            return new RExpr.MethodCall(e, "map", List.of(new RExpr.Path("str::to_string")));
        }
        if (to instanceof RT.Ref r && sameType(from, r.inner())) {
            return new RExpr.MethodCall(e, "as_ref", List.of());
        }
        if (from instanceof RT.Ref r && sameType(r.inner(), to)) {
            return new RExpr.MethodCall(e, to.copy() ? "copied" : "cloned", List.of());
        }
        if (from instanceof RT.Prim fp && to instanceof RT.Prim tp) {
            return new RExpr.MethodCall(e, "map", List.of(new RExpr.Path(tp.name() + "::from")));
        }
        return e;
    }

    private static RExpr numeric(RExpr e, RT.Prim from, RT.Prim to) {
        if (from.equals(to) || e instanceof RExpr.Lit l && l.text().matches("-?[0-9]+") && !to.name().startsWith("f")) {
            return e;
        }
        List<String> widening = List.of("i8>i16", "i8>i32", "i8>i64", "i16>i32", "i16>i64", "i32>i64", "u16>i32", "u16>i64",
                "i32>f64", "i8>f64", "i16>f64", "f32>f64", "i8>f32", "i16>f32", "u16>f64");
        if (widening.contains(from.name() + ">" + to.name())) {
            return new RExpr.Call(new RExpr.Path(to.name() + "::from"), List.of(e));
        }
        return new RExpr.Cast(e, new io.github.ykwyuta.j2r.rir.RType(to.name()));
    }

    /** 同じ型の値を使う。最後に使うローカル変数と一時的な値は移動し、それ以外の Copy でない値は clone する。 */
    private static RExpr moveOrClone(RV v) {
        if (v.type().copy() || v.place() == Place.NONE || v.place() == Place.LOCAL && v.movable()) {
            return v.expr();
        }
        return new RExpr.MethodCall(v.expr(), "clone", List.of());
    }

    static boolean sameType(RT a, RT b) {
        return switch (a) {
            case RT.Named x -> b instanceof RT.Named y && x.name().equals(y.name());
            case RT.Opt x -> b instanceof RT.Opt y && sameType(x.inner(), y.inner());
            case RT.VecT x -> b instanceof RT.VecT y && sameType(x.elem(), y.elem());
            case RT.Slice x -> b instanceof RT.Slice y && sameType(x.elem(), y.elem());
            case RT.Ref x -> b instanceof RT.Ref y && x.mut() == y.mut() && sameType(x.inner(), y.inner());
            case RT.ArrayT x -> b instanceof RT.ArrayT y && sameType(x.elem(), y.elem());
            default -> a.equals(b);
        };
    }

    // ================================================================== 補助

    /** getter / setter のある普通のクラス（エンティティ・フォーム）。 */
    static boolean isEntityLike(SpringModel.Role role) {
        return role == SpringModel.Role.ENTITY || role == SpringModel.Role.FORM;
    }

    RExpr unsupported(SourcePos pos, String what) {
        diags.report(DiagnosticCode.SPRING_UNSUPPORTED, pos, what + " is not supported yet");
        return new RExpr.Macro("todo", List.of(new RExpr.Lit(rustString(what))));
    }

    Imports imports() {
        return imports;
    }

    TypeResolver types() {
        return types;
    }

    Diagnostics diags() {
        return diags;
    }
}
