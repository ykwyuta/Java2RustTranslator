package io.github.ykwyuta.j2r.jir;

import java.util.ArrayList;
import java.util.List;

/**
 * JIR の木をボトムアップに書き換える基底クラス。パスは {@link #rewriteExpr} / {@link #rewriteStmt} を
 * オーバーライドし、子を書き換えた後のノードを受け取って置換結果を返す。
 */
public abstract class JirRewriter {

    /** 子を書き換え済みの式を受け取り、置換後の式を返す。既定は恒等。 */
    protected Expr rewriteExpr(Expr e) {
        return e;
    }

    /** 子を書き換え済みの文を受け取り、置換後の文を返す。既定は恒等。 */
    protected Stmt rewriteStmt(Stmt s) {
        return s;
    }

    public final Decl.Program apply(Decl.Program program) {
        List<Decl.CompilationUnit> units = new ArrayList<>();
        for (Decl.CompilationUnit u : program.units()) {
            List<Decl.TypeDecl> types = new ArrayList<>();
            for (Decl.TypeDecl t : u.types()) {
                types.add(apply(t));
            }
            units.add(new Decl.CompilationUnit(u.packageName(), u.sourcePath(), types));
        }
        return new Decl.Program(units);
    }

    public Decl.TypeDecl apply(Decl.TypeDecl t) {
        List<Decl.FieldDecl> fields = new ArrayList<>();
        for (Decl.FieldDecl f : t.fields()) {
            fields.add(new Decl.FieldDecl(f.name(), f.type(), f.isFinal(), f.init() == null ? null : expr(f.init()),
                    f.constantValue(), f.isPublic(), f.javadoc(), f.pos()));
        }
        List<Decl.MethodDecl> methods = new ArrayList<>();
        for (Decl.MethodDecl m : t.methods()) {
            methods.add(m.withBody((Stmt.Block) stmt(m.body())));
        }
        return t.withFields(fields).withMethods(methods);
    }

    public final Expr expr(Expr e) {
        if (e == null) {
            return null;
        }
        Expr rebuilt = switch (e) {
            case Expr.Literal x -> x;
            case Expr.Local x -> x;
            case Expr.StaticField x -> x;
            case Expr.Unsupported x -> x;
            case Expr.Binary x -> new Expr.Binary(x.op(), expr(x.left()), expr(x.right()), x.operandType(), x.type(), x.pos());
            case Expr.Unary x -> new Expr.Unary(x.op(), expr(x.operand()), x.type(), x.pos());
            case Expr.Assign x -> new Expr.Assign(expr(x.target()), expr(x.value()), x.type(), x.pos());
            case Expr.CompoundAssign x -> new Expr.CompoundAssign(x.op(), expr(x.target()), expr(x.value()), x.operandType(), x.type(), x.pos());
            case Expr.IncDec x -> new Expr.IncDec(x.increment(), x.prefix(), expr(x.target()), x.type(), x.pos());
            case Expr.Conditional x -> new Expr.Conditional(expr(x.condition()), expr(x.whenTrue()), expr(x.whenFalse()), x.type(), x.pos());
            case Expr.Cast x -> new Expr.Cast(expr(x.expr()), x.type(), x.implicit(), x.pos());
            case Expr.Call x -> new Expr.Call(x.method(), expr(x.receiver()), exprs(x.args()), x.type(), x.pos());
            case Expr.New x -> new Expr.New(x.constructor(), exprs(x.args()), x.type(), x.pos());
            case Expr.NewArray x -> new Expr.NewArray(x.type(), exprs(x.dims()), x.initializer() == null ? null : exprs(x.initializer()), x.pos());
            case Expr.ArrayAccess x -> new Expr.ArrayAccess(expr(x.array()), expr(x.index()), x.type(), x.pos());
            case Expr.ArrayLength x -> new Expr.ArrayLength(expr(x.array()), x.type(), x.pos());
            case Expr.StringConcat x -> new Expr.StringConcat(exprs(x.parts()), x.type(), x.pos());
        };
        return rewriteExpr(rebuilt);
    }

    public final List<Expr> exprs(List<Expr> es) {
        List<Expr> out = new ArrayList<>(es.size());
        for (Expr e : es) {
            out.add(expr(e));
        }
        return out;
    }

    public final Stmt stmt(Stmt s) {
        if (s == null) {
            return null;
        }
        Stmt rebuilt = switch (s) {
            case Stmt.Block x -> new Stmt.Block(stmts(x.stmts()), x.pos());
            case Stmt.LocalVar x -> new Stmt.LocalVar(x.name(), x.type(), expr(x.init()), x.synthetic(), x.pos());
            case Stmt.ExprStmt x -> new Stmt.ExprStmt(expr(x.expr()), x.pos());
            case Stmt.If x -> new Stmt.If(expr(x.condition()), stmt(x.thenStmt()), stmt(x.elseStmt()), x.pos());
            case Stmt.While x -> new Stmt.While(expr(x.condition()), stmt(x.body()), x.pos());
            case Stmt.DoWhile x -> new Stmt.DoWhile(stmt(x.body()), expr(x.condition()), x.pos());
            case Stmt.For x -> new Stmt.For(stmts(x.init()), expr(x.condition()), exprs(x.update()), stmt(x.body()), x.pos());
            case Stmt.ForEach x -> new Stmt.ForEach(x.varName(), x.varType(), expr(x.iterable()), stmt(x.body()), x.pos());
            case Stmt.Switch x -> {
                List<Stmt.SwitchCase> cases = new ArrayList<>();
                for (Stmt.SwitchCase c : x.cases()) {
                    cases.add(new Stmt.SwitchCase(c.labels(), stmts(c.body()), c.arrow(), c.pos()));
                }
                yield new Stmt.Switch(expr(x.selector()), cases, x.pos());
            }
            case Stmt.Return x -> new Stmt.Return(expr(x.value()), x.pos());
            case Stmt.Break x -> x;
            case Stmt.Continue x -> x;
            case Stmt.Labeled x -> new Stmt.Labeled(x.label(), stmt(x.body()), x.pos());
            case Stmt.ThrowNew x -> new Stmt.ThrowNew(x.exceptionClass(), expr(x.message()), x.pos());
            case Stmt.Unsupported x -> x;
        };
        return rewriteStmt(rebuilt);
    }

    public final List<Stmt> stmts(List<Stmt> ss) {
        List<Stmt> out = new ArrayList<>(ss.size());
        for (Stmt s : ss) {
            out.add(stmt(s));
        }
        return out;
    }
}
