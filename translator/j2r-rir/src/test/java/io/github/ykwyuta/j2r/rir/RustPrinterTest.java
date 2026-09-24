package io.github.ykwyuta.j2r.rir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RustPrinterTest {
    private static RExpr p(String s) {
        return new RExpr.Path(s);
    }

    private static String print(RExpr e) {
        return RustPrinter.print(e);
    }

    @Test
    void parenthesizesOnlyWhenPrecedenceRequires() {
        RExpr sum = new RExpr.Binary("+", p("a"), p("b"));
        assertThat(print(new RExpr.Binary("*", sum, p("c")))).isEqualTo("(a + b) * c");
        assertThat(print(new RExpr.Binary("+", p("c"), new RExpr.Binary("*", p("a"), p("b"))))).isEqualTo("c + a * b");
        // 左結合: a - (b - c) は括弧が必要、(a - b) - c は不要
        assertThat(print(new RExpr.Binary("-", p("a"), new RExpr.Binary("-", p("b"), p("c"))))).isEqualTo("a - (b - c)");
        assertThat(print(new RExpr.Binary("-", new RExpr.Binary("-", p("a"), p("b")), p("c")))).isEqualTo("a - b - c");
    }

    @Test
    void comparisonsAreNonAssociative() {
        RExpr cmp = new RExpr.Binary("==", p("a"), p("b"));
        assertThat(print(new RExpr.Binary("==", cmp, p("c")))).isEqualTo("(a == b) == c");
    }

    @Test
    void castBeforeLessThanIsParenthesized() {
        RExpr cast = new RExpr.Cast(p("x"), new RType("i32"));
        assertThat(print(new RExpr.Binary("<", cast, p("y")))).isEqualTo("(x as i32) < y");
        assertThat(print(new RExpr.Binary(">", cast, p("y")))).isEqualTo("x as i32 > y");
    }

    @Test
    void receiversAreAtomic() {
        RExpr neg = new RExpr.Lit("-5i32");
        assertThat(print(new RExpr.MethodCall(neg, "wrapping_abs", List.of()))).isEqualTo("(-5i32).wrapping_abs()");
        RExpr sum = new RExpr.Binary("+", p("a"), p("b"));
        assertThat(print(new RExpr.MethodCall(sum, "abs", List.of()))).isEqualTo("(a + b).abs()");
        assertThat(print(new RExpr.MethodCall(new RExpr.Cast(p("x"), new RType("u32")), "wrapping_shr", List.of(new RExpr.Lit("1")))))
                .isEqualTo("(x as u32).wrapping_shr(1)");
    }

    @Test
    void blockLikeExpressionsAreParenthesizedInOperands() {
        RExpr.Block block = new RExpr.Block(List.of(), p("x"), null, true);
        assertThat(print(new RExpr.Binary("+", block, p("y")))).isEqualTo("({ x }) + y");
        assertThat(print(new RExpr.Assign(p("n"), "=", block))).isEqualTo("n = { x }");
    }
}
