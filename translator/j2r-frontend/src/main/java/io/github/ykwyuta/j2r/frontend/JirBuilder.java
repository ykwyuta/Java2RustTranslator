package io.github.ykwyuta.j2r.frontend;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DefaultCaseLabelTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.BinaryOp;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.MethodRef;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.jir.UnaryOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * 属性付け済みの javac 構文木を JIR に変換する。
 *
 * <p>暗黙の数値変換（昇格・代入変換）はここで {@link Expr.Cast}（implicit）として明示化する。
 * 変換できない構文は {@link Expr.Unsupported} / {@link Stmt.Unsupported} にして診断を出す。
 */
final class JirBuilder {
    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final CompilationUnitTree cu;
    private final Set<String> programTypes;
    private final Diagnostics diags;
    private final String file;
    private final TypeMirror throwableType;

    private JType currentReturnType = JType.VOID;

    JirBuilder(JavacTask task, Trees trees, CompilationUnitTree cu, Set<String> programTypes, Diagnostics diags) {
        this.trees = trees;
        this.types = task.getTypes();
        this.elements = task.getElements();
        this.cu = cu;
        this.programTypes = programTypes;
        this.diags = diags;
        this.file = cu.getSourceFile().getName();
        this.throwableType = elements.getTypeElement("java.lang.Throwable").asType();
    }

    Decl.CompilationUnit build() {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        List<Decl.TypeDecl> typeDecls = new ArrayList<>();
        TreePath root = new TreePath(cu);
        for (Tree t : cu.getTypeDecls()) {
            if (t instanceof ClassTree ct) {
                Decl.TypeDecl td = typeDecl(new TreePath(root, ct), pkg);
                if (td != null) {
                    typeDecls.add(td);
                }
            }
        }
        return new Decl.CompilationUnit(pkg, file, typeDecls);
    }

    // ------------------------------------------------------------------ 宣言

    private Decl.TypeDecl typeDecl(TreePath path, String pkg) {
        ClassTree ct = (ClassTree) path.getLeaf();
        TypeElement te = (TypeElement) trees.getElement(path);
        if (te.getKind() != ElementKind.CLASS) {
            unsupportedDecl(DiagnosticCode.UNSUPPORTED_OOP, ct, te.getKind().name().toLowerCase(java.util.Locale.ROOT)
                    + " '" + te.getSimpleName() + "' is not supported yet (only classes with static members)");
            return null;
        }
        if (te.getSuperclass() instanceof DeclaredType st
                && !((TypeElement) st.asElement()).getQualifiedName().contentEquals("java.lang.Object")) {
            unsupportedDecl(DiagnosticCode.UNSUPPORTED_OOP, ct, "class inheritance is not supported yet (" + te.getSimpleName() + ")");
        }
        List<Decl.FieldDecl> fields = new ArrayList<>();
        List<Decl.MethodDecl> methods = new ArrayList<>();
        for (Tree member : ct.getMembers()) {
            TreePath mp = new TreePath(path, member);
            switch (member) {
                case VariableTree vt -> {
                    VariableElement ve = (VariableElement) trees.getElement(mp);
                    if (ve.getModifiers().contains(Modifier.STATIC)) {
                        fields.add(fieldDecl(mp, vt, ve));
                    } else {
                        unsupportedDecl(DiagnosticCode.UNSUPPORTED_OOP, vt, "instance field '" + ve.getSimpleName() + "' is not supported yet");
                    }
                }
                case MethodTree mt -> {
                    ExecutableElement ee = (ExecutableElement) trees.getElement(mp);
                    if (ee.getKind() == ElementKind.CONSTRUCTOR) {
                        if (elements.getOrigin(ee) != Elements.Origin.MANDATED) {
                            unsupportedDecl(DiagnosticCode.UNSUPPORTED_OOP, mt, "constructors are not supported yet");
                        }
                    } else if (!ee.getModifiers().contains(Modifier.STATIC)) {
                        unsupportedDecl(DiagnosticCode.UNSUPPORTED_OOP, mt, "instance method '" + ee.getSimpleName() + "' is not supported yet");
                    } else if (mt.getBody() == null) {
                        unsupportedDecl(DiagnosticCode.UNSUPPORTED_SYNTAX, mt, "method without body '" + ee.getSimpleName() + "' (native/abstract)");
                    } else {
                        methods.add(methodDecl(mp, mt, ee));
                    }
                }
                case BlockTree bt -> unsupportedDecl(bt.isStatic() ? DiagnosticCode.UNSUPPORTED_SYNTAX : DiagnosticCode.UNSUPPORTED_OOP,
                        bt, (bt.isStatic() ? "static" : "instance") + " initializer blocks are not supported yet");
                case ClassTree nested -> unsupportedDecl(DiagnosticCode.UNSUPPORTED_OOP, nested,
                        "nested type '" + nested.getSimpleName() + "' is not supported yet");
                default -> unsupportedDecl(DiagnosticCode.UNSUPPORTED_SYNTAX, member, "unsupported member: " + member.getKind());
            }
        }
        return new Decl.TypeDecl(te.getSimpleName().toString(), te.getQualifiedName().toString(), pkg,
                fields, methods, elements.getDocComment(te), pos(ct));
    }

