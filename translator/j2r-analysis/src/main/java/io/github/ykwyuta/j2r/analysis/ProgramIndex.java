package io.github.ykwyuta.j2r.analysis;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.JType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 変換対象プログラム全体の索引。型・メソッド・フィールドから、Rust 上の配置（モジュールパス・構造体名・名前）を引く。
 */
public final class ProgramIndex {

    /** Rust のプレリュードや jrt の名前と衝突するので、構造体名に J を前置するクラス名。 */
    private static final Set<String> RESERVED_TYPE_NAMES = Set.of("Option", "Some", "None", "Result", "Ok", "Err", "Box", "Vec",
            "String", "ToString", "Clone", "Copy", "Default", "Drop", "Fn", "FnMut", "FnOnce", "Iterator", "Into", "From",
            "Send", "Sync", "Sized", "Eq", "PartialEq", "Ord", "PartialOrd", "Self", "JObject", "JString", "JArray", "JChar",
            "Object", "Collections", "Any", "Rc", "Cell", "RefCell");

    /**
     * @param modulePath crate ルートからのモジュールパス（例: [com, example, hello_world]）
     * @param structName Rust の構造体名（通常はクラスの単純名）
     */
    public record TypeInfo(Decl.TypeDecl decl, List<String> modulePath, String structName) {
        public String modulePathString() {
            return "crate::" + String.join("::", modulePath);
        }
    }

    public record MethodInfo(Decl.MethodDecl decl, TypeInfo owner) {}

    public enum FieldStorage {
        /** コンパイル時定数 → {@code pub const}。 */
        CONST,
        /** static フィールド → {@code thread_local!} の Cell / RefCell。 */
        THREAD_LOCAL,
        /** enum 定数 → 定数の配列から取り出す関数。 */
        ENUM_CONSTANT,
        /** インスタンスフィールド → 構造体のフィールド（Cell / RefCell）。 */
        INSTANCE
    }

    /** ordinal は enum 定数のときだけ意味を持つ。 */
    public record FieldInfo(Decl.FieldDecl decl, TypeInfo owner, String rustName, FieldStorage storage, JType type, int ordinal) {}

    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final Map<String, MethodInfo> methods = new LinkedHashMap<>();
    private final Map<String, FieldInfo> fields = new LinkedHashMap<>();

    public ProgramIndex(Decl.Program program) {
        Map<String, Decl.TypeDecl> decls = new LinkedHashMap<>();
        program.types().forEach(t -> decls.put(t.qualifiedName(), t));
        for (Decl.TypeDecl t : decls.values()) {
            types.put(t.qualifiedName(), new TypeInfo(t, modulePath(t, decls), structName(t)));
        }
        for (TypeInfo ti : types.values()) {
            Decl.TypeDecl t = ti.decl();
            for (Decl.MethodDecl m : t.methods()) {
                methods.put(m.ref().key(), new MethodInfo(m, ti));
            }
            for (Decl.FieldDecl f : t.fields()) {
                FieldStorage storage;
                String rustName;
                if (!f.isStatic()) {
                    storage = FieldStorage.INSTANCE;
                    rustName = Naming.valueName(f.name());
                } else {
                    boolean constant = f.isFinal() && f.constantValue() != null
                            && (f.type() instanceof JType.Primitive || JType.isString(f.type()));
                    storage = constant ? FieldStorage.CONST : FieldStorage.THREAD_LOCAL;
                    rustName = Naming.staticName(f.name());
                }
                fields.put(t.qualifiedName() + "#" + f.name(), new FieldInfo(f, ti, rustName, storage, f.type(), -1));
            }
            int ordinal = 0;
            for (Decl.EnumConstant c : t.enumConstants()) {
                fields.put(t.qualifiedName() + "#" + c.name(), new FieldInfo(null, ti, Naming.staticName(c.name()),
                        FieldStorage.ENUM_CONSTANT, new JType.ClassType(t.qualifiedName()), ordinal++));
            }
        }
    }

    private static List<String> modulePath(Decl.TypeDecl t, Map<String, Decl.TypeDecl> decls) {
        List<String> path = new ArrayList<>();
        if (t.outer() != null && decls.containsKey(t.outer())) {
            path.addAll(modulePath(decls.get(t.outer()), decls));
        } else if (!t.packageName().isEmpty()) {
            for (String seg : t.packageName().split("\\.")) {
                path.add(Naming.moduleName(seg));
            }
        }
        path.add(Naming.moduleName(t.simpleName()));
        return List.copyOf(path);
    }

    private static String structName(Decl.TypeDecl t) {
        String n = t.simpleName().replace('$', '_');
        return RESERVED_TYPE_NAMES.contains(n) ? "J" + n : Naming.escape(n);
    }

    public TypeInfo type(String qualifiedName) {
        return types.get(qualifiedName);
    }

    public boolean isProgramType(String qualifiedName) {
        return types.containsKey(qualifiedName);
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

    /** 型 owner のインスタンスフィールド（宣言順）。 */
    public List<FieldInfo> instanceFields(String owner) {
        return fields.values().stream().filter(f -> f.owner().decl().qualifiedName().equals(owner) && f.storage() == FieldStorage.INSTANCE).toList();
    }

    /** main メソッドを持つ型。 */
    public List<TypeInfo> mainTypes() {
        return types.values().stream().filter(t -> t.decl().methods().stream().anyMatch(Decl.MethodDecl::isMain)).toList();
    }
}
