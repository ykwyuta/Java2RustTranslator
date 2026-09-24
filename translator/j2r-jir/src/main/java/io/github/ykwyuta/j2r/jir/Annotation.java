package io.github.ykwyuta.j2r.jir;

import java.util.List;
import java.util.Map;

/**
 * 注釈とその要素の値（フレームワークの変換で使う）。
 *
 * @param type   注釈型の完全修飾名
 * @param values ソースに書かれた要素の値（既定値は含まない）。値は String / Boolean / Integer などのボックス型 /
 *               クラスリテラルの完全修飾名（String）/ 列挙定数の名前（String）/ {@link Annotation} / それらの List
 */
public record Annotation(String type, Map<String, Object> values) {
    public Annotation {
        values = Map.copyOf(values);
    }

    /** 要素 name の値（書かれていなければ null）。 */
    public Object value(String name) {
        return values.get(name);
    }

    /** 要素 name の文字列値。配列なら先頭の要素。書かれていなければ null。 */
    public String stringValue(String name) {
        Object v = values.get(name);
        if (v instanceof List<?> l) {
            v = l.isEmpty() ? null : l.get(0);
        }
        return v == null ? null : v.toString();
    }

    /** 要素 name の真偽値。書かれていなければ defaultValue。 */
    public boolean booleanValue(String name, boolean defaultValue) {
        return values.get(name) instanceof Boolean b ? b : defaultValue;
    }
}
