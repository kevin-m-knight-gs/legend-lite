"""
Which grammars legend-lite's parser is graded on, and why.

legend-engine ships 70 grammars declaring 698 distinct typeable keywords. Grading a Pure
parser against all of them is wrong in both directions: it demands GHC pragmas, and it
buries the Domain and Relational gaps that actually matter under a long tail nobody can
reach by typing.

So the denominator is decided by ONE test, applied to each grammar: can a user reach it
by typing into a .pure file? A grammar qualifies if it registers a `###Section` parser, or
an EmbeddedPureParser, or is a sub-grammar of something that does. Everything else is
reached by POSTing to a REST endpoint or by code generation consuming a file from outside
the system -- real functionality, but not this parser's surface.

The evidence for each exclusion is recorded here rather than in a commit message, because
the exclusions are the part a reader should be able to challenge. Each one names the
symbol that was checked.
"""
from __future__ import annotations

# Core Legend surface. The Pure DSL proper plus the sections and sub-grammars any Legend
# user writes: domain, stores, mappings, runtimes, connections, services, tests, data.
# 100% here is the bar for calling the parser rewrite complete.
TIER1 = {
    "DomainLexerGrammar", "RelationalLexerGrammar", "MappingLexerGrammar",
    "RuntimeLexerGrammar", "ServiceLexerGrammar", "ConnectionLexerGrammar",
    "RelationalDatabaseConnectionLexerGrammar", "DiagramLexerGrammar", "DataLexerGrammar",
    "M3LexerGrammar", "CoreLexerGrammar", "GraphFetchTreeLexerGrammar",
    "RelationFunctionMappingLexerGrammar", "AggregationAwareLexerGrammar",
    "PureInstanceClassMappingLexerGrammar", "ModelConnectionLexerGrammar",
    "TextLexerGrammar", "EqualToAssertionLexerGrammar", "EqualToTDSAssertionLexerGrammar",
    "EqualToJsonAssertionLexerGrammar", "EqualToContentPatternLexerGrammar",
    "EqualToJsonContentPatternLexerGrammar", "ExternalFormatDataLexerGrammar",
    "RelationElementsDataLexerGrammar", "RelationalEmbeddedDataLexerGrammar",
    "GenerationSpecificationLexerGrammar", "FileGenerationLexerGrammar",
    "QueryGenerationConfigsLexerGrammar", "PostProcessorLexerGrammar",
    "DataSourceSpecificationLexerGrammar", "AuthenticationStrategyLexerGrammar",
    "RelationalMapperLexerGrammar", "ExternalFormatLexerGrammar", "FlatDataLexerGrammar",
}

# In scope, but NOT core Legend surface, and kept out of TIER1 so the number that gates
# the rewrite is not distorted by 35 GraphQL SDL keywords. Reachable only through an
# EmbeddedPureParser: a user writes `#GQL{ query { firm { id } } }#` in an expression, and
# the Pure parser must lex the delimiters and route the body. Worth covering; not worth
# letting `INPUT_FIELD_DEFINITION` count against "is the Domain grammar complete".
TIER1_EMBEDDED = {"GraphQL"}

# Extension DSLs and vendor connectors. Every one registers a ###Section, so all of it is
# typeable and none of it is out of scope -- but it splits by cost, and the split is worth
# keeping visible because it decides the order of work:
#
#   vendor connectors are flat keyword->field bags (Snowflake, Trino, BigQuery, ...) and
#   go to 100% almost mechanically;
#
#   the extension DSLs are nested grammars needing shaped fixtures (Persistence at 103
#   keywords is larger than Domain and Relational combined).
TIER2_VENDOR = {
    "SnowflakeLexerGrammar", "TrinoLexerGrammar", "BigQueryLexerGrammar",
    "BigQueryFunctionLexerGrammar", "MemSqlLexerGrammar", "MemSqlFunctionLexerGrammar",
    "RedshiftLexerGrammar", "SpannerLexerGrammar", "OracleLexerGrammar",
    "AthenaLexerGrammar", "AuroraLexerGrammar", "DatabricksLexerGrammar",
    "DuckDBLexerGrammar", "DeephavenLexerGrammar", "DeephavenConnectionLexerGrammar",
    "ElasticsearchLexerGrammar", "ElasticsearchConnectionLexerGrammar",
    "MongoDBSchemaLexerGrammar", "MongoDBConnectionLexerGrammar",
    "MongoDBMappingLexerGrammar", "AuthenticationLexerGrammar",
}

