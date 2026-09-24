package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.ClassHierarchy;
import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.MethodRef;
import io.github.ykwyuta.j2r.jir.Stmt;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JIR → RIR の変換のうち、クラスごとのファイル構成を担う（表現戦略 S0 = faithful モード）。
 * 規則は docs/03-translation-rules.md §3。1 つの Java の型から次を生成する:
 * <ul>
 *   <li>構造体（スーパークラスを {@code __super} として埋め込む。インタフェースは名前空間用の unit 構造体）</li>
 *   <li>{@code impl}: {@code of}（JObject → 構造体）、コンストラクタ（{@code new} / {@code __init}）、static メソッド、
 *       インスタンスメソッドの本体（{@code this: &JObject}）、仮想呼び出しの振り分け関数</li>
 *   <li>{@code impl jrt::Object}: クラス名・instanceof・toString / equals / hashCode / compareTo の上書き・
 *       JDK のインタフェースのメソッドを名前で呼ぶための {@code invoke}</li>
 *   <li>static フィールド（const / thread_local）、static 初期化ブロック（{@code __clinit}）、enum 定数</li>
 * </ul>
 */
public final class Lowerer {
    private final ProgramIndex index;
    private final ClassHierarchy hierarchy;
    private final ApiMappings mappings;
    private final Diagnostics diags;
    private LowerContext cx;

    public Lowerer(ProgramIndex index, ClassHierarchy hierarchy, ApiMappings mappings, Diagnostics diags) {
        this.index = index;
        this.hierarchy = hierarchy;
        this.mappings = mappings;
        this.diags = diags;
    }

    /**
     * プログラム全体を変換する。例外を送出しうるメソッドの集合（{@link Throwing}）は、変換の結果から求める:
     * JResult を返さない関数が例外を伝えようとしていたら、そのメソッドの系列を集合に加えて変換し直す（集合は
     * 単調に増えるので、呼び出しの深さ程度の回数で止まる）。診断は最後の回のものだけを報告する。
     */
    public LoweredCrate lower(Decl.Program program, String mainClass) {
        Set<String> throwing = new java.util.HashSet<>();
        for (int round = 0; ; round++) {
            Diagnostics local = new Diagnostics();
            cx = new LowerContext(index, hierarchy, mappings, new TypeMapper(mappings), local, new Throwing(index, throwing));
            LoweredCrate crate = lowerOnce(program, mainClass);
            Set<String> required = cx.throwing().required();
            if (required.isEmpty() || round > 10_000) {
                local.all().forEach(diags::add);
                return crate;
            }
            throwing.addAll(required);
        }
    }

    private LoweredCrate lowerOnce(Decl.Program program, String mainClass) {
        List<RFile> files = new ArrayList<>();
        List<List<String>> modules = new ArrayList<>();
        for (ProgramIndex.TypeInfo ti : cx.index().types()) {
            files.add(new TypeLowering(ti, program).file());
            modules.add(ti.modulePath());
        }
        ProgramIndex.TypeInfo main = null;
        if (mainClass != null) {
            main = cx.index().type(mainClass);
            if (main == null || main.decl().methods().stream().noneMatch(Decl.MethodDecl::isMain)) {
                cx.diags().report(DiagnosticCode.NO_MAIN, SourcePos.UNKNOWN, "main class " + mainClass + " has no 'public static void main(String[])'");
                main = null;
            }
        } else {
            List<ProgramIndex.TypeInfo> mains = cx.index().mainTypes();
            if (mains.size() == 1) {
                main = mains.get(0);
            } else if (mains.size() > 1) {
                cx.diags().report(DiagnosticCode.NO_MAIN, SourcePos.UNKNOWN, "several classes have a main method; specify one with --main-class"
                        + " (candidates: " + mains.stream().map(t -> t.decl().qualifiedName()).toList() + ")");
            } else {
                cx.diags().report(DiagnosticCode.NO_MAIN, SourcePos.UNKNOWN, "no main method found; generating a library crate only");
            }
        }
        List<String> mainPath = null;
        if (main != null) {
            mainPath = new ArrayList<>(main.modulePath());
            mainPath.add(main.structName());
        }
        return new LoweredCrate(files, modules, mainPath);
    }

    /** 型 ti（またはスーパークラス）が JDK のクラスを継承しているなら、委譲先（OnceCell）までのフィールドのパス。 */
    static String jdkPath(ProgramIndex index, ProgramIndex.TypeInfo ti) {
        StringBuilder sb = new StringBuilder();
        ProgramIndex.TypeInfo cur = ti;
        while (cur != null) {
            if (cur.decl().jdkSuperclass() != null) {
                return sb + "__jdk";
            }
            String sup = cur.decl().superclass();
            if (sup == null || !index.isProgramType(sup)) {
                return null;
            }
            sb.append("__super.");
            cur = index.type(sup);
        }
        return null;
    }

    /**
     * クラスの初期化（{@code __clinit}）が必要か: static 初期化があるか、スーパークラスが初期化を必要とする
     * （Java はクラスの初期化の前にスーパークラスを初期化する）。
     */
    static boolean needsClinit(ProgramIndex index, ProgramIndex.TypeInfo t) {
        for (ProgramIndex.TypeInfo cur = t; cur != null; cur = cur.decl().superclass() == null ? null : index.type(cur.decl().superclass())) {
            if (cur.decl().staticInit() != null) {
                return true;
            }
        }
        return false;
    }

    /** クラスの初期化の、例外を送出しうるかの判定（{@link Throwing}）に使うキー。 */
    static String clinitKey(Decl.TypeDecl t) {
        return t.qualifiedName() + "#<clinit>()";
    }

    /** Java の binary name（{@code getClass().getName()} の値。例: {@code pkg.Outer$Inner}）。 */
    static String binaryName(ProgramIndex index, Decl.TypeDecl t) {
        if (t.qualifiedName().contains("$")) {
            return t.qualifiedName();
        }
        StringBuilder sb = new StringBuilder(t.simpleName());
        Decl.TypeDecl cur = t;
        while (cur.outer() != null && index.type(cur.outer()) != null) {
            cur = index.type(cur.outer()).decl();
            sb.insert(0, cur.simpleName() + "$");
        }
        return cur.packageName().isEmpty() ? sb.toString() : cur.packageName() + "." + sb;
    }

    private static List<String> docLines(String javadoc) {
        if (javadoc == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : javadoc.strip().split("\n")) {
            out.add(line.strip());
        }
        return out;
    }

    private static String fileName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static RExpr call(String path, RExpr... args) {
        return new RExpr.Call(new RExpr.Path(path), List.of(args));
    }

    private static RExpr path(String p) {
        return new RExpr.Path(p);
    }

    private static RExpr.Block block(List<RStmt> stmts, RExpr tail) {
        return new RExpr.Block(stmts, tail, null, false);
    }

    private static RExpr lit(String s) {
        return new RExpr.Lit(s);
    }

    private static RExpr str(String s) {
        return new RExpr.Lit("\"" + s + "\"");
    }

    /** 1 つの型を 1 つのファイルにする。 */
    private final class TypeLowering {
        private final ProgramIndex.TypeInfo ti;
        private final Decl.TypeDecl t;
        private final Decl.Program program;
        private final Imports imp;
        private final String self;

