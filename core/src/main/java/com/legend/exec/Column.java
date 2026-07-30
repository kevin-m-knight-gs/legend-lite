package com.legend.exec;

import com.legend.compiler.element.type.Type;

/**
 * One result column: the name, an informational SQL type name, and the PURE
 * type — the OBJECT, not a name string: consumers (PCT, serializers, the
 * QueryService bridge) convert values by this type and never sniff SQL types.
 */
public record Column(String name, @com.legend.Nullable String sqlType, Type pureType) {
}
