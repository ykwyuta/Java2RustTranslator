package io.github.ykwyuta.j2r.rir;

/** Rust の型（テキスト表現）。例: {@code i32}, {@code JString}, {@code JArray<i32>}。 */
public record RType(String text) {
    public static final RType UNIT = new RType("()");

    @Override
    public String toString() {
        return text;
    }
}
