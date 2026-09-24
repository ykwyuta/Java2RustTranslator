package io.github.ykwyuta.j2r.driver;

import io.github.ykwyuta.j2r.analysis.ClassHierarchy;
import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.backend.CargoProjectWriter;
import io.github.ykwyuta.j2r.backend.CargoRunner;
import io.github.ykwyuta.j2r.common.Diagnostic;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.common.TranslatorOptions;
import io.github.ykwyuta.j2r.frontend.JavacFrontend;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.lowering.ApiMappings;
import io.github.ykwyuta.j2r.lowering.LoweredCrate;
import io.github.ykwyuta.j2r.lowering.Lowerer;
import io.github.ykwyuta.j2r.passes.PassManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 変換パイプライン全体（docs/02-architecture.md §2）: javac → JIR → 正規化パス → 解析 → lowering → Cargo プロジェクト出力。
 * CLI・Gradle プラグイン・テストハーネスはこのクラスを入口にする。
 */
public final class Translator {
    private Translator() {}

    /**
     * @param success     Cargo プロジェクトを出力できたか（未対応機能の警告があっても true）
     * @param files       出力した src/ 配下のファイル
     */
    public record Result(boolean success, Path outputDir, List<Diagnostic> diagnostics,
                         List<CargoProjectWriter.GeneratedFile> files) {}

    public static Result translate(TranslatorOptions options) {
        Diagnostics diags = new Diagnostics();
        Decl.Program program = JavacFrontend.run(options.sources(), options.classpath(), options.javaRelease(), diags);
        if (program == null) {
            return new Result(false, options.outputDir(), diags.all(), List.of());
        }
        program = PassManager.standard().run(program);
        ProgramIndex index = new ProgramIndex(program);
        ApiMappings mappings = ApiMappings.load(options.mappingDirs());
        ClassHierarchy hierarchy = new ClassHierarchy(index, program);
        LoweredCrate crate = new Lowerer(index, hierarchy, mappings, diags).lower(program, options.mainClass());

        String crateName = Naming.crateName(options.crateName());
        Path runtime = options.runtime() == TranslatorOptions.RuntimeDependency.PATH ? options.runtimePath() : null;
        List<CargoProjectWriter.GeneratedFile> files = new CargoProjectWriter(
                new CargoProjectWriter.Config(options.outputDir(), crateName, runtime), diags)
                .write(crate.files(), crate.modules(), crate.mainModule());

        if (options.cargoCheck()) {
            CargoRunner.Result r = CargoRunner.check(options.outputDir());
            if (!r.ok()) {
                diags.report(DiagnosticCode.CARGO_ERROR, SourcePos.UNKNOWN, "cargo check failed:\n" + r.output().strip());
            }
        }
        writeReport(options.outputDir(), diags.all());
        return new Result(!diags.hasErrors(), options.outputDir(), diags.all(), files);
    }

    /** j2r-report.json: 診断の一覧（CI やエディタ連携で読むため）。 */
    static void writeReport(Path outputDir, List<Diagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder("{\n  \"diagnostics\": [");
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic d = diagnostics.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"code\": ").append(json(d.code().id()))
                    .append(", \"severity\": ").append(json(d.severity().label()))
                    .append(", \"file\": ").append(json(d.pos().file()))
                    .append(", \"line\": ").append(d.pos().line())
                    .append(", \"column\": ").append(d.pos().column())
                    .append(", \"message\": ").append(json(d.message())).append('}');
        }
        sb.append(diagnostics.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("j2r-report.json"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
