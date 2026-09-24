package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.LocalMutability;
import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.BinaryOp;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.jir.UnaryOp;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import io.github.ykwyuta.j2r.rir.RustPrinter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JIR → RIR の変換（表現戦略 S0 = faithful モード）。規則は docs/03-translation-rules.md。
 */
public final class Lowerer {
    private final ProgramIndex index;
    private final ApiMappings mappings;
    private final Diagnostics diags;
    private final TypeMapper types;

    public Lowerer(ProgramIndex index, ApiMappings mappings, Diagnostics diags) {
        this.index = index;
        this.mappings = mappings;
        this.diags = diags;
        this.types = new TypeMapper(mappings);
    }

    public LoweredCrate lower(Decl.Program program, String mainClass) {
        List<RFile> files = new ArrayList<>();
        List<List<String>> modules = new ArrayList<>();
        for (ProgramIndex.TypeInfo ti : index.types()) {
            files.add(lowerType(ti, program));
            modules.add(ti.modulePath());
        }
        List<String> main = null;
        if (mainClass != null) {
            ProgramIndex.TypeInfo ti = index.type(mainClass);
            if (ti == null || ti.decl().methods().stream().noneMatch(Decl.MethodDecl::isMain)) {
                diags.report(DiagnosticCode.NO_MAIN, SourcePos.UNKNOWN, "main class " + mainClass + " has no 'public static void main(String[])'");
            } else {
                main = ti.modulePath();
            }
        } else {
            List<ProgramIndex.TypeInfo> mains = index.mainTypes();
            if (mains.size() == 1) {
                main = mains.get(0).modulePath();
            } else if (mains.size() > 1) {
                diags.report(DiagnosticCode.NO_MAIN, SourcePos.UNKNOWN, "several classes have a main method; specify one with --main-class"
                        + " (candidates: " + mains.stream().map(t -> t.decl().qualifiedName()).toList() + ")");
            } else {
                diags.report(DiagnosticCode.NO_MAIN, SourcePos.UNKNOWN, "no main method found; generating a library crate only");
            }
        }
        return new LoweredCrate(files, modules, main);
    }

    // ------------------------------------------------------------------ 型（クラス）→ ファイル

    private RFile lowerType(ProgramIndex.TypeInfo ti, Decl.Program program) {
        Decl.TypeDecl t = ti.decl();
        String source = program.units().stream().filter(u -> u.types().contains(t)).map(Decl.CompilationUnit::sourcePath)
                .findFirst().orElse("?");
        List<String> header = new ArrayList<>();
        header.add("Translated from `" + t.qualifiedName() + "` (" + fileName(source) + ") by Java2RustTranslator.");
        if (t.javadoc() != null) {
            header.add("");
            header.addAll(docLines(t.javadoc()));
        }
        List<RItem> items = new ArrayList<>();
        items.add(new RItem.Use(List.of("allow(unused_imports)"), "jrt::prelude::*"));
        for (Decl.FieldDecl f : t.fields()) {
            items.add(lowerField(ti, f));
        }
        for (Decl.MethodDecl m : t.methods()) {
            items.add(lowerMethod(ti, m));
        }
        String path = String.join("/", ti.modulePath()) + ".rs";
        return new RFile(path, header, List.of(), items);
    }

    private static String fileName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static List<String> docLines(String javadoc) {
        List<String> out = new ArrayList<>();
        for (String line : javadoc.strip().split("\n")) {
            out.add(line.strip());
        }
        return out;
    }

    private RItem lowerField(ProgramIndex.TypeInfo ti, Decl.FieldDecl f) {
        ProgramIndex.FieldInfo info = index.field(ti.decl().qualifiedName(), f.name());
        List<String> docs = f.javadoc() == null ? List.of() : docLines(f.javadoc());
        RType rt = types.rust(f.type());
        FnCtx ctx = new FnCtx(ti, Set.of());
        if (info.storage() == ProgramIndex.FieldStorage.CONST) {
            RExpr value = JType.isString(f.type()) ? stringLiteral((String) f.constantValue(), f.pos())
                    : RustLiterals.number(f.constantValue(), f.type(), false);
            return new RItem.Const(docs, f.isPublic(), info.rustName(), rt, value);
        }
        RExpr init;
        if (f.init() != null) {
            init = value(ctx, f.init());
        } else if (f.type() instanceof JType.Primitive p) {
            init = defaultValue(p);
        } else {
            diags.report(DiagnosticCode.LOSSY_NULL, f.pos(), "static field '" + f.name()
                    + "' has no initializer; Java's null is represented by the type's default value");
            init = new RExpr.Call(new RExpr.Path(rt.text() + "::default"), List.of());
        }
        boolean copy = TypeMapper.isCopy(f.type());
        String cell = copy ? "std::cell::Cell" : "std::cell::RefCell";
        return new RItem.ThreadLocal(docs, f.isPublic(), info.rustName(), new RType(cell + "<" + rt.text() + ">"),
                new RExpr.Call(new RExpr.Path(cell + "::new"), List.of(init)));
    }

    private static RExpr defaultValue(JType.Primitive p) {
        return switch (p.kind()) {
            case BOOLEAN -> new RExpr.Lit("false");
            case FLOAT -> new RExpr.Lit("0.0f32");
            case DOUBLE -> new RExpr.Lit("0.0");
            case CHAR -> new RExpr.Lit("0u16");
            default -> RustLiterals.integer(0, p.kind(), false);
        };
    }

