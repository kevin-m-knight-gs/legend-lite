# Honest debt — the other side of the parity numbers

The 2026-08-12 adversarial self-review ("what are we hiding, compensating
for, or explaining away?"), kept as a tracked ledger. An item leaves this
file by being FIXED or by being an explicitly ARGUED decision with its
argument linked — never by silence.

| # | item | status | remaining |
|---|---|---|---|
| 1 | Allowlist reasons were category stamps, not reviews | PARTLY FIXED — 241 parse-only rows re-reasoned from a probed mechanism (emitter wall, `ZStrictOnlyProbe`); upstream rows re-reasoned with honest epistemics; `#505` burned | **34 document+strict rows still say REVIEW** — the real pre-flip-residue worklist |
| 2 | "Upstream defect" was an unearned label | REFRAMED — `docs/UPSTREAM_DEFECTS.md`: a crash makes the oracle's verdict UNKNOWABLE, not lite-correct; corroboration noted per row | file the two crashes upstream; read `TestModelMapping.java#33` closely; re-adjudicate on next oracle re-pin |
| 3 | Corpus-shaped blind spots (the audit's M04: a branch no corpus file reaches) | STARTED — `OffsetCompositionParityTest`, 5 hand-built live-adjudicated fixtures | identify M04's exact branch from the audit's mutation set; grow the fixture family; the full 52-mutation re-run was SAMPLED (2), not repeated |
| 4 | 5b (shiftSpans in the bridge) and 5d (directional byte proof) survive as compensations | ARGUED DECISIONS — recorded in commit messages `d4a70c00` | 5b dies if the lexer grows native offsets; 5d means 10 files differ from the engine's raw first-pass bytes — the "invisible to consumers" claim is false for raw-diff consumers |
| 5 | **The compiler is uncovered.** Everything this program proves is the PARSER; the model transform is smoke-tested only; compile-stage refusal parity is 5 allowlisted rows and otherwise unmeasured | OPEN — the next PROGRAM, not a harness item | charter compile-stage parity; the audit's words: "drop-in is true of PmcdParser and false of the compiler" |
| 6 | Two-grammar architecture held by call-site convention (lenient `ElementParser.parse` still reachable; `Compiler.java` uses it) | OPEN — call-site census taken, conclusion not drawn | decide per entry point which surface it must use; consider making the lenient surface package-private to lite's own platform loading |
| 7 | Loose threads: stale rejection pin, gate budget, M3 calibration, C6 staleness, moving denominators | PARTLY FIXED — stale pin now NAMED in the report; M3 calibration SELF-MEASURED every run (≥95% gate); manifest pins denominators going forward | GATES.md budget entry from the final timing run; C6 re-harvest cadence undefined |
| 8 | `engine-fixture#505` message anomaly | RESOLVED — the message is the ENGINE's own walker text; the row was a real lite leniency, burned engine-verbatim | — |

Also from the same review, resolved structurally: the oracle-cache
eviction hack and five-way sweep fragmentation — see the one-sweep
harness collapse this ledger rode in with.
