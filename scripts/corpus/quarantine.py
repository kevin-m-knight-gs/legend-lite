"""
Known-failing cases, and why.

A corpus that deletes or "fixes" the cases an engine gets wrong is worse than no corpus:
it launders a defect into a passing suite. These cases stay, with their CORRECT
expectations, and are reported as KNOWN-FAIL. `run.py` exits non-zero if a quarantined
case starts passing (the defect is fixed — remove it from here) or if an unquarantined
case fails (a regression).

Nothing may be added here without a minimized reproduction under repro/ and an entry in
docs/UPSTREAM_FINDINGS.md. "It fails and I do not know why" is not a quarantine reason.

A quarantine is PER ENGINE, not per corpus. Sharing one list across both harnesses was
wrong and briefly made the differential report the six F6 services as "fixed": they fail in
legend-engine and AGREE in legend-lite, which is precisely the finding. A case can be
broken in one engine and correct in the other, so each harness carries its own list.

  ENGINE_QUARANTINE   legend-engine vs the oracle  -> scripts/corpus/run.py
  LITE_QUARANTINE     legend-lite   vs the oracle  -> CorpusDifferentialTest
"""
from __future__ import annotations

# testable fqn -> (finding id, one-line reason)
ENGINE_QUARANTINE: dict[str, tuple[str, str]] = {
    f"stress::{name}": ("F6", "count() over an empty to-many returns 1, not 0")
    for name in (
        "F0_InstrumentChildCounts",
        "F1_CounterpartyChildCounts",
        "F2_BookChildCounts",
        "F3_DeskChildCounts",
        "F4_SectorInstrumentCounts",
        "F6_PositionGreeksCounts",
    )
}

ENGINE_QUARANTINE["stress::G3_UnionTreeWithEnum"] = (
    "F10", "graph fetch RAISES on an unmapped enum code where TDS returns null")

ENGINE_QUARANTINE["stress::M2_CanonicalWithEnum"] = (
    "F12", "EnumerationMapping not applied through a ModelChainConnection")

ENGINE_QUARANTINE["stress::O1_CounterpartyOtherwise"] = (
    "F13", "Otherwise never falls back under TDS projection")

# legend-lite currently agrees with the reference evaluator on every service, including
# all seven fan-out ones. That is not an accident of coverage — it is what makes F6 a
# legend-engine defect rather than an oracle error.
LITE_QUARANTINE: dict[str, tuple[str, str]] = {}

# Kept for callers that predate the split; run.py is the legend-engine harness.
QUARANTINE = ENGINE_QUARANTINE

# stress::F5_TraderChildCounts is deliberately NOT quarantined. It is the only fan-out
# service whose data contains no childless entity, and it passes. Keeping it in the green
# set is what proves the other six fail because of the empty case specifically, rather
# than because aggregate projections are broken in general.
