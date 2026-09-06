// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.compiler.spec;

import com.legend.protocol.spec.NewInstance;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.protocol.spec.ValueSpecification;

import java.util.List;

/**
 * The TDS null cell's two spellings at the typer (batch 74): the bare
 * reference {@code TDSNull} in a presence TEST ({@code $v != TDSNull})
 * is the {@code sqlNull()} funnel (Typer.synth); the bare reference as a
 * LIST ELEMENT ({@code [TDSNull, 1, 2]}, tds.pure firstNotNull) is the
 * null-cell VALUE {@code ^TDSNull()} — one element, counted (pure: a
 * TDSNull instance IS a value; the engine's TDSNull is one constant).
 */
final class TdsNullForms {

    private TdsNullForms() {
    }

    static boolean isBareTdsNull(ValueSpecification v) {
        return v instanceof PackageableElementPtr ref
                && (ref.fullPath().equals("TDSNull")
                        || ref.fullPath().equals("meta::pure::tds::TDSNull"));
    }

    /** A collection literal's element: the bare TDSNull becomes the
     * instance; every other element is itself. */
    static ValueSpecification listElement(ValueSpecification v) {
        return isBareTdsNull(v)
                ? new NewInstance("meta::pure::tds::TDSNull", List.of(), List.of(), List.of())
                : v;
    }
}
