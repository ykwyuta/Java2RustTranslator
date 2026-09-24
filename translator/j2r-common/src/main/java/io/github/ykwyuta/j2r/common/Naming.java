package io.github.ykwyuta.j2r.common;

import java.util.Locale;
import java.util.Set;

/** Java の識別子を Rust の命名規約・予約語に合わせて変換する。 */
public final class Naming {
    private Naming() {}

    /** Rust の予約語（strict / reserved、edition 2021 + 将来予約分）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for",
            "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
            "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use",
            "where", "while", "async", "await", "dyn", "abstract", "become", "box", "do", "final",
            "macro", "override", "priv", "typeof", "unsized", "virtual", "yield", "try", "gen");

    /** raw identifier (r#) にできない予約語。 */
    private static final Set<String> NON_RAW = Set.of("crate", "self", "Self", "super", "_");

    /** camelCase / PascalCase → snake_case。 */
    public static String toSnakeCase(String name) {
        String s = name.replace('$', '_');
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                boolean prevLowerOrDigit = i > 0 && (Character.isLowerCase(s.charAt(i - 1)) || Character.isDigit(s.charAt(i - 1)));
                boolean prevUpper = i > 0 && Character.isUpperCase(s.charAt(i - 1));
                boolean nextLower = i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
                if (prevLowerOrDigit || (prevUpper && nextLower)) {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                        out.append('_');
                    }
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** SCREAMING_SNAKE_CASE（static 変数・定数用）。 */
    public static String toScreamingSnakeCase(String name) {
        if (name.equals(name.toUpperCase(Locale.ROOT))) {
            return name.replace('$', '_');
        }
        return toSnakeCase(name).toUpperCase(Locale.ROOT);
    }

    /** 予約語と衝突する場合に raw identifier もしくは接尾辞 _ で回避する。 */
    public static String escape(String ident) {
        if (NON_RAW.contains(ident)) {
            return ident + "_";
        }
        if (KEYWORDS.contains(ident)) {
            return "r#" + ident;
        }
        return ident;
    }

    /** 関数名・変数名。 */
    public static String valueName(String javaName) {
        return escape(toSnakeCase(javaName));
    }

    /** static 変数・定数名。 */
    public static String staticName(String javaName) {
        return escape(toScreamingSnakeCase(javaName));
    }

    /** モジュール名（パッケージのセグメント、クラス名）。 */
    public static String moduleName(String javaName) {
        return escape(toSnakeCase(javaName));
    }

    /** Cargo パッケージ名・crate 名として使える形にする。 */
    public static String crateName(String name) {
        String s = toSnakeCase(name).replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = "j_" + s;
        }
        return s;
    }
}
