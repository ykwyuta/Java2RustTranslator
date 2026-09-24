package io.github.ykwyuta.j2r.common;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 変換の設定。CLI・Gradle プラグイン・テストハーネスから共通で使う。
 *
 * @param sources         入力の Java ソースファイル（ディレクトリを渡した場合は配下の *.java）
 * @param classpath       入力のコンパイルに必要なクラスパス
 * @param outputDir       出力先（Cargo プロジェクトのルート）
 * @param crateName       生成する crate 名
 * @param mainClass       main メソッドを持つクラスの完全修飾名。null なら自動検出
 * @param mode            変換モード
 * @param runtime         ランタイム crate jrt の参照方法
 * @param runtimePath     runtime == PATH のときの jrt のディレクトリ
 * @param mappingDirs     追加の API マッピング定義（YAML）ディレクトリ
 * @param javaRelease     入力 Java の言語レベル（javac --release）
 * @param cargoCheck      出力後に cargo check を実行するか
 * @param framework       入力が使うフレームワーク（SPRING なら Spring Boot + MyBatis の変換規則を使う）
 * @param resourceDirs    リソースのディレクトリ（MyBatis の Mapper XML・schema.sql を探す。framework が SPRING のとき）
 * @param testSources     テストの Java ソース（framework が SPRING のとき、MockMvc の結合テストを Rust のテストにする）
 */
public record TranslatorOptions(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        String crateName,
        String mainClass,
        Mode mode,
        RuntimeDependency runtime,
        Path runtimePath,
        List<Path> mappingDirs,
        int javaRelease,
        boolean cargoCheck,
        Framework framework,
        List<Path> resourceDirs,
        List<Path> testSources) {

    public enum Mode { FAITHFUL, IDIOMATIC }

    /** NONE: JDK だけを使う Java（jrt の上に変換する）。SPRING: Spring Boot + MyBatis（axum / sqlx の構成に変換する）。 */
    public enum Framework { NONE, SPRING }

    /** VENDOR: jrt のソースを出力先にコピーする（既定）。PATH: 指定ディレクトリの jrt を path 依存で参照する。 */
    public enum RuntimeDependency { VENDOR, PATH }

    public TranslatorOptions {
        Objects.requireNonNull(outputDir, "outputDir");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        mappingDirs = List.copyOf(mappingDirs);
        framework = framework == null ? Framework.NONE : framework;
        resourceDirs = resourceDirs == null ? List.of() : List.copyOf(resourceDirs);
        testSources = testSources == null ? List.of() : List.copyOf(testSources);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Path> sources = new ArrayList<>();
        private final List<Path> classpath = new ArrayList<>();
        private final List<Path> mappingDirs = new ArrayList<>();
        private final List<Path> resourceDirs = new ArrayList<>();
        private final List<Path> testSources = new ArrayList<>();
        private Framework framework = Framework.NONE;
        private Path outputDir;
        private String crateName = "translated";
        private String mainClass;
        private Mode mode = Mode.FAITHFUL;
        private RuntimeDependency runtime = RuntimeDependency.VENDOR;
        private Path runtimePath;
        private int javaRelease = 21;
        private boolean cargoCheck;

        public Builder addSource(Path p) { sources.add(p); return this; }
        public Builder addSources(List<Path> ps) { sources.addAll(ps); return this; }
        public Builder addClasspath(Path p) { classpath.add(p); return this; }
        public Builder addMappingDir(Path p) { mappingDirs.add(p); return this; }
        public Builder outputDir(Path p) { outputDir = p; return this; }
        public Builder crateName(String n) { crateName = n; return this; }
        public Builder mainClass(String c) { mainClass = c; return this; }
        public Builder mode(Mode m) { mode = m; return this; }
        public Builder runtimePath(Path p) { runtime = RuntimeDependency.PATH; runtimePath = p; return this; }
        public Builder javaRelease(int r) { javaRelease = r; return this; }
        public Builder cargoCheck(boolean b) { cargoCheck = b; return this; }
        public Builder framework(Framework f) { framework = f; return this; }
        public Builder addResourceDir(Path p) { resourceDirs.add(p); return this; }
        public Builder addTestSource(Path p) { testSources.add(p); return this; }

        public TranslatorOptions build() {
            return new TranslatorOptions(sources, classpath, outputDir, crateName, mainClass, mode,
                    runtime, runtimePath, mappingDirs, javaRelease, cargoCheck, framework, resourceDirs, testSources);
        }
    }
}