    private Decl.FieldDecl fieldDecl(TreePath path, VariableTree vt, VariableElement ve) {
        JType type = type(ve.asType());
        reportIfUnsupportedType(type, vt);
        Expr init = vt.getInitializer() == null ? null : coerce(expr(new TreePath(path, vt.getInitializer())), type);
        return new Decl.FieldDecl(ve.getSimpleName().toString(), type, ve.getModifiers().contains(Modifier.FINAL),
                init, ve.getConstantValue(), !ve.getModifiers().contains(Modifier.PRIVATE), elements.getDocComment(ve), pos(vt));
    }

    private Decl.MethodDecl methodDecl(TreePath path, MethodTree mt, ExecutableElement ee) {
        MethodRef ref = methodRef(ee);
        List<Decl.Param> params = new ArrayList<>();
        for (VariableElement p : ee.getParameters()) {
            JType pt = type(p.asType());
            reportIfUnsupportedType(pt, mt);
            params.add(new Decl.Param(p.getSimpleName().toString(), pt));
        }
        JType ret = type(ee.getReturnType());
        reportIfUnsupportedType(ret, mt);
        currentReturnType = ret;
        Stmt.Block body = (Stmt.Block) stmt(new TreePath(path, mt.getBody()));
        boolean isMain = ee.getSimpleName().contentEquals("main")
                && ret instanceof JType.Void
                && params.size() == 1
                && params.get(0).type().equals(new JType.ArrayType(JType.STRING));
        return new Decl.MethodDecl(ref, params, body, !ee.getModifiers().contains(Modifier.PRIVATE), isMain, null,
                elements.getDocComment(ee), pos(mt));
    }

    private MethodRef methodRef(ExecutableElement ee) {
        List<JType> ps = new ArrayList<>();
        for (VariableElement p : ee.getParameters()) {
            ps.add(type(types.erasure(p.asType())));
        }
        String owner = ((TypeElement) ee.getEnclosingElement()).getQualifiedName().toString();
        String name = ee.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : ee.getSimpleName().toString();
        return new MethodRef(owner, name, ps, type(types.erasure(ee.getReturnType())), ee.getModifiers().contains(Modifier.STATIC));
    }

    // ------------------------------------------------------------------ 文

