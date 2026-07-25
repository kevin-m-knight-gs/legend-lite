package com.legend.compiler.element;

/**
 * The temporal-stereotype lookup shared by the type checker (milestoned
 * property functions) and the store resolver (temporal fetch/join filters)
 * &mdash; engine {@code milestoningCanSupportTemporalStrategy}'s class half.
 */
public final class Temporal {

    /** The GENERATED milestoning member surface real pure adds to
     * temporal classes — businessDate/processingDate (the instance's
     * context date), the milestoning struct, and the struct's own
     * members. ONE registry for query-position typing (Typer) and graph
     * trees (GraphFetchChecker); null when {@code prop} is not generated
     * for {@code classFqn}. */
    public static com.legend.compiler.element.type.ExprType generatedMember(
            ModelContext ctx, String classFqn, String prop) {
        String strat = strategyOf(ctx, classFqn);
        boolean generated = strat != null
                && (prop.equals("businessDate")
                        && (strat.equals("businesstemporal")
                                || strat.equals("bitemporal"))
                || prop.equals("processingDate")
                        && (strat.equals("processingtemporal")
                                || strat.equals("bitemporal")));
        if (generated) {
            return new com.legend.compiler.element.type.ExprType(
                    com.legend.compiler.element.type.Type.Primitive.DATE,
                    com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        }
        if (prop.equals("milestoning") && strat != null) {
            return new com.legend.compiler.element.type.ExprType(
                    new com.legend.compiler.element.type.Type.ClassType(
                            "meta::pure::milestoning::" + (strat
                                    .equals("processingtemporal")
                                    ? "ProcessingDateMilestoning"
                                    : "BusinessDateMilestoning")),
                    com.legend.compiler.element.type.Multiplicity
                            .Bounded.ZERO_ONE);
        }
        if ((classFqn.equals(
                        "meta::pure::milestoning::BusinessDateMilestoning")
                || classFqn.equals(
                        "meta::pure::milestoning::ProcessingDateMilestoning"))
                && java.util.Set.of("from", "thru", "in", "out",
                        "snapshotDate").contains(prop)) {
            // DATE_TIME, not abstract Date: the wire keeps the physical
            // precision (engine milestone columns read back as timestamps)
            return new com.legend.compiler.element.type.ExprType(
                    com.legend.compiler.element.type.Type.Primitive.DATE_TIME,
                    com.legend.compiler.element.type.Multiplicity
                            .Bounded.ZERO_ONE);
        }
        return null;
    }

    private Temporal() {
    }

    /**
     * The class's temporal stereotype ({@code <<temporal.businesstemporal>>}
     * etc., inherited through superclasses), or {@code null} for a
     * non-temporal class.
     */
    public static String strategyOf(ModelContext ctx, String classFqn) {
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        work.add(classFqn);
        while (!work.isEmpty()) {
            String fqn = work.poll();
            if (!seen.add(fqn)) {
                continue;
            }
            var def = ctx.findClassDefinition(fqn).orElse(null);
            if (def != null) {
                for (var st : def.stereotypes()) {
                    if (("temporal".equals(st.profileName())
                            || "meta::pure::profiles::temporal".equals(st.profileName()))
                            && java.util.Set.of("businesstemporal",
                                    "processingtemporal", "bitemporal")
                                    .contains(st.stereotypeName())) {
                        return st.stereotypeName();
                    }
                }
            }
            ctx.findClass(fqn).ifPresent(tc -> work.addAll(tc.superClassFqns()));
        }
        return null;
    }
}
