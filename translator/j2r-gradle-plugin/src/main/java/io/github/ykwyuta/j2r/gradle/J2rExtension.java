package io.github.ykwyuta.j2r.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/**
 * {@code j2r { ... }} ブロック。
 *
 * <pre>
 * j2r {
 *     crateName.set("hello")                   // 既定: プロジェクト名
 *     mainClass.set("com.example.hello.App")   // 省略時は main メソッドを持つクラスを自動検出
 *     outputDir.set(layout.buildDirectory.dir("rust"))
 * }
 * </pre>
 */
public abstract class J2rExtension {
    /** 変換する Java ソース（既定: main ソースセットの java ディレクトリ）。 */
    public abstract ConfigurableFileCollection getSources();

    /** 生成する crate 名。 */
    public abstract Property<String> getCrateName();

    /** main メソッドを持つクラスの完全修飾名。 */
    public abstract Property<String> getMainClass();

    /** 出力先（Cargo プロジェクトのルート）。 */
    public abstract DirectoryProperty getOutputDir();

    /** 変換モード（faithful / idiomatic）。 */
    public abstract Property<String> getMode();

    /** 追加の API マッピング定義（YAML）のディレクトリ。 */
    public abstract ConfigurableFileCollection getMappingDirs();

    /** 変換後に cargo check を実行するか。 */
    public abstract Property<Boolean> getCargoCheck();

    /** 使用する j2r-cli のバージョン（既定: プラグインと同じ）。 */
    public abstract Property<String> getTranslatorVersion();
}
