package io.github.ykwyuta.j2r.lowering;

import io.github.ykwyuta.j2r.analysis.ClassHierarchy;
import io.github.ykwyuta.j2r.analysis.ProgramIndex;
import io.github.ykwyuta.j2r.common.Diagnostics;

/** lowering 全体で共有する情報。 */
record LowerContext(ProgramIndex index, ClassHierarchy hierarchy, ApiMappings mappings, TypeMapper types, Diagnostics diags,
                    Throwing throwing) {}
