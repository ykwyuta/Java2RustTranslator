package io.github.ykwyuta.j2r.passes;

import io.github.ykwyuta.j2r.jir.Decl;
import java.util.List;

/** 正規化パスを定められた順序で実行する。順序の根拠は docs/02-architecture.md §4.2。 */
public final class PassManager {
    private final List<Pass> passes;

    public PassManager(List<Pass> passes) {
        this.passes = List.copyOf(passes);
    }

    public static PassManager standard() {
        return new PassManager(List.of(
                new DesugarEnhancedFor(),
                new DesugarStringConcat(),
                new MangleOverloads()));
    }

    public List<Pass> passes() {
        return passes;
    }

    public Decl.Program run(Decl.Program program) {
        Decl.Program p = program;
        for (Pass pass : passes) {
            p = pass.run(p);
        }
        return p;
    }
}
