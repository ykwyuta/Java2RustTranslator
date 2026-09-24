package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.jir.DeclInfo;
import io.github.ykwyuta.j2r.jir.JType;
import io.github.ykwyuta.j2r.jir.MethodRef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java の型を Rust の型（{@link RT}）に対応付ける。参照型は {@code @Nullable}（JSpecify）が付いていれば
 * {@code Option<T>}、付いていなければ null にならない値として扱う（Spring Framework 7 の {@code @NullMarked} の前提）。
 */
final class TypeResolver {
    static final RT.Named CLOCK = new RT.Named("Arc<dyn Clock>", List.of("std::sync::Arc", "crate::clock::Clock"), false,
            RT.Named.Kind.CLOCK, "java.time.Clock");
    static final RT.Named NAIVE_DATE = value("NaiveDate", "chrono::NaiveDate", "java.time.LocalDate");
    static final RT.Named NAIVE_DATE_TIME = value("NaiveDateTime", "chrono::NaiveDateTime", "java.time.LocalDateTime");
    static final RT.Named NAIVE_TIME = value("NaiveTime", "chrono::NaiveTime", "java.time.LocalTime");

    private static final Map<String, RT> BOXES = Map.of(
            "java.lang.Integer", RT.I32, "java.lang.Long", RT.I64, "java.lang.Boolean", RT.BOOL,
            "java.lang.Short", new RT.Prim("i16"), "java.lang.Byte", new RT.Prim("i8"),
            "java.lang.Double", new RT.Prim("f64"), "java.lang.Float", new RT.Prim("f32"),
            "java.lang.Character", new RT.Prim("u16"));

    private static final Set<String> LISTS = Set.of("java.util.List", "java.util.Collection", "java.util.ArrayList",
            "java.lang.Iterable");

    private final SpringModel model;
    private final Map<String, Boolean> copyCache = new HashMap<>();

    TypeResolver(SpringModel model) {
        this.model = model;
    }

    private static RT.Named value(String name, String use, String javaName) {
        return new RT.Named(name, List.of(use), true, RT.Named.Kind.VALUE, javaName);
    }

    /** 宣言 key の型（消去前の型と @Nullable を DeclInfo から読む）。 */
    RT declared(String key, JType erased) {
        DeclInfo info = model.info(key);
        JType t = info.genericType() != null ? info.genericType() : erased;
        return owned(t, info.nullable());
    }

    RT field(Decl.TypeDecl owner, Decl.FieldDecl f) {
        return declared(DeclInfo.fieldKey(owner.qualifiedName(), f.name()), f.type());
    }

    /** フィールド名から（record の構成要素も含む）。見つからなければ null。 */
    RT field(String owner, String name) {
        Decl.TypeDecl t = model.types.get(owner);
        if (t == null) {
            return null;
        }
        for (Decl.FieldDecl f : t.fields()) {
            if (f.name().equals(name) && !f.isStatic()) {
                return field(t, f);
            }
        }
        return null;
    }

    RT returnType(MethodRef m) {
        return declared(DeclInfo.methodKey(m), m.returnType());
    }

    RT param(MethodRef m, int index, JType erased) {
        return declared(DeclInfo.paramKey(m, index), erased);
    }

    /** 所有する値の型。 */
    RT owned(JType t, boolean nullable) {
        RT base = switch (t) {
            case JType.Primitive p -> primitive(p.kind());
            case JType.Void v -> RT.UNIT;
            case JType.ClassType c -> classType(c.qualifiedName(), List.of());
            case JType.Parameterized p -> classType(p.qualifiedName(), p.args());
            case JType.ArrayType a -> new RT.VecT(owned(a.component(), false));
            default -> new RT.Unknown(t.javaName());
        };
        if (t instanceof JType.Primitive || base instanceof RT.Opt || base instanceof RT.Unit || !nullable) {
            return base;
        }
        return new RT.Opt(base);
    }

    static RT primitive(JType.Kind k) {
        return switch (k) {
            case BOOLEAN -> RT.BOOL;
            case BYTE -> new RT.Prim("i8");
            case SHORT -> new RT.Prim("i16");
            case CHAR -> new RT.Prim("u16");
            case INT -> RT.I32;
            case LONG -> RT.I64;
            case FLOAT -> new RT.Prim("f32");
            case DOUBLE -> new RT.Prim("f64");
        };
    }

    private RT classType(String name, List<JType> args) {
        if (name.equals("java.lang.String")) {
            return RT.STR;
        }
        if (BOXES.containsKey(name)) {
            return BOXES.get(name);
        }
        switch (name) {
            case "java.time.LocalDate" -> {
                return NAIVE_DATE;
            }
            case "java.time.LocalDateTime" -> {
                return NAIVE_DATE_TIME;
            }
            case "java.time.LocalTime" -> {
                return NAIVE_TIME;
            }
            case "java.time.Clock" -> {
                return CLOCK;
            }
            case "java.util.Optional" -> {
                return new RT.Opt(args.isEmpty() ? new RT.Unknown("Optional") : owned(args.get(0), false));
            }
            default -> { }
        }
        if (LISTS.contains(name)) {
            return new RT.VecT(args.isEmpty() ? new RT.Unknown("raw List") : owned(args.get(0), false));
        }
        Decl.TypeDecl decl = model.types.get(name);
        SpringModel.Role role = model.role(name);
        if (decl != null && role != null) {
            String rustName = SpringModel.rustTypeName(decl);
            String use = model.rustModule(decl) + "::" + rustName;
            return switch (role) {
                case ENUM -> new RT.Named(rustName, List.of(use), true, RT.Named.Kind.ENUM, name);
                case RECORD -> new RT.Named(rustName, List.of(use), isCopy(decl), RT.Named.Kind.RECORD, name);
                case ENTITY -> new RT.Named(rustName, List.of(use), false, RT.Named.Kind.ENTITY, name);
                case FORM -> new RT.Named(rustName, List.of(use), false, RT.Named.Kind.FORM, name);
                case SERVICE -> new RT.Named(rustName, List.of(use), false, RT.Named.Kind.SERVICE, name);
                case EXCEPTION -> new RT.ErrorValue();
                default -> new RT.Unknown(name);
            };
        }
        return new RT.Unknown(name);
    }

    /** record の構成要素がすべて Copy なら Copy。 */
    boolean isCopy(Decl.TypeDecl record) {
        Boolean cached = copyCache.get(record.qualifiedName());
        if (cached != null) {
            return cached;
        }
        copyCache.put(record.qualifiedName(), false);
        boolean copy = true;
        for (Decl.Param c : record.recordComponents()) {
            RT t = declared(DeclInfo.fieldKey(record.qualifiedName(), c.name()), c.type());
            copy &= t.copy() && !(t instanceof RT.Unknown);
        }
        copyCache.put(record.qualifiedName(), copy);
        return copy;
    }

    /** derive できるトレイト（Default・Eq）を決めるための、型に含まれる型の集合。 */
    static Set<String> kinds(RT t) {
        Set<String> out = new HashSet<>();
        collect(t, out);
        return out;
    }

    private static void collect(RT t, Set<String> out) {
        switch (t) {
            case RT.Prim p -> out.add(p.name());
            case RT.Named n -> out.add(n.kind().name());
            case RT.Opt o -> collect(o.inner(), out);
            case RT.VecT v -> collect(v.elem(), out);
            case RT.Unknown u -> out.add("UNKNOWN");
            default -> out.add(t.getClass().getSimpleName());
        }
    }
}
