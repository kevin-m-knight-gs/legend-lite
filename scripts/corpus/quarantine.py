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

ENGINE_QUARANTINE["stress::X1_ExternalEntityProjection"] = (
    "F15", "XStore navigation is unsupported in a relational projection")

# Cases that do not FAIL but HANG. They stay in the corpus as the statement of what should
# work, and are excluded from the run: a test that never returns does not report a
# failure, it blocks every other test behind it. That is how F15 first presented -- a
# 50-minute "slow run" that was one service never returning.
#
# Each needs a minimized repro proving the hang, exactly like any other quarantine entry.
HANGS = {
    "stress::X1_ExternalEntityProjection":
        ("F15", "hangs rather than failing; see repro/xstore/"),
}

ENGINE_QUARANTINE["stress::F32_TradeRollupEverything"] = (
    "F14", "groupBy on an enum-mapped column groups by the source code, not the value")

# legend-lite agrees with the reference evaluator on every service EXCEPT F32, where it
# shares F14 with legend-engine. That is the differential's stated blind spot arriving:
# the two engines agree with each other and both diverge from the reference, so only the
# independent oracle can see it.
LITE_QUARANTINE: dict[str, tuple[str, str]] = {
    "stress::F32_TradeRollupEverything": (
        "F14", "shared with legend-engine: groupBy uses the enum SOURCE code"),
}

# Kept for callers that predate the split; run.py is the legend-engine harness.
QUARANTINE = ENGINE_QUARANTINE

# stress::F5_TraderChildCounts is deliberately NOT quarantined. It is the only fan-out
# service whose data contains no childless entity, and it passes. Keeping it in the green
# set is what proves the other six fail because of the empty case specifically, rather
# than because aggregate projections are broken in general.
