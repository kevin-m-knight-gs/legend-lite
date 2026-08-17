package com.legend.exec;

import com.legend.compiler.element.type.Type;

/**
 * One result column: the name, an informational SQL type name, and the
 * PURE type — the OBJECT, not a name string. Consumers convert values
 * by pureType and never sniff sqlType (§11 close: the two PCT sniff
 * sites the audit named died with F5.1/F5.3B; no {@code .sqlType()}
 * consumer remains — the field is display metadata only). The
 * multiplicity rides since F5.2.
 */
public record Column(String name, @com.legend.Nullable String sqlType,
        Type pureType,
        com.legend.compiler.element.type.@com.legend.Nullable Multiplicity
                multiplicity) {

    /** Pre-F5.2 arity — multiplicity unknown at this construction site
     * (scalar envelopes, pivot-rebuilt schemas). */
    public Column(String name, @com.legend.Nullable String sqlType,
            Type pureType) {
        this(name, sqlType, pureType, null);
    }
}
