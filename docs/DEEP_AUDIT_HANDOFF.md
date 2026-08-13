# Deep Parser Audit — Handoff

The parser programs that precede this audit are COMPLETE and pushed:
wire burn-down (ledger zero), leniency catalog (executable,
`LeniencyCatalogTest`), dialect quarantine (`legendStrict` total,
`StrictDialectParityTest`), the 4.138.2 oracle re-pin (W10), the
extension close-out, and full PMCD (`PmcdParser.parseDocument`,
`PmcdEquivalenceTest` — since consolidated into `CorpusSweepTest`, 2026-08-12: 5,259/5,259 distinct accepted sources byte-identical — the earlier 8,186 double-counted the snippet tiers).
This document is the audit's charter: what is PROVEN, what is OPEN, the
exact row lists, and the method. Read docs/LENIENCY_CATALOG.md first.

## State of proof (do not re-derive)

- ACCEPT parity: every oracle-accepted corpus source byte-matches —
  26,168 elements (G8 ledger) and 5,259 distinct whole PMCD documents
  (envelope + element order + sectionIndex spans included).
- REJECT parity: all 742 DIALECT rows refuse on the strict surface with
  the engine's own messages (0 leaks), plus engine-parity field/enum
  validations (ownership/functionName/deploymentSchema/deploymentStage/
  asserts/permissionScheme/schema format...).
- The catalog is TOTAL: 1,459 rows = DIALECT 742 + ORACLE-DEFECT 682 +
  VERSION-SKEW 25 + ENGINE-TEST-SCOPED 10; an unclassified row fails the
  build.

## The audit's core question

Bytes prove we match the engine on everything the oracle ADJUDICATES.
The audit proves the remaining direction: **we invented nothing** — every
construct we accept exists in a reference grammar (engine checkout or
legend-pure), and every acceptance beyond the engine is a NAMED
pure-dialect feature, never an accident.

## Leg 1 — the unverified-construct worklist (exact rows, one by one)

Three row sets share one epistemic hole: the oracle produced NO usable
verdict (bare message or crash) and our STRICT surface accepts, so no
reference has confirmed the construct. Regenerate the lists any time:

