package io.github.ykwyuta.j2r.passes;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.JType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 各メソッドに Rust の関数名を割り当てる。Rust にはオーバーロードがないため、
 * 同名メソッドが複数ある場合のみ引数型から接尾辞を付ける（例: {@code max_i32_i32}, {@code max_f64_f64}）。
 */
public final class MangleOverloads implements Pass {
    @Override
    public String name() {
        return "MangleOverloads";
    }

    @Override
    public Decl.Program run(Decl.Program program) {
        List<Decl.CompilationUnit> units = new ArrayList<>();
        for (Decl.CompilationUnit u : program.units()) {
            List<Decl.TypeDecl> types = new ArrayList<>();
            for (Decl.TypeDecl t : u.types()) {
                types.add(t.withMethods(mangle(t.methods())));
            }
            units.add(new Decl.CompilationUnit(u.packageName(), u.sourcePath(), types));
        }
        return new Decl.Program(units);
    }

    private static List<Decl.MethodDecl> mangle(List<Decl.MethodDecl> methods) {
        Map<String, Integer> counts = new HashMap<>();
        for (Decl.MethodDecl m : methods) {
            counts.merge(m.name(), 1, Integer::sum);
        }
        Set<String> used = new HashSet<>();
        List<Decl.MethodDecl> out = new ArrayList<>();
        for (Decl.MethodDecl m : methods) {
            String base = Naming.toSnakeCase(m.name());
            String name = base;
            if (counts.get(m.name()) > 1) {
                StringBuilder sb = new StringBuilder(base);
                for (Decl.Param p : m.params()) {
                    sb.append('_').append(suffix(p.type()));
                }
                if (m.params().isEmpty()) {
                    sb.append("_0");
                }
                name = sb.toString();
            }
            String unique = name;
            for (int i = 2; !used.add(unique); i++) {
                unique = name + "_" + i;
            }
            out.add(m.withRustName(Naming.escape(unique)));
        }
        return out;
    }

    static String suffix(JType t) {
        return switch (t) {
            case JType.Primitive p -> switch (p.kind()) {
                case BOOLEAN -> "bool";
                case BYTE -> "i8";
                case SHORT -> "i16";
                case CHAR -> "char";
                case INT -> "i32";
                case LONG -> "i64";
                case FLOAT -> "f32";
                case DOUBLE -> "f64";
            };
            case JType.ArrayType a -> suffix(a.component()) + "_array";
            case JType.ClassType c -> Naming.toSnakeCase(c.qualifiedName().substring(c.qualifiedName().lastIndexOf('.') + 1));
            default -> "x";
        };
    }
}
