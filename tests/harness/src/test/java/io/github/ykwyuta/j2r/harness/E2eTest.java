package io.github.ykwyuta.j2r.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ykwyuta.j2r.backend.CargoRunner;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.TranslatorOptions;
import io.github.ykwyuta.j2r.driver.Translator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 差分実行テスト: tests/e2e/&lt;category&gt;/&lt;case&gt;/src/**.java を JVM で実行した結果と、
 * 変換した Rust を cargo run した結果（標準出力・終了コード）を比較する。期待値ファイルは置かない（JVM が正）。
 * 任意で stdin.txt を標準入力として渡す。
 */
class E2eTest {
    private static final Path ROOT = Cases.repoRoot().resolve("tests/e2e");
    private static final Path WORK = Path.of("build/e2e").toAbsolutePath();

    @TestFactory
    Stream<DynamicTest> e2e() {
        return Cases.find(ROOT, "src").stream()
                .map(dir -> DynamicTest.dynamicTest(Cases.name(ROOT, dir), () -> runCase(dir)));
    }

    private void runCase(Path dir) throws Exception {
        assumeTrue(CargoRunner.available(), "cargo is not installed");
        String name = Cases.name(ROOT, dir).replace('/', '_');
        Path work = WORK.resolve(name);
        byte[] stdin = Files.exists(dir.resolve("stdin.txt")) ? Files.readAllBytes(dir.resolve("stdin.txt")) : new byte[0];

        Run java = runJava(dir.resolve("src"), work.resolve("classes"), stdin);

        Translator.Result tr = Translator.translate(TranslatorOptions.builder()
                .addSource(dir.resolve("src"))
                .outputDir(work.resolve("rust"))
                .crateName(name)
                .runtimePath(Cases.repoRoot().resolve("runtime/jrt"))
                .build());
        assertThat(tr.success()).as("translation diagnostics: %s", tr.diagnostics()).isTrue();

        // jrt のビルド結果を全ケースで共有してテストを速くする。
        Map<String, String> env = Map.of("CARGO_TARGET_DIR", WORK.resolve("target").toString());
        CargoRunner.Result build = CargoRunner.run(work.resolve("rust"), List.of("build", "--quiet"), null, env);
        assertThat(build.ok()).as("cargo build failed:\n%s", build.output()).isTrue();
        assertThat(build.output()).as("cargo build must not emit warnings").doesNotContain("warning");
        Path exe = WORK.resolve("target/debug").resolve(Naming.crateName(name) + (isWindows() ? ".exe" : ""));
        Run rust = runProcess(List.of(exe.toString()), stdin);

        assertThat(rust.stdout()).as("stdout").isEqualTo(java.stdout());
        assertThat(rust.exitCode()).as("exit code (stderr: %s)", rust.stderr()).isEqualTo(java.exitCode());
    }

    private record Run(int exitCode, String stdout, String stderr) {}

    private static Run runJava(Path src, Path classes, byte[] stdin) throws Exception {
        Files.createDirectories(classes);
        List<String> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        List<String> javac = new ArrayList<>(List.of(javaTool("javac"), "-d", classes.toString()));
        javac.addAll(files);
        Run compiled = runProcess(javac, new byte[0]);
        assertThat(compiled.exitCode()).as("javac: %s", compiled.stderr()).isZero();
        String mainClass = findMainClass(src, files);
        // Rust 側は常に UTF-8 で出力するので、JVM 側もロケールに依らず UTF-8 にそろえる。
        return runProcess(List.of(javaTool("java"), "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classes.toString(), mainClass), stdin);
    }

    /** main メソッドを含むファイルからクラス名を決める（package 宣言 + ファイル名）。 */
    private static String findMainClass(Path src, List<String> files) throws IOException {
        for (String f : files) {
            String text = Files.readString(Path.of(f));
            if (text.contains("static void main(")) {
                String rel = src.relativize(Path.of(f)).toString().replace(File.separatorChar, '.');
                return rel.substring(0, rel.length() - ".java".length());
            }
        }
        throw new IllegalStateException("no main class in " + src);
    }

    private static String javaTool(String tool) {
        return Path.of(System.getProperty("java.home"), "bin", tool + (isWindows() ? ".exe" : "")).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static Run runProcess(List<String> cmd, byte[] stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        p.getOutputStream().write(stdin);
        p.getOutputStream().close();
        var err = new java.util.concurrent.CompletableFuture<String>();
        Thread t = new Thread(() -> {
            try {
                err.complete(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                err.completeExceptionally(e);
            }
        });
        t.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return new Run(p.exitValue(), out, err.get());
    }
}
