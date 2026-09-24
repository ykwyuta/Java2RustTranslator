package io.github.ykwyuta.j2r.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

/** Java ソースを Rust の Cargo プロジェクトに変換する。変換器（j2r-cli）は別 JVM で実行する。 */
@CacheableTask
public abstract class TranslateToRustTask extends DefaultTask {
    static final String CLI_MAIN = "io.github.ykwyuta.j2r.cli.Main";

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** 入力のコンパイルに必要なクラスパス（javac に渡す）。 */
    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

    /** 変換器（j2r-cli とその依存）のクラスパス。 */
    @Classpath
    public abstract ConfigurableFileCollection getTranslatorClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getMappingDirs();

    @Input
    public abstract Property<String> getCrateName();

    @Input
    @Optional
    public abstract Property<String> getMainClass();

    @Input
    public abstract Property<String> getMode();

    @Input
    public abstract Property<Boolean> getCargoCheck();

    @Input
    public abstract Property<String> getFramework();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getResourceDirs();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /** 変換器を動かす JDK（javac が必要なので JRE ではなく JDK）。 */
    @Nested
    public abstract Property<JavaLauncher> getLauncher();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void translate() {
        List<String> args = new ArrayList<>();
        args.add("--output");
        args.add(getOutputDir().get().getAsFile().getAbsolutePath());
        args.add("--crate-name");
        args.add(getCrateName().get());
        args.add("--mode");
        args.add(getMode().get().toUpperCase(java.util.Locale.ROOT));
        if (getMainClass().isPresent()) {
            args.add("--main-class");
            args.add(getMainClass().get());
        }
        if (getCargoCheck().get()) {
            args.add("--cargo-check");
        }
        args.add("--framework");
        args.add(getFramework().get().toUpperCase(java.util.Locale.ROOT));
        for (File d : getResourceDirs().getFiles()) {
            if (d.exists()) {
                args.add("--resources");
                args.add(d.getAbsolutePath());
            }
        }
        if (!getCompileClasspath().isEmpty()) {
            args.add("--classpath");
            args.add(getCompileClasspath().getFiles().stream().map(File::getAbsolutePath)
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        for (File d : getMappingDirs().getFiles()) {
            args.add("--mapping-dir");
            args.add(d.getAbsolutePath());
        }
        List<String> sources = getSources().getFiles().stream().filter(File::exists).map(File::getAbsolutePath).sorted().toList();
        if (sources.isEmpty()) {
            getLogger().warn("j2r: no Java sources to translate");
            return;
        }
        args.addAll(sources);

        // ソースが多いとコマンドラインが長くなるので、picocli の引数ファイル（@file）で渡す。
        File argFile = new File(getTemporaryDir(), "j2r-args.txt");
        try {
            Files.writeString(argFile.toPath(), args.stream().map(TranslateToRustTask::quote).collect(Collectors.joining("\n")) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        getExecOperations().javaexec(spec -> {
            spec.setExecutable(getLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
            spec.setClasspath(getTranslatorClasspath());
            spec.getMainClass().set(CLI_MAIN);
            spec.args("@" + argFile.getAbsolutePath());
        });
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
