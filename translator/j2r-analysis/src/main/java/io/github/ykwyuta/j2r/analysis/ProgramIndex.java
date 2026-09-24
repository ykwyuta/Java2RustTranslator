package io.github.ykwyuta.j2r.analysis;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.JType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 変換対象プログラム全体の索引。型・メソッド・static フィールドから、Rust 上の配置（モジュールパスと名前）を引く。
 */
public final class ProgramIndex {

    /** @param modulePath crate ルートからのモジュールパス（例: [com, example, hello_world]） */
    public record TypeInfo(Decl.TypeDecl decl, List<String> modulePath) {
        public String rustPath() {
            return "crate::" + String.join("::", modulePath);
        }
    }

    public record MethodInfo(Decl.MethodDecl decl, TypeInfo owner) {}

    public enum FieldStorage {
        /** コンパイル時定数 → {@code pub const}。 */
        CONST,
        /** それ以外 → {@code thread_local!} の Cell / RefCell。 */
        THREAD_LOCAL
    }

    public record FieldInfo(Decl.FieldDecl decl, TypeInfo owner, String rustName, FieldStorage storage) {}

    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final Map<String, MethodInfo> methods = new LinkedHashMap<>();
    private final Map<String, FieldInfo> fields = new LinkedHashMap<>();

    public ProgramIndex(Decl.Program program) {
        for (Decl.CompilationUnit u : program.units()) {
            for (Decl.TypeDecl t : u.types()) {
                List<String> path = new ArrayList<>();
                if (!t.packageName().isEmpty()) {
                    for (String seg : t.packageName().split("\\.")) {
                        path.add(Naming.moduleName(seg));
                    }
                }
                path.add(Naming.moduleName(t.simpleName()));
                TypeInfo ti = new TypeInfo(t, List.copyOf(path));
                types.put(t.qualifiedName(), ti);
                for (Decl.MethodDecl m : t.methods()) {
                    methods.put(m.ref().key(), new MethodInfo(m, ti));
                }
                for (Decl.FieldDecl f : t.fields()) {
                    boolean constant = f.isFinal() && f.constantValue() != null
                            && (f.type() instanceof JType.Primitive || JType.isString(f.type()));
                    fields.put(t.qualifiedName() + "#" + f.name(), new FieldInfo(f, ti, Naming.staticName(f.name()),
                            constant ? FieldStorage.CONST : FieldStorage.THREAD_LOCAL));
                }
            }
        }
    }

    public TypeInfo type(String qualifiedName) {
        return types.get(qualifiedName);
    }

    public Collection<TypeInfo> types() {
        return types.values();
    }

    public MethodInfo method(String key) {
        return methods.get(key);
    }

    public FieldInfo field(String owner, String name) {
        return fields.get(owner + "#" + name);
    }

    /** main メソッドを持つ型。 */
    public List<TypeInfo> mainTypes() {
        return types.values().stream().filter(t -> t.decl().methods().stream().anyMatch(Decl.MethodDecl::isMain)).toList();
    }
}
