# The openCypher TCK, as test input

The feature files under `features/` and the named graphs under `graphs/` are the openCypher Technology Compatibility
Kit (https://github.com/opencypher/openCypher, `tck/`), at commit `677cbaf` (2026-03-20), copied unchanged under the
Apache License 2.0 (`LICENSE`, with the attribution notice each feature file carries). They are read by
`core/src/test/java/com/legend/testcases/CypherTck.java` and `tools/neo4j-oracle/snap.py` as the input of the Cypher
parity corpus (docs/CYPHER_STUDY_2026_10_15.md §3.1, §8); nothing here runs them as the TCK's own runner does.
