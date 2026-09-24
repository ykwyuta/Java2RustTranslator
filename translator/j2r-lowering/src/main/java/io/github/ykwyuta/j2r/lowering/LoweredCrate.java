package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.rir.RFile;
import java.util.List;

/**
 * lowering の結果。
 *
 * @param files       クラスごとの .rs ファイル
 * @param modules     各ファイルのモジュールパス（files と同順）
 * @param mainModule  main メソッドを持つクラスのパス（モジュールパス + 構造体名。なければ null）
 */
public record LoweredCrate(List<RFile> files, List<List<String>> modules, List<String> mainModule) {
    public LoweredCrate {
        files = List.copyOf(files);
        modules = List.copyOf(modules);
    }
}
