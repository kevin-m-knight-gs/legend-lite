# SpanOrigin consolidation — Phase 3 structural leg

2026-08-14. Deep-audit #2 §3 counted ~7 coexisting span/offset
mechanisms; every position bug of the 08-14 session traced to one of
them. This charter catalogs them and defines the consolidation target.

## Resolution of the sibling Phase-3 item first

**Island registry: resolved by design.** The expression-island dispatch
(`SpecParser.parseDsl`: `""`/`">"`/`"SQL"`/`"GQL"` + engine-verbatim
refusal) mirrors the ENGINE's own closed set — the engine hardcodes
exactly these kinds in its grammar; its true extension seam is the
SECTION level, and ours already registry-dispatches sections
(`SectionGrammarRegistry`). A map-of-lambdas over four arms adds
indirection without capability; pressure for a fifth kind is detected
by `SurfaceCensusTest`/`FixtureCorpusParityTest` going red, which is
the correct trigger to extend the switch.

## The seven mechanisms (catalog, with owners)

| # | mechanism | where | shape |
|---|---|---|---|
| 1 | Token-index spans | `TokenStreamCursor.spanOf(fromTok, toTok)` | THE primary mechanism; file-absolute; correct by construction. |
| 2 | Island walker-offset reparse | `TokenStreamCursor.shiftIsland` (engine rule: line offset every line, column offset line 1 only) | Used by embedded-connection islands, Mapping island reparses. |
| 3 | Reparse-overshoot constants | `authSpan` (+3), ES connection value (+4), Deephaven close-col +4 | The engine re-lexes value snippets with a column offset that LEAKS into ctx ends; we reproduce the leak as arithmetic on otherwise-correct spans. |
| 4 | Content-anchored island nodes | `PersistenceSectionGrammar.contentAnchoredIslandNode` (span = line after `#{`, ends ONE PAST `}#`) | Persistence-target walker quirk. |
| 5 | Charwise raw scans | graph-fetch tree spans (`wrapGraphFetch`), Diagram `Raw` reader (own line/col tracking) | Sections/content the shared lexer cannot lex. |
| 6 | Synthesized-snippet shifts | legacy `$this.<prop>` service-mapping transform (var 5 cols at ctx start, property at +6); engine's own `"$this." + prop` reparse offsets | Positions invented to match the engine's synthetic reparse. |
| 7 | Buffer-coordinate composition | PMCD whole-document rules (buffer-coordinate span rules, `OffsetCompositionParityTest`) | Section start-line composition over the document. |

## Target

One `SpanOrigin` value (`file | shifted(line,colFirstLine) |
overshoot(delta) | contentAnchored | synthetic(base)`) threaded where
spans are BUILT, so that:

- every mechanism is NAMED at its construction site (no bare `+3`
  arithmetic — `SpanOrigin.overshoot(3)` says which engine quirk);
- composition is one function (`SpanOrigin.compose`) with the engine's
  line/column rule in exactly one place (today `shiftIsland` holds the
  rule but overshoots/anchors are ad-hoc per site);
- `OffsetCompositionParityTest` pins the composed behavior; a new
  grammar picks a named origin instead of inventing mechanism #8.

## Migration order (each step gated by the FULL chain)

1. Introduce `SpanOrigin` + `compose`; port mechanism #3's three
   overshoot sites (auth +3, ES +4, Deephaven +4) — smallest, purely
   mechanical, byte-parity-pinned by existing batteries.
2. Port #2 (`shiftIsland` callers) — the rule already lives in one
   method; wrap it.
3. Port #4 and #6 (persistence anchor, legacy service-mapping
   synthetic) — each has a dedicated battery row.
4. #5 stays charwise (the content is unlexable by design) but its
   span construction adopts the named origin.
5. #7 unchanged (document composition is already centralized in
   PmcdParser); document it as the top-level origin.

Byte parity (6,489 docs + 266 fixtures + 950 mutants) is the safety
net for every step: span consolidation must be a ZERO-diff refactor.
