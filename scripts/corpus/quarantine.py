"""
Known-failing cases, and why.

A corpus that deletes or "fixes" the cases an engine gets wrong is worse than no corpus:
it launders a defect into a passing suite. These cases stay, with their CORRECT
expectations, and are reported as KNOWN-FAIL. `run.py` exits non-zero if a quarantined
case starts passing (the defect is fixed — remove it from here) or if an unquarantined
case fails (a regression).

Nothing may be added here without a minimized reproduction under repro/ and an entry in
docs/UPSTREAM_FINDINGS.md. "It fails and I do not know why" is not a quarantine reason.
"""
from __future__ import annotations

# testable fqn -> (finding id, one-line reason)
QUARANTINE: dict[str, tuple[str, str]] = {
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

# stress::F5_TraderChildCounts is deliberately NOT quarantined. It is the only fan-out
# service whose data contains no childless entity, and it passes. Keeping it in the green
# set is what proves the other six fail because of the empty case specifically, rather
# than because aggregate projections are broken in general.
