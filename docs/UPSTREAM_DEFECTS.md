# Upstream defects — engine crashes on input its own grammar parses

Four corpus rows where the legend-engine 4.138.2 oracle **crashes** (not
refuses) while adjudicating. Each is held in `docs/refusal-allowlist.tsv`
with an UPSTREAM DEFECT reason; this file is the claim's evidence and its
honest epistemics.

**What "defect" does and does not mean here.** In every row the engine's
ANTLR grammar *parses* the source and a later stage throws an unhandled
`RuntimeException` — an NPE in a walker, or a `NumberFormatException` in
the unicode-escape reader. A crash is not a verdict: had the bug not
fired, the engine might have *accepted* (making lite's acceptance correct
parity) or *refused* (making it a lite leniency). **The crash makes the
oracle's verdict unknowable for these rows.** Lite's acceptance is
corroborated only where a second reference grammar (legend-pure's
`M3Parser`) accepts the same source — noted per row. None of these has
been verified against an engine version newer than 4.138.2, and none has
been filed upstream yet; both are open actions.

| row | crash | second-reference verdict |
|---|---|---|
| `TestProjectionCompilation.java#38` | `ClassBodyContext.properties()` NPE in the class-projection walker | m3 grammar has a projection rule — corroborated |
| `TestPureRuntimeProjection.java#60` | same NPE, same walker | corroborated (same construct) |
| `TestProfile.java#52` | `NumberFormatException: For input string "sers" under radix 16` — the unicode-escape reader consumes `\users` inside a string literal as `\u` + hex | the escape is legal text by both lite and m3 lexing |
| `TestModelMapping.java#33` | walker crash (`please notify developer` family) | UNVERIFIED — needs a look |

**Reproduce:** each row's id is a corpus source (manifest-pinned); run
`ZOneOffProbe` with `-Dprobe.id=<id>` to see the engine's full exception
chain live.

**Open actions:**
1. Re-adjudicate all four against the newest legend-engine release; if
   fixed upstream, the rows re-classify on the next oracle re-pin.
2. File the projection-walker NPE and the unicode-escape crash upstream
   with the reproducers above.
3. `TestModelMapping.java#33` has not had its crash read closely — do so
   before defending the row.
