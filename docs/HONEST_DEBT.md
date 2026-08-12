# Honest debt — the other side of the parity numbers

The 2026-08-12 adversarial self-review ("what are we hiding, compensating
for, or explaining away?"), kept as a tracked ledger. An item leaves this
file by being FIXED or by being an explicitly ARGUED decision with its
argument linked — never by silence.

| # | item | status | remaining |
|---|---|---|---|
| 1 | Allowlist reasons were category stamps, not reviews | **DONE 2026-08-12** — every document-accepting row individually adjudicated: 7 negative-fixture leniencies BURNED (#505 ModelJoin lambda, XStore comma + two-ids, empty graph subtree, trailing tree comma, empty keys, EE runtime-or-components), 20 #TDS + 3 m2-Diagram + 2 m2-Otherwise rows DEFENDED with probed reasons, 4 upstream crashes documented, 1 version-skew suspect tagged for re-pin | Diagram grammar strictness pass (the 3 defended m2-Diagram rows) |
| 2 | "Upstream defect" was an unearned label | REFRAMED — `docs/UPSTREAM_DEFECTS.md`: a crash makes the oracle's verdict UNKNOWABLE, not lite-correct; corroboration noted per row; `#33` read — same walker-NPE family as the projections | file the two crashes upstream; re-adjudicate on next oracle re-pin |
| 3 | Corpus-shaped blind spots (the audit's M04: a branch no corpus file reaches) | **CLOSED 2026-08-12** — M04's branch identified (`TokenStreamCursor.shiftIsland`'s end-column arm: fires only when a span ENDS on line 1 of a re-lexed island slice — single-line islands, absent from the corpus); two single-line-island fixtures added to `OffsetCompositionParityTest` (7 total, live-adjudicated); a scripted mutation sweep of ALL SIX shiftIsland terms ran and every mutation was KILLED, including M04. The original 52-item list was session-local and unrecoverable; the family-complete sweep of the surviving fault's function replaces it as the measured claim | — |
| 4 | 5b (shiftSpans in the bridge) and 5d (directional byte proof) survive as compensations | ARGUED DECISIONS — recorded in commit messages `d4a70c00` | 5b dies if the lexer grows native offsets; 5d means 10 files differ from the engine's raw first-pass bytes — the "invisible to consumers" claim is false for raw-diff consumers |
| 5 | **The compiler is uncovered.** Everything this program proves is the PARSER; the model transform is smoke-tested only; compile-stage refusal parity is 5 allowlisted rows and otherwise unmeasured | OPEN — the next PROGRAM, not a harness item | charter compile-stage parity; the audit's words: "drop-in is true of PmcdParser and false of the compiler" |
| 6 | Two-grammar architecture held by call-site convention (lenient `ElementParser.parse` still reachable; `Compiler.java` uses it) | **CLOSED 2026-08-12** — resolved by the dialect program (collapse `8dbf3e8a` → uniform threading `c07c8a10`): no lenient default exists to reach — every parse names its level, the Compiler is LEGEND_LITE via facades, PLATFORM is confined to a class list that structurally bans compiler entries (`ParserBoundaryArchTest`), and the platform token survives in exactly Pure.java + Dialect.java (guardrail) | — |
| 7 | Loose threads: stale rejection pin, gate budget, M3 calibration, C6 staleness, moving denominators | PARTLY FIXED — stale pin now NAMED in the report; M3 calibration SELF-MEASURED every run (≥95% gate); manifest pins denominators going forward | C6 re-harvest cadence undefined (GATES.md budget entry landed 2026-08-12 with the sweep) |
| 8 | `engine-fixture#505` message anomaly | RESOLVED — the message is the ENGINE's own walker text; the row was a real lite leniency, burned engine-verbatim | — |

| 9 | Parser-internal PLATFORM defaults (added 2026-08-12): `SpecParser.parse(tokens)`, `DatabaseProtocolParser`/`MappingProtocolParser` default overloads, `TokenStreamCursor.dialect()` all default to LEGEND_PLATFORM for legacy-caller compatibility — a defaulted overload already hid one real hole (`databaseElement` parsed Database internals at PLATFORM on the strict surfaces; caught by the user's "why does Database need PLATFORM?" question) | CLOSED 2026-08-12 — every default deleted, `TokenStreamCursor.dialect()` is ABSTRACT (island re-lex cursors inherit the HOST level; the SPI feed is LEGEND_ENGINE, the drop-in seam), whitelist = Pure.java + ElementParser.java + Dialect.java, and `ParserBoundaryArchTest` now guards the package boundary itself | done |

Also from the same review, resolved structurally: the oracle-cache
eviction hack and five-way sweep fragmentation — see the one-sweep
harness collapse this ledger rode in with.

## 10. The quote/eval fold parses in a CHECKER (added 2026-08-12)

`GraphFetchChecker.unwrapCompiledTree` implements
`meta::legend::compileLegendValueSpecification` for string-literal
arguments by CALLING THE PARSER at check time (`TreeLiterals.parseTree`
at LEGEND_ENGINE — the level the engine's own `LegendCompile.java:57`
uses, `PureGrammarParser.parseModel("function a::f():Any[*]{" + code +
"}")`). The LEVEL is now engine-true, but the LAYERING is wrong:
parsing belongs to the parser, and lite needs the fold only because
lowering wants the tree statically ("Java orchestrates, database
executes"). Caught by the user asking why a checker names a dialect at
all; `ParserBoundaryArchTest` sanctions the file with a DEBT note so
the violation can't spread.

**Fix:** CLOSED 2026-08-12, same day — `QuotedTreeCall` (sealed spec
variant): SpecParser folds `compileLegendValueSpecification('<literal>')`
at the quote boundary via `TreeLiterals.foldQuoteEval` (LEGEND_ENGINE,
the level the engine's `LegendCompile` routing implies); wire face =
the original call byte-verbatim (the engine's wire carries the string
opaque too — its native parses at RUNTIME), pipeline face = the parsed
tree; typing/resolution delegate to the original (behavior identical);
`GraphFetchChecker` reads `q.tree()` and left the arch-test sanctioned
list — no checker touches `com.legend.parser` anymore.