    private Stmt stmt(TreePath path) {
        Tree t = path.getLeaf();
        SourcePos pos = pos(t);
        return switch (t) {
            case BlockTree b -> {
                List<Stmt> out = new ArrayList<>();
                for (StatementTree st : b.getStatements()) {
                    if (!(st instanceof EmptyStatementTree)) {
                        out.add(stmt(new TreePath(path, st)));
                    }
                }
                yield new Stmt.Block(out, pos);
            }
            case VariableTree v -> {
                Element el = trees.getElement(path);
                JType vt = type(el.asType());
                reportIfUnsupportedType(vt, v);
                Expr init = v.getInitializer() == null ? null : coerce(expr(new TreePath(path, v.getInitializer())), vt);
                yield new Stmt.LocalVar(v.getName().toString(), vt, init, false, pos);
            }
            case ExpressionStatementTree es -> new Stmt.ExprStmt(expr(new TreePath(path, es.getExpression())), pos);
            case IfTree it -> new Stmt.If(condition(path, it.getCondition()), stmt(new TreePath(path, it.getThenStatement())),
                    it.getElseStatement() == null ? null : stmt(new TreePath(path, it.getElseStatement())), pos);
            case WhileLoopTree w -> new Stmt.While(condition(path, w.getCondition()), stmt(new TreePath(path, w.getStatement())), pos);
            case DoWhileLoopTree d -> new Stmt.DoWhile(stmt(new TreePath(path, d.getStatement())), condition(path, d.getCondition()), pos);
            case ForLoopTree f -> {
                List<Stmt> init = new ArrayList<>();
                for (StatementTree st : f.getInitializer()) {
                    init.add(stmt(new TreePath(path, st)));
                }
                List<Expr> update = new ArrayList<>();
                for (ExpressionStatementTree es : f.getUpdate()) {
                    update.add(expr(new TreePath(new TreePath(path, es), es.getExpression())));
                }
                Expr cond = f.getCondition() == null ? null : condition(path, f.getCondition());
                yield new Stmt.For(init, cond, update, stmt(new TreePath(path, f.getStatement())), pos);
            }
            case EnhancedForLoopTree ef -> {
                TreePath vp = new TreePath(path, ef.getVariable());
                JType vt = type(trees.getElement(vp).asType());
                Expr iterable = expr(new TreePath(path, ef.getExpression()));
                if (!(iterable.type() instanceof JType.ArrayType)) {
                    yield unsupportedStmt(DiagnosticCode.UNSUPPORTED_API, ef, "enhanced for over Iterable is not supported yet");
                }
                yield new Stmt.ForEach(ef.getVariable().getName().toString(), vt, iterable, stmt(new TreePath(path, ef.getStatement())), pos);
            }
            case SwitchTree sw -> switchStmt(path, sw);
            case ReturnTree r -> new Stmt.Return(r.getExpression() == null ? null
                    : coerce(expr(new TreePath(path, r.getExpression())), currentReturnType), pos);
            case BreakTree b -> new Stmt.Break(b.getLabel() == null ? null : b.getLabel().toString(), pos);
            case ContinueTree c -> new Stmt.Continue(c.getLabel() == null ? null : c.getLabel().toString(), pos);
            case LabeledStatementTree l -> new Stmt.Labeled(l.getLabel().toString(), stmt(new TreePath(path, l.getStatement())), pos);
            case ThrowTree th -> throwStmt(path, th);
            case TryTree tr -> unsupportedStmt(DiagnosticCode.UNSUPPORTED_EXCEPTION, tr, "try/catch/finally is not supported yet");
            case EmptyStatementTree e -> new Stmt.Block(List.of(), pos);
            // 単一スレッドを前提とするため synchronized は本体のみ変換する。
            case SynchronizedTree s -> stmt(new TreePath(path, s.getBlock()));
            // Java の assert は既定で無効（-ea なし）なので何も生成しない。
            case com.sun.source.tree.AssertTree a -> new Stmt.Block(List.of(), pos);
            case ClassTree c -> unsupportedStmt(DiagnosticCode.UNSUPPORTED_OOP, c, "local classes are not supported yet");
            default -> unsupportedStmt(DiagnosticCode.UNSUPPORTED_SYNTAX, t, "statement '" + t.getKind() + "' is not supported yet");
        };
    }

    private Expr condition(TreePath parent, ExpressionTree cond) {
        return coerce(expr(new TreePath(parent, cond)), JType.BOOLEAN);
    }

    private Stmt throwStmt(TreePath path, ThrowTree th) {
        if (th.getExpression() instanceof NewClassTree nc && nc.getClassBody() == null) {
            TypeMirror tm = trees.getTypeMirror(new TreePath(path, nc));
            if (tm != null && types.isSubtype(tm, throwableType)) {
                String cls = ((TypeElement) types.asElement(tm)).getQualifiedName().toString();
                TreePath ncPath = new TreePath(path, nc);
                if (nc.getArguments().isEmpty()) {
                    return new Stmt.ThrowNew(cls, null, pos(th));
                }
                if (nc.getArguments().size() == 1) {
                    Expr msg = expr(new TreePath(ncPath, nc.getArguments().get(0)));
                    if (JType.isString(msg.type())) {
                        return new Stmt.ThrowNew(cls, msg, pos(th));
                    }
                }
            }
        }
        return unsupportedStmt(DiagnosticCode.UNSUPPORTED_EXCEPTION, th, "only 'throw new X()' / 'throw new X(String)' is supported");
    }

