package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.rir.RItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 1 つの .rs ファイルが使う {@code use} の集まり。std・外部 crate・crate 内の順に、親パスごとにまとめて出力する。 */
final class Imports {
    private final TreeSet<String> paths = new TreeSet<>();
    /** このファイル自身のモジュールパス（自分の中の型は use しない）。 */
    private final String self;

    /** テストのファイル（crate:: を crate 名にする）なら crate 名。 */
    private String crateName;

    Imports(String selfModule) {
        this.self = selfModule;
    }

    /** 結合テスト（tests/）のファイルの use。crate:: で始まるパスを crate 名で書く。 */
    static Imports forTests(String crateName) {
        Imports i = new Imports("tests");
        i.crateName = crateName;
        return i;
    }

    /** このファイルで定義する名前（同じ名前を use しない）。 */
    private final java.util.Set<String> locals = new java.util.HashSet<>();

    void add(String path) {
        int i = path.lastIndexOf("::");
        if (i > 0 && path.substring(0, i).equals(self) || locals.contains(path.substring(i < 0 ? 0 : i + 2))) {
            return;
        }
        paths.add(path);
    }

    void remove(String path) {
        paths.remove(path);
    }

    void defineLocal(String name) {
        locals.add(name);
    }

    List<RItem> items() {
        Map<String, TreeSet<String>> byParent = new TreeMap<>();
        for (String p : paths) {
            int i = p.lastIndexOf("::");
            byParent.computeIfAbsent(i < 0 ? "" : p.substring(0, i), k -> new TreeSet<>()).add(i < 0 ? p : p.substring(i + 2));
        }
        List<RItem> std = new ArrayList<>();
        List<RItem> external = new ArrayList<>();
        List<RItem> crate = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : byParent.entrySet()) {
            String parent = e.getKey();
            TreeSet<String> names = e.getValue();
            if (crateName != null && (parent.equals("crate") || parent.startsWith("crate::"))) {
                parent = crateName + parent.substring("crate".length());
            }
            String path = parent.isEmpty() ? names.first()
                    : names.size() == 1 ? parent + "::" + names.first()
                    : parent + "::{" + String.join(", ", names) + "}";
            RItem use = new RItem.Use(List.of(), path);
            if (parent.startsWith("std") || parent.startsWith("core")) {
                std.add(use);
            } else if (parent.startsWith("crate") || crateName != null && (parent.startsWith(crateName) || parent.startsWith("mock_mvc"))) {
                crate.add(use);
            } else {
                external.add(use);
            }
        }
        List<RItem> out = new ArrayList<>();
        addGroup(out, std);
        addGroup(out, external);
        addGroup(out, crate);
        return out;
    }

    /** グループの間は空行で区切る（RustPrinter は種類の違うアイテムの間に空行を入れるので、コメントで区切る）。 */
    private static void addGroup(List<RItem> out, List<RItem> group) {
        if (group.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            out.add(new RItem.Comment(GROUP_BREAK));
        }
        out.addAll(group);
    }

    /** グループの区切り（SpringTranslator が出力時に空行に置き換える）。 */
    static final String GROUP_BREAK = "\u0000use-group";
}
