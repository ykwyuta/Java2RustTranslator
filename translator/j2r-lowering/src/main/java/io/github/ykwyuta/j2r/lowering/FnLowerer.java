package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.LocalMutability;
import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.BinaryOp;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.JirVisitor;
import io.github.ykwyuta.j2r.jir.MethodRef;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import io.github.ykwyuta.j2r.rir.RustPrinter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1 つの関数本体（メソッド・コンストラクタ・ラムダ・static 初期化）の JIR → RIR 変換。
 * 規則は docs/03-translation-rules.md。
 */
final class FnLowerer {
    private final LowerContext cx;
    private final ProgramIndex.TypeInfo owner;
    private final Imports imp;
    private final Conversions conv;
    /** Java の戻り値型（ラムダでは本体の return の値の型は様々なので void 以外なら OBJECT 扱い）。 */
    private final JType returnType;
    /** ラムダの本体なら true（戻り値は JObject にボクシングして返す）。 */
    private final boolean lambdaBody;
    /** try のクロージャが返す Flow の型引数（関数の Rust の戻り値型）。 */
    private final RType flowType;
    private final Set<String> mutableLocals;
    /** try 文を含む関数では、初期化子のないローカル変数を既定値で初期化する（クロージャに捕捉されるため）。 */
    private final boolean defaultInitLocals;
    /** この関数が JResult を返すか（例外を送出しうるメソッド。ラムダの本体は常に JResult）。 */
    private final boolean throwing;
    /** 関数の本体（try のクロージャの外）で ? や return Err(..) を生成したか（JResult を返す必要がある）。 */
    private boolean fallible;

    private final Deque<RustFrame> frames = new ArrayDeque<>();
    private final Deque<JavaTarget> targets = new ArrayDeque<>();
    private final Deque<TryCtx> tries = new ArrayDeque<>();
    private final Deque<RustFrame> yieldTargets = new ArrayDeque<>();
    private int temps;
    private int labels;
    private int tryDepth;

    FnLowerer(LowerContext cx, ProgramIndex.TypeInfo owner, Imports imp, JType returnType, boolean lambdaBody,
              List<Decl.Param> params, Stmt body, boolean throwing) {
        this.cx = cx;
        this.owner = owner;
        this.imp = imp;
        this.throwing = throwing || lambdaBody;
        this.conv = new Conversions(cx, imp, this::markFallible);
        this.returnType = returnType;
        this.lambdaBody = lambdaBody;
        this.flowType = lambdaBody ? new RType("JObject") : cx.types().rust(returnType);
        this.mutableLocals = body == null ? Set.of() : LocalMutability.mutableLocals(params, body);
        this.defaultInitLocals = body != null && LocalMutability.containsTry(body);
    }

    Conversions conversions() {
        return conv;
    }

    /** 関数の本体で例外を送出しうる操作（? / return Err）を生成したか。 */
    boolean fallible() {
        return fallible;
    }

    /** 例外を関数の外へ伝える操作を生成した（try のクロージャの中なら、そのクロージャが受け止める）。 */
    private void markFallible() {
        if (tryDepth == 0) {
            fallible = true;
        }
    }

    private RExpr tryOp(RExpr e) {
        return conv.tryOp(e);
    }

    /** {@code return Err(..)} など、例外を送出する式（型は !）。 */
    private RExpr raise(RExpr result) {
        markFallible();
        return new RExpr.Return(result);
    }

    /** {@code return jrt::throw("クラス", None)}。 */
    private RExpr raiseJdk(String exceptionClass) {
        return raise(new RExpr.Call(new RExpr.Path("jrt::throw"), List.of(new RExpr.Lit("\"" + exceptionClass + "\""), new RExpr.Path("None"))));
    }

    boolean isMutable(String javaName) {
        return mutableLocals.contains(javaName);
    }

    // ================================================================== 関数本体

    /**
     * 本体の文を変換する。allowTail なら末尾の {@code return v;} を末尾式 {@code v} にする。
     * パターン変数（instanceof / case の束縛変数）は先頭でまとめて宣言する。
     */
    RExpr.Block body(List<Stmt> stmts, boolean allowTail) {
        List<RStmt> out = new ArrayList<>(declareBindings(stmts));
        RExpr tail = null;
        int n = stmts.size();
        if (allowTail && !lambdaBody && n > 0 && stmts.get(n - 1) instanceof Stmt.Return r && r.value() != null) {
            for (int i = 0; i < n - 1; i++) {
                stmt(stmts.get(i), out);
            }
            tail = value(r.value());
        } else {
            for (Stmt s : stmts) {
                stmt(s, out);
            }
            // Java では値を返すメソッドの末尾には到達しない（コンパイラが保証する）が、try の後など Rust が
            // それを判断できない場合があるので明示する。
            boolean returnsValue = !(returnType instanceof JType.Void);
            boolean endsWithExit = n > 0 && (stmts.get(n - 1) instanceof Stmt.Return || stmts.get(n - 1) instanceof Stmt.Throw);
            if (returnsValue && !lambdaBody && !endsWithExit) {
                tail = new RExpr.Macro("unreachable", List.of());
            } else if (!returnsValue && throwing && !lambdaBody && !endsWithExit) {
                tail = Conversions.ok(new RExpr.Lit("()"));
            }
            return new RExpr.Block(out, tail, null, false);
        }
        if (throwing && !lambdaBody) {
            tail = Conversions.ok(tail);
        }
        return new RExpr.Block(out, tail, null, false);
    }

    /** instanceof / 型パターンの束縛変数を関数の先頭で宣言する（ラムダの本体は除く）。 */
    private List<RStmt> declareBindings(List<Stmt> stmts) {
        Map<String, JType> bindings = new LinkedHashMap<>();
        for (Stmt s : stmts) {
            collectBindings(s, bindings);
        }
        List<RStmt> out = new ArrayList<>();
        bindings.forEach((name, t) -> out.add(new RStmt.Let(Naming.valueName(name), true, cx.types().rust(t), cx.types().defaultValue(t))));
        return out;
    }

    private static void collectBindings(Stmt s, Map<String, JType> out) {
        new io.github.ykwyuta.j2r.jir.JirRewriter() {
            @Override
            protected Expr rewriteExpr(Expr e) {
                if (e instanceof Expr.InstanceOf io && io.binding() != null) {
                    out.putIfAbsent(io.binding(), io.target());
                }
                if (e instanceof Expr.Bind b) {
                    out.putIfAbsent(b.name(), b.value().type());
                }
                if (e instanceof Expr.SwitchExpr se) {
                    se.cases().forEach(c -> patternBindings(c, out));
                }
                return e;
            }

            @Override
            protected Stmt rewriteStmt(Stmt st) {
                if (st instanceof Stmt.Switch sw) {
                    sw.cases().forEach(c -> patternBindings(c, out));
                }
                return st;
            }
        }.stmt(withoutLambdas(s));
    }

    private static void patternBindings(Stmt.SwitchCase c, Map<String, JType> out) {
        for (Object l : c.labels()) {
            if (l instanceof Stmt.TypePattern tp && tp.binding() != null) {
                out.putIfAbsent(tp.binding(), tp.type());
            }
        }
    }

    /** ラムダの本体を空にした木（束縛変数の収集でラムダの中を見ないため）。 */
    private static Stmt withoutLambdas(Stmt s) {
        return new io.github.ykwyuta.j2r.jir.JirRewriter() {
            @Override
            protected Expr rewriteExpr(Expr e) {
                if (e instanceof Expr.Lambda l) {
                    return new Expr.Lambda(l.params(), new Stmt.Block(List.of(), l.pos()), l.sam(), l.interfaces(), l.type(), l.pos());
                }
                return e;
            }
        }.stmt(s);
    }

    // ================================================================== ループ・ジャンプの飛び先

    /** Rust 側のループ・ラベル付きブロック。label は使われたときだけ出力する。 */
    static final class RustFrame {
        final String label;
        final boolean isLoop;
        final int tryDepth;
        boolean used;

        RustFrame(String label, boolean isLoop, int tryDepth) {
            this.label = label;
            this.isLoop = isLoop;
            this.tryDepth = tryDepth;
        }

        String labelIfUsed() {
            return used ? label : null;
        }
    }

    /** Java の break / continue の飛び先。 */
    private record JavaTarget(String javaLabel, boolean isLoop, boolean isSwitch, RustFrame breakFrame,
                              RustFrame continueFrame, boolean continueIsBreak) {}

    /** try のクロージャから外へ出る return / break / continue の記録（Flow の番号）。 */
    private static final class TryCtx {
        final int depth;
        final Map<RustFrame, Integer> breaks = new LinkedHashMap<>();
        final Map<RustFrame, Integer> continues = new LinkedHashMap<>();
        boolean returns;
        int next;

        TryCtx(int depth) {
            this.depth = depth;
        }
    }

    private String temp() {
        return "__t" + temps++;
    }

    private String freshLabel(String javaLabel, String prefix) {
        if (javaLabel != null) {
            String l = Naming.toSnakeCase(javaLabel);
            return Naming.escape(l).equals(l) ? l : l + "_";
        }
        return prefix + ++labels;
    }

    private RustFrame newFrame(String label, boolean isLoop) {
        return new RustFrame(label, isLoop, tryDepth);
    }

    // ================================================================== 文

    private RExpr.Block block(Stmt s) {
        List<RStmt> out = new ArrayList<>();
        if (s instanceof Stmt.Block b) {
            for (Stmt x : b.stmts()) {
                stmt(x, out);
            }
        } else if (s != null) {
            stmt(s, out);
        }
        return RExpr.Block.of(out);
    }

    private RExpr.Block block(List<Stmt> stmts) {
        return block(new Stmt.Block(stmts, SourcePos.UNKNOWN));
    }

    void stmt(Stmt s, List<RStmt> out) {
        switch (s) {
            case Stmt.Block b -> out.add(new RStmt.ExprStmt(block(b), false));
            case Stmt.LocalVar v -> {
                RExpr init = v.init() != null ? value(v.init()) : defaultInitLocals ? cx.types().defaultValue(v.type()) : null;
                out.add(new RStmt.Let(Naming.valueName(v.name()), mutableLocals.contains(v.name()), cx.types().rust(v.type()), init));
            }
            case Stmt.ExprStmt e -> exprStmt(e.expr(), out);
            case Stmt.If i -> out.add(new RStmt.ExprStmt(ifStmt(i), false));
            case Stmt.While w -> whileLoop(w, null, out);
            case Stmt.DoWhile d -> doWhileLoop(d, null, out);
            case Stmt.For f -> forLoop(f, null, out);
            case Stmt.Labeled l -> labeled(l, out);
            case Stmt.Switch sw -> switchStmt(sw, out);
            case Stmt.Return r -> out.add(new RStmt.ExprStmt(returnExpr(r.value() == null ? null : returnValue(r.value())), true));
            case Stmt.Break b -> out.add(new RStmt.ExprStmt(breakExpr(b), true));
            case Stmt.Continue c -> out.add(new RStmt.ExprStmt(continueExpr(c), true));
            case Stmt.Yield y -> out.add(new RStmt.ExprStmt(yieldExpr(y), true));
            case Stmt.Throw t -> {
                RExpr exc = conv.toObject(value(t.exception()), t.exception().type());
                // new で作った例外は null にならないので、そのまま Err にする（null なら throw_obj が NPE にする）。
                RExpr result = t.exception() instanceof Expr.New
                        ? new RExpr.Call(new RExpr.Path("Err"), List.of(exc))
                        : new RExpr.Call(new RExpr.Path("jrt::throw_obj"), List.of(exc));
                out.add(new RStmt.ExprStmt(raise(result), true));
            }
            case Stmt.Try t -> tryStmt(t, out);
            case Stmt.ForEach fe -> out.add(new RStmt.ExprStmt(todo("enhanced for over " + fe.iterable().type().javaName()), true));
            case Stmt.Unsupported u -> out.add(new RStmt.ExprStmt(todo(u.description()), true));
        }
    }

