package io.github.ykwyuta.j2r.rir;

import java.util.List;

/**
 * 1 つの .rs ファイル。
 *
 * @param path       crate ルート（src/）からの相対パス（例: {@code com/example/hello.rs}）
 * @param innerDocs  ファイル先頭の {@code //!} コメント
 * @param innerAttrs ファイル先頭の {@code #![...]} 属性
 */
public record RFile(String path, List<String> innerDocs, List<String> innerAttrs, List<RItem> items) {
    public RFile {
        innerDocs = List.copyOf(innerDocs);
        innerAttrs = List.copyOf(innerAttrs);
        items = List.copyOf(items);
    }
}
