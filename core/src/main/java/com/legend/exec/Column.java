package com.legend.exec;

import com.legend.compiler.element.type.Type;

/**
 * One result column: the name, an informational SQL type name, and the
 * PURE type — the OBJECT, not a name string. Consumers SHOULD convert
 * values by pureType and never sniff sqlType — H2Verify does; PCT still
 * sniffs sqlType() at two sites (audit P2/T5) until F5.1 lands. F5.2
 * adds the multiplicity the bridge currently drops.
 */
public record Column(String name, @com.legend.Nullable String sqlType, Type pureType) {
}
