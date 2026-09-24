package io.github.ykwyuta.j2r.jir;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 解決済みのメソッド参照（javac のオーバーロード解決結果）。
 *
 * @param owner       宣言クラスの完全修飾名
 * @param name        メソッド名（コンストラクタは {@code <init>}）
 * @param paramTypes  消去後の仮引数型
 * @param returnType  消去後の戻り値型
 * @param isStatic    static メソッドか
 * @param ownerInterface 宣言した型がインタフェースか
 */
public record MethodRef(String owner, String name, List<JType> paramTypes, JType returnType, boolean isStatic, boolean ownerInterface) {

    public MethodRef {
        paramTypes = List.copyOf(paramTypes);
    }

    public MethodRef(String owner, String name, List<JType> paramTypes, JType returnType, boolean isStatic) {
        this(owner, name, paramTypes, returnType, isStatic, false);
    }

    /** マッピング規則・名前表で使うシグネチャ（例: {@code max(int,int)}）。 */
    public String signature() {
        return name + "(" + paramTypes.stream().map(JType::javaName).collect(Collectors.joining(",")) + ")";
    }

    /** プログラム全体で一意なキー（例: {@code com.example.Foo#max(int,int)}）。 */
    public String key() {
        return owner + "#" + signature();
    }
}