        TypeLowering(ProgramIndex.TypeInfo ti, Decl.Program program) {
            this.ti = ti;
            this.t = ti.decl();
            this.program = program;
            this.imp = new Imports(ti);
            this.self = ti.structName();
        }

        private boolean hasClinit() {
            return needsClinit(cx.index(), ti);
        }

        private boolean clinitThrowing() {
            return hasClinit() && cx.throwing().isThrowing(clinitKey(t));
        }

        /** スーパークラスが JDK の例外クラス（ユーザー定義の例外クラスのルート）か。 */
        private boolean jdkThrowableRoot() {
            return t.superclass() != null && !cx.index().isProgramType(t.superclass());
        }

        private ProgramIndex.TypeInfo superType() {
            return t.superclass() == null ? null : cx.index().type(t.superclass());
        }

        /** enum か、enum を継承したクラス（本体付きの enum 定数）か（生成時に名前と序数を受け取る）。 */
        private boolean enumLike() {
            ProgramIndex.TypeInfo cur = ti;
            while (cur != null) {
                if (cur.decl().kind() == Decl.TypeKind.ENUM) {
                    return true;
                }
                cur = cur.decl().superclass() == null ? null : cx.index().type(cur.decl().superclass());
            }
            return false;
        }

        RFile file() {
            String source = program.units().stream().filter(u -> u.types().contains(t)).map(Decl.CompilationUnit::sourcePath)
                    .findFirst().orElse("?");
            List<String> header = new ArrayList<>();
            header.add("Translated from `" + t.qualifiedName() + "` (" + fileName(source) + ") by Java2RustTranslator.");
            if (t.javadoc() != null) {
                header.add("");
                header.addAll(docLines(t.javadoc()));
            }
            List<RItem> body = new ArrayList<>();
            body.addAll(statics());
            if (t.kind() == Decl.TypeKind.ENUM) {
                body.add(enumValuesStorage());
            }
            body.add(struct());
            body.add(new RItem.Impl(List.of(), self, implItems()));
            if (t.isConcrete()) {
                body.add(objectImpl());
            }
            List<RItem> items = new ArrayList<>();
            items.add(new RItem.Use(List.of("allow(unused_imports)"), "jrt::prelude::*"));
            items.addAll(imp.useItems());
            items.addAll(body);
            // raw identifier のモジュール（mod r#box;）のファイル名は box.rs。
            List<String> segs = ti.modulePath().stream().map(x -> x.startsWith("r#") ? x.substring(2) : x).toList();
            return new RFile(String.join("/", segs) + ".rs", header, List.of(), items);
        }

        // ------------------------------------------------------------ static フィールド

        private List<RItem> statics() {
            List<RItem> out = new ArrayList<>();
            for (Decl.FieldDecl f : t.fields()) {
                if (!f.isStatic()) {
                    continue;
                }
                ProgramIndex.FieldInfo info = cx.index().field(t.qualifiedName(), f.name());
                List<String> docs = docLines(f.javadoc());
                RType rt = cx.types().rust(f.type());
                if (info.storage() == ProgramIndex.FieldStorage.CONST) {
                    RExpr value = JType.isString(f.type())
                            ? new RExpr.Macro("jstr", List.of(lit(RustLiterals.stringLiteral((String) f.constantValue(), new boolean[1]))))
                            : RustLiterals.number(f.constantValue(), f.type(), false);
                    out.add(new RItem.Const(docs, f.isPublic(), info.rustName(), rt, value));
                    continue;
                }
                RExpr init;
                if (f.init() != null && t.staticInit() == null) {
                    FnLowerer fl = staticLowerer();
                    init = fl.value(f.init());
                    if (fl.fallible()) {
                        init = staticInit(init, rt);
                    }
                } else {
                    init = cx.types().defaultValue(f.type());
                }
                boolean copy = TypeMapper.isCopy(f.type());
                String cell = copy ? "std::cell::Cell" : "std::cell::RefCell";
                out.add(new RItem.ThreadLocal(docs, f.isPublic(), info.rustName(), new RType(cell + "<" + rt.text() + ">"),
                        call(cell + "::new", init)));
            }
            if (hasClinit()) {
                // 初期化の状態（例外を送出しうる初期化では 0: 未初期化、1: 初期化中・済み、2: 失敗）。
                out.add(new RItem.ThreadLocal(List.of(), false, "__CLINIT", new RType(clinitThrowing() ? "std::cell::Cell<u8>" : "std::cell::Cell<bool>"),
                        call("std::cell::Cell::new", lit(clinitThrowing() ? "0" : "false"))));
            }
            return out;
        }

        private FnLowerer staticLowerer() {
            return new FnLowerer(cx, ti, imp, JType.VOID, false, List.of(), null, false);
        }

        /**
         * 例外を送出しうる static 初期化: {@code jrt::rt::static_init(|| -> JResult<T> { Ok(value) })}
         * （static フィールドは最初に使われたときに初期化するので、例外は呼び出し元へ伝えられない）。
         */
        private RExpr staticInit(RExpr value, RType type) {
            return call("jrt::rt::static_init", new RExpr.Closure(false, List.of(), new RType("JResult<" + type.text() + ">"),
                    block(List.of(), Conversions.ok(value))));
        }

        // ------------------------------------------------------------ 構造体

        private String basePath() {
            StringBuilder sb = new StringBuilder();
            ProgramIndex.TypeInfo cur = ti;
            while (cur != null && cur.decl().superclass() != null && cx.index().isProgramType(cur.decl().superclass())) {
                sb.append("__super.");
                cur = cx.index().type(cur.decl().superclass());
            }
            return sb + "__base";
        }

        /** JDK のクラス（Thread・コレクション）を継承しているなら、委譲先のオブジェクトまでのパス（なければ null）。 */
        private String jdkPath() {
            return Lowerer.jdkPath(cx.index(), ti);
        }

        /** 例外クラスなら Throwable の状態までのパス（なければ null）。 */
        private String throwablePath() {
            StringBuilder sb = new StringBuilder();
            ProgramIndex.TypeInfo cur = ti;
            while (cur != null) {
                String sup = cur.decl().superclass();
                if (sup == null) {
                    return null;
                }
                if (!cx.index().isProgramType(sup)) {
                    return sb + "__throwable";
                }
                sb.append("__super.");
                cur = cx.index().type(sup);
            }
            return null;
        }

