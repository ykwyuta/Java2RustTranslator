package io.github.ykwyuta.j2r.spring;

import io.github.ykwyuta.j2r.common.Naming;
import io.github.ykwyuta.j2r.jir.Decl;
import io.github.ykwyuta.j2r.rir.RExpr;
import io.github.ykwyuta.j2r.rir.RFile;
import io.github.ykwyuta.j2r.rir.RItem;
import io.github.ykwyuta.j2r.rir.RStmt;
import io.github.ykwyuta.j2r.rir.RType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * エンティティ（getter / setter のあるクラス）・record・enum を構造体と列挙型にし、例外クラスを Error のバリアントにする。
 *
 * <ul>
 *   <li>フィールドは pub なフィールドになり、getter / setter は消える（呼び出し側がフィールドを読み書きする）</li>
 *   <li>Mapper の select の結果になる型は {@code sqlx::FromRow} を derive する</li>
 *   <li>enum は {@code values()}・{@code name()}・{@code ordinal()} を持つ Copy な列挙型になる</li>
 * </ul>
 */
final class DomainGenerator {
    private final SpringTranslator tr;

    DomainGenerator(SpringTranslator tr) {
        this.tr = tr;
    }

    RFile generate(Decl.TypeDecl t) {
        SpringModel model = tr.model();
        String module = model.rustModule(t) + "::" + SpringModel.fileModule(t);
        Imports imports = new Imports(module);
        String name = SpringModel.rustTypeName(t);
        imports.defineLocal(name);
        List<RItem> items = new ArrayList<>();
        switch (model.role(t.qualifiedName())) {
            case ENUM -> enumItems(t, name, imports, items);
            case RECORD, ENTITY, FORM -> structItems(t, name, imports, items);
            default -> throw new IllegalArgumentException(t.qualifiedName());
        }
        List<RItem> all = new ArrayList<>(imports.items());
        all.addAll(items);
        String path = String.join("/", model.modulePath(t.packageName()));
        return new RFile((path.isEmpty() ? "" : path + "/") + SpringModel.fileModule(t) + ".rs", SpringTranslator.header(t), List.of(), all);
    }

    // ------------------------------------------------------------------ 構造体

    private void structItems(Decl.TypeDecl t, String name, Imports imports, List<RItem> items) {
        TypeResolver types = tr.types();
        boolean record = t.kind() == Decl.TypeKind.RECORD;
        List<RItem.Field> fields = new ArrayList<>();
        Set<String> kinds = new java.util.HashSet<>();
        boolean copy = true;
        for (Decl.FieldDecl f : t.fields()) {
            if (f.isStatic()) {
                continue;
            }
            RT ft = types.field(t, f);
            kinds.addAll(TypeResolver.kinds(ft));
            copy &= ft.copy();
            if (ft instanceof RT.Unknown u) {
                tr.report(f.pos(), "field type " + u.description());
            }
            fields.add(new RItem.Field("pub", Naming.valueName(f.name()), new RType(ft.text(imports))));
        }
        List<String> derives = new ArrayList<>(List.of("Debug", "Clone"));
        if (record && copy) {
            derives.add("Copy");
        }
        boolean defaultable = !record && kinds.stream().allMatch(k -> Set.of("VALUE", "ENUM", "Str", "i8", "i16", "u16", "i32", "i64", "f32",
                "f64", "bool").contains(k));
        List<RItem> defaultImpl = defaultable && !t.instanceInit().isEmpty() ? defaultWithInitializers(t, name, imports) : null;
        if (defaultable && defaultImpl == null) {
            derives.add("Default");
        }
        derives.add("PartialEq");
        if (kinds.stream().noneMatch(k -> k.equals("f32") || k.equals("f64") || k.equals("UNKNOWN"))) {
            derives.add("Eq");
        }
        if (tr.rowTypes.contains(t.qualifiedName())) {
            derives.add("sqlx::FromRow");
        }
        items.add(new RItem.Struct(SpringTranslator.docs(t.javadoc()), List.of("derive(" + String.join(", ", derives) + ")"), name, fields));
        if (defaultImpl != null) {
            items.addAll(defaultImpl);
        }
        List<RItem> methods = methods(t, imports);
        for (Decl.MethodDecl m : t.methods()) {
            if (m.isConstructor() && !m.params().isEmpty() && !record) {
                tr.report(m.pos(), t.simpleName() + ": constructors with parameters are not supported yet (use the setters)");
            }
        }
        if (tr.model().is(t.qualifiedName(), SpringModel.Role.FORM)) {
            methods.addAll(new FormGenerator(tr).methods(t, imports));
        }
        if (!methods.isEmpty()) {
            items.add(new RItem.Impl(List.of(), name, methods));
        }
    }

