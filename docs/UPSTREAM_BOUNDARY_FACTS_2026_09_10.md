# Bumping legend-engine / legend-pure — the FACTS, 2026-09-10

> **SUPERSEDED.** The plan is [`UPSTREAM_BOUNDARY_PROGRAM.md`](UPSTREAM_BOUNDARY_PROGRAM.md);
> the evidence is [`UPSTREAM_BOUNDARY_HOMEWORK_2026_09_10.md`](UPSTREAM_BOUNDARY_HOMEWORK_2026_09_10.md). Read those instead. It verifies or corrects every claim below, closes all six open
> questions in §6, and adds the ordered procedure. Two corrections matter enough
> to flag here: **latest upstream is engine 4.145.0 / pure 5.99.0**, not the
> 4.138.1 / 5.92.0 recorded below (that came from unfetched local tag lists); and
> **all 122 hardcoded upstream paths still resolve at latest**, so the path
> hazard in §4 is real in mechanism but did not fire across 15 engine releases.
> Kept for provenance.

Not a plan. A starting fact sheet for the homework, so that work begins from
what is already known rather than re-deriving it. Everything below is marked
**VERIFIED** (checked in this tree today) or **CANDIDATE** (believed relevant,
not confirmed) — do not treat a candidate as established.

---

## 1. There is no single version. There are at least five identities.

**VERIFIED**, by grepping every pom and the pins file:

| identity | engine | pure | what it controls |
|---|---|---|---|
| `tools/oracle-pins.env` | 4.137.0 **+36 commits** (`943d38b3dc2`) | 5.92.0 **+3** (`d00cfd5ba`) | the SOURCE checkouts the corpus, PCT channel B, the parser corpus and the prelude generator all read |
| `parser-equivalence/pom.xml` | 4.138.2 | `legend-pure-m3-core` 5.92.0, declared separately from any property | the reference PARSER (the oracle jar) |
| `pct/pom.xml` | **4.133.0** | **5.88.0** | the PCT framework + ReportScopes |
| `core/pom.xml` | — | — | H2 2.1.214, comment says pinned "to the ENGINE's" forked version |
| `engine-grammar-fixtures-4.138.2.jsonl` | 4.138.2 in the FILENAME | — | committed harvested fixture snapshot (tier C6) |

So three engine versions and three pure versions are simultaneously live, and
one of them is encoded in a committed filename. `parser-equivalence/pom.xml`
already carries a comment acknowledging it is AHEAD of the corpus checkout and
that "VERSION-SKEW must be zero", which is what `docs/version-skew-claims.tsv`
ledgers.

**Latest upstream** (VERIFIED via `git tag` on the local checkouts, after
fetch): engine `4.138.1`, pure `5.92.0`. Note the parser module pins a Maven
artifact (4.138.2) NEWER than the newest git tag found — resolve whether Maven
Central and the git tags diverge, or the tag fetch was incomplete.

## 2. Artifacts that are GENERATED from the checkouts

Each must be regenerated and reviewed on a bump. **VERIFIED** (the regeneration
switch exists and is documented in the named test):

| artifact | regenerate with |
|---|---|
| `prelude.pure` | `PreludeGeneratorTest` with `-Dprelude.generate=1` |
| the corpus manifest | `CorpusManifestTest` with `-Dcorpus.manifest.regen=1` |
| `docs/RELATIONAL_CORPUS.md` | gate 4 rewrites it in place |
| `engine-grammar-fixtures-<ver>.jsonl` | the `engine-fixture-harvest` profile (`ZEngineFixtureHarvest`) — and its FILENAME carries the version |

## 3. Ledgers and pins that DRIFT with a version

**VERIFIED** to exist and to be version-sensitive (they went red on Windows
this week for an unrelated reason, which is how their coupling surfaced):

- `docs/refusal-allowlist.tsv` — stale rows must be REMOVED when parity is fixed
- `docs/version-skew-claims.tsv` — same, and it is explicitly about skew
- the corpus rosters, `rcorpus/{duckdb,h2}-skipped-roster.txt` and the fail rosters
- gate 7's ceilings in `tools/allgates.sh` (`run>=348, fail<=1, err<=22`)
- gate 9's Channel B discovery pins (287 / 137)
- `SpecBodyCensusTest`'s shrink-only pin (22 rows → 19 at batch 155)
- `GrammarCoverageCensusTest` ratchets — `tools/diagnostics.sh` names an
  oracle-pin bump as **trigger 2**, so the battery is already part of the procedure

## 4. Hand-written code that TRACKS the upstream spec

**CANDIDATE** — believed to require review on a bump, not verified as part of
any existing procedure:

- `Pure.java` native signatures. A standing memory says these must match the
  REAL legend-pure / legend-engine `.pure` sources, so an upstream signature
  change is a silent divergence until something catches it.
- `Prelude.java` / the prelude generator's exclusion lists (`EXCLUDED_CLASSES`,
  the protocol-template admission) — these name upstream packages by FQN.
- `SpecBodyCensusTest.PLATFORM_ROOTS` — nine hardcoded paths into legend-pure's
  module layout. An upstream module move breaks these silently.
- `MinimalCorpus.ENGINE_IMPLEMENTATION_FILES` — a path into the engine tree
  (`lineage/scanRelations/scanRelations.pure`) that must keep resolving.
- `Corpus.java` (rcorpus) — ~60 hardcoded paths into the engine's module tree.

**That last group is the real hazard**: they are paths and names, not versions,
so nothing declares them as version-coupled and nothing fails loudly when
upstream moves a file. `MinimalCorpus`'s exclusion map already proved this
class of failure this week.

## 5. What CI now gives the bump procedure for free

**VERIFIED** this week: the nine gates run on Linux, macOS and Windows on every
push, against the pinned commits, and `tools/oracle-roots.sh` FAILS the run if
a checkout drifts from `tools/oracle-pins.env`. So a bump is now:
change the pins, and let three platforms tell you what broke. That did not
exist before 2026-09-09 and materially changes what "automating a bump" means.

## 6. Open questions for the homework

1. Should the three Maven version properties collapse to one, or is the skew
   deliberate? `parser-equivalence`'s comment suggests deliberate (the parser
   oracle deliberately runs AHEAD). If deliberate, what is the invariant?
2. What is the correct relationship between the SOURCE pins and the ARTIFACT
   versions? Today the engine checkout (4.137.0+36) and the parser oracle jar
   (4.138.2) are different code.
3. Is there a way to make the hardcoded upstream PATHS fail loudly on an
   upstream move, rather than silently reading nothing?
4. What is the full ordered procedure, and which steps can be automated versus
   which need review? (Regeneration is mechanical; ledger rows are judgement.)
5. Is `4.138.2` on Maven Central without a corresponding git tag?
