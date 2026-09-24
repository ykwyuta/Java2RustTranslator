package io.github.ykwyuta.j2r.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** tests/ 配下のテストケースの探索。 */
final class Cases {
    private Cases() {}

    static Path repoRoot() {
        return Path.of(System.getProperty("j2r.repoRoot", "../..")).toAbsolutePath().normalize();
    }

    /** root/<category>/<case>/ のうち、marker を含むディレクトリ。 */
    static List<Path> find(Path root, String marker) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root, 2)) {
            return s.filter(p -> Files.exists(p.resolve(marker))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String name(Path root, Path caseDir) {
        return root.relativize(caseDir).toString().replace('\\', '/');
    }
}
