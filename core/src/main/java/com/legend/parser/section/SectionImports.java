// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;

/** Shared {@code import path::*;} parsing for section grammars — engine's
 *  built-in sections are import-aware ({@code ImportAwareCodeSection}), so
 *  every grammar here accepts and records imports the same way. */
final class SectionImports {

    private SectionImports() {
    }

    static String parseImport(TokenStreamCursor c) {
        c.expect(TokenType.IMPORT);
        StringBuilder sb = new StringBuilder();
        sb.append(c.parseIdentifier());
        boolean starred = false;
        while (c.match(TokenType.PATH_SEPARATOR)) {
            sb.append("::");
            if (c.match(TokenType.STAR)) {
                sb.append("*");
                starred = true;
                break;
            }
            sb.append(c.parseIdentifier());
        }
        if (!starred) {
            // both references are star-only (engine `IMPORT packagePath
            // STAR`, pure M3 identical) — see ElementParser's import arm
            throw c.error("an import names a package wildcard — expected"
                    + " '::*' (import " + sb + "::*;)");
        }
        c.expect(TokenType.SEMI_COLON);
        return sb.toString();
    }
}
