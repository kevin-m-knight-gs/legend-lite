// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.spi;

/** Where a {@link SectionGrammar} delivers its parsed elements. Foreign
 *  elements enter as the OPAQUE carrier variant (step 3) — core indexes,
 *  names and routes them but never looks inside; compiling one is the
 *  extension's job (the plug-in unit is a language MODULE, not a grammar). */
public interface ElementSink {

    /** Deliver one element's FQN with its extension-owned protocol JSON. */
    void accept(String fqn, String protocolJson);
}