        private RItem struct() {
            List<String> docs = docLines(t.javadoc());
            if (t.isInterface()) {
                return new RItem.Struct(docs, self, List.of());
            }
            List<RItem.Field> fields = new ArrayList<>();
            ProgramIndex.TypeInfo sup = superType();
            if (sup != null) {
                fields.add(new RItem.Field("pub(crate)", "__super", new RType(imp.type(sup))));
            } else {
                fields.add(new RItem.Field("pub(crate)", "__base", new RType("jrt::ObjectBase")));
                if (jdkThrowableRoot()) {
                    fields.add(new RItem.Field("pub(crate)", "__throwable", new RType("jrt::lang::throwable::Throwable")));
                }
                if (t.kind() == Decl.TypeKind.ENUM) {
                    fields.add(new RItem.Field("pub(crate)", "__enum", new RType("jrt::lang::enums::EnumBase")));
                }
                if (t.jdkSuperclass() != null) {
                    fields.add(new RItem.Field("pub(crate)", "__jdk", new RType("std::cell::OnceCell<JObject>")));
                }
            }
            if (t.hasOuterInstance()) {
                fields.add(new RItem.Field("pub(crate)", "__outer", new RType("std::cell::RefCell<JObject>")));
            }
            for (ProgramIndex.FieldInfo f : cx.index().instanceFields(t.qualifiedName())) {
                fields.add(new RItem.Field("pub(crate)", f.rustName(), cellType(f.type())));
            }
            return new RItem.Struct(docs, self, fields);
        }

        private RType cellType(JType type) {
            String inner = cx.types().text(type);
            return new RType((TypeMapper.isCopy(type) ? "std::cell::Cell<" : "std::cell::RefCell<") + inner + ">");
        }

        private RExpr cellNew(JType type, RExpr value) {
            return call(TypeMapper.isCopy(type) ? "std::cell::Cell::new" : "std::cell::RefCell::new", value);
        }

        // ------------------------------------------------------------ impl

        private List<RItem> implItems() {
            List<RItem> items = new ArrayList<>();
            if (!t.isInterface()) {
                items.add(ofFn());
                items.add(allocFn());
            }
            if (hasClinit()) {
                items.add(clinitFn());
            }
            if (t.kind() == Decl.TypeKind.ENUM) {
                items.addAll(enumFns());
            }
            List<Decl.MethodDecl> ctors = t.methods().stream().filter(Decl.MethodDecl::isConstructor).toList();
            if (!t.isInterface() && ctors.isEmpty()) {
                ctors = List.of(defaultConstructor());
            }
            for (Decl.MethodDecl m : ctors) {
                items.addAll(constructor(m));
            }
            for (Decl.MethodDecl m : t.methods()) {
                if (m.isConstructor()) {
                    continue;
                }
                ProgramIndex.MethodInfo mi = new ProgramIndex.MethodInfo(m, ti);
                if (m.isStatic()) {
                    items.add(staticMethod(m));
                    continue;
                }
                if (cx.hierarchy().needsDispatch(mi)) {
                    items.add(dispatcher(mi));
                }
                if (m.body() != null) {
                    items.add(instanceMethod(mi));
                }
            }
            return items;
        }

        private Decl.MethodDecl defaultConstructor() {
            MethodRef ref = new MethodRef(t.qualifiedName(), "<init>", List.of(), JType.VOID, false);
            return new Decl.MethodDecl(ref, Decl.MethodKind.CONSTRUCTOR, List.of(), new Stmt.Block(List.of(), t.pos()), true, false,
                    false, List.of(), "new", null, t.pos());
        }

