package io.github.ykwyuta.j2r.spring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成する関数の形（名前・引数と戻り値の型・async か・Result を返すか）。本体を変換する前に全部決めておき、
 * 呼び出し側の変換（{@link BodyLowerer}）が参照する。キーは MethodRef.key()。
 */
final class Plans {
    enum SelfKind { NONE, REF, MUT, VALUE }

    /**
     * サービス・エンティティ・record・enum のメソッド。
     *
     * @param helperName 接続を引数に取る版（{@code find_by_id_in}）の名前。サービスの中から呼ばれるメソッドなど、なければ null
     * @param needsConn  データベースを使う（async で、呼び出しに接続が要る）
     * @param fallible   {@code Result<T>} を返す
     * @param params     引数の型（借用の型）
     */
    record Method(String rustName, String helperName, boolean needsConn, boolean fallible, List<RT> params, RT ret,
                  SelfKind self) {
        Method {
            params = List.copyOf(params);
        }
    }

    /** Mapper の関数（{@code todo_mapper::find_all}）。params は接続を除いた引数の型。 */
    record MapperFn(String modulePath, String module, String rustName, List<RT> params, RT ret) {
        MapperFn {
            params = List.copyOf(params);
        }
    }

    /** 例外クラスに対応する Error のバリアント。fields はコンストラクタの引数の型。 */
    record ErrorVariant(String name, List<RT> fields, String message) {
        ErrorVariant {
            fields = List.copyOf(fields);
        }
    }

    final Map<String, Method> methods = new HashMap<>();
    final Map<String, MapperFn> mapperFns = new HashMap<>();
    /** 例外クラスの完全修飾名 → バリアント。 */
    final Map<String, ErrorVariant> errors = new HashMap<>();
    /** アクセサとして消したメソッド（getter / setter / record のアクセサ）→ フィールド名。 */
    final Map<String, String> getters = new HashMap<>();
    final Map<String, String> setters = new HashMap<>();
}