    /** return する値（ラムダの本体なら JObject に変換）。 */
    private RExpr returnValue(Expr e) {
        RExpr v = value(e);
        return lambdaBody ? conv.toObject(v, e.type()) : v;
    }

    /** {@code return v}（try のクロージャの中なら Flow::Return(v) を返す）。 */
    private RExpr returnExpr(RExpr v) {
        RExpr value = v != null ? v : lambdaBody ? new RExpr.Call(new RExpr.Path("JObject::null"), List.of()) : null;
        if (tryDepth > 0) {
            tries.peek().returns = true;
            return new RExpr.Return(Conversions.ok(new RExpr.Call(new RExpr.Path("jrt::Flow::Return"),
                    List.of(value == null ? new RExpr.Lit("()") : value))));
        }
        if (throwing) {
            return new RExpr.Return(Conversions.ok(value == null ? new RExpr.Lit("()") : value));
        }
        return new RExpr.Return(value);
    }

    private RExpr ifStmt(Stmt.If i) {
        RExpr cond = value(i.condition());
        RExpr.Block then = block(i.thenStmt());
        RExpr elseBranch = null;
        if (i.elseStmt() instanceof Stmt.If nested) {
            elseBranch = ifStmt(nested);
        } else if (i.elseStmt() != null) {
            elseBranch = block(i.elseStmt());
        }
        return new RExpr.If(cond, then, elseBranch);
    }

    private static boolean isTrue(Expr e) {
        return e instanceof Expr.Literal l && Boolean.TRUE.equals(l.value());
    }

    private void whileLoop(Stmt.While w, String javaLabel, List<RStmt> out) {
        RExpr cond = isTrue(w.condition()) ? null : value(w.condition());
        RustFrame f = newFrame(freshLabel(javaLabel, "l"), true);
        frames.push(f);
        targets.push(new JavaTarget(javaLabel, true, false, f, f, false));
        RExpr.Block b = block(w.body());
        targets.pop();
        frames.pop();
        out.add(new RStmt.ExprStmt(cond == null ? new RExpr.Loop(f.labelIfUsed(), b) : new RExpr.While(f.labelIfUsed(), cond, b), false));
    }

