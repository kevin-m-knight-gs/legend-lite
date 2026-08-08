// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.model.OpaqueElementDefinition;
import com.legend.model.PackageableElement;

/**
 * THE ONLY legitimate producer of {@link OpaqueElementDefinition}.
 *
 * <p>The opaque carrier exists for one situation: a section owned by an
 * OVERLAY grammar (GRAMMAR_EXTENSIBILITY.md step 3), whose payload core
 * genuinely cannot open because the grammar lives in someone else's jar.
 * Compiling it is the extension's job.
 *
 * <p>It is NOT a hiding place for built-in sections legend-lite has simply
 * not modelled yet. Used that way it converts a loud, accurate parse failure
 * into a silent hole: an element carried opaquely is invisible to
 * {@code findConnection}, to the resolver, and to every compiler phase, so a
 * file "parses" while half of it is unreachable. That mistake was made once —
 * ###Data — and {@code ArchitectureTest.opaqueCarrierIsLockedToTheOverlaySeam}
 * now pins the construction sites so it cannot be made quietly again.
 *
 * <p>Extracting this into its own class is what makes the rule enforceable:
 * the ArchUnit gate can name a single class, rather than trusting a comment
 * inside the section dispatcher.
 */
final class OverlayElementSink implements com.legend.spi.ElementSink {

    private final String sectionName;
    private final java.util.List<PackageableElement> out;

    OverlayElementSink(String sectionName, java.util.List<PackageableElement> out) {
        this.sectionName = sectionName;
        this.out = out;
    }

    @Override
    public void accept(String qualifiedName, String protocolJson) {
        out.add(new OpaqueElementDefinition(qualifiedName, sectionName,
                protocolJson));
    }
}
