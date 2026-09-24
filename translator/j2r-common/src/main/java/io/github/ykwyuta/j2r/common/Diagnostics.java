package io.github.ykwyuta.j2r.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 変換全体で共有する診断の収集先。 */
public final class Diagnostics {
    private final List<Diagnostic> items = new ArrayList<>();

    public synchronized void add(Diagnostic d) {
        items.add(d);
    }

    public void report(DiagnosticCode code, SourcePos pos, String message) {
        add(Diagnostic.of(code, pos, message));
    }

    public synchronized List<Diagnostic> all() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public synchronized boolean hasErrors() {
        return items.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    public synchronized long count(Severity severity) {
        return items.stream().filter(d -> d.severity() == severity).count();
    }
}