TIER2_DSL = {
    "PersistenceLexerGrammar", "PersistenceRelationalLexerGrammar",
    "PersistenceCloudLexerGrammar", "DataSpaceLexerGrammar", "DataQualityLexerGrammar",
    "HostedServiceLexerGrammar", "ServiceStoreLexerGrammar",
    "ServiceStoreConnectionLexerGrammar", "ServiceStoreEmbeddedDataLexerGrammar",
    "FunctionJarLexerGrammar",
}

TIER2 = TIER2_VENDOR | TIER2_DSL

# Out of scope, with the symbol that was checked in each case. None of these registers a
# SectionParser or an EmbeddedPureParser; each was traced to its only non-test caller.
OUT_OF_SCOPE = {
    "HaskellLexer": (
        "GHC. Callers are HaskellGrammarParser, DamlGrammarParser (DAML is Haskell-"
        "derived) and TestGrammar. 71 keywords including VOCURLY -- a virtual layout "
        "token the lexer SYNTHESISES for indentation, which no user can type."
    ),
    "Protobuf3": (
        "Generation only. Its one non-test caller is ProtobufFormatExtension, declared "
        "`implements ExternalFormatSchemaGenerationExtension`, i.e. Pure -> .proto. The "
        "grammar reads .proto files arriving from outside; nothing routes .pure text to "
        "it. Its HTTP entry point lives under .../protobuf/deprecated/generation/api/."
    ),
    "MongoDBQuery": (
        "Three keywords -- true, false, null -- and no caller but its own parser and "
        "tests. NOT MongoDB's Pure surface: MongoDBSchemaLexerGrammar and "
        "MongoDBMappingLexerGrammar do register sections and are in TIER2_VENDOR."
    ),
    "SqlBaseLexer": (
        "The /sql/ REST endpoint for PostgresSQL, reached by POSTing SQL. Also the one "
        "grammar in the repo declaring keywords in case-insensitive fragment style "
        "(`SELECT: S E L E C T;`), so the NAME:'literal' harvest sees 1 of its 323 "
        "tokens. Excluded on reachability; the miscount is recorded so nobody re-adds it "
        "and trusts the 1."
    ),
}

# Embedded Pure parsers -- a construct the keyword census CANNOT see, because the marker
# is punctuation and the harvest drops punctuation by design. There are exactly two, both
# found by enumerating `implements EmbeddedPureParser`. Listed explicitly so the census
# does not read as green over a hole.
EMBEDDED = {
    "#GQL{": "GraphQLEmbeddedPureParser.getParserName() -> \"GQL\"",
    "#>{": "RelationStoreAccessorPureParser.getParserName() -> \">\"",
}