        /** {@code pub fn of(this: &JObject) -> &S}: 実行時の型から、この型の部分を取り出す。 */
        private RItem ofFn() {
            List<RStmt> stmts = new ArrayList<>();
            for (ProgramIndex.TypeInfo sub : cx.hierarchy().concreteSubtypes(t.qualifiedName())) {
                StringBuilder p = new StringBuilder("x");
                ProgramIndex.TypeInfo cur = sub;
                while (cur != ti && cur != null) {
                    p.append(".__super");
                    cur = cur.decl().superclass() == null ? null : cx.index().type(cur.decl().superclass());
                }
                RExpr result = p.toString().equals("x") ? path("x") : new RExpr.Unary("&", path(p.toString()));
                stmts.add(new RStmt.ExprStmt(new RExpr.IfLet("Some(x)",
                        new RExpr.MethodCall(path("this"), "downcast_ref::<" + imp.type(sub) + ">", List.of()),
                        RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(result), true))), null), false));
            }
            RExpr tail = call("jrt::object::bad_cast", path("this"), str(t.qualifiedName()));
            return new RItem.Fn(List.of("この型（またはサブクラス）のオブジェクトから、" + t.simpleName() + " の部分を取り出す。"), List.of(),
                    "pub", "of", List.of(new RItem.Param("this", false, new RType("&JObject"))), new RType("&" + self), block(stmts, tail));
        }

        /** {@code __alloc(base) -> S}: フィールドを既定値にした構造体を作る（コンストラクタの本体を実行する前の状態）。 */
        private RItem allocFn() {
            List<RItem.Param> params = new ArrayList<>();
            params.add(new RItem.Param("base", false, new RType("jrt::ObjectBase")));
            List<RExpr.FieldInit> inits = new ArrayList<>();
            ProgramIndex.TypeInfo sup = superType();
            if (enumLike()) {
                params.add(new RItem.Param("name", false, new RType("JString")));
                params.add(new RItem.Param("ordinal", false, new RType("i32")));
            }
            if (sup != null) {
                inits.add(new RExpr.FieldInit("__super", enumLike()
                        ? call(imp.type(sup) + "::__alloc", path("base"), path("name"), path("ordinal"))
                        : call(imp.type(sup) + "::__alloc", path("base"))));
            } else {
                inits.add(new RExpr.FieldInit("__base", path("base")));
                if (jdkThrowableRoot()) {
                    inits.add(new RExpr.FieldInit("__throwable", call("jrt::lang::throwable::Throwable::new_part")));
                }
                if (t.kind() == Decl.TypeKind.ENUM) {
                    inits.add(new RExpr.FieldInit("__enum", call("jrt::lang::enums::EnumBase::new", path("name"), path("ordinal"), str(binaryName()), path(self + "::__values"))));
                }
                if (t.jdkSuperclass() != null) {
                    inits.add(new RExpr.FieldInit("__jdk", call("std::cell::OnceCell::new")));
                }
            }
            if (t.hasOuterInstance()) {
                inits.add(new RExpr.FieldInit("__outer", call("std::cell::RefCell::new", call("JObject::null"))));
            }
            for (ProgramIndex.FieldInfo f : cx.index().instanceFields(t.qualifiedName())) {
                inits.add(new RExpr.FieldInit(f.rustName(), cellNew(f.type(), cx.types().defaultValue(f.type()))));
            }
            return new RItem.Fn(List.of(), List.of(), "pub(crate)", "__alloc", params, new RType(self),
                    block(List.of(), new RExpr.StructLit(self, inits)));
        }

        /**
         * クラスの初期化 {@code __clinit}: スーパークラスの初期化、enum 定数の生成、static フィールドの初期化子と
         * static 初期化ブロック（テキストの順）を、最初の 1 回だけ実行する。例外を送出しうる初期化は JResult を返し、
         * 失敗すると ExceptionInInitializerError を送出する（2 回目以降は NoClassDefFoundError）。
         */
        private RItem clinitFn() {
            boolean throwing = clinitThrowing();
            List<Stmt> body = t.staticInit() == null ? List.of() : t.staticInit();
            FnLowerer fl = new FnLowerer(cx, ti, imp, JType.VOID, false, List.of(), new Stmt.Block(body, t.pos()), false);
            List<RStmt> init = new ArrayList<>();
            boolean fallible = false;
            ProgramIndex.TypeInfo sup = superType();
            if (sup != null && needsClinit(cx.index(), sup)) {
                RExpr superInit = call(imp.type(sup) + "::__clinit");
                if (cx.throwing().isThrowing(clinitKey(sup.decl()))) {
                    superInit = new RExpr.Try(superInit);
                    fallible = true;
                }
                init.add(new RStmt.ExprStmt(superInit, true));
            }
            if (t.kind() == Decl.TypeKind.ENUM) {
                init.add(new RStmt.ExprStmt(call(self + "::__values"), true));
            }
            init.addAll(fl.body(body, false).stmts());
            fallible |= fl.fallible();
            if (fallible && !throwing) {
                cx.throwing().require(clinitKey(t));
            }
            List<String> docs = List.of("クラスの初期化（static フィールドの初期化子と static 初期化ブロック）。最初の 1 回だけ実行する。");
            List<RStmt> stmts = new ArrayList<>();
            if (!throwing) {
                RExpr done = new RExpr.MethodCall(path("__CLINIT"), "with", List.of(RExpr.Closure.of(List.of("c"),
                        new RExpr.MethodCall(path("c"), "replace", List.of(lit("true"))))));
                stmts.add(new RStmt.ExprStmt(new RExpr.If(done, RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(null), true))), null), false));
                stmts.addAll(init);
                return new RItem.Fn(docs, List.of(), "pub", "__clinit", List.of(), null, block(stmts, null));
            }
            RExpr state = new RExpr.MethodCall(path("__CLINIT"), "with", List.of(RExpr.Closure.of(List.of("c"),
                    new RExpr.MethodCall(path("c"), "get", List.of()))));
            stmts.add(new RStmt.Let("state", false, null, state));
            stmts.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.Binary("==", path("state"), lit("1")),
                    RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(Conversions.ok(lit("()"))), true))), null), false));
            stmts.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.Binary("==", path("state"), lit("2")),
                    RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(call("jrt::rt::no_class_def_found", str(binaryName()))), true))), null), false));
            stmts.add(new RStmt.ExprStmt(setState("1"), true));
            RExpr run = new RExpr.Call(new RExpr.Closure(false, List.of(), new RType("JResult<()>"), block(init, Conversions.ok(lit("()")))), List.of());
            stmts.add(new RStmt.ExprStmt(new RExpr.IfLet("Err(e)", run, RExpr.Block.of(List.of(
                    new RStmt.ExprStmt(setState("2"), true),
                    new RStmt.ExprStmt(new RExpr.Return(new RExpr.Call(path("Err"), List.of(call("jrt::rt::initializer_error", path("e"))))), true))), null), false));
            return new RItem.Fn(docs, List.of(), "pub", "__clinit", List.of(), new RType("JResult<()>"), block(stmts, Conversions.ok(lit("()"))));
        }

        private RExpr setState(String v) {
            return new RExpr.MethodCall(path("__CLINIT"), "with", List.of(RExpr.Closure.of(List.of("c"),
                    new RExpr.MethodCall(path("c"), "set", List.of(lit(v))))));
        }

        /** 自分のクラスの初期化の呼び出し（例外を送出しうるなら ?。呼ぶ関数 key も JResult を返す必要がある）。 */
        private RExpr clinitStmt(String callerKey, boolean callerThrowing) {
            if (clinitThrowing()) {
                if (!callerThrowing) {
                    cx.throwing().require(callerKey);
                }
                return new RExpr.Try(call(self + "::__clinit"));
            }
            return call(self + "::__clinit");
        }

        // ------------------------------------------------------------ コンストラクタ

        private List<RItem> constructor(Decl.MethodDecl m) {
            List<RItem> out = new ArrayList<>();
            String initName = FnLowerer.initName(m.rustName());
            List<Stmt> stmts = new ArrayList<>(m.body().stmts());
            Stmt first = stmts.isEmpty() ? null : stmts.get(0);
            boolean delegates = first instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.CtorCall cc && !cc.isSuper();
            if (!delegates) {
                // 捕捉した変数の代入（__cap_*）と super(...) の後ろに入れる。
                int at = 0;
                while (at < stmts.size() && stmts.get(at) instanceof Stmt.ExprStmt es
                        && (es.expr() instanceof Expr.CtorCall
                            || es.expr() instanceof Expr.Assign a && a.target() instanceof Expr.FieldAccess fa && fa.name().startsWith("__cap_"))) {
                    boolean isCtor = es.expr() instanceof Expr.CtorCall;
                    at++;
                    if (isCtor) {
                        break;
                    }
                }
                // super(...) の直後にフィールドの初期化子とインスタンス初期化ブロックを実行する。
                stmts.addAll(at, t.instanceInit());
            }
            Stmt.Block body = new Stmt.Block(stmts, m.pos());
            boolean throwing = cx.throwing().isThrowing(m);
            FnLowerer fl = new FnLowerer(cx, ti, imp, JType.VOID, false, m.params(), body, throwing);
            List<RItem.Param> initParams = new ArrayList<>();
            initParams.add(new RItem.Param("this", false, new RType("&JObject")));
            List<RExpr> initArgs = new ArrayList<>();
            initArgs.add(new RExpr.Unary("&", path("this")));
            List<RItem.Param> newParams = new ArrayList<>();
            List<RStmt> prologue = new ArrayList<>();
            if (t.hasOuterInstance()) {
                initParams.add(new RItem.Param("__outer", false, new RType("JObject")));
                newParams.add(new RItem.Param("__outer", false, new RType("JObject")));
                initArgs.add(path("__outer"));
                prologue.add(new RStmt.ExprStmt(new RExpr.Assign(new RExpr.Unary("*", new RExpr.MethodCall(
                        new RExpr.Field(call(self + "::of", path("this")), "__outer"), "borrow_mut", List.of())), "=", path("__outer")), true));
            }
            for (Decl.Param p : m.params()) {
                String n = Naming.valueName(p.name());
                initParams.add(new RItem.Param(n, fl.isMutable(p.name()), cx.types().rust(p.type())));
                newParams.add(new RItem.Param(n, false, cx.types().rust(p.type())));
                initArgs.add(path(n));
            }
            RExpr.Block lowered = fl.body(stmts, false);
            requireIfFallible(fl, throwing, m.ref().key());
            List<RStmt> initBody = new ArrayList<>(prologue);
            initBody.addAll(lowered.stmts());
            out.add(new RItem.Fn(List.of(), List.of(), "pub(crate)", initName, initParams, throwing ? new RType("JResult<()>") : null,
                    block(initBody, lowered.tail())));
            RExpr initCall = new RExpr.Call(path(self + "::" + initName), initArgs);
            if (throwing) {
                initCall = new RExpr.Try(initCall);
            }
            RType objType = new RType(throwing ? "JResult<JObject>" : "JObject");
            RExpr result = throwing ? Conversions.ok(path("this")) : path("this");

            if (enumLike() && !t.isAbstract()) {
                List<RItem.Param> ps = new ArrayList<>();
                ps.add(new RItem.Param("name", false, new RType("JString")));
                ps.add(new RItem.Param("ordinal", false, new RType("i32")));
                ps.addAll(newParams);
                List<RStmt> s = new ArrayList<>();
                s.add(new RStmt.Let("this", false, null, call("jrt::alloc", RExpr.Closure.of(List.of("base"),
                        call(self + "::__alloc", path("base"), path("name"), path("ordinal"))))));
                s.add(new RStmt.ExprStmt(initCall, true));
                out.add(0, new RItem.Fn(List.of(), List.of(), "pub(crate)", "__create" + m.rustName().substring("new".length()), ps,
                        objType, block(s, result)));
                return out;
            }
            if (t.isAbstract()) {
                return out;
            }
            List<RStmt> s = new ArrayList<>();
            if (hasClinit()) {
                s.add(new RStmt.ExprStmt(clinitStmt(m.ref().key(), throwing), true));
            }
            s.add(new RStmt.Let("this", false, null, call("jrt::alloc", RExpr.Closure.of(List.of("base"), call(self + "::__alloc", path("base"))))));
            s.add(new RStmt.ExprStmt(initCall, true));
            out.add(0, new RItem.Fn(docLines(m.javadoc()), List.of(), m.isPrivate() ? "pub(crate)" : "pub", m.rustName(), newParams,
                    objType, block(s, result)));
            return out;
        }

        // ------------------------------------------------------------ メソッド

        private List<RItem.Param> params(FnLowerer fl, Decl.MethodDecl m, boolean withThis) {
            List<RItem.Param> ps = new ArrayList<>();
            if (withThis) {
                ps.add(new RItem.Param("this", false, new RType("&JObject")));
            }
            for (Decl.Param p : m.params()) {
                ps.add(new RItem.Param(Naming.valueName(p.name()), fl != null && fl.isMutable(p.name()), cx.types().rust(p.type())));
            }
            return ps;
        }

        /** Rust の戻り値型（例外を送出しうるメソッドは {@code JResult<T>}）。 */
        private RType returnType(Decl.MethodDecl m) {
            boolean isVoid = m.ref().returnType() instanceof JType.Void;
            if (cx.throwing().isThrowing(m)) {
                return new RType("JResult<" + (isVoid ? "()" : cx.types().rust(m.ref().returnType()).text()) + ">");
            }
            return isVoid ? null : cx.types().rust(m.ref().returnType());
        }

        /** JResult を返さない関数が例外を伝えようとしていたら、そのメソッドの系列を JResult にする（次の回から）。 */
        private void requireIfFallible(FnLowerer fl, boolean throwing, String key) {
            if (fl.fallible() && !throwing) {
                cx.throwing().require(key);
            }
        }

        private RItem staticMethod(Decl.MethodDecl m) {
            boolean throwing = cx.throwing().isThrowing(m);
            FnLowerer fl = new FnLowerer(cx, ti, imp, m.ref().returnType(), false, m.params(), m.body(), throwing);
            RExpr.Block b = fl.body(m.body().stmts(), true);
            requireIfFallible(fl, throwing, m.ref().key());
            if (hasClinit()) {
                List<RStmt> stmts = new ArrayList<>();
                stmts.add(new RStmt.ExprStmt(clinitStmt(m.ref().key(), throwing), true));
                stmts.addAll(b.stmts());
                b = new RExpr.Block(stmts, b.tail(), null, false);
            }
            return new RItem.Fn(docLines(m.javadoc()), List.of(), m.isPrivate() ? "" : "pub", m.rustName(), params(fl, m, false),
                    returnType(m), b);
        }

        private RItem instanceMethod(ProgramIndex.MethodInfo mi) {
            Decl.MethodDecl m = mi.decl();
            boolean throwing = cx.throwing().isThrowing(m);
            FnLowerer fl = new FnLowerer(cx, ti, imp, m.ref().returnType(), false, m.params(), m.body(), throwing);
            RExpr.Block b = fl.body(m.body().stmts(), true);
            requireIfFallible(fl, throwing, m.ref().key());
            return new RItem.Fn(docLines(m.javadoc()), List.of(), m.isPrivate() ? "" : "pub", cx.hierarchy().bodyName(mi),
                    params(fl, m, true), returnType(m), b);
        }

        /**
         * 仮想呼び出しの振り分け関数: 実行時の型ごとに、実際に呼ばれる本体を呼ぶ。
         * ラムダが実装しうる関数型インタフェースなら、最後にラムダを名前で呼ぶ。
         */
        private RItem dispatcher(ProgramIndex.MethodInfo mi) {
            Decl.MethodDecl m = mi.decl();
            boolean[] fallible = {false};
            Conversions conv = new Conversions(cx, imp, () -> fallible[0] = true);
            boolean throwing = cx.throwing().isThrowing(m);
            Map<ProgramIndex.MethodInfo, List<ProgramIndex.TypeInfo>> byImpl = new LinkedHashMap<>();
            List<ProgramIndex.TypeInfo> inherited = new ArrayList<>();
            for (ProgramIndex.TypeInfo s : cx.hierarchy().concreteSubtypes(t.qualifiedName())) {
                ProgramIndex.MethodInfo impl = cx.hierarchy().implementation(s, m.ref().key());
                if (impl == null) {
                    inherited.add(s);
                } else {
                    byImpl.computeIfAbsent(impl, k -> new ArrayList<>()).add(s);
                }
            }
            List<RExpr> args = new ArrayList<>();
            args.add(path("this"));
            for (Decl.Param p : m.params()) {
                args.add(path(Naming.valueName(p.name())));
            }
            boolean isVoid = m.ref().returnType() instanceof JType.Void;
            List<RStmt> stmts = new ArrayList<>();
            byImpl.forEach((impl, classes) -> {
                RExpr cond = typeTest(classes);
                List<RExpr> callArgs = new ArrayList<>(args);
                for (int i = 1; i < callArgs.size(); i++) {
                    Decl.Param p = m.params().get(i - 1);
                    if (!TypeMapper.isCopy(p.type())) {
                        callArgs.set(i, new RExpr.MethodCall(callArgs.get(i), "clone", List.of()));
                    }
                }
                RExpr c = conv.callImpl(impl, m.ref(), callArgs);
                stmts.add(new RStmt.ExprStmt(new RExpr.If(cond, returnBlock(c, isVoid, throwing), null), false));
            });
            if (!inherited.isEmpty()) {
                RExpr fallback = objectMethodDefault(m);
                if (fallback != null) {
                    stmts.add(new RStmt.ExprStmt(new RExpr.If(typeTest(inherited), returnBlock(conv.tryOp(fallback), isVoid, throwing), null), false));
                }
            }
            if (cx.hierarchy().lambdaMayImplement(t.qualifiedName()) && m.body() != null) {
                // ラムダはインタフェースの default メソッドを上書きしないので、インタフェースの実装を呼ぶ。
                List<RExpr> callArgs = new ArrayList<>(args);
                RExpr c = conv.callImpl(mi, m.ref(), callArgs);
                stmts.add(new RStmt.ExprStmt(new RExpr.If(new RExpr.MethodCall(path("this"), "is::<jrt::lambda::Lambda>", List.of()),
                        returnBlock(c, isVoid, throwing), null), false));
            } else if (cx.hierarchy().lambdaMayImplement(t.qualifiedName())) {
                List<RExpr> boxed = new ArrayList<>();
                for (Decl.Param p : m.params()) {
                    RExpr v = path(Naming.valueName(p.name()));
                    boxed.add(conv.toObject(TypeMapper.isCopy(p.type()) ? v : new RExpr.MethodCall(v, "clone", List.of()), p.type()));
                }
                RExpr inv = conv.tryOp(new RExpr.MethodCall(path("this"), "try_invoke", List.of(str(m.name()), new RExpr.Unary("&", new RExpr.Array(boxed)))));
                RExpr result = conv.fromObject(path("__r"), m.ref().returnType());
                stmts.add(new RStmt.ExprStmt(new RExpr.IfLet("Some(__r)", inv, returnBlock(result, isVoid, throwing), null), false));
            }
            if (fallible[0] && !throwing) {
                cx.throwing().require(m.ref().key());
            }
            RExpr tail = call("jrt::object::bad_cast", path("this"), str(t.qualifiedName()));
            return new RItem.Fn(List.of("`" + m.name() + "` の仮想呼び出し（実行時の型で呼び先を選ぶ）。"), List.of(), "pub", m.rustName(),
                    params(null, m, true), returnType(m), block(stmts, tail));
        }

        private RExpr typeTest(List<ProgramIndex.TypeInfo> classes) {
            RExpr cond = null;
            for (ProgramIndex.TypeInfo s : classes) {
                RExpr test = new RExpr.MethodCall(path("this"), "is::<" + imp.type(s) + ">", List.of());
                cond = cond == null ? test : new RExpr.Binary("||", cond, test);
            }
            return cond;
        }

        private RExpr.Block returnBlock(RExpr value, boolean isVoid, boolean throwing) {
            if (isVoid && !throwing) {
                return RExpr.Block.of(List.of(new RStmt.ExprStmt(value, true), new RStmt.ExprStmt(new RExpr.Return(null), true)));
            }
            if (isVoid) {
                // 戻り値のない（void の）本体の呼び出し: 結果の JResult<()> をそのまま返す。
                if (value instanceof RExpr.Try tr) {
                    return RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(tr.expr()), true)));
                }
                return RExpr.Block.of(List.of(new RStmt.ExprStmt(value, true), new RStmt.ExprStmt(new RExpr.Return(Conversions.ok(lit("()"))), true)));
            }
            return RExpr.Block.of(List.of(new RStmt.ExprStmt(new RExpr.Return(throwing ? Conversions.ok(value) : value), true)));
        }

        /** プログラムに本体がない（java.lang.Object から継承する）メソッドの既定の動作。 */
        private RExpr objectMethodDefault(Decl.MethodDecl m) {
            return switch (m.ref().signature()) {
                case "toString()" -> new RExpr.MethodCall(path("this"), "to_jstring", List.of());
                case "hashCode()" -> new RExpr.MethodCall(path("this"), "hash_code", List.of());
                case "equals(java.lang.Object)" -> new RExpr.MethodCall(path("this"), "equals",
                        List.of(new RExpr.Unary("&", path(Naming.valueName(m.params().get(0).name())))));
                default -> null;
            };
        }

        // ------------------------------------------------------------ enum

        private RItem enumValuesStorage() {
            FnLowerer fl = staticLowerer();
            boolean fallible = false;
            List<RExpr> creations = new ArrayList<>();
            int ordinal = 0;
            for (Decl.EnumConstant c : t.enumConstants()) {
                ProgramIndex.MethodInfo ctor = c.constructor() == null ? null : cx.index().method(c.constructor().key());
                // 本体付きの定数は、その匿名クラスの __create で作る。
                String create = (ctor == null ? self : imp.type(ctor.owner())) + "::__create"
                        + (ctor == null ? "" : ctor.decl().rustName().substring("new".length()));
                List<RExpr> args = new ArrayList<>();
                args.add(new RExpr.Macro("jstr", List.of(str(c.name()))));
                args.add(lit(Integer.toString(ordinal++)));
                for (Expr a : c.args()) {
                    args.add(fl.value(a));
                }
                RExpr creation = new RExpr.Call(path(create), args);
                if (ctor != null ? cx.throwing().isThrowing(ctor.decl()) : cx.throwing().isThrowing(t.qualifiedName() + "#<init>()")) {
                    creation = new RExpr.Try(creation);
                    fallible = true;
                }
                creations.add(creation);
            }
            RExpr init = creations.isEmpty() ? call("JArray::<JObject>::from_vec", path("Vec::new()"))
                    : call("JArray::from_vec", new RExpr.Macro("vec", creations));
            if (fallible || fl.fallible()) {
                init = staticInit(init, new RType("JArray<JObject>"));
            }
            return new RItem.ThreadLocal(List.of("enum 定数（宣言順）。"), false, "__VALUES", new RType("JArray<JObject>"), init);
        }

        private List<RItem> enumFns() {
            List<RItem> out = new ArrayList<>();
            out.add(new RItem.Fn(List.of(), List.of(), "pub(crate)", "__values", List.of(), new RType("JArray<JObject>"),
                    block(List.of(), new RExpr.MethodCall(path("__VALUES"), "with", List.of(RExpr.Closure.of(List.of("v"),
                            new RExpr.MethodCall(path("v"), "clone", List.of())))))));
            out.add(new RItem.Fn(List.of("`values()`: 定数の配列（呼ぶたびに新しい配列）。"), List.of(), "pub", "values", List.of(),
                    new RType("JArray<JObject>"), block(List.of(), new RExpr.MethodCall(call(self + "::__values"), "duplicate", List.of()))));
            out.add(new RItem.Fn(List.of("`valueOf(String)`。"), List.of(), "pub", "value_of",
                    List.of(new RItem.Param("name", false, new RType("JString"))), new RType("JResult<JObject>"),
                    block(List.of(), call("jrt::lang::enums::value_of", new RExpr.Unary("&", call(self + "::__values")),
                            str(t.qualifiedName()), new RExpr.Unary("&", path("name"))))));
            int ordinal = 0;
            for (Decl.EnumConstant c : t.enumConstants()) {
                ProgramIndex.FieldInfo fi = cx.index().field(t.qualifiedName(), c.name());
                out.add(new RItem.Fn(List.of(), List.of("allow(non_snake_case)"), "pub", fi.rustName(), List.of(), new RType("JObject"),
                        block(List.of(), new RExpr.MethodCall(call(self + "::__values"), "element", List.of(lit(Integer.toString(ordinal++)))))));
            }
            return out;
        }

        // ------------------------------------------------------------ Object トレイト

        private String binaryName() {
            return Lowerer.binaryName(cx.index(), t);
        }

        private RExpr thisObject() {
            return new RExpr.MethodCall(new RExpr.MethodCall(path("self"), "base", List.of()), "this", List.of());
        }

        /**
         * {@code impl jrt::Object}。toString / equals / hashCode / compareTo / invoke はユーザーのコードを実行するので
         * JResult を返す（上書きしたメソッドが JResult を返さなければ Ok で包む）。
         */
        private RItem objectImpl() {
            Conversions conv = new Conversions(cx, imp, () -> { });
            List<RItem> fns = new ArrayList<>();
            RType jstring = new RType("JResult<JString>");
            fns.add(fn("base", List.of(), new RType("&jrt::ObjectBase"), new RExpr.Unary("&", new RExpr.Field(path("self"), basePath()))));
            fns.add(fn("class_name", List.of(), new RType("&'static str"), str(binaryName())));
            Set<String> names = new LinkedHashSet<>();
            names.add(t.qualifiedName());
            names.addAll(t.allSupertypes());
            List<String> quoted = names.stream().map(n -> "\"" + n + "\"").toList();
            fns.add(fn("instance_of", List.of(new RItem.Param("class", false, new RType("&str"))), new RType("bool"),
                    new RExpr.Macro("matches", List.of(path("class"), lit(String.join(" | ", quoted))))));

            RItem.Param other = new RItem.Param("other", false, new RType("&JObject"));
            ProgramIndex.MethodInfo toString = cx.hierarchy().implementation(ti, "java.lang.Object#toString()");
            if (toString != null) {
                fns.add(fn("to_jstring", List.of(), jstring, Conversions.ok(conv.callImpl(toString, objectRef("toString", List.of(), JType.STRING),
                        List.of(new RExpr.Unary("&", thisObject()))))));
            } else if (t.kind() == Decl.TypeKind.RECORD) {
                fns.add(fn("to_jstring", List.of(), jstring, Conversions.ok(recordToString())));
            } else if (enumLike()) {
                fns.add(fn("to_jstring", List.of(), jstring, call("jrt::lang::enums::name", new RExpr.Unary("&", thisObject()))));
            } else if (jdkPath() != null) {
                fns.add(fn("to_jstring", List.of(), jstring, call("jrt::object::jdk_to_string", jdkCell())));
            } else if (throwablePath() != null) {
                fns.add(fn("to_jstring", List.of(), jstring, Conversions.ok(call("jrt::lang::throwable::throwable_to_string",
                        new RExpr.MethodCall(path("self"), "class_name", List.of()), new RExpr.Unary("&", new RExpr.Field(path("self"), throwablePath()))))));
            }
            ProgramIndex.MethodInfo equals = cx.hierarchy().implementation(ti, "java.lang.Object#equals(java.lang.Object)");
            if (equals != null) {
                fns.add(fn("equals", List.of(other), new RType("JResult<bool>"), Conversions.ok(conv.callImpl(equals,
                        objectRef("equals", List.of(JType.OBJECT), JType.BOOLEAN),
                        List.of(new RExpr.Unary("&", thisObject()), new RExpr.MethodCall(path("other"), "clone", List.of()))))));
            } else if (t.kind() == Decl.TypeKind.RECORD) {
                fns.add(fn("equals", List.of(other), new RType("JResult<bool>"), Conversions.ok(recordEquals())));
            } else if (jdkPath() != null) {
                fns.add(fn("equals", List.of(other), new RType("JResult<bool>"), call("jrt::object::jdk_equals", jdkCell(),
                        new RExpr.Unary("&", thisObject()), path("other"))));
            }
            ProgramIndex.MethodInfo hash = cx.hierarchy().implementation(ti, "java.lang.Object#hashCode()");
            if (hash != null) {
                fns.add(fn("hash_code", List.of(), new RType("JResult<i32>"), Conversions.ok(conv.callImpl(hash, objectRef("hashCode", List.of(), JType.INT),
                        List.of(new RExpr.Unary("&", thisObject()))))));
            } else if (t.kind() == Decl.TypeKind.RECORD) {
                fns.add(fn("hash_code", List.of(), new RType("JResult<i32>"), Conversions.ok(recordHash())));
            } else if (jdkPath() != null) {
                fns.add(fn("hash_code", List.of(), new RType("JResult<i32>"), call("jrt::object::jdk_hash_code", jdkCell(),
                        new RExpr.Unary("&", thisObject()))));
            }
            ProgramIndex.MethodInfo cmp = cx.hierarchy().implementation(ti, "java.lang.Comparable#compareTo(java.lang.Object)");
            if (cmp != null) {
                fns.add(fn("compare_to", List.of(other), new RType("JResult<i32>"), Conversions.ok(conv.callImpl(cmp,
                        new MethodRef("java.lang.Comparable", "compareTo", List.of(JType.OBJECT), JType.INT, false, true),
                        List.of(new RExpr.Unary("&", thisObject()), new RExpr.MethodCall(path("other"), "clone", List.of()))))));
            } else if (enumLike()) {
                fns.add(fn("compare_to", List.of(other), new RType("JResult<i32>"), call("jrt::lang::enums::compare",
                        new RExpr.Unary("&", thisObject()), path("other"))));
            }
            RItem invoke = invokeFn(conv);
            if (invoke != null) {
                fns.add(invoke);
            }
            if (throwablePath() != null) {
                fns.add(fn("as_throwable", List.of(), new RType("Option<&jrt::lang::throwable::Throwable>"),
                        call("Some", new RExpr.Unary("&", new RExpr.Field(path("self"), throwablePath())))));
            }
            if (jdkPath() != null) {
                fns.add(fn("delegate", List.of(), new RType("Option<&JObject>"), new RExpr.MethodCall(
                        new RExpr.Field(path("self"), jdkPath()), "get", List.of())));
            }
            if (enumLike()) {
                fns.add(fn("as_enum", List.of(), new RType("Option<&jrt::lang::enums::EnumBase>"),
                        call("Some", new RExpr.Unary("&", new RExpr.Field(path("self"), basePath().replace("__base", "__enum"))))));
            }
            return new RItem.Impl(List.of(), "jrt::Object for " + self, fns);
        }

        private RExpr jdkCell() {
            return new RExpr.Unary("&", new RExpr.Field(path("self"), jdkPath()));
        }

        private MethodRef objectRef(String name, List<JType> params, JType ret) {
            return new MethodRef("java.lang.Object", name, params, ret, false);
        }

        private RItem fn(String name, List<RItem.Param> params, RType ret, RExpr tail) {
            List<RItem.Param> ps = new ArrayList<>();
            ps.add(new RItem.Param("&self", false, null));
            ps.addAll(params);
            return new RItem.Fn(List.of(), List.of(), "", name, ps, ret, block(List.of(), tail));
        }

        /**
         * JDK のインタフェース（Comparator, Runnable, Iterator など）のメソッドを名前で呼ぶための {@code invoke}。
         * JDK のコレクションや関数合成から、ユーザーのクラスのメソッドを呼ぶときに使われる。
         */
        private RItem invokeFn(Conversions conv) {
            Map<String, String> keys = new LinkedHashMap<>();
            collectJdkInterfaceKeys(ti, keys);
            if (keys.isEmpty()) {
                return null;
            }
            List<RExpr.Arm> arms = new ArrayList<>();
            for (var e : keys.entrySet()) {
                String key = e.getValue();
                ProgramIndex.MethodInfo impl = cx.hierarchy().implementation(ti, key);
                if (impl == null) {
                    continue;
                }
                List<JType> ps = parseParams(key);
                List<RExpr> args = new ArrayList<>();
                args.add(new RExpr.Unary("&", path("this")));
                for (int i = 0; i < ps.size(); i++) {
                    args.add(conv.fromObject(new RExpr.MethodCall(new RExpr.Index(path("args"), i), "clone", List.of()), ps.get(i)));
                }
                JType ret = impl.decl().ref().returnType();
                MethodRef called = new MethodRef(key.substring(0, key.indexOf('#')), impl.decl().name(), ps, ret, false, true);
                RExpr result = conv.toObject(conv.callImpl(impl, called, args), ret);
                arms.add(new RExpr.Arm("(\"" + impl.decl().name() + "\", " + ps.size() + ")", call("Some", result)));
            }
            if (arms.isEmpty()) {
                return null;
            }
            arms.add(new RExpr.Arm("_", path("None")));
            List<RStmt> stmts = List.of(new RStmt.Let("this", false, null, thisObject()));
            RExpr match = Conversions.ok(new RExpr.Match(new RExpr.Template(List.of("(method, args.len())")), arms));
            List<RItem.Param> ps = new ArrayList<>();
            ps.add(new RItem.Param("&self", false, null));
            ps.add(new RItem.Param("method", false, new RType("&str")));
            ps.add(new RItem.Param("args", false, new RType("&[JObject]")));
            return new RItem.Fn(List.of(), List.of(), "", "invoke", ps, new RType("JResult<Option<JObject>>"), block(stmts, match));
        }

        /** 型 s とその上位型のメソッドがオーバーライドする、JDK のインタフェースのメソッドのキー（名前と引数の数ごと）。 */
        private void collectJdkInterfaceKeys(ProgramIndex.TypeInfo s, Map<String, String> out) {
            Set<String> seen = new LinkedHashSet<>();
            List<ProgramIndex.TypeInfo> chain = new ArrayList<>();
            ProgramIndex.TypeInfo cur = s;
            while (cur != null) {
                chain.add(cur);
                cur = cur.decl().superclass() == null ? null : cx.index().type(cur.decl().superclass());
            }
            for (String st : s.decl().allSupertypes()) {
                ProgramIndex.TypeInfo i = cx.index().type(st);
                if (i != null && i.decl().isInterface()) {
                    chain.add(i);
                }
            }
            for (ProgramIndex.TypeInfo c : chain) {
                for (Decl.MethodDecl m : c.decl().methods()) {
                    for (String k : m.overrides()) {
                        String owner = k.substring(0, k.indexOf('#'));
                        if (cx.index().isProgramType(owner) || owner.equals("java.lang.Object") || owner.equals("java.lang.Comparable")) {
                            continue;
                        }
                        String id = m.name() + "/" + m.params().size();
                        if (seen.add(id)) {
                            out.put(id, k);
                        }
                    }
                }
            }
        }

        private List<JType> parseParams(String key) {
            String sig = key.substring(key.indexOf('(') + 1, key.lastIndexOf(')'));
            List<JType> out = new ArrayList<>();
            if (sig.isEmpty()) {
                return out;
            }
            for (String p : sig.split(",")) {
                out.add(parseType(p.strip()));
            }
            return out;
        }

        private JType parseType(String s) {
            if (s.endsWith("[]")) {
                return new JType.ArrayType(parseType(s.substring(0, s.length() - 2)));
            }
            return switch (s) {
                case "boolean" -> JType.BOOLEAN;
                case "byte" -> JType.BYTE;
                case "short" -> JType.SHORT;
                case "char" -> JType.CHAR;
                case "int" -> JType.INT;
                case "long" -> JType.LONG;
                case "float" -> JType.FLOAT;
                case "double" -> JType.DOUBLE;
                default -> new JType.ClassType(s);
            };
        }

        // ------------------------------------------------------------ record の equals / hashCode / toString

        private RExpr componentRead(Decl.Param c, String receiver) {
            ProgramIndex.FieldInfo fi = cx.index().field(t.qualifiedName(), c.name());
            RExpr cell = new RExpr.Field(path(receiver), fi.rustName());
            return TypeMapper.isCopy(c.type()) ? new RExpr.MethodCall(cell, "get", List.of())
                    : new RExpr.MethodCall(new RExpr.MethodCall(cell, "borrow", List.of()), "clone", List.of());
        }

        /** {@code Point[x=1, y=2]}。 */
        private RExpr recordToString() {
            List<RExpr> parts = new ArrayList<>();
            StringBuilder text = new StringBuilder(t.simpleName() + "[");
            for (int i = 0; i < t.recordComponents().size(); i++) {
                Decl.Param c = t.recordComponents().get(i);
                text.append(i > 0 ? ", " : "").append(c.name()).append('=');
                parts.add(str(text.toString()));
                text.setLength(0);
                RExpr v = componentRead(c, "self");
                if (JType.isPrimitive(c.type(), JType.Kind.CHAR)) {
                    v = call("JChar", v);
                } else if (cx.types().kind(c.type()) == TypeMapper.Kind.OBJECT) {
                    v = new RExpr.Try(call("jrt::string_of", new RExpr.Unary("&", v)));
                }
                parts.add(v);
            }
            parts.add(str(text + "]"));
            return new RExpr.Macro("jconcat", parts);
        }

        /** すべての構成要素が等しいか（浮動小数点数はビット表現、参照型は equals）。 */
        private RExpr recordEquals() {
            RExpr all = lit("true");
            for (Decl.Param c : t.recordComponents()) {
                RExpr a = componentRead(c, "self");
                RExpr b = componentRead(c, "o");
                RExpr eq = switch (cx.types().kind(c.type())) {
                    case PRIMITIVE -> JType.isFloating(c.type())
                            ? new RExpr.Binary("==", new RExpr.MethodCall(a, "to_bits", List.of()), new RExpr.MethodCall(b, "to_bits", List.of()))
                            : new RExpr.Binary("==", a, b);
                    case STRING -> new RExpr.Binary("==", a, b);
                    case ARRAY -> call("JArray::ptr_eq", new RExpr.Unary("&", a), new RExpr.Unary("&", b));
                    default -> new RExpr.Try(call("jrt::object::equals_nullable", new RExpr.Unary("&", a), new RExpr.Unary("&", b)));
                };
                all = all instanceof RExpr.Lit l && l.text().equals("true") ? eq : new RExpr.Binary("&&", all, eq);
            }
            return new RExpr.Match(new RExpr.MethodCall(path("other"), "downcast_ref::<" + self + ">", List.of()), List.of(
                    new RExpr.Arm("Some(o)", all), new RExpr.Arm("None", lit("false"))));
        }

        /** {@code 31 * h + hash(c)} を構成要素の順に畳み込む。 */
        private RExpr recordHash() {
            RExpr h = lit("0i32");
            for (Decl.Param c : t.recordComponents()) {
                RExpr v = componentRead(c, "self");
                RExpr hc = new RExpr.Try(call("jrt::util::arrays::JavaHash::java_hash", new RExpr.Unary("&", v)));
                h = new RExpr.MethodCall(new RExpr.MethodCall(h, "wrapping_mul", List.of(lit("31"))), "wrapping_add", List.of(hc));
            }
            return h;
        }
    }
}