    private Stmt switchStmt(TreePath path, SwitchTree sw) {
        Expr selector = expr(new TreePath(path, sw.getExpression()));
        JType st = selector.type();
        boolean okType = JType.isString(st) || JType.isPrimitive(st, JType.Kind.INT) || JType.isPrimitive(st, JType.Kind.CHAR)
                || JType.isPrimitive(st, JType.Kind.SHORT) || JType.isPrimitive(st, JType.Kind.BYTE);
        if (!okType) {
            return unsupportedStmt(DiagnosticCode.UNSUPPORTED_SYNTAX, sw, "switch on " + st.javaName() + " is not supported yet");
        }
        List<Stmt.SwitchCase> cases = new ArrayList<>();
        for (CaseTree c : sw.getCases()) {
            TreePath cp = new TreePath(path, c);
            List<Object> labels = new ArrayList<>();
            for (CaseLabelTree lt : c.getLabels()) {
                if (lt instanceof DefaultCaseLabelTree) {
                    continue;
                }
                if (lt instanceof ConstantCaseLabelTree cl) {
                    Object v = constantValue(new TreePath(new TreePath(cp, cl), cl.getConstantExpression()));
                    if (v == null) {
                        return unsupportedStmt(DiagnosticCode.UNSUPPORTED_SYNTAX, sw, "switch with non-constant case label (enum/null)");
                    }
                    labels.add(v);
                } else {
                    return unsupportedStmt(DiagnosticCode.UNSUPPORTED_SYNTAX, sw, "pattern matching switch is not supported yet");
                }
            }
            List<Stmt> body = new ArrayList<>();
            boolean arrow = c.getCaseKind() == CaseTree.CaseKind.RULE;
            if (arrow) {
                Tree b = c.getBody();
                TreePath bp = new TreePath(cp, b);
                if (b instanceof ExpressionTree et) {
                    body.add(new Stmt.ExprStmt(expr(bp), pos(et)));
                } else {
                    body.add(stmt(bp));
                }
            } else {
                for (StatementTree s : c.getStatements()) {
                    body.add(stmt(new TreePath(cp, s)));
                }
            }
            cases.add(new Stmt.SwitchCase(labels, body, arrow, pos(c)));
        }
        return new Stmt.Switch(selector, cases, pos(sw));
    }

    private Object constantValue(TreePath path) {
        Tree t = path.getLeaf();
        if (t instanceof ParenthesizedTree p) {
            return constantValue(new TreePath(path, p.getExpression()));
        }
        if (t instanceof LiteralTree lit) {
            return lit.getValue();
        }
        Element el = trees.getElement(path);
        if (el instanceof VariableElement ve && ve.getConstantValue() != null) {
            return ve.getConstantValue();
        }
        // -1 などの単項演算を含む定数式は javac の定数畳み込みに頼れないため、ここで畳み込む。
        if (t instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) {
            Object v = constantValue(new TreePath(path, u.getExpression()));
            if (v instanceof Integer i) {
                return -i;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 式

    private Expr expr(TreePath path) {
        Tree t = path.getLeaf();
        SourcePos pos = pos(t);
        return switch (t) {
            case ParenthesizedTree p -> expr(new TreePath(path, p.getExpression()));
            case LiteralTree lit -> literal(path, lit);
            case IdentifierTree id -> identifier(path, id);
            case MemberSelectTree ms -> memberSelect(path, ms);
            case MethodInvocationTree mi -> invocation(path, mi);
            case NewClassTree nc -> newClass(path, nc);
            case NewArrayTree na -> newArray(path, na);
            case ArrayAccessTree aa -> new Expr.ArrayAccess(expr(new TreePath(path, aa.getExpression())),
                    coerce(expr(new TreePath(path, aa.getIndex())), JType.INT), typeOf(path), pos);
            case AssignmentTree as -> {
                Expr target = lvalue(new TreePath(path, as.getVariable()));
                if (target instanceof Expr.Unsupported) {
                    yield target;
                }
                yield new Expr.Assign(target, coerce(expr(new TreePath(path, as.getExpression())), target.type()), target.type(), pos);
            }
            case CompoundAssignmentTree ca -> compoundAssign(path, ca);
            case UnaryTree u -> unary(path, u);
            case BinaryTree b -> binary(path, b);
            case ConditionalExpressionTree c -> {
                JType ty = typeOf(path);
                yield new Expr.Conditional(condition(path, c.getCondition()),
                        coerce(expr(new TreePath(path, c.getTrueExpression())), ty),
                        coerce(expr(new TreePath(path, c.getFalseExpression())), ty), ty, pos);
            }
            case TypeCastTree tc -> {
                JType target = typeOf(path);
                Expr inner = expr(new TreePath(path, tc.getExpression()));
                if (inner.type() instanceof JType.Primitive && target instanceof JType.Primitive) {
                    yield inner.type().equals(target) ? inner : new Expr.Cast(inner, target, false, pos);
                }
                if (inner.type().equals(target) || inner instanceof Expr.Unsupported) {
                    yield inner;
                }
                yield unsupportedExpr(DiagnosticCode.UNSUPPORTED_TYPE, t, "reference/boxing cast to " + target.javaName() + " is not supported yet", target);
            }
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, t, "expression '" + t.getKind() + "' is not supported yet", typeOf(path));
        };
    }

    private Expr literal(TreePath path, LiteralTree lit) {
        if (lit.getKind() == Tree.Kind.NULL_LITERAL) {
            return unsupportedExpr(DiagnosticCode.LOSSY_NULL, lit, "null is not supported yet", new JType.NullType());
        }
        return new Expr.Literal(lit.getValue(), typeOf(path), pos(lit));
    }

    private Expr identifier(TreePath path, IdentifierTree id) {
        Element el = trees.getElement(path);
        if (el == null) {
            return unsupportedExpr(DiagnosticCode.INTERNAL, id, "unresolved identifier " + id.getName(), typeOf(path));
        }
        return switch (el.getKind()) {
            case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE ->
                    new Expr.Local(id.getName().toString(), type(el.asType()), pos(id));
            case FIELD -> fieldRef(path, (VariableElement) el, id);
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, id, "reference to " + el.getKind() + " '" + id.getName() + "'", typeOf(path));
        };
    }

    private Expr memberSelect(TreePath path, MemberSelectTree ms) {
        TreePath qualifier = new TreePath(path, ms.getExpression());
        TypeMirror qt = trees.getTypeMirror(qualifier);
        if (qt != null && qt.getKind() == TypeKind.ARRAY && ms.getIdentifier().contentEquals("length")) {
            return new Expr.ArrayLength(expr(qualifier), JType.INT, pos(ms));
        }
        Element el = trees.getElement(path);
        if (el instanceof VariableElement ve && el.getKind() == ElementKind.FIELD) {
            return fieldRef(path, ve, ms);
        }
        return unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, ms, "member reference '" + ms + "' is not supported yet", typeOf(path));
    }

