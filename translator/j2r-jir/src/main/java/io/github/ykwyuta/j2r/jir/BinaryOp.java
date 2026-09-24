package io.github.ykwyuta.j2r.jir;

public enum BinaryOp {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), REM("%"),
    SHL("<<"), SHR(">>"), USHR(">>>"),
    LT("<"), GT(">"), LE("<="), GE(">="), EQ("=="), NE("!="),
    BIT_AND("&"), BIT_XOR("^"), BIT_OR("|"),
    AND("&&"), OR("||");

    private final String symbol;

    BinaryOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isComparison() {
        return switch (this) {
            case LT, GT, LE, GE, EQ, NE -> true;
            default -> false;
        };
    }

    public boolean isShift() {
        return this == SHL || this == SHR || this == USHR;
    }
}