# Which grammars a `###Section` routes to.
#
# This is what lets existing sources count WITHOUT a COVERS declaration, and it is what
# removes the cross-grammar over-count the text search had: `Schema` written in a Service
# section is credited to nothing, because ServiceLexerGrammar does not define it. A section
# is the unit at which legend-engine itself dispatches to a grammar, so splitting on `###`
# and attributing per section is not an approximation of the routing -- it IS the routing.
#
# What stays approximate: sub-grammars nested INSIDE a section share that section's credit,
# so a keyword defined by both MappingLexerGrammar and AggregationAwareLexerGrammar is
# credited to both from one ###Mapping section. Fixtures avoid this by declaring COVERS
# explicitly, which is why fixtures are authoritative and this is the fallback.
SECTION_GRAMMARS = {
    "Pure": {"DomainLexerGrammar", "M3LexerGrammar", "CoreLexerGrammar",
             "GraphFetchTreeLexerGrammar"},
    # GraphQL is deliberately NOT here. Its keywords are only keywords inside `#GQL{...}#`;
    # crediting them from a ###Pure section would score `true` in Pure code as GraphQL
    # coverage. It is reachable only by an explicit fixture COVERS declaration.
    "Relational": {"RelationalLexerGrammar"},
    "Mapping": {"MappingLexerGrammar", "CoreLexerGrammar", "AggregationAwareLexerGrammar",
                "PureInstanceClassMappingLexerGrammar", "RelationFunctionMappingLexerGrammar",
                "RelationalLexerGrammar", "GraphFetchTreeLexerGrammar", "M3LexerGrammar",
                "MongoDBMappingLexerGrammar", "ServiceStoreLexerGrammar"},
    "Runtime": {"RuntimeLexerGrammar", "CoreLexerGrammar"},
    "Connection": {"ConnectionLexerGrammar", "RelationalDatabaseConnectionLexerGrammar",
                   "AuthenticationStrategyLexerGrammar", "DataSourceSpecificationLexerGrammar",
                   "PostProcessorLexerGrammar", "QueryGenerationConfigsLexerGrammar",
                   "ModelConnectionLexerGrammar", "CoreLexerGrammar",
                   "ServiceStoreConnectionLexerGrammar", "MongoDBConnectionLexerGrammar",
                   "DeephavenConnectionLexerGrammar", "ElasticsearchConnectionLexerGrammar"},
    "Service": {"ServiceLexerGrammar", "CoreLexerGrammar", "M3LexerGrammar",
                "EqualToAssertionLexerGrammar", "EqualToTDSAssertionLexerGrammar",
                "EqualToJsonAssertionLexerGrammar", "EqualToContentPatternLexerGrammar",
                "EqualToJsonContentPatternLexerGrammar", "DataLexerGrammar",
                "ExternalFormatDataLexerGrammar", "RelationalEmbeddedDataLexerGrammar",
                "RelationElementsDataLexerGrammar", "GraphFetchTreeLexerGrammar"},
    "Data": {"DataLexerGrammar", "CoreLexerGrammar", "ExternalFormatDataLexerGrammar",
             "RelationalEmbeddedDataLexerGrammar", "RelationElementsDataLexerGrammar",
             "ServiceStoreEmbeddedDataLexerGrammar"},
    "Diagram": {"DiagramLexerGrammar"},
    "Text": {"TextLexerGrammar"},
    "ExternalFormat": {"ExternalFormatLexerGrammar", "FlatDataLexerGrammar"},
    "FileGeneration": {"FileGenerationLexerGrammar"},
    "GenerationSpecification": {"GenerationSpecificationLexerGrammar"},
    "RelationalMapper": {"RelationalMapperLexerGrammar"},
    "Persistence": {"PersistenceLexerGrammar", "PersistenceRelationalLexerGrammar",
                    "PersistenceCloudLexerGrammar"},
    "DataSpace": {"DataSpaceLexerGrammar"},
    "DataQualityValidation": {"DataQualityLexerGrammar"},
    "ServiceStore": {"ServiceStoreLexerGrammar"},
    "HostedService": {"HostedServiceLexerGrammar"},
    "FunctionJar": {"FunctionJarLexerGrammar"},
    "Snowflake": {"SnowflakeLexerGrammar"},
    "BigQuery": {"BigQueryFunctionLexerGrammar"},
    "MemSql": {"MemSqlFunctionLexerGrammar"},
    "Deephaven": {"DeephavenLexerGrammar"},
    "Elasticsearch": {"ElasticsearchLexerGrammar"},
    "MongoDB": {"MongoDBSchemaLexerGrammar"},
}


def tier_of(stem: str) -> str:
    if stem in OUT_OF_SCOPE:
        return "out"
    if stem in TIER1:
        return "1"
    if stem in TIER1_EMBEDDED:
        return "1e"
    if stem in TIER2:
        return "2"
    return "unclassified"
