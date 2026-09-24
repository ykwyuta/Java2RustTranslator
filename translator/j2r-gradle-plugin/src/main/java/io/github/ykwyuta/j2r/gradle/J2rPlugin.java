package io.github.ykwyuta.j2r.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * プラグイン {@code io.github.ykwyuta.j2r}。
 *
 * <ul>
 *   <li>{@code translateToRust}: main ソースセットを Rust の Cargo プロジェクトに変換（既定: build/rust）</li>
 *   <li>{@code cargoCheck} / {@code cargoBuild} / {@code cargoRun}: 変換結果に対して cargo を実行</li>
 * </ul>
 */
public class J2rPlugin implements Plugin<Project> {
    public static final String TASK_GROUP = "j2r";
    public static final String CONFIGURATION = "j2rTranslator";

    @Override
    public void apply(Project project) {
        J2rExtension ext = project.getExtensions().create("j2r", J2rExtension.class);
        ext.getCrateName().convention(project.getName());
        ext.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("rust"));
        ext.getMode().convention("faithful");
        ext.getFramework().convention("none");
        ext.getCargoCheck().convention(false);
        ext.getTranslatorVersion().convention(pluginVersion());

        Configuration translator = project.getConfigurations().create(CONFIGURATION, c -> {
            c.setDescription("The Java2RustTranslator command line (j2r-cli) and its dependencies.");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.defaultDependencies(deps -> deps.add(project.getDependencies().create(
                    "io.github.ykwyuta.j2r:j2r-cli:" + ext.getTranslatorVersion().get())));
        });

        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        TaskProvider<TranslateToRustTask> translate = project.getTasks().register("translateToRust", TranslateToRustTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Translates the Java sources into a Rust Cargo project.");
            t.getSources().from(ext.getSources());
            t.getTranslatorClasspath().from(translator);
            t.getMappingDirs().from(ext.getMappingDirs());
            t.getCrateName().set(ext.getCrateName());
            t.getMainClass().set(ext.getMainClass());
            t.getMode().set(ext.getMode());
            t.getFramework().set(ext.getFramework());
            t.getResourceDirs().from(ext.getResourceDirs());
            t.getCargoCheck().set(ext.getCargoCheck());
            t.getOutputDir().set(ext.getOutputDir());
            t.getLauncher().convention(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(21))));
        });

        project.getPlugins().withType(JavaPlugin.class, p -> {
            SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            ext.getSources().from(main.getJava().getSourceDirectories());
            ext.getResourceDirs().from(main.getResources().getSourceDirectories());
            translate.configure(t -> t.getCompileClasspath().from(main.getCompileClasspath()));
        });

        registerCargo(project, ext, translate, "cargoCheck", "Runs 'cargo check' on the translated project.", "check");
        registerCargo(project, ext, translate, "cargoBuild", "Runs 'cargo build --release' on the translated project.", "build", "--release");
        registerCargo(project, ext, translate, "cargoRun", "Runs the translated program with 'cargo run'.", "run", "--quiet");
    }

    private static void registerCargo(Project project, J2rExtension ext, TaskProvider<TranslateToRustTask> translate,
                                      String name, String description, String... cargoArgs) {
        project.getTasks().register(name, Exec.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription(description);
            t.dependsOn(translate);
            t.workingDir(ext.getOutputDir());
            t.executable("cargo");
            t.args((Object[]) cargoArgs);
        });
    }

    static String pluginVersion() {
        try (InputStream in = J2rPlugin.class.getResourceAsStream("j2r-plugin.properties")) {
            if (in == null) {
                throw new IllegalStateException("j2r-plugin.properties not found");
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
