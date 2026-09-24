package io.github.ykwyuta.j2r.frontend;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.SourcePos;
import io.github.ykwyuta.j2r.jir.Decl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * javac の Compiler Tree API で入力を解析（parse + 属性付け）し、JIR を構築する。
 * javac がエラーを報告した場合は変換しない（null を返す）。
 */
public final class JavacFrontend {
    private JavacFrontend() {}

    public static Decl.Program run(List<Path> sources, List<Path> classpath, int release, Diagnostics diags) {
        List<Path> files = expandSources(sources);
        if (files.isEmpty()) {
            diags.report(DiagnosticCode.JAVAC_ERROR, SourcePos.UNKNOWN, "no Java source files given");
            return null;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            diags.report(DiagnosticCode.INTERNAL, SourcePos.UNKNOWN, "javac is not available (run on a JDK, not a JRE)");
            return null;
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("-proc:none", "--release", Integer.toString(release), "-Xlint:none"));
            if (!classpath.isEmpty()) {
                options.add("-classpath");
                options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)));
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(null, fm, collector, options, null, units);
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            task.analyze();

            boolean failed = false;
            for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
                if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                    failed = true;
                    String file = d.getSource() == null ? "<javac>" : d.getSource().getName();
                    diags.report(DiagnosticCode.JAVAC_ERROR, new SourcePos(file, d.getLineNumber(), d.getColumnNumber()),
                            d.getMessage(Locale.ROOT));
                }
            }
            if (failed) {
                return null;
            }

            Trees trees = Trees.instance(task);
            Set<String> programTypes = new TreeSet<>();
            for (CompilationUnitTree cu : parsed) {
                for (var t : cu.getTypeDecls()) {
                    if (trees.getElement(trees.getPath(cu, t)) instanceof TypeElement te) {
                        collectTypes(te, programTypes);
                    }
                }
            }
            List<Decl.CompilationUnit> out = new ArrayList<>();
            java.util.Map<String, io.github.ykwyuta.j2r.jir.DeclInfo> info = new java.util.HashMap<>();
            for (CompilationUnitTree cu : parsed) {
                out.add(new JirBuilder(task, trees, cu, programTypes, diags, info).build());
            }
            return new Decl.Program(out, info);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 型とその入れ子の型（メンバー型）の完全修飾名を集める。 */
    private static void collectTypes(TypeElement te, Set<String> out) {
        out.add(te.getQualifiedName().toString());
        for (var e : te.getEnclosedElements()) {
            if (e instanceof TypeElement nested) {
                collectTypes(nested, out);
            }
        }
    }

    /** ディレクトリは配下の *.java に展開する。結果はパス順にソートして決定的にする。 */
    static List<Path> expandSources(List<Path> sources) {
        Set<Path> files = new TreeSet<>();
        for (Path p : sources) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (p.toString().endsWith(".java")) {
                files.add(p);
            }
        }
        return new ArrayList<>(files);
    }
}
