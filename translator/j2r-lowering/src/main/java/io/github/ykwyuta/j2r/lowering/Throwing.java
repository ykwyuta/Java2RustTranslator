package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.jir.Decl;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 例外を送出しうるメソッド（Rust で {@code JResult<T>} を返す関数）の集合（docs/03-translation-rules.md §5）。
 *
 * <p>オーバーライドの関係にあるメソッドは、振り分け関数から同じ形で呼ぶため、同じ「系列」として扱う
 * （系列のどれかが例外を送出しうるなら、系列のすべてが {@code JResult} を返す）。コンストラクタ・static メソッド・
 * private メソッドはそれぞれ単独の系列になる。
 *
 * <p>どのメソッドが例外を送出しうるかは、lowering の結果から求める（固定点）。lowering は現在の集合を前提に
 * 各関数を生成し、{@code ?} や {@code return Err(..)} を生成したのに {@code JResult} を返さない関数があれば
 * {@link #require} で記録する。記録がなくなるまで、集合を広げて lowering をやり直す。
 */
final class Throwing {
    /** メソッドのキー → 系列の代表のキー。 */
    private final Map<String, String> parent = new HashMap<>();
    /** JResult を返す系列（代表のキー）。 */
    private final Set<String> throwing;
    /** この回の lowering で新たに JResult を返す必要があると分かった系列。 */
    private final Set<String> required = new LinkedHashSet<>();

    Throwing(ProgramIndex index, Set<String> throwing) {
        this.throwing = throwing;
        for (ProgramIndex.TypeInfo t : index.types()) {
            for (Decl.MethodDecl m : t.decl().methods()) {
                String key = m.ref().key();
                find(key);
                if (!m.isStatic() && !m.isConstructor() && !m.isPrivate()) {
                    for (String o : m.overrides()) {
                        union(key, o);
                    }
                }
            }
        }
    }

    private String find(String key) {
        String p = parent.get(key);
        if (p == null) {
            parent.put(key, key);
            return key;
        }
        if (p.equals(key)) {
            return key;
        }
        String root = find(p);
        parent.put(key, root);
        return root;
    }

    private void union(String a, String b) {
        String ra = find(a);
        String rb = find(b);
        if (!ra.equals(rb)) {
            parent.put(rb, ra);
        }
    }

    /** キー key のメソッド（またはコンストラクタ）が JResult を返すか。 */
    boolean isThrowing(String key) {
        return throwing.contains(find(key));
    }

    boolean isThrowing(Decl.MethodDecl m) {
        return isThrowing(m.ref().key());
    }

    /** キー key のメソッドの系列は JResult を返す必要がある（次の回の lowering から）。 */
    void require(String key) {
        String r = find(key);
        if (!throwing.contains(r)) {
            required.add(r);
        }
    }

    Set<String> required() {
        return required;
    }
}
