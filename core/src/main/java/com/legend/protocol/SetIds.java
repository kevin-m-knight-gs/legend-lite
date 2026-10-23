package com.legend.protocol;

/**
 * A class mapping's set id as legend-engine spells it: the declared id, else the class's path with {@code ::} spelled
 * {@code _} (the engine's default). One owner for the parser, which needs an aggregation-aware mapping's outer id, and
 * the model ({@code com.legend.model.SetId}, which delegates here): the parser returns protocol only
 * (docs/PROTOCOL_PROGRAM_2026_10_05.md leg 8), so the rule lives with the records.
 */
public final class SetIds {

    private SetIds() {
    }

    /** The effective id: {@code declared}, else the default derived from {@code fqn}. */
    public static String of(@com.legend.base.Nullable String declared, String fqn) {
        return declared != null && !declared.isEmpty() ? declared : defaultFor(fqn);
    }

    /** The engine's default id for {@code fqn}: {@code ::} spelled {@code _}. */
    public static String defaultFor(String fqn) {
        return fqn.replace("::", "_");
    }
}
