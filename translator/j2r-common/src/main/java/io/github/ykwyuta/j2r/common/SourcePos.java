package io.github.ykwyuta.j2r.common;

/** Java ソース上の位置。行・列は 1 始まり。不明な場合は {@link #UNKNOWN}。 */
public record SourcePos(String file, long line, long column) {
    public static final SourcePos UNKNOWN = new SourcePos("<unknown>", 0, 0);

    @Override
    public String toString() {
        return line > 0 ? file + ":" + line + ":" + column : file;
    }
}
