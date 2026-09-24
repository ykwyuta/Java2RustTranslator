package io.github.ykwyuta.j2r.frontend;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.PatternTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
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
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * 属性付け済みの javac 構文木を JIR に変換する。
 *
 * <ul>
 *   <li>暗黙の型変換（数値の昇格・代入変換・ボクシング/アンボクシング・String / 配列と Object の間の変換）は
 *       {@link Expr.Cast}（implicit）として明示する。</li>
 *   <li>ジェネリクスは消去する。メソッド呼び出し・フィールド参照の型は消去後の型とし、javac が具体化した型との差は
 *       Cast で表す（javac が checkcast を挿入するのと同じ位置）。</li>
 *   <li>可変長引数は配列にまとめ、メソッド参照はラムダに、try-with-resources は try/finally に展開する。</li>
 * </ul>
 * 変換できない構文は {@link Expr.Unsupported} / {@link Stmt.Unsupported} にして診断を出す。
 */
final class JirBuilder {
    /** 継承できる JDK のクラス（状態を委譲先のオブジェクトに持たせる）。 */
    static final Set<String> DELEGATING_SUPERCLASSES = Set.of(
            "java.lang.Thread",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector", "java.util.Stack", "java.util.ArrayDeque",
            "java.util.PriorityQueue", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.Hashtable",
            "java.util.concurrent.ConcurrentHashMap");

    private static final Set<String> BOXES = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Character", "java.lang.Boolean", "java.lang.Double", "java.lang.Float");

    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final CompilationUnitTree cu;
    private final Set<String> programTypes;
    private final Diagnostics diags;
    private final String file;
    private final TypeMirror throwableType;

    /** 本体を変換中のメソッド（ラムダの本体では、ラムダの戻り値型）の戻り値型。 */
    private JType currentReturnType = JType.VOID;
    /** 本体を変換中の型（ネストしたラムダの中でも同じ）。 */
    private TypeElement currentType;
    private final Deque<TypeElement> typeStack = new ArrayDeque<>();
    /** 本体を変換中のメソッド・初期化子が static か（匿名クラス・ローカルクラスが外側のインスタンスを持つか）。 */
    private boolean staticContext;
    /** 変換中のローカルクラス・匿名クラスと、それが捕捉した外側のローカル変数。 */
    private final Deque<LocalClass> localClasses = new ArrayDeque<>();
    /** ローカルクラス・匿名クラスの一覧（build() の最後にコンパイル単位の型に加える）。 */
    private final List<Decl.TypeDecl> extraTypes = new ArrayList<>();
    /** ローカルクラス → 捕捉した変数（生成時に追加の引数として渡す）。 */
    private final java.util.Map<TypeElement, List<VariableElement>> localCaptures = new java.util.HashMap<>();
    /** 外側のインスタンスを持つローカルクラス・匿名クラス。 */
    private final Set<TypeElement> localWithOuter = new java.util.HashSet<>();
    private int localCounter;
    /** パターンの束縛変数の名前（同じメソッドで同名・別の型の束縛変数があれば名前を変える）。 */
    private final java.util.Map<VariableElement, String> bindingNames = new java.util.HashMap<>();
    private final java.util.Map<String, JType> bindingTypes = new java.util.HashMap<>();
    private int patternTemps;
    private int resourceCounter;

    private record LocalClass(TypeElement type, String qname, java.util.LinkedHashMap<String, VariableElement> captures) {}