    /**
     * フィールドの初期化子（{@code private Priority priority = Priority.MEDIUM;}）→ その値で始まる {@code Default}。
     * 初期化子が {@code this.f = 式;} の形でなければ null（derive する）。
     */
    private List<RItem> defaultWithInitializers(Decl.TypeDecl t, String name, Imports imports) {
        java.util.Map<String, io.github.ykwyuta.j2r.jir.Expr> inits = new java.util.LinkedHashMap<>();
        for (io.github.ykwyuta.j2r.jir.Stmt st : t.instanceInit()) {
            if (st instanceof io.github.ykwyuta.j2r.jir.Stmt.ExprStmt es && es.expr() instanceof io.github.ykwyuta.j2r.jir.Expr.Assign a
                    && a.target() instanceof io.github.ykwyuta.j2r.jir.Expr.FieldAccess fa
                    && fa.receiver() instanceof io.github.ykwyuta.j2r.jir.Expr.This) {
                inits.put(fa.name(), a.value());
            } else {
                tr.report(st.pos(), t.simpleName() + ": only field initializers are supported (not instance initializer blocks)");
                return null;
            }
        }
        BodyLowerer lowerer = new BodyLowerer(tr.model(), tr.types(), tr.plans(), tr.diags(), imports);
        BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, false, new RT.Unknown("default"), false, null, BodyLowerer.Tail.VALUE, null);
        List<RExpr.FieldInit> fields = new ArrayList<>();
        for (Decl.FieldDecl f : t.fields()) {
            if (f.isStatic()) {
                continue;
            }
            RExpr value = inits.containsKey(f.name()) ? lowerer.coerce(lowerer.expr(inits.get(f.name()), ctx), tr.types().field(t, f))
                    : new RExpr.Call(new RExpr.Path("Default::default"), List.of());
            fields.add(new RExpr.FieldInit(Naming.valueName(f.name()), value));
        }
        return List.of(new RItem.Impl(List.of(), "Default for " + name, List.of(new RItem.Fn(
                List.of("フィールドの初期化子の値（初期化子のないフィールドは既定値）。"), List.of(), "", "default", List.of(), new RType("Self"),
                new RExpr.Block(List.of(), new RExpr.StructLit("Self", fields), null, false)))));
    }

    // ------------------------------------------------------------------ enum

    private void enumItems(Decl.TypeDecl t, String name, Imports imports, List<RItem> items) {
        List<RItem.Variant> variants = new ArrayList<>();
        List<RExpr> values = new ArrayList<>();
        List<RExpr.Arm> nameArms = new ArrayList<>();
        List<RExpr.Arm> ordinalArms = new ArrayList<>();
        List<RExpr.Arm> valueOfArms = new ArrayList<>();
        int ordinal = 0;
        for (Decl.EnumConstant c : t.enumConstants()) {
            if (!c.args().isEmpty()) {
                tr.report(c.pos(), t.simpleName() + "." + c.name() + ": enum constants with arguments are not supported yet");
            }
            String v = BodyLowerer.variantName(c.name());
            // 最初の定数を既定値にする（エンティティ・フォームの Default のため。Java では null）。
            variants.add(new RItem.Variant(List.of(), ordinal == 0 ? List.of("default") : List.of(), v));
            values.add(new RExpr.Path("Self::" + v));
            nameArms.add(new RExpr.Arm("Self::" + v, new RExpr.Lit(BodyLowerer.rustString(c.name()))));
            ordinalArms.add(new RExpr.Arm("Self::" + v, new RExpr.Lit(Integer.toString(ordinal++))));
            valueOfArms.add(new RExpr.Arm(BodyLowerer.rustString(c.name()), new RExpr.Path("Some(Self::" + v + ")")));
        }
        valueOfArms.add(new RExpr.Arm("_", new RExpr.Path("None")));
        if (t.fields().stream().anyMatch(f -> !f.isStatic() && !f.name().startsWith("$"))) {
            tr.report(t.pos(), t.simpleName() + ": enums with fields are not supported yet");
        }
        items.add(new RItem.Enum(SpringTranslator.docs(t.javadoc()), List.of("derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)"), name,
                variants));
        List<RItem> methods = new ArrayList<>();
        methods.add(new RItem.Fn(List.of("すべての定数（`values()`）。"), List.of(), "pub", "values", List.of(),
                new RType("[Self; " + values.size() + "]"), new RExpr.Block(List.of(), new RExpr.Array(values), null, false)));
        methods.add(new RItem.Fn(List.of("定数の名前（`name()`）。"), List.of(), "pub", "name", List.of(new RItem.Param("self", false, null)),
                new RType("&'static str"), new RExpr.Block(List.of(), new RExpr.Match(new RExpr.Path("self"), nameArms), null, false)));
        methods.add(new RItem.Fn(List.of("定数の位置（`ordinal()`）。"), List.of(), "pub", "ordinal", List.of(new RItem.Param("self", false, null)),
                new RType("i32"), new RExpr.Block(List.of(), new RExpr.Match(new RExpr.Path("self"), ordinalArms), null, false)));
        methods.add(new RItem.Fn(List.of("名前の定数（`valueOf(name)`。なければ None）。"), List.of(), "pub", "value_of",
                List.of(new RItem.Param("name", false, new RType("&str"))), new RType("Option<Self>"),
                new RExpr.Block(List.of(), new RExpr.Match(new RExpr.Path("name"), valueOfArms), null, false)));
        methods.addAll(methods(t, imports));
        items.add(new RItem.Impl(List.of(), name, methods));
        if (tr.dbEnums().contains(t.qualifiedName())) {
            // MyBatis の EnumTypeHandler と同じく、列の値（文字列）を定数の名前として読む。
            items.add(new RItem.Impl(List.of(), "sqlx::Type<sqlx::Postgres> for " + name, List.of(
                    new RItem.Fn(List.of(), List.of(), "", "type_info", List.of(), new RType("sqlx::postgres::PgTypeInfo"),
                            new RExpr.Block(List.of(), new RExpr.Path("<str as sqlx::Type<sqlx::Postgres>>::type_info()"), null, false)),
                    new RItem.Fn(List.of(), List.of(), "", "compatible", List.of(new RItem.Param("ty", false, new RType("&sqlx::postgres::PgTypeInfo"))),
                            new RType("bool"),
                            new RExpr.Block(List.of(), new RExpr.Path("<str as sqlx::Type<sqlx::Postgres>>::compatible(ty)"), null, false)))));
            String message = "No enum constant " + t.qualifiedName() + ".{name}";
            items.add(new RItem.Impl(List.of(), "<'r> sqlx::Decode<'r, sqlx::Postgres> for " + name, List.of(
                    new RItem.Fn(List.of(), List.of(), "", "decode", List.of(new RItem.Param("value", false, new RType("sqlx::postgres::PgValueRef<'r>"))),
                            new RType("Result<Self, sqlx::error::BoxDynError>"), new RExpr.Block(List.of(
                            new RStmt.Let("name", false, null, new RExpr.Path("<&str as sqlx::Decode<sqlx::Postgres>>::decode(value)?"))),
                            new RExpr.Path("Self::value_of(name).ok_or_else(|| format!(" + BodyLowerer.rustString(message) + ").into())"),
                            null, false)))));
        }
    }

    // ------------------------------------------------------------------ メソッド

    private List<RItem> methods(Decl.TypeDecl t, Imports imports) {
        List<RItem> out = new ArrayList<>();
        BodyLowerer lowerer = new BodyLowerer(tr.model(), tr.types(), tr.plans(), tr.diags(), imports);
        for (Decl.MethodDecl m : t.methods()) {
            Plans.Method plan = tr.plans().methods.get(m.ref().key());
            if (plan == null || m.body() == null) {
                continue;
            }
            List<RItem.Param> params = new ArrayList<>();
            switch (plan.self()) {
                case REF -> params.add(new RItem.Param("&self", false, null));
                case MUT -> params.add(new RItem.Param("&mut self", false, null));
                case VALUE -> params.add(new RItem.Param("self", false, null));
                case NONE -> { }
            }
            BodyLowerer.Ctx ctx = new BodyLowerer.Ctx(t, plan.self() != Plans.SelfKind.NONE, plan.ret(), false, null,
                    BodyLowerer.Tail.VALUE, null);
            for (int i = 0; i < m.params().size(); i++) {
                Decl.Param p = m.params().get(i);
                String rust = Naming.valueName(p.name());
                ctx.declare(p.name(), rust, plan.params().get(i));
                params.add(new RItem.Param(rust, false, new RType(plan.params().get(i).text(imports))));
            }
            RExpr.Block body = lowerer.body(m, ctx);
            RType ret = plan.ret() instanceof RT.Unit ? null : new RType(plan.ret().text(imports));
            out.add(new RItem.Fn(SpringTranslator.docs(m.javadoc()), List.of(), m.isPrivate() ? "" : "pub", plan.rustName(), params,
                    ret, body));
        }
        return out;
    }

    // ------------------------------------------------------------------ Error

    /** 例外クラス → crate::error::Error。 */
    RFile errorFile(Map<String, Plans.ErrorVariant> errors) {
        Imports imports = new Imports("crate::error");
        imports.defineLocal("Error");
        imports.defineLocal("Result");
        List<RItem.Variant> variants = new ArrayList<>();
        for (Map.Entry<String, Plans.ErrorVariant> e : errors.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Plans.ErrorVariant v = e.getValue();
            String fields = v.fields().isEmpty() ? ""
                    : "(" + String.join(", ", v.fields().stream().map(f -> f.text(imports)).toList()) + ")";
            String simple = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
            variants.add(new RItem.Variant(List.of(simple), List.of("error(" + BodyLowerer.rustString(v.message()) + ")"),
                    v.name() + fields));
        }
        variants.add(new RItem.Variant(List.of("データベースのエラー（Spring の DataAccessException に当たる）。"), List.of("error(transparent)"),
                "Database(#[from] sqlx::Error)"));
        List<RItem> items = new ArrayList<>(imports.items());
        items.add(new RItem.Enum(List.of("サービスが返すエラー。例外クラスごとのバリアントを持つ。"), List.of("derive(Debug, thiserror::Error)"),
                "Error", variants));
        items.add(new RItem.TypeAlias(List.of(), true, "Result<T>", new RType("std::result::Result<T, Error>")));
        return new RFile("error.rs", List.of("Exceptions thrown by the services, translated by Java2RustTranslator."), List.of(), items);
    }
}
