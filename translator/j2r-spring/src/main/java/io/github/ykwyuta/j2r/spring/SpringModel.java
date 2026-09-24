package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.DiagnosticCode;
import io.github.ykwyuta.j2r.common.Diagnostics;
import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.jir.Expr;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.JirVisitor;
import io.github.ykwyuta.j2r.jir.Stmt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 入力プログラムの型を、Spring / MyBatis での役割で分類する。
 *
 * <ul>
 *   <li>{@code @Mapper} のインタフェース → sqlx の関数のモジュール</li>
 *   <li>{@code @Service} のクラス → トランザクションを張る構造体</li>
 *   <li>それらが使うクラス（エンティティ）・record・enum → 構造体・列挙型</li>
 *   <li>それらが投げる例外 → {@code crate::error::Error} のバリアント</li>
 *   <li>Web 層（{@code @Controller} など）・設定クラス → まだ変換しない（J2R-SPRING-SKIPPED）</li>
 * </ul>
 */
final class SpringModel {
    enum Role { MAPPER, SERVICE, ENTITY, RECORD, ENUM, EXCEPTION, SKIPPED }

    static final String MAPPER = "org.apache.ibatis.annotations.Mapper";
    static final String SERVICE = "org.springframework.stereotype.Service";
    static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    static final String PARAM = "org.apache.ibatis.annotations.Param";
    static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";

    /** まだ変換しない層を表す注釈。 */
    private static final Map<String, String> SKIPPED_STEREOTYPES = Map.of(
            "org.springframework.stereotype.Controller", "@Controller (web layer)",
            "org.springframework.web.bind.annotation.RestController", "@RestController (web layer)",
            "org.springframework.web.bind.annotation.ControllerAdvice", "@ControllerAdvice (web layer)",
            "org.springframework.web.bind.annotation.RestControllerAdvice", "@RestControllerAdvice (web layer)",
            "org.springframework.context.annotation.Configuration", "@Configuration (application setup)",
            SPRING_BOOT_APPLICATION, "@SpringBootApplication (application setup)",
            "org.springframework.stereotype.Component", "@Component");

    /** フレームワーク・ライブラリのパッケージ（テスト用のスタブがソースに含まれていても変換しない）。 */
    private static final List<String> LIBRARY_PACKAGES = List.of(
            "org.springframework.", "org.apache.ibatis.", "org.mybatis.", "org.jspecify.", "jakarta.", "javax.", "java.");

    final Decl.Program program;
    final Map<String, Decl.TypeDecl> types = new LinkedHashMap<>();
    final Map<String, Role> roles = new LinkedHashMap<>();
    /** 変換の基点のパッケージ（この下のパッケージが crate のモジュールになる）。 */
    final String basePackage;
    private final Diagnostics diags;

    SpringModel(Decl.Program program, Diagnostics diags) {
        this.program = program;
        this.diags = diags;
        program.types().filter(t -> LIBRARY_PACKAGES.stream().noneMatch(p -> t.qualifiedName().startsWith(p)))
                .forEach(t -> types.put(t.qualifiedName(), t));
        classify();
        this.basePackage = basePackage();
    }

    // ------------------------------------------------------------------ 分類

