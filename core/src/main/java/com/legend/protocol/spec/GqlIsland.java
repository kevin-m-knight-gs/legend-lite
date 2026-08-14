// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.spec;

import java.util.Objects;

/**
 * A {@code #GQL{ query { ... } }#} expression island (the graphQL
 * extension). Wire shape (probe gql-wire 2026-08-14): a
 * {@code classInstance} of type {@code GQL} whose value is the FULL
 * GraphQL AST ({@code executableDocument} / definitions); the span covers
 * the whole literal. The AST carries NO source positions, so the value is
 * rendered to its canonical wire JSON at parse time by
 * {@code com.legend.parser.GqlParser} and carried here verbatim.
 *
 * <p>Carried for parse coverage — legend-lite's compiler refuses it loudly,
 * like {@link SqlIsland}.
 */
public record GqlIsland(String valueJson,
        @com.legend.Nullable com.legend.protocol.SourceInfo pos)
        implements ValueSpecification {

    public GqlIsland {
        Objects.requireNonNull(valueJson, "valueJson");
    }
}
