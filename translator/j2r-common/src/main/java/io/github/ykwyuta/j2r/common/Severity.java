package io.github.ykwyuta.j2r.common;

public enum Severity {
    ERROR, WARNING, INFO;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
