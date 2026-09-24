package io.github.ykwyuta.j2r.jir;

import io.github.ykwyuta.j2r.common.SourcePos;
import java.util.List;

/**
 * JIR の式。すべての式は解決済みの型を持つ。暗黙の型変換は {@link Cast}（implicit=true）として明示される。
 */
public sealed interface Expr {
    JType type();

    SourcePos pos();

    /** リテラル。value は Boolean / Character / Integer / Long / Float / Double / String。 */
    record Literal(Object value, JType type, SourcePos pos) implements Expr {}

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

    /** メソッド呼び出し。receiver は static 呼び出しでは null。 */
    record Call(MethodRef method, Expr receiver, List<Expr> args, JType type, SourcePos pos) implements Expr {
        public Call {
            args = List.copyOf(args);
        }
    }

    /** JDK 等のマッピング対象クラスのインスタンス生成。 */
    record New(MethodRef constructor, List<Expr> args, JType type, SourcePos pos) implements Expr {
        public New {
            args = List.copyOf(args);
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
