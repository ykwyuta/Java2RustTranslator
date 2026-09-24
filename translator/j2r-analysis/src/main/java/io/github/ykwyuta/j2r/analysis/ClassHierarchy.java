package io.github.ykwyuta.j2r.analysis;

import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JirVisitor;
import io.github.ykwyuta.j2r.jir.MethodRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * クラス階層解析（CHA）と仮想呼び出しの解決。プログラム全体が分かっている（閉世界）ことを前提に、
 * 呼び出し {@code recv.m()} の実行時の呼び先の候補を求める。候補が 1 つなら直接呼び出し、
 * 複数（またはラムダの可能性がある）なら、実行時の型で振り分ける関数（ディスパッチャ）を経由する。
 */
public final class ClassHierarchy {
    private final ProgramIndex index;
    /** ラムダ・メソッド参照の実装先になっている関数型インタフェース。 */
    private final Set<String> lambdaTargets = new HashSet<>();
    private final Map<String, Boolean> dispatchCache = new HashMap<>();

    public ClassHierarchy(ProgramIndex index, Decl.Program program) {
        this.index = index;
        program.types().forEach(t -> {
            for (Decl.MethodDecl m : t.methods()) {
                if (m.body() != null) {
                    scanLambdas(m.body());
                }
            }
            t.instanceInit().forEach(this::scanLambdas);
            if (t.staticInit() != null) {
                t.staticInit().forEach(this::scanLambdas);
            }
            for (Decl.FieldDecl f : t.fields()) {
                if (f.init() != null) {
                    JirVisitor.walk(new io.github.ykwyuta.j2r.jir.Stmt.ExprStmt(f.init(), f.pos()), s -> { }, this::noteLambda);
                }
            }
        });
    }

    private void scanLambdas(io.github.ykwyuta.j2r.jir.Stmt s) {
        JirVisitor.walk(s, x -> { }, this::noteLambda);
    }

    private void noteLambda(Expr e) {
        if (e instanceof Expr.Lambda l) {
            lambdaTargets.addAll(l.interfaces());
        }
    }

    /** qn が型 t 自身またはその上位型か。 */
    private static boolean isSubtype(Decl.TypeDecl t, String qn) {
        return t.qualifiedName().equals(qn) || t.allSupertypes().contains(qn);
    }

    /** qn のサブタイプ（qn 自身を含む）のうち、インスタンスを作れるプログラムのクラス。 */
    public List<ProgramIndex.TypeInfo> concreteSubtypes(String qn) {
        List<ProgramIndex.TypeInfo> out = new ArrayList<>();
        for (ProgramIndex.TypeInfo ti : index.types()) {
            if (ti.decl().isConcrete() && isSubtype(ti.decl(), qn)) {
                out.add(ti);
            }
        }
        return out;
    }

    /** qn のサブタイプ（qn 自身を含む）のすべてのプログラムのクラス（abstract を含む。インタフェースを除く）。 */
    public List<ProgramIndex.TypeInfo> classSubtypes(String qn) {
        List<ProgramIndex.TypeInfo> out = new ArrayList<>();
        for (ProgramIndex.TypeInfo ti : index.types()) {
            if (!ti.decl().isInterface() && isSubtype(ti.decl(), qn)) {
                out.add(ti);
            }
        }
        return out;
    }

    /** ラムダが qn を実装しうるか。 */
    public boolean lambdaMayImplement(String qn) {
        return lambdaTargets.contains(qn);
    }

    /** 宣言 m が、キー key のメソッドそのものか、それをオーバーライドしているか。 */
    private static boolean implementsKey(Decl.MethodDecl m, String key) {
        return !m.isStatic() && !m.isConstructor() && (m.ref().key().equals(key) || m.overrides().contains(key));
    }

    /**
     * インスタンスを作れるクラス s で、キー key のメソッドを呼んだときに実行される本体（プログラムのメソッド）。
     * クラスの継承の連鎖を優先し、なければインタフェースの default メソッドのうち最も具体的なもの。
     * プログラムに本体がなければ（JDK のメソッドを継承）null。
     */
    public ProgramIndex.MethodInfo implementation(ProgramIndex.TypeInfo s, String key) {
        ProgramIndex.TypeInfo cur = s;
        while (cur != null) {
            for (Decl.MethodDecl m : cur.decl().methods()) {
                if (m.body() != null && implementsKey(m, key)) {
                    return new ProgramIndex.MethodInfo(m, cur);
                }
            }
            String sup = cur.decl().superclass();
            cur = sup == null ? null : index.type(sup);
        }
        List<ProgramIndex.MethodInfo> defaults = new ArrayList<>();
        for (String st : s.decl().allSupertypes()) {
            ProgramIndex.TypeInfo iface = index.type(st);
            if (iface == null || !iface.decl().isInterface()) {
                continue;
            }
            for (Decl.MethodDecl m : iface.decl().methods()) {
                if (m.body() != null && implementsKey(m, key)) {
                    defaults.add(new ProgramIndex.MethodInfo(m, iface));
                }
            }
        }
        for (ProgramIndex.MethodInfo d : defaults) {
            boolean mostSpecific = defaults.stream().noneMatch(o -> o != d && o.owner().decl().allSupertypes().contains(d.owner().decl().qualifiedName()));
            if (mostSpecific) {
                return d;
            }
        }
        return null;
    }

    /** 呼び出し先の候補（重複なし）。null を含む場合、プログラム外（JDK）の実装を継承するクラスがある。 */
    public Set<ProgramIndex.MethodInfo> targets(MethodRef ref) {
        Set<ProgramIndex.MethodInfo> out = new LinkedHashSet<>();
        for (ProgramIndex.TypeInfo s : concreteSubtypes(ref.owner())) {
            out.add(implementation(s, ref.key()));
        }
        return out;
    }

    /** メソッド宣言 m（型 owner）の呼び出しに、実行時の型による振り分けが必要か。 */
    public boolean needsDispatch(ProgramIndex.MethodInfo m) {
        Decl.MethodDecl d = m.decl();
        if (d.isStatic() || d.isConstructor() || d.isPrivate()) {
            return false;
        }
        return dispatchCache.computeIfAbsent(d.ref().key(), k -> {
            if (lambdaMayImplement(m.owner().decl().qualifiedName())) {
                return true;
            }
            Set<ProgramIndex.MethodInfo> t = targets(d.ref());
            if (t.size() != 1) {
                return true;
            }
            ProgramIndex.MethodInfo only = t.iterator().next();
            return only == null || !only.decl().ref().key().equals(d.ref().key());
        });
    }

    /** 本体を持つメソッドの Rust での関数名（振り分け関数と区別するため、振り分けが必要なら _impl を付ける）。 */
    public String bodyName(ProgramIndex.MethodInfo m) {
        return needsDispatch(m) ? m.decl().rustName() + "_impl" : m.decl().rustName();
    }
}
