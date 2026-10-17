// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.code.core.CoreUnclassifiedFunctionsCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The unclassified PCT suite on MongoDB (docs/MONGODB_PLAN_2026_10_16.md §6): the SAME tests as the DuckDB lane's, through the
 * same adapter, on the embedded MongoDB 8.0 ({@code LEGENDLITE_PCT_BACKEND=mongodb}, pct/BUILD.bazel), channel A
 * through the plan path (PctPlanRoute: every result crosses as Pure's TDS text), with MongoDB's OWN expected
 * failures -- every one named and pinned by the text it fails with, and a pinned test that starts passing fails the
 * run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_MongoDB_UnclassifiedFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = CoreUnclassifiedFunctionsCodeRepositoryProvider.unclassifiedFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id, no source line.
    // First measured 2026-10-17 (MongoDB 8.0.17, the pinned server; channel A through the plan path, PctPlanRoute).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // MONGODB'S OWN, refused by name
            one("meta::pure::functions::hash::tests::testMD5Hash_Function_1__Boolean_1_", "\"MD5 reached MongoDb, which has no digest operator\""),
            one("meta::pure::functions::hash::tests::testSHA1Hash_Function_1__Boolean_1_", "\"SHA1 reached MongoDb, which has no digest operator\""),
            one("meta::pure::functions::hash::tests::testSHA256Hash_Function_1__Boolean_1_", "\"SHA256 reached MongoDb, which has no digest operator\""),
            one("meta::pure::functions::runtime::currentUserId::testCurrentUserId_Function_1__Boolean_1_", "\"currentUserId reached MongoDb, whose pipeline names no user\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiDigit_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiEmptyChar_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiLower_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiMultiCharString_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiNewline_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiUpper_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::ascii::testAsciiWhitespace_Function_1__Boolean_1_", "\"ascii reached MongoDb, which has no character-code operator\""),
            one("meta::pure::functions::string::tests::base64::testDecodeBase64NoPadding_Function_1__Boolean_1_", "\"DECODE_BASE64 reached MongoDb, which has no base64 operator\""),
            one("meta::pure::functions::string::tests::base64::testDecodeBase64RoundTrip_Function_1__Boolean_1_", "\"DECODE_BASE64 reached MongoDb, which has no base64 operator\""),
            one("meta::pure::functions::string::tests::base64::testDecodeBase64_Function_1__Boolean_1_", "\"DECODE_BASE64 reached MongoDb, which has no base64 operator\""),
            one("meta::pure::functions::string::tests::base64::testEncodeBase64RoundTrip_Function_1__Boolean_1_", "\"ENCODE_BASE64 reached MongoDb, which has no base64 operator\""),
            one("meta::pure::functions::string::tests::base64::testEncodeBase64_Function_1__Boolean_1_", "\"ENCODE_BASE64 reached MongoDb, which has no base64 operator\""),
            one("meta::pure::functions::string::tests::char::testCharDigits_Function_1__Boolean_1_", "\"char reached MongoDb, which has no code-to-character operator\""),
            one("meta::pure::functions::string::tests::char::testCharLower_Function_1__Boolean_1_", "\"char reached MongoDb, which has no code-to-character operator\""),
            one("meta::pure::functions::string::tests::char::testCharUpper_Function_1__Boolean_1_", "\"char reached MongoDb, which has no code-to-character operator\""),
            one("meta::pure::functions::string::tests::char::testEmptyChar_Function_1__Boolean_1_", "\"char reached MongoDb, which has no code-to-character operator\""),
            one("meta::pure::functions::string::tests::char::testNewline_Function_1__Boolean_1_", "\"char reached MongoDb, which has no code-to-character operator\""),
            one("meta::pure::functions::string::tests::char::testSpace_Function_1__Boolean_1_", "\"char reached MongoDb, which has no code-to-character operator\""),
            one("meta::pure::functions::string::tests::jaroWinklerSimilarity::testJaroWinklerSimilarityEqual_Function_1__Boolean_1_", "\"JARO_WINKLER reached MongoDb, which has no string-distance operator\""),
            one("meta::pure::functions::string::tests::jaroWinklerSimilarity::testJaroWinklerSimilarityNotEqual_Function_1__Boolean_1_", "\"JARO_WINKLER reached MongoDb, which has no string-distance operator\""),
            one("meta::pure::functions::string::tests::levenshteinDistance::testLevenshteinDistanceEqual_Function_1__Boolean_1_", "\"LEVENSHTEIN reached MongoDb, which has no string-distance operator\""),
            one("meta::pure::functions::string::tests::levenshteinDistance::testLevenshteinDistanceNotEqual_Function_1__Boolean_1_", "\"LEVENSHTEIN reached MongoDb, which has no string-distance operator\""),
            one("meta::pure::functions::string::tests::regexpReplace::testregexpReplaceAll_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::string::tests::regexpReplace::testregexpReplace_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            // VALUE DIFFERENCES, measured
            one("meta::pure::functions::string::tests::regexpExtract::testRegexpExtractAll_Function_1__Boolean_1_", "\"\nexpected: ['ab', 'cb', 'cb']\nactual:   ['b', 'b', 'b']\""),
            one("meta::pure::functions::string::tests::regexpExtract::testRegexpExtract_Function_1__Boolean_1_", "\"\nexpected: 'ab'\nactual:   'b'\""),
            one("meta::pure::functions::string::tests::regexpIndexOf::testRegexpIndexOf_Function_1__Boolean_1_", "\"\nexpected: 3\nactual:   -1\""),
            one("meta::pure::functions::string::tests::regexpIndexOf::testRegexpIndexOf_NoMatch_Function_1__Boolean_1_", "\"\nexpected: -1\nactual:   0\""),
            one("meta::pure::functions::string::tests::repeatstring::testRepeatStringEmptyString_Function_1__Boolean_1_", "\"\nexpected: ['']\nactual:   []\""),
            // THE READER: a cell typed by its text
            one("meta::pure::functions::string::tests::splitPart::testSplitPartTypicalToken_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            // THE TDS TEXT ROUTE: a value the text does not carry (an empty string, a class instance, a List, a Map, an Any)
            one("meta::pure::functions::string::tests::tolowerfirstcharacter::TestToLowerFirstCharacterEmptyString_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::toupperfirstcharacter::TestToUpperFirstCharacterEmptyString_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\"")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Unclassified", wrapSuite(
                () -> true,
                () -> PureTestBuilderInterpreted.buildPCTTestSuite(reportScope, expectedFailures, adapter),
                () -> false,
                Lists.mutable.empty()));
    }

    @Override
    public MutableList<ExclusionSpecification> expectedFailures() {
        return expectedFailures;
    }

    @Override
    public ReportScope getReportScope() {
        return reportScope;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    @Override
    public String getPlatform() {
        return platform;
    }
}
