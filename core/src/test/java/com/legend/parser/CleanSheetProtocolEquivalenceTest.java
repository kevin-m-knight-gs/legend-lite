// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.model.PackageableElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE CLEAN-SHEET ARM'S ONLY SAFETY NET.
 *
 * <p>{@code MappingEquivalenceTest} runs the legacy parser against
 * protocol+transform over every corpus mapping — and the corpus contains
 * ZERO function-form mappings, because the clean-sheet form is legend-lite's
 * own surface and no legend-engine file uses it. So the corpus differential
 * gives this arm no coverage whatsoever: it reported the same 1,448/0 before
 * and after the arm existed.
 *
 * <p>That is the inverse of the situation everywhere else in this migration,
 * and therefore exactly where a silent gap survives. These cases are the
 * hand-written substitute: the SAME sources the parser's own clean-sheet
 * tests use, required to produce the SAME model through both paths.
 */
class CleanSheetProtocolEquivalenceTest {

    private static void bothPathsAgree(String source) {
        TokenStream ts = Lexer.tokenize(source);
        int at = -1;
        for (int i = 0; i < ts.count(); i++) {
            if (ts.type(i) == com.legend.lexer.TokenType.MAPPING) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            throw new AssertionError("no Mapping element in: " + source);
        }
        PackageableElement viaLegacy = ElementParser.parseMappingAt(ts, at);
        PackageableElement viaProtocol = com.legend.model.MappingFromProtocol
                .toMappingElement(MappingProtocolParser.parse(ts, at, 1));
        assertEquals(viaLegacy, viaProtocol,
                () -> "clean-sheet paths disagree for: " + source);
    }

    @Test
    void relationalFunctionRef() {
        bothPathsAgree("Mapping acme::M ( "
                + "  *acme::Person: Relational { acme::funcs::personMapping } "
                + ")");
    }

    @Test
    void pureFunctionRef() {
        bothPathsAgree("Mapping acme::M ( "
                + "  acme::StaffMember: Pure { acme::funcs::staffMapping } "
                + ")");
    }

    @Test
    void setIdAndExtendsRideTheBinding() {
        bothPathsAgree("Mapping acme::M ( "
                + "  acme::Person[emp] extends [base]: Relational"
                + " { acme::funcs::employeeMapping } "
                + ")");
    }

    @Test
    void associationKindTag() {
        bothPathsAgree("Mapping acme::M ( "
                + "  *acme::Person: Relational { acme::funcs::personMapping } "
                + "  *acme::Firm:   Relational { acme::funcs::firmMapping } "
                + "  acme::Person_Firm: AssociationMapping"
                + " { acme::funcs::personFirmMatch } "
                + ")");
    }

    @Test
    void multipleBindingsKeepOrderAndRootMarkers() {
        bothPathsAgree("Mapping acme::M ( "
                + "  *acme::Person: Relational { acme::funcs::personMapping } "
                + "  acme::Firm:    Relational { acme::funcs::firmMapping } "
                + "  acme::Staff:   Pure       { acme::funcs::staffMapping } "
                + ")");
    }

    /** The INLINE form — a body that is not a lone element pointer, so it
     *  becomes a lambda on the wire and a Realization.Inline in the model. */
    @Test
    void inlineExpressionBody() {
        bothPathsAgree("Mapping acme::M ( "
                + "  *acme::Person: Relational"
                + " { acme::funcs::rows() -> map(r | ^acme::Person(name = $r.NAME)) } "
                + ")");
    }

    /** A legacy body next to the kind tag must STILL take the legacy path —
     *  the disambiguator is shared, so both parsers must answer alike. */
    @Test
    void legacyBodyStillTakesTheLegacyPath() {
        bothPathsAgree("Mapping acme::M ( "
                + "  *acme::Person: Relational { ~mainTable [acme::DB]personTable"
                + "  name: [acme::DB]personTable.NAME } "
                + ")");
    }
}
