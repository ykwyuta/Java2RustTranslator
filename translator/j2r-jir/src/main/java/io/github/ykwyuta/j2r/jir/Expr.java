package io.github.ykwyuta.j2r.jir;

import io.github.ykwyuta.j2r.common.SourcePos;
import java.util.List;

/**
 * JIR の式。すべての式は解決済みの型を持つ。暗黙の型変換は {@link Cast}（implicit=true）として明示される。
 */
public sealed interface Expr {
    JType type();

    SourcePos pos();

    /** リテラル。value は Boolean / Character / Integer / Long / Float / Double / String、または null（型は代入先の型）。 */
    record Literal(Object value, JType type, SourcePos pos) implements Expr {}

    /** {@code this}（内部クラスから外側のインスタンスを指す {@code Outer.this} は outerLevels > 0）。 */
    record This(JType type, int outerLevels, SourcePos pos) implements Expr {}

    /** インスタンスフィールドの参照 {@code receiver.name}（owner はフィールドを宣言したクラス）。 */
    record FieldAccess(Expr receiver, String owner, String name, JType type, SourcePos pos) implements Expr {}

    /** ローカル変数・仮引数の参照。 */
    record Local(String name, JType type, SourcePos pos) implements Expr {}

    /** 変換対象プログラム内の static フィールド、または JDK の static フィールド。 */
    record StaticField(String owner, String name, JType type, SourcePos pos) implements Expr {}

    record Binary(BinaryOp op, Expr left, Expr right, JType operandType, JType type, SourcePos pos) implements Expr {}

    record Unary(UnaryOp op, Expr operand, JType type, SourcePos pos) implements Expr {}

    /** {@code target = value}。target は Local / StaticField / ArrayAccess。 */
    record Assign(Expr target, Expr value, JType type, SourcePos pos) implements Expr {}

    /**
     * 複合代入 {@code target op= value}。Java の意味（{@code target = (T)(target op value)}）を保つため
     * 演算は operandType で行い、結果を target の型に縮小変換する。
     */
    record CompoundAssign(BinaryOp op, Expr target, Expr value, JType operandType, JType type, SourcePos pos) implements Expr {}

    /** {@code ++x}, {@code x++}, {@code --x}, {@code x--}。 */
    record IncDec(boolean increment, boolean prefix, Expr target, JType type, SourcePos pos) implements Expr {}

    record Conditional(Expr condition, Expr whenTrue, Expr whenFalse, JType type, SourcePos pos) implements Expr {}

    /** 型変換。implicit は Java ソースに現れない暗黙の変換（昇格・代入変換）か。 */
    record Cast(Expr expr, JType type, boolean implicit, SourcePos pos) implements Expr {}

    /**
     * メソッド呼び出し。receiver は static 呼び出しでは null。superCall は {@code super.m()}（仮想呼び出ししない）。
     * type はメソッドの消去後の戻り値型（ジェネリクスで具体化された型への変換は、呼び出しを包む Cast で表す）。
     */
    record Call(MethodRef method, Expr receiver, List<Expr> args, boolean superCall, JType type, SourcePos pos) implements Expr {
        public Call {
            args = List.copyOf(args);
        }
    }

    /**
     * インスタンス生成 {@code new C(args)}。jdkThrowable は C が JDK の例外クラスであること、
     * outer は内部クラスの外側のインスタンス（なければ null）。
     */
    record New(MethodRef constructor, List<Expr> args, Expr outer, boolean jdkThrowable, JType type, SourcePos pos) implements Expr {
        public New {
            args = List.copyOf(args);
        }
    }

    /** コンストラクタ本体の先頭の {@code this(...)} / {@code super(...)}。 */
    record CtorCall(boolean isSuper, MethodRef constructor, List<Expr> args, Expr outer, JType type, SourcePos pos) implements Expr {
        public CtorCall {
            args = List.copyOf(args);
        }
    }

    /** {@code expr instanceof T}（{@code instanceof T name} なら binding に変数名）。 */
    record InstanceOf(Expr expr, JType target, String binding, JType type, SourcePos pos) implements Expr {}

    /**
     * ラムダ式（メソッド参照もラムダに正規化する）。
     *
     * @param params     ラムダの仮引数（型は具体化された型）
     * @param body       本体（式ラムダは return 文 1 つのブロックにする）
     * @param sam        実装する関数型インタフェースのメソッド（消去後）
     * @param interfaces instanceof 用のインタフェース名（関数型インタフェースとその上位インタフェース）
     */
    record Lambda(List<Decl.Param> params, Stmt.Block body, MethodRef sam, List<String> interfaces, JType type, SourcePos pos)
            implements Expr {
        public Lambda {
            params = List.copyOf(params);
            interfaces = List.copyOf(interfaces);
        }
    }

    /** switch 式（値は case の式、または yield）。 */
    record SwitchExpr(Expr selector, List<Stmt.SwitchCase> cases, JType type, SourcePos pos) implements Expr {
        public SwitchExpr {
            cases = List.copyOf(cases);
        }
    }

    /**
     * 配列生成。dims は {@code new int[a][b]} の各次元の長さ、initializer は {@code {1, 2}} の要素。
     * どちらか一方のみ非 null/非空。
     */
    record NewArray(JType.ArrayType type, List<Expr> dims, List<Expr> initializer, SourcePos pos) implements Expr {}

    record ArrayAccess(Expr array, Expr index, JType type, SourcePos pos) implements Expr {}

    record ArrayLength(Expr array, JType type, SourcePos pos) implements Expr {}

    /** 文字列連結（DesugarStringConcat パスが二項 + から生成する）。 */
    record StringConcat(List<Expr> parts, JType type, SourcePos pos) implements Expr {
        public StringConcat {
            parts = List.copyOf(parts);
        }
    }

    /** 変換器が扱えない式。lowering で todo!() になる。 */
    record Unsupported(String description, JType type, SourcePos pos) implements Expr {}
}