    private void doWhileLoop(Stmt.DoWhile d, String javaLabel, List<RStmt> out) {
        RustFrame loop = newFrame(freshLabel(javaLabel, "l"), true);
        RustFrame cont = hasContinue(d.body(), javaLabel) ? newFrame(loop.label + "_body", false) : null;
        frames.push(loop);
        if (cont != null) {
            frames.push(cont);
        }
        targets.push(new JavaTarget(javaLabel, true, false, loop, cont != null ? cont : loop, cont != null));
        RExpr.Block b = block(d.body());
        targets.pop();
        if (cont != null) {
            frames.pop();
        }
        List<RStmt> stmts = new ArrayList<>();
        if (cont != null) {
            stmts.add(new RStmt.ExprStmt(new RExpr.Block(b.stmts(), null, cont.label, false), false));
        } else {
            stmts.addAll(b.stmts());
        }
        if (!isTrue(d.condition())) {
            RExpr notCond = new RExpr.Unary("!", value(d.condition()));
            stmts.add(new RStmt.ExprStmt(new RExpr.If(notCond, RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Break(null, null), true))), null), false));
        }
        frames.pop();
        out.add(new RStmt.ExprStmt(new RExpr.Loop(loop.labelIfUsed(), RExpr.Block.of(stmts)), false));
    }

    private void forLoop(Stmt.For f, String javaLabel, List<RStmt> out) {
        List<RStmt> scope = new ArrayList<>();
        for (Stmt init : f.init()) {
            stmt(init, scope);
        }
        RExpr cond = f.condition() == null || isTrue(f.condition()) ? null : value(f.condition());
        RustFrame loop = newFrame(freshLabel(javaLabel, "l"), true);
        boolean needCont = !f.update().isEmpty() && hasContinue(f.body(), javaLabel);
        RustFrame cont = needCont ? newFrame(loop.label + "_body", false) : null;
        frames.push(loop);
        if (cont != null) {
            frames.push(cont);
        }
        targets.push(new JavaTarget(javaLabel, true, false, loop, cont != null ? cont : loop, cont != null));
        RExpr.Block b = block(f.body());
        targets.pop();
        if (cont != null) {
            frames.pop();
        }
        List<RStmt> stmts = new ArrayList<>();
        if (cont != null) {
            stmts.add(new RStmt.ExprStmt(new RExpr.Block(b.stmts(), null, cont.label, false), false));
        } else {
            stmts.addAll(b.stmts());
        }
        for (Expr u : f.update()) {
            exprStmt(u, stmts);
        }
        frames.pop();
        RExpr loopExpr = cond == null ? new RExpr.Loop(loop.labelIfUsed(), RExpr.Block.of(stmts))
                : new RExpr.While(loop.labelIfUsed(), cond, RExpr.Block.of(stmts));
        if (scope.isEmpty()) {
            out.add(new RStmt.ExprStmt(loopExpr, false));
        } else {
            scope.add(new RStmt.ExprStmt(loopExpr, false));
            out.add(new RStmt.ExprStmt(RExpr.Block.of(scope), false));
        }
    }

    private void labeled(Stmt.Labeled l, List<RStmt> out) {
        switch (l.body()) {
            case Stmt.While w -> whileLoop(w, l.label(), out);
            case Stmt.DoWhile d -> doWhileLoop(d, l.label(), out);
            case Stmt.For f -> forLoop(f, l.label(), out);
            default -> {
                RustFrame f = newFrame(freshLabel(l.label(), "b"), false);
                frames.push(f);
                targets.push(new JavaTarget(l.label(), false, false, f, null, false));
                RExpr.Block b = block(l.body());
                targets.pop();
                frames.pop();
                out.add(new RStmt.ExprStmt(new RExpr.Block(b.stmts(), null, f.labelIfUsed(), false), false));
            }
        }
    }

    /** body 内に、このループを対象とする continue があるか（入れ子のループ内の無ラベル continue は除く）。 */
    private static boolean hasContinue(Stmt s, String javaLabel) {
        return switch (s) {
            case null -> false;
            case Stmt.Continue c -> c.label() == null || c.label().equals(javaLabel);
            case Stmt.Block b -> b.stmts().stream().anyMatch(x -> hasContinue(x, javaLabel));
            case Stmt.If i -> hasContinue(i.thenStmt(), javaLabel) || hasContinue(i.elseStmt(), javaLabel);
            case Stmt.Labeled l -> hasContinue(l.body(), javaLabel);
            case Stmt.Switch sw -> sw.cases().stream().anyMatch(c -> c.body().stream().anyMatch(x -> hasContinue(x, javaLabel)));
            case Stmt.Try t -> hasContinue(t.body(), javaLabel) || t.catches().stream().anyMatch(c -> hasContinue(c.body(), javaLabel))
                    || hasContinue(t.finallyBlock(), javaLabel);
            case Stmt.While w -> javaLabel != null && hasLabeledContinue(w.body(), javaLabel);
            case Stmt.DoWhile d -> javaLabel != null && hasLabeledContinue(d.body(), javaLabel);
            case Stmt.For f -> javaLabel != null && hasLabeledContinue(f.body(), javaLabel);
            default -> false;
        };
    }

    private static boolean hasLabeledContinue(Stmt s, String javaLabel) {
        return switch (s) {
            case null -> false;
            case Stmt.Continue c -> javaLabel.equals(c.label());
            case Stmt.Block b -> b.stmts().stream().anyMatch(x -> hasLabeledContinue(x, javaLabel));
            case Stmt.If i -> hasLabeledContinue(i.thenStmt(), javaLabel) || hasLabeledContinue(i.elseStmt(), javaLabel);
            case Stmt.Labeled l -> hasLabeledContinue(l.body(), javaLabel);
            case Stmt.Switch sw -> sw.cases().stream().anyMatch(c -> c.body().stream().anyMatch(x -> hasLabeledContinue(x, javaLabel)));
            case Stmt.Try t -> hasLabeledContinue(t.body(), javaLabel)
                    || t.catches().stream().anyMatch(c -> hasLabeledContinue(c.body(), javaLabel))
                    || hasLabeledContinue(t.finallyBlock(), javaLabel);
            case Stmt.While w -> hasLabeledContinue(w.body(), javaLabel);
            case Stmt.DoWhile d -> hasLabeledContinue(d.body(), javaLabel);
            case Stmt.For f -> hasLabeledContinue(f.body(), javaLabel);
            default -> false;
        };
    }

    private RExpr breakExpr(Stmt.Break b) {
        JavaTarget t = findTarget(b.label(), false);
        if (t == null) {
            return todo("break without target");
        }
        return jump(t.breakFrame(), true);
    }

    private RExpr continueExpr(Stmt.Continue c) {
        JavaTarget t = findTarget(c.label(), true);
        if (t == null || t.continueFrame() == null) {
            return todo("continue without target");
        }
        return jump(t.continueFrame(), t.continueIsBreak());
    }

    /**
     * frame への break（asBreak）/ continue。frame が try のクロージャの外にあれば、
     * クロージャから Flow::Break / Flow::Continue を返し、try の後で改めて飛ぶ。
     */
    private RExpr jump(RustFrame frame, boolean asBreak) {
        if (frame.tryDepth < tryDepth) {
            TryCtx t = tries.peek();
            Map<RustFrame, Integer> ids = asBreak ? t.breaks : t.continues;
            int id = ids.computeIfAbsent(frame, k -> t.next++);
            return new RExpr.Return(Conversions.ok(new RExpr.Call(new RExpr.Path(asBreak ? "jrt::Flow::Break" : "jrt::Flow::Continue"),
                    List.of(new RExpr.Lit(Integer.toString(id))))));
        }
        String label = labelFor(frame);
        return asBreak ? new RExpr.Break(label, null) : new RExpr.Continue(label);
    }

    private JavaTarget findTarget(String label, boolean forContinue) {
        for (JavaTarget t : targets) {
            if (label != null ? label.equals(t.javaLabel()) : (t.isLoop() || (!forContinue && t.isSwitch()))) {
                return t;
            }
        }
        return null;
    }

    /** 最も内側の Rust フレームがループ自身なら無ラベルで済む。それ以外はラベルを付けて使用済みにする。 */
    private String labelFor(RustFrame target) {
        if (target.isLoop && frames.peek() == target) {
            return null;
        }
        target.used = true;
        return target.label;
    }

    // ---------------------------------------------------------------- try / catch / finally

    /**
     * try 文。本体（と finally がある場合は catch 節も）を {@code JResult<Flow<R>>} を返すクロージャにして
     * {@code jrt::try_block} で実行する。本体の中の {@code ?} / {@code return Err(..)} はクロージャが受け止める。
     * クロージャから外への return / break / continue は Flow で伝え、try の後で実行する。
     */
    private void tryStmt(Stmt.Try t, List<RStmt> out) {
        TryCtx ctx = new TryCtx(tryDepth + 1);
        tries.push(ctx);
        tryDepth++;
        RExpr.Block body = block(t.body());
        RExpr bodyClosure = flowClosure(body);
        RExpr.Arm catchArm = null;
        if (!t.catches().isEmpty()) {
            if (t.finallyBlock() != null) {
                // finally がある場合、catch 節もクロージャ（try の中）で実行する。
                catchArm = new RExpr.Arm("Err(__e)", catchChain(t.catches(), true));
            }
        }
        tryDepth--;
        tries.pop();

        RExpr tryCall = new RExpr.Call(new RExpr.Path("jrt::try_block"), List.of(bodyClosure));
        if (t.finallyBlock() == null) {
            // catch 節は try の外で（普通の制御構文として）実行する。
            List<RExpr.Arm> arms = new ArrayList<>(flowArms(ctx, "Ok"));
            arms.add(new RExpr.Arm("Ok(_)", RExpr.Block.of(List.of())));
            arms.add(new RExpr.Arm("Err(__e)", catchChain(t.catches(), false)));
            out.add(new RStmt.ExprStmt(new RExpr.Match(tryCall, arms), false));
            return;
        }
        String r = temp();
        RExpr protectedCall = tryCall;
        if (catchArm != null) {
            // try { try { body } catch { ... } } finally { ... }
            List<RExpr.Arm> inner = List.of(new RExpr.Arm("Ok(__f)", Conversions.ok(new RExpr.Path("__f"))), catchArm);
            tries.push(ctx);
            tryDepth++;
            RExpr.Block outerBody = new RExpr.Block(List.of(), new RExpr.Match(tryCall, inner), null, false);
            tryDepth--;
            tries.pop();
            protectedCall = new RExpr.Call(new RExpr.Path("jrt::try_block"), List.of(
                    new RExpr.Closure(false, List.of(), flowResultType(), outerBody)));
        }
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let(r, false, null, protectedCall));
        // finally 節（try の外で実行するので、中の return / break はそのまま効く）。
        stmts.addAll(block(t.finallyBlock()).stmts());
        List<RExpr.Arm> arms = new ArrayList<>(flowArms(ctx, "Ok"));
        arms.add(new RExpr.Arm("Ok(_)", RExpr.Block.of(List.of())));
        arms.add(new RExpr.Arm("Err(__e)", raise(new RExpr.Call(new RExpr.Path("Err"), List.of(new RExpr.Path("__e"))))));
        stmts.add(new RStmt.ExprStmt(new RExpr.Match(new RExpr.Path(r), arms), false));
        out.add(new RStmt.ExprStmt(RExpr.Block.of(stmts), false));
    }

    /** try のクロージャの戻り値型 {@code JResult<jrt::Flow<R>>}。 */
    private RType flowResultType() {
        return new RType("JResult<jrt::Flow<" + flowType.text() + ">>");
    }

    /** {@code Ok(jrt::Flow::Normal)}。 */
    private static RExpr flowNormal() {
        return Conversions.ok(new RExpr.Path("jrt::Flow::Normal"));
    }

    /** {@code || -> JResult<jrt::Flow<R>> { body; Ok(jrt::Flow::Normal) }}。 */
    private RExpr flowClosure(RExpr.Block body) {
        List<RStmt> stmts = new ArrayList<>(body.stmts());
        RExpr.Block b = new RExpr.Block(stmts, flowNormal(), null, false);
        return new RExpr.Closure(false, List.of(), flowResultType(), b);
    }

    /** try の後で、クロージャから伝えられた return / break / continue を実行する match の腕。 */
    private List<RExpr.Arm> flowArms(TryCtx ctx, String wrap) {
        List<RExpr.Arm> arms = new ArrayList<>();
        if (ctx.returns) {
            RExpr v = flowType.equals(RType.UNIT) || flowType.text().equals("()") ? null : new RExpr.Path("__v");
            RExpr ret = returnExpr(v);
            arms.add(new RExpr.Arm(wrap + "(jrt::Flow::Return(" + (v == null ? "_" : "__v") + "))", ret));
        }
        ctx.breaks.forEach((frame, id) -> arms.add(new RExpr.Arm(wrap + "(jrt::Flow::Break(" + id + "))", jump(frame, true))));
        ctx.continues.forEach((frame, id) -> arms.add(new RExpr.Arm(wrap + "(jrt::Flow::Continue(" + id + "))", jump(frame, false))));
        return arms;
    }

    /**
     * catch 節の連鎖: {@code if __e.instance_of("A") { let e = __e; ... } else { return Err(__e); }}。
     * inClosure なら、クロージャの戻り値として Ok(Flow::Normal) で終える。
     */
    private RExpr catchChain(List<Stmt.Catch> catches, boolean inClosure) {
        boolean catchesAll = catches.stream().anyMatch(c -> c.types().contains("java.lang.Throwable"));
        RExpr chain = catchesAll ? null : RExpr.Block.of(List.of(new RStmt.ExprStmt(
                raise(new RExpr.Call(new RExpr.Path("Err"), List.of(new RExpr.Path("__e")))), true)));
        for (int i = catches.size() - 1; i >= 0; i--) {
            Stmt.Catch c = catches.get(i);
            RExpr cond = null;
            for (String type : c.types()) {
                RExpr test = new RExpr.MethodCall(new RExpr.Path("__e"), "instance_of", List.of(new RExpr.Lit("\"" + type + "\"")));
                cond = cond == null ? test : new RExpr.Binary("||", cond, test);
            }
            List<RStmt> stmts = new ArrayList<>();
            stmts.add(new RStmt.Let(Naming.valueName(c.var()), false, new RType("JObject"), new RExpr.Path("__e")));
            stmts.addAll(block(c.body()).stmts());
            RExpr.Block then = inClosure ? new RExpr.Block(stmts, flowNormal(), null, false) : RExpr.Block.of(stmts);
            if (c.types().contains("java.lang.Throwable") || chain == null) {
                chain = then;
            } else {
                chain = new RExpr.If(cond, then, chain);
            }
        }
        return chain instanceof RExpr.Block b ? b : RExpr.Block.of(List.of(new RStmt.ExprStmt(chain, false)));
    }

    // ---------------------------------------------------------------- switch

    private void switchStmt(Stmt.Switch sw, List<RStmt> out) {
        out.add(new RStmt.ExprStmt(switchLowering(sw.selector(), sw.cases(), null, sw.pos()), false));
    }

    private RExpr switchExpr(Expr.SwitchExpr se) {
        return switchLowering(se.selector(), se.cases(), se.type(), se.pos());
    }

    /** 空の case（case 1: case 2: ...）を次の case にまとめたもの。 */
    private record Group(List<Object> labels, boolean isDefault, Expr guard, List<Stmt> body, boolean arrow) {}

    private static List<Group> groups(List<Stmt.SwitchCase> cases) {
        List<Group> groups = new ArrayList<>();
        List<Object> pending = new ArrayList<>();
        boolean pendingDefault = false;
        for (Stmt.SwitchCase c : cases) {
            pending.addAll(c.labels());
            pendingDefault |= c.isDefault();
            if (c.arrow() || !c.body().isEmpty()) {
                groups.add(new Group(List.copyOf(pending), pendingDefault, c.guard(), c.body(), c.arrow()));
                pending.clear();
                pendingDefault = false;
            }
        }
        if (!pending.isEmpty() || pendingDefault) {
            groups.add(new Group(List.copyOf(pending), pendingDefault, null, List.of(), false));
        }
        return groups;
    }

    /**
     * switch 文・switch 式。resultType が null なら文。
     * <ul>
     *   <li>型パターン・ガード・case null を含む → if の連鎖</li>
     *   <li>fall-through がある → 入口の case 番号を求めてから、番号以上の本体を順に実行</li>
     *   <li>それ以外 → match</li>
     * </ul>
     */
    private RExpr switchLowering(Expr selector, List<Stmt.SwitchCase> cases, JType resultType, SourcePos pos) {
        List<Group> groups = groups(cases);
        boolean patterns = groups.stream().anyMatch(g -> g.guard() != null
                || g.labels().stream().anyMatch(l -> l instanceof Stmt.TypePattern || l instanceof Stmt.NullLabel));
        boolean fallThrough = false;
        for (int i = 0; i < groups.size() - 1; i++) {
            if (!groups.get(i).arrow() && !endsWithJump(groups.get(i).body())) {
                fallThrough = true;
            }
        }
        RustFrame frame = newFrame(freshLabel(null, "sw"), false);
        frames.push(frame);
        if (resultType != null) {
            yieldTargets.push(frame);
        } else {
            targets.push(new JavaTarget(null, false, true, frame, null, false));
        }
        RExpr result;
        try {
            if (patterns) {
                result = patternSwitch(selector, groups, resultType, frame);
            } else if (fallThrough) {
                result = fallThroughSwitch(selector, groups, resultType, frame);
            } else {
                result = matchSwitch(selector, groups, resultType, frame);
            }
        } finally {
            if (resultType != null) {
                yieldTargets.pop();
            } else {
                targets.pop();
            }
            frames.pop();
        }
        return result;
    }

    /** セレクタの種類に応じた match の対象とパターン。 */
    private RExpr matchScrutinee(Expr selector) {
        JType t = selector.type();
        if (JType.isString(t)) {
            return tryOp(new RExpr.MethodCall(ref(selector), "as_str", List.of()));
        }
        if (t instanceof JType.ClassType) {
            return tryOp(new RExpr.Call(new RExpr.Path("jrt::lang::enums::ordinal"), List.of(borrow(selector))));
        }
        return value(selector);
    }

    private static String pattern(Object label) {
        return switch (label) {
            case String s -> RustLiterals.stringLiteral(s, new boolean[1]);
            case Character c -> RustLiterals.charPattern(c);
            case Stmt.EnumLabel e -> e.ordinal() + " /* " + e.name() + " */";
            default -> label.toString();
        };
    }

    private RExpr matchSwitch(Expr selector, List<Group> groups, JType resultType, RustFrame frame) {
        RExpr scrutinee = matchScrutinee(selector);
        List<RExpr.Arm> arms = new ArrayList<>();
        RExpr.Arm defaultArm = null;
        for (Group g : groups) {
            List<Stmt> stmts = new ArrayList<>(g.body());
            if (resultType == null && !stmts.isEmpty() && stmts.get(stmts.size() - 1) instanceof Stmt.Break b && b.label() == null) {
                stmts.remove(stmts.size() - 1);
            }
            RExpr armBody = armBody(stmts, resultType);
            if (g.isDefault()) {
                defaultArm = new RExpr.Arm("_", armBody);
            } else {
                List<String> pats = new ArrayList<>();
                for (Object label : g.labels()) {
                    pats.add(pattern(label));
                }
                arms.add(new RExpr.Arm(String.join(" | ", pats), armBody));
            }
        }
        if (defaultArm != null) {
            arms.add(defaultArm);
        } else if (resultType != null) {
            arms.add(new RExpr.Arm("_", matchException()));
        } else {
            arms.add(new RExpr.Arm("_", RExpr.Block.of(List.of())));
        }
        RExpr match = new RExpr.Match(scrutinee, arms);
        if (frame.used) {
            return new RExpr.Block(List.of(), match, frame.label, false);
        }
        return match;
    }

    /** match の腕の本体。switch 式で本体が yield 1 つなら、その値をそのまま腕の式にする。 */
    private RExpr armBody(List<Stmt> stmts, JType resultType) {
        if (resultType != null && stmts.size() == 1 && stmts.get(0) instanceof Stmt.Yield y) {
            return value(y.value());
        }
        return block(stmts);
    }

    private RExpr matchException() {
        return raiseJdk("java.lang.MatchException");
    }

    /** fall-through のある switch（文）: 入口の番号を求め、番号以上の本体を順に実行する。 */
    private RExpr fallThroughSwitch(Expr selector, List<Group> groups, JType resultType, RustFrame frame) {
        frame.used = true;
        String entry = temp();
        List<RExpr.Arm> arms = new ArrayList<>();
        int defaultIndex = groups.size();
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            if (g.isDefault()) {
                defaultIndex = i;
            }
            List<String> pats = new ArrayList<>();
            for (Object label : g.labels()) {
                pats.add(pattern(label));
            }
            if (!pats.isEmpty()) {
                arms.add(new RExpr.Arm(String.join(" | ", pats), new RExpr.Lit(Integer.toString(i))));
            }
        }
        arms.add(new RExpr.Arm("_", new RExpr.Lit(Integer.toString(defaultIndex))));
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let(entry, false, new RType("usize"), new RExpr.Match(matchScrutinee(selector), arms)));
        for (int i = 0; i < groups.size(); i++) {
            RExpr cond = new RExpr.Binary("<=", new RExpr.Path(entry), new RExpr.Lit(Integer.toString(i)));
            stmts.add(new RStmt.ExprStmt(new RExpr.If(cond, block(groups.get(i).body()), null), false));
        }
        if (resultType != null) {
            stmts.add(new RStmt.ExprStmt(matchException(), true));
        }
        return new RExpr.Block(stmts, null, frame.label, false);
    }

    /** 型パターン・ガード・case null を含む switch: if の連鎖にする。 */
    private RExpr patternSwitch(Expr selector, List<Group> groups, JType resultType, RustFrame frame) {
        frame.used = true;
        String s = temp();
        JType selType = selector.type();
        List<RStmt> stmts = new ArrayList<>();
        stmts.add(new RStmt.Let(s, false, null, value(selector)));
        RExpr sel = new RExpr.Path(s);
        boolean hasNullCase = groups.stream().anyMatch(g -> g.labels().stream().anyMatch(l -> l instanceof Stmt.NullLabel));
        RExpr isNull = new RExpr.MethodCall(sel, "is_null", List.of());
        if (!hasNullCase && !(selType instanceof JType.Primitive)) {
            stmts.add(new RStmt.ExprStmt(new RExpr.If(isNull, RExpr.Block.of(List.of(new RStmt.ExprStmt(
                    raiseJdk("java.lang.NullPointerException"), true))), null), false));
        }
        Group defaultGroup = null;
        for (Group g : groups) {
            if (g.isDefault() && g.labels().stream().noneMatch(l -> l instanceof Stmt.NullLabel)) {
                defaultGroup = g;
                continue;
            }
            RExpr cond = null;
            List<RStmt> bind = new ArrayList<>();
            for (Object label : g.labels()) {
                RExpr test;
                switch (label) {
                    case Stmt.NullLabel n -> test = isNull;
                    case Stmt.TypePattern tp -> {
                        test = new RExpr.MethodCall(sel, "instance_of", List.of(new RExpr.Lit("\"" + tp.type().javaName() + "\"")));
                        if (JType.isString(tp.type())) {
                            test = new RExpr.MethodCall(sel, "instance_of", List.of(new RExpr.Lit("\"java.lang.String\"")));
                        }
                        if (tp.binding() != null) {
                            bind.add(new RStmt.ExprStmt(new RExpr.Assign(new RExpr.Path(Naming.valueName(tp.binding())), "=",
                                    conv.convert(new RExpr.MethodCall(sel, "clone", List.of()), selType, tp.type(), false)), true));
                        }
                    }
                    case Stmt.EnumLabel e -> test = new RExpr.Binary("==", tryOp(new RExpr.Call(new RExpr.Path("jrt::lang::enums::ordinal"),
                            List.of(new RExpr.Unary("&", sel)))), new RExpr.Lit(Integer.toString(e.ordinal())));
                    default -> test = tryOp(new RExpr.MethodCall(sel, "equals", List.of(new RExpr.Unary("&",
                            conv.toObject(literalFor(label), labelType(label))))));
                }
                cond = cond == null ? test : new RExpr.Binary("||", cond, test);
            }
            List<RStmt> body = new ArrayList<>(bind);
            RExpr.Block caseBody = caseBlock(g.body(), resultType, frame);
            if (g.guard() != null) {
                // ガードは束縛の後で評価する。成り立たなければ次の case へ進む。
                body.add(new RStmt.ExprStmt(new RExpr.If(value(g.guard()), caseBody, null), false));
            } else {
                body.addAll(caseBody.stmts());
            }
            if (g.isDefault()) {
                cond = cond == null ? new RExpr.Lit("true") : cond;
            }
            stmts.add(new RStmt.ExprStmt(new RExpr.If(cond == null ? new RExpr.Lit("false") : cond, RExpr.Block.of(body), null), false));
        }
        if (defaultGroup != null) {
            stmts.addAll(caseBlock(defaultGroup.body(), resultType, frame).stmts());
        } else if (resultType != null) {
            stmts.add(new RStmt.ExprStmt(matchException(), true));
        }
        return new RExpr.Block(stmts, null, frame.label, false);
    }

    private RExpr literalFor(Object label) {
        return switch (label) {
            case String s -> stringLiteral(s, SourcePos.UNKNOWN);
            case Integer i -> RustLiterals.number(i, JType.INT, false);
            case Character c -> RustLiterals.number(c, JType.CHAR, false);
            default -> todo("case label " + label);
        };
    }

    private static JType labelType(Object label) {
        return switch (label) {
            case String s -> JType.STRING;
            case Character c -> JType.CHAR;
            default -> JType.INT;
        };
    }

    /** if の連鎖・fall-through 版の case 本体。文なら最後に switch を抜け、式なら yield で抜ける。 */
    private RExpr.Block caseBlock(List<Stmt> body, JType resultType, RustFrame frame) {
        List<RStmt> stmts = new ArrayList<>(block(body).stmts());
        if (resultType == null && !endsWithJump(body)) {
            stmts.add(new RStmt.ExprStmt(new RExpr.Break(frame.label, null), true));
        }
        return RExpr.Block.of(stmts);
    }

    private RExpr yieldExpr(Stmt.Yield y) {
        RustFrame frame = yieldTargets.peek();
        if (frame == null) {
            return todo("yield outside of a switch expression");
        }
        if (frame.tryDepth < tryDepth) {
            return todo("yield out of a try block");
        }
        frame.used = true;
        return new RExpr.Break(frame.label, value(y.value()));
    }

    private static boolean endsWithJump(List<Stmt> body) {
        if (body.isEmpty()) {
            return false;
        }
        Stmt last = body.get(body.size() - 1);
        return switch (last) {
            case Stmt.Break b -> true;
            case Stmt.Continue c -> true;
            case Stmt.Return r -> true;
            case Stmt.Throw t -> true;
            case Stmt.Yield y -> true;
            case Stmt.Block b -> endsWithJump(b.stmts());
            default -> false;
        };
    }

    // ================================================================== 式文・代入

    private void exprStmt(Expr e, List<RStmt> out) {
        switch (e) {
            case Expr.Assign a -> {
                Place p = place(a.target(), out, false);
                out.add(new RStmt.ExprStmt(write(p, value(a.value())), true));
            }
            case Expr.CompoundAssign ca -> {
                Place p = place(ca.target(), out, true);
                if (p instanceof Place.Local l && canUseCompoundOperator(ca)) {
                    out.add(new RStmt.ExprStmt(new RExpr.Assign(new RExpr.Path(l.name()), ca.op().symbol() + "=", value(ca.value())), true));
                } else {
                    out.add(new RStmt.ExprStmt(write(p, compound(ca, read(p))), true));
                }
            }
            case Expr.IncDec id -> {
                Place p = place(id.target(), out, true);
                out.add(new RStmt.ExprStmt(write(p, step(read(p), id.type(), id.increment())), true));
            }
            case Expr.CtorCall c -> out.add(new RStmt.ExprStmt(ctorCall(c), true));
            case Expr.Unsupported u when u.description().equals("<drop>") -> { }
            default -> out.add(new RStmt.ExprStmt(value(e), true));
        }
    }

    /** Rust の複合代入演算子（+= など）をそのまま使っても Java と同じ結果になる場合。 */
    private static boolean canUseCompoundOperator(Expr.CompoundAssign ca) {
        if (!ca.operandType().equals(ca.type())) {
            return false;
        }
        boolean floating = JType.isFloating(ca.type());
        return switch (ca.op()) {
            case ADD, SUB, MUL, DIV, REM -> floating;
            case BIT_AND, BIT_OR, BIT_XOR -> ca.type() instanceof JType.Primitive;
            default -> false;
        };
    }

    /** 代入先。 */
    private sealed interface Place {
        JType type();

        record Local(String name, JType type) implements Place {}

        /** thread_local の static（path は RIR のパス文字列）。 */
        record Static(String path, JType type, RExpr clinit) implements Place {}

        /** インスタンスフィールド: {@code Struct::of(obj).field}（obj は &JObject として使える式）。 */
        record Field(RExpr obj, String struct, String field, JType type) implements Place {}

        record ArrayElem(RExpr array, RExpr index, JType type) implements Place {}

        record Invalid(String reason, JType type) implements Place {}
    }

    /**
     * 代入先を評価する。hoist が true（読み出しと書き込みの両方を行う）なら、配列・添字・フィールドの
     * レシーバを一時変数に束縛して二重評価を防ぐ。
     */
    private Place place(Expr target, List<RStmt> pre, boolean hoist) {
        return switch (target) {
            case Expr.Local l -> new Place.Local(Naming.valueName(l.name()), l.type());
            case Expr.StaticField f -> {
                ProgramIndex.FieldInfo fi = cx.index().field(f.owner(), f.name());
                if (fi == null || fi.storage() != ProgramIndex.FieldStorage.THREAD_LOCAL) {
                    yield new Place.Invalid("assignment to " + f.owner() + "." + f.name(), f.type());
                }
                yield new Place.Static(imp.item(fi.owner(), fi.rustName()), fi.type(), clinitCall(fi.owner()));
            }
            case Expr.FieldAccess f -> {
                ProgramIndex.FieldInfo fi = cx.index().field(f.owner(), f.name());
                if (fi == null) {
                    yield new Place.Invalid("field " + f.owner() + "." + f.name(), f.type());
                }
                RExpr obj = objRef(f.receiver());
                if (hoist && !(f.receiver() instanceof Expr.This) && !(f.receiver() instanceof Expr.Local)) {
                    String t = temp();
                    pre.add(new RStmt.Let(t, false, null, value(f.receiver())));
                    obj = tryOp(new RExpr.MethodCall(new RExpr.Path(t), "nn", List.of()));
                }
                yield new Place.Field(obj, imp.type(fi.owner()), fi.rustName(), fi.type());
            }
            case Expr.ArrayAccess a -> {
                RExpr arr = ref(a.array());
                RExpr idx = value(a.index());
                if (hoist && !isSimple(arr)) {
                    String t = temp();
                    pre.add(new RStmt.Let(t, false, null, value(a.array())));
                    arr = new RExpr.Path(t);
                }
                if (hoist && !isSimple(idx)) {
                    String t = temp();
                    pre.add(new RStmt.Let(t, false, null, idx));
                    idx = new RExpr.Path(t);
                }
                yield new Place.ArrayElem(arr, idx, a.type());
            }
            default -> new Place.Invalid("assignment target " + target.getClass().getSimpleName(), target.type());
        };
    }

    private static boolean isSimple(RExpr e) {
        return e instanceof RExpr.Path || (e instanceof RExpr.Lit l && !l.text().startsWith("-"));
    }

    private RExpr read(Place p) {
        return switch (p) {
            case Place.Local l -> TypeMapper.isCopy(l.type()) ? new RExpr.Path(l.name())
                    : new RExpr.MethodCall(new RExpr.Path(l.name()), "clone", List.of());
            case Place.Static s -> withClinit(s.clinit(), staticRead(s.path(), s.type()));
            case Place.Field f -> fieldRead(f.obj(), f.struct(), f.field(), f.type());
            case Place.ArrayElem a -> tryOp(new RExpr.MethodCall(a.array(), "get", List.of(a.index())));
            case Place.Invalid i -> todo(i.reason());
        };
    }

    private RExpr write(Place p, RExpr v) {
        return switch (p) {
            case Place.Local l -> new RExpr.Assign(new RExpr.Path(l.name()), "=", v);
            case Place.Static s -> {
                // 値の式（? や return を含みうる）は with のクロージャの外で評価する。
                List<RStmt> pre = new ArrayList<>();
                RExpr value = v;
                if (!isSimple(v)) {
                    pre.add(new RStmt.Let("__v", false, null, v));
                    value = new RExpr.Path("__v");
                }
                RExpr c = new RExpr.Path("c");
                RExpr body = TypeMapper.isCopy(s.type())
                        ? new RExpr.MethodCall(c, "set", List.of(value))
                        : new RExpr.Assign(new RExpr.Unary("*", new RExpr.MethodCall(c, "borrow_mut", List.of())), "=", value);
                RExpr store = withClinit(s.clinit(), new RExpr.MethodCall(new RExpr.Path(s.path()), "with", List.of(RExpr.Closure.of(List.of("c"), body))));
                yield pre.isEmpty() ? store : new RExpr.Block(pre, store, null, true);
            }
            case Place.Field f -> {
                RExpr cell = new RExpr.Field(new RExpr.Call(new RExpr.Path(f.struct() + "::of"), List.of(f.obj())), f.field());
                yield TypeMapper.isCopy(f.type()) ? new RExpr.MethodCall(cell, "set", List.of(v))
                        : new RExpr.Assign(new RExpr.Unary("*", new RExpr.MethodCall(cell, "borrow_mut", List.of())), "=", v);
            }
            case Place.ArrayElem a -> tryOp(new RExpr.MethodCall(a.array(), "set", List.of(a.index(), v)));
            case Place.Invalid i -> {
                cx.diags().report(DiagnosticCode.UNSUPPORTED_SYNTAX, SourcePos.UNKNOWN, i.reason());
                yield todo(i.reason());
            }
        };
    }

    private static RExpr fieldRead(RExpr obj, String struct, String field, JType type) {
        RExpr cell = new RExpr.Field(new RExpr.Call(new RExpr.Path(struct + "::of"), List.of(obj)), field);
        return TypeMapper.isCopy(type) ? new RExpr.MethodCall(cell, "get", List.of())
                : new RExpr.MethodCall(new RExpr.MethodCall(cell, "borrow", List.of()), "clone", List.of());
    }

    static RExpr staticRead(String path, JType type) {
        RExpr c = new RExpr.Path("c");
        RExpr body = TypeMapper.isCopy(type) ? new RExpr.MethodCall(c, "get", List.of())
                : new RExpr.MethodCall(new RExpr.MethodCall(c, "borrow", List.of()), "clone", List.of());
        return new RExpr.MethodCall(new RExpr.Path(path), "with", List.of(RExpr.Closure.of(List.of("c"), body)));
    }

    /** static 初期化ブロックを持つ別のクラスの static 変数を参照する前に呼ぶ __clinit()（なければ null）。 */
    private RExpr clinitCall(ProgramIndex.TypeInfo t) {
        if (!Lowerer.needsClinit(cx.index(), t) || t == owner) {
            return null;
        }
        RExpr call = new RExpr.Call(new RExpr.Path(imp.type(t) + "::__clinit"), List.of());
        return cx.throwing().isThrowing(Lowerer.clinitKey(t.decl())) ? tryOp(call) : call;
    }

    private static RExpr withClinit(RExpr clinit, RExpr e) {
        if (clinit == null) {
            return e;
        }
        return new RExpr.Block(List.of(new RStmt.ExprStmt(clinit, true)), e, null, true);
    }

    /** 複合代入の右辺: {@code (T)(cur op value)}。 */
    private RExpr compound(Expr.CompoundAssign ca, RExpr current) {
        if (JType.isString(ca.type())) {
            // jconcat! は参照で受け取るので、ローカル変数の clone は不要。
            RExpr cur = current instanceof RExpr.MethodCall mc && mc.method().equals("clone") && mc.args().isEmpty() ? mc.receiver() : current;
            return new RExpr.Macro("jconcat", List.of(cur, concatPart(ca.value())));
        }
        RExpr cur = conv.convert(current, ca.type(), ca.operandType(), false);
        RExpr result = binary(ca.op(), cur, value(ca.value()), ca.operandType(), ca.value());
        return conv.convert(result, ca.operandType(), ca.type(), false);
    }

    /** ++ / -- の 1 ステップ（byte/short/char も自身の型でラップアラウンドさせれば Java と一致する）。 */
    private RExpr step(RExpr current, JType t, boolean increment) {
        if (!(t instanceof JType.Primitive)) {
            // ボックス型: アンボクシングして 1 を足し、ボクシングし直す。
            JType prim = Conversions.unboxed(t);
            if (prim instanceof JType.Primitive) {
                return conv.convert(step(conv.convert(current, t, prim, false), prim, increment), prim, t, false);
            }
            return todo("++/-- on " + t.javaName());
        }
        if (JType.isFloating(t)) {
            RExpr one = new RExpr.Lit(JType.isPrimitive(t, JType.Kind.FLOAT) ? "1.0f32" : "1.0");
            return new RExpr.Binary(increment ? "+" : "-", current, one);
        }
        return new RExpr.MethodCall(Conversions.withSuffix(current, t), increment ? "wrapping_add" : "wrapping_sub", List.of(new RExpr.Lit("1")));
    }

    // ================================================================== 式

    /** 値として評価する（参照型のローカル変数は clone する）。 */
    RExpr value(Expr e) {
        return expr(e, false);
    }

    /** 参照として評価する（メソッドのレシーバ・& で渡す引数。ローカル変数を clone しない）。 */
    RExpr ref(Expr e) {
        return expr(e, true);
    }

    /** {@code &x} として渡す式（this は既に参照なのでそのまま）。 */
    private RExpr borrow(Expr e) {
        if (e instanceof Expr.This t && t.outerLevels() == 0) {
            return new RExpr.Path("this");
        }
        return new RExpr.Unary("&", ref(e));
    }

    /** Struct::of(...) に渡す &JObject（this 以外は null なら NullPointerException）。 */
    private RExpr objRef(Expr e) {
        if (e instanceof Expr.This) {
            return borrow(e);
        }
        return tryOp(new RExpr.MethodCall(ref(e), "nn", List.of()));
    }

    private RExpr expr(Expr e, boolean byRef) {
        return switch (e) {
            case Expr.Literal l -> literal(l, false);
            case Expr.Local l -> {
                RExpr p = new RExpr.Path(Naming.valueName(l.name()));
                yield byRef || TypeMapper.isCopy(l.type()) ? p : new RExpr.MethodCall(p, "clone", List.of());
            }
            case Expr.This t -> thisExpr(t, byRef);
            case Expr.StaticField f -> staticField(f);
            case Expr.FieldAccess f -> {
                ProgramIndex.FieldInfo fi = cx.index().field(f.owner(), f.name());
                if (fi == null) {
                    yield todo("field " + f.owner() + "." + f.name());
                }
                yield fieldRead(objRef(f.receiver()), imp.type(fi.owner()), fi.rustName(), fi.type());
            }
            case Expr.Binary b -> binaryExpr(b);
            case Expr.Unary u -> unary(u);
            case Expr.Assign a -> {
                List<RStmt> pre = new ArrayList<>();
                Place p = place(a.target(), pre, false);
                yield valueBlock(pre, p, value(a.value()));
            }
            case Expr.CompoundAssign ca -> {
                List<RStmt> pre = new ArrayList<>();
                Place p = place(ca.target(), pre, true);
                yield valueBlock(pre, p, compound(ca, read(p)));
            }
            case Expr.IncDec id -> {
                List<RStmt> pre = new ArrayList<>();
                Place p = place(id.target(), pre, true);
                if (id.prefix()) {
                    yield valueBlock(pre, p, step(read(p), id.type(), id.increment()));
                }
                String t = temp();
                pre.add(new RStmt.Let(t, false, null, read(p)));
                RExpr old = TypeMapper.isCopy(p.type()) ? new RExpr.Path(t) : new RExpr.MethodCall(new RExpr.Path(t), "clone", List.of());
                pre.add(new RStmt.ExprStmt(write(p, step(old, id.type(), id.increment())), true));
                yield new RExpr.Block(pre, new RExpr.Path(t), null, true);
            }
            case Expr.Conditional c -> new RExpr.If(value(c.condition()),
                    new RExpr.Block(List.of(), value(c.whenTrue()), null, false),
                    new RExpr.Block(List.of(), value(c.whenFalse()), null, false));
            case Expr.Cast c -> castExpr(c);
            case Expr.Call c -> call(c);
            case Expr.New n -> newObject(n);
            case Expr.CtorCall c -> ctorCall(c);
            case Expr.InstanceOf io -> instanceOf(io);
            case Expr.Bind b -> new RExpr.Block(List.of(new RStmt.ExprStmt(
                    new RExpr.Assign(new RExpr.Path(Naming.valueName(b.name())), "=", value(b.value())), true)), new RExpr.Lit("true"), null, false);
            case Expr.Lambda l -> lambda(l);
            case Expr.SwitchExpr se -> switchExpr(se);
            case Expr.NewArray na -> newArray(na);
            case Expr.ArrayAccess a -> tryOp(new RExpr.MethodCall(ref(a.array()), "get", List.of(value(a.index()))));
            case Expr.ArrayLength a -> tryOp(new RExpr.MethodCall(ref(a.array()), "length", List.of()));
            case Expr.StringConcat sc -> {
                List<RExpr> parts = new ArrayList<>();
                for (Expr p : sc.parts()) {
                    parts.add(concatPart(p));
                }
                yield new RExpr.Macro("jconcat", parts);
            }
            // 参照として渡す位置（ジェネリックな引数など）では todo!() の型が決まらないので、型を明示する。
            case Expr.Unsupported u -> byRef
                    ? new RExpr.Call(new RExpr.Path("jrt::unsupported::<" + (u.type() instanceof JType.Void ? "()" : cx.types().text(u.type())) + ">"),
                            List.of(new RExpr.Lit(quoted("J2R: " + u.description()))))
                    : todo(u.description());
        };
    }

    /** this（外側のインスタンスは {@code Inner::of(this).__outer} をたどる）。 */
    private RExpr thisExpr(Expr.This t, boolean byRef) {
        RExpr cur = new RExpr.Path("this");
        if (t.outerLevels() == 0) {
            return byRef ? cur : new RExpr.MethodCall(cur, "clone", List.of());
        }
        ProgramIndex.TypeInfo level = owner;
        boolean isRef = true;
        for (int i = 0; i < t.outerLevels() && level != null; i++) {
            RExpr obj = isRef ? cur : new RExpr.Unary("&", cur);
            cur = new RExpr.MethodCall(new RExpr.MethodCall(new RExpr.Field(new RExpr.Call(new RExpr.Path(imp.type(level) + "::of"), List.of(obj)),
                    "__outer"), "borrow", List.of()), "clone", List.of());
            isRef = false;
            level = level.decl().outer() == null ? null : cx.index().type(level.decl().outer());
        }
        return cur;
    }

    /** 代入式の値: {@code { pre; let t = v; write(t); t }}。 */
    private RExpr valueBlock(List<RStmt> pre, Place p, RExpr v) {
        String t = temp();
        List<RStmt> stmts = new ArrayList<>(pre);
        stmts.add(new RStmt.Let(t, false, null, v));
        RExpr stored = TypeMapper.isCopy(p.type()) ? new RExpr.Path(t) : new RExpr.MethodCall(new RExpr.Path(t), "clone", List.of());
        stmts.add(new RStmt.ExprStmt(write(p, stored), true));
        return new RExpr.Block(stmts, new RExpr.Path(t), null, true);
    }

    /** 文字列連結・文字列化の引数: 参照で渡し、char は JChar で包み、文字列リテラル・null は &str のまま渡す。 */
    private RExpr concatPart(Expr p) {
        if (p instanceof Expr.Literal l && l.value() instanceof String s) {
            return new RExpr.Lit(stringLiteralText(s, l.pos()));
        }
        if (p instanceof Expr.Literal l && l.value() == null) {
            return new RExpr.Lit("\"null\"");
        }
        if (JType.isPrimitive(p.type(), JType.Kind.CHAR)) {
            return new RExpr.Call(new RExpr.Path("JChar"), List.of(value(p)));
        }
        if (cx.types().kind(p.type()) == TypeMapper.Kind.OBJECT) {
            // 参照型は toString() を呼ぶ（例外を送出しうる）。
            return stringOf(p);
        }
        return ref(p);
    }

    /** {@code jrt::string_of(&x)?}（参照型の文字列化。null は "null"）。 */
    private RExpr stringOf(Expr p) {
        return tryOp(new RExpr.Call(new RExpr.Path("jrt::string_of"), List.of(borrow(p))));
    }

    private RExpr literal(Expr.Literal l, boolean suffix) {
        if (l.value() == null) {
            return cx.types().nullValue(l.type());
        }
        if (l.value() instanceof String s) {
            return stringLiteral(s, l.pos());
        }
        return RustLiterals.number(l.value(), l.type(), suffix);
    }

    private RExpr stringLiteral(String s, SourcePos pos) {
        return new RExpr.Macro("jstr", List.of(new RExpr.Lit(stringLiteralText(s, pos))));
    }

    private String stringLiteralText(String s, SourcePos pos) {
        boolean[] lossy = new boolean[1];
        String lit = RustLiterals.stringLiteral(s, lossy);
        if (lossy[0]) {
            cx.diags().report(DiagnosticCode.LOSSY_SURROGATE, pos, "string literal contains an unpaired surrogate; replaced with U+FFFD");
        }
        return lit;
    }

    private RExpr staticField(Expr.StaticField f) {
        if (f.name().equals("class")) {
            // クラスリテラル X.class（同じクラスなら同じ Class オブジェクト）。
            ProgramIndex.TypeInfo ti = cx.index().type(f.owner());
            String name = ti != null ? Lowerer.binaryName(cx.index(), ti.decl()) : f.owner();
            return new RExpr.Call(new RExpr.Path("jrt::util::misc::class_for"), List.of(new RExpr.Lit(quoted(name))));
        }
        ProgramIndex.FieldInfo fi = cx.index().field(f.owner(), f.name());
        if (fi != null) {
            return switch (fi.storage()) {
                case CONST -> new RExpr.Path(imp.item(fi.owner(), fi.rustName()));
                case ENUM_CONSTANT -> new RExpr.Call(new RExpr.Path(imp.type(fi.owner()) + "::" + fi.rustName()), List.of());
                default -> withClinit(clinitCall(fi.owner()), staticRead(imp.item(fi.owner(), fi.rustName()), fi.type()));
            };
        }
        String template = cx.mappings().field(f.owner(), f.name());
        if (template == null) {
            cx.diags().report(DiagnosticCode.UNSUPPORTED_API, f.pos(), "static field " + f.owner() + "." + f.name() + " has no mapping");
            return typedTodo(f.type(), "static field " + f.owner() + "." + f.name());
        }
        return expandTemplate(template, null, List.of());
    }

    // ---------------------------------------------------------------- 演算

    private RExpr binaryExpr(Expr.Binary b) {
        JType ot = b.operandType();
        TypeMapper.Kind k = cx.types().kind(ot);
        if (b.op() == BinaryOp.EQ || b.op() == BinaryOp.NE) {
            RExpr test = null;
            boolean leftNull = b.left() instanceof Expr.Literal l && l.value() == null;
            boolean rightNull = b.right() instanceof Expr.Literal r && r.value() == null;
            if (k != TypeMapper.Kind.PRIMITIVE && (leftNull || rightNull)) {
                if (leftNull && rightNull) {
                    test = new RExpr.Lit("true");
                } else {
                    test = new RExpr.MethodCall(ref(leftNull ? b.right() : b.left()), "is_null", List.of());
                }
            } else if (k == TypeMapper.Kind.ARRAY) {
                test = new RExpr.Call(new RExpr.Path("JArray::ptr_eq"), List.of(borrow(b.left()), borrow(b.right())));
            } else if (k == TypeMapper.Kind.OBJECT) {
                test = new RExpr.MethodCall(ref(b.left()), "same", List.of(borrow(b.right())));
            } else if (k == TypeMapper.Kind.STRING || k == TypeMapper.Kind.MAPPED) {
                // String どうしの == / != は値の比較として変換する（診断は frontend で出している）。
                return new RExpr.Binary(b.op().symbol(), ref(b.left()), ref(b.right()));
            }
            if (test != null) {
                return b.op() == BinaryOp.EQ ? test : new RExpr.Unary("!", test);
            }
        }
        if (k != TypeMapper.Kind.PRIMITIVE) {
            return new RExpr.Binary(b.op().symbol(), ref(b.left()), ref(b.right()));
        }
        return binary(b.op(), value(b.left()), value(b.right()), ot, b.right());
    }

    /**
     * 同じ型 t の 2 値に対する二項演算。整数の加減乗算・シフトは Java と同じくラップアラウンドさせる。
     *
     * @param rightJir シフト量がリテラルかどうかの判定用
     */
    private RExpr binary(BinaryOp op, RExpr l, RExpr r, JType t, Expr rightJir) {
        boolean integral = JType.isPrimitive(t, JType.Kind.INT) || JType.isPrimitive(t, JType.Kind.LONG);
        boolean isLong = JType.isPrimitive(t, JType.Kind.LONG);
        if (integral) {
            switch (op) {
                case ADD, SUB, MUL -> {
                    String m = switch (op) {
                        case ADD -> "wrapping_add";
                        case SUB -> "wrapping_sub";
                        default -> "wrapping_mul";
                    };
                    return new RExpr.MethodCall(Conversions.withSuffix(l, t), m, List.of(r));
                }
                case DIV, REM -> {
                    if (rightJir instanceof Expr.Literal lit && lit.value() instanceof Number n && n.longValue() != 0) {
                        // 0 でない定数での除算は例外にならない（MIN / -1 も wrapping_div が Java と同じ結果になる）。
                        return new RExpr.MethodCall(Conversions.withSuffix(l, t), op == BinaryOp.DIV ? "wrapping_div" : "wrapping_rem", List.of(r));
                    }
                    String fn = "jrt::num::" + (op == BinaryOp.DIV ? "div" : "rem") + (isLong ? "_i64" : "_i32");
                    return tryOp(new RExpr.Call(new RExpr.Path(fn), List.of(l, r)));
                }
                case SHL, SHR -> {
                    return new RExpr.MethodCall(Conversions.withSuffix(l, t), op == BinaryOp.SHL ? "wrapping_shl" : "wrapping_shr",
                            List.of(shiftAmount(r, rightJir)));
                }
                case USHR -> {
                    RType unsigned = new RType(isLong ? "u64" : "u32");
                    RExpr shifted = new RExpr.MethodCall(new RExpr.Cast(Conversions.withSuffix(l, t), unsigned), "wrapping_shr",
                            List.of(shiftAmount(r, rightJir)));
                    return new RExpr.Cast(shifted, new RType(isLong ? "i64" : "i32"));
                }
                default -> { }
            }
        }
        String sym = switch (op) {
            case AND -> "&&";
            case OR -> "||";
            case USHR -> ">>";
            default -> op.symbol();
        };
        return new RExpr.Binary(sym, l, r);
    }

    /** シフト量（Rust の wrapping_shl は u32 を取り、ビット幅でマスクする。Java の意味と一致）。 */
    private static RExpr shiftAmount(RExpr r, Expr rightJir) {
        if (rightJir instanceof Expr.Literal lit && lit.value() instanceof Number n && n.longValue() >= 0 && n.longValue() < 64) {
            return new RExpr.Lit(Long.toString(n.longValue()));
        }
        return new RExpr.Cast(r, new RType("u32"));
    }

    private RExpr unary(Expr.Unary u) {
        JType t = u.type();
        return switch (u.op()) {
            case NEG -> {
                if (u.operand() instanceof Expr.Literal lit && lit.value() instanceof Number) {
                    Object neg = switch (lit.value()) {
                        case Integer i -> -i;
                        case Long l -> -l;
                        case Float f -> -f;
                        case Double d -> -d;
                        default -> null;
                    };
                    if (neg != null) {
                        yield RustLiterals.number(neg, t, false);
                    }
                }
                RExpr x = value(u.operand());
                yield JType.isFloating(t) ? new RExpr.Unary("-", x) : new RExpr.MethodCall(Conversions.withSuffix(x, t), "wrapping_neg", List.of());
            }
            case PLUS -> value(u.operand());
            case BIT_NOT, NOT -> new RExpr.Unary("!", value(u.operand()));
        };
    }

    private RExpr castExpr(Expr.Cast c) {
        JType from = c.expr().type();
        JType to = c.type();
        // リテラルは Java の変換規則でコンパイル時に畳み込む（例: (byte) 300 → 44i8）。
        if (from instanceof JType.Primitive && to instanceof JType.Primitive tp
                && c.expr() instanceof Expr.Literal lit && lit.value() != null && !(lit.value() instanceof Boolean)) {
            return RustLiterals.number(RustLiterals.convert(lit.value(), tp.kind()), to, false);
        }
        return conv.convert(value(c.expr()), from, to, !c.implicit());
    }

    // ---------------------------------------------------------------- instanceof

    private RExpr instanceOf(Expr.InstanceOf io) {
        JType from = io.expr().type();
        TypeMapper.Kind k = cx.types().kind(from);
        String target = io.target() instanceof JType.ClassType c ? c.qualifiedName()
                : io.target() instanceof JType.ArrayType ? io.target().javaName() : null;
        if (io.binding() == null) {
            if (k == TypeMapper.Kind.OBJECT && target != null) {
                return new RExpr.MethodCall(ref(io.expr()), "instance_of", List.of(new RExpr.Lit("\"" + target + "\"")));
            }
            return new RExpr.Unary("!", new RExpr.MethodCall(ref(io.expr()), "is_null", List.of()));
        }
        String t = temp();
        RExpr test = k == TypeMapper.Kind.OBJECT && target != null
                ? new RExpr.MethodCall(new RExpr.Path(t), "instance_of", List.of(new RExpr.Lit("\"" + target + "\"")))
                : new RExpr.Unary("!", new RExpr.MethodCall(new RExpr.Path(t), "is_null", List.of()));
        RExpr assign = new RExpr.Assign(new RExpr.Path(Naming.valueName(io.binding())), "=",
                conv.convert(new RExpr.MethodCall(new RExpr.Path(t), "clone", List.of()), from, io.target(), false));
        RExpr cond = new RExpr.If(test, new RExpr.Block(List.of(new RStmt.ExprStmt(assign, true)), new RExpr.Lit("true"), null, false),
                new RExpr.Block(List.of(), new RExpr.Lit("false"), null, false));
        return new RExpr.Block(List.of(new RStmt.Let(t, false, null, value(io.expr()))), cond, null, false);
    }

    // ---------------------------------------------------------------- 呼び出し

    private static boolean isObjectMethod(MethodRef r, String name, int params) {
        return !r.isStatic() && r.name().equals(name) && r.paramTypes().size() == params;
    }

    private RExpr call(Expr.Call c) {
        MethodRef r = c.method();
        Expr recv = c.receiver();
        // java.lang.Object のメソッド（toString / equals / hashCode）は、上書きを含めて Object トレイト経由で呼ぶ。
        if (recv != null && !c.superCall() && cx.types().kind(recv.type()) == TypeMapper.Kind.OBJECT) {
            if (isObjectMethod(r, "toString", 0)) {
                return tryOp(new RExpr.MethodCall(ref(recv), "to_jstring", List.of()));
            }
            if (isObjectMethod(r, "hashCode", 0)) {
                return tryOp(new RExpr.MethodCall(ref(recv), "hash_code", List.of()));
            }
            if (isObjectMethod(r, "equals", 1) && r.paramTypes().get(0).equals(JType.OBJECT)) {
                return tryOp(new RExpr.MethodCall(ref(recv), "equals", List.of(new RExpr.Unary("&", conv.toObject(value(c.args().get(0)), c.args().get(0).type())))));
            }
        }
        ProgramIndex.MethodInfo mi = cx.index().method(r.key());
        if (mi != null) {
            return programCall(c, mi);
        }
        ProgramIndex.TypeInfo ownerType = cx.index().type(r.owner());
        if (ownerType != null && ownerType.decl().kind() == Decl.TypeKind.ENUM && r.isStatic()) {
            if (r.name().equals("values") && r.paramTypes().isEmpty()) {
                return new RExpr.Call(new RExpr.Path(imp.type(ownerType) + "::values"), List.of());
            }
            if (r.name().equals("valueOf") && r.paramTypes().size() == 1) {
                return tryOp(new RExpr.Call(new RExpr.Path(imp.type(ownerType) + "::value_of"), List.of(value(c.args().get(0)))));
            }
        }
        if (c.superCall() && recv != null) {
            return superObjectCall(c);
        }
        String sig = r.signature();
        String template = null;
        if (recv != null && recv.type() instanceof JType.ClassType rt) {
            template = cx.mappings().method(rt.qualifiedName(), sig);
        }
        if (template == null) {
            template = cx.mappings().method(r.owner(), sig);
        }
        if (template == null && !r.isStatic() && (r.owner().endsWith("Exception") || r.owner().endsWith("Error"))) {
            // JDK の例外クラスが上書きしたメソッド（PatternSyntaxException.getMessage など）は Throwable の規則を使う。
            template = cx.mappings().method("java.lang.Throwable", sig);
        }
        if (template == null && !r.isStatic()) {
            template = cx.mappings().method("java.lang.Object", sig);
        }
        if (template != null) {
            return expandTemplate(template, recv, c.args());
        }
        if (!r.isStatic() && recv != null && (r.ownerInterface() || cx.types().kind(recv.type()) == TypeMapper.Kind.OBJECT)) {
            // マッピングのない JDK インタフェースのメソッド（関数型インタフェースなど）は、名前で呼ぶ。
            List<RExpr> boxed = new ArrayList<>();
            for (Expr a : c.args()) {
                boxed.add(conv.toObject(value(a), a.type()));
            }
            RExpr inv = tryOp(new RExpr.MethodCall(ref(recv), "invoke", List.of(new RExpr.Lit("\"" + r.name() + "\""),
                    new RExpr.Unary("&", new RExpr.Array(boxed)))));
            return conv.fromObject(inv, r.returnType());
        }
        cx.diags().report(DiagnosticCode.UNSUPPORTED_API, c.pos(), "no mapping for " + r.owner() + "#" + sig);
        return typedTodo(r.returnType(), "call " + r.owner() + "." + sig);
    }

    /** 型が決まる todo（ジェネリックな引数の位置でも型検査を通るように）。 */
    private RExpr typedTodo(JType t, String description) {
        if (t instanceof JType.Void) {
            return todo(description);
        }
        return new RExpr.Call(new RExpr.Path("jrt::unsupported::<" + cx.types().text(t) + ">"), List.of(new RExpr.Lit(quoted("J2R: " + description))));
    }

    /** super.toString() など、JDK のスーパークラスのメソッドの呼び出し。 */
    private RExpr superObjectCall(Expr.Call c) {
        MethodRef r = c.method();
        RExpr self = new RExpr.Path("this");
        if (isObjectMethod(r, "toString", 0)) {
            return new RExpr.Call(new RExpr.Path("jrt::object::default_to_string_of"), List.of(self));
        }
        if (isObjectMethod(r, "hashCode", 0)) {
            return new RExpr.Call(new RExpr.Path("jrt::util::misc::identity_hash_code"), List.of(self));
        }
        if (isObjectMethod(r, "equals", 1)) {
            return new RExpr.MethodCall(self, "same", List.of(new RExpr.Unary("&", conv.toObject(value(c.args().get(0)), c.args().get(0).type()))));
        }
        String template = cx.mappings().method(r.owner(), r.signature());
        if (template != null) {
            return expandTemplate(template, c.receiver(), c.args());
        }
        cx.diags().report(DiagnosticCode.UNSUPPORTED_API, c.pos(), "super call to " + r.owner() + "#" + r.signature());
        return todo("super." + r.name());
    }

    private RExpr programCall(Expr.Call c, ProgramIndex.MethodInfo mi) {
        MethodRef r = c.method();
        List<RExpr> args = new ArrayList<>();
        for (Expr a : c.args()) {
            args.add(value(a));
        }
        if (r.isStatic()) {
            return maybeTry(new RExpr.Call(new RExpr.Path(imp.type(mi.owner()) + "::" + mi.decl().rustName()), args), mi.decl());
        }
        Expr recv = c.receiver();
        RExpr recvRef = recv instanceof Expr.This t && t.outerLevels() == 0 ? new RExpr.Path("this")
                : c.superCall() ? new RExpr.Path("this")
                : tryOp(new RExpr.MethodCall(ref(recv), "nn", List.of()));
        List<RExpr> all = new ArrayList<>();
        all.add(recvRef);
        all.addAll(args);
        if (c.superCall()) {
            ProgramIndex.MethodInfo impl = superImplementation(mi, r.key());
            if (impl == null) {
                return superObjectCall(c);
            }
            return conv.callImpl(impl, r, all);
        }
        if (cx.hierarchy().needsDispatch(mi)) {
            return maybeTry(new RExpr.Call(new RExpr.Path(imp.type(mi.owner()) + "::" + mi.decl().rustName()), all), mi.decl());
        }
        ProgramIndex.MethodInfo impl = cx.hierarchy().targets(r).stream().findFirst().orElse(mi);
        if (impl == null) {
            impl = mi;
        }
        return conv.callImpl(impl, r, all);
    }

    /** 呼び先のプログラムのメソッド・コンストラクタが JResult を返すなら {@code call?}。 */
    private RExpr maybeTry(RExpr call, Decl.MethodDecl callee) {
        return cx.throwing().isThrowing(callee) ? tryOp(call) : call;
    }

    /**
     * {@code super.m()} の呼び先: 現在のクラスのスーパークラスから（インタフェースの default メソッドの場合は
     * そのインタフェースで）実装を探す。
     */
    private ProgramIndex.MethodInfo superImplementation(ProgramIndex.MethodInfo declared, String key) {
        if (declared.owner().decl().isInterface()) {
            return declared.decl().body() != null ? declared : null;
        }
        String sup = owner.decl().superclass();
        ProgramIndex.TypeInfo st = sup == null ? null : cx.index().type(sup);
        while (st != null) {
            for (Decl.MethodDecl m : st.decl().methods()) {
                if (m.body() != null && !m.isStatic() && (m.ref().key().equals(key) || m.overrides().contains(key))) {
                    return new ProgramIndex.MethodInfo(m, st);
                }
            }
            st = st.decl().superclass() == null ? null : cx.index().type(st.decl().superclass());
        }
        return null;
    }

    private RExpr newObject(Expr.New n) {
        MethodRef ctor = n.constructor();
        ProgramIndex.MethodInfo mi = cx.index().method(ctor.key());
        if (mi != null) {
            List<RExpr> args = new ArrayList<>();
            if (n.outer() != null) {
                args.add(value(n.outer()));
            }
            for (Expr a : n.args()) {
                args.add(value(a));
            }
            return maybeTry(new RExpr.Call(new RExpr.Path(imp.type(mi.owner()) + "::" + mi.decl().rustName()), args), mi.decl());
        }
        if (n.jdkThrowable()) {
            return newThrowable(ctor, n.args(), n.pos());
        }
        String template = cx.mappings().constructor(ctor.owner(), ctor.signature());
        if (template == null) {
            cx.diags().report(DiagnosticCode.UNSUPPORTED_API, n.pos(), "no mapping for new " + ctor.owner() + ctor.signature().substring("<init>".length()));
            return typedTodo(n.type(), "new " + ctor.owner());
        }
        return expandTemplate(template, null, n.args());
    }

    /** JDK の例外クラスの生成: (), (String), (String, Throwable), (Throwable)。 */
    private RExpr newThrowable(MethodRef ctor, List<Expr> args, SourcePos pos) {
        RExpr cls = new RExpr.Lit("\"" + ctor.owner() + "\"");
        List<JType> ps = ctor.paramTypes();
        if (ps.isEmpty()) {
            return new RExpr.Call(new RExpr.Path("jrt::lang::throwable::new_throwable"), List.of(cls, cx.types().nullValue(JType.STRING),
                    cx.types().nullValue(JType.OBJECT)));
        }
        if (ps.size() == 1 && JType.isString(ps.get(0))) {
            return new RExpr.Call(new RExpr.Path("jrt::lang::throwable::new_throwable"), List.of(cls, value(args.get(0)),
                    cx.types().nullValue(JType.OBJECT)));
        }
        if (ps.size() == 2 && JType.isString(ps.get(0))) {
            return new RExpr.Call(new RExpr.Path("jrt::lang::throwable::new_throwable"), List.of(cls, value(args.get(0)), value(args.get(1))));
        }
        if (ps.size() == 1) {
            return tryOp(new RExpr.Call(new RExpr.Path("jrt::lang::throwable::new_throwable_with_cause"), List.of(cls, value(args.get(0)))));
        }
        cx.diags().report(DiagnosticCode.UNSUPPORTED_API, pos, "constructor " + ctor.owner() + ctor.signature());
        return todo("new " + ctor.owner());
    }

    /** コンストラクタの先頭の this(...) / super(...)。 */
    private RExpr ctorCall(Expr.CtorCall c) {
        MethodRef ctor = c.constructor();
        ProgramIndex.MethodInfo mi = cx.index().method(ctor.key());
        RExpr self = new RExpr.Path("this");
        if (mi != null) {
            List<RExpr> args = new ArrayList<>();
            args.add(self);
            if (mi.owner().decl().hasOuterInstance()) {
                args.add(c.outer() != null && !c.isSuper() ? value(c.outer())
                        : c.isSuper() && c.outer() != null ? value(c.outer())
                        : new RExpr.Call(new RExpr.Path("JObject::null"), List.of()));
            }
            for (Expr a : c.args()) {
                args.add(value(a));
            }
            return maybeTry(new RExpr.Call(new RExpr.Path(imp.type(mi.owner()) + "::" + initName(mi.decl().rustName())), args), mi.decl());
        }
        if (c.isSuper()) {
            // JDK の例外クラスのコンストラクタ（Throwable の状態を初期化する）。
            List<JType> ps = ctor.paramTypes();
            RExpr nullStr = cx.types().nullValue(JType.STRING);
            RExpr nullObj = cx.types().nullValue(JType.OBJECT);
            String init = "jrt::lang::throwable::Throwable::init";
            if (ps.isEmpty()) {
                return new RExpr.Call(new RExpr.Path(init), List.of(self, nullStr, nullObj));
            }
            if (ps.size() == 1 && JType.isString(ps.get(0))) {
                return new RExpr.Call(new RExpr.Path(init), List.of(self, value(c.args().get(0)), nullObj));
            }
            if (ps.size() >= 2 && JType.isString(ps.get(0))) {
                return new RExpr.Call(new RExpr.Path(init), List.of(self, value(c.args().get(0)), value(c.args().get(1))));
            }
            if (ps.size() == 1) {
                return tryOp(new RExpr.Call(new RExpr.Path("jrt::lang::throwable::Throwable::init_cause_only"), List.of(self, value(c.args().get(0)))));
            }
        }
        cx.diags().report(DiagnosticCode.UNSUPPORTED_OOP, c.pos(), "constructor call to " + ctor.owner() + ctor.signature());
        return todo("constructor call " + ctor.owner());
    }

    /** new / new_i32 → __init / __init_i32。 */
    static String initName(String ctorRustName) {
        return "__init" + ctorRustName.substring("new".length());
    }

    private RExpr newArray(Expr.NewArray na) {
        if (na.initializer() != null) {
            List<RExpr> elems = new ArrayList<>();
            for (Expr e : na.initializer()) {
                elems.add(value(e));
            }
            RExpr vec = new RExpr.Macro("vec", elems);
            if (elems.isEmpty()) {
                return new RExpr.Call(new RExpr.Path("JArray::<" + cx.types().text(na.type().component()) + ">::from_vec"), List.of(new RExpr.Path("Vec::new()")));
            }
            return new RExpr.Call(new RExpr.Path("JArray::from_vec"), List.of(vec));
        }
        // new T[d0][d1]...: 外側から new_with で作る。長さは左から順に一度だけ評価する。
        List<RStmt> pre = new ArrayList<>();
        List<RExpr> dims = new ArrayList<>();
        for (Expr d : na.dims()) {
            RExpr v = value(d);
            if (!isSimple(v) && na.dims().size() > 1) {
                String t = temp();
                pre.add(new RStmt.Let(t, false, null, v));
                v = new RExpr.Path(t);
            }
            dims.add(v);
        }
        JType leaf = na.type();
        for (int i = 0; i < dims.size(); i++) {
            leaf = ((JType.ArrayType) leaf).component();
        }
        // 各段は JResult（負の長さは NegativeArraySizeException）。内側の段はクロージャの戻り値としてそのまま返す。
        RExpr inner = new RExpr.Call(new RExpr.Path("JArray::<" + cx.types().text(leaf) + ">::new"), List.of(dims.get(dims.size() - 1)));
        for (int i = dims.size() - 2; i >= 0; i--) {
            inner = new RExpr.Call(new RExpr.Path("JArray::new_with"), List.of(dims.get(i), RExpr.Closure.of(List.of("_"), inner)));
        }
        inner = tryOp(inner);
        return pre.isEmpty() ? inner : new RExpr.Block(pre, inner, null, true);
    }

    // ---------------------------------------------------------------- ラムダ

    /**
     * ラムダ: {@code jrt::lambda(&["Iface"], { let x = x.clone(); move |__args: &[JObject]| -> JObject { ... } })}。
     * 捕捉する変数は（Java では実質 final なので）複製してクロージャに移す。
     */
    private RExpr lambda(Expr.Lambda l) {
        Set<String> declared = new java.util.HashSet<>();
        for (Decl.Param p : l.params()) {
            declared.add(p.name());
        }
        Map<String, JType> free = new LinkedHashMap<>();
        boolean[] usesThis = {false};
        JirVisitor.walk(l.body(), s -> {
            switch (s) {
                case Stmt.LocalVar v -> declared.add(v.name());
                case Stmt.Try t -> t.catches().forEach(c -> declared.add(c.var()));
                default -> { }
            }
        }, e -> {
            switch (e) {
                case Expr.Local loc -> free.putIfAbsent(loc.name(), loc.type());
                case Expr.This t -> usesThis[0] = true;
                case Expr.Lambda inner -> inner.params().forEach(p -> declared.add(p.name()));
                case Expr.InstanceOf io when io.binding() != null -> declared.add(io.binding());
                case Expr.Bind b -> declared.add(b.name());
                default -> { }
            }
        });
        collectBindingsInto(l.body(), declared);
        List<RStmt> captures = new ArrayList<>();
        for (var entry : free.entrySet()) {
            if (declared.contains(entry.getKey()) || TypeMapper.isCopy(entry.getValue())) {
                continue;
            }
            String n = Naming.valueName(entry.getKey());
            captures.add(new RStmt.Let(n, false, null, new RExpr.MethodCall(new RExpr.Path(n), "clone", List.of())));
        }
        if (usesThis[0]) {
            captures.add(new RStmt.Let("__this", false, null, new RExpr.MethodCall(new RExpr.Path("this"), "clone", List.of())));
        }
        JType ret = l.sam().returnType();
        FnLowerer inner = new FnLowerer(cx, owner, imp, ret, true, l.params(), l.body(), true);
        List<RStmt> body = new ArrayList<>();
        if (usesThis[0]) {
            body.add(new RStmt.Let("this", false, null, new RExpr.Unary("&", new RExpr.Path("__this"))));
        }
        for (int i = 0; i < l.params().size(); i++) {
            Decl.Param p = l.params().get(i);
            RExpr arg = new RExpr.MethodCall(new RExpr.Index(new RExpr.Path("__args"), i), "clone", List.of());
            body.add(new RStmt.Let(Naming.valueName(p.name()), inner.isMutable(p.name()), cx.types().rust(p.type()),
                    inner.conversions().fromObject(arg, p.type())));
        }
        RExpr.Block lowered = inner.body(l.body().stmts(), false);
        body.addAll(lowered.stmts());
        RExpr tail = endsWithJump(l.body().stmts()) ? null
                : Conversions.ok(new RExpr.Call(new RExpr.Path("JObject::null"), List.of()));
        RExpr closure = new RExpr.Closure(true, List.of("__args: &[JObject]"), new RType("JResult<JObject>"),
                new RExpr.Block(body, tail, null, false));
        List<RExpr> names = new ArrayList<>();
        for (String i : l.interfaces()) {
            names.add(new RExpr.Lit("\"" + i + "\""));
        }
        RExpr fnExpr = captures.isEmpty() ? closure : new RExpr.Block(captures, closure, null, false);
        return new RExpr.Call(new RExpr.Path("jrt::lambda"), List.of(new RExpr.Unary("&", new RExpr.Array(names)), fnExpr));
    }

    private static void collectBindingsInto(Stmt body, Set<String> out) {
        Map<String, JType> m = new java.util.HashMap<>();
        collectBindings(body, m);
        out.addAll(m.keySet());
    }

    // ---------------------------------------------------------------- マッピング規則のテンプレート

    /**
     * マッピング規則のテンプレートを展開する。
     * <ul>
     *   <li>{@code {this}}: レシーバ（参照）</li>
     *   <li>{@code {N}}: 第 N 引数（値）</li>
     *   <li>{@code {&N}}: 第 N 引数への参照</li>
     *   <li>{@code {sN}}: 文字列化する引数（参照。char は JChar、文字列リテラルは &str）</li>
     * </ul>
     * JDK のメソッドへの引数では、String・配列から Object への暗黙の変換は行わない（jrt の関数が
     * {@code impl Into<JObject>} などで受け取る）。同じ引数が複数回現れ、単純な式でなければ一時変数に束縛する。
     */
    private RExpr expandTemplate(String template, Expr receiver, List<Expr> rawArgs) {
        List<Expr> args = new ArrayList<>();
        for (Expr a : rawArgs) {
            args.add(stripRefWidening(a));
        }
        List<Object> parts = new ArrayList<>();
        Map<Integer, Integer> uses = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(&?this|&?\\d+|s\\d+)}").matcher(template);
        while (m.find()) {
            String tok = m.group(1);
            if (!tok.endsWith("this")) {
                uses.merge(Integer.parseInt(tok.replaceAll("[^0-9]", "")), 1, Integer::sum);
            }
        }
        List<RStmt> pre = new ArrayList<>();
        Map<Integer, RExpr> hoisted = new HashMap<>();
        uses.forEach((i, count) -> {
            if (count > 1 && i < args.size()) {
                RExpr v = value(args.get(i));
                if (!isSimple(v)) {
                    String t = temp();
                    pre.add(new RStmt.Let(t, false, null, v));
                    hoisted.put(i, new RExpr.Path(t));
                }
            }
        });

        m.reset();
        int last = 0;
        while (m.find()) {
            parts.add(template.substring(last, m.start()));
            String after = template.substring(m.end());
            String tok = m.group(1);
            int minPrec = contextPrecedence(template.substring(0, m.start()), after);
            RExpr piece;
            if (tok.equals("this")) {
                piece = receiver == null ? todo("template uses {this} without receiver") : ref(receiver);
            } else if (tok.equals("&this")) {
                piece = receiver == null ? todo("template uses {&this} without receiver") : borrow(receiver);
            } else {
                int i = Integer.parseInt(tok.replaceAll("[^0-9]", ""));
                if (i >= args.size()) {
                    piece = todo("template argument {" + tok + "} out of range");
                } else {
                    Expr arg = args.get(i);
                    if (tok.startsWith("s")) {
                        piece = hoisted.containsKey(i) ? new RExpr.Unary("&", hoisted.get(i)) : stringifyArg(arg);
                    } else if (tok.startsWith("&")) {
                        piece = hoisted.containsKey(i) ? new RExpr.Unary("&", hoisted.get(i)) : borrow(arg);
                    } else if (hoisted.containsKey(i)) {
                        piece = hoisted.get(i);
                    } else if (arg instanceof Expr.Literal lit && minPrec >= RustPrinter.P_POSTFIX && lit.value() != null
                            && !(lit.value() instanceof String)) {
                        piece = literal(lit, true);
                    } else {
                        piece = value(arg);
                    }
                }
            }
            parts.add(new RExpr.Group(piece, minPrec));
            last = m.end();
        }
        parts.add(template.substring(last));
        if (hasTry(template)) {
            markFallible();
        }
        RExpr result = new RExpr.Template(parts);
        return pre.isEmpty() ? result : new RExpr.Block(pre, result, null, true);
    }

    /** テンプレートが（文字列リテラルの外に）{@code ?} を含むか（例外を送出しうる JDK の API）。 */
    static boolean hasTry(String template) {
        boolean inString = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '?') {
                return true;
            }
        }
        return false;
    }

    /** String / 配列から参照型への暗黙の変換を取り除く（JDK のメソッドの引数用）。 */
    private Expr stripRefWidening(Expr e) {
        if (e instanceof Expr.Cast c && c.implicit()) {
            TypeMapper.Kind from = cx.types().kind(c.expr().type());
            TypeMapper.Kind to = cx.types().kind(c.type());
            if ((from == TypeMapper.Kind.STRING || from == TypeMapper.Kind.ARRAY || from == TypeMapper.Kind.MAPPED) && to == TypeMapper.Kind.OBJECT) {
                return c.expr();
            }
            // String[] → Object[] など（jrt の関数は要素型について汎用）。
            if (from == TypeMapper.Kind.ARRAY && to == TypeMapper.Kind.ARRAY) {
                return c.expr();
            }
        }
        return e;
    }

    private RExpr stringifyArg(Expr arg) {
        if (arg instanceof Expr.Literal l && l.value() instanceof String s) {
            return new RExpr.Lit(stringLiteralText(s, l.pos()));
        }
        if (arg instanceof Expr.Literal l && l.value() == null) {
            return new RExpr.Lit("\"null\"");
        }
        if (JType.isPrimitive(arg.type(), JType.Kind.CHAR)) {
            return new RExpr.Unary("&", new RExpr.Call(new RExpr.Path("JChar"), List.of(value(arg))));
        }
        if (cx.types().kind(arg.type()) == TypeMapper.Kind.OBJECT) {
            return new RExpr.Unary("&", stringOf(arg));
        }
        return borrow(arg);
    }

    /** テンプレート中のプレースホルダの前後から、置く式に必要な優先順位を決める。 */
    private static int contextPrecedence(String before, String after) {
        String b = before.stripTrailing();
        String a = after.stripLeading();
        if (a.startsWith(".")) {
            return RustPrinter.P_POSTFIX;
        }
        if (b.endsWith("&") || b.endsWith("!") || b.endsWith("*") || (b.endsWith("-") && !b.endsWith("->"))) {
            return RustPrinter.P_UNARY;
        }
        boolean openBefore = b.endsWith("(") || b.endsWith(",") || b.endsWith("[");
        boolean closeAfter = a.startsWith(")") || a.startsWith(",") || a.startsWith("]");
        return openBefore && closeAfter ? 0 : RustPrinter.P_POSTFIX;
    }

    static RExpr todo(String description) {
        return new RExpr.Macro("todo", List.of(new RExpr.Lit(quoted("J2R: " + description))));
    }

    static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 束縛変数（instanceof / case の型パターン）の名前。 */
    static Set<String> bindingNames(Stmt s) {
        Map<String, JType> m = new LinkedHashMap<>();
        collectBindings(s, m);
        return new LinkedHashSet<>(m.keySet());
    }
}