    private RItem.Fn lowerMethod(ProgramIndex.TypeInfo ti, Decl.MethodDecl m) {
        Set<String> mutable = LocalMutability.mutableLocals(m);
        FnCtx ctx = new FnCtx(ti, mutable);
        List<RItem.Param> params = new ArrayList<>();
        for (Decl.Param p : m.params()) {
            params.add(new RItem.Param(Naming.valueName(p.name()), mutable.contains(p.name()), types.rust(p.type())));
        }
        List<Stmt> body = m.body().stmts();
        List<RStmt> out = new ArrayList<>();
        RExpr tail = null;
        int n = body.size();
        if (n > 0 && body.get(n - 1) instanceof Stmt.Return r && r.value() != null) {
            for (int i = 0; i < n - 1; i++) {
                stmt(ctx, body.get(i), out);
            }
            tail = value(ctx, r.value());
        } else {
            for (Stmt s : body) {
                stmt(ctx, s, out);
            }
        }
        List<String> docs = m.javadoc() == null ? List.of() : docLines(m.javadoc());
        RType ret = m.ref().returnType() instanceof JType.Void ? null : types.rust(m.ref().returnType());
        return new RItem.Fn(docs, m.isPublic(), m.rustName(), params, ret, new RExpr.Block(out, tail, null, false));
    }

    // ------------------------------------------------------------------ 関数ごとの状態

    /** Rust 側のループ・ラベル付きブロック。label は使われたときだけ出力する。 */
    private static final class RustFrame {
        final String label;
        final boolean isLoop;
        boolean used;

        RustFrame(String label, boolean isLoop) {
            this.label = label;
            this.isLoop = isLoop;
        }

        String labelIfUsed() {
            return used ? label : null;
        }
    }

    /** Java の break / continue の飛び先。 */
    private record JavaTarget(String javaLabel, boolean isLoop, boolean isSwitch, RustFrame breakFrame,
                              RustFrame continueFrame, boolean continueIsBreak) {}

    private final class FnCtx {
        final ProgramIndex.TypeInfo owner;
        final Set<String> mutableLocals;
        final Deque<RustFrame> frames = new ArrayDeque<>();
        final Deque<JavaTarget> targets = new ArrayDeque<>();
        int temps;
        int labels;

        FnCtx(ProgramIndex.TypeInfo owner, Set<String> mutableLocals) {
            this.owner = owner;
            this.mutableLocals = mutableLocals;
        }

        String temp() {
            return "__t" + temps++;
        }

        String freshLabel(String javaLabel, String prefix) {
            if (javaLabel != null) {
                String l = Naming.toSnakeCase(javaLabel);
                return Naming.escape(l).equals(l) ? l : l + "_";
            }
            return prefix + ++labels;
        }
    }

    // ------------------------------------------------------------------ 文

    private RExpr.Block body(FnCtx ctx, Stmt s) {
        List<RStmt> out = new ArrayList<>();
        if (s instanceof Stmt.Block b) {
            for (Stmt x : b.stmts()) {
                stmt(ctx, x, out);
            }
        } else {
            stmt(ctx, s, out);
        }
        return RExpr.Block.of(out);
    }

    private void stmt(FnCtx ctx, Stmt s, List<RStmt> out) {
        switch (s) {
            case Stmt.Block b -> out.add(new RStmt.ExprStmt(body(ctx, b), false));
            case Stmt.LocalVar v -> out.add(new RStmt.Let(Naming.valueName(v.name()), ctx.mutableLocals.contains(v.name()),
                    types.rust(v.type()), v.init() == null ? null : value(ctx, v.init())));
            case Stmt.ExprStmt e -> exprStmt(ctx, e.expr(), out);
            case Stmt.If i -> out.add(new RStmt.ExprStmt(ifStmt(ctx, i), false));
            case Stmt.While w -> whileLoop(ctx, w, null, out);
            case Stmt.DoWhile d -> doWhileLoop(ctx, d, null, out);
            case Stmt.For f -> forLoop(ctx, f, null, out);
            case Stmt.Labeled l -> labeled(ctx, l, out);
            case Stmt.Switch sw -> switchStmt(ctx, sw, out);
            case Stmt.Return r -> out.add(new RStmt.ExprStmt(new RExpr.Return(r.value() == null ? null : value(ctx, r.value())), true));
            case Stmt.Break b -> out.add(new RStmt.ExprStmt(breakExpr(ctx, b), true));
            case Stmt.Continue c -> out.add(new RStmt.ExprStmt(continueExpr(ctx, c), true));
            case Stmt.ThrowNew t -> out.add(new RStmt.ExprStmt(new RExpr.Call(new RExpr.Path("jrt::throw_new"), List.of(
                    new RExpr.Lit("\"" + t.exceptionClass() + "\""),
                    t.message() == null ? new RExpr.Path("None") : new RExpr.Call(new RExpr.Path("Some"), List.of(value(ctx, t.message()))))), true));
            case Stmt.ForEach fe -> out.add(new RStmt.ExprStmt(todo("enhanced for over " + fe.iterable().type().javaName()), true));
            case Stmt.Unsupported u -> out.add(new RStmt.ExprStmt(todo(u.description()), true));
        }
    }

    private RExpr ifStmt(FnCtx ctx, Stmt.If i) {
        RExpr cond = value(ctx, i.condition());
        RExpr.Block then = body(ctx, i.thenStmt());
        RExpr elseBranch = null;
        if (i.elseStmt() instanceof Stmt.If nested) {
            elseBranch = ifStmt(ctx, nested);
        } else if (i.elseStmt() != null) {
            elseBranch = body(ctx, i.elseStmt());
        }
        return new RExpr.If(cond, then, elseBranch);
    }

    private static boolean isTrue(Expr e) {
        return e instanceof Expr.Literal l && Boolean.TRUE.equals(l.value());
    }

