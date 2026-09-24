package io.github.ykwyuta.j2r.cli;

import io.github.ykwyuta.j2r.common.Diagnostic;
import io.github.ykwyuta.j2r.common.Severity;
import io.github.ykwyuta.j2r.common.TranslatorOptions;
import io.github.ykwyuta.j2r.driver.Translator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** コマンドライン: {@code j2r [options] <sources...>}。引数ファイル（@args.txt）も使える。 */
@Command(name = "j2r", mixinStandardHelpOptions = true, version = "j2r 0.1.0-SNAPSHOT",
        description = "Translates Java source code into a Rust Cargo project.")
public final class Main implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "SOURCE", description = "Java source files or directories.")
    private List<Path> sources = new ArrayList<>();

    @Option(names = {"-o", "--output"}, required = true, description = "Output directory (Cargo project root).")
    private Path output;

    @Option(names = {"-n", "--crate-name"}, defaultValue = "translated", description = "Crate name (default: ${DEFAULT-VALUE}).")
    private String crateName;

    @Option(names = {"-m", "--main-class"}, description = "Fully qualified class whose main method becomes the binary entry point.")
    private String mainClass;

    @Option(names = {"-cp", "--classpath"}, split = "${sys:path.separator}", description = "Classpath needed to compile the sources.")
    private List<Path> classpath = new ArrayList<>();

    @Option(names = "--mapping-dir", description = "Additional directory with API mapping YAML files.")
    private List<Path> mappingDirs = new ArrayList<>();

    @Option(names = "--runtime-path", description = "Reference an existing jrt crate by path instead of vendoring it.")
    private Path runtimePath;

    @Option(names = "--release", defaultValue = "21", description = "Java language level of the sources (default: ${DEFAULT-VALUE}).")
    private int release;

    @Option(names = "--mode", defaultValue = "FAITHFUL", description = "Translation mode: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). IDIOMATIC is not implemented yet and behaves like FAITHFUL.")
    private TranslatorOptions.Mode mode;

    @Option(names = "--cargo-check", description = "Run 'cargo check' on the generated project.")
    private boolean cargoCheck;

    @Option(names = {"-q", "--quiet"}, description = "Only print errors.")
    private boolean quiet;

    @Override
    public Integer call() {
        TranslatorOptions.Builder b = TranslatorOptions.builder()
                .addSources(sources)
                .outputDir(output)
                .crateName(crateName)
                .mainClass(mainClass)
                .mode(mode)
                .javaRelease(release)
                .cargoCheck(cargoCheck);
        classpath.forEach(b::addClasspath);
        mappingDirs.forEach(b::addMappingDir);
        if (runtimePath != null) {
            b.runtimePath(runtimePath);
        }
        Translator.Result r = Translator.translate(b.build());
        for (Diagnostic d : r.diagnostics()) {
            if (!quiet || d.severity() == Severity.ERROR) {
                System.err.println(d);
            }
        }
        if (!r.success()) {
            System.err.println("j2r: translation failed");
            return 1;
        }
        if (!quiet) {
            long warnings = r.diagnostics().stream().filter(d -> d.severity() == Severity.WARNING).count();
            System.err.printf("j2r: wrote %d Rust files to %s (%d warnings)%n", r.files().size(), r.outputDir(), warnings);
        }
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
