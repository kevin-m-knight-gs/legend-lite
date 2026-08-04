package com.legend.model;

/**
 * A source span, in legend-engine's coordinate convention.
 *
 * <p><b>1-based</b> lines, <b>1-based</b> start column, and an <b>inclusive</b> end column
 * ({@code charPositionInLine + text.length()}, deliberately no {@code +1}). Reproduced exactly
 * because protocol {@code sourceInformation} must be byte-identical.
 *
 * <p><b>Why it lives in {@code com.legend.model} and not in {@code com.legend.protocol}.</b> Both
 * the parser's AST records and the protocol records carry spans. Putting it in {@code protocol}
 * created a package cycle — {@code model → protocol → model} — which {@code ArchitectureTest}
 * invariants 4 and 6j both rejected. {@code protocol} already depends on {@code model}, so this is
 * the direction that has no cycle.
 */
public record SourceInfo(String sourceId, int startLine, int startColumn,
                         int endLine, int endColumn) {
}
