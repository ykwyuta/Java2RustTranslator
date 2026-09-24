package io.github.ykwyuta.j2r.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.ykwyuta.j2r.backend.CargoRunner;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.TranslatorOptions;
import io.github.ykwyuta.j2r.driver.Translator;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * JUnit テストの変換: tests/junit/&lt;category&gt;/&lt;case&gt;/src/**.java のテストクラスを JUnit Platform で実行した結果と、
 * 変換した Rust を cargo test した結果（テストごとの ok / FAILED / ignored）を比較する。
 * 前提（Assumptions）の失敗で中断したテストは、Rust 側では成功（ok）として扱う。
 */
class JunitTest {
    private static final Path ROOT = Cases.repoRoot().resolve("tests/junit");
    private static final Path WORK = Path.of("build/junit").toAbsolutePath();
    private static final Pattern RESULT = Pattern.compile("^test (\\S+)::__tests::(\\S+) \\.\\.\\. (ok|FAILED|ignored)", Pattern.MULTILINE);

    @TestFactory
    Stream<DynamicTest> junit() {
        return Cases.find(ROOT, "src").stream()
                .map(dir -> DynamicTest.dynamicTest(Cases.name(ROOT, dir), () -> runCase(dir)));
    }

    private void runCase(Path dir) throws Exception {
        assumeTrue(CargoRunner.available(), "cargo is not installed");
        String name = "junit_" + Cases.name(ROOT, dir).replace('/', '_');
        Path work = WORK.resolve(name);
        List<Path> classpath = List.of(jarOf(org.junit.jupiter.api.Test.class), jarOf(org.opentest4j.AssertionFailedError.class));

        Map<String, String> expected = runOnJvm(dir.resolve("src"), work.resolve("classes"), classpath);
        assertThat(expected).as("JUnit tests found in %s", dir).isNotEmpty();

        TranslatorOptions.Builder options = TranslatorOptions.builder()
                .addSource(dir.resolve("src"))
                .outputDir(work.resolve("rust"))
                .crateName(name)
                .runtimePath(Cases.repoRoot().resolve("runtime/jrt"));
        classpath.forEach(options::addClasspath);
        Translator.Result tr = Translator.translate(options.build());
        assertThat(tr.success()).as("translation diagnostics: %s", tr.diagnostics()).isTrue();

        Map<String, String> env = Map.of("CARGO_TARGET_DIR", WORK.resolve("target").toString());
        CargoRunner.Result build = CargoRunner.run(work.resolve("rust"), List.of("test", "--no-run", "--quiet"), null, env);
        assertThat(build.ok()).as("cargo test --no-run failed:\n%s", build.output()).isTrue();
        assertThat(build.output()).as("cargo build must not emit warnings").doesNotContain("warning");
        CargoRunner.Result run = CargoRunner.run(work.resolve("rust"), List.of("test", "--", "--test-threads=1"), null, env);
        Map<String, String> actual = new TreeMap<>();
        Matcher m = RESULT.matcher(run.output());
        while (m.find()) {
            String module = m.group(1).substring(m.group(1).lastIndexOf("::") + 2);
            actual.put(module + "::" + m.group(2), m.group(3));
        }
        assertThat(actual).as("cargo test output:\n%s", run.output()).isEqualTo(expected);
    }

    private static Path jarOf(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** テストクラスをコンパイルして JUnit Platform で実行し、テストごとの結果を「モジュール名::関数名」で返す。 */
    private static Map<String, String> runOnJvm(Path src, Path classes, List<Path> classpath) throws Exception {
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp",
                String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList())));
        List<Path> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        files.forEach(f -> args.add(f.toString()));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).as("javac").isZero();

        Map<String, String> results = new TreeMap<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, JunitTest.class.getClassLoader())) {
            LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
            for (Path f : files) {
                String rel = src.relativize(f).toString().replace(java.io.File.separatorChar, '.');
                Class<?> c = loader.loadClass(rel.substring(0, rel.length() - ".java".length()));
                request.selectors(selectClass(c));
            }
            Launcher launcher = LauncherFactory.create();
            launcher.execute(request.build(), new TestExecutionListener() {
                @Override
                public void executionSkipped(TestIdentifier id, String reason) {
                    record(id, "ignored");
                }

                @Override
                public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                    record(id, result.getStatus() == TestExecutionResult.Status.FAILED ? "FAILED" : "ok");
                }

                private void record(TestIdentifier id, String status) {
                    if (id.isTest() && id.getSource().orElse(null) instanceof MethodSource ms) {
                        String cls = ms.getClassName().substring(ms.getClassName().lastIndexOf('.') + 1);
                        results.put(Naming.moduleName(cls) + "::" + Naming.valueName(ms.getMethodName()), status);
                    }
                }
            });
        }
        return results;
    }
}