    private Expr fieldRef(TreePath path, VariableElement ve, Tree t) {
        String owner = ((TypeElement) ve.getEnclosingElement()).getQualifiedName().toString();
        JType ft = type(ve.asType());
        if (!ve.getModifiers().contains(Modifier.STATIC)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_OOP, t, "instance field access '" + ve.getSimpleName() + "' is not supported yet", ft);
        }
        // 変換対象外（JDK 等）の定数は値を埋め込む。変換対象内の定数は名前を保つ。
        if (ve.getConstantValue() != null && !programTypes.contains(owner)) {
            return new Expr.Literal(ve.getConstantValue(), ft, pos(t));
        }
        return new Expr.StaticField(owner, ve.getSimpleName().toString(), ft, pos(t));
    }

    private Expr lvalue(TreePath path) {
        Expr e = expr(path);
        return switch (e) {
            case Expr.Local l -> l;
            case Expr.StaticField f -> f;
            case Expr.ArrayAccess a -> a;
            case Expr.Unsupported u -> u;
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, path.getLeaf(), "unsupported assignment target", e.type());
        };
    }

    private Expr invocation(TreePath path, MethodInvocationTree mi) {
        TreePath selPath = new TreePath(path, mi.getMethodSelect());
        Element el = trees.getElement(selPath);
        JType resultType = typeOf(path);
        if (!(el instanceof ExecutableElement ee)) {
            return unsupportedExpr(DiagnosticCode.INTERNAL, mi, "unresolved method " + mi.getMethodSelect(), resultType);
        }
        MethodRef ref = methodRef(ee);
        if (ee.isVarArgs() && !isNonVarargsInvocation(path, mi, ref)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_API, mi, "varargs call " + ref.owner() + "." + ref.signature() + " is not supported yet", resultType);
        }
        Expr receiver = null;
        if (!ref.isStatic()) {
            if (mi.getMethodSelect() instanceof MemberSelectTree ms && !isSuperOrThis(ms.getExpression())) {
                receiver = expr(new TreePath(selPath, ms.getExpression()));
            } else {
                return unsupportedExpr(DiagnosticCode.UNSUPPORTED_OOP, mi, "instance method call on this/super is not supported yet", resultType);
            }
        }
        List<Expr> args = args(path, mi.getArguments(), ref);
        return new Expr.Call(ref, receiver, args, resultType, pos(mi));
    }

    private boolean isNonVarargsInvocation(TreePath path, MethodInvocationTree mi, MethodRef ref) {
        if (mi.getArguments().size() != ref.paramTypes().size()) {
            return false;
        }
        TypeMirror last = trees.getTypeMirror(new TreePath(path, mi.getArguments().get(mi.getArguments().size() - 1)));
        return last != null && last.getKind() == TypeKind.ARRAY;
    }

    private static boolean isSuperOrThis(ExpressionTree e) {
        return e instanceof IdentifierTree id && (id.getName().contentEquals("this") || id.getName().contentEquals("super"));
    }

    private List<Expr> args(TreePath path, List<? extends ExpressionTree> argTrees, MethodRef ref) {
        List<Expr> args = new ArrayList<>();
        for (int i = 0; i < argTrees.size(); i++) {
            Expr a = expr(new TreePath(path, argTrees.get(i)));
            args.add(i < ref.paramTypes().size() ? coerce(a, ref.paramTypes().get(i)) : a);
        }
        return args;
    }

    private Expr newClass(TreePath path, NewClassTree nc) {
        JType ty = typeOf(path);
        if (nc.getClassBody() != null) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_OOP, nc, "anonymous classes are not supported yet", ty);
        }
        if (!(trees.getElement(path) instanceof ExecutableElement ctor)) {
            return unsupportedExpr(DiagnosticCode.INTERNAL, nc, "unresolved constructor", ty);
        }
        MethodRef ref = methodRef(ctor);
        if (programTypes.contains(ref.owner())) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_OOP, nc, "object creation of '" + ref.owner() + "' is not supported yet", ty);
        }
        if (ctor.isVarArgs()) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_API, nc, "varargs constructor is not supported yet", ty);
        }
        return new Expr.New(ref, args(path, nc.getArguments(), ref), ty, pos(nc));
    }

    private Expr newArray(TreePath path, NewArrayTree na) {
        JType ty = typeOf(path);
        if (!(ty instanceof JType.ArrayType at)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_TYPE, na, "array of unsupported type", ty);
        }
        List<Expr> dims = new ArrayList<>();
        for (ExpressionTree d : na.getDimensions()) {
            dims.add(coerce(expr(new TreePath(path, d)), JType.INT));
        }
        if (!dims.isEmpty()) {
            JType rest = at;
            for (int i = 0; i < dims.size(); i++) {
                rest = ((JType.ArrayType) rest).component();
            }
            if (rest instanceof JType.ArrayType) {
                return unsupportedExpr(DiagnosticCode.LOSSY_NULL, na, "partially dimensioned array (elements would be null) is not supported yet", ty);
            }
        }
        List<Expr> init = null;
        if (na.getInitializers() != null) {
            init = new ArrayList<>();
            for (ExpressionTree e : na.getInitializers()) {
                init.add(coerce(expr(new TreePath(path, e)), at.component()));
            }
        }
        return new Expr.NewArray(at, dims, init, pos(na));
    }

    private Expr compoundAssign(TreePath path, CompoundAssignmentTree ca) {
        Expr target = lvalue(new TreePath(path, ca.getVariable()));
        if (target instanceof Expr.Unsupported) {
            return target;
        }
        Expr value = expr(new TreePath(path, ca.getExpression()));
        BinaryOp op = switch (ca.getKind()) {
            case PLUS_ASSIGNMENT -> BinaryOp.ADD;
            case MINUS_ASSIGNMENT -> BinaryOp.SUB;
            case MULTIPLY_ASSIGNMENT -> BinaryOp.MUL;
            case DIVIDE_ASSIGNMENT -> BinaryOp.DIV;
            case REMAINDER_ASSIGNMENT -> BinaryOp.REM;
            case LEFT_SHIFT_ASSIGNMENT -> BinaryOp.SHL;
            case RIGHT_SHIFT_ASSIGNMENT -> BinaryOp.SHR;
            case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> BinaryOp.USHR;
            case AND_ASSIGNMENT -> BinaryOp.BIT_AND;
            case OR_ASSIGNMENT -> BinaryOp.BIT_OR;
            case XOR_ASSIGNMENT -> BinaryOp.BIT_XOR;
            default -> throw new IllegalStateException(ca.getKind().toString());
        };
        JType tt = target.type();
        JType operandType;
        if (JType.isString(tt)) {
            operandType = JType.STRING;
        } else if (op.isShift()) {
            operandType = JType.unaryPromotion(tt);
            value = coerce(value, JType.unaryPromotion(value.type()));
        } else if (JType.isPrimitive(tt, JType.Kind.BOOLEAN)) {
            operandType = JType.BOOLEAN;
        } else {
            operandType = JType.binaryPromotion(tt, value.type());
            value = coerce(value, operandType);
        }
        return new Expr.CompoundAssign(op, target, value, operandType, tt, pos(ca));
    }

    private Expr unary(TreePath path, UnaryTree u) {
        TreePath op = new TreePath(path, u.getExpression());
        JType ty = typeOf(path);
        SourcePos pos = pos(u);
        return switch (u.getKind()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                Expr target = lvalue(op);
                if (target instanceof Expr.Unsupported) {
                    yield target;
                }
                boolean inc = u.getKind() == Tree.Kind.PREFIX_INCREMENT || u.getKind() == Tree.Kind.POSTFIX_INCREMENT;
                boolean prefix = u.getKind() == Tree.Kind.PREFIX_INCREMENT || u.getKind() == Tree.Kind.PREFIX_DECREMENT;
                yield new Expr.IncDec(inc, prefix, target, target.type(), pos);
            }
            case UNARY_MINUS -> {
                // javac は -2147483648 / -9223372036854775808L のリテラル部分を範囲外の値として保持しないため、
                // 単項マイナス + リテラルは常にここで負のリテラルに畳み込む。
                if (u.getExpression() instanceof LiteralTree lit) {
                    Object v = lit.getValue();
                    if (v instanceof Integer i) {
                        yield new Expr.Literal(-i, ty, pos);
                    }
                    if (v instanceof Long l) {
                        yield new Expr.Literal(-l, ty, pos);
                    }
                }
                yield new Expr.Unary(UnaryOp.NEG, coerce(expr(op), ty), ty, pos);
            }
            case UNARY_PLUS -> coerce(expr(op), ty);
            case BITWISE_COMPLEMENT -> new Expr.Unary(UnaryOp.BIT_NOT, coerce(expr(op), ty), ty, pos);
            case LOGICAL_COMPLEMENT -> new Expr.Unary(UnaryOp.NOT, expr(op), ty, pos);
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, u, "unary " + u.getKind(), ty);
        };
    }

    private Expr binary(TreePath path, BinaryTree b) {
        Expr left = expr(new TreePath(path, b.getLeftOperand()));
        Expr right = expr(new TreePath(path, b.getRightOperand()));
        JType ty = typeOf(path);
        SourcePos pos = pos(b);
        BinaryOp op = switch (b.getKind()) {
            case PLUS -> BinaryOp.ADD;
            case MINUS -> BinaryOp.SUB;
            case MULTIPLY -> BinaryOp.MUL;
            case DIVIDE -> BinaryOp.DIV;
            case REMAINDER -> BinaryOp.REM;
            case LEFT_SHIFT -> BinaryOp.SHL;
            case RIGHT_SHIFT -> BinaryOp.SHR;
            case UNSIGNED_RIGHT_SHIFT -> BinaryOp.USHR;
            case LESS_THAN -> BinaryOp.LT;
            case GREATER_THAN -> BinaryOp.GT;
            case LESS_THAN_EQUAL -> BinaryOp.LE;
            case GREATER_THAN_EQUAL -> BinaryOp.GE;
            case EQUAL_TO -> BinaryOp.EQ;
            case NOT_EQUAL_TO -> BinaryOp.NE;
            case AND -> BinaryOp.BIT_AND;
            case OR -> BinaryOp.BIT_OR;
            case XOR -> BinaryOp.BIT_XOR;
            case CONDITIONAL_AND -> BinaryOp.AND;
            case CONDITIONAL_OR -> BinaryOp.OR;
            default -> null;
        };
        if (op == null) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, b, "binary operator " + b.getKind(), ty);
        }
        JType lt = left.type();
        JType rt = right.type();
        if (op == BinaryOp.ADD && JType.isString(ty)) {
            return new Expr.Binary(op, left, right, JType.STRING, JType.STRING, pos);
        }
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            return new Expr.Binary(op, left, right, JType.BOOLEAN, JType.BOOLEAN, pos);
        }
        if (op.isShift()) {
            JType lp = JType.unaryPromotion(lt);
            return new Expr.Binary(op, coerce(left, lp), coerce(right, JType.unaryPromotion(rt)), lp, ty, pos);
        }
        if (op.isComparison()) {
            if (JType.isNumeric(lt) && JType.isNumeric(rt)) {
                // 同じ型どうしの比較は昇格しなくても結果が同じなので、そのまま比較する（例: char == char）。
                JType operand = lt.equals(rt) ? lt : JType.binaryPromotion(lt, rt);
                return new Expr.Binary(op, coerce(left, operand), coerce(right, operand), operand, JType.BOOLEAN, pos);
            }
            if (JType.isPrimitive(lt, JType.Kind.BOOLEAN)) {
                return new Expr.Binary(op, left, right, JType.BOOLEAN, JType.BOOLEAN, pos);
            }
            if (lt instanceof JType.Primitive || rt instanceof JType.Primitive) {
                return unsupportedExpr(DiagnosticCode.UNSUPPORTED_TYPE, b, "comparison involving boxing is not supported yet", ty);
            }
            if (JType.isString(lt) && JType.isString(rt)) {
                diags.report(DiagnosticCode.LOSSY_STRING_IDENTITY, pos,
                        "String reference comparison (==/!=) is translated as value comparison");
            }
            return new Expr.Binary(op, left, right, lt, JType.BOOLEAN, pos);
        }
        if (JType.isPrimitive(lt, JType.Kind.BOOLEAN) && JType.isPrimitive(rt, JType.Kind.BOOLEAN)) {
            return new Expr.Binary(op, left, right, JType.BOOLEAN, JType.BOOLEAN, pos);
        }
        if (!JType.isNumeric(lt) || !JType.isNumeric(rt)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_TYPE, b, "arithmetic on boxed values is not supported yet", ty);
        }
        JType operand = JType.binaryPromotion(lt, rt);
        return new Expr.Binary(op, coerce(left, operand), coerce(right, operand), operand, operand, pos);
    }

    /** 代入変換・メソッド呼び出し変換（プリミティブ間のみ明示化。参照型の拡大はそのまま）。 */
    private Expr coerce(Expr e, JType target) {
        JType from = e.type();
        if (target == null || from.equals(target) || e instanceof Expr.Unsupported || from instanceof JType.NullType) {
            return e;
        }
        if (from instanceof JType.Primitive && target instanceof JType.Primitive) {
            return new Expr.Cast(e, target, true, e.pos());
        }
        if (from instanceof JType.Primitive || target instanceof JType.Primitive) {
            diags.report(DiagnosticCode.UNSUPPORTED_TYPE, e.pos(), "autoboxing/unboxing (" + from.javaName() + " -> "
                    + target.javaName() + ") is not supported yet");
            return new Expr.Unsupported("boxing " + from.javaName() + " -> " + target.javaName(), target, e.pos());
        }
        return e;
    }

    // ------------------------------------------------------------------ 型・位置・診断

    private JType typeOf(TreePath path) {
        TypeMirror tm = trees.getTypeMirror(path);
        return tm == null ? new JType.Unsupported("<unknown>") : type(tm);
    }

    private JType type(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> JType.BOOLEAN;
            case BYTE -> JType.BYTE;
            case SHORT -> JType.SHORT;
            case CHAR -> JType.CHAR;
            case INT -> JType.INT;
            case LONG -> JType.LONG;
            case FLOAT -> JType.FLOAT;
            case DOUBLE -> JType.DOUBLE;
            case VOID -> JType.VOID;
            case ARRAY -> new JType.ArrayType(type(((ArrayType) t).getComponentType()));
            case DECLARED -> new JType.ClassType(((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().toString());
            case NULL -> new JType.NullType();
            default -> new JType.Unsupported(t.toString());
        };
    }

    private void reportIfUnsupportedType(JType t, Tree at) {
        if (t instanceof JType.Unsupported u) {
            diags.report(DiagnosticCode.UNSUPPORTED_TYPE, pos(at), "type '" + u.description() + "' is not supported yet");
        } else if (t instanceof JType.ArrayType a) {
            reportIfUnsupportedType(a.component(), at);
        }
    }

    private SourcePos pos(Tree t) {
        long start = trees.getSourcePositions().getStartPosition(cu, t);
        if (start < 0) {
            return new SourcePos(file, 0, 0);
        }
        return new SourcePos(file, cu.getLineMap().getLineNumber(start), cu.getLineMap().getColumnNumber(start));
    }

    private void unsupportedDecl(DiagnosticCode code, Tree t, String message) {
        diags.report(code, pos(t), message);
    }

    private Stmt unsupportedStmt(DiagnosticCode code, Tree t, String message) {
        diags.report(code, pos(t), message);
        return new Stmt.Unsupported(message, pos(t));
    }

    private Expr unsupportedExpr(DiagnosticCode code, Tree t, String message, JType type) {
        diags.report(code, pos(t), message);
        return new Expr.Unsupported(message, type, pos(t));
    }
}