    private void whileLoop(FnCtx ctx, Stmt.While w, String javaLabel, List<RStmt> out) {
        RExpr cond = isTrue(w.condition()) ? null : value(ctx, w.condition());
        RustFrame f = new RustFrame(ctx.freshLabel(javaLabel, "l"), true);
        ctx.frames.push(f);
        ctx.targets.push(new JavaTarget(javaLabel, true, false, f, f, false));
        RExpr.Block b = body(ctx, w.body());
        ctx.targets.pop();
        ctx.frames.pop();
        out.add(new RStmt.ExprStmt(cond == null ? new RExpr.Loop(f.labelIfUsed(), b) : new RExpr.While(f.labelIfUsed(), cond, b), false));
    }

    private void doWhileLoop(FnCtx ctx, Stmt.DoWhile d, String javaLabel, List<RStmt> out) {
        RustFrame loop = new RustFrame(ctx.freshLabel(javaLabel, "l"), true);
        RustFrame cont = hasContinue(d.body(), javaLabel) ? new RustFrame(loop.label + "_body", false) : null;
        ctx.frames.push(loop);
        if (cont != null) {
            ctx.frames.push(cont);
        }
        ctx.targets.push(new JavaTarget(javaLabel, true, false, loop, cont != null ? cont : loop, cont != null));
        RExpr.Block b = body(ctx, d.body());
        ctx.targets.pop();
        if (cont != null) {
            ctx.frames.pop();
        }
        List<RStmt> stmts = new ArrayList<>();
        if (cont != null) {
            stmts.add(new RStmt.ExprStmt(new RExpr.Block(b.stmts(), null, cont.label, false), false));
        } else {
            stmts.addAll(b.stmts());
        }
        if (!isTrue(d.condition())) {
            RExpr notCond = new RExpr.Unary("!", value(ctx, d.condition()));
            stmts.add(new RStmt.ExprStmt(new RExpr.If(notCond, RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Break(null, null), true))), null), false));
        }
        ctx.frames.pop();
        out.add(new RStmt.ExprStmt(new RExpr.Loop(loop.labelIfUsed(), RExpr.Block.of(stmts)), false));
    }

    private void forLoop(FnCtx ctx, Stmt.For f, String javaLabel, List<RStmt> out) {
        List<RStmt> scope = new ArrayList<>();
        for (Stmt init : f.init()) {
            stmt(ctx, init, scope);
        }
        RExpr cond = f.condition() == null || isTrue(f.condition()) ? null : value(ctx, f.condition());
        RustFrame loop = new RustFrame(ctx.freshLabel(javaLabel, "l"), true);
        boolean needCont = !f.update().isEmpty() && hasContinue(f.body(), javaLabel);
        RustFrame cont = needCont ? new RustFrame(loop.label + "_body", false) : null;
        ctx.frames.push(loop);
        if (cont != null) {
            ctx.frames.push(cont);
        }
        ctx.targets.push(new JavaTarget(javaLabel, true, false, loop, cont != null ? cont : loop, cont != null));
        RExpr.Block b = body(ctx, f.body());
        ctx.targets.pop();
        if (cont != null) {
            ctx.frames.pop();
        }
        List<RStmt> stmts = new ArrayList<>();
        if (cont != null) {
            stmts.add(new RStmt.ExprStmt(new RExpr.Block(b.stmts(), null, cont.label, false), false));
        } else {
            stmts.addAll(b.stmts());
        }
        for (Expr u : f.update()) {
            exprStmt(ctx, u, stmts);
        }
        ctx.frames.pop();
        RExpr loopExpr = cond == null ? new RExpr.Loop(loop.labelIfUsed(), RExpr.Block.of(stmts))
                : new RExpr.While(loop.labelIfUsed(), cond, RExpr.Block.of(stmts));
        if (scope.isEmpty()) {
            out.add(new RStmt.ExprStmt(loopExpr, false));
        } else {
            scope.add(new RStmt.ExprStmt(loopExpr, false));
            out.add(new RStmt.ExprStmt(RExpr.Block.of(scope), false));
        }
    }

    private void labeled(FnCtx ctx, Stmt.Labeled l, List<RStmt> out) {
        switch (l.body()) {
            case Stmt.While w -> whileLoop(ctx, w, l.label(), out);
            case Stmt.DoWhile d -> doWhileLoop(ctx, d, l.label(), out);
            case Stmt.For f -> forLoop(ctx, f, l.label(), out);
            default -> {
                RustFrame f = new RustFrame(ctx.freshLabel(l.label(), "b"), false);
                ctx.frames.push(f);
                ctx.targets.push(new JavaTarget(l.label(), false, false, f, null, false));
                RExpr.Block b = body(ctx, l.body());
                ctx.targets.pop();
                ctx.frames.pop();
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
            // 入れ子のループでは、ラベル付き continue だけがこのループを対象にしうる。
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
            case Stmt.While w -> hasLabeledContinue(w.body(), javaLabel);
            case Stmt.DoWhile d -> hasLabeledContinue(d.body(), javaLabel);
            case Stmt.For f -> hasLabeledContinue(f.body(), javaLabel);
            default -> false;
        };
    }

    private RExpr breakExpr(FnCtx ctx, Stmt.Break b) {
        JavaTarget t = findTarget(ctx, b.label(), false);
        if (t == null) {
            return todo("break without target");
        }
        return new RExpr.Break(labelFor(ctx, t.breakFrame()), null);
    }

    private RExpr continueExpr(FnCtx ctx, Stmt.Continue c) {
        JavaTarget t = findTarget(ctx, c.label(), true);
        if (t == null || t.continueFrame() == null) {
            return todo("continue without target");
        }
        String label = labelFor(ctx, t.continueFrame());
        return t.continueIsBreak() ? new RExpr.Break(label, null) : new RExpr.Continue(label);
    }

    private static JavaTarget findTarget(FnCtx ctx, String label, boolean forContinue) {
        for (JavaTarget t : ctx.targets) {
            if (label != null ? label.equals(t.javaLabel()) : (t.isLoop() || (!forContinue && t.isSwitch()))) {
                return t;
            }
        }
        return null;
    }

    /** 最も内側の Rust フレームがループ自身なら無ラベルで済む。それ以外はラベルを付けて使用済みにする。 */
    private static String labelFor(FnCtx ctx, RustFrame target) {
        if (target.isLoop && ctx.frames.peek() == target) {
            return null;
        }
        target.used = true;
        return target.label;
    }

    private void switchStmt(FnCtx ctx, Stmt.Switch sw, List<RStmt> out) {
        // 空の case（case 1: case 2: ...）を次の case にまとめる。
        record Group(List<Object> labels, boolean isDefault, List<Stmt> body, boolean arrow) {}
        List<Group> groups = new ArrayList<>();
        List<Object> pending = new ArrayList<>();
        boolean pendingDefault = false;
        for (Stmt.SwitchCase c : sw.cases()) {
            pending.addAll(c.labels());
            pendingDefault |= c.isDefault();
            if (c.arrow() || !c.body().isEmpty()) {
                groups.add(new Group(List.copyOf(pending), pendingDefault, c.body(), c.arrow()));
                pending.clear();
                pendingDefault = false;
            }
        }
        if (!pending.isEmpty() || pendingDefault) {
            groups.add(new Group(List.copyOf(pending), pendingDefault, List.of(), false));
        }
        for (int i = 0; i < groups.size() - 1; i++) {
            Group g = groups.get(i);
            if (!g.arrow() && !endsWithJump(g.body())) {
                diags.report(DiagnosticCode.UNSUPPORTED_SYNTAX, sw.pos(), "switch with fall-through between cases is not supported yet");
                out.add(new RStmt.ExprStmt(todo("switch with fall-through"), true));
                return;
            }
        }
        boolean stringSelector = JType.isString(sw.selector().type());
        RExpr scrutinee = stringSelector ? new RExpr.MethodCall(ref(ctx, sw.selector()), "as_str", List.of()) : value(ctx, sw.selector());

        RustFrame f = new RustFrame(ctx.freshLabel(null, "sw"), false);
        ctx.frames.push(f);
        ctx.targets.push(new JavaTarget(null, false, true, f, null, false));
        List<RExpr.Arm> arms = new ArrayList<>();
        RExpr.Arm defaultArm = null;
        for (Group g : groups) {
            List<Stmt> stmts = new ArrayList<>(g.body());
            if (!stmts.isEmpty() && stmts.get(stmts.size() - 1) instanceof Stmt.Break b && b.label() == null) {
                stmts.remove(stmts.size() - 1);
            }
            RExpr.Block armBody = body(ctx, new Stmt.Block(stmts, sw.pos()));
            if (g.isDefault()) {
                defaultArm = new RExpr.Arm("_", armBody);
            } else {
                List<String> pats = new ArrayList<>();
                for (Object label : g.labels()) {
                    pats.add(switch (label) {
                        case String s -> RustLiterals.stringLiteral(s, new boolean[1]);
                        case Character c -> RustLiterals.charPattern(c);
                        default -> label.toString();
                    });
                }
                arms.add(new RExpr.Arm(String.join(" | ", pats), armBody));
            }
        }
        ctx.targets.pop();
        ctx.frames.pop();
        arms.add(defaultArm != null ? defaultArm : new RExpr.Arm("_", RExpr.Block.of(List.of())));
        RExpr match = new RExpr.Match(scrutinee, arms);
        if (f.used) {
            out.add(new RStmt.ExprStmt(new RExpr.Block(List.of(new RStmt.ExprStmt(match, false)), null, f.label, false), false));
        } else {
            out.add(new RStmt.ExprStmt(match, false));
        }
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
            case Stmt.ThrowNew t -> true;
            case Stmt.Block b -> endsWithJump(b.stmts());
            default -> false;
        };
    }

    // ------------------------------------------------------------------ 式文・代入

    private void exprStmt(FnCtx ctx, Expr e, List<RStmt> out) {
        switch (e) {
            case Expr.Assign a -> {
                Place p = place(ctx, a.target(), out, false);
                out.add(new RStmt.ExprStmt(write(ctx, p, value(ctx, a.value())), true));
            }
            case Expr.CompoundAssign ca -> {
                Place p = place(ctx, ca.target(), out, true);
                if (p instanceof Place.Local l && canUseCompoundOperator(ca)) {
                    out.add(new RStmt.ExprStmt(new RExpr.Assign(new RExpr.Path(l.name()), ca.op().symbol() + "=", value(ctx, ca.value())), true));
                } else {
                    out.add(new RStmt.ExprStmt(write(ctx, p, compound(ctx, ca, read(p))), true));
                }
            }
            case Expr.IncDec id -> {
                Place p = place(ctx, id.target(), out, true);
                out.add(new RStmt.ExprStmt(write(ctx, p, step(read(p), id)), true));
            }
            default -> out.add(new RStmt.ExprStmt(value(ctx, e), true));
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
            case BIT_AND, BIT_OR, BIT_XOR -> true;
            default -> false;
        };
    }

    /** 代入先。 */
    private sealed interface Place {
        JType type();

        record Local(String name, JType type) implements Place {}

        /** thread_local の static（path は RIR のパス文字列）。 */
        record Static(String path, JType type) implements Place {}

        record ArrayElem(RExpr array, RExpr index, JType type) implements Place {}

        record Invalid(String reason, JType type) implements Place {}
    }

    /**
     * 代入先を評価する。hoist が true（読み出しと書き込みの両方を行う）で、配列要素の場合は
     * 配列と添字を一時変数に束縛して二重評価を防ぐ。
     */
    private Place place(FnCtx ctx, Expr target, List<RStmt> pre, boolean hoist) {
        return switch (target) {
            case Expr.Local l -> new Place.Local(Naming.valueName(l.name()), l.type());
            case Expr.StaticField f -> {
                ProgramIndex.FieldInfo fi = index.field(f.owner(), f.name());
                if (fi == null || fi.storage() != ProgramIndex.FieldStorage.THREAD_LOCAL) {
                    yield new Place.Invalid("assignment to " + f.owner() + "." + f.name(), f.type());
                }
                yield new Place.Static(staticPath(ctx, fi), f.type());
            }
            case Expr.ArrayAccess a -> {
                RExpr arr = ref(ctx, a.array());
                RExpr idx = value(ctx, a.index());
                if (hoist && !isSimple(arr)) {
                    String t = ctx.temp();
                    pre.add(new RStmt.Let(t, false, null, value(ctx, a.array())));
                    arr = new RExpr.Path(t);
                }
                if (hoist && !isSimple(idx)) {
                    String t = ctx.temp();
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
            case Place.Static s -> staticRead(s.path(), s.type());
            case Place.ArrayElem a -> new RExpr.MethodCall(a.array(), "get", List.of(a.index()));
            case Place.Invalid i -> todo(i.reason());
        };
    }

    private RExpr write(FnCtx ctx, Place p, RExpr v) {
        return switch (p) {
            case Place.Local l -> new RExpr.Assign(new RExpr.Path(l.name()), "=", v);
            case Place.Static s -> {
                RExpr c = new RExpr.Path("c");
                RExpr body = TypeMapper.isCopy(s.type())
                        ? new RExpr.MethodCall(c, "set", List.of(v))
                        : new RExpr.Assign(new RExpr.Unary("*", new RExpr.MethodCall(c, "borrow_mut", List.of())), "=", v);
                yield new RExpr.MethodCall(new RExpr.Path(s.path()), "with", List.of(new RExpr.Closure(List.of("c"), body)));
            }
            case Place.ArrayElem a -> new RExpr.MethodCall(a.array(), "set", List.of(a.index(), v));
            case Place.Invalid i -> {
                diags.report(DiagnosticCode.UNSUPPORTED_SYNTAX, SourcePos.UNKNOWN, i.reason());
                yield todo(i.reason());
            }
        };
    }

    private static RExpr staticRead(String path, JType type) {
        RExpr c = new RExpr.Path("c");
        RExpr body = TypeMapper.isCopy(type) ? new RExpr.MethodCall(c, "get", List.of())
                : new RExpr.MethodCall(new RExpr.MethodCall(c, "borrow", List.of()), "clone", List.of());
        return new RExpr.MethodCall(new RExpr.Path(path), "with", List.of(new RExpr.Closure(List.of("c"), body)));
    }

    private String staticPath(FnCtx ctx, ProgramIndex.FieldInfo fi) {
        return fi.owner() == ctx.owner ? fi.rustName() : fi.owner().rustPath() + "::" + fi.rustName();
    }

    /** 複合代入の右辺: {@code (T)(cur op value)}。 */
    private RExpr compound(FnCtx ctx, Expr.CompoundAssign ca, RExpr current) {
        if (JType.isString(ca.type())) {
            // jconcat! は参照で受け取るので、ローカル変数の clone は不要。
            RExpr cur = current instanceof RExpr.MethodCall mc && mc.method().equals("clone") && mc.args().isEmpty() ? mc.receiver() : current;
            return new RExpr.Macro("jconcat", List.of(cur, concatPart(ctx, ca.value())));
        }
        RExpr cur = cast(current, ca.type(), ca.operandType());
        RExpr result = binary(ca.op(), cur, value(ctx, ca.value()), ca.operandType(), ca.value());
        return cast(result, ca.operandType(), ca.type());
    }

    /** ++ / -- の 1 ステップ（byte/short/char も自身の型でラップアラウンドさせれば Java と一致する）。 */
    private static RExpr step(RExpr current, Expr.IncDec id) {
        JType t = id.type();
        if (JType.isFloating(t)) {
            RExpr one = new RExpr.Lit(JType.isPrimitive(t, JType.Kind.FLOAT) ? "1.0f32" : "1.0");
            return new RExpr.Binary(id.increment() ? "+" : "-", current, one);
        }
        return new RExpr.MethodCall(withSuffix(current, t), id.increment() ? "wrapping_add" : "wrapping_sub", List.of(new RExpr.Lit("1")));
    }

    // ------------------------------------------------------------------ 式

    /** 値として評価する（参照型のローカル変数は clone する）。 */
    private RExpr value(FnCtx ctx, Expr e) {
        return expr(ctx, e, false);
    }

    /** 参照として評価する（メソッドのレシーバ・& で渡す引数。ローカル変数を clone しない）。 */
    private RExpr ref(FnCtx ctx, Expr e) {
        return expr(ctx, e, true);
    }

    private RExpr expr(FnCtx ctx, Expr e, boolean byRef) {
        return switch (e) {
            case Expr.Literal l -> literal(l, false);
            case Expr.Local l -> {
                RExpr p = new RExpr.Path(Naming.valueName(l.name()));
                yield byRef || TypeMapper.isCopy(l.type()) ? p : new RExpr.MethodCall(p, "clone", List.of());
            }
            case Expr.StaticField f -> staticField(ctx, f);
            case Expr.Binary b -> binaryExpr(ctx, b);
            case Expr.Unary u -> unary(ctx, u);
            case Expr.Assign a -> {
                List<RStmt> pre = new ArrayList<>();
                Place p = place(ctx, a.target(), pre, false);
                yield valueBlock(ctx, pre, p, value(ctx, a.value()));
            }
            case Expr.CompoundAssign ca -> {
                List<RStmt> pre = new ArrayList<>();
                Place p = place(ctx, ca.target(), pre, true);
                yield valueBlock(ctx, pre, p, compound(ctx, ca, read(p)));
            }
            case Expr.IncDec id -> {
                List<RStmt> pre = new ArrayList<>();
                Place p = place(ctx, id.target(), pre, true);
                if (id.prefix()) {
                    yield valueBlock(ctx, pre, p, step(read(p), id));
                }
                String t = ctx.temp();
                pre.add(new RStmt.Let(t, false, null, read(p)));
                pre.add(new RStmt.ExprStmt(write(ctx, p, step(new RExpr.Path(t), id)), true));
                yield new RExpr.Block(pre, new RExpr.Path(t), null, true);
            }
            case Expr.Conditional c -> new RExpr.If(value(ctx, c.condition()),
                    new RExpr.Block(List.of(), value(ctx, c.whenTrue()), null, false),
                    new RExpr.Block(List.of(), value(ctx, c.whenFalse()), null, false));
            case Expr.Cast c -> castExpr(ctx, c);
            case Expr.Call c -> call(ctx, c);
            case Expr.New n -> newObject(ctx, n);
            case Expr.NewArray na -> newArray(ctx, na);
            case Expr.ArrayAccess a -> new RExpr.MethodCall(ref(ctx, a.array()), "get", List.of(value(ctx, a.index())));
            case Expr.ArrayLength a -> new RExpr.MethodCall(ref(ctx, a.array()), "length", List.of());
            case Expr.StringConcat sc -> {
                List<RExpr> parts = new ArrayList<>();
                for (Expr p : sc.parts()) {
                    parts.add(concatPart(ctx, p));
                }
                yield new RExpr.Macro("jconcat", parts);
            }
            // 参照として渡す位置（ジェネリックな引数など）では todo!() の型が決まらないので、型を明示する。
            case Expr.Unsupported u -> byRef
                    ? new RExpr.Call(new RExpr.Path("jrt::unsupported::<" + (u.type() instanceof JType.Void ? "()" : types.text(u.type())) + ">"),
                            List.of(new RExpr.Lit(quoted("J2R: " + u.description()))))
                    : todo(u.description());
        };
    }

    /** 代入式の値: {@code { pre; let t = v; write(t); t }}。 */
    private RExpr valueBlock(FnCtx ctx, List<RStmt> pre, Place p, RExpr v) {
        String t = ctx.temp();
        List<RStmt> stmts = new ArrayList<>(pre);
        stmts.add(new RStmt.Let(t, false, null, v));
        RExpr stored = TypeMapper.isCopy(p.type()) ? new RExpr.Path(t) : new RExpr.MethodCall(new RExpr.Path(t), "clone", List.of());
        stmts.add(new RStmt.ExprStmt(write(ctx, p, stored), true));
        return new RExpr.Block(stmts, new RExpr.Path(t), null, true);
    }

    /** 文字列連結・文字列化の引数: 参照で渡し、char は JChar で包み、文字列リテラルは &str のまま渡す。 */
    private RExpr concatPart(FnCtx ctx, Expr p) {
        if (p instanceof Expr.Literal l && l.value() instanceof String s) {
            return new RExpr.Lit(stringLiteralText(s, l.pos()));
        }
        if (JType.isPrimitive(p.type(), JType.Kind.CHAR)) {
            return new RExpr.Call(new RExpr.Path("JChar"), List.of(value(ctx, p)));
        }
        return ref(ctx, p);
    }

    private RExpr literal(Expr.Literal l, boolean suffix) {
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
            diags.report(DiagnosticCode.LOSSY_SURROGATE, pos, "string literal contains an unpaired surrogate; replaced with U+FFFD");
        }
        return lit;
    }

    /** メソッド呼び出しのレシーバになる数値リテラルには型接尾辞が必要（`1.wrapping_add(x)` は型が決まらない）。 */
    private static RExpr withSuffix(RExpr e, JType type) {
        if (e instanceof RExpr.Lit lit && type instanceof JType.Primitive p && p.kind() != JType.Kind.BOOLEAN
                && lit.text().matches("-?[0-9][0-9.eE+-]*")) {
            return new RExpr.Lit(lit.text() + TypeMapper.primitive(p.kind()));
        }
        return e;
    }

    private RExpr staticField(FnCtx ctx, Expr.StaticField f) {
        ProgramIndex.FieldInfo fi = index.field(f.owner(), f.name());
        if (fi != null) {
            String path = staticPath(ctx, fi);
            return fi.storage() == ProgramIndex.FieldStorage.CONST ? new RExpr.Path(path) : staticRead(path, f.type());
        }
        String template = mappings.field(f.owner(), f.name());
        if (template == null) {
            diags.report(DiagnosticCode.UNSUPPORTED_API, f.pos(), "static field " + f.owner() + "." + f.name() + " has no mapping");
            return todo("static field " + f.owner() + "." + f.name());
        }
        return expandTemplate(ctx, template, null, List.of());
    }

    // ------------------------------------------------------------------ 演算

    private RExpr binaryExpr(FnCtx ctx, Expr.Binary b) {
        JType ot = b.operandType();
        if (ot instanceof JType.ArrayType && (b.op() == BinaryOp.EQ || b.op() == BinaryOp.NE)) {
            RExpr same = new RExpr.Call(new RExpr.Path("JArray::ptr_eq"), List.of(
                    new RExpr.Unary("&", ref(ctx, b.left())), new RExpr.Unary("&", ref(ctx, b.right()))));
            return b.op() == BinaryOp.EQ ? same : new RExpr.Unary("!", same);
        }
        if (!(ot instanceof JType.Primitive)) {
            // String どうしの == / != は値の比較として変換する（診断は frontend で出している）。
            return new RExpr.Binary(b.op().symbol(), ref(ctx, b.left()), ref(ctx, b.right()));
        }
        return binary(b.op(), value(ctx, b.left()), value(ctx, b.right()), ot, b.right());
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
                    return new RExpr.MethodCall(withSuffix(l, t), m, List.of(r));
                }
                case DIV, REM -> {
                    String fn = "jrt::num::" + (op == BinaryOp.DIV ? "div" : "rem") + (isLong ? "_i64" : "_i32");
                    return new RExpr.Call(new RExpr.Path(fn), List.of(l, r));
                }
                case SHL, SHR -> {
                    return new RExpr.MethodCall(withSuffix(l, t), op == BinaryOp.SHL ? "wrapping_shl" : "wrapping_shr",
                            List.of(shiftAmount(r, rightJir)));
                }
                case USHR -> {
                    RType unsigned = new RType(isLong ? "u64" : "u32");
                    RExpr shifted = new RExpr.MethodCall(new RExpr.Cast(withSuffix(l, t), unsigned), "wrapping_shr", List.of(shiftAmount(r, rightJir)));
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

    private RExpr unary(FnCtx ctx, Expr.Unary u) {
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
                RExpr x = value(ctx, u.operand());
                yield JType.isFloating(t) ? new RExpr.Unary("-", x) : new RExpr.MethodCall(withSuffix(x, t), "wrapping_neg", List.of());
            }
            case PLUS -> value(ctx, u.operand());
            case BIT_NOT, NOT -> new RExpr.Unary("!", value(ctx, u.operand()));
        };
    }

    private RExpr castExpr(FnCtx ctx, Expr.Cast c) {
        JType from = c.expr().type();
        JType to = c.type();
        if (!(from instanceof JType.Primitive) || !(to instanceof JType.Primitive tp)) {
            return value(ctx, c.expr());
        }
        // リテラルは Java の変換規則でコンパイル時に畳み込む（例: (byte) 300 → 44i8）。
        if (c.expr() instanceof Expr.Literal lit && !(lit.value() instanceof Boolean)) {
            return RustLiterals.number(RustLiterals.convert(lit.value(), tp.kind()), to, false);
        }
        return cast(value(ctx, c.expr()), from, to);
    }

    /** プリミティブ型間の変換（docs/03-translation-rules.md §1）。 */
    private static RExpr cast(RExpr e, JType from, JType to) {
        if (from.equals(to)) {
            return e;
        }
        JType.Kind tk = ((JType.Primitive) to).kind();
        RType target = new RType(TypeMapper.primitive(tk));
        boolean narrowFromFloat = JType.isFloating(from)
                && (tk == JType.Kind.BYTE || tk == JType.Kind.SHORT || tk == JType.Kind.CHAR);
        // `-8 as u32` のようにリテラルの型が変換先から推論されないよう、リテラルには元の型の接尾辞を付ける。
        RExpr src = withSuffix(e, from);
        if (narrowFromFloat) {
            // Java は浮動小数点数 → byte/short/char をいったん int に変換してから縮小する。
            return new RExpr.Cast(new RExpr.Cast(src, new RType("i32")), target);
        }
        return new RExpr.Cast(src, target);
    }

    // ------------------------------------------------------------------ 呼び出し

    private RExpr call(FnCtx ctx, Expr.Call c) {
        ProgramIndex.MethodInfo mi = index.method(c.method().key());
        if (mi != null) {
            String name = mi.decl().rustName();
            String path = mi.owner() == ctx.owner ? name : mi.owner().rustPath() + "::" + name;
            List<RExpr> args = new ArrayList<>();
            for (Expr a : c.args()) {
                args.add(value(ctx, a));
            }
            return new RExpr.Call(new RExpr.Path(path), args);
        }
        String sig = c.method().signature();
        String template = null;
        if (c.receiver() != null && c.receiver().type() instanceof JType.ClassType rt) {
            template = mappings.method(rt.qualifiedName(), sig);
        }
        if (template == null) {
            template = mappings.method(c.method().owner(), sig);
        }
        if (template == null) {
            diags.report(DiagnosticCode.UNSUPPORTED_API, c.pos(), "no mapping for " + c.method().owner() + "#" + sig);
            return todo("call " + c.method().owner() + "." + sig);
        }
        return expandTemplate(ctx, template, c.receiver(), c.args());
    }

    private RExpr newObject(FnCtx ctx, Expr.New n) {
        String template = mappings.constructor(n.constructor().owner(), n.constructor().signature());
        if (template == null) {
            diags.report(DiagnosticCode.UNSUPPORTED_API, n.pos(), "no mapping for new " + n.constructor().owner() + n.constructor().signature().substring("<init>".length()));
            return todo("new " + n.constructor().owner());
        }
        return expandTemplate(ctx, template, null, n.args());
    }

    private RExpr newArray(FnCtx ctx, Expr.NewArray na) {
        if (na.initializer() != null) {
            List<RExpr> elems = new ArrayList<>();
            for (Expr e : na.initializer()) {
                elems.add(value(ctx, e));
            }
            return new RExpr.Call(new RExpr.Path("JArray::from_vec"), List.of(new RExpr.Macro("vec", elems)));
        }
        // new T[d0][d1]...: 外側から new_with で作る。長さは左から順に一度だけ評価する。
        List<RStmt> pre = new ArrayList<>();
        List<RExpr> dims = new ArrayList<>();
        for (Expr d : na.dims()) {
            RExpr v = value(ctx, d);
            if (!isSimple(v) && na.dims().size() > 1) {
                String t = ctx.temp();
                pre.add(new RStmt.Let(t, false, null, v));
                v = new RExpr.Path(t);
            }
            dims.add(v);
        }
        JType leaf = na.type();
        for (int i = 0; i < dims.size(); i++) {
            leaf = ((JType.ArrayType) leaf).component();
        }
        if (!(leaf instanceof JType.Primitive)) {
            diags.report(DiagnosticCode.LOSSY_NULL, na.pos(), "elements of new " + leaf.javaName()
                    + "[] are initialized with the type's default value instead of null");
        }
        RExpr inner = new RExpr.Call(new RExpr.Path("JArray::<" + types.text(leaf) + ">::new"), List.of(dims.get(dims.size() - 1)));
        for (int i = dims.size() - 2; i >= 0; i--) {
            inner = new RExpr.Call(new RExpr.Path("JArray::new_with"), List.of(dims.get(i), new RExpr.Closure(List.of("_"), inner)));
        }
        return pre.isEmpty() ? inner : new RExpr.Block(pre, inner, null, true);
    }

    /**
     * マッピング規則のテンプレートを展開する。
     * <ul>
     *   <li>{@code {this}}: レシーバ（参照）</li>
     *   <li>{@code {N}}: 第 N 引数（値）</li>
     *   <li>{@code {&N}}: 第 N 引数への参照</li>
     *   <li>{@code {sN}}: 文字列化する引数（参照。char は JChar、文字列リテラルは &str）</li>
     * </ul>
     * 同じ引数が複数回現れ、かつ単純な式でない場合は一時変数に束縛して評価を一度にする。
     */
    private RExpr expandTemplate(FnCtx ctx, String template, Expr receiver, List<Expr> args) {
        List<Object> parts = new ArrayList<>();
        Map<Integer, Integer> uses = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(this|&?\\d+|s\\d+)}").matcher(template);
        while (m.find()) {
            String tok = m.group(1);
            if (!tok.equals("this")) {
                uses.merge(Integer.parseInt(tok.replaceAll("[^0-9]", "")), 1, Integer::sum);
            }
        }
        List<RStmt> pre = new ArrayList<>();
        Map<Integer, RExpr> hoisted = new HashMap<>();
        uses.forEach((i, count) -> {
            if (count > 1 && i < args.size()) {
                RExpr v = value(ctx, args.get(i));
                if (!isSimple(v)) {
                    String t = ctx.temp();
                    pre.add(new RStmt.Let(t, false, null, v));
                    hoisted.put(i, new RExpr.Path(t));
                }
            }
        });

        m.reset();
        int last = 0;
        while (m.find()) {
            String before = template.substring(last, m.start());
            parts.add(before);
            String after = template.substring(m.end());
            String tok = m.group(1);
            int minPrec = contextPrecedence(template.substring(0, m.start()), after);
            RExpr piece;
            if (tok.equals("this")) {
                piece = receiver == null ? todo("template uses {this} without receiver") : ref(ctx, receiver);
            } else {
                int i = Integer.parseInt(tok.replaceAll("[^0-9]", ""));
                if (i >= args.size()) {
                    piece = todo("template argument {" + tok + "} out of range");
                } else {
                    Expr arg = args.get(i);
                    if (tok.startsWith("s")) {
                        RExpr part = hoisted.containsKey(i) ? new RExpr.Unary("&", hoisted.get(i)) : stringifyArg(ctx, arg);
                        piece = part;
                    } else if (tok.startsWith("&")) {
                        piece = new RExpr.Unary("&", hoisted.containsKey(i) ? hoisted.get(i) : ref(ctx, arg));
                    } else if (hoisted.containsKey(i)) {
                        piece = hoisted.get(i);
                    } else if (arg instanceof Expr.Literal lit && minPrec >= RustPrinter.P_POSTFIX && !(lit.value() instanceof String)) {
                        piece = literal(lit, true);
                    } else {
                        piece = value(ctx, arg);
                    }
                }
            }
            parts.add(new RExpr.Group(piece, minPrec));
            last = m.end();
        }
        parts.add(template.substring(last));
        RExpr result = new RExpr.Template(parts);
        return pre.isEmpty() ? result : new RExpr.Block(pre, result, null, true);
    }

    private RExpr stringifyArg(FnCtx ctx, Expr arg) {
        if (arg instanceof Expr.Literal l && l.value() instanceof String s) {
            return new RExpr.Lit(stringLiteralText(s, l.pos()));
        }
        if (JType.isPrimitive(arg.type(), JType.Kind.CHAR)) {
            return new RExpr.Unary("&", new RExpr.Call(new RExpr.Path("JChar"), List.of(value(ctx, arg))));
        }
        return new RExpr.Unary("&", ref(ctx, arg));
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

    private static RExpr todo(String description) {
        return new RExpr.Macro("todo", List.of(new RExpr.Lit(quoted("J2R: " + description))));
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
