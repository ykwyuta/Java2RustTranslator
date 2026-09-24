package io.github.ykwyuta.j2r.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ykwyuta.j2r.backend.CargoProjectWriter;
import io.github.ykwyuta.j2r.common.TranslatorOptions;
import io.github.ykwyuta.j2r.driver.Translator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * ゴールデンテスト: tests/golden/&lt;category&gt;/&lt;case&gt;/Input.java（または src/）を変換した src/ 配下の Rust と、
 * expected/ の内容を比較する。{@code ./gradlew :j2r-test-harness:test -PupdateGolden=true} で expected/ を更新する。
 */
class GoldenTest {
    private static final Path ROOT = Cases.repoRoot().resolve("tests/golden");

    @TestFactory
    Stream<DynamicTest> golden() {
        return Cases.find(ROOT, "expected").stream()
                .map(dir -> DynamicTest.dynamicTest(Cases.name(ROOT, dir), () -> runCase(dir)));
    }

    private void runCase(Path dir) throws IOException {
        Path input = Files.isDirectory(dir.resolve("src")) ? dir.resolve("src") : dir.resolve("Input.java");
        Path out = Path.of("build/golden").toAbsolutePath().resolve(Cases.name(ROOT, dir));
        Translator.Result r = Translator.translate(TranslatorOptions.builder()
                .addSource(input)
                .outputDir(out)
                .crateName(dir.getFileName().toString())
                .runtimePath(Cases.repoRoot().resolve("runtime/jrt"))
                .build());
        assertThat(r.success()).as("diagnostics: %s", r.diagnostics()).isTrue();

        Map<String, String> actual = new TreeMap<>();
        for (CargoProjectWriter.GeneratedFile f : r.files()) {
            actual.put(f.path(), f.content());
        }
        Path expectedDir = dir.resolve("expected");
        if (Boolean.getBoolean("j2r.updateGolden")) {
            try (Stream<Path> old = Files.walk(expectedDir)) {
                for (Path p : old.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
            for (var e : actual.entrySet()) {
                Path p = expectedDir.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
            return;
        }
        Map<String, String> expected = new TreeMap<>();
        try (Stream<Path> s = Files.walk(expectedDir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                expected.put(expectedDir.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        assertThat(actual.keySet()).as("generated files").isEqualTo(expected.keySet());
        for (var e : expected.entrySet()) {
            assertThat(actual.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
        }
    }
}
