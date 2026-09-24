package io.github.ykwyuta.j2r.passes;

import io.github.ykwyuta.j2r.jir.Decl;

/** JIR → JIR の変換パス。パスは副作用を持たず、新しい Program を返す。 */
public interface Pass {
    String name();

    Decl.Program run(Decl.Program program);
}
