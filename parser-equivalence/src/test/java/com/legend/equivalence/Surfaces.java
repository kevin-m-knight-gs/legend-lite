// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.legend.model.ParsedModel;

/**
 * THE THREE SURFACES, each named ONCE for the whole parity harness —
 * this module's job is comparing the levels against the oracle, and
 * these are the levels it compares.
 */
public final class Surfaces {

    private Surfaces() {
    }

    public static ParsedModel platform(String source) {
        return com.legend.parser.ElementParser.parse(source,
                com.legend.parser.Dialect.LEGEND_PLATFORM);
    }

    public static ParsedModel platform(com.legend.lexer.TokenStream tokens) {
        return com.legend.parser.ElementParser.parse(tokens,
                com.legend.parser.Dialect.LEGEND_PLATFORM);
    }

    public static ParsedModel lite(String source) {
        return com.legend.parser.ElementParser.parse(source,
                com.legend.parser.Dialect.LEGEND_LITE);
    }

    public static ParsedModel engine(String source) {
        return com.legend.parser.ElementParser.parse(source,
                com.legend.parser.Dialect.LEGEND_ENGINE);
    }
}