    private void classify() {
        Deque<String> work = new ArrayDeque<>();
        for (Decl.TypeDecl t : types.values()) {
            DeclInfo info = program.info(DeclInfo.typeKey(t.qualifiedName()));
            String skipped = SKIPPED_STEREOTYPES.entrySet().stream().filter(e -> info.has(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (skipped != null) {
                roles.put(t.qualifiedName(), Role.SKIPPED);
                diags.report(DiagnosticCode.SPRING_SKIPPED, t.pos(),
                        t.simpleName() + " is not translated: " + skipped + " is not supported yet");
            } else if (t.isInterface() && info.has(MAPPER)) {
                roles.put(t.qualifiedName(), Role.MAPPER);
                work.add(t.qualifiedName());
            } else if (info.has(SERVICE)) {
                roles.put(t.qualifiedName(), Role.SERVICE);
                work.add(t.qualifiedName());
            }
        }
        // Mapper とサービスから使われる型をたどる。
        while (!work.isEmpty()) {
            Decl.TypeDecl t = types.get(work.poll());
            for (String used : referencedTypes(t)) {
                if (roles.containsKey(used) || !types.containsKey(used)) {
                    continue;
                }
                Decl.TypeDecl u = types.get(used);
                Role role = switch (u.kind()) {
                    case ENUM -> Role.ENUM;
                    case RECORD -> Role.RECORD;
                    case INTERFACE -> Role.SKIPPED;
                    case CLASS -> isException(u) ? Role.EXCEPTION : Role.ENTITY;
                };
                if (role == Role.SKIPPED) {
                    diags.report(DiagnosticCode.SPRING_UNSUPPORTED, u.pos(), "interface " + u.simpleName() + " is not supported yet");
                }
                roles.put(used, role);
                work.add(used);
            }
        }
        for (Decl.TypeDecl t : types.values()) {
            if (!roles.containsKey(t.qualifiedName())) {
                roles.put(t.qualifiedName(), Role.SKIPPED);
                if (t.outer() == null) {
                    diags.report(DiagnosticCode.SPRING_SKIPPED, t.pos(),
                            t.simpleName() + " is not translated: it is not used by any @Service or @Mapper");
                }
            }
        }
    }

    private boolean isException(Decl.TypeDecl t) {
        return t.allSupertypes().contains("java.lang.Throwable");
    }

    /** 型の宣言（フィールド・シグネチャ・本体）が参照するプログラム内の型。 */
    private Set<String> referencedTypes(Decl.TypeDecl t) {
        Set<String> out = new TreeSet<>();
        if (t.superclass() != null) {
            out.add(t.superclass());
        }
        for (Decl.FieldDecl f : t.fields()) {
            addType(out, f.type());
            addType(out, program.info(DeclInfo.fieldKey(t.qualifiedName(), f.name())).genericType());
        }
        for (Decl.Param p : t.recordComponents()) {
            addType(out, p.type());
        }
        for (Decl.MethodDecl m : t.methods()) {
            addType(out, m.ref().returnType());
            addType(out, program.info(DeclInfo.methodKey(m.ref())).genericType());
            for (int i = 0; i < m.params().size(); i++) {
                addType(out, m.params().get(i).type());
                addType(out, program.info(DeclInfo.paramKey(m.ref(), i)).genericType());
            }
            if (m.body() != null) {
                JirVisitor.walk(m.body(), s -> {
                    if (s instanceof Stmt.LocalVar lv) {
                        addType(out, lv.type());
                    }
                }, e -> {
                    addType(out, e.type());
                    if (e instanceof Expr.New n) {
                        out.add(n.constructor().owner());
                    } else if (e instanceof Expr.Call c) {
                        out.add(c.method().owner());
                    } else if (e instanceof Expr.StaticField sf) {
                        out.add(sf.owner());
                    }
                });
            }
        }
        out.remove(t.qualifiedName());
        return out;
    }

    private static void addType(Set<String> out, JType t) {
        switch (t) {
            case null -> { }
            case JType.ClassType c -> out.add(c.qualifiedName());
            case JType.Parameterized p -> {
                out.add(p.qualifiedName());
                p.args().forEach(a -> addType(out, a));
            }
            case JType.ArrayType a -> addType(out, a.component());
            default -> { }
        }
    }

    private String basePackage() {
        for (Decl.TypeDecl t : types.values()) {
            if (program.info(DeclInfo.typeKey(t.qualifiedName())).has(SPRING_BOOT_APPLICATION)) {
                return t.packageName();
            }
        }
        String common = null;
        for (Map.Entry<String, Role> e : roles.entrySet()) {
            if (e.getValue() == Role.SKIPPED) {
                continue;
            }
            String pkg = types.get(e.getKey()).packageName();
            common = common == null ? pkg : commonPrefix(common, pkg);
        }
        return common == null ? "" : common;
    }

    private static String commonPrefix(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(x.length, y.length) && x[i].equals(y[i]); i++) {
            out.add(x[i]);
        }
        return String.join(".", out);
    }

    // ------------------------------------------------------------------ 問い合わせ

    Role role(String qualifiedName) {
        return roles.get(qualifiedName);
    }

    boolean is(String qualifiedName, Role role) {
        return roles.get(qualifiedName) == role;
    }

    List<Decl.TypeDecl> typesWith(Role role) {
        return types.values().stream().filter(t -> roles.get(t.qualifiedName()) == role).toList();
    }

    DeclInfo info(String key) {
        return program.info(key);
    }

    /** パッケージに対応するモジュールのパス（crate からの相対。基点のパッケージなら空）。 */
    List<String> modulePath(String packageName) {
        String rest = packageName.equals(basePackage) ? ""
                : basePackage.isEmpty() ? packageName
                : packageName.startsWith(basePackage + ".") ? packageName.substring(basePackage.length() + 1) : packageName;
        List<String> out = new ArrayList<>();
        if (!rest.isEmpty()) {
            for (String seg : rest.split("\\.")) {
                out.add(Naming.moduleName(seg));
            }
        }
        return out;
    }

    /** 型を定義するファイルのモジュール名（クラス名の snake_case）。 */
    static String fileModule(Decl.TypeDecl t) {
        return Naming.moduleName(t.simpleName());
    }

    /** 型のあるモジュールの {@code crate::...} パス（型はモジュールの mod.rs で再公開する）。 */
    String rustModule(Decl.TypeDecl t) {
        List<String> mods = modulePath(t.packageName());
        return mods.isEmpty() ? "crate" : "crate::" + String.join("::", mods);
    }

    /** 型の Rust での名前（入れ子の型は外側の名前を前に付ける）。 */
    static String rustTypeName(Decl.TypeDecl t) {
        return t.outer() == null ? t.simpleName() : t.qualifiedName().substring(t.packageName().length() + 1).replace(".", "");
    }

    /** 例外クラスに対応する Error のバリアント名（末尾の Exception を除く）。 */
    static String errorVariant(String simpleName) {
        String n = simpleName.endsWith("Exception") && simpleName.length() > "Exception".length()
                ? simpleName.substring(0, simpleName.length() - "Exception".length()) : simpleName;
        return n;
    }

    /** 入れ子を含むすべての型の単純名 → 宣言（Mapper XML の resultType の別名解決に使う）。 */
    Map<String, Decl.TypeDecl> bySimpleName() {
        Map<String, Decl.TypeDecl> out = new HashMap<>();
        for (Decl.TypeDecl t : types.values()) {
            out.putIfAbsent(t.simpleName(), t);
        }
        return out;
    }
}
