package io.github.ykwyuta.j2r.analysis;

import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JirRewriter;
import io.github.ykwyuta.j2r.jir.Stmt;
import java.util.HashMap;
import java.util.HashSet;
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
        Map<String, Info> infos = new HashMap<>();
        for (Decl.Param p : method.params()) {
            Info i = new Info();
            i.hasInit = true;
            infos.put(p.name(), i);
        }
        walk(method.body(), 0, infos);
        Set<String> out = new HashSet<>();
        infos.forEach((name, i) -> {
            boolean lateInitOnce = !i.hasInit && i.assigns == 1 && !i.assignedDeeper;
            if (i.assigns > 0 && !lateInitOnce) {
                out.add(name);
            }
        });
        return out;
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
                sw.cases().forEach(c -> c.body().forEach(x -> walk(x, depth, infos)));
            }
            case Stmt.Return r -> expr(r.value(), depth, infos);
            case Stmt.Labeled l -> walk(l.body(), depth, infos);
            case Stmt.ThrowNew t -> expr(t.message(), depth, infos);
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
