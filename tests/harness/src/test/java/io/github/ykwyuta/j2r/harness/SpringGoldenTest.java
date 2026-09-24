package io.github.ykwyuta.j2r.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ykwyuta.j2r.backend.CargoProjectWriter;
import io.github.ykwyuta.j2r.backend.CargoRunner;
import io.github.ykwyuta.j2r.common.Severity;
import io.github.ykwyuta.j2r.common.TranslatorOptions;
import io.github.ykwyuta.j2r.driver.Translator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Spring モード（{@code --framework spring}）のゴールデンテスト: tests/spring/&lt;case&gt;/src/main/java（と
 * src/main/resources・src/test/java）を変換した crate と expected/ を比べる。Spring・MyBatis・JSpecify の注釈は tests/spring/stubs の
 * スタブで型検査する（変換器は注釈の名前と値しか見ない）。
 *
 * <p>cargo があれば、生成した crate を（テストも含めて）cargo check し、エラーも警告も出ないことを確かめる（依存 crate のビルドは
 * build/spring-target で共有する）。{@code ./gradlew :j2r-test-harness:test -PupdateGolden=true} で expected/ を更新する。
 */
class SpringGoldenTest {
    private static final Path ROOT = Cases.repoRoot().resolve("tests/spring");

    @TestFactory
    Stream<DynamicTest> spring() throws IOException {
        List<Path> cases;
        try (Stream<Path> s = Files.list(ROOT)) {
            cases = s.filter(p -> Files.isDirectory(p.resolve("src/main/java"))).sorted().toList();
        }
        return cases.stream().flatMap(dir -> Stream.of(
                DynamicTest.dynamicTest(dir.getFileName() + " (golden)", () -> golden(dir)),
                DynamicTest.dynamicTest(dir.getFileName() + " (cargo check)", () -> cargoCheck(dir))));
    }

    private static Translator.Result translate(Path dir, Path out) {
        TranslatorOptions.Builder options = TranslatorOptions.builder();
        if (Files.isDirectory(dir.resolve("src/test/java"))) {
            options.addTestSource(dir.resolve("src/test/java"));
        }
        Translator.Result r = Translator.translate(options
                .addSource(dir.resolve("src/main/java"))
                .addSource(ROOT.resolve("stubs"))
                .addResourceDir(dir.resolve("src/main/resources"))
                .framework(TranslatorOptions.Framework.SPRING)
                .outputDir(out)
                .crateName(dir.getFileName().toString())
                .build());
        assertThat(r.success()).as("diagnostics: %s", r.diagnostics()).isTrue();
        assertThat(r.diagnostics()).as("warnings").noneMatch(d -> d.severity() == Severity.WARNING);
        return r;
    }

    private void golden(Path dir) throws IOException {
        Path out = Path.of("build/spring").toAbsolutePath().resolve(dir.getFileName().toString());
        Translator.Result r = translate(dir, out);
        Map<String, String> actual = new TreeMap<>();
        for (CargoProjectWriter.GeneratedFile f : r.files()) {
            actual.put(f.path(), f.content());
        }
        Path expectedDir = dir.resolve("expected");
        if (Boolean.getBoolean("j2r.updateGolden")) {
            if (Files.exists(expectedDir)) {
                try (Stream<Path> old = Files.walk(expectedDir)) {
                    for (Path p : old.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
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

    private void cargoCheck(Path dir) {
        assumeTrue(CargoRunner.available(), "cargo is not installed");
        Path out = Path.of("build/spring-check").toAbsolutePath().resolve(dir.getFileName().toString());
        translate(dir, out);
        Path target = Path.of("build/spring-target").toAbsolutePath();
        CargoRunner.Result check = CargoRunner.run(out, List.of("check", "--quiet", "--all-targets"), new byte[0],
                Map.of("CARGO_TARGET_DIR", target.toString()));
        assertThat(check.ok()).as("cargo check failed:\n%s", check.output()).isTrue();
        assertThat(check.output()).as("cargo check must not emit warnings").doesNotContain("warning");
    }
}
