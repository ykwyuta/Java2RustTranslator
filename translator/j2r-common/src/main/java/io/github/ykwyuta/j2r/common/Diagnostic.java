package io.github.ykwyuta.j2r.common;

public record Diagnostic(DiagnosticCode code, Severity severity, SourcePos pos, String message) {

    public static Diagnostic of(DiagnosticCode code, SourcePos pos, String message) {
        return new Diagnostic(code, code.defaultSeverity(), pos, message);
    }

    @Override
    public String toString() {
        return pos + ": " + severity.label() + ": [" + code.id() + "] " + message;
    }
}
