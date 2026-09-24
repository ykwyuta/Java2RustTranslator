package io.github.ykwyuta.j2r.common;

/**
 * 診断コード。体系は docs/02-architecture.md §10 を参照。
 * UNSUPPORTED: 未対応（todo!() を生成）、LOSSY: 意味が変わりうる変換、PERF: 非効率な既定表現。
 */
public enum DiagnosticCode {
    JAVAC_ERROR("J2R-JAVAC", Severity.ERROR),
    UNSUPPORTED_SYNTAX("J2R-UNSUPPORTED-SYNTAX", Severity.WARNING),
    UNSUPPORTED_TYPE("J2R-UNSUPPORTED-TYPE", Severity.WARNING),
    UNSUPPORTED_API("J2R-UNSUPPORTED-API", Severity.WARNING),
    UNSUPPORTED_OOP("J2R-UNSUPPORTED-OOP", Severity.WARNING),
    UNSUPPORTED_EXCEPTION("J2R-UNSUPPORTED-EXCEPTION", Severity.WARNING),
    LOSSY_NULL("J2R-LOSSY-NULL", Severity.WARNING),
    LOSSY_STRING_IDENTITY("J2R-LOSSY-STRING-IDENTITY", Severity.WARNING),
    LOSSY_SURROGATE("J2R-LOSSY-SURROGATE", Severity.WARNING),
    NAME_COLLISION("J2R-NAME-COLLISION", Severity.WARNING),
    NO_MAIN("J2R-NO-MAIN", Severity.INFO),
    CARGO_ERROR("J2R-CARGO", Severity.ERROR),
    INTERNAL("J2R-INTERNAL", Severity.ERROR);

    private final String id;
    private final Severity defaultSeverity;

    DiagnosticCode(String id, Severity defaultSeverity) {
        this.id = id;
        this.defaultSeverity = defaultSeverity;
    }

    public String id() {
        return id;
    }

    public Severity defaultSeverity() {
        return defaultSeverity;
    }
}
