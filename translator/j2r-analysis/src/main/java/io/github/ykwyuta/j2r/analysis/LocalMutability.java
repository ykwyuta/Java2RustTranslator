package io.github.ykwyuta.j2r.analysis;

import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JirRewriter;
import io.github.ykwyuta.j2r.jir.Stmt;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ローカル変数・仮引数のうち {@code let mut} が必要なものを求める。
 *
 * <p>宣言後に代入されるものは mut。ただし初期化子なしで宣言され、宣言と同じループ深さで
 * ちょうど 1 回だけ代入されるもの（遅延初期化）は mut 不要。Java は同一メソッド内で
 * ローカル変数のシャドーイングを許さないため、名前単位で集計してよい（兄弟スコープの同名変数は
 * 合算するので安全側に倒れる）。
 */
public final class LocalMutability {
    private LocalMutability() {}

    private static final class Info {
        boolean hasInit;
        int declDepth;
        int assigns;
        boolean assignedDeeper;
    }

    public static Set<String> mutableLocals(Decl.MethodDecl method) {
        return mutableLocals(method.params(), method.body());
    }

    /**
     * @param params 仮引数（代入されれば mut）
     * @param body   本体。try 文を含む場合、初期化子のない変数は既定値で初期化される（lowering）ので、
     *               代入があれば常に mut にする
     */
    public static Set<String> mutableLocals(List<Decl.Param> params, Stmt body) {
        Map<String, Info> infos = new HashMap<>();
        for (Decl.Param p : params) {
            Info i = new Info();
            i.hasInit = true;
            infos.put(p.name(), i);
        }
        walk(body, 0, infos);
        boolean hasTry = containsTry(body);
        Set<String> out = new HashSet<>();
        infos.forEach((name, i) -> {
            boolean lateInitOnce = !hasTry && !i.hasInit && i.assigns == 1 && !i.assignedDeeper;
            if (i.assigns > 0 && !lateInitOnce) {
                out.add(name);
            }
        });
        return out;
    }

    /** try 文を含むか（ラムダの本体は除く）。 */
    public static boolean containsTry(Stmt body) {
        boolean[] found = {false};
        io.github.ykwyuta.j2r.jir.JirVisitor.walk(body, s -> found[0] |= s instanceof Stmt.Try, e -> { });
        return found[0];
    }

    private static void walk(Stmt s, int depth, Map<String, Info> infos) {
        if (s == null) {
            return;
        }
        switch (s) {
            case Stmt.Block b -> b.stmts().forEach(x -> walk(x, depth, infos));
            case Stmt.LocalVar v -> {
                Info i = infos.computeIfAbsent(v.name(), k -> new Info());
                i.hasInit |= v.init() != null;
                i.declDepth = depth;
                expr(v.init(), depth, infos);
            }
            case Stmt.ExprStmt e -> expr(e.expr(), depth, infos);
            case Stmt.If i -> {
                expr(i.condition(), depth, infos);
                walk(i.thenStmt(), depth, infos);
                walk(i.elseStmt(), depth, infos);
            }
            case Stmt.While w -> {
                expr(w.condition(), depth + 1, infos);
                walk(w.body(), depth + 1, infos);
            }
            case Stmt.DoWhile d -> {
                walk(d.body(), depth + 1, infos);
                expr(d.condition(), depth + 1, infos);
            }
            case Stmt.For f -> {
                f.init().forEach(x -> walk(x, depth, infos));
                expr(f.condition(), depth + 1, infos);
                f.update().forEach(x -> expr(x, depth + 1, infos));
                walk(f.body(), depth + 1, infos);
            }
            case Stmt.ForEach fe -> {
                expr(fe.iterable(), depth, infos);
                walk(fe.body(), depth + 1, infos);
            }
            case Stmt.Switch sw -> {
                expr(sw.selector(), depth, infos);
                sw.cases().forEach(c -> {
                    expr(c.guard(), depth, infos);
                    c.body().forEach(x -> walk(x, depth, infos));
                });
            }
            case Stmt.Return r -> expr(r.value(), depth, infos);
            case Stmt.Yield y -> expr(y.value(), depth, infos);
            case Stmt.Labeled l -> walk(l.body(), depth, infos);
            case Stmt.Throw t -> expr(t.exception(), depth, infos);
            case Stmt.Try t -> {
                // try の本体・catch 節はクロージャになり、繰り返し実行されうるものとして扱う。
                walk(t.body(), depth + 1, infos);
                t.catches().forEach(c -> walk(c.body(), depth + 1, infos));
                walk(t.finallyBlock(), depth, infos);
            }
            case Stmt.Break b -> { }
            case Stmt.Continue c -> { }
            case Stmt.Unsupported u -> { }
        }
    }

    private static void expr(Expr e, int depth, Map<String, Info> infos) {
        if (e == null) {
            return;
        }
        new JirRewriter() {
            @Override
            protected Expr rewriteExpr(Expr x) {
                Expr target = switch (x) {
                    case Expr.Assign a -> a.target();
                    case Expr.CompoundAssign a -> a.target();
                    case Expr.IncDec a -> a.target();
                    default -> null;
                };
                if (x instanceof Expr.Lambda lam) {
                    walk(lam.body(), depth + 1, infos);
                }
                if (x instanceof Expr.SwitchExpr se) {
                    se.cases().forEach(c -> c.body().forEach(st -> walk(st, depth, infos)));
                }
                if (target instanceof Expr.Local l) {
                    Info i = infos.computeIfAbsent(l.name(), k -> new Info());
                    i.assigns++;
                    if (depth > i.declDepth || !(x instanceof Expr.Assign)) {
                        i.assignedDeeper = true;
                    }
                }
                return x;
            }
        }.expr(e);
    }
}
