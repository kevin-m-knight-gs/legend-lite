package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code Class.all()} object-graph source (engine {@code TypedGetAll}) &mdash;
 * {@code getAll<T>(Class<T>[1] [, Date[1] [, Date[1]]]):T[*]}, the anchor node
 * store lowering resolves a mapping against. Checked generically ({@code T}
 * binds from the {@code Class<T>} reference); a missing mapping is NOT a type
 * error &mdash; compile succeeds, the back-end surfaces it at the use site
 * (engine's compile-vs-link split). The optional milestoning dates ride along.
 *
 * @param classFqn     the source class, fully qualified
 * @param milestoning  the business/processing date arguments, possibly empty
 * @param versionSweep {@code Class.allVersions()} / {@code
 *                     allVersionsInRange(start, end)} — the VERSION-sweep
 *                     fetch: no dates = every version row unfiltered; two
 *                     dates = versions whose validity window overlaps the
 *                     range (engine getTemporalMilestoneRangeFilter)
 * @param info         {@code ClassType[*]}
 */
public record TypedGetAll(String classFqn, List<TypedSpec> milestoning,
                          boolean versionSweep, boolean forEachDate,
                          ExprType info) implements TypedSpec {
    public TypedGetAll {
        milestoning = List.copyOf(milestoning);
    }
    // NO convenience constructor: a defaulted versionSweep silently turned
    // an allVersionsInRange rebuild into a POINT fetch (remediation T1.1);
    // every construction names every field.

    @Override
    public List<TypedSpec> children() {
        return new ArrayList<>(milestoning);
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        return new TypedGetAll(classFqn, kids, versionSweep, forEachDate, info);
    }
}
