package io.github.ykwyuta.j2r.jir;

import io.github.ykwyuta.j2r.common.SourcePos;
import java.util.List;

/** JIR の宣言。 */
public final class Decl {
    private Decl() {}

    /** 変換単位全体。 */
    public record Program(List<CompilationUnit> units) {
        public Program {
            units = List.copyOf(units);
        }

        public java.util.stream.Stream<TypeDecl> types() {
            return units.stream().flatMap(u -> u.types().stream());
        }
    }

    /** packageName はデフォルトパッケージなら空文字列。 */
    public record CompilationUnit(String packageName, String sourcePath, List<TypeDecl> types) {
        public CompilationUnit {
            types = List.copyOf(types);
        }
    }

    public record TypeDecl(String simpleName, String qualifiedName, String packageName,
                           List<FieldDecl> fields, List<MethodDecl> methods, String javadoc, SourcePos pos) {
        public TypeDecl {
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }

        public TypeDecl withMethods(List<MethodDecl> newMethods) {
            return new TypeDecl(simpleName, qualifiedName, packageName, fields, newMethods, javadoc, pos);
        }

        public TypeDecl withFields(List<FieldDecl> newFields) {
            return new TypeDecl(simpleName, qualifiedName, packageName, newFields, methods, javadoc, pos);
        }
    }

    /**
     * static フィールド。constantValue はコンパイル時定数の値（なければ null）。
     */
    public record FieldDecl(String name, JType type, boolean isFinal, Expr init, Object constantValue,
                            boolean isPublic, String javadoc, SourcePos pos) {}

    public record Param(String name, JType type) {}

    /**
     * static メソッド。rustName は MangleOverloads パスが設定する（それまでは null）。
     */
    public record MethodDecl(MethodRef ref, List<Param> params, Stmt.Block body, boolean isPublic,
                             boolean isMain, String rustName, String javadoc, SourcePos pos) {
        public MethodDecl {
            params = List.copyOf(params);
        }

        public String name() {
            return ref.name();
        }

        public MethodDecl withRustName(String n) {
            return new MethodDecl(ref, params, body, isPublic, isMain, n, javadoc, pos);
        }

        public MethodDecl withBody(Stmt.Block b) {
            return new MethodDecl(ref, params, b, isPublic, isMain, rustName, javadoc, pos);
        }
    }
}