- **25 VERSION-SKEW rows** — `LeniencyCatalogTest` report
  (`target/leniency-catalog.txt`, class VERSION-SKEW-grammar). CAUTION,
  already discovered: 13 of the 25 are legend-PURE-side sources (9
  `legend-pure-dsl-tds` TestTDSDSLCompilation snippets, testModel.pure,
  TestDefaultValue#41, TestFunctionTester#2 ×2) — for those,
  "checkout-unreleased ENGINE grammar" is the WRONG story and the label
  is provisional.
- **320 ORACLE-DEFECT rows our strict surface ACCEPTS** — run
  `ZDefectResidueProbe` (prints the verdict census; the accept rows are
  the worklist). All are engine-embedded legend-pure stdlib
  (`src/main/resources/core/pure/**`) or legend-pure checkout files.
- **54 ORACLE-DEFECT rows strict-refusing with a NON-dialect message**
  (`strict-refuses:other` in the same probe) — verify each refusal is a
  principled one (unknown section, required field), not an accident.

METHOD (proven on min.pure and the W10 residue):
1. Bisect the file against the RELEASE oracle to the choking line
   (prefix-parse loop — see `ZOneOffProbe` for the pattern).
2. Name the construct at that line.
3. Adjudicate against THREE references:
   - engine checkout `.g4` grammars (73 files) → true VERSION-SKEW; keep,
     re-adjudicates at the next oracle bump;
   - legend-pure grammar/sources → PURE-DIALECT; move the row to a
     DIALECT class AND add a `legendStrict()` gate for the construct
     (engine-verbatim message where one exists; `StrictDialectParityTest`
     enforces);
   - NEITHER → a lite-only acceptance: a bug; fix the parser to refuse
     (both surfaces) unless a corpus gate proves the platform needs it.
4. No sampling. Every row gets a named construct in the catalog.

## Leg 2 — known open quarantine edges

- m2 instance forms (`^X(...)`) and friends inside the 320 accept-rows:
  strict currently tolerates them; doctrine says strict refuses (no
  engine message exists to mirror — the oracle only crashes — so a clean
  lite message is acceptable, documented).
- `Mapping`/`Runtime`/etc. declared in `###Pure` sections: the engine
  binds element kinds to their sections; our strict surface accepts them
  anywhere. Decide + gate (the 12 rows that exposed this were absorbed
  into other classes, so build the fixture set from the engine's section
  grammars, not from leniency rows).
  DONE for OUR OWN tests (sections normalization, 2026-08-11):
  `ZSectionNormalizeRewrite` mechanically inserted `###Section` headers
  into 435 sectionless test snippets (1019 headers; leading imports
  replicated into import-aware sections only — Relational/DataSpace
  grammars are `definition: (element)* EOF` and refuse import lines).
  Own-corpus census moved 257→595 oracle-accepted of 776; the residue is
  pinned per-class in `OwnCorpusConformanceTest` and the
  `noSectionlessSnippets` ratchet holds the sectionless population at
  ZERO. Conformance fixes riding the leg: Service `documentation: '';`
  where required, sized `VARCHAR`/`DECIMAL` in Legend tables, Legend-side
  `BOOLEAN` columns → `BIT` (lite types `Bit` as Boolean). Deliberate
  design divergences got NAMED classes: `LITE-DESIGN-inline-association`
  (clean-sheet `Assoc: AssociationMapping { {p,f|...} }`),
  `LITE-DESIGN-sqlite-backend` (`type: SQLite` connections). The
  STRICT-side section-binding decision for user endpoints stays with the
  lenient→strict flip (LAST, after the invention census).
- Block-doc sugar (`'''` before a declaration) is NEWER than the 4.138.2
  release: both sides refuse today; becomes wire work at the next bump.

## Leg 3 — the LITE-ONLY grammar census

Walk every parser arm against BOTH references (engine checkout .g4 +
legend-pure grammar); classify each production ENGINE / PURE-DIALECT /
LITE-ONLY. LITE-ONLY is deleted or explicitly justified+cataloged.
Named hunt targets from the user:
- `get()` inside mappings — lite construct mapping a JSON column to a
  class column;
- the mapping-as-first-class-function surface (mappings realize
  `Class[*]`; model tenet MAPPING_CLEAN_SHEET §1 — check what parser
  surface it implies);
- `Measure~Unit` tilde spelling; quote-unquoting and optional-semicolon
  tolerances; anything the walk can't source to a reference grammar.

## Leg 4 — dedupes (drift risks found during the builds)

- Harness site enumeration: `ParserEquivalence` duplicates core
  `PmcdParser`'s scanners/dispatch — make the harness delegate to core.
- TWO tagged-value parsers: `ElementParser.parseTaggedValue` and
  `TokenStreamCursor.parseDecorations` (the `'''` work touched both).
- Function-descriptor scan is already shared
  (`TokenStreamCursor.parseFunctionDescriptor`) — the model to follow.

## Instruments

- `CorpusSweepTest` (absorbed `PmcdEquivalenceTest` 2026-08-12) — whole-document byte parity; the audit's
  strongest regression net: ANY parse/order/bookkeeping change that
  deviates shows as a document diff.
- `LeniencyCatalogTest` — the total refusal-row classifier (classify()
  consults parseStrict for bare "Unexpected token").
- `StrictDialectParityTest` — dialect rows must strict-refuse.
- `SpiSeamProofTest` ratchets; CodeShape/ErrorShape/DropInSurface
  guardrails (file size, regex ban in parser, endsWith-FQN ban).
- Probes: `ZSkewResidueProbe`, `ZDefectResidueProbe`, `ZOneOffProbe`
  (bisect pattern), `ZPmcdProbe/2` (engine coordinate quirks),
  `ZSectionizeProbe` (does mechanical sectionizing cure a refusal),
  `ZSectionNormalizeRewrite` (the header-inserting source rewriter;
  report-only unless `-Dsectionize.apply=true`, self-verifying via
  independent re-extraction + token equality).

## Standing rules (unchanged)

- Full 8-gate chain green before ANY push
  (`LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine
  LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure
  GATES_LOG=/tmp/gatesX.log caffeinate -i bash tools/allgates.sh`);
  gate 7's pinned budget is run>=348, fail<=1, err<=22.
- The oracle stays PRODUCTION-shaped: never load engine-test-scoped
  jars to make rows adjudicable (see ENGINE-TEST-SCOPED in the catalog).
- Probe-first for any wire claim; corpus gates adjudicate need; no
  sampling on row work; exact FQNs; no new regex in the parser package.
