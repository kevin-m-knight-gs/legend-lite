// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.spi;

/** One {@code ###} section occurrence: the raw content text and where it
 *  sits in the enclosing source (absolute char offsets), mirroring the
 *  engine's {@code SectionSourceCode} walker-offset contract. */
public record SectionSource(String sectionName, String text,
        int startOffset, int endOffset) {
}
