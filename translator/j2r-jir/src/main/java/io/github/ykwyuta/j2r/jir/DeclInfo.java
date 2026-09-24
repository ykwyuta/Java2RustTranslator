package io.github.ykwyuta.j2r.jir;

import java.util.List;
import java.util.Set;

/**
 * 宣言（型・フィールド・メソッド・仮引数）のメタ情報。JIR 本体は消去後の型しか持たないため、
 * フレームワークの変換（Spring・MyBatis）が必要とする注釈と消去前の型をここに持つ。
 * {@link Decl.Program#info()} に {@link #typeKey} などのキーで入る。
 *
 * @param annotations 宣言の注釈と、宣言の型に付いた型注釈（{@code @Nullable String} の {@code @Nullable} など）
 * @param genericType 消去前の型（型・メソッドの戻り値・フィールド・仮引数の型。型の宣言では null）
 */
public record DeclInfo(List<Annotation> annotations, JType genericType) {
    /** null を許す型を表す注釈（JSpecify と Spring Framework の旧注釈）。 */
    public static final Set<String> NULLABLE = Set.of("org.jspecify.annotations.Nullable", "org.springframework.lang.Nullable",
            "jakarta.annotation.Nullable", "javax.annotation.Nullable");

    public static final DeclInfo EMPTY = new DeclInfo(List.of(), null);

    public DeclInfo {
        annotations = List.copyOf(annotations);
    }

    public boolean has(String type) {
        return get(type) != null;
    }

    /** 注釈 type（なければ null）。 */
    public Annotation get(String type) {
        for (Annotation a : annotations) {
            if (a.type().equals(type)) {
                return a;
            }
        }
        return null;
    }

    /** {@code @Nullable} が付いているか。 */
    public boolean nullable() {
        return annotations.stream().anyMatch(a -> NULLABLE.contains(a.type()));
    }

    public static String typeKey(String qualifiedName) {
        return qualifiedName;
    }

    public static String fieldKey(String owner, String field) {
        return owner + "." + field;
    }

    public static String methodKey(MethodRef method) {
        return method.key();
    }

    public static String paramKey(MethodRef method, int index) {
        return method.key() + "@" + index;
    }
}