    /** 型の名前付け（メンバー型は javac の名前、ローカル・匿名クラスは合成した名前）。 */
    private record TypeNaming(String qname, String simpleName, String outer, boolean hasOuter) {}

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
                typeDecl(new TreePath(root, ct), pkg, typeDecls);
            }
        }
        typeDecls.addAll(extraTypes);
        return new Decl.CompilationUnit(pkg, file, typeDecls);
    }

    // ================================================================== 型の宣言

    private void typeDecl(TreePath path, String pkg, List<Decl.TypeDecl> out) {
        ClassTree ct = (ClassTree) path.getLeaf();
        TypeElement te = (TypeElement) trees.getElement(path);
        Decl.TypeKind kind = switch (te.getKind()) {
            case CLASS -> Decl.TypeKind.CLASS;
            case INTERFACE -> Decl.TypeKind.INTERFACE;
            case ENUM -> Decl.TypeKind.ENUM;
            case RECORD -> Decl.TypeKind.RECORD;
            default -> null;
        };
        if (kind == null && te.getKind() == ElementKind.ANNOTATION_TYPE) {
            // 注釈型の宣言は実行時の意味を持たない（注釈の値の実行時参照は対応しない）ので、何も生成しない。
            return;
        }
        if (kind == null) {
            report(DiagnosticCode.UNSUPPORTED_OOP, ct, te.getKind().name().toLowerCase(java.util.Locale.ROOT)
                    + " '" + te.getSimpleName() + "' is not supported yet");
            return;
        }
        boolean inner = te.getNestingKind() == NestingKind.MEMBER && kind == Decl.TypeKind.CLASS
                && !te.getModifiers().contains(Modifier.STATIC);
        String outer = te.getNestingKind() == NestingKind.MEMBER
                ? qualifiedNameOf(((TypeElement) te.getEnclosingElement())) : null;
        TypeNaming naming = new TypeNaming(qualifiedNameOf(te), te.getSimpleName().toString(), outer, inner);
        withType(te, () -> buildType(path, ct, te, kind, pkg, out, naming));
    }

    private void withType(TypeElement te, Runnable r) {
        typeStack.push(te);
        TypeElement savedType = currentType;
        boolean savedStatic = staticContext;
        currentType = te;
        try {
            r.run();
        } finally {
            currentType = savedType;
            staticContext = savedStatic;
            typeStack.pop();
        }
    }

    /**
     * ローカルクラス・匿名クラス: 外側のクラスの入れ子の型として変換する。外側のメソッドのローカル変数を
     * 参照していれば、それをフィールド（__cap_名前）に捕捉し、コンストラクタの追加の引数で受け取る。
     */
    private Decl.TypeDecl localClass(TreePath path, ClassTree ct, TypeElement te) {
        String binary = elements.getBinaryName(te).toString();
        String simple = te.getNestingKind() == NestingKind.ANONYMOUS ? "Anon" + (++localCounter)
                : te.getSimpleName() + "" + (++localCounter);
        TypeElement enclosing = currentType;
        boolean hasOuter = !staticContext && te.getKind() == ElementKind.CLASS;
        if (hasOuter) {
            localWithOuter.add(te);
        }
        programTypes.add(binary);
        // 本体付きの enum 定数の匿名クラスは、javac では ENUM と報告されるが、enum を継承するクラスとして扱う。
        Decl.TypeKind kind = te.getNestingKind() == NestingKind.ANONYMOUS ? Decl.TypeKind.CLASS : switch (te.getKind()) {
            case INTERFACE -> Decl.TypeKind.INTERFACE;
            case ENUM -> Decl.TypeKind.ENUM;
            case RECORD -> Decl.TypeKind.RECORD;
            default -> Decl.TypeKind.CLASS;
        };
        TypeNaming naming = new TypeNaming(binary, simple, false
                ? elements.getBinaryName(enclosing).toString() : qualifiedNameOf(enclosing), hasOuter);
        LocalClass lc = new LocalClass(te, binary, new java.util.LinkedHashMap<>());
        localClasses.push(lc);
        List<Decl.TypeDecl> out = new ArrayList<>();
        try {
            withType(te, () -> buildType(path, ct, te, kind, "", out, naming));
        } finally {
            localClasses.pop();
        }
        localCaptures.put(te, new ArrayList<>(lc.captures().values()));
        Decl.TypeDecl decl = addCaptures(out.get(0), lc);
        extraTypes.add(decl);
        extraTypes.addAll(out.subList(1, out.size()));
        return decl;
    }

    /** 型の名前（ローカルクラス・匿名クラスは binary name）。 */
    private String qualifiedNameOf(TypeElement te) {
        if (te.getNestingKind() == NestingKind.LOCAL || te.getNestingKind() == NestingKind.ANONYMOUS) {
            return elements.getBinaryName(te).toString();
        }
        return te.getQualifiedName().toString();
    }

    /** 捕捉した変数をフィールドにし、各コンストラクタの引数に加えて、super(...) より前に代入する。 */
    private Decl.TypeDecl addCaptures(Decl.TypeDecl t, LocalClass lc) {
        if (lc.captures().isEmpty()) {
            return t;
        }
        List<Decl.FieldDecl> fields = new ArrayList<>(t.fields());
        List<Decl.Param> extra = new ArrayList<>();
        for (VariableElement v : lc.captures().values()) {
            JType vt = type(v.asType());
            String f = "__cap_" + v.getSimpleName();
            fields.add(new Decl.FieldDecl(f, vt, false, true, null, null, false, null, t.pos()));
            extra.add(new Decl.Param(f, vt));
        }
        List<Decl.MethodDecl> methods = new ArrayList<>();
        JType self = new JType.ClassType(t.qualifiedName());
        for (Decl.MethodDecl m : t.methods()) {
            if (!m.isConstructor() || m.body() == null) {
                methods.add(m);
                continue;
            }
            List<Decl.Param> params = new ArrayList<>(m.params());
            params.addAll(extra);
            List<Stmt> stmts = new ArrayList<>();
            boolean delegates = !m.body().stmts().isEmpty() && m.body().stmts().get(0) instanceof Stmt.ExprStmt es
                    && es.expr() instanceof Expr.CtorCall cc && !cc.isSuper();
            if (!delegates) {
                for (Decl.Param p : extra) {
                    stmts.add(new Stmt.ExprStmt(new Expr.Assign(new Expr.FieldAccess(new Expr.This(self, 0, t.pos()), t.qualifiedName(), p.name(), p.type(), t.pos()),
                            new Expr.Local(p.name(), p.type(), t.pos()), p.type(), t.pos()), t.pos()));
                }
            }
            stmts.addAll(m.body().stmts());
            methods.add(new Decl.MethodDecl(m.ref(), m.kind(), params, new Stmt.Block(stmts, m.body().pos()), m.isPublic(), m.isPrivate(),
                    m.isMain(), m.overrides(), m.rustName(), m.javadoc(), m.pos(), m.annotations()));
        }
        return t.withBodies(fields, methods, t.instanceInit(), t.staticInit(), t.enumConstants());
    }

    /**
     * ローカル変数 v を、今変換中のクラスから参照する式。v が外側のメソッドの変数なら、ローカルクラス・匿名クラスが
     * 捕捉したフィールドの参照にする。
     */
    private Expr localRef(VariableElement v, SourcePos pos) {
        JType vt = type(v.asType());
        TypeElement declaring = enclosingType(v);
        if (localClasses.isEmpty() || declaring == null || declaring.equals(currentType) || !localClasses.peek().type().equals(currentType)) {
            return new Expr.Local(v.getSimpleName().toString(), vt, pos);
        }
        LocalClass lc = localClasses.peek();
        lc.captures().putIfAbsent(v.getSimpleName().toString(), v);
        return new Expr.FieldAccess(new Expr.This(new JType.ClassType(lc.qname()), 0, pos), lc.qname(),
                "__cap_" + v.getSimpleName(), vt, pos);
    }

    /** 要素を宣言しているクラス。 */
    private static TypeElement enclosingType(Element e) {
        Element cur = e.getEnclosingElement();
        while (cur != null && !(cur instanceof TypeElement)) {
            cur = cur.getEnclosingElement();
        }
        return (TypeElement) cur;
    }

    private void buildType(TreePath path, ClassTree ct, TypeElement te, Decl.TypeKind kind, String pkg, List<Decl.TypeDecl> out,
                           TypeNaming naming) {
        String qname = naming.qname();
        String superclass = null;
        String jdkSuperclass = null;
        if (kind == Decl.TypeKind.CLASS && te.getSuperclass() instanceof DeclaredType st) {
            String sn = qualifiedName(st);
            if (!sn.equals("java.lang.Object")) {
                if (programTypes.contains(sn) || types.isSubtype(types.erasure(st), types.erasure(throwableType))) {
                    superclass = sn;
                } else if (DELEGATING_SUPERCLASSES.contains(sn)) {
                    // JDK のクラスの状態は、委譲先のオブジェクト（super(...) で作る）が持つ。
                    jdkSuperclass = sn;
                } else {
                    report(DiagnosticCode.UNSUPPORTED_OOP, ct, "extending the JDK class " + sn + " is not supported yet");
                }
            }
        }
        List<String> interfaces = new ArrayList<>();
        for (TypeMirror i : te.getInterfaces()) {
            interfaces.add(qualifiedName(i));
        }
        boolean inner = naming.hasOuter();
        String outer = naming.outer();
        // Java のクラス初期化: static フィールドの初期化子と static 初期化ブロックを、テキストの順に 1 回だけ実行する。
        boolean hasStaticBlock = ct.getMembers().stream().anyMatch(m -> m instanceof BlockTree b && b.isStatic()
                || m instanceof VariableTree v && v.getInitializer() != null && isNonConstantStatic(new TreePath(path, m)));

        List<Decl.FieldDecl> fields = new ArrayList<>();
        List<Decl.MethodDecl> methods = new ArrayList<>();
        List<Stmt> instanceInit = new ArrayList<>();
        List<Stmt> staticInit = hasStaticBlock ? new ArrayList<>() : null;
        List<Decl.EnumConstant> constants = new ArrayList<>();
        List<ClassTree> nested = new ArrayList<>();
        JType selfType = new JType.ClassType(qname);

        for (Tree member : ct.getMembers()) {
            TreePath mp = new TreePath(path, member);
            switch (member) {
                case VariableTree vt -> {
                    VariableElement ve = (VariableElement) trees.getElement(mp);
                    if (ve.getKind() == ElementKind.ENUM_CONSTANT) {
                        staticContext = true;
                        constants.add(enumConstant(mp, vt, ve));
                        continue;
                    }
                    boolean isStatic = ve.getModifiers().contains(Modifier.STATIC);
                    staticContext = isStatic;
                    JType ft = type(ve.asType());
                    Expr init = vt.getInitializer() == null ? null : coerce(expr(new TreePath(mp, vt.getInitializer())), ft);
                    boolean constant = ve.getConstantValue() != null && isStatic;
                    if (!isStatic && init != null) {
                        // インスタンスフィールドの初期化子はコンストラクタの中（super(...) の直後）で実行する。
                        instanceInit.add(new Stmt.ExprStmt(new Expr.Assign(
                                new Expr.FieldAccess(new Expr.This(selfType, 0, pos(vt)), qname, ve.getSimpleName().toString(), ft, pos(vt)),
                                init, ft, pos(vt)), pos(vt)));
                        init = null;
                    } else if (isStatic && staticInit != null && init != null && !constant) {
                        staticInit.add(new Stmt.ExprStmt(new Expr.Assign(
                                new Expr.StaticField(qname, ve.getSimpleName().toString(), ft, pos(vt)), init, ft, pos(vt)), pos(vt)));
                        init = null;
                    }
                    fields.add(new Decl.FieldDecl(ve.getSimpleName().toString(), ft, isStatic, ve.getModifiers().contains(Modifier.FINAL),
                            init, isStatic ? ve.getConstantValue() : null, !ve.getModifiers().contains(Modifier.PRIVATE),
                            elements.getDocComment(ve), pos(vt)));
                }
                case MethodTree mt -> {
                    ExecutableElement ee = (ExecutableElement) trees.getElement(mp);
                    methods.add(methodDecl(mp, mt, ee, te, kind));
                }
                case BlockTree bt -> {
                    staticContext = bt.isStatic();
                    Stmt block = stmt(mp);
                    if (bt.isStatic()) {
                        staticInit.add(block);
                    } else {
                        instanceInit.add(block);
                    }
                }
                case ClassTree c -> nested.add(c);
                default -> report(DiagnosticCode.UNSUPPORTED_SYNTAX, member, "unsupported member: " + member.getKind());
            }
        }

        List<Decl.Param> components = new ArrayList<>();
        if (kind == Decl.TypeKind.RECORD) {
            for (RecordComponentElement rc : te.getRecordComponents()) {
                components.add(new Decl.Param(rc.getSimpleName().toString(), type(rc.asType())));
            }
            addRecordMembers(te, components, fields, methods);
        }

        out.add(new Decl.TypeDecl(naming.simpleName(), qname, pkg, kind,
                te.getModifiers().contains(Modifier.ABSTRACT) || kind == Decl.TypeKind.INTERFACE,
                superclass, interfaces, allSupertypes(te), outer, inner,
                fields, methods, instanceInit, staticInit, constants, components, elements.getDocComment(te), pos(ct), jdkSuperclass));
        for (ClassTree c : nested) {
            typeDecl(new TreePath(path, c), pkg, out);
        }
    }

    /** 定数でない static フィールド（enum 定数を除く）か。 */
    private boolean isNonConstantStatic(TreePath p) {
        return trees.getElement(p) instanceof VariableElement ve && ve.getKind() == ElementKind.FIELD
                && ve.getModifiers().contains(Modifier.STATIC) && ve.getConstantValue() == null;
    }

    /** record の構成要素のフィールドと、明示されていないアクセサを補う。 */
    private void addRecordMembers(TypeElement te, List<Decl.Param> components, List<Decl.FieldDecl> fields,
                                  List<Decl.MethodDecl> methods) {
        String qname = qualifiedNameOf(te);
        JType self = new JType.ClassType(qname);
        for (Decl.Param c : components) {
            boolean hasField = fields.stream().anyMatch(f -> !f.isStatic() && f.name().equals(c.name()));
            if (!hasField) {
                fields.add(new Decl.FieldDecl(c.name(), c.type(), false, true, null, null, false, null, pos(trees.getTree(te))));
            }
            boolean hasAccessor = methods.stream().anyMatch(m -> m.name().equals(c.name()) && m.params().isEmpty() && !m.isStatic());
            if (!hasAccessor) {
                SourcePos p = pos(trees.getTree(te));
                MethodRef ref = new MethodRef(qname, c.name(), List.of(), c.type(), false);
                Stmt.Block body = new Stmt.Block(List.of(new Stmt.Return(
                        new Expr.FieldAccess(new Expr.This(self, 0, p), qname, c.name(), c.type(), p), p)), p);
                methods.add(new Decl.MethodDecl(ref, Decl.MethodKind.INSTANCE, List.of(), body, true, false, false, List.of(), null, null, p));
            }
        }
    }

    private Decl.EnumConstant enumConstant(TreePath path, VariableTree vt, VariableElement ve) {
        MethodRef ctor = null;
        List<Expr> args = List.of();
        if (vt.getInitializer() instanceof NewClassTree nc) {
            TreePath np = new TreePath(path, nc);
            if (nc.getClassBody() != null) {
                // 本体付きの定数は、enum を継承する匿名クラス（javac の Color$1 など）のインスタンスにする。
                TreePath bodyPath = new TreePath(np, nc.getClassBody());
                localClass(bodyPath, nc.getClassBody(), (TypeElement) trees.getElement(bodyPath));
            }
            if (trees.getElement(np) instanceof ExecutableElement ee) {
                ctor = methodRef(ee);
                args = args(np, nc.getArguments(), ctor, ee.isVarArgs());
            }
        }
        return new Decl.EnumConstant(ve.getSimpleName().toString(), ctor, args, pos(vt));
    }

    private Decl.MethodDecl methodDecl(TreePath path, MethodTree mt, ExecutableElement ee, TypeElement owner, Decl.TypeKind ownerKind) {
        MethodRef ref = methodRef(ee);
        List<Decl.Param> params = new ArrayList<>();
        for (VariableElement p : ee.getParameters()) {
            params.add(new Decl.Param(p.getSimpleName().toString(), type(p.asType())));
        }
        Decl.MethodKind kind = ee.getKind() == ElementKind.CONSTRUCTOR ? Decl.MethodKind.CONSTRUCTOR
                : ee.getModifiers().contains(Modifier.STATIC) ? Decl.MethodKind.STATIC : Decl.MethodKind.INSTANCE;
        JType ret = type(ee.getReturnType());
        bindingTypes.clear();
        JType savedReturn = currentReturnType;
        currentReturnType = ret;
        staticContext = kind == Decl.MethodKind.STATIC;
        Stmt.Block body = null;
        if (mt.getBody() != null) {
            body = (Stmt.Block) stmt(new TreePath(path, mt.getBody()));
            if (kind == Decl.MethodKind.CONSTRUCTOR && ownerKind == Decl.TypeKind.RECORD) {
                body = completeRecordConstructor(owner, params, body);
            }
        }
        currentReturnType = savedReturn;
        boolean isMain = kind == Decl.MethodKind.STATIC && ee.getSimpleName().contentEquals("main")
                && ret instanceof JType.Void && params.size() == 1
                && params.get(0).type().equals(new JType.ArrayType(JType.STRING));
        List<String> overrides = kind == Decl.MethodKind.INSTANCE ? overriddenMethods(ee, owner) : List.of();
        return new Decl.MethodDecl(ref, kind, params, body, !ee.getModifiers().contains(Modifier.PRIVATE),
                ee.getModifiers().contains(Modifier.PRIVATE), isMain, overrides, null, elements.getDocComment(ee), pos(mt),
                ee.getAnnotationMirrors().stream().map(a -> ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString()).toList());
    }

    /**
     * record の標準コンストラクタ: 本体で代入していない構成要素のフィールドに引数を代入する
     * （コンパクトコンストラクタと、暗黙の標準コンストラクタの意味）。
     */
    private Stmt.Block completeRecordConstructor(TypeElement owner, List<Decl.Param> params, Stmt.Block body) {
        List<RecordComponentElement> comps = List.copyOf(owner.getRecordComponents());
        if (comps.size() != params.size()) {
            return body;
        }
        for (int i = 0; i < comps.size(); i++) {
            if (!type(types.erasure(comps.get(i).asType())).equals(params.get(i).type())) {
                return body;
            }
        }
        Set<String> assigned = new java.util.HashSet<>();
        io.github.ykwyuta.j2r.jir.JirVisitor.walk(body, s -> { }, e -> {
            if (e instanceof Expr.Assign a && a.target() instanceof Expr.FieldAccess f && f.receiver() instanceof Expr.This) {
                assigned.add(f.name());
            }
        });
        if (body.stmts().stream().anyMatch(s -> s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.CtorCall c && !c.isSuper())) {
            return body;
        }
        String qname = qualifiedNameOf(owner);
        List<Stmt> stmts = new ArrayList<>(body.stmts());
        for (Decl.Param p : params) {
            if (!assigned.contains(p.name())) {
                stmts.add(new Stmt.ExprStmt(new Expr.Assign(
                        new Expr.FieldAccess(new Expr.This(new JType.ClassType(qname), 0, body.pos()), qname, p.name(), p.type(), body.pos()),
                        new Expr.Local(p.name(), p.type(), body.pos()), p.type(), body.pos()), body.pos()));
            }
        }
        return new Stmt.Block(stmts, body.pos());
    }

    /** ee がオーバーライド・実装する上位型のメソッド（JDK のメソッドを含む）。 */
    private List<String> overriddenMethods(ExecutableElement ee, TypeElement owner) {
        Set<String> out = new LinkedHashSet<>();
        for (TypeElement st : allSupertypeElements(owner)) {
            for (Element e : st.getEnclosedElements()) {
                if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD
                        && m.getSimpleName().equals(ee.getSimpleName()) && !m.getModifiers().contains(Modifier.STATIC)
                        && !m.getModifiers().contains(Modifier.PRIVATE) && elements.overrides(ee, m, owner)) {
                    out.add(methodRef(m).key());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private List<TypeElement> allSupertypeElements(TypeElement te) {
        List<TypeElement> out = new ArrayList<>();
        Deque<TypeMirror> work = new ArrayDeque<>(types.directSupertypes(te.asType()));
        Set<String> seen = new java.util.HashSet<>();
        while (!work.isEmpty()) {
            TypeMirror t = work.pop();
            if (t instanceof DeclaredType dt && dt.asElement() instanceof TypeElement e && seen.add(qualifiedNameOf(e))) {
                out.add(e);
                work.addAll(types.directSupertypes(t));
            }
        }
        return out;
    }

    private List<String> allSupertypes(TypeElement te) {
        List<String> out = new ArrayList<>();
        for (TypeElement e : allSupertypeElements(te)) {
            out.add(qualifiedNameOf(e));
        }
        if (!out.contains("java.lang.Object")) {
            out.add("java.lang.Object");
        }
        return out;
    }

    private MethodRef methodRef(ExecutableElement ee) {
        List<JType> ps = new ArrayList<>();
        for (VariableElement p : ee.getParameters()) {
            ps.add(type(types.erasure(p.asType())));
        }
        String owner = qualifiedNameOf(((TypeElement) ee.getEnclosingElement()));
        String name = ee.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : ee.getSimpleName().toString();
        return new MethodRef(owner, name, ps, type(types.erasure(ee.getReturnType())), ee.getModifiers().contains(Modifier.STATIC),
                ee.getEnclosingElement().getKind() == ElementKind.INTERFACE);
    }

    // ================================================================== 文

    private Stmt stmt(TreePath path) {
        Tree t = path.getLeaf();
        SourcePos pos = pos(t);
        return switch (t) {
            case BlockTree b -> block(path, b);
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
            case EnhancedForLoopTree ef -> enhancedFor(path, ef);
            case SwitchTree sw -> {
                Expr selector = switchSelector(new TreePath(path, sw.getExpression()));
                List<Stmt.SwitchCase> cases = switchCases(path, sw.getCases(), null);
                yield cases == null ? unsupportedStmt(DiagnosticCode.UNSUPPORTED_SYNTAX, sw, "unsupported switch")
                        : new Stmt.Switch(selector, cases, pos);
            }
            case ReturnTree r -> new Stmt.Return(r.getExpression() == null ? null
                    : coerce(expr(new TreePath(path, r.getExpression())), currentReturnType), pos);
            case BreakTree b -> new Stmt.Break(b.getLabel() == null ? null : b.getLabel().toString(), pos);
            case ContinueTree c -> new Stmt.Continue(c.getLabel() == null ? null : c.getLabel().toString(), pos);
            case LabeledStatementTree l -> new Stmt.Labeled(l.getLabel().toString(), stmt(new TreePath(path, l.getStatement())), pos);
            case ThrowTree th -> new Stmt.Throw(expr(new TreePath(path, th.getExpression())), pos);
            case TryTree tr -> tryStmt(path, tr);
            case YieldTree y -> new Stmt.Yield(expr(new TreePath(path, y.getValue())), pos);
            case EmptyStatementTree e -> new Stmt.Block(List.of(), pos);
            // 単一スレッドを前提とするため synchronized は本体のみ変換する。
            case SynchronizedTree s -> stmt(new TreePath(path, s.getBlock()));
            // Java の assert は既定で無効（-ea なし）なので何も生成しない。
            case com.sun.source.tree.AssertTree a -> new Stmt.Block(List.of(), pos);
            case ClassTree c -> {
                localClass(path, c, (TypeElement) trees.getElement(path));
                yield new Stmt.Block(List.of(), pos);
            }
            default -> unsupportedStmt(DiagnosticCode.UNSUPPORTED_SYNTAX, t, "statement '" + t.getKind() + "' is not supported yet");
        };
    }

    private int iterCounter;

    /**
     * 拡張 for。配列は ForEach のまま残し（DesugarEnhancedFor が添字ループにする）、Iterable は Iterator のループにする:
     * {@code for (Iterator $it = expr.iterator(); $it.hasNext(); ) { T x = (T) $it.next(); body }}
     */
    private Stmt enhancedFor(TreePath path, EnhancedForLoopTree ef) {
        SourcePos pos = pos(ef);
        TreePath vp = new TreePath(path, ef.getVariable());
        JType vt = type(types.erasure(trees.getElement(vp).asType()));
        TreePath ep = new TreePath(path, ef.getExpression());
        Expr iterable = expr(ep);
        Stmt body = stmt(new TreePath(path, ef.getStatement()));
        String var = ef.getVariable().getName().toString();
        if (iterable.type() instanceof JType.ArrayType) {
            return new Stmt.ForEach(var, vt, iterable, body, pos);
        }
        ExecutableElement iteratorM = findMethod(trees.getTypeMirror(ep), "iterator");
        if (iteratorM == null) {
            return unsupportedStmt(DiagnosticCode.UNSUPPORTED_API, ef, "enhanced for over " + iterable.type().javaName());
        }
        MethodRef iterRef = methodRef(iteratorM);
        TypeMirror iterType = iteratorM.getReturnType();
        ExecutableElement hasNextM = findMethod(iterType, "hasNext");
        ExecutableElement nextM = findMethod(iterType, "next");
        if (hasNextM == null || nextM == null) {
            return unsupportedStmt(DiagnosticCode.UNSUPPORTED_API, ef, "iterator without hasNext()/next()");
        }
        String it = "__j2r_it" + iterCounter++;
        JType itType = type(types.erasure(iterType));
        Expr itRef = new Expr.Local(it, itType, pos);
        Stmt decl = new Stmt.LocalVar(it, itType, new Expr.Call(iterRef, iterable, List.of(), false, iterRef.returnType(), pos), true, pos);
        MethodRef hasNextRef = methodRef(hasNextM);
        MethodRef nextRef = methodRef(nextM);
        Expr cond = new Expr.Call(hasNextRef, itRef, List.of(), false, hasNextRef.returnType(), pos);
        Expr next = new Expr.Call(nextRef, itRef, List.of(), false, nextRef.returnType(), pos);
        if (!(vt instanceof JType.Primitive) && !next.type().equals(vt)) {
            next = new Expr.Cast(next, vt, true, pos);
        }
        List<Stmt> stmts = new ArrayList<>();
        stmts.add(new Stmt.LocalVar(var, vt, coerce(next, vt), false, pos));
        if (body instanceof Stmt.Block b) {
            stmts.addAll(b.stmts());
        } else {
            stmts.add(body);
        }
        return new Stmt.For(List.of(decl), coerce(cond, JType.BOOLEAN), List.of(), new Stmt.Block(stmts, pos), pos);
    }

    /** 型 t のメンバーから、引数のない名前 name のメソッドを探す。 */
    private ExecutableElement findMethod(TypeMirror t, String name) {
        TypeMirror e = types.erasure(t);
        if (!(e instanceof DeclaredType dt) || !(dt.asElement() instanceof TypeElement te)) {
            return null;
        }
        for (Element m : elements.getAllMembers(te)) {
            if (m instanceof ExecutableElement ee && ee.getKind() == ElementKind.METHOD && ee.getSimpleName().contentEquals(name)
                    && ee.getParameters().isEmpty()) {
                return ee;
            }
        }
        return null;
    }

    private Stmt.Block block(TreePath path, BlockTree b) {
        List<Stmt> out = new ArrayList<>();
        for (StatementTree st : b.getStatements()) {
            if (!(st instanceof EmptyStatementTree)) {
                Stmt s = stmt(new TreePath(path, st));
                // javac が補う super() は、外側がない JDK のクラス（Object / Enum / Record）なら何もしない。
                if (s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.Unsupported u && u.description().equals(DROP)) {
                    continue;
                }
                out.add(s);
            }
        }
        return new Stmt.Block(out, pos(b));
    }

    private static final String DROP = "<drop>";

    private Expr condition(TreePath parent, ExpressionTree cond) {
        return coerce(expr(new TreePath(parent, cond)), JType.BOOLEAN);
    }

    private Stmt tryStmt(TreePath path, TryTree tr) {
        SourcePos pos = pos(tr);
        Stmt.Block body = block(new TreePath(path, tr.getBlock()), tr.getBlock());
        // try-with-resources: 後に宣言したリソースから閉じる try/catch/finally の入れ子にする。
        List<? extends Tree> resources = tr.getResources();
        for (int i = resources.size() - 1; i >= 0; i--) {
            Tree res = resources.get(i);
            TreePath rp = new TreePath(path, res);
            List<Stmt> outer = new ArrayList<>();
            Expr resource;
            if (res instanceof VariableTree vt) {
                Stmt.LocalVar decl = (Stmt.LocalVar) stmt(rp);
                outer.add(decl);
                resource = new Expr.Local(decl.name(), decl.type(), pos(vt));
            } else {
                Expr e = expr(rp);
                String tmp = "__j2r_res" + i;
                outer.add(new Stmt.LocalVar(tmp, e.type(), e, true, pos(res)));
                resource = new Expr.Local(tmp, e.type(), pos(res));
            }
            // JLS 14.20.3.1: 本体で例外が起きたら、close() の例外はその例外の suppressed に加える。
            Stmt close = closeCall(resource, trees.getTypeMirror(rp), pos(res));
            JType throwable = new JType.ClassType("java.lang.Throwable");
            int id = ++resourceCounter;
            String primary = "__j2r_primary" + id;
            String caught = "__j2r_t" + id;
            String closeEx = "__j2r_x" + id;
            Expr primaryRef = new Expr.Local(primary, throwable, pos);
            outer.add(new Stmt.LocalVar(primary, throwable, new Expr.Literal(null, throwable, pos), true, pos));
            Stmt.Block onThrow = new Stmt.Block(List.of(
                    new Stmt.ExprStmt(new Expr.Assign(primaryRef, new Expr.Local(caught, throwable, pos), throwable, pos), pos),
                    new Stmt.Throw(new Expr.Local(caught, throwable, pos), pos)), pos);
            Stmt.Block suppress = new Stmt.Block(List.of(new Stmt.ExprStmt(new Expr.Call(throwableMethod("addSuppressed"), primaryRef,
                    List.of(new Expr.Local(closeEx, throwable, pos)), false, JType.VOID, pos), pos)), pos);
            Stmt closeQuietly = new Stmt.Try(new Stmt.Block(List.of(close), pos),
                    List.of(new Stmt.Catch(List.of("java.lang.Throwable"), closeEx, suppress, pos)), null, pos);
            Stmt closeStmt = new Stmt.If(new Expr.Binary(BinaryOp.NE, primaryRef, new Expr.Literal(null, throwable, pos), throwable, JType.BOOLEAN, pos),
                    closeQuietly, close, pos);
            Stmt.Block fin = new Stmt.Block(List.of(new Stmt.If(
                    new Expr.Binary(BinaryOp.NE, resource, new Expr.Literal(null, resource.type(), pos), resource.type(), JType.BOOLEAN, pos),
                    closeStmt, null, pos)), pos);
            outer.add(new Stmt.Try(body, List.of(new Stmt.Catch(List.of("java.lang.Throwable"), caught, onThrow, pos)), fin, pos));
            body = new Stmt.Block(outer, pos);
        }
        List<Stmt.Catch> catches = new ArrayList<>();
        for (CatchTree c : tr.getCatches()) {
            TreePath cp = new TreePath(path, c);
            VariableTree param = c.getParameter();
            List<String> typesCaught = new ArrayList<>();
            if (param.getType() instanceof UnionTypeTree u) {
                for (Tree alt : u.getTypeAlternatives()) {
                    typesCaught.add(qualifiedName(trees.getTypeMirror(new TreePath(new TreePath(new TreePath(cp, param), u), alt))));
                }
            } else {
                typesCaught.add(qualifiedName(trees.getElement(new TreePath(cp, param)).asType()));
            }
            catches.add(new Stmt.Catch(typesCaught, param.getName().toString(), block(new TreePath(cp, c.getBlock()), c.getBlock()), pos(c)));
        }
        Stmt.Block fin = tr.getFinallyBlock() == null ? null : block(new TreePath(path, tr.getFinallyBlock()), tr.getFinallyBlock());
        if (resources.isEmpty() || !catches.isEmpty() || fin != null) {
            return new Stmt.Try(body, catches, fin, pos);
        }
        return body;
    }

    /** java.lang.Throwable の引数 1 つのメソッド。 */
    private MethodRef throwableMethod(String name) {
        TypeElement te = elements.getTypeElement("java.lang.Throwable");
        for (Element e : te.getEnclosedElements()) {
            if (e instanceof ExecutableElement m && m.getSimpleName().contentEquals(name) && m.getParameters().size() == 1) {
                return methodRef(m);
            }
        }
        throw new IllegalStateException("java.lang.Throwable." + name);
    }

    /** {@code resource.close()} の呼び出し。 */
    private Stmt closeCall(Expr resource, TypeMirror type, SourcePos pos) {
        if (types.asElement(type) instanceof TypeElement te) {
            for (Element e : elements.getAllMembers(te)) {
                if (e instanceof ExecutableElement m && m.getSimpleName().contentEquals("close") && m.getParameters().isEmpty()
                        && m.getKind() == ElementKind.METHOD) {
                    return new Stmt.ExprStmt(new Expr.Call(methodRef(m), resource, List.of(), false, JType.VOID, pos), pos);
                }
            }
        }
        return unsupportedStmt(DiagnosticCode.UNSUPPORTED_API, null, "resource without close()");
    }

    // ---------------------------------------------------------------- switch

    private Expr switchSelector(TreePath path) {
        Expr sel = expr(path);
        JType st = sel.type();
        if (st instanceof JType.ClassType c && BOXES.contains(c.qualifiedName())) {
            return coerce(sel, unboxedType(c));
        }
        return sel;
    }

    /**
     * case の一覧を変換する。resultType は switch 式の型（switch 文なら null）。
     * 対応できないラベルがあれば null。
     */
    private List<Stmt.SwitchCase> switchCases(TreePath path, List<? extends CaseTree> caseTrees, JType resultType) {
        List<Stmt.SwitchCase> cases = new ArrayList<>();
        for (CaseTree c : caseTrees) {
            TreePath cp = new TreePath(path, c);
            List<Object> labels = new ArrayList<>();
            boolean isDefault = false;
            Expr patternGuard = null;
            for (CaseLabelTree lt : c.getLabels()) {
                TreePath lp = new TreePath(cp, lt);
                switch (lt) {
                    case DefaultCaseLabelTree d -> isDefault = true;
                    case ConstantCaseLabelTree cl -> {
                        TreePath ep = new TreePath(lp, cl.getConstantExpression());
                        Element el = trees.getElement(ep);
                        if (cl.getConstantExpression() instanceof LiteralTree lit && lit.getKind() == Tree.Kind.NULL_LITERAL) {
                            labels.add(new Stmt.NullLabel());
                        } else if (el != null && el.getKind() == ElementKind.ENUM_CONSTANT) {
                            labels.add(new Stmt.EnumLabel(el.getSimpleName().toString(), enumOrdinal((VariableElement) el)));
                        } else {
                            Object v = constantValue(ep);
                            if (v == null) {
                                report(DiagnosticCode.UNSUPPORTED_SYNTAX, lt, "non-constant case label");
                                return null;
                            }
                            labels.add(v);
                        }
                    }
                    case PatternCaseLabelTree pl -> {
                        TreePath pp = new TreePath(lp, pl.getPattern());
                        if (pl.getPattern() instanceof BindingPatternTree bp) {
                            VariableElement ve = (VariableElement) trees.getElement(new TreePath(pp, bp.getVariable()));
                            labels.add(new Stmt.TypePattern(type(types.erasure(ve.asType())), bindingName(ve)));
                        } else if (pl.getPattern() instanceof DeconstructionPatternTree dp) {
                            // record パターン: 型の一致を case のラベルに、構成要素の一致をガードの前半にする。
                            Deconstruction d = deconstruct(pp, dp);
                            labels.add(new Stmt.TypePattern(d.type(), d.temp()));
                            if (d.nested() != null) {
                                patternGuard = patternGuard == null ? d.nested() : and(patternGuard, d.nested());
                            }
                        } else {
                            report(DiagnosticCode.UNSUPPORTED_SYNTAX, lt, "unsupported pattern " + pl.getPattern().getKind());
                            return null;
                        }
                    }
                    default -> {
                        report(DiagnosticCode.UNSUPPORTED_SYNTAX, lt, "unsupported case label");
                        return null;
                    }
                }
            }
            Expr guard = c.getGuard() == null ? null : condition(cp, c.getGuard());
            if (patternGuard != null) {
                guard = guard == null ? patternGuard : and(patternGuard, guard);
            }
            List<Stmt> body = new ArrayList<>();
            boolean arrow = c.getCaseKind() == CaseTree.CaseKind.RULE;
            if (arrow) {
                Tree b = c.getBody();
                TreePath bp = new TreePath(cp, b);
                if (b instanceof ExpressionTree et) {
                    Expr e = expr(bp);
                    body.add(resultType != null ? new Stmt.Yield(coerce(e, resultType), pos(et)) : new Stmt.ExprStmt(e, pos(et)));
                } else {
                    body.add(stmt(bp));
                }
            } else {
                for (StatementTree s : c.getStatements()) {
                    body.add(stmt(new TreePath(cp, s)));
                }
            }
            if (resultType != null) {
                body = coerceYields(body, resultType);
            }
            cases.add(new Stmt.SwitchCase(labels, isDefault, guard, body, arrow, pos(c)));
        }
        return cases;
    }

    /** switch 式の yield の値を式の型に変換する（入れ子の switch 式の yield は対象外）。 */
    private List<Stmt> coerceYields(List<Stmt> body, JType resultType) {
        io.github.ykwyuta.j2r.jir.JirRewriter r = new io.github.ykwyuta.j2r.jir.JirRewriter() {
            @Override
            protected Stmt rewriteStmt(Stmt s) {
                return s instanceof Stmt.Yield y ? new Stmt.Yield(coerce(y.value(), resultType), y.pos()) : s;
            }

            @Override
            protected Expr rewriteExpr(Expr e) {
                return e;
            }
        };
        List<Stmt> out = new ArrayList<>();
        for (Stmt s : body) {
            out.add(containsSwitchExpr(s) ? s : r.stmt(s));
        }
        return out;
    }

    private static boolean containsSwitchExpr(Stmt s) {
        boolean[] found = {false};
        io.github.ykwyuta.j2r.jir.JirVisitor.walk(s, x -> { }, e -> found[0] |= e instanceof Expr.SwitchExpr);
        return found[0];
    }

    private int enumOrdinal(VariableElement constant) {
        int i = 0;
        for (Element e : constant.getEnclosingElement().getEnclosedElements()) {
            if (e.getKind() == ElementKind.ENUM_CONSTANT) {
                if (e.equals(constant)) {
                    return i;
                }
                i++;
            }
        }
        return -1;
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
        if (t instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) {
            Object v = constantValue(new TreePath(path, u.getExpression()));
            if (v instanceof Integer i) {
                return -i;
            }
        }
        return null;
    }

    // ================================================================== 式

    private Expr expr(TreePath path) {
        Tree t = path.getLeaf();
        SourcePos pos = pos(t);
        return switch (t) {
            case ParenthesizedTree p -> expr(new TreePath(path, p.getExpression()));
            case LiteralTree lit -> lit.getKind() == Tree.Kind.NULL_LITERAL
                    ? new Expr.Literal(null, new JType.NullType(), pos)
                    : new Expr.Literal(lit.getValue(), typeOf(path), pos);
            case IdentifierTree id -> identifier(path, id);
            case MemberSelectTree ms -> memberSelect(path, ms);
            case MethodInvocationTree mi -> invocation(path, mi);
            case NewClassTree nc -> newClass(path, nc);
            case NewArrayTree na -> newArray(path, na);
            case ArrayAccessTree aa -> new Expr.ArrayAccess(expr(new TreePath(path, aa.getExpression())),
                    coerce(expr(new TreePath(path, aa.getIndex())), JType.INT), erasedTypeOf(path), pos);
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
                JType ty = erasedTypeOf(path);
                yield new Expr.Conditional(condition(path, c.getCondition()),
                        coerce(expr(new TreePath(path, c.getTrueExpression())), ty),
                        coerce(expr(new TreePath(path, c.getFalseExpression())), ty), ty, pos);
            }
            case TypeCastTree tc -> typeCast(path, tc);
            case InstanceOfTree io -> instanceOf(path, io);
            case LambdaExpressionTree le -> lambda(path, le);
            case MemberReferenceTree mr -> memberReference(path, mr);
            case SwitchExpressionTree se -> {
                JType ty = erasedTypeOf(path);
                Expr selector = switchSelector(new TreePath(path, se.getExpression()));
                List<Stmt.SwitchCase> cases = switchCases(path, se.getCases(), ty);
                yield cases == null ? unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, se, "unsupported switch expression", ty)
                        : new Expr.SwitchExpr(selector, cases, ty, pos);
            }
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, t, "expression '" + t.getKind() + "' is not supported yet", typeOf(path));
        };
    }

    private Expr identifier(TreePath path, IdentifierTree id) {
        if (id.getName().contentEquals("this") || id.getName().contentEquals("super")) {
            return thisRef(currentType, pos(id));
        }
        Element el = trees.getElement(path);
        if (el == null) {
            return unsupportedExpr(DiagnosticCode.INTERNAL, id, "unresolved identifier " + id.getName(), typeOf(path));
        }
        return switch (el.getKind()) {
            case BINDING_VARIABLE -> new Expr.Local(bindingName((VariableElement) el), type(el.asType()), pos(id));
            case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE ->
                    localRef((VariableElement) el, pos(id));
            case FIELD, ENUM_CONSTANT -> {
                VariableElement ve = (VariableElement) el;
                yield fieldRef(path, ve, ve.getModifiers().contains(Modifier.STATIC) ? null : implicitThis(ve, pos(id)), id);
            }
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, id, "reference to " + el.getKind() + " '" + id.getName() + "'", typeOf(path));
        };
    }

    private Expr thisRef(TypeElement te, SourcePos pos) {
        return new Expr.This(new JType.ClassType(qualifiedNameOf(te)), 0, pos);
    }

    /**
     * メンバー m を暗黙の this で参照するときの this。m が外側のクラスのメンバーなら {@code Outer.this}（outerLevels > 0）。
     */
    private Expr implicitThis(Element member, SourcePos pos) {
        TypeElement owner = (TypeElement) member.getEnclosingElement();
        TypeElement cur = currentType;
        int levels = 0;
        while (cur != null) {
            if (types.isSubtype(types.erasure(cur.asType()), types.erasure(owner.asType()))) {
                return new Expr.This(new JType.ClassType(qualifiedNameOf(cur)), levels, pos);
            }
            if (!hasOuterInstance(cur)) {
                break;
            }
            cur = enclosingType(cur);
            levels++;
        }
        return new Expr.This(new JType.ClassType(qualifiedNameOf(currentType)), 0, pos);
    }

    private Expr memberSelect(TreePath path, MemberSelectTree ms) {
        TreePath qualifier = new TreePath(path, ms.getExpression());
        TypeMirror qt = trees.getTypeMirror(qualifier);
        if (qt != null && qt.getKind() == TypeKind.ARRAY && ms.getIdentifier().contentEquals("length")) {
            return new Expr.ArrayLength(expr(qualifier), JType.INT, pos(ms));
        }
        Element el = trees.getElement(path);
        // Outer.this
        if (ms.getIdentifier().contentEquals("this") && trees.getElement(qualifier) instanceof TypeElement target) {
            return outerThis(target, pos(ms));
        }
        if (el instanceof VariableElement ve && (el.getKind() == ElementKind.FIELD || el.getKind() == ElementKind.ENUM_CONSTANT)) {
            if (ve.getModifiers().contains(Modifier.STATIC)) {
                return fieldRef(path, ve, null, ms);
            }
            Expr receiver = isSuperOrThis(ms.getExpression()) ? implicitThis(ve, pos(ms)) : expr(qualifier);
            return fieldRef(path, ve, receiver, ms);
        }
        return unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, ms, "member reference '" + ms + "' is not supported yet", typeOf(path));
    }

    private Expr outerThis(TypeElement target, SourcePos pos) {
        TypeElement cur = currentType;
        int levels = 0;
        while (cur != null && !cur.equals(target)) {
            cur = enclosingType(cur);
            levels++;
        }
        return new Expr.This(new JType.ClassType(qualifiedNameOf(target)), cur == null ? 0 : levels, pos);
    }

    /** 外側のインスタンスを持つ（static でない内部クラス、またはインスタンスの文脈のローカル・匿名クラス）か。 */
    private boolean hasOuterInstance(TypeElement te) {
        if (te.getNestingKind() == NestingKind.MEMBER) {
            return isInner(te);
        }
        return localWithOuter.contains(te);
    }

    /** static フィールド（receiver == null）またはインスタンスフィールドの参照。 */
    private Expr fieldRef(TreePath path, VariableElement ve, Expr receiver, Tree t) {
        String owner = qualifiedNameOf(((TypeElement) ve.getEnclosingElement()));
        JType declared = type(types.erasure(ve.asType()));
        if (receiver == null) {
            // 変換対象外（JDK 等）の定数は値を埋め込む。変換対象内の定数は名前を保つ。
            if (ve.getConstantValue() != null && !programTypes.contains(owner)) {
                return new Expr.Literal(ve.getConstantValue(), type(ve.asType()), pos(t));
            }
            return narrowTo(new Expr.StaticField(owner, ve.getSimpleName().toString(), declared, pos(t)), path);
        }
        if (!programTypes.contains(owner)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_API, t, "instance field " + owner + "." + ve.getSimpleName() + " is not supported", declared);
        }
        return narrowTo(new Expr.FieldAccess(receiver, owner, ve.getSimpleName().toString(), declared, pos(t)), path);
    }

    /** 消去後の型 e.type() と、javac が具体化した型が違えば Cast を挟む（ジェネリクスの checkcast に相当）。 */
    private Expr narrowTo(Expr e, TreePath path) {
        JType actual = erasedTypeOf(path);
        if (actual instanceof JType.Unsupported || actual instanceof JType.Void || actual.equals(e.type())) {
            return e;
        }
        return new Expr.Cast(e, actual, true, e.pos());
    }

    private Expr lvalue(TreePath path) {
        Expr e = expr(path);
        if (e instanceof Expr.Cast c && c.implicit()) {
            e = c.expr();
        }
        return switch (e) {
            case Expr.Local l -> l;
            case Expr.StaticField f -> f;
            case Expr.FieldAccess f -> f;
            case Expr.ArrayAccess a -> a;
            case Expr.Unsupported u -> u;
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, path.getLeaf(), "unsupported assignment target", e.type());
        };
    }

    private Expr invocation(TreePath path, MethodInvocationTree mi) {
        TreePath selPath = new TreePath(path, mi.getMethodSelect());
        Element el = trees.getElement(selPath);
        if (!(el instanceof ExecutableElement ee)) {
            return unsupportedExpr(DiagnosticCode.INTERNAL, mi, "unresolved method " + mi.getMethodSelect(), typeOf(path));
        }
        MethodRef ref = methodRef(ee);
        // this(...) / super(...)
        if (ee.getKind() == ElementKind.CONSTRUCTOR) {
            boolean isSuper = mi.getMethodSelect() instanceof IdentifierTree id && id.getName().contentEquals("super");
            if (isSuper && Set.of("java.lang.Object", "java.lang.Enum", "java.lang.Record").contains(ref.owner())) {
                return new Expr.Unsupported(DROP, JType.VOID, pos(mi));
            }
            Expr outer = null;
            if (programTypes.contains(ref.owner())) {
                TypeElement ownerEl = (TypeElement) ee.getEnclosingElement();
                if (isInner(ownerEl)) {
                    outer = new Expr.This(new JType.ClassType(qualifiedNameOf(currentType)), 1, pos(mi));
                }
            }
            return new Expr.CtorCall(isSuper, ref, args(path, mi.getArguments(), ref, ee.isVarArgs()), outer, JType.VOID, pos(mi));
        }
        Expr receiver = null;
        boolean superCall = false;
        if (!ref.isStatic()) {
            if (mi.getMethodSelect() instanceof MemberSelectTree ms) {
                if (isSuper(ms.getExpression())) {
                    superCall = true;
                    receiver = thisRef(currentType, pos(mi));
                } else if (ms.getExpression() instanceof MemberSelectTree q && q.getIdentifier().contentEquals("super")) {
                    // Interface.super.m()
                    superCall = true;
                    receiver = thisRef(currentType, pos(mi));
                } else {
                    receiver = expr(new TreePath(selPath, ms.getExpression()));
                }
            } else {
                receiver = implicitThis(ee, pos(mi));
            }
        }
        List<Expr> args = args(path, mi.getArguments(), ref, ee.isVarArgs());
        return narrowTo(new Expr.Call(ref, receiver, args, superCall, ref.returnType(), pos(mi)), path);
    }

    private boolean isInner(TypeElement te) {
        if (te.getNestingKind() == NestingKind.LOCAL || te.getNestingKind() == NestingKind.ANONYMOUS) {
            return localWithOuter.contains(te);
        }
        return te.getNestingKind() == NestingKind.MEMBER && te.getKind() == ElementKind.CLASS && !te.getModifiers().contains(Modifier.STATIC);
    }

    private static boolean isSuperOrThis(ExpressionTree e) {
        return e instanceof IdentifierTree id && (id.getName().contentEquals("this") || id.getName().contentEquals("super"));
    }

    private static boolean isSuper(ExpressionTree e) {
        return e instanceof IdentifierTree id && id.getName().contentEquals("super");
    }

    /** 実引数を仮引数の型に変換する。可変長引数の呼び出しなら末尾の引数を配列にまとめる。 */
    private List<Expr> args(TreePath path, List<? extends ExpressionTree> argTrees, MethodRef ref, boolean varargs) {
        List<Expr> raw = new ArrayList<>();
        for (ExpressionTree a : argTrees) {
            raw.add(expr(new TreePath(path, a)));
        }
        int n = ref.paramTypes().size();
        boolean packed = varargs && !(raw.size() == n && raw.get(n - 1).type() instanceof JType.ArrayType)
                && !(raw.size() == n && raw.get(n - 1).type() instanceof JType.NullType);
        List<Expr> out = new ArrayList<>();
        if (packed) {
            for (int i = 0; i < n - 1; i++) {
                out.add(coerce(raw.get(i), ref.paramTypes().get(i)));
            }
            JType.ArrayType at = (JType.ArrayType) ref.paramTypes().get(n - 1);
            List<Expr> rest = new ArrayList<>();
            for (int i = n - 1; i < raw.size(); i++) {
                rest.add(coerce(raw.get(i), at.component()));
            }
            SourcePos p = raw.isEmpty() ? SourcePos.UNKNOWN : raw.get(raw.size() - 1).pos();
            out.add(new Expr.NewArray(at, List.of(), rest, p));
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            out.add(i < n ? coerce(raw.get(i), ref.paramTypes().get(i)) : raw.get(i));
        }
        return out;
    }

    private Expr newClass(TreePath path, NewClassTree nc) {
        JType ty = erasedTypeOf(path);
        if (nc.getClassBody() != null) {
            TreePath bodyPath = new TreePath(path, nc.getClassBody());
            TypeElement anon = (TypeElement) trees.getElement(bodyPath);
            localClass(bodyPath, nc.getClassBody(), anon);
            ty = new JType.ClassType(qualifiedNameOf(anon));
        }
        if (!(trees.getElement(path) instanceof ExecutableElement ctor)) {
            return unsupportedExpr(DiagnosticCode.INTERNAL, nc, "unresolved constructor", ty);
        }
        MethodRef ref = methodRef(ctor);
        TypeElement cls = (TypeElement) ctor.getEnclosingElement();
        List<Expr> args = new ArrayList<>(args(path, nc.getArguments(), ref, ctor.isVarArgs()));
        if (programTypes.contains(ref.owner())) {
            Expr outer = null;
            if (isInner(cls)) {
                outer = nc.getEnclosingExpression() != null ? expr(new TreePath(path, nc.getEnclosingExpression()))
                        : localWithOuter.contains(cls) ? thisRef(currentType, pos(nc)) : implicitThis(cls, pos(nc));
            }
            // ローカルクラス・匿名クラスが捕捉した変数を追加の引数として渡す。
            for (VariableElement v : localCaptures.getOrDefault(cls, List.of())) {
                args.add(localRef(v, pos(nc)));
            }
            return new Expr.New(ref, args, outer, false, ty, pos(nc));
        }
        boolean throwable = types.isSubtype(types.erasure(cls.asType()), types.erasure(throwableType));
        return new Expr.New(ref, args, null, throwable, ty, pos(nc));
    }

    private Expr newArray(TreePath path, NewArrayTree na) {
        JType ty = erasedTypeOf(path);
        if (!(ty instanceof JType.ArrayType at)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_TYPE, na, "array of unsupported type", ty);
        }
        List<Expr> dims = new ArrayList<>();
        for (ExpressionTree d : na.getDimensions()) {
            dims.add(coerce(expr(new TreePath(path, d)), JType.INT));
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

    private Expr typeCast(TreePath path, TypeCastTree tc) {
        JType target = erasedTypeOf(path);
        Expr inner = expr(new TreePath(path, tc.getExpression()));
        JType from = inner.type();
        SourcePos pos = pos(tc);
        if (from instanceof JType.Primitive && target instanceof JType.Primitive) {
            return from.equals(target) ? inner : new Expr.Cast(inner, target, false, pos);
        }
        if (target instanceof JType.Primitive tp) {
            // (int) obj: Integer への checkcast とアンボクシング。(long) integerBox: アンボクシングして拡大。
            if (from instanceof JType.ClassType c && BOXES.contains(c.qualifiedName())) {
                return coerce(inner, target);
            }
            Expr boxed = new Expr.Cast(inner, boxedType(tp), false, pos);
            return coerce(boxed, target);
        }
        if (from instanceof JType.Primitive) {
            return coerce(inner, target);
        }
        if (from.equals(target) || inner instanceof Expr.Unsupported) {
            return inner;
        }
        return new Expr.Cast(inner, target, false, pos);
    }

    private Expr instanceOf(TreePath path, InstanceOfTree io) {
        Expr e = expr(new TreePath(path, io.getExpression()));
        SourcePos pos = pos(io);
        if (io.getPattern() instanceof BindingPatternTree bp) {
            TreePath vp = new TreePath(new TreePath(new TreePath(path, io.getPattern()), bp), bp.getVariable());
            VariableElement ve = (VariableElement) trees.getElement(vp);
            return new Expr.InstanceOf(e, type(types.erasure(ve.asType())), bindingName(ve), JType.BOOLEAN, pos);
        }
        if (io.getPattern() instanceof DeconstructionPatternTree dp) {
            Deconstruction d = deconstruct(new TreePath(path, dp), dp);
            Expr test = new Expr.InstanceOf(e, d.type(), d.temp(), JType.BOOLEAN, pos);
            return d.nested() == null ? test : and(test, d.nested());
        }
        if (io.getPattern() != null) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, io, "unsupported pattern " + io.getPattern().getKind(), JType.BOOLEAN);
        }
        JType t = type(types.erasure(trees.getTypeMirror(new TreePath(path, io.getType()))));
        return new Expr.InstanceOf(e, t, null, JType.BOOLEAN, pos);
    }

    // ---------------------------------------------------------------- パターン

    /** パターンの束縛変数の名前。同じメソッドの中で同名・別の型の束縛変数があれば、名前に番号を付ける。 */
    private String bindingName(VariableElement ve) {
        String known = bindingNames.get(ve);
        if (known != null) {
            return known;
        }
        JType t = type(types.erasure(ve.asType()));
        String name = ve.getSimpleName().toString();
        String candidate = name;
        for (int i = 2; bindingTypes.containsKey(candidate) && !bindingTypes.get(candidate).equals(t); i++) {
            candidate = name + "_p" + i;
        }
        bindingTypes.put(candidate, t);
        bindingNames.put(ve, candidate);
        return candidate;
    }

    private static Expr and(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.AND, a, b, JType.BOOLEAN, JType.BOOLEAN, a.pos());
    }

    /** record パターン: 型の一致を調べる一時変数 temp（record の型）と、構成要素のパターンの条件（なければ null）。 */
    private record Deconstruction(String temp, JType type, Expr nested) {}

    private Deconstruction deconstruct(TreePath pp, DeconstructionPatternTree dp) {
        TypeMirror rt = trees.getTypeMirror(pp);
        TypeElement re = (TypeElement) types.asElement(rt);
        String temp = "__jrp" + ++patternTemps;
        JType rjt = type(types.erasure(rt));
        SourcePos pos = pos(dp);
        Expr nested = null;
        List<? extends PatternTree> patterns = dp.getNestedPatterns();
        List<? extends RecordComponentElement> comps = re.getRecordComponents();
        for (int i = 0; i < patterns.size() && i < comps.size(); i++) {
            ExecutableElement acc = comps.get(i).getAccessor();
            TypeMirror ct = rt instanceof DeclaredType dt
                    ? ((javax.lang.model.type.ExecutableType) types.asMemberOf(dt, acc)).getReturnType() : acc.getReturnType();
            Expr value = new Expr.Call(methodRef(acc), new Expr.Local(temp, rjt, pos), List.of(), false, type(types.erasure(acc.getReturnType())), pos);
            Expr cond = componentCondition(new TreePath(pp, patterns.get(i)), patterns.get(i), value, ct);
            if (cond != null) {
                nested = nested == null ? cond : and(nested, cond);
            }
        }
        return new Deconstruction(temp, rjt, nested);
    }

    /** record の構成要素 value（型 ct）がパターン p に一致する条件（常に一致して束縛もなければ null）。 */
    private Expr componentCondition(TreePath pp, PatternTree p, Expr value, TypeMirror ct) {
        SourcePos pos = pos(p);
        if (p instanceof BindingPatternTree bp) {
            VariableElement ve = (VariableElement) trees.getElement(new TreePath(pp, bp.getVariable()));
            TypeMirror pt = ve.asType();
            String name = bindingName(ve);
            JType jt = type(types.erasure(pt));
            if (types.isSubtype(types.erasure(ct), types.erasure(pt))) {
                // 常に一致する（null も含む）パターン: 束縛するだけ。
                return new Expr.Bind(name, coerce(value, jt), JType.BOOLEAN, pos);
            }
            return new Expr.InstanceOf(value, jt, name, JType.BOOLEAN, pos);
        }
        if (p instanceof DeconstructionPatternTree dp) {
            Deconstruction d = deconstruct(pp, dp);
            Expr test = new Expr.InstanceOf(value, d.type(), d.temp(), JType.BOOLEAN, pos);
            return d.nested() == null ? test : and(test, d.nested());
        }
        // Java 21 のプレビューの無名パターン（_）などは、常に一致するとみなす。
        return null;
    }

    // ---------------------------------------------------------------- ラムダ・メソッド参照

    /** 関数型インタフェースの唯一の抽象メソッド。 */
    private ExecutableElement samOf(TypeElement iface) {
        for (Element e : elements.getAllMembers(iface)) {
            if (e instanceof ExecutableElement m && m.getModifiers().contains(Modifier.ABSTRACT)
                    && !isObjectMethod(m)) {
                return m;
            }
        }
        return null;
    }

    private boolean isObjectMethod(ExecutableElement m) {
        TypeElement object = elements.getTypeElement("java.lang.Object");
        for (Element e : object.getEnclosedElements()) {
            if (e instanceof ExecutableElement om && om.getSimpleName().equals(m.getSimpleName())
                    && elements.overrides(m, om, (TypeElement) m.getEnclosingElement())) {
                return true;
            }
        }
        return m.getSimpleName().contentEquals("equals") && m.getParameters().size() == 1
                || (m.getSimpleName().contentEquals("hashCode") || m.getSimpleName().contentEquals("toString")) && m.getParameters().isEmpty();
    }

    private record Functional(DeclaredType type, ExecutableElement sam, ExecutableType instantiated, List<String> interfaces) {}

    private Functional functional(TreePath path) {
        TypeMirror t = trees.getTypeMirror(path);
        if (t instanceof javax.lang.model.type.IntersectionType it) {
            t = it.getBounds().get(0);
        }
        if (!(t instanceof DeclaredType dt) || !(dt.asElement() instanceof TypeElement iface)) {
            return null;
        }
        ExecutableElement sam = samOf(iface);
        if (sam == null) {
            return null;
        }
        ExecutableType inst = (ExecutableType) types.asMemberOf(dt, sam);
        List<String> ifaces = new ArrayList<>();
        ifaces.add(qualifiedNameOf(iface));
        for (String s : allSupertypes(iface)) {
            if (!s.equals("java.lang.Object")) {
                ifaces.add(s);
            }
        }
        return new Functional(dt, sam, inst, ifaces);
    }

    private Expr lambda(TreePath path, LambdaExpressionTree le) {
        JType ty = erasedTypeOf(path);
        Functional f = functional(path);
        if (f == null) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, le, "lambda with an unsupported target type", ty);
        }
        List<Decl.Param> params = new ArrayList<>();
        for (VariableTree p : le.getParameters()) {
            Element pe = trees.getElement(new TreePath(path, p));
            params.add(new Decl.Param(p.getName().toString(), type(types.erasure(pe.asType()))));
        }
        JType ret = type(types.erasure(f.instantiated().getReturnType()));
        JType saved = currentReturnType;
        currentReturnType = ret;
        try {
            TreePath bp = new TreePath(path, le.getBody());
            Stmt.Block body;
            if (le.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION) {
                Expr e = expr(bp);
                body = new Stmt.Block(List.of(ret instanceof JType.Void ? new Stmt.ExprStmt(e, e.pos())
                        : new Stmt.Return(coerce(e, ret), e.pos())), pos(le));
            } else {
                body = (Stmt.Block) stmt(bp);
            }
            return new Expr.Lambda(params, body, methodRef(f.sam()), f.interfaces(), ty, pos(le));
        } finally {
            currentReturnType = saved;
        }
    }

    /** メソッド参照をラムダに正規化する（修飾子の式は呼び出しのたびに評価する）。 */
    private Expr memberReference(TreePath path, MemberReferenceTree mr) {
        JType ty = erasedTypeOf(path);
        SourcePos pos = pos(mr);
        Functional f = functional(path);
        if (f == null || !(trees.getElement(path) instanceof ExecutableElement target)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, mr, "unsupported method reference", ty);
        }
        List<Decl.Param> params = new ArrayList<>();
        List<Expr> lambdaArgs = new ArrayList<>();
        int i = 0;
        for (TypeMirror pt : f.instantiated().getParameterTypes()) {
            JType jt = type(types.erasure(pt));
            String name = "__j2r_p" + i++;
            params.add(new Decl.Param(name, jt));
            lambdaArgs.add(new Expr.Local(name, jt, pos));
        }
        JType ret = type(types.erasure(f.instantiated().getReturnType()));
        MethodRef ref = methodRef(target);
        TreePath qp = new TreePath(path, mr.getQualifierExpression());
        boolean qualifierIsType = trees.getElement(qp) instanceof TypeElement;
        Expr call;
        if (mr.getMode() == MemberReferenceTree.ReferenceMode.NEW) {
            if (trees.getTypeMirror(qp) instanceof ArrayType) {
                JType.ArrayType at = (JType.ArrayType) type(types.erasure(trees.getTypeMirror(qp)));
                call = new Expr.NewArray(at, List.of(coerce(lambdaArgs.get(0), JType.INT)), null, pos);
            } else if (programTypes.contains(ref.owner())) {
                call = new Expr.New(ref, coerceArgs(lambdaArgs, ref, target.isVarArgs()), null, false,
                        type(types.erasure(target.getEnclosingElement().asType())), pos);
            } else {
                TypeElement cls = (TypeElement) target.getEnclosingElement();
                boolean throwable = types.isSubtype(types.erasure(cls.asType()), types.erasure(throwableType));
                call = new Expr.New(ref, coerceArgs(lambdaArgs, ref, target.isVarArgs()), null, throwable,
                        type(types.erasure(cls.asType())), pos);
            }
        } else if (ref.isStatic()) {
            call = new Expr.Call(ref, null, coerceArgs(lambdaArgs, ref, target.isVarArgs()), false, ref.returnType(), pos);
        } else if (qualifierIsType && !isSuperOrThis(mr.getQualifierExpression())) {
            // 非束縛のインスタンスメソッド参照（String::length）: 最初の引数がレシーバ。
            Expr recv = coerce(lambdaArgs.get(0), type(types.erasure(target.getEnclosingElement().asType())));
            call = new Expr.Call(ref, recv, coerceArgs(lambdaArgs.subList(1, lambdaArgs.size()), ref, target.isVarArgs()),
                    false, ref.returnType(), pos);
        } else {
            boolean sup = isSuper(mr.getQualifierExpression());
            Expr recv = sup ? thisRef(currentType, pos) : expr(qp);
            call = new Expr.Call(ref, recv, coerceArgs(lambdaArgs, ref, target.isVarArgs()), sup, ref.returnType(), pos);
        }
        Stmt s = ret instanceof JType.Void ? new Stmt.ExprStmt(call, pos) : new Stmt.Return(coerce(call, ret), pos);
        return new Expr.Lambda(params, new Stmt.Block(List.of(s), pos), methodRef(f.sam()), f.interfaces(), ty, pos);
    }

    private List<Expr> coerceArgs(List<Expr> raw, MethodRef ref, boolean varargs) {
        int n = ref.paramTypes().size();
        List<Expr> out = new ArrayList<>();
        boolean packed = varargs && !(raw.size() == n && raw.get(n - 1).type() instanceof JType.ArrayType);
        if (packed) {
            for (int i = 0; i < n - 1; i++) {
                out.add(coerce(raw.get(i), ref.paramTypes().get(i)));
            }
            JType.ArrayType at = (JType.ArrayType) ref.paramTypes().get(n - 1);
            List<Expr> rest = new ArrayList<>();
            for (int i = n - 1; i < raw.size(); i++) {
                rest.add(coerce(raw.get(i), at.component()));
            }
            out.add(new Expr.NewArray(at, List.of(), rest, SourcePos.UNKNOWN));
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            out.add(i < n ? coerce(raw.get(i), ref.paramTypes().get(i)) : raw.get(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- 演算

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
        JType ut = unboxedType(tt);
        JType operandType;
        if (JType.isString(tt)) {
            operandType = JType.STRING;
        } else if (op.isShift()) {
            operandType = JType.unaryPromotion(ut);
            value = coerce(value, JType.unaryPromotion(unboxedType(value.type())));
        } else if (JType.isPrimitive(ut, JType.Kind.BOOLEAN)) {
            operandType = JType.BOOLEAN;
            value = coerce(value, JType.BOOLEAN);
        } else {
            operandType = JType.binaryPromotion(ut, unboxedType(value.type()));
            value = coerce(value, operandType);
        }
        return new Expr.CompoundAssign(op, target, value, operandType, tt, pos(ca));
    }

    private Expr unary(TreePath path, UnaryTree u) {
        TreePath op = new TreePath(path, u.getExpression());
        JType ty = erasedTypeOf(path);
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
            case LOGICAL_COMPLEMENT -> new Expr.Unary(UnaryOp.NOT, coerce(expr(op), JType.BOOLEAN), JType.BOOLEAN, pos);
            default -> unsupportedExpr(DiagnosticCode.UNSUPPORTED_SYNTAX, u, "unary " + u.getKind(), ty);
        };
    }

    private Expr binary(TreePath path, BinaryTree b) {
        Expr left = expr(new TreePath(path, b.getLeftOperand()));
        Expr right = expr(new TreePath(path, b.getRightOperand()));
        JType ty = erasedTypeOf(path);
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
        if (op == BinaryOp.ADD && JType.isString(ty)) {
            return new Expr.Binary(op, left, right, JType.STRING, JType.STRING, pos);
        }
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            return new Expr.Binary(op, coerce(left, JType.BOOLEAN), coerce(right, JType.BOOLEAN), JType.BOOLEAN, JType.BOOLEAN, pos);
        }
        JType lt = left.type();
        JType rt = right.type();
        JType lu = unboxedType(lt);
        JType ru = unboxedType(rt);
        if (op.isShift()) {
            JType lp = JType.unaryPromotion(lu);
            return new Expr.Binary(op, coerce(left, lp), coerce(right, JType.unaryPromotion(ru)), lp, ty, pos);
        }
        if (op == BinaryOp.EQ || op == BinaryOp.NE) {
            // 参照どうし（一方が null を含む）は同一性の比較。数値・boolean を含めばアンボクシングして値を比べる。
            boolean numeric = (lt instanceof JType.Primitive || rt instanceof JType.Primitive) && JType.isNumeric(lu) && JType.isNumeric(ru);
            boolean bool = (lt instanceof JType.Primitive || rt instanceof JType.Primitive)
                    && JType.isPrimitive(lu, JType.Kind.BOOLEAN) && JType.isPrimitive(ru, JType.Kind.BOOLEAN);
            if (!numeric && !bool) {
                if (JType.isString(lt) && JType.isString(rt)) {
                    diags.report(DiagnosticCode.LOSSY_STRING_IDENTITY, pos,
                            "String reference comparison (==/!=) is translated as value comparison");
                }
                JType operand = lt instanceof JType.NullType ? rt : lt;
                return new Expr.Binary(op, coerce(left, operand), coerce(right, operand), operand, JType.BOOLEAN, pos);
            }
        }
        if (op.isComparison()) {
            if (JType.isNumeric(lu) && JType.isNumeric(ru)) {
                JType operand = lu.equals(ru) ? lu : JType.binaryPromotion(lu, ru);
                return new Expr.Binary(op, coerce(left, operand), coerce(right, operand), operand, JType.BOOLEAN, pos);
            }
            return new Expr.Binary(op, coerce(left, JType.BOOLEAN), coerce(right, JType.BOOLEAN), JType.BOOLEAN, JType.BOOLEAN, pos);
        }
        if (JType.isPrimitive(lu, JType.Kind.BOOLEAN) && JType.isPrimitive(ru, JType.Kind.BOOLEAN)) {
            return new Expr.Binary(op, coerce(left, JType.BOOLEAN), coerce(right, JType.BOOLEAN), JType.BOOLEAN, JType.BOOLEAN, pos);
        }
        if (!JType.isNumeric(lu) || !JType.isNumeric(ru)) {
            return unsupportedExpr(DiagnosticCode.UNSUPPORTED_TYPE, b, "unsupported operand types for " + op.symbol(), ty);
        }
        JType operand = JType.binaryPromotion(lu, ru);
        return new Expr.Binary(op, coerce(left, operand), coerce(right, operand), operand, operand, pos);
    }

    // ---------------------------------------------------------------- 型変換

    /**
     * 代入変換・メソッド呼び出し変換。プリミティブ間の変換、ボクシング・アンボクシング、
     * String / 配列と参照型（Object など）の間の変換を Cast（implicit）で明示する。
     * それ以外の参照型どうしの拡大変換（Rust ではどちらも JObject）はそのまま。
     */
    private Expr coerce(Expr e, JType target) {
        JType from = e.type();
        if (target == null || target instanceof JType.Void || from.equals(target) || e instanceof Expr.Unsupported) {
            return e;
        }
        if (from instanceof JType.NullType) {
            return new Expr.Literal(null, target, e.pos());
        }
        if (from instanceof JType.Primitive && target instanceof JType.Primitive) {
            return new Expr.Cast(e, target, true, e.pos());
        }
        if (from instanceof JType.Primitive fp && !(target instanceof JType.Primitive)) {
            // ボクシング（int → Integer → Object など）。
            if (target instanceof JType.ClassType tc && BOXES.contains(tc.qualifiedName())) {
                JType prim = unboxedType(tc);
                Expr widened = prim.equals(from) ? e : new Expr.Cast(e, prim, true, e.pos());
                return new Expr.Cast(widened, tc, true, e.pos());
            }
            return new Expr.Cast(e, boxedType(fp), true, e.pos());
        }
        if (target instanceof JType.Primitive tp) {
            // アンボクシング（Integer → int → long など）。
            JType prim = unboxedType(from);
            if (!(prim instanceof JType.Primitive)) {
                prim = tp;
                e = new Expr.Cast(e, boxedType(tp), true, e.pos());
            }
            Expr unboxed = new Expr.Cast(e, prim, true, e.pos());
            return prim.equals(target) ? unboxed : new Expr.Cast(unboxed, target, true, e.pos());
        }
        boolean fromSpecial = JType.isString(from) || from instanceof JType.ArrayType || isNativeValue(from);
        boolean toSpecial = JType.isString(target) || target instanceof JType.ArrayType || isNativeValue(target);
        if (fromSpecial != toSpecial || (from instanceof JType.ArrayType && target instanceof JType.ArrayType && !from.equals(target))) {
            return new Expr.Cast(e, target, true, e.pos());
        }
        return e;
    }

    /** Rust では参照型（JObject）ではなく値の型で表す JDK のクラス（Object との間で変換が必要）。 */
    private static boolean isNativeValue(JType t) {
        return t instanceof JType.ClassType c && switch (c.qualifiedName()) {
            case "java.lang.StringBuilder", "java.util.Scanner", "java.io.PrintStream", "java.io.InputStream" -> true;
            default -> false;
        };
    }

    private static JType boxedType(JType.Primitive p) {
        return new JType.ClassType(switch (p.kind()) {
            case BOOLEAN -> "java.lang.Boolean";
            case BYTE -> "java.lang.Byte";
            case SHORT -> "java.lang.Short";
            case CHAR -> "java.lang.Character";
            case INT -> "java.lang.Integer";
            case LONG -> "java.lang.Long";
            case FLOAT -> "java.lang.Float";
            case DOUBLE -> "java.lang.Double";
        });
    }

    /** ボックス型ならプリミティブ型、それ以外はそのまま。 */
    private static JType unboxedType(JType t) {
        if (t instanceof JType.ClassType c) {
            return switch (c.qualifiedName()) {
                case "java.lang.Integer" -> JType.INT;
                case "java.lang.Long" -> JType.LONG;
                case "java.lang.Short" -> JType.SHORT;
                case "java.lang.Byte" -> JType.BYTE;
                case "java.lang.Character" -> JType.CHAR;
                case "java.lang.Boolean" -> JType.BOOLEAN;
                case "java.lang.Double" -> JType.DOUBLE;
                case "java.lang.Float" -> JType.FLOAT;
                default -> t;
            };
        }
        return t;
    }

    // ================================================================== 型・位置・診断

    private JType typeOf(TreePath path) {
        TypeMirror tm = trees.getTypeMirror(path);
        return tm == null ? new JType.Unsupported("<unknown>") : type(tm);
    }

    /** javac の型を消去した型。 */
    private JType erasedTypeOf(TreePath path) {
        TypeMirror tm = trees.getTypeMirror(path);
        return tm == null ? new JType.Unsupported("<unknown>") : type(types.erasure(tm));
    }

    private String qualifiedName(TypeMirror t) {
        TypeMirror e = types.erasure(t);
        if (e instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
            return qualifiedNameOf(te);
        }
        return e.toString();
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
            case DECLARED -> new JType.ClassType(qualifiedNameOf(((TypeElement) ((DeclaredType) t).asElement())));
            case NULL -> new JType.NullType();
            // 型変数・ワイルドカード・交差型はジェネリクスの消去と同じく上限の型にする。
            case TYPEVAR, WILDCARD, INTERSECTION -> {
                TypeMirror er = types.erasure(t);
                yield er.getKind() == t.getKind() ? JType.OBJECT : type(er);
            }
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
        if (t == null) {
            return new SourcePos(file, 0, 0);
        }
        long start = trees.getSourcePositions().getStartPosition(cu, t);
        if (start < 0) {
            return new SourcePos(file, 0, 0);
        }
        return new SourcePos(file, cu.getLineMap().getLineNumber(start), cu.getLineMap().getColumnNumber(start));
    }

    private void report(DiagnosticCode code, Tree t, String message) {
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
