package com.legend.tools.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The checkout rule CheckoutLineEndings applies, case by case. CI checks out LF,
 * so the generated-file diff tests there only ever take the pass-through path;
 * these cases are what hold the CRLF path.
 */
class CheckoutLineEndingsTest {

    private static String checkedOut(String committed, String generated) {
        return new String(CheckoutLineEndings.checkedOut(
                committed.getBytes(StandardCharsets.UTF_8), generated.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    @Test
    void anLfCheckoutTakesTheGeneratedBytes() {
        assertEquals("a\nb\n", checkedOut("a\nb\n", "a\nb\n"));
    }

    @Test
    void aCrlfCheckoutTakesCrlfOnEveryLine() {
        assertEquals("a\r\nb\r\n", checkedOut("a\r\nb\r\n", "a\nb\n"));
    }

    @Test
    void aStaleCrlfCopyStillGetsCrlfAndTheDiffTestSeesTheContentChange() {
        assertEquals("a\r\nc\r\nd\r\n", checkedOut("a\r\nb\r\n", "a\nc\nd\n"));
    }

    @Test
    void generatedBytesHoldingACrPassThroughAsGitLeavesSuchAFile() {
        // prelude.pure carries an upstream file's CRLF lines verbatim: git checks it out as committed
        assertEquals("a\nb\r\nc\n", checkedOut("x\r\ny\r\n", "a\nb\r\nc\n"));
    }

    @Test
    void aCommittedCopyWithAnLfLinePassesThrough() {
        assertEquals("a\nb\n", checkedOut("a\r\nb\n", "a\nb\n"));
    }

    @Test
    void aCommittedCopyWithNoLineBreakPassesThrough() {
        assertEquals("a\nb\n", checkedOut("ab", "a\nb\n"));
    }

    @Test
    void aLeadingLfIsNotCrlf() {
        assertFalse(CheckoutLineEndings.everyLineEndsCrlf("\na\r\n".getBytes(StandardCharsets.UTF_8)));
    }
}
