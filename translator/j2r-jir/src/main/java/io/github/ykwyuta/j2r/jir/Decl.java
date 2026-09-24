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

    /** packageName はデフォルトパッケージなら空文字列。types には入れ子の型も含む（外側の型の後ろに並ぶ）。 */
    public record CompilationUnit(String packageName, String sourcePath, List<TypeDecl> types) {
        public CompilationUnit {
            types = List.copyOf(types);
        }
    }

    public enum TypeKind { CLASS, INTERFACE, ENUM, RECORD }

    /**
     * 型の宣言。
     *
     * @param superclass       スーパークラスの完全修飾名（java.lang.Object とインタフェースは null）
     * @param interfaces       直接実装・継承するインタフェース
     * @param allSupertypes    推移的なすべての上位型（JDK の型と java.lang.Object を含む。instanceof 用）
     * @param outer            入れ子の型なら外側の型の完全修飾名
     * @param hasOuterInstance 内部クラス（static でない入れ子のクラス）なら true
     * @param instanceInit     インスタンスフィールドの初期化子とインスタンス初期化ブロック（ソースの順）
     * @param staticInit       static 初期化ブロックがある場合、static フィールドの初期化子とブロック（ソースの順）。なければ null
     * @param enumConstants    enum 定数（enum 以外は空）
     * @param recordComponents record の構成要素（record 以外は空）
     */
    public record TypeDecl(String simpleName, String qualifiedName, String packageName, TypeKind kind, boolean isAbstract,
                           String superclass, List<String> interfaces, List<String> allSupertypes,
                           String outer, boolean hasOuterInstance,
                           List<FieldDecl> fields, List<MethodDecl> methods,
                           List<Stmt> instanceInit, List<Stmt> staticInit,
                           List<EnumConstant> enumConstants, List<Param> recordComponents,
                           String javadoc, SourcePos pos) {
        public TypeDecl {
            interfaces = List.copyOf(interfaces);
            allSupertypes = List.copyOf(allSupertypes);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
            instanceInit = List.copyOf(instanceInit);
            staticInit = staticInit == null ? null : List.copyOf(staticInit);
            enumConstants = List.copyOf(enumConstants);
            recordComponents = List.copyOf(recordComponents);
        }

        public TypeDecl withMethods(List<MethodDecl> newMethods) {
            return new TypeDecl(simpleName, qualifiedName, packageName, kind, isAbstract, superclass, interfaces, allSupertypes,
                    outer, hasOuterInstance, fields, newMethods, instanceInit, staticInit, enumConstants, recordComponents, javadoc, pos);
        }

        public TypeDecl withBodies(List<FieldDecl> newFields, List<MethodDecl> newMethods, List<Stmt> newInstanceInit,
                                   List<Stmt> newStaticInit, List<EnumConstant> newEnumConstants) {
            return new TypeDecl(simpleName, qualifiedName, packageName, kind, isAbstract, superclass, interfaces, allSupertypes,
                    outer, hasOuterInstance, newFields, newMethods, newInstanceInit, newStaticInit, newEnumConstants, recordComponents,
                    javadoc, pos);
        }

        public boolean isInterface() {
            return kind == TypeKind.INTERFACE;
        }

        /** インスタンスを作れる（abstract クラス・インタフェースでない）か。 */
        public boolean isConcrete() {
            return kind != TypeKind.INTERFACE && !isAbstract;
        }
    }

    /**
     * フィールド。constantValue は static final なコンパイル時定数の値（なければ null）。
     */
    public record FieldDecl(String name, JType type, boolean isStatic, boolean isFinal, Expr init, Object constantValue,
                            boolean isPublic, String javadoc, SourcePos pos) {}

    public record Param(String name, JType type) {}

    public enum MethodKind { STATIC, INSTANCE, CONSTRUCTOR }

    /**
     * メソッド・コンストラクタ。
     *
     * @param body      本体（abstract メソッドは null）
     * @param overrides このメソッドがオーバーライド・実装する上位型のメソッド（MethodRef.key()。JDK のメソッドを含む）
     * @param rustName  MangleOverloads パスが設定する Rust の名前（それまでは null）
     */
    public record MethodDecl(MethodRef ref, MethodKind kind, List<Param> params, Stmt.Block body, boolean isPublic,
                             boolean isPrivate, boolean isMain, List<String> overrides, String rustName,
                             String javadoc, SourcePos pos) {
        public MethodDecl {
            params = List.copyOf(params);
            overrides = List.copyOf(overrides);
        }

        public String name() {
            return ref.name();
        }

        public boolean isStatic() {
            return kind == MethodKind.STATIC;
        }

        public boolean isConstructor() {
            return kind == MethodKind.CONSTRUCTOR;
        }

        public boolean isAbstract() {
            return body == null;
        }

        public MethodDecl withRustName(String n) {
            return new MethodDecl(ref, kind, params, body, isPublic, isPrivate, isMain, overrides, n, javadoc, pos);
        }

        public MethodDecl withBody(Stmt.Block b) {
            return new MethodDecl(ref, kind, params, b, isPublic, isPrivate, isMain, overrides, rustName, javadoc, pos);
        }
    }

    /** enum 定数（ordinal はリスト内の位置）。 */
    public record EnumConstant(String name, MethodRef constructor, List<Expr> args, SourcePos pos) {
        public EnumConstant {
            args = List.copyOf(args);
        }
    }
}
