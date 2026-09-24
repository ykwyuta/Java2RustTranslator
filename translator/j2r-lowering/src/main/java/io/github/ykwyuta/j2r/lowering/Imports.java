package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.rir.RItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 1 つのファイル（クラス）から他のクラスを参照するときの名前の解決と {@code use} 文の管理。
 * 構造体は {@code use crate::a::b::dog::Dog;}、static 変数はモジュールを取り込んで {@code dog::COUNT} で参照する。
 * 名前が衝突する場合は {@code crate::} からの完全なパスにする。
 */
final class Imports {
    private final ProgramIndex.TypeInfo current;
    private final Map<String, ProgramIndex.TypeInfo> structs = new HashMap<>();
    private final Map<String, ProgramIndex.TypeInfo> modules = new HashMap<>();
    private final TreeSet<String> uses = new TreeSet<>();

    Imports(ProgramIndex.TypeInfo current) {
        this.current = current;
        structs.put(current.structName(), current);
    }

    private static String moduleName(ProgramIndex.TypeInfo t) {
        return t.modulePath().get(t.modulePath().size() - 1);
    }

    /** 型 t の構造体を参照する名前。 */
    String type(ProgramIndex.TypeInfo t) {
        if (t == current) {
            return current.structName();
        }
        ProgramIndex.TypeInfo existing = structs.get(t.structName());
        if (existing == null) {
            structs.put(t.structName(), t);
            uses.add(t.modulePathString() + "::" + t.structName());
            return t.structName();
        }
        if (existing == t) {
            return t.structName();
        }
        return t.modulePathString() + "::" + t.structName();
    }

    /** 型 t のモジュールにある static 項目（thread_local / const）を参照するパス。 */
    String item(ProgramIndex.TypeInfo t, String name) {
        if (t == current) {
            return name;
        }
        String mod = moduleName(t);
        // 入れ子の型のモジュールは、このファイルの子モジュールなので use なしで参照できる。
        List<String> childPath = new ArrayList<>(current.modulePath());
        childPath.add(mod);
        if (childPath.equals(t.modulePath())) {
            return mod + "::" + name;
        }
        ProgramIndex.TypeInfo existing = modules.get(mod);
        if (existing == null && !mod.equals(moduleName(current))) {
            modules.put(mod, t);
            uses.add(t.modulePathString());
            return mod + "::" + name;
        }
        if (existing == t) {
            return mod + "::" + name;
        }
        return t.modulePathString() + "::" + name;
    }

    List<RItem> useItems() {
        List<RItem> out = new ArrayList<>();
        for (String u : uses) {
            out.add(new RItem.Use(List.of(), u));
        }
        return out;
    }
}
