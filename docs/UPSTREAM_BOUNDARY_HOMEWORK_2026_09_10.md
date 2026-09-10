# Bumping legend-engine / legend-pure — the HOMEWORK

> **The program is [`UPSTREAM_BOUNDARY_PROGRAM.md`](UPSTREAM_BOUNDARY_PROGRAM.md).** This
> file is the evidence base behind it — every measurement, every correction, every
> receipt — and it grew by accretion, so its sections are in order of discovery, not of
> logic. Read the program for the plan; read this for the numbers. The 175-native
> classification it describes is committed as
> [`NATIVE_CLAIMS_CENSUS_2026_09_10.tsv`](NATIVE_CLAIMS_CENSUS_2026_09_10.tsv).
>
> **Terminology warning (USER 2026-09-10, program §0):** this document uses the word
> "native" for two unrelated things — upstream's `native function` (an implementation
> detail: the body is Java) and our `Pure.java` entries (a semantic claim: the platform
> lowers this). Sections §3j, §3k, §3m conflate them in places. §3n below holds the
> measurements that separate the axes; the program's §0 holds the model.

**This is the central place for the evidence.** Everything a version bump touches is named here:
the pins, the invariants that bound them, every site that reads upstream, the
ordered procedure, and which steps are mechanical versus judgement.

Claims are marked **[V]** verified in this tree today, or **[C]** candidate —
believed, not confirmed. Supersedes `docs/UPSTREAM_BOUNDARY_FACTS_2026_09_10.md`
(the opening fact sheet); two of its headline claims are corrected in §4.

Two standing questions are answered by tools, not by tables in this document —
the tables below are a dated snapshot of what the tools print:

```
tools/version-report.sh            # what are we pinned to, versus upstream?
tools/version-report.sh --check    #   invariants only (CI-shaped; exit 1 on violation)
tools/version-report.sh --offline  #   pins only, no network

tools/upstream-drift.py            # what would bumping to latest cost?
tools/upstream-drift.py 4.145.0 5.99.0   # …or to a named target
tools/upstream-drift.py --paths-only     #   just the hardcoded-path check
tools/upstream-drift.py --tests          #   also count the test functions it imports
```

Both read `tools/oracle-pins.env` and the checkouts named by
`LEGEND_ENGINE_ROOT` / `LEGEND_PURE_ROOT`. Every **[V]** number in §1 and §4 is
their output on 2026-09-10; re-run rather than trusting the tables.

---

## 1. The version census — six live identities, not one

**[V]** Measured 2026-09-10 by `tools/version-report.sh`, cross-checked against
`mvn dependency:tree` for both jar modules. Four identities are *declared* and
checkable; §1b adds two more that are **frozen in captured bytes** and checkable
by nothing.

| identity | declared in | engine | pure | releases behind | what it controls |
|---|---|---|---|---:|---|
| **SOURCE** | `tools/oracle-pins.env` | `4.137.0-36-g943d38b3dc2` | `5.92.0-3-gd00cfd5ba` | 15 / 9 | the CHECKOUTS read as the spec: gates 1, 4, 5, 8, 9, the prelude generator, the spec census |
| **ORACLE** | `parser-equivalence/pom.xml` | `4.138.2` | `5.92.0` | 12 / 9 | the reference PARSER gate 8 differs against |
| **PCT** | `pct/pom.xml` | `4.133.0` | `5.88.0` | 23 / 17 | the PCT framework + `ReportScope`s: gates 6, 7 |
| **RUNNER** | `tools/engine-runner/pom.xml` | `4.138.2` | — | 12 / — | the perf harness (not a gate) |
| **FIXTURE** | `…/engine-grammar-fixtures-4.138.2.jsonl` | `4.138.2` *(in the filename)* | — | 12 / — | the committed harvested fixture snapshot, parser corpus tier C6 |

**Latest upstream [V]:** engine **4.145.0**, pure **5.99.0** — agreeing on both
Maven Central's `<release>` and `git ls-remote --tags` over all 1,232 engine tags.

Three things this corrects or adds versus the opening fact sheet:

- **Latest is 4.145.0 / 5.99.0, not 4.138.1 / 5.92.0.** The earlier figure came
  from `git tag` on checkouts last fetched in July. Read latest from Central or
  `git ls-remote --tags`, never from a local tag list — and not from the GitHub
  tags API either: it pages, and its first page opens with the legacy
  `legend-engine-release-*` names rather than the newest version.
- **`4.138.2` is a real git tag** (`1d3e236bc735bf98b40388eff9d813acd4fb18e4`) as
  well as a Central artifact. It is not a phantom. Open question 5: closed.
- **Tags and Central disagree in both directions [V].** `4.142.0` is tagged and
  was never published; `4.135.3` / `4.135.4` likewise. So jar pins may only name
  what Central has, source pins may name any commit, and "what is the latest
  version" has two different correct answers depending on which you are moving.

### 1a. Each jar identity is internally consistent

**[V]** `mvn dependency:tree` resolves exactly **one** engine version and **one**
pure version per module — `parser-equivalence`: 4.138.2 + 5.92.0 (30 pure
artifacts, all 5.92.0); `pct`: 4.133.0 + 5.88.0 (41 engine + 30 pure artifacts).
No mixed-version classpath anywhere. The spread between the identities is
deliberate configuration, not dependency-resolution accident.

**[V] Three test files document a `5.88.1` pure oracle that is no longer on the
classpath** — `EngineSectionRosterTest:32`, `FixtureAdjudicationTest:42`,
`SectionParseSentinelTest:329`. The resolved oracle is 5.92.0. These are stale
comments from before the 2026-08-10 re-pin, and they are cited as the rationale
for ledger adjudications, so they mislead exactly where it costs most. Fix them
in the next bump.

### 1b. A fifth and sixth identity: the PROTOCOL goldens

**[V]** Missed entirely by the first pass of this document, and the most exposed
surface in the repo. `core/src/test/java/com/legend/protocol/` holds **17
`EXPECTED_*` constants** that are *verbatim JSON bytes from legend-engine's own
parser*, captured **2026-08-04** at:

| test | captured from |
|---|---|
| `GenericTypeEmissionTest` (3) | `legend-engine-language-pure-grammar:4.133.0` |
| `ConstraintEmissionTest` (12) | engine `4.133.0`, HTTP-endpoint mapper |
| `DefaultValueEmissionTest` (1) | engine `4.133.0`, HTTP-endpoint mapper |
| `ProtocolEmitterTest` (1) | `legend-engine d0b4c3a2f68` — **a commit SHA, a sixth identity** |

Three things make this worse than every other coupling:

1. **[V] `core/pom.xml` declares zero legend-engine or legend-pure dependencies.**
   `ProtocolEmitterTest:23` states this is deliberate. So the goldens cannot be
   re-derived in the module that asserts them.
2. **The capture tool is a manual instrument, not a gate.** `ProbeWireShapes`
   ("NOT a gate — an instrument… Run on demand") lives in `parser-equivalence`,
   which has since moved to **4.138.2**. The goldens have not been re-captured,
   and the tool that would do it now talks to a different engine than produced
   them.
3. **`ProtocolRosterCensusTest` does not cover this.** It censuses the Jackson
   `@JsonSubTypes` roster off the oracle classpath and writes
   `target/protocol-roster.txt` — a `target/` dump, with **[V]** no committed
   counterpart anywhere in the tree. It reports *new* protocol tags to stdout. It
   does not compare *wire shapes*. So a new protocol type is (loosely) visible; a
   **changed** protocol shape is not.

That is the same loud/silent split as `Pure.java` (§3c), with less coverage: 17
byte-exact expectations frozen against an engine **23 releases old**, no
dependency that could re-derive them, and no census that compares them. A bump
cannot make these go red — which is precisely the problem, because upstream
protocol changes are exactly what they exist to pin. `ProtocolEmitterTest:27`
notes the repo chose frozen pins because upstream "rotted [its] own 26 golden
fixtures for 16 months"; the frozen copy inherits the same rot, just privately.

**Owed — now planned in §5c:** make the comparison *live* in `parser-equivalence`
(which holds both our emitter and the engine in one JVM) and delete the goldens
entirely, rather than freezing a better-maintained copy of them.

### 1c. Hidden backend-driver couplings

**[V]** Not versions of Legend, but pinned *to* Legend, and nothing says so:

| we pin | the engine pins (4.137 and 4.145.0 alike) | note |
|---|---|---|
| H2 `2.1.214` (`core/pom.xml`, `pct/pom.xml`) | `2.1.214` | matches. The engine has a module literally named `…-h2-execution-2.1.214`, so an upstream H2 move changes an **artifactId** |
| H2 `2.4.240` (gate 7 only, `-Dh2.version=`) | — | the "h2modern" lane; deliberate |
| H2 `2.2.224` (`tools/engine-runner/pom.xml`) | `2.1.214` | diverges; perf tool only |
| DuckDB `1.4.4.0` (root `pom.xml`) | `1.3.0.0` | **diverges** — measured. That the corpus's expected rows were therefore authored under 1.3.0.0 semantics is inference (§8), not a measured divergence |
| eclipse-collections `10.2.0` (`pct/pom.xml`) | `10.2.0` | matches |

The DuckDB row deserves a decision rather than silence: we execute the engine's
expected rows on a driver four minor versions ahead of the one the engine builds
against, and `docs/GATES.md §"OPEN, 2026-09-09"` already carries an unresolved
DuckDB arch-stability finding in `percentile_cont`. Whether the spread has ever
*caused* a divergence is unmeasured — that is the decision owed, and the cheap
first probe is running gate 4 against `duckdb_jdbc` 1.3.0.0.

---

## 2. The invariants — what bounds the spread

The answer to *"is the skew deliberate?"* is: **partly.** Three of the four
identities are governed by a rule; one spread is a defect. All four rules are
checked by `tools/version-report.sh --check`.

**INV-1 — a jar identity pairs an engine release with the pure version THAT
release declares.** Upstream's `legend-engine/pom.xml` carries
`<legend.pure.version>`; it is the authority. **[V]** Read off the published
poms: 4.133.0 → 5.88.0, 4.137.0 → 5.91.0, 4.138.2 → 5.92.0, 4.145.0 → 5.99.0 —
four releases of twenty-four, so the rule itself is sampled (§8), though
`version-report.sh` re-checks it against the actual target every run. Both live
jar identities satisfy it. Violating it puts an oracle's compiler and its own
platform sources at different versions, so its refusals stop meaning anything.
**Status: HOLDS.**

**INV-2 — the SOURCE pins name RELEASE TAGS, so the oracle jar can be the same
release as the source.** **Status: BROKEN — and the first draft of this document
got this one wrong.**

The draft stated the invariant as "the oracle jar is at or ahead of the source
pin", called the spread deliberate, and marked it HOLDS. Its only evidence was
the comment at `parser-equivalence/pom.xml:26` asserting the parser is
deliberately AHEAD. That is the repo testifying about itself, not a
justification — and the comparison it rests on is base-version arithmetic
(4.138.2 ≥ 4.137.0), which is not how the two trees actually relate.

**[V]** `GET /repos/finos/legend-engine/compare/legend-engine-4.138.2...943d38b3dc2`:

```
status    : diverged
ahead_by  : 20    commits the SOURCE pin has that the 4.138.2 tag does not
behind_by : 11    commits the tag has that the SOURCE pin does not
```

They are **not ordered**. "Deliberately ahead" requires an ordering, and there
isn't one. That is visible in the skew ledger, which has rows in *both*
directions — two rows explicitly annotated `post-4.138.2` are constructs the
SOURCE checkout has and the oracle refuses, which an "oracle ahead" story cannot
produce.

**So why do they differ at all?** Not by design. Jars exist **only at release
tags**; the source pin is a commit **36 past a tag**. There is no
`legend-engine-language-pure-grammar:4.137.0-36-g943d38b3dc2` to depend on, so an
identical oracle is *impossible* while the source pin is an arbitrary commit. The
skew is a tooling artifact, and `docs/version-skew-claims.tsv` — 25 rows of hand
adjudication, several marked "re-adjudicate at re-pin" — is the standing cost of
it.

**The fix is upstream of the invariant: pin the source to a release tag.** Then
the oracle jar and the source checkout are the *same release*, INV-2 collapses
into INV-3, and most of the skew ledger loses its reason to exist. There is no
reason a source pin must be a non-tag commit; it is what `git pull` leaves you on.

**INV-3 — the PCT jars equal the SOURCE pin.** This is the one that is broken,
and it matters more than its size suggests. **[V]** The two PCT channels
discover their universes from *different places*:

- **Channel A** (`Test_LegendLite_*_PCT`, gates 6/7) discovers from the **jars** —
  `ReportScope` off `CoreStandardFunctionsCodeRepositoryProvider`, i.e. engine
  4.133.0 / pure 5.88.0.
- **Channel B** (`ChannelB*Test`, gate 9) discovers by **walking the SOURCE
  checkouts** — `System.getProperty("legend.pure.root")` + `Files.walk`, i.e.
  engine 4.137.0+36 / pure 5.92.0+3.

They are a dual-verdict pair whose whole purpose is comparing two verdicts over
the same test. Four engine releases apart, they referee different test sets.

**[V] Measured, not inferred.** Counting `<<PCT.test>>` declarations in the
relation scope both channels claim to cover — `core_functions_relation`, 49
`.pure` files on both sides:

| | `<<PCT.test>>` | `<<PCT.test,` | total |
|---|---:|---:|---:|
| channel A — the jar, `legend-engine-pure-functions-relation-pure:4.133.0` | 93 | 253 | **346** |
| channel B — the source checkout at the pin | 93 | 260 | **353** |

**The jar universe is 7 tests smaller than the source universe — and the pins
are 7 apart** (gate 7 floors A at `348`, `ChannelBRelationTest` asserts B
discovers exactly `355`). The same +2 offset on both sides is a discovery detail
my grep does not model; the *delta* is the point, and it matches exactly.
**Status: BROKEN (a defect to close, not a policy to document).**

**INV-4 — the committed fixture snapshot was harvested from the ORACLE jars it
is adjudicated against.** **[V]** `engine-grammar-fixtures-4.138.2.jsonl` ==
ORACLE 4.138.2. **Status: HOLDS.** The version lives in a *filename*, and the
reader (`parser-equivalence/.../Corpus.java:151`) returns an empty list when the
file is absent — so renaming without re-harvesting silently empties tier C6
(1,552 sources). §6 fixes that.

---

## 3. The coupling map — every site that reads upstream

Sorted by what actually goes wrong. **[V]** — each mechanism read in source.

### 3a. LOUD on upstream change (these are the working machinery)

| site | mechanism | what it catches |
|---|---|---|
| `CorpusManifestTest` | SHA-256 of every one of **8,891** distinct sources, committed as `corpus-manifest.tsv` | any content or file-set change in either checkout. The strongest guard in the repo |
| `PreludeGeneratorTest.preludeIsCurrent` | regenerates `prelude.pure` (5,281 lines) from the checkouts and asserts byte-equality with the committed file | upstream shape/signature changes that reach the prelude; also an absent root (output shrinks) |
| `PreludeGeneratorTest.m3ReaderPrintsEveryClass` | `assertTrue(Files.isRegularFile(m3))` + `decls.size() >= 85` | `m3.pure` moving — the one path site that fails loudly by design |
| `ChannelB*Test` ×5 | exact-equality discovery pins: **137 / 327 / 204 / 355 / 95** | any change to the source-walked PCT universe. Goes red immediately on a SOURCE bump |
| `SectionParseSentinelTest` | `MIN_BEHAVIOUR_MATCHED = 2093` over real corpus files | a pull that collapses parsing. `docs/GATES.md` names the 2026-08-04 incident this was built for |
| `oracle_roots_check` | HEAD vs `oracle-pins.env`, fatal | a checkout that drifted from the pin |
| gate 7 ceilings | `run>=348, fail<=1, err<=22` in `tools/allgates.sh` | Channel A universe changes |

### 3b. SILENT on upstream change (the hazard class)

**[V]** Each of these reads a hardcoded upstream path and does nothing visible
when it stops resolving:

| site | count | mechanism | consequence |
|---|---:|---|---|
| `MinimalCorpus.java:312` ← `Corpus.SHAPE_FILES` | 64 | `if (!Files.isRegularFile(f)) continue;` | shapes vanish from the graph; the failure surfaces later as unknown-type errors that read as platform regressions |
| `MinimalCorpus.java:396` ← `Corpus.LIBRARY_FILES` | 6 | `if (Files.isRegularFile(lib)) out.add(lib);` | same |
| `SpecBodyCensusTest.java:82` ← `PLATFORM_ROOTS` | 9 | `if (!Files.isDirectory(root)) continue;` | census runs over **fewer files**, and a shrink-only pin (`walled <= 23`) passes more easily with less input — a false green |
| `SpecBodyCensusTest.java:73` | 1 | `assumeTrue(isDirectory(PLATFORM_ROOTS.get(0)))` | only root **1 of 9** is checked before the census is allowed to run |
| `PreludeGeneratorTest.platformFunctions` | 4 | `if (!Files.isDirectory(root)) continue;` | mitigated: a shrunken prelude trips the byte-parity assert |
| `MinimalCorpus.ENGINE_IMPLEMENTATION_FILES` | 1 | map key matched against a relativized path | **inverted failure**: the key stops matching, the engine's own `scanRelations` implementation is admitted and shadows the platform's. Measured cost: **49 tests**, with no signal naming a path |
| `parser-equivalence/.../Corpus.java:151` | 1 | `if (!Files.exists(p)) return out;` | tier C6 silently drops all 1,552 fixture sources |
| `docs/version-skew-claims.tsv` | 25 | ledger rows keyed by upstream path | a stale row stops matching and silently stops excusing (or keeps excusing something repaired) |
| `docs/refusal-allowlist.tsv` | 8 | same | same |

**The `ENGINE_IMPLEMENTATION_FILES` row is the only one that has actually
fired**, on Windows CI 2026-09-09 — and via a path-*separator* bug, not an
upstream move. The cost (49 tests, no diagnostic) is the reason to fix the class.

### 3c. Hand-written code tracking the upstream spec, with no oracle

This section and §1b are the same failure in two places: a hand-held copy of an
upstream fact, with no mechanism that re-derives it.

**[V] `Pure.java` — 881 native signatures, validated against nothing upstream.**
`native-catalog.txt` (881 rows, no header, no duplicates) is a snapshot of *our
own* output: it catches our edits, not upstream's. (881, not 880: the file has 880
*lines* declaring a `NativeFunctionDefinition`, because line 1173 declares two
constants on one line. `signature()` registrations, catalog rows and the
`assertEquals` all agree at 881.) `NativeFunctionTest` and `PlatformSurfaceGuardrailTest`
read neither `legend.pure.root` nor `legend.engine.root`. The file's own header
says every signature is "VERBATIM to its real `.pure` source (verified per
function)" — verified *by hand*, once, in July.

What partially covers it: `SpecBodyCensusTest` loads every platform `.pure` and
buckets unknown functions by whether the spec marks them native, so an upstream
**addition** eventually shows up as a new row. What nothing covers: an upstream
**signature change** to a native we already declare — a parameter added, a
multiplicity widened, a return type narrowed. That is silent, and it is the
largest unguarded surface in the repo.

---

### 3d. The COMPLETE enumeration, by kind of dependency

The first two passes of this document searched for *references* to upstream — a
Maven coordinate, a root property, an import. Everything that holds a **copy** of
an upstream fact was invisible to that search, which is how §1b's protocol
goldens survived two passes. Dependencies come in seven kinds, and each needs its
own detector.

| kind | how it depends | detector | complete? |
|---|---|---|---|
| **K1** Maven coordinate | declares a jar | grep poms for `org.finos.legend` | yes, by construction |
| **K2** filesystem read | reads the checkout at test time | grep `legend.{engine,pure}.root`, `LEGEND_*_ROOT` | yes, by construction |
| **K3** compiled-against API | imports upstream Java classes | grep `^import org.finos.legend.{engine,pure}.` | yes — but **our own code squats `org.finos.legend.engine.nlq`**, so the grep over-reports; check the package is really upstream's |
| **K4** **captured value** | upstream output **copied into our source**, link severed | provenance words, or upstream-shaped literals | **NO — heuristic only** |
| **K5** version literal / filename | a version spelled in a string or filename | grep version regexes | yes |
| **K6** generated artifact | committed file produced from upstream | the generator's own parity assert | yes |
| **K7** calibrated number | a ratchet whose value is a function of upstream content, naming nothing | none — invisible by construction | n/a; loud at runtime |

**The enumeration, and the plan per entry:**

| # | kind | what | size | guarded today? | plan |
|---|---|---|---:|---|---|
| 1 | K1 | `parser-equivalence` oracle jars | engine 4.138.2 + pure 5.92.0 | INV-1/2/4 | collapse into `oracle-pins.env`; source pinned to the same tag |
| 2 | K1 | `pct` PCT jars | engine 4.133.0 + pure 5.88.0 | INV-3 **broken** | move onto the source release — its own commit, first |
| 3 | K1 | `tools/engine-runner` | engine 4.138.2 | no | follow ORACLE; not a gate |
| 4 | K2 | the 132 upstream path sites | 132 | **4 silent `continue`s** | `UpstreamPathManifestTest`: assert all resolve, report misses (§6.2) |
| 5 | K3 | `parser-equivalence` → `grammar.from`, `protocol.*`, `shared.core` | 29 files | compile break | none needed — self-guarding |
| 6 | K3 | `pct` → `m3.pct.reports.config`, interpreted runtime | 11 files | compile break | none needed; a 12-release API drift is the first thing Phase 1 finds |
| 7 | **K4** | protocol wire goldens (§1b) | **17** | **nothing** | generator + committed resource + parity assert (§6.3b) |
| 8 | **K4** | `Pure.java` natives + `native-catalog.txt` | **881** | self-snapshot only | diff against upstream `native function` declarations (§6.3) |
| 9 | ~~K4~~ → **duplication** | `PlatformTypes` FQN constants | **111** | **reclassified, §3g**: a copy of our own `Pure.java`, not of upstream. Guard covers **6 of 111**, by containment, against our own prelude | generate it from the same read as §6.3; replace identity-checks with methods; 7 inline-literal bypasses to delete |
| 10 | **K4** | `NameResolver.CORE_IMPORTS` — legend-pure's `system::imports::coreImport`, **in `core/src/main`** | **32** | **nothing** | `PreludeGeneratorTest` already reads `m3.pure` and already asserts 85 classes; add the `coreImport` comparison there — ~5 lines |
| 11 | **K4** | `TokenStreamPositionsTest` — position numbers from the engine's parser, captured 2026-08-04 | 20 asserts | **nothing** | re-capture in Phase 6, or fold into the §6.3b generator |
| 12 | K5 | `engine-grammar-fixtures-4.138.2.jsonl` | filename | INV-4 | derive the name from the pin; **fail** when absent (§6.5) |
| 13 | K5 | three stale `5.88.1` oracle comments | 3 | no | fix in the next bump (§1a) |
| 14 | K6 | `prelude.pure` | 5,281 lines | **byte-parity assert** | keep — this is the pattern entries 7–11 should copy |
| 15 | K6 | `corpus-manifest.tsv` | 8,891 SHA rows | **exact** | keep |
| 16 | K6 | `RELATIONAL_CORPUS.md` | scoreboard | gate 4 rewrites | keep |
| 17 | K7 | ChannelB discovery pins | 5 exact | red on any move | Phase 4 — §4b predicts two of them |
| 18 | K7 | gate 7 ceilings, spec-census pins, rcorpus rosters, ~15 parser ratchets, PCT expected-failures | ~60 rows | red on any move | Phase 4, row by row, each with a reason |

Entries **7–11 are the whole problem**: 1,072 hand-held copies of upstream facts
across five sites, none of which a bump can turn red. Everything else is either
self-guarding (K1/K3/K6), now checked (K2/K5), or loud (K7).

### 3e. How to search comprehensively — the method, and its limit

**Reference detectors (K1–K3, K5) are complete by construction.** Run them as
written above; they cannot miss, because a reference is a symbol and symbols are
greppable.

**Capture detectors (K4) are heuristics and both were needed:**

1. *Provenance words* — `captured (on|at|from|<date>)`, `verbatim (output|copy|from)`,
   `copied from legend`, `engine's (own|exact) (output|bytes)`, `snapshot of the
   engine`. Found 21 files; 7 were genuine, 14 were internal code movement
   ("verbatim from the harness") or an explicit negation ("**NOT** captured from
   engine output").
2. *Upstream-shaped literals* — `"_type"` in a Java string (wire JSON), `"meta::`
   FQN literals, and `>= 3` of them in one file. This is what surfaced
   `PlatformTypes` (122) and confirmed the emission tests.

**Neither is complete, and no grep can be.** A capture with no comment and no
distinctive shape — a bare integer copied from an engine run — leaves no trace to
search for. `TokenStreamPositionsTest`'s 20 position numbers are exactly that
shape, and they were found only because someone wrote the comment.

**So the complete answer is not a better search — it is an enforced rule.** The
repo already has the enforcement point: `core/src/test/java/com/legend/architecture/`
with ArchUnit 1.3.0 and three standing rules. Add a fourth:

> A committed expectation of upstream behaviour may not be a typed literal. It
> must be a resource produced by a named generator, asserted byte-equal against a
> fresh run of that generator — the `prelude.pure` contract.

That converts "did we find them all?" from a question about search coverage into
a question a test answers on every run. Entries 7–11 are its backlog; after they
land, the rule keeps the class closed.

### 3f. What KIND of upstream fact — and why 3,700 green tests do not cover them

Calling all of §3d's entries 7–11 "hand-held copies" flattened four different
things. The distinction decides both the risk and the fix.

| | what it is | how it is verified | stale-copy risk |
|---|---|---|---|
| **C1 reimplemented behaviour** | we wrote the code; upstream is the spec | by running thousands of tests | **none** |
| **C2 copied identifier** | a *name* in upstream's namespace | nothing | additions hurt; renames are rare |
| **C3 copied data** | a *table or value* upstream declares as data, read by our runtime | nothing | **highest — silent** |
| **C4 observed output** | *bytes* upstream emits | nothing | real, and invisible to any name check |

**C1 is most of this repository** — the compiler, the lowerer, the dialects, the
position math. It is **self-verifying**: when upstream's behaviour changes, the
corpus and PCT gates go red because the *outputs* disagree. That is why the gates
feel strong.

**C2–C4 are transcriptions, and the gates do not test transcription.** This is the
structural blind spot in one sentence: *2,575 corpus tests and 1,109 PCT tests
verify behaviour; nothing verifies that a copied declaration still matches its
original.* A stale transcription produces a confidently wrong answer that every
behavioural test agrees with, because our behaviour is consistent with our own
copy.

**Where each entry lands:**

| entry | category | note |
|---|---|---|
| `NameResolver.CORE_IMPORTS` (32) | **C3** | the only clean instance of copied data: an ordered table from `m3.pure`, walked by the resolver at runtime |
| `Pure.java` signatures (881) | **C3** | a signature is a copied *declaration*, not reimplemented behaviour — which is exactly why a changed parameter is silent |
| `PlatformTypes` (111) | **C2**, second-order | see §3g — its direct source is our own `Pure.java`, not upstream |
| the 17 protocol goldens | **C4** | no upstream file to diff against; only a fresh engine run can re-derive them |
| `TokenStreamPositionsTest` (20) | **C4**, test-only | pins a *convention* (1-based lines, 1-based start, **inclusive** end). Nothing in the runtime reads these numbers |

**`CORE_IMPORTS` and `Pure.java` are the same kind of thing, and an earlier draft
of this document ranked them apart on a claim that does not hold.** The claim was
that `Pure.java` additions surface in the spec census while `CORE_IMPORTS` has no
signal at all. **[V]** `Compiler` calls `NameResolver.resolve`, and
`SpecBodyCensusTest` compiles through `Compiler.parseSources` — so a package
*added* to upstream's `coreImport` leaves bare names unresolved in platform
bodies, producing walls and tripping the same shrink-only pin. **Same signal, same
test.** What actually drove the split was (a) recency and (b) conflating *cheap to
fix* with *high risk*: `CORE_IMPORTS` is cheapest because it comes from **one**
declaration in **one** file the prelude generator already opens.

Two differences between them are real, and both are narrower than that ranking
implied:

- **Leverage per fact.** Each of the 32 `CORE_IMPORTS` entries gates an entire
  namespace of bare names; each of the 881 signatures gates one function.
- **Set versus sequence.** `Pure.java` is a **set** — diff element-wise, report
  added / removed / changed. `CORE_IMPORTS` is an **ordered list whose order is
  semantic**: first match wins, and the ordering is what keeps a function and a
  profile of the same bare name apart (`NameResolver:628`). It has a failure mode
  sets do not have — *same 32 elements, different order, different meaning* — and
  a set-diff validator passes that cleanly. **The validator for this one must
  compare sequences.** Write it as a set and the reordering case is lost.

They should therefore be fixed by one mechanism in one batch (§6.3), with the
ordered comparison called out explicitly.

### 3g. `PlatformTypes` is not an upstream dependency — it is a copy of our own copy

**[V] Reclassified after measurement.** An earlier draft listed it beside the
protocol goldens as an upstream exposure. It is not one.

**What it is:** a flat table of **111 `String` constants** — 108 `meta::` FQNs plus
`rows`, `TDSNull`, `csv` — read by **91 main files**. Its *direct* source is our own
`Pure.java` / `prelude.pure` declarations, which in turn came from upstream. A copy
of a copy, so the upstream coupling is **transitive**: fix `Pure.java` against
upstream and generate this table from the same read, and it is covered.

**Why the duplicate exists:** layering. Its own Javadoc says the constants exist so
that `com.legend.compiler.element.type` needs no dependency on
`com.legend.builtin` (the parser-level prelude) — so instead of
`Pure.ANY.qualifiedName()`, the string was re-typed.

**Do not confuse it with a type.** We have our own `Any` twice over, in different
senses: `prelude.pure` **declares** `Any` as a real class (which is why
`Prelude.classFqns()` contains it), and `Type.java` holds the primitive lattice
`Integer < Number < Any` as typing logic. `PlatformTypes.ANY` is neither — it is
*the name of a type, as text*, used to ask "is this FQN the one that means Any?"

**Two measured problems, both about duplication rather than upstream:**

1. **[V] The guard covers 6 of 111.** `PlatformTypesDriftTest` asserts exactly
   `ANY`, `NIL`, `VARIANT`, `LIST`, `PAIR`, `FUNCTION` are *contained* in
   `Prelude.classFqns()` — containment, against our own prelude, not equality with
   upstream. The other 105 are unguarded even against our own model.
2. **[V] The centralisation it exists to provide is not in force.** The Javadoc
   calls it "the ONE home for these checks"; at least **seven real code sites spell
   the `Any` FQN inline** and bypass it — `MatchFold:47`, `PureAsserts:204`, and
   `Typer` at 1987, 2119, 2250, 2352 (plus `Typer:438`). A table whose purpose is
   to stop string-comparison sprawl, alongside the sprawl.

**The two jobs it does are not the same, and only one of them is legitimate:**

- **Construction** — `new ClassType(PlatformTypes.ANY)`,
  `new TypeExpression.NameRef(PlatformTypes.ANY)`. Building a type node genuinely
  needs a name string. Fine.
- **Identity** — `ct.fqn().equals(PlatformTypes.ANY)`. This should not be a string
  compare at all. "Is this `Any`?" is a question about a type, and `Type.java`
  already owns the lattice.

**The design:**

1. The *decision* of which types are distinguished stays ours — a generator cannot
   know which types Java code wants to special-case.
2. The *spelling* of each name is **derived, never typed**: the prelude generator
   already reads upstream and writes `prelude.pure`; it emits this FQN table as a
   second generated artifact. `PlatformTypesDriftTest` then becomes unnecessary
   rather than 5% effective, because nothing can drift.
3. Identity checks become methods (`type.isAny()` or an enum), and the §3e ArchUnit
   rule extends naturally: **no `meta::` FQN literal outside the generated names
   table.** That rule has seven violations for `Any` alone today, so it is
   measurable the day it lands.

So `PlatformTypes` leaves the upstream-risk list and becomes a **duplication-deletion
item**. The genuine "copies of upstream, held to prove we are compliant with their
spec" are `Pure.java`, `CORE_IMPORTS` and `prelude.pure`.

### 3h. Census: how much of our platform surface is reachable by a bare name?

**[V] Measured 2026-09-10.** Question (USER): should prelude content come only from
`CORE_IMPORTS`, and how much of it comes from outside what upstream declares as the
implicit import group? The two are different axes, and the numbers say so.

`prelude.pure` declares **466 `Class`, 82 `function`, 18 `Enum`** and **no
natives** — the 881 natives live in `Pure.java` and register programmatically. The
whole platform surface is therefore both files together:

| surface | total | in a `coreImport` package | outside | % bare-reachable |
|---|---:|---:|---:|---:|
| `prelude.pure` Class/Enum | 481 | 126 | 355 | 26.2% |
| `prelude.pure` library functions | 63 | 27 | 36 | 42.9% |
| `Pure.java` natives | 881 | 672 | 209 | 76.3% |
| **whole surface** | **1,425** | **825** | **600** | **57.9%** |

**No — the prelude should not come only from `CORE_IMPORTS`, and 42% outside is
correct, not a defect.** The prelude is the platform's *declared surface* (what
exists and is resolvable by FQN); `coreImport` is the *implicit import scope* (what
a bare name can reach). Upstream separates them the same way — you must spell
`meta::relational::metamodel::Table` in full there too, and the outside bulk here is
exactly that: `meta::relational::*` (~170 declarations), `meta::pure::mapping` (39),
`diagram` (18), `executionPlan` (12). The permissiveness hole this could have
implied is already closed by the 2026-09-08 user ruling — `NameResolver` has **no
fallback tier**, so prelude membership never grants bare-name reachability.

**Three findings the census did surface:**

1. **[V] 36 natives sit in `meta::legend::lite`** — our own invented namespace, not
   upstream's. That is the exact size of the allowlist the §6.3 native diff needs for
   its "ours-only" bucket. See [[internal-natives-fqn-partition]].
2. **[V] 32 natives in `meta::pure::functions::date::calendar`** are outside
   `coreImport` *correctly* — upstream requires an explicit import for the calendar
   family, because the sub-package is not in the group even though
   `functions::date` is.
3. **[V] Three `coreImport` packages are empty across our entire surface**, and
   upstream declares 16 elements in them:
   - `meta::pure::functions::constraints` — **`fail`, `warn`: genuinely absent.**
     (We declare `meta::pure::functions::asserts::fail`, a different function in a
     different package.)
   - `meta::pure::functions::tools` — **`profile`, `ProfileResult`: genuinely absent.**
   - `meta::pure::profiles` — 12 profiles (`doc`, `access`, `equality`,
     `functionType`, `milestoning`, …). **Legitimately absent**: profiles are a
     parser-level element kind (`ElementParser.parseProfileDefinition` →
     `Protocol.PProfile`), not prelude declarations.

   So the real gap is **four elements in two implicitly-imported packages** — small,
   bounded, and precisely the class of "new upstream thing" that nothing currently
   screams about.

**Make this census standing.** It is ~20 lines, it answers "is our implicit
vocabulary complete relative to upstream's?", and it belongs beside the §6.3 native
diff — the two read the same files.

### 3i. Is the prelude the platform, or corpus bootstrap? (USER question)

**Answer: the platform — and the split this question imagines already happened.**
**[V]** The generator states its demand rule in code, and the committed census
measures the result.

`PreludeGeneratorTest`'s comment (T1) is explicit about the three demand sources,
and about the one that is *not* a source:

> (1) legend-pure's platform packages WHOLE — every class and enum under the nine
> platform roots, minus the decided exclusions and the spec's test packages,
> **demanded or not**; (2) the platform's VOCABULARY — every spec class or enum its
> **Java NAMES in code**, plus what the system metamodel's source names; (3) the
> closure of those. **"The corpus names it" is no reason (T2)**: an engine class a
> program needs enters that program's graph by file (`Corpus.SHAPE_FILES`).

**[V] `docs/PRELUDE_MODULE_CENSUS_2026_09_08.tsv`, 480 rows, `demand` column:**

| demand | from legend-pure | from legend-engine | total | share |
|---|---:|---:|---:|---:|
| `platform` — pure's platform roots, whole | 210 | 0 | **210** | 43.8% |
| `java` — our own Java names it | 122 | 61 | **183** | 38.1% |
| `closure` — pulled in by the above | 0 | 87 | **87** | 18.1% |
| **total** | **332** | **148** | **480** | |

**Zero rows are demanded by the corpus.** The corpus tree is walked only to compute
`corpusDefined` for the T4 receipts — **135 of the 480** are also declared by the
corpus graph, and the rule decides which copy wins.

The history matters here, because the intuition behind the question was right *once*:
before batch 155, **253 classes rode in the prelude precisely because the corpus
named them**. That was cut (PHASE3_DEMAND_CUT_HOMEWORK, phase 3b-2), and the
replacement is exactly the split this question proposes: engine classes a corpus
program needs now enter that program's graph **by file** — `Corpus.SHAPE_FILES` (64)
and `LIBRARY_FILES` (6). So there is already a "platform prelude" and a separate
"corpus bootstrap", and they are already separate things.

**One design fact worth stating plainly, though:** 148 of 480 (**30.8%**) come from
**legend-engine, not legend-pure** — 61 by java-demand, 87 by closure. So the
prelude is not "legend-pure's platform". It is *pure's platform taken whole, plus the
engine vocabulary our own Java names, plus the closure of both*. Those 148 are
defensible — our Java implements relational stores, so it depends on
`meta::relational::metamodel::Table` existing — but "the prelude is the Pure
platform" is not an accurate description of what the file contains.

### 3j. Are natives registered only so the corpus resolves? (USER question)

**Answer: no — 92% are named by the platform itself. The 8% residue is
declared-but-unimplemented reflection, which is debt, not corpus bootstrap.**

First, the scale, reconciled: **881 registrations = 520 distinct FQNs = 484 distinct
simple names** (overloads collapse). The platform has two implementation paths, and
`CoreFn`'s own Javadoc draws the line:

- **`CoreFn` — 61 members.** "The closed vocabulary of core structural constructs —
  the functions the type checker treats as language forms rather than library calls."
- **The generic signature-driven path** for everything else: "the ~440 scalar and
  collection natives (`+`, `size`, `toUpper`) … ride the generic signature-driven
  path and lower via the native-function table, exactly GHC's
  library-functions-vs-primops split."

**[V] Measured partition.** A native FQN counts as *named by the platform* if its
simple name or FQN appears as a string literal anywhere in `core/src/main` outside
`Pure.java`, **or** its Java constant is referenced there, **or** it is reached
through `INTERNAL_DESUGAR` / the engine-vocab shims:

| | FQNs | share |
|---|---:|---:|
| named by the platform (three mechanisms) | **478** | 91.9% |
| named by **nothing** — registered so Pure code resolves | **42** | 8.1% |

The resolution-only 42, by family — and the shape of it is the finding:

| family | resolution-only | of total |
|---|---:|---:|
| `meta::pure::functions::meta` | **14** | of 25 |
| `meta::pure::functions::collection` | 5 | of 66 |
| `meta::relational` | 4 | of 37 |
| `meta::pure::test` | 3 | — |
| eleven other families | 1–2 each | — |

`meta::pure::functions::meta` is the **reflection family**: `reactivate`,
`openVariableValues`, `pathToElement`, `elementPath`, `lenientPathToElement`,
`subTypeOf`, `_subTypeOf`, `generalizations`, `genericTypeClass`, `getHiddenPayload`,
`canReactivateDynamically`, `addColumns`, `enumName`, `getUnitValue`. That is a
coherent bucket, it is already known debt — see [[system-prelude-tenets]]
(resolveSchema parked on reflective equality) and `MinimalCorpus`'s own note about
walling on `openVariableValues` — and it is **not** corpus bootstrap. It is the
reflective surface we have declared and not implemented.

**So the proposed split — a "pure" `Pure.java` for the platform plus a separate
corpus prelude for natives with no Pure body — is not warranted by the measurements,
for two reasons:**

1. **There is no large corpus-only native set to separate out.** The catalog is
   overwhelmingly *the language*: date 84, collection 66, math 63, string 49,
   relation 43 distinct FQNs. Any Pure program needs those, corpus or not. Only 42
   FQNs are unnamed by the platform, and 14 of those are one debt family.
2. **The prelude side of the split already exists** (§3i): the corpus is not a
   prelude demand source, and corpus needs enter by file.

**What the measurements do justify:** a **declared-but-unimplemented ledger** for the
42, shrink-only, with the reflection family named as one entry. That converts an
invisible 8% into a burn-down list, which is this repo's standard instrument — and it
is the honest version of the question's instinct: the residue is real, it is just
*unimplemented*, not *corpus-only*.

**Method caveat [V]:** the scan's false-positive mode is a native referenced through
a Java constant rather than a string literal. That bit the `meta::legend::lite`
family: a first pass flagged 12 as resolution-only, but **11 are referenced via
`Lite.*` constants** gathered into `INTERNAL_DESUGAR` (`Pure.java:483`), whose names
are spelled only inside `Pure.java`. Only `divideRound` survives. The combined
three-mechanism scan above accounts for this; a single-mechanism scan over-reports by
roughly a quarter.

### 3k. Who demands the 42? (USER hypothesis — confirmed for the majority)

**Hypothesis: the 42 are there because of the corpus — they are natives upstream, so
there is no Pure body to put in the prelude. Measured: right for 23 of 42.**

**[V]** Each of the 42 grepped for callers across the relational corpus (543 files),
legend-pure's platform tree, and the whole engine checkout:

| group | count | what it means |
|---|---:|---|
| **A — corpus-demanded** | **23** | corpus bodies call them; they exist so those bodies type-check |
| **B — pure-platform-demanded** | 13 | the spec census compiles platform bodies that call them |
| **C — engine-only** | 4 | called in the engine tree, neither corpus nor pure platform |
| **D — called nowhere** in either checkout | **1** | `meta::relational::metamodel::execute::loadValuesToDbTable` — dead |

**Group A, by corpus caller count** — `meta::pure::tools::noDebug` (56 files),
`functions::io::http::executeHTTPRaw` (37), `functions::meta::pathToElement` (29),
`functions::lang::mutateAdd` (19), `functions::meta::_subTypeOf` (10),
`openVariableValues` (8), `reactivate` (7), `extension::moduleExtension` (7),
`router::printer::asString` (5), `getHiddenPayload` (4), `lang::evaluate` (4),
`collection::replaceTreeNode` (4), `relational::…::createTempTable` (3),
`mapping::resolveStore` (3), `subTypeOf` (3), `extension::defaultExtensions` (3),
`dropTempTable` (2), `relation::stringToTDS` (2), `string::lastIndexOf` (2),
`genericTypeClass` (2), `functions::test::testedBy` (1), `string::isAlphaNumeric` (1),
`collection::getMapStats` (1).

**Group B** is the reflection/PCT residue that belongs to the platform regardless:
`getIfAbsentPutWithKey`, `removeAllOptimized`, `replaceAll`, `addColumns`,
`canReactivateDynamically`, `elementPath`, `enumName`, `generalizations`,
`getUnitValue`, `lenientPathToElement`, `test::pct::loadPCTManifest`,
`surveyor::executePCTTest`, `surveyor::executeTest`.

#### Should the 23 move out of `Pure.java`?

**No — and it is not a move.** **[V]** The catalog is **not extensible**: `Pure.all()`
returns a static `ALL` list populated at class-load, there is no `register(…)` API,
and `Pure`'s own Javadoc states the catalog is fixed at class-load with the FQN
indexes built once, lazily. `ENGINE_VOCAB_SHIMS` is a wire-name shim set, not an
extension point. So relocating corpus-demanded signatures means **adding a
runtime-mutable extension point to the compiler** — trading a compile-time guarantee
for tidiness, which is exactly the trade
[[no-guarantee-trading-audit-your-own-code]] forbids, and adjacent to the
[[one-router-one-evaluator]] ruling against new per-FQN entry points.

**Do this instead: partition them in place, with a shrink-only ledger.** The catalog
already has partitions (`Lite.*`, `INTERNAL_DESUGAR`, `ENGINE_VOCAB_SHIMS`), so a
`RESOLUTION_ONLY` partition plus a test asserting it is exactly the measured set —
shrink-only — makes the claim honest, keeps the guarantee compile-time, and turns the
8% into a visible burn-down. And **delete `loadValuesToDbTable`**: zero callers in
either checkout.

### 3l. `SHAPE_FILES` and `LIBRARY_FILES` — the dependency batch 155 created

The answer to §3i ("corpus needs enter by file") names two lists in
`core/src/test/java/com/legend/rcorpus/Corpus.java`. They are themselves upstream
dependencies, and they are the ones with the worst failure mode in the repo.

**`LIBRARY_FILES` — 6 named engine files, admitted WHOLE as program libraries.**
**[V]** They contribute **72 Class, 6 Enum and 649 function declarations** to the
corpus's global module. Each is a library a corpus family imports by name: the
relational compiler's own model vocabulary (`pureToSQLQuery.pure`),
`toPostgresModel`'s helpers (`sqlDialectTranslation/utils.pure`), and four
engine-core test fixtures (`testTdsToRelation`, the PCT `testModel`, the router
preeval fixtures, `testToJson`). A Pure `import` only shortens names — nothing ties a
package to a file — so the files must be **named**.

**`SHAPE_FILES` — 64 named engine files whose CLASSES AND ENUMS enter the graph and
whose FUNCTIONS do not.** **[V]** They contribute **508 Class and 30 Enum**
declarations, while **743 function declarations in the same files are deliberately
excluded** — those are the engine's own machinery (plan generation, the SQL printer,
routing), which this platform implements in Java or walls by name.

**And here is the thing worth seeing.** Batch 155 did not remove a hidden dependency;
it **changed its shape**, and arguably for the worse on one axis:

| | before batch 155 | after |
|---|---|---|
| where the corpus's engine shapes lived | **253 classes inside `prelude.pure`** | **70 hardcoded upstream paths** admitting 538 shapes + 78 library elements + 649 library functions |
| what happens when upstream moves one | the generated prelude changes → **byte-parity assert fails loudly** | `if (!Files.isRegularFile(f)) continue;` → **silent skip** (`MinimalCorpus:312`, `:396`) |

The cleanup was right on its own terms — the prelude became honestly "the platform",
0% corpus-demanded (§3i) — but the dependency moved from a **generated,
parity-guarded artifact** to **exact paths with silent-skip semantics**. That is why
§6 item 2 is not cosmetic: making those paths fail loudly restores the guard that
this otherwise-correct cleanup gave up.

### 3m. The 42 are hand-typed natives — and 8 of them SUPPRESS a real upstream body

**USER hypothesis:** the 42 are unimplemented; they sit in `Pure.java` only because
upstream declares them `native` (had upstream given them a Pure body, they would have
arrived via the prelude or `SHAPE_FILES`/`LIBRARY_FILES`); we do not need the
implementation, so the signature could live in the prelude — without the `native`
tag — or go through the LIB/SHAPES route.

**[V] Measured. The hypothesis is right about the mechanism, and the split is
23 / 8 / 11 — with one finding that is worse than the hypothesis assumed.**

#### How upstream declares each of the 42

| upstream form | count | consequence |
|---|---:|---|
| `native` only — no Pure body anywhere | **23** | a signature is the *only* possible representation |
| bodied `function`, no native | **9** | **upstream implements it in Pure** — 4 of them inside a platform root |
| both a native and a bodied overload | **6** | |
| not located by a single-line grep | 3 | `isAlphaNumeric` **is** declared upstream (multi-line header); `moduleExtension` and `_range` unresolved — regex artefacts, not proven absences |

#### The finding: we suppress 8 working upstream implementations

`prelude.pure` carries a block at line 5067 headed, verbatim:

> **PLATFORM-OWNED NAMES** — library functions whose name the platform implements (a
> registered native or an operator special form: **the native IS the definition,
> `Pure.java`**); **not carried**

**209 names** sit in that list. The generator's rule
(`PreludeGeneratorTest.generate`) is:

```java
if (!Pure.nativeFunctionsAt(simple).isEmpty() || CoreFn.of(simple).isPresent()) {
    platformOwnedNames.add(pf.fqn());
    continue;                    // the library's BODY stays out
}
```

So a declared signature causes upstream's bodied function to be **excluded from the
prelude**. The rule exists for a real reason (batch 169: legend-pure's
`meta::pure::tds::join` captured the corpus's bare `join` calls away from the built-in
form) — but **it keys on "a signature exists", not on "the platform implements it."**

**[V] Intersecting the 209 excluded names with the 42:**

| upstream Pure implementation suppressed | corpus callers |
|---|---:|
| `meta::pure::tools::noDebug` | **56 files** |
| `meta::pure::functions::meta::pathToElement` | **29** |
| `meta::pure::functions::meta::_subTypeOf` | 10 |
| `meta::pure::functions::meta::reactivate` | 7 |
| `meta::pure::functions::meta::getHiddenPayload` | 4 |
| `meta::pure::mapping::resolveStore` | 3 |
| `meta::pure::functions::string::lastIndexOf` | 2 |
| `meta::pure::functions::meta::lenientPathToElement` | — |

**Eight upstream implementations are excluded from the prelude in favour of a native
we never wrote.** The function resolves, type-checks, and then does nothing.

#### The plan, three ways

**1. Delete the signature — the 8 above. Highest value, start here.** Removing our
declaration lets the generator carry upstream's real body, and the function may simply
begin working. Two of them are called by 56 and 29 corpus files. Independently, **fix
the generator's rule** to key on *implemented* rather than *declared*, or this
recurs with every future signature.

**2. Generate into the prelude — the 23 native-only.** A "signature without the
`native` tag" is **not expressible**: a Pure `function` declaration requires a body.
But `native function` *in* `prelude.pure` is — **[V]** `Prelude.java` parses the
resource with the same `ElementParser` that has `nativeFunctionElement()`
(`ElementParser:2403`); `prelude.pure` simply contains none today. That is strictly
better than the status quo: **generated and parity-guarded instead of hand-typed.**

> **This corrects §3k.** That section argued against relocating signatures because the
> catalog is fixed at class-load and an extension point would trade the guarantee
> away. That argument was against a *runtime-mutable* catalog. The prelude is a
> **static committed resource loaded once** — as static as `Pure.java` — so the
> guarantee is preserved and the objection does not apply to what was actually
> proposed. The real cost is narrower: **[V]** natives reach the type checker only
> through `Pure.nativeFunctionsAt`, consulted at five sites
> (`FunctionCompiler:35,137`, `ResolvedNames:29`, `StatementInline:208`,
> `Scalars:2486`); those would also have to consult the prelude's natives. Bounded,
> static-to-static, no guarantee lost.

**3. Admit the file — the engine-side bodied ones.** `defaultExtensions`, `save`,
`asString` (15 bodied declarations upstream), `testedBy`, `toJson` have Pure bodies in
**legend-engine**, outside the prelude's T1 platform roots, so the prelude will never
reach them. The route is `LIBRARY_FILES` / `SHAPE_FILES` — admit the defining file —
which is the second half of the hypothesis, and correct.

**Net:** of the 42, **8 should lose their signature**, **23 should become generated
prelude natives**, ~11 should come in by file, and `loadValuesToDbTable` should be
deleted (zero callers anywhere). What should *not* happen is the status quo: 42
hand-typed claims, 8 of which actively suppress a working implementation.

### 3n. The two axes of "native", measured from the running registries

**USER correction 2026-09-10:** upstream's `native` = "the body is Java" (an
implementation detail); our `Pure.java` = "the platform lowers this" (a semantic claim).
They share a keyword and nothing else. Sections §3j/§3k/§3m measured "named somewhere in
Java" and called it "implemented"; that is the conflation. This section re-measures
both axes properly. Everything below is **[V]**.

#### Axis U — how UPSTREAM declares each function, in the roots the prelude reads

| roots | native FQNs | bodied FQNs |
|---|---:|---:|
| legend-pure platform roots (9) | 173 | 1,083 |
| engine roots the prelude scans (3) | 114 | 13,103 |
| union | **286** | **14,182** (46 both) |

The Pure platform is **98% Pure**. Which 2% upstream wrote in Java is upstream's business.

Our `Pure.java`: **881 overloads = 520 FQNs = 491 upstream-named + 29 `meta::legend::lite`.**
Of the 491 on axis U: **205 upstream-native only, 238 upstream-BODIED only, 37 both, 11
outside these roots.** So half of Pure.java overrides a Pure body with a lowering (the
point of a SQL platform) and half ports upstream natives — membership is a historical
mix, not a decision on either axis.

**44 upstream natives in these roots are not declared anywhere** — today "unknown
function". Eleven are reflection (`meta::newClass`, `newProperty`, `newAssociation`,
`newEnumeration`, `newLambdaFunction`, `newQualifiedProperty`, `tag`,
`compileValueSpecification`, …), four are `lang` (`new`, `copy`, `rawEvalProperty`,
`removeOverride`).

#### Axis O — does OUR platform lower it? Read from the RUNNING registries, not greps

A throwaway probe (`ZzzLoweringCoverageProbe`, run once, deleted) reflected the four
catalog-keyed lowering maps and `CoreFn`, then classified every `Pure.all()` overload:

| registry | keys |
|---|---:|
| `Scalars.RULES` | 455 |
| `Aggregates.REDUCERS` | 97 |
| `Windows.FNS` | 18 |
| `Windows.AGGREGATES` | 2 |
| **union** | **469** |
| `CoreFn` members / names | 61 / 122 |
| `Pure.WALLED_NATIVES` (by decision, with reason) | 6 |

| state (best over overloads) | overloads | FQNs | share of 520 |
|---|---:|---:|---:|
| LOWERED — a registry holds the key | 469 | 272 | 52.3% |
| COREFN — a language form | 136 | 67 | 12.9% |
| WALLED — named wall with a reason | 6 | 6 | 1.2% |
| **UNREGISTERED — none of the above** | **270** | **175** | **33.7%** |

The 175 by family: date 33, `functions::meta` 18, asserts 15, `relational::functions` 11,
`relational::metamodel` 11, tds 10, relation 9, collection 8, executionPlan 5, lang 5,
`legend::lite` 4, math 4, lineage 4, postProcessor 4, then a long tail.

**175 is an UPPER bound, not the answer.** `Scalars.lower` is the loud entry point
("no scalar lowering registered" — its own comment calls that *the registration bug*),
but **80 files** dispatch on a native callee *outside* the four registries:
`AssertVerdicts` (the 15 asserts), `CalendarAgg` (most of the 33 dates),
`CollectionLanes`, `StatementExecutor`, `compiler/spec/NativeDispatch`, `Typer`, and
the desugar IR (`Lite::otherwise` is named in 84 files and is in no registry). The
earlier 42 (§3j: "named nowhere in core main") is a sound **lower** bound: unnamed ⟹
unregistered ⟹ unimplemented. So:

> **42 ≤ unimplemented ≤ 175 by bounds; classified against the ad-hoc sites (appendix
> below): 70 implemented off-registry, 35 grey, 70 certainly unimplemented.** The exact
> number is not a *computed* fact today, because there is no single place that says what
> the platform implements. That absence is the finding. Program workstream D1 (one
> registry of claims) is what makes it one.

#### The quadrants that matter

The 175 unregistered on axis U: **37 upstream-native only → the prelude should carry a
respelled `native function`; 114 upstream-BODIED only → upstream has a Pure body we
could RUN; 9 both; 4 `legend::lite`; 11 outside the roots.**

Intersected with the prelude's 209 "platform-owned names" (bodies excluded because a
signature exists): **25** of the 175 suppress an upstream body. §3m said 8 (the 42 ∩
209). The truth is **8 ≤ suppressed ≤ 25** — and every one of them is a function that
may start working the day its signature leaves Pure.java.


#### The 175, classified against the ~80 ad-hoc dispatch sites

A mention in `PlatformTypes`, `SystemMetamodel` or `Prelude` is a **constant**, not a
handler, and is discounted. Everything else that names the FQN or its `Pure.X` constant
is listed as its handler.

| class | FQNs | meaning |
|---|---:|---|
| **OFF-REGISTRY IMPL** | **70** | named in a lowering / exec / verdict file — a real lowering the four registries cannot see. **These are the "scalar functions that DO get lowered."** |
| FRONT-END-ONLY | 35 | named only in `Typer` / `LiteralUnroll` / `ExecuteChainAssembly` / … — type-checked or desugared; whether a *call* ever lowers needs one look each |
| CONSTANT-ONLY | 31 | named only as a `PlatformTypes` / `SystemMetamodel` constant — **no handler** |
| NONE | 39 | named nowhere outside `Pure.java` — **no handler** |

**Certainly unimplemented = 70 (CONSTANT-ONLY + NONE). Implemented but invisible to any
registry = 70. Grey = 35.** So the answer to "are we sure the 175 are not scalar
functions that get lowered?" is: **no — 70 of them are exactly that**, and the reason
nobody could tell is the finding. The claim registry (program D1) is what makes each of
these three buckets a printed, enforced fact rather than a one-off classification.

<details><summary><b>OFF-REGISTRY IMPL — 70</b> (implemented; must CLAIM)</summary>

| FQN | handler(s) |
|---|---|
| `meta::legend::lite::unionScan` | RelationPredicates; UnionSynthesis; Pipelines |
| `meta::pure::functions::asserts::assert` | AssertVerdicts; LambdaBodies; LiteralUnroll; MappingProtocolParser |
| `meta::pure::functions::asserts::assertContains` | AssertVerdicts |
| `meta::pure::functions::asserts::assertEmpty` | AssertVerdicts; LambdaBodies |
| `meta::pure::functions::asserts::assertEq` | AssertVerdicts |
| `meta::pure::functions::asserts::assertEqWithinTolerance` | AssertVerdicts; VerdictQueries |
| `meta::pure::functions::asserts::assertEquals` | AssertVerdicts; LineageTreeVerdicts; LambdaBodies |
| `meta::pure::functions::asserts::assertFalse` | AssertVerdicts; LambdaBodies |
| `meta::pure::functions::asserts::assertInstanceOf` | AssertVerdicts; LiteralUnroll |
| `meta::pure::functions::asserts::assertIs` | AssertVerdicts |
| `meta::pure::functions::asserts::assertJsonStringsEqual` | AssertVerdicts |
| `meta::pure::functions::asserts::assertNotEmpty` | AssertVerdicts; LambdaBodies |
| `meta::pure::functions::asserts::assertNotEquals` | AssertVerdicts; LambdaBodies |
| `meta::pure::functions::asserts::assertSameElements` | AssertVerdicts; CanonicalDivergence |
| `meta::pure::functions::asserts::assertSize` | AssertVerdicts |
| `meta::pure::functions::boolean::is` | Lexer; Lexicon |
| `meta::pure::functions::collection::union` | Typer; Fold; GqlParser; ClassSource; GraphEmission; Lexicon |
| `meta::pure::functions::date::calendar::CYMinus2` | CalendarAgg |
| `meta::pure::functions::date::calendar::CYMinus3` | CalendarAgg |
| `meta::pure::functions::date::calendar::annualized` | CalendarAgg |
| `meta::pure::functions::date::calendar::cme` | CalendarAgg |
| `meta::pure::functions::date::calendar::cw` | CalendarAgg |
| `meta::pure::functions::date::calendar::cw_fm` | CalendarAgg |
| `meta::pure::functions::date::calendar::mtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::p12mtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::p12wa` | CalendarAgg |
| `meta::pure::functions::date::calendar::p12wtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::p4wa` | CalendarAgg |
| `meta::pure::functions::date::calendar::p4wtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::p52wa` | CalendarAgg |
| `meta::pure::functions::date::calendar::p52wtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::pma` | CalendarAgg |
| `meta::pure::functions::date::calendar::pmtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::pqtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::priorDay` | CalendarAgg |
| `meta::pure::functions::date::calendar::priorYear` | CalendarAgg |
| `meta::pure::functions::date::calendar::pw` | CalendarAgg |
| `meta::pure::functions::date::calendar::pw_fm` | CalendarAgg |
| `meta::pure::functions::date::calendar::pwa` | CalendarAgg |
| `meta::pure::functions::date::calendar::pwtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::pymtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::pyqtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::pytd` | CalendarAgg |
| `meta::pure::functions::date::calendar::pywa` | CalendarAgg |
| `meta::pure::functions::date::calendar::pywtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::qtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::reportEndDay` | CalendarAgg |
| `meta::pure::functions::date::calendar::wtd` | CalendarAgg |
| `meta::pure::functions::date::calendar::ytd` | CalendarAgg |
| `meta::pure::functions::math::mathUtility::rowMapper` | Lowerer |
| `meta::pure::functions::math::wavgUtility::wavgRowMapper` | Lowerer |
| `meta::pure::functions::math::zScore` | Lowerer |
| `meta::pure::functions::meta::genericType` | AssertVerdicts; Typer; GenericTypeReflection |
| `meta::pure::functions::meta::instanceOf` | LiteralUnroll; ResultEnvelopeSplice; Typer; Lowerer; Substitution |
| `meta::pure::functions::relation::lateral` | Lowerer |
| `meta::pure::functions::relation::offset` | Lexicon |
| `meta::pure::functions::relation::reduce` | TypedAggCol; Lowerer |
| `meta::pure::functions::relation::rows` | AssertVerdicts; Frames; ResultEnvelopeSplice; TdsSurfaceReads; Typer; ExecutionResult; JsonEmission; Anchors; Lexicon |
| `meta::pure::functions::variant::navigation::get` | JoinChecker; LiteralUnroll; Typer; ScanRelations; Lowerer; MappingNormalizer; RelOpTranslator; JsonSourceFrame |
| `meta::pure::tds::getBoolean` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getDate` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getDateTime` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getDecimal` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getFloat` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getInteger` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getNumber` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getStrictDate` | JoinChecker; Typer; RowGetters |
| `meta::pure::tds::getString` | JoinChecker; Typer; RowGetters; Substitution |
| `meta::relational::metamodel::execute::executeInDb` | ConnectionLets |
| `meta::relational::tests::csv::toCSV` | AssertVerdicts |

</details>

<details><summary><b>FRONT-END-ONLY — 35</b> (grey; adjudicate each)</summary>

| FQN | named in |
|---|---|
| `meta::alloy::objectReference::decodeObjectReferencesAndGetPkMap` | ObjectReferenceDecode |
| `meta::alloy::objectReference::generateObjectReferences` | ObjectReferenceArms |
| `meta::alloy::objectReference::generateObjectReferencesForGivenSetId` | ObjectReferenceArms |
| `meta::legend::lite::legacyAssocPredicate` | AssociationSynthesis; MappingNormalizer; XStorePureEnds |
| `meta::legend::lite::legacyLocalProperty` | MappingNormalizer; XStorePureEnds |
| `meta::legend::lite::otherwise` | MappingNormalizer; Substitution |
| `meta::pure::alloy::connections::relationalMapperPostProcessor` | RelationalMapperRenames |
| `meta::pure::executionPlan::execute` | ValidateDesugar |
| `meta::pure::executionPlan::featureFlag::withFeatureFlags` | ExecuteChainAssembly; StoreResolver |
| `meta::pure::functions::collection::keyValues` | LiteralUnroll |
| `meta::pure::functions::collection::objectReferenceIn` | Substitution |
| `meta::pure::functions::collection::paginated` | Typer |
| `meta::pure::functions::date::convertTimeZone` | RelOpTranslator |
| `meta::pure::functions::lang::dynamicNew` | LiteralUnroll |
| `meta::pure::functions::lang::extractEnumValue` | Typer |
| `meta::pure::functions::lang::subType` | ScanRelations; IslandScan; SpecParser; DataQualityValidationSectionGrammar; AggregationAwareRouting; CorrelatedSubselects; InnerDemand; Substitution |
| `meta::pure::functions::lang::whenSubType` | AggregationAwareRouting |
| `meta::pure::functions::math::olap::averageRank` | Typer |
| `meta::pure::functions::meta::enumValues` | LiteralUnroll |
| `meta::pure::functions::meta::newUnit` | SpecParser |
| `meta::pure::functions::meta::sourceInformation` | ProtocolEmitter; TailEmitter |
| `meta::pure::functions::relation::ascending` | CoreFn |
| `meta::pure::functions::relation::descending` | CoreFn |
| `meta::pure::functions::relation::unbounded` | Frames |
| `meta::pure::graphFetch::execution::alloyConfig` | GraphEmission |
| `meta::pure::mapping::withMapping` | FromChecker |
| `meta::pure::metamodel::relation::newTDSRelationAccessor` | CoreFn |
| `meta::pure::router::execute` | ExecuteChainAssembly; Typer; ValidateDesugar |
| `meta::pure::router::preeval::preval` | ExecuteChainAssembly |
| `meta::pure::tds::tdsContains` | InnerDemand; Substitution |
| `meta::relational::extension::relationalExtensions` | TestDataGenerationNatives |
| `meta::relational::milestoning::concatenateTemporalTdsQueries` | ExecuteChainAssembly |
| `meta::relational::postProcessor::cteExtraction::extractSubqueriesAsCTEs` | ContextReading |
| `meta::relational::postProcessor::nonExecutable` | ContextReading |
| `meta::relational::postProcessor::replaceTables` | ContextReading |

</details>

<details><summary><b>CONSTANT-ONLY — 31</b> (certainly unimplemented; only a constant mentions it)</summary>

| FQN | |
|---|---|
| `meta::alloy::service::execution::setUpDataSQLs` | — |
| `meta::alloy::service::execution::setUpDataSQLsV2` | — |
| `meta::core::runtime::connectionByElement` | — |
| `meta::legend::executeLegendQuery` | — |
| `meta::pure::executionPlan::executionPlan` | — |
| `meta::pure::executionPlan::toString::planToString` | — |
| `meta::pure::executionPlan::toString::planToStringWithoutFormatting` | — |
| `meta::pure::functions::asserts::assertError` | — |
| `meta::pure::functions::relation::assertTdsEquivalent` | — |
| `meta::pure::lineage::scanColumns::scanColumns` | — |
| `meta::pure::lineage::scanProperties::propertyTree::buildPropertyTree` | — |
| `meta::pure::lineage::scanProperties::scanProperties` | — |
| `meta::pure::lineage::scanRelations::scanRelations` | — |
| `meta::pure::mapping::withChainedMappings` | — |
| `meta::relational::functions::sqlQueryToString::createDbConfig` | — |
| `meta::relational::functions::sqlstring::toNonExecutableSQLString` | — |
| `meta::relational::functions::sqlstring::toSQL` | — |
| `meta::relational::functions::sqlstring::toSQLString` | — |
| `meta::relational::functions::sqlstring::toSQLStringPretty` | — |
| `meta::relational::functions::toDDL::createSchemaStatement` | — |
| `meta::relational::functions::toDDL::createTableStatement` | — |
| `meta::relational::functions::toDDL::dropAndCreateSchemaInDb` | — |
| `meta::relational::functions::toDDL::dropAndCreateTableInDb` | — |
| `meta::relational::functions::toDDL::dropSchemaStatement` | — |
| `meta::relational::functions::toDDL::dropTableStatement` | — |
| `meta::relational::metamodel::execute::executeInDbToTDS` | — |
| `meta::relational::metamodel::execute::fetchDbColumnsMetaData` | — |
| `meta::relational::metamodel::execute::fetchDbPrimaryKeysMetaData` | — |
| `meta::relational::metamodel::execute::fetchDbSchemasMetaData` | — |
| `meta::relational::metamodel::execute::fetchDbTablesMetaData` | — |
| `meta::relational::metamodel::execute::loadCsvToDbTable` | — |

</details>

<details><summary><b>NONE — 39</b> (certainly unimplemented; named nowhere)</summary>

| FQN | |
|---|---|
| `meta::pure::extension::defaultExtensions` | — |
| `meta::pure::extension::moduleExtension` | — |
| `meta::pure::functions::boolean::equalJsonStrings` | — |
| `meta::pure::functions::collection::getIfAbsentPutWithKey` | — |
| `meta::pure::functions::collection::getMapStats` | — |
| `meta::pure::functions::collection::removeAllOptimized` | — |
| `meta::pure::functions::collection::replaceAll` | — |
| `meta::pure::functions::lang::evaluate` | — |
| `meta::pure::functions::meta::_subTypeOf` | — |
| `meta::pure::functions::meta::canReactivateDynamically` | — |
| `meta::pure::functions::meta::elementPath` | — |
| `meta::pure::functions::meta::enumName` | — |
| `meta::pure::functions::meta::generalizations` | — |
| `meta::pure::functions::meta::genericTypeClass` | — |
| `meta::pure::functions::meta::getHiddenPayload` | — |
| `meta::pure::functions::meta::getUnitValue` | — |
| `meta::pure::functions::meta::lenientPathToElement` | — |
| `meta::pure::functions::meta::openVariableValues` | — |
| `meta::pure::functions::meta::pathToElement` | — |
| `meta::pure::functions::meta::reactivate` | — |
| `meta::pure::functions::meta::subTypeOf` | — |
| `meta::pure::functions::relation::_range` | — |
| `meta::pure::functions::string::isAlphaNumeric` | — |
| `meta::pure::functions::string::lastIndexOf` | — |
| `meta::pure::functions::test::testedBy` | — |
| `meta::pure::functions::variant::convert::toJson` | — |
| `meta::pure::mapping::resolveStore` | — |
| `meta::pure::metamodel::relation::stringToTDS` | — |
| `meta::pure::mutation::save` | — |
| `meta::pure::router::printer::asString` | — |
| `meta::pure::test::pct::loadPCTManifest` | — |
| `meta::pure::test::surveyor::executePCTTest` | — |
| `meta::pure::test::surveyor::executeTest` | — |
| `meta::pure::tools::noDebug` | — |
| `meta::relational::metamodel::execute::createTempTable` | — |
| `meta::relational::metamodel::execute::dropTempTable` | — |
| `meta::relational::metamodel::execute::loadValuesToDbTable` | — |
| `meta::relational::metamodel::relation` | — |
| `meta::relational::postProcessor::cteExtraction::extractSubQueriesAsCTEsPostProcessor` | — |

</details>


#### Reproducing §3n — the probe source and the commands

The probe was a throwaway JUnit test, run once and deleted so it never became a silent
member of gate 1. Its output (`core/target/lowering-coverage-probe.txt`) is ephemeral;
the classification it fed is committed as **`docs/NATIVE_CLAIMS_CENSUS_2026_09_10.tsv`**
(175 rows, 4 classes, handler per row). To re-run:

1. Recreate `core/src/test/java/com/legend/ZzzLoweringCoverageProbe.java` from the source
   below; `mvn -q -pl core test -Dtest=ZzzLoweringCoverageProbe -Dsurefire.excludedGroups=
   -DfailIfNoTests=false`; delete the file again.
2. `LEGEND_ENGINE_ROOT=… LEGEND_PURE_ROOT=… tools/native-axes.py --tsv /tmp/claims.tsv` —
   axis U, the axis-O summary from the probe file, and the 4-class handler census.

The natural permanent home is `tools/diagnostics.sh` (the measurement battery, triggered
not scheduled) — **after** batch 3 turns it from a probe into the claim-completeness test.

```java
// THROWAWAY PROBE — reflects the four catalog-keyed lowering maps and CoreFn, classifies
// every Pure.all() overload: LOWERED | COREFN | WALLED | UNREGISTERED.
package com.legend;
import com.legend.builtin.Pure; import com.legend.model.NativeFunctionDefinition;
import org.junit.jupiter.api.Test; import java.lang.reflect.Field; import java.util.*;
class ZzzLoweringCoverageProbe {
  @SuppressWarnings("unchecked")
  static Set<String> keysOf(String cls, String field) throws Exception {
    Class<?> c = Class.forName(cls, true, Pure.class.getClassLoader());
    Field f = c.getDeclaredField(field); f.setAccessible(true);
    return new HashSet<>(((Map<String, ?>) f.get(null)).keySet());
  }
  @Test void dump() throws Exception {
    Set<String> reg = new HashSet<>();
    for (String[] r : new String[][]{{"com.legend.lowering.Scalars","RULES"},{"com.legend.lowering.Windows","FNS"},
        {"com.legend.lowering.Windows","AGGREGATES"},{"com.legend.lowering.Aggregates","REDUCERS"}}) reg.addAll(keysOf(r[0], r[1]));
    Set<String> coreFn = new HashSet<>();
    for (Object e : com.legend.compiler.spec.CoreFn.values()) { coreFn.add(e.toString());
      for (Field fld : e.getClass().getDeclaredFields()) if (fld.getType()==String.class && !java.lang.reflect.Modifier.isStatic(fld.getModifiers())) { fld.setAccessible(true); coreFn.add(String.valueOf(fld.get(e))); } }
    List<String> order = List.of("LOWERED","COREFN","WALLED","UNREGISTERED"); Map<String,String> best = new TreeMap<>();
    for (NativeFunctionDefinition d : Pure.all()) {
      String fqn = d.qualifiedName(), simple = fqn.substring(fqn.lastIndexOf(':')+1);
      String st = reg.contains(d.signatureKey()) ? "LOWERED" : coreFn.contains(simple) ? "COREFN"
               : Pure.walledNativeReason(fqn) != null ? "WALLED" : "UNREGISTERED";
      String prev = best.get(fqn); if (prev == null || order.indexOf(st) < order.indexOf(prev)) best.put(fqn, st);
    }
    StringBuilder out = new StringBuilder("@@PROBE registered keys: " + reg.size() + "\n");
    Map<String,Long> by = new TreeMap<>(); best.values().forEach(v -> by.merge(v, 1L, Long::sum));
    out.append("@@PROBE FQNs by best state: ").append(by).append('\n');
    best.forEach((f, st) -> { if (st.equals("UNREGISTERED")) out.append("@@U ").append(f).append('\n'); if (st.equals("WALLED")) out.append("@@W ").append(f).append('\n'); });
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/lowering-coverage-probe.txt"), out.toString()); System.out.print(out);
  }
}
```

**Method bounds for every census in this section.** Axis U (286 / 14,182 / 491 / 205 /
238 / 37 / 11) is a single-pass regex over declaration headers; a header split across
lines in an unusual way is missed (3 of the 42 in §3m were exactly that). The handler
census is a string-literal / constant-reference scan: a mention is evidence of a handler,
not proof of a lowering, which is why the 70 "off-registry" are labelled *implemented,
verify*, and why the 35 grey exist at all. The registry half (469 keys, 272/67/6/175) is
exact — it is read from the running maps. Treat every other number as exact to within the
regex.
#### Two more hand lists this exposed

- **`Scalars.KNOWN_ABSENT`** — "names known to be ABSENT from our catalog": 39 entries,
  **38 now present** in Pure.java. 97% stale. Harmless only because its branch is dead.
  The pattern in miniature.
- The 3 `legend::lite` natives in no registry (`otherwise`, `legacyAssocPredicate`,
  `legacyLocalProperty`) are desugar IR handled in the front end — *implemented*, but
  by a path no registry records. Exactly the case the claim registry must cover.

#### What this changes

- §3j's "92% named by the platform" is a statement about **mentions**, not lowerings.
  Retract "the split is not warranted": there **is** a warranted split, along the
  ours/theirs axis (program §0, §3 D).
- §3k's "do not relocate" objection was to a runtime-mutable catalog; the prelude is
  static (§3m). Relocation to the prelude is correct for every unclaimed entry.
- §3m's plan ("8 delete / 23 generate / 11 by file") becomes: **every unclaimed entry
  leaves Pure.java** (≥ 42, ≤ 175); the prelude carries it as body or respelled native
  according to axis U; the exclusion rule keys on claims.
- The previous draft of the program said "generate Pure.java from upstream natives."
  **Wrong** — that makes Pure.java = "what upstream wrote in Java," an implementation
  detail of theirs. Membership is our claim; only the signature text is derived.

### 3o. Receipts for the feature table and the topology decision (program §2b, §6.3)

**Module topology — measured, not chosen.** `mvn dependency:list` on both jar modules:
`pct` resolves 142 artifacts, `parser-equivalence` 389, **135 shared, 70 at different
versions** — 65 legend (the version spread batch 1 removes) and 5 third-party:
HikariCP 3.4.5 vs 7.0.2, commons-lang3 3.5 vs 3.18.0, deephaven-csv 0.18 vs 0.19, junit
4.13.1 vs 4.13.2, httpcore 4.4.9 vs 4.4.13. **A first draft said these "persist" after one
release — a GUESS, and traced it is wrong:** `dependency:tree -Dincludes` shows pct's
`commons-lang3:3.5` under `legend-pure-m3-core:5.88.0` and parser's `3.18.0` under
`legend-engine-language-pure-grammar:4.138.2`; pct's `HikariCP:3.4.5` under
`…pure-compiler:4.133.0 → shared-core → pac4j`, parser's `7.0.2` under
`…persistence-test-runner:4.138.2 → relationalStore-executionPlan-connection`. Version
drift, not module-inherent. Whether any survive at 4.138.2 is unverified (not
re-resolved), but the dependency conflicts are **not** a reason to keep the modules apart. `parser-equivalence/pom.xml:113-121` records that upstream
test-jars carry ServiceLoader registrations that **altered the oracle** (a stale error pin
and 3 corpus rows flipping), so its classpath is quarantined deliberately. The rcorpus
harness, generators and census need **no upstream jar** — `core/test` has zero
`org.finos.legend` imports. Surefire differs trivially (`pct.reuseForks`;
`surefire.excludedGroups` on core). **Verdict: three modules** — `spec` (checkouts
only), `pct` (PCT jars), `parser-equivalence` (oracle jars, ServiceLoader-sensitive) —
not one, not four. The verdict rests on the ServiceLoader hazard and on `spec` needing no
jars, **not** on the dependency conflicts.

**NLQ.** 28 Java files declare/import `org.finos.legend.engine.nlq`; non-Java references:
`nlq/pom.xml` (`mainClass`), `README.md`, this doc. It imports **no** real upstream class.

**`SystemMetamodel`** (1,507 lines): our own Pure source (Database + Mapping + navigation
functions, inline), parsed at class load. **127 distinct FQNs: 23 `meta::lite::*` (ours),
104 upstream-named** — `relational::metamodel` 42, `pure::mapping` 22, `pure::metamodel`
10, `relational::mapping` 9, `executionPlan` 6. The 104 are spellings of upstream
declarations with no oracle — the `PlatformTypes` class of problem, second instance.

**Lexer / grammar.** `Lexer.KEYWORDS` has 56 entries. `docs/g4-keyword-snapshot.tsv`
(538 rows, columns `keyword / grammars / status`) is written from the engine's 73 `.g4`
files (`GrammarKeywordCensusTest` reads `legend.engine.root`) and **asserted** by
`SurfaceCensusTest:147`: any engine keyword absent from the snapshot fails with "classify
them". Loud on upstream addition; no shrink direction.

**`tools/fqn-mapping.json`**: 467 rows, **zero readers** anywhere in the tree (Java, py,
sh, md); last commit `b0618ad5c` 2026-07-08 ("the FULLY-QUALIFIED catalog"). Dead.

**PCT adapter**: `pct/src/main/resources/core_legend_lite_pct/pct_adapter.pure`, 479
lines, our Pure implementing upstream's `GrammarExtension`; Java side extends
`PCTReportConfiguration` (×5) and implements `PCTReportProvider`. Ours; the seam is
upstream's API (K3), self-guarding by compile.

## 4. What bumping to latest costs, measured today

**[V]** File-set drift between the pinned checkouts and engine 4.145.0 /
pure 5.99.0, per gate universe (`git ls-tree` vs the GitHub trees API):

| universe | pinned | latest | added | removed |
|---|---:|---:|---:|---:|
| engine, all `.pure` (parser corpus C3/C10) | 3,180 | 3,253 | +75 | −2 |
| pure, all `.pure` (C10) | 275 | 281 | +6 | 0 |
| engine, relational corpus (gates 4/5) | 543 | 552 | +9 | 0 |
| pure, `PLATFORM_ROOTS` (spec census + prelude) | 261 | 267 | +6 | 0 |
| engine, `src/test` `.java` (inline tiers C4/C12) | 1,396 | 1,410 | +14 | 0 |
| pure, `src/test` `.java` (inline tier C5) | 571 | 582 | +26 | **−15** |
| engine, `.txt` resources (C11) | 78 | 80 | +2 | 0 |

### 4a. The headline correction: the path hazard did not fire

**[V] All 132 hardcoded upstream paths still resolve at engine 4.145.0 and pure
5.99.0. Zero breaks.** Checked: 64 `SHAPE_FILES`, 6 `LIBRARY_FILES`, 3 derived
corpus roots, 9 `PLATFORM_ROOTS`, 4 prelude-generator roots, the prelude's corpus
root, `m3.pure`, the exclusion-map key, **10 ChannelB scope roots**, and all 33
ledger path keys (25 skew + 8 refusal) — against both the pinned checkouts and
the latest trees.

> The first pass of this census said **122** and missed the ten ChannelB roots
> entirely, because it walked the readers of `legend.engine.root` in `core/` and
> the ChannelB suites live in `pct/`. They are the sites that matter most in
> §4b. That miss is the argument for `tools/upstream-drift.py` over any
> hand-written list: the number has to come from parsing the source, every run.

The opening fact sheet's "the real hazard is paths, not versions" is right about
the *mechanism* and wrong about the *frequency*. Across 15 engine releases and 9
pure releases, upstream moved not one file we name. Upstream's layout is stable;
our paths are not the thing that will break this bump. They still need to fail
loudly (§6, item 2) — it is cheap, and one silent miss already cost 49 tests —
but it is latent-risk work, not the bump's critical path.

### 4b. What will actually break

**[V] A stdlib function moved between repositories — and it moves two pins in
opposite directions.** `core_functions_unclassified/string/slice/{left,right}.pure`
was **deleted from legend-engine** and appeared as
`platform/pure/essential/string/slice/{left,right}.pure` **in legend-pure**. Both
directories are hardcoded ChannelB scope roots, and both still resolve — so a
path check sees nothing. What actually moves:

| | at the pin | at latest | delta |
|---|---|---|---|
| engine `core_functions_unclassified` — `ChannelBUnclassifiedTest`'s scope | 67 `.pure`, `string/slice/` holds left + right | 65 `.pure`, `string/slice/` **gone** | −2 files |
| `left.pure` + `right.pure` PCT.test, engine side | 3 + 3 | — | **−6** |
| `left.pure` + `right.pure` PCT.test, pure side | — | 5 + 6 | **+11** |

ChannelB discovers any function with a stereotype *named* `test`
(`ChannelB.java:189`), so the arithmetic is direct. The unclassified scope's file
set changes **only** by those two removals (67 − 2 = 65, nothing added), so
`ChannelBUnclassifiedTest`'s exact pin goes **95 → 89**, plus whatever content
drift the 65 surviving files carry. `ChannelBEssentialTest`'s **327 → 345**: all
four new `PCT.function` files sit under `platform/pure/essential`, carrying 18
PCT.test between them (the relocated pair also *grew* coverage, 3 → 5 and
3 → 6), again plus content drift.

So the relocation is not merely invisible to a path check — it is the reason two
exact-equality pins move in opposite directions, which reads as two unrelated
regressions unless you know the file moved.

**[V] Six new platform FILES in legend-pure — but only two new functions.**
Corrected after checking each one against the pinned checkouts: upstream 5.93→5.99
has been **splitting platform sources into per-function files** under `essential/`,
so "+6 files" is mostly reorganisation.

| new file under `platform/pure/essential/` | at the pin | what the bump actually is | we already declare it? |
|---|---|---|---|
| `string/slice/left.pure` | legend-**engine** `core_functions_unclassified` | **relocated** engine → pure | **yes** — `Pure.java` + catalog |
| `string/slice/right.pure` | legend-**engine** `core_functions_unclassified` | **relocated** engine → pure | **yes** |
| `string/slice/substr.pure` | **absent** | **genuinely new** (2 overloads) | no |
| `math/round/binFloor.pure` | **absent** | **genuinely new** | no |
| `meta/graph/elementPath.pure` | legend-pure, a different file | **relocated** within pure | **yes** — `Pure.java:1185` |
| `meta/graph/lenientPathToElement.pure` | legend-pure, a different file | **relocated** within pure | **yes** — `Pure.java:1184` |

**So the consequences are smaller and different from an earlier draft of this
section, which claimed "six new platform functions" and "two new natives = two new
`Pure.java` signatures". Both were wrong:**

- **Zero new `Pure.java` signatures.** `elementPath` and `lenientPathToElement` are
  already declared (and sit in the §3j resolution-only set — declared, unimplemented);
  `left` and `right` are already declared at the correct pure FQN, so their move out
  of the engine changes nothing in our catalog.
- **Two genuinely new functions**, `substr` (2 overloads) and `binFloor`. Both are
  `<<PCT.function>>` — Pure-defined upstream, not native — so they need our platform
  to **compile and execute** their bodies, not to declare a signature.
- **Of the 18 new `PCT.test` functions, 11 are added coverage on functions we already
  have**: the relocated pair grew tests on the way across (`left` 3 → 5, `right`
  3 → 6). Only 7 belong to the two new functions.
- The five Channel B discovery pins still move, and the arithmetic in §4b above still
  holds — the relocation is what moves them, independently of whether the functions
  are new to us.

**[V] +14 new `<<test.Test>>` functions in the relational corpus**, from 9 new
files. The families name the feature work a bump imports:
`sqlQueryToString/testSuite/testNullOrderingSupport` (9) and
`testUseDbNativeImplicitNullOrderingResolution` (5) are **dialect null-ordering**;
`mutation/` is non-relational-to-relational; `tests/semistructured/` ×4 is the
store-language / semi-structured family.

**[V] Plus 6 new `<<paramTest.Test>>` functions** in those same files — a
stereotype the harness does not discover at all. 43 engine `.pure` files already
use it at the pin, and `docs/TWO_DESIGN_LEGS_2026_09_07.md` P-29 already flags
151 undiscovered tests. A bump grows a known blind spot; it does not create it.

**[V] 15 removed pure `src/test` `.java` files** (the
`runtime-java-engine-compiled` serialization tests) — inline-snippet tier C5
loses its rows, so the corpus manifest diff will show removals as well as
additions.

---

## 5. The ordered procedure

No written procedure existed before this document. `docs/GATES.md` and
`tools/diagnostics.sh` both *refer* to "the oracle-pin bump procedure" as
something that exists; neither writes it down. This is it.

**M** = mechanical, safe to automate. **J** = judgement, must be reviewed.

### Phase 0 — decide the target (M)

1. `tools/version-report.sh` — read the four identities and the gap. Then
   `tools/upstream-drift.py --tests <engine> <pure>` — read the cost: whether the
   hardcoded paths survive, how far each gate's universe moves, and which test
   families the bump imports. A bump whose drift you have not read is a bump
   whose re-pinning you cannot review.
2. Choose the target engine release from **Maven Central**, not from git tags
   (4.142.0 is tagged and unpublished). Derive the pure version from that
   release's own `<legend.pure.version>`: INV-1 is not optional.
3. Decide whether this bump also closes **INV-3** by moving `pct/pom.xml` onto
   the same release. Recommended: yes, as its own commit, before the source pin
   moves — Channel A/B is a dual-verdict pair and is currently incomparable.

### Phase 1 — move the pins (M)

4. Move both local checkouts to the target commits. Run with
   `ORACLE_PIN_CHECK=0` from here until step 6.
5. `tools/oracle-pins.env` — update both SHAs and both `*_DESCRIBE` lines.
6. The jar identities, together, preserving INV-1/2/4:
   `parser-equivalence/pom.xml` (`legend.engine.version` **and** the hardcoded
   `legend-pure-m3-core` version), `pct/pom.xml` (both properties),
   `tools/engine-runner/pom.xml`.
7. `tools/version-report.sh --check` must exit 0 before going further.

### Phase 2 — regenerate (M to produce, J to accept)

| artifact | command | review |
|---|---|---|
| `prelude.pure` | `mvn -pl core test -Dtest=PreludeGeneratorTest -Dprelude.generate=1` | **J** — the diff is the upstream shape change |
| `corpus-manifest.tsv` | `mvn -pl parser-equivalence test -Dtest=CorpusManifestTest -Dcorpus.manifest.regen=1`, then copy `target/corpus-manifest.tsv` over the resource | **J** — added/removed/changed rows are the drift |
| `engine-grammar-fixtures-<new>.jsonl` | `mvn -pl parser-equivalence test -Pengine-fixture-harvest -Dtest=ZEngineFixtureHarvest` | **J** — and **rename the file**; INV-4 is a filename |
| `docs/RELATIONAL_CORPUS.md` | gate 4 rewrites it in place | **J** — new tests appear as rows |

### Phase 3 — run the chain, let three platforms report (M)

8. `tools/allgates.sh` ONCE, in the background, all nine gates
   (`docs/GATES.md`; 12-minute budget). Do not hand-run the lanes first.
9. Push the branch: CI runs the same nine gates on Linux, macOS and Windows
   against the new pins, via `tools/oracle-pins.env` + `.github/actions/gate-env`.
10. `tools/diagnostics.sh` — an oracle-pin bump is its documented **trigger 2**,
    and `diagnostics.yml` already fires on a change to `oracle-pins.env`.

### Phase 4 — re-pin the scoreboards (J, every row)

Expect each of these to move; each needs a reason, not just a new number:

- the five Channel B discovery pins: `137 / 327 / 204 / 355 / 95`
- gate 7's ceilings in `tools/allgates.sh`: `run>=348, fail<=1, err<=22`
- `SpecBodyCensusTest`: `walled <= 23`, `loadWalls <= 1`, the bucket pins
- the rcorpus rosters: `{duckdb,h2}-{accepted,fail,skipped}-roster.txt`
  (108 / 565 fail rows today)
- the parser ratchets — `MIN_DOCS_MATCHED 6489`, `MIN_BEHAVIOUR_MATCHED 2093`,
  `MIN_PINS 424`, `MAX_PLATFORM_CATALOG 1633`, and the rest of the live-ratchet
  table in `docs/GATES.md §"Live ratchet constants"` (the **source** is authority)
- `GrammarCoverageCensusTest`'s ratchets (diagnostics battery)
- the PCT expected-failure lists (27 + 9 + 1 rows across the suites)

### Phase 5 — adjudicate the ledgers (J)

- `docs/version-skew-claims.tsv` — 25 rows, most annotated "re-adjudicate at
  re-pin". A row whose skew the new oracle no longer has must be **removed**, not
  left to rot.
- `docs/refusal-allowlist.tsv`, `docs/model-refuse-allowlist.tsv`.
- **Shrink-only means shrink**: a pin that a bump happens to make easier gets
  ratcheted down in the same commit.

### Phase 6 — the spec surfaces nothing checks (J)

- **The 17 protocol wire goldens (§1b).** Re-capture with `ProbeWireShapes`
  against the NEW oracle jars and diff the 17 `EXPECTED_*` constants by hand —
  today this is the only way they can move, and nothing will tell you they should.
- New upstream natives → new `Pure.java` signatures (`elementPath` this time).
- Changed upstream signatures → silent today. Until §6 item 3 lands, this is a
  manual diff of the upstream `.pure` natives against `native-catalog.txt`.
- New `.pure` files in `PLATFORM_ROOTS` that the prelude generator's exclusion
  lists should or should not admit.
- Fix the stale `5.88.1` oracle comments named in §1a.

---

## 5b. Module layout: where each upstream-dependent thing belongs

**USER proposal 2026-09-10:** anything with a hard pure/engine dependency —
generator or runtime — should live outside `core`, in its own module, the way
`pct` does.

**Verdict: adopt it, with one corrected boundary.** The rule is not "anything that
*depends on* upstream moves out" — it is:

> **Anything that READS upstream moves out of `core`. Anything upstream-DERIVED
> stays in `core` as a generated resource.**

The distinction is load-bearing. `Pure.java`, `PlatformTypes`,
`NameResolver.CORE_IMPORTS` and `prelude.pure` are *derived from* upstream, but
`core` must compile and type-check with **no legend-engine on the classpath at
all** — that is the whole value of a clean-room implementation. Moving them out
would invert the dependency (`core` → new module → engine) and destroy it.
`prelude.pure` already demonstrates the right shape: **generator outside, generated
resource inside.**

### The table

| functional thing | what it is | kind | lives now | belongs | plan |
|---|---|---|---|---|---|
| `Pure.java` + `native-catalog.txt` | the 881-signature native catalog the type checker reads | K4 | `core` main + test resource | **stays** | becomes a generated resource; the generator moves out (§6.3) |
| `PlatformTypes` | 122 distinguished upstream FQNs + magic spellings, read by 91 main files | K4 | `core` main | **stays** | folded into the §6.3 native diff |
| `NameResolver.CORE_IMPORTS` | pure's 32-package implicit import group, order-sensitive | K4 | `core` main | **stays** | validated against `m3.pure` in the prelude generator (~5 lines) |
| `Prelude.java` / `prelude.pure` | the 5,281-line generated platform module | K6 | resource in `core` main | **stays** | already correct — this is the model |
| `PreludeGeneratorTest` | the generator; walks both checkouts | K2 | `core` test | **moves out** | |
| `SpecBodyCensusTest` | types every platform body from `legend-pure` | K2 | `core` test | **moves out** | |
| rcorpus harness (`Corpus`, `MinimalCorpus`, +4) | reads the engine's relational corpus, runs gates 4/5 | K2 | `core` test | **moves out** | |
| `com.legend.harness` (`H2Verify`, `ReplayOracle`, `H2ExtensionFunctions`) | support for the above — the only test-side coupling | — | `core` test | **moves with them** | |
| protocol emitters (`ProtocolEmitter`, `MappingEmitter`, …) | emit the wire format; no upstream dependency | — | `core` main | **stays** | |
| the 17 protocol goldens | captured engine JSON | K4 | `core` test | **moves out** (needs the engine to re-derive) | §6.3b |
| `ProbeWireShapes` | the capture tool; compiles against engine | K3 | `parser-equivalence` | correct already | becomes a generator (§6.3b) |
| `TokenStreamPositionsTest` | 20 captured engine source-positions | K4 | `core` test | **moves out** | §6.3b |
| `parser-equivalence` (29 files) | the oracle parser, jars at runtime | K3 | own module | correct already | |
| `pct` Channel A (11 files) | PCT framework + ReportScopes, jars at runtime | K3 | own module | correct already | |
| `pct` Channel B (5 suites) | walks the checkouts | K2 | `pct` | correct already | |
| `tools/engine-runner` | perf harness, engine jars | K3 | own module | correct already | |
| `nlq` | **no upstream dependency** — `org.finos.legend.engine.nlq` is *our* package | — | own module | correct already | none |

### Measured cost of the move

**[V]** The nine candidate `core` test files (rcorpus 6 + tools 3, 4,155 lines of
255 test files):

- live in packages `com.legend.rcorpus` / `com.legend.tools` that contain **zero**
  main classes — no same-package access to protect;
- reference **47 `core` main classes, all `public`** — **zero** API widening needed
  (the six that first looked non-public are `public sealed interface` /
  `public @interface`);
- reference **three** `core` test-side classes, all in `com.legend.harness` — they
  move too, and that is the entire coupling;
- cost the gate chain **nothing**: `tools/allgates.sh` already runs
  `mvn -pl .,core clean install -DskipTests` as gate 2, before gates 4–9, so a new
  module consuming core's installed jar adds no step.

### Three honest caveats

1. **This is hygiene, not the guard.** The 17 protocol goldens already sat in a
   module with *no* engine dependency, deliberately. A module boundary would not
   have stopped anyone pasting engine JSON into a `core` test. Only the ArchUnit
   rule (§3e, §6.6) closes that. Do the move *and* the rule, or you have
   reorganised the problem rather than fixed it.
2. **Sequence: generators first, move second.** A single commit that relocates
   4,155 lines *and* changes what they assert is unreviewable. Land §6 items 2, 3
   and 3b in place, then move.
3. **One thing gets slightly worse.** Today `-Drcorpus.only=…` iterates inside one
   module; afterwards it needs a `core` install first. That is already the
   documented requirement for downstream modules, so the cost is largely paid —
   but it is the one day-to-day regression, and it argues for keeping the new
   module's surefire invocation as cheap as gate 4's is now.

**Naming:** not `tools` — that collides with the existing `tools/` script
directory. `spec-harness` or `oracle` reads better and says what it is: the module
that reads the spec.

## 5c. Protocol: the plan

The 17 wire goldens (§1b) are the only **C4** surface with no upstream file to diff
against and no census touching them. They need a different answer from everything
else, and the obvious answer is not the best one.

### The option that looks right and is second-best

**Generator + committed resource + parity assert** — the `prelude.pure` pattern:
`parser-equivalence` (which has the engine jars) captures the engine's JSON for a
fixed probe set, writes a resource, and a test in `core` asserts our emitter matches
it byte-for-byte. `-Dprotocol.generate=1` to re-capture, exactly like
`-Dprelude.generate=1`.

It works, and it keeps `core` free of an engine dependency. But it has two flaws:
the resource must be generated in one module and committed for a test in another
(a module writing into its sibling's tree), and — more importantly — **it keeps a
frozen artifact.** Staleness becomes a reviewed diff instead of a silent rot, which
is an improvement, but the artifact still only refreshes when someone remembers to
run the generator.

### The option to take

**Make it live. Delete the goldens.**

`parser-equivalence` already has **both sides in one JVM**: our emitter (via the
`legend-lite-core` dependency) and the engine's parser (via the oracle jars). So the
comparison needs no golden at all — capture the engine's JSON and our emitter's JSON
for the same source in the same run, and compare. That is precisely what the module
already does for the *parser* (differential harness, oracle versus ours over the same
input). **Protocol emission is the same shape of problem, and the module that exists
for it is already a standing gate (gate 8).**

What this buys that a golden cannot:

- **Nothing freezes.** An upstream protocol change goes red on the next run, with no
  re-capture step and nothing to remember during a bump.
- **The probe set stops being 17.** A golden costs a hand-written constant per case,
  which is why there are seventeen. A live differential costs nothing per case, so
  the input can be *the corpus* — every source gate 8 already parses becomes an
  emission comparison too. That is the same leverage the parser harness already
  enjoys: thousands of real sources instead of a curated handful.
- **`ProbeWireShapes` stops being a manual instrument** and becomes the engine half
  of a gate.

The cost: it needs the engine jars at test time, so it cannot run in a
no-engine environment. Gate 8 already requires them, so nothing is lost — but the
coverage **moves out of `core`**, which is exactly what §5b prescribes anyway.

### Sequence — expect it to go red, and treat that as the point

Landing a live differential against a 12-release-newer engine will surface real
divergences immediately. That is information, not a setback, and the repo has a
standard shape for it:

1. **Build the live comparison** in `parser-equivalence`, seeded with the 17 existing
   probe sources so the change is reviewable against known expectations.
2. **Run it.** Any case where our emitter and the engine disagree is either our
   defect or a deliberate divergence.
3. **Adjudicate into a ledger** with a stated reason per row — the
   `docs/refusal-allowlist.tsv` pattern — and ratchet the row count shrink-only.
4. **Expand the input** from the 17 probes to the corpus, adjudicating the new
   divergences the same way. This is where the real coverage arrives.
5. **Delete the 17 frozen constants from `core`** once the live test covers their
   cases. Keep whatever structural assertions in `core` do not need the engine.
6. **Put it in gate 8.** It is already the `parser-equivalence` gate, and
   `tools/diagnostics.sh` trigger 3 already fires on protocol-package changes.

### What this does to the bump procedure

Phase 6's protocol step (§5) disappears. There is no re-capture to remember, because
there is no golden: a bump either leaves the differential green or it does not, on the
first run, on three platforms. That is the whole objective of this program applied to
the one surface where it currently fails completely.

## 6. The automation to build, in priority order

Ranked by cost avoided per unit of work. Item 1 is landed; 2–5 are designed and
not landed — each is a gated batch.

**1. The two read-only tools — LANDED.** `tools/version-report.sh` (four
identities, the release gap from Central and from tags, checkout pin state,
INV-1..4) and `tools/upstream-drift.py` (the 132 paths against a target, plus
per-universe file-set drift and the imported test counts). Neither touches the
build, so both can land outside a gate cycle. Next step: wire
`version-report.sh --check` into CI next to `oracle_roots_check`, so an invariant
violation fails a push — today INV-3 would.

**2. Make every upstream path fail loudly.** §3l is the reason this ranks where it
does: batch 155 moved the corpus's engine shapes out of the parity-guarded prelude and
into 70 exact paths read with a silent `continue`. This item restores the guard that
move gave up. One new test,
`UpstreamPathManifestTest`, asserting that every declared upstream path resolves,
reporting the full miss list. The constants are already public and reachable:
`Corpus.SHAPE_FILES` (64), `Corpus.LIBRARY_FILES` (6), `Corpus.RELATIONAL`,
`Corpus.M2M_TESTS`, `Corpus.CORE_PURE`, `SpecBodyCensusTest.PLATFORM_ROOTS` (9),
plus the prelude roots and the two ledgers' path keys. Then turn the four silent
`continue`s into collected-and-reported misses (`MinimalCorpus:313`, `:397`,
`SpecBodyCensusTest:83`, `PreludeGeneratorTest` `platformFunctions`), widen
`SpecBodyCensusTest:73` from root 1 to all 9, and make
`ENGINE_IMPLEMENTATION_FILES` assert each key resolves — a key that matches
nothing is the inverted failure that cost 49 tests.

**3. Give `Pure.java` an upstream oracle.** The real fix for the largest
unguarded surface: a test that extracts every `native function` signature from
the two checkouts and diffs the set against `native-catalog.txt`, reporting three
buckets — upstream-only (a native to add), ours-only (an invention, which must be
an allowlisted internal), and **same-FQN-different-signature** (the silent
divergence nothing sees today). The parser already parses these signatures, so
the machinery exists; this is a new consumer, not a new parser.

**3b. Make the protocol comparison LIVE and delete the goldens (§5c).** Not a
generator-plus-resource: `parser-equivalence` already holds our emitter and the
engine's parser in one JVM, so the 17 frozen constants can become a differential
that re-derives both sides every run — and whose input can then grow from 17 probes
to the corpus. Expect it to go red on landing; adjudicate into a shrink-only ledger.
This is the only item that *removes* a hand-held artifact rather than guarding one.

**3c. Unwind the 42 hand-typed natives (§3j, §3k, §3m).** In order of value:
**(i)** delete the **8** signatures that suppress a working upstream Pure body, and fix
the generator's exclusion rule to key on *implemented* rather than *declared* —
`noDebug` and `pathToElement` alone are called by 56 and 29 corpus files;
**(ii)** generate the **23** native-only signatures into `prelude.pure` instead of
hand-typing them (the parse path already supports `native function`; five
`Pure.nativeFunctionsAt` call sites must also consult the prelude);
**(iii)** route the engine-side bodied ones in by file via `LIBRARY_FILES` /
`SHAPE_FILES`; **(iv)** delete
`meta::relational::metamodel::execute::loadValuesToDbTable` — zero callers in either
checkout. Until (i)–(iii) land, a `RESOLUTION_ONLY` partition asserted shrink-only
keeps the remaining claims honest.

**4. Collapse the pins to one declarative place.** `tools/oracle-pins.env` is
already valid `.properties`. Extend it with `ORACLE_JAR_ENGINE_VERSION`,
`ORACLE_JAR_PURE_VERSION`, `PCT_ENGINE_VERSION`, `PCT_PURE_VERSION`; load it in
the root pom (`properties-maven-plugin:read-project-properties`) so the modules
read `${…}` instead of literals. One file then carries every identity, CI already
reads it, and `--check` enforces the invariants over it.

**5. Make the fixture filename self-checking.** Construct the expected name from
the pinned oracle version and **fail** when it is absent, instead of
`return out` on a missing file. Bumping the oracle then names its own next step
("re-harvest") rather than silently emptying 1,552 sources.

**6. The ArchUnit rule that closes the class (§3e).** No committed expectation of
upstream behaviour may be a typed literal; it must come from a named generator and
be asserted byte-equal against a fresh run. `core/src/test/java/com/legend/architecture/`
already holds three ArchUnit rules, so this is a fourth in an established place.
Land it *after* items 2, 3 and 3b, or it fails on its own backlog.

**7. [C] A `bump` driver** that executes Phase 0–2 and opens a PR with the
regenerated artifacts and a diff summary. Worth doing only after 2–5: the value
is in the loud failures and the single pins file, not in the typing.

---

## 7. The opening fact sheet's six open questions

1. **Collapse the versions, or is the skew deliberate?** Partly deliberate.
   **Collapse them — to ONE, not two.** INV-1 (pairing) is upstream's rule and
   must be kept: pick an engine release, derive pure from its own
   `legend.pure.version`, never type a pure version. INV-2 is **not** deliberate
   (the draft said it was — see §2): the oracle differs from the source only
   because the source pin is a non-tag commit that no jar exists for. Pin the
   source to a release tag and the oracle becomes the same release. INV-3 is
   broken and should be closed. `RUNNER` and `FIXTURE` follow. One number,
   everywhere.
2. **SOURCE vs ARTIFACT relationship?** They should be the SAME RELEASE (§2,
   INV-2 as corrected; INV-3 for PCT).
   The source pins must additionally satisfy INV-1 between *themselves* — and
   today they do **not**: engine@943d38b3dc2 is `4.137.1-SNAPSHOT` (its pom
   line 29) declaring `<legend.pure.version>5.93.0</legend.pure.version>` (line
   121), while the pure checkout is `5.92.1-SNAPSHOT`.
   **[V]** The source pair is one pure minor behind what that engine commit asks
   for. Not currently checked by anything; `--check` should cover it once the
   snapshot-describe comparison is made reliable.
3. **Can the hardcoded paths fail loudly?** Yes — §6 item 2, and all 132 resolve
   today so the guard lands green.
4. **The full ordered procedure?** §5, with M/J marked per step.
5. **Is 4.138.2 on Central without a tag?** No — it is tagged
   (`1d3e236bc735bf98b40388eff9d813acd4fb18e4`). The reverse case is the real
   one: 4.142.0 is tagged and unpublished.
6. *(implied)* **What does a bump to latest cost?** §4.

---

## 8. Provenance — what each claim rests on

Re-audited 2026-09-10 after the first draft. Five things were weaker than the
draft stated; all five are corrected above and listed here so the correction is
not silently absorbed.

**Receipted — exhaustive, reproducible by a named command**

- The four identities and their resolved classpaths: every pom grepped, plus
  `mvn dependency:tree` on both jar modules (one engine + one pure version each,
  no mixed classpath).
- Latest upstream: Maven Central `<release>` **and** `git ls-remote --tags` over
  all 1,232 engine tags / all pure tags. *Corrected:* the draft read the GitHub
  tags API's first page and sorted that — the endpoint pages and does not return
  version order, so it was sampling that happened to be right.
  `version-report.sh` now uses `ls-remote`.
- The 132 paths, the per-universe file-set drift, and the new-test counts: full
  git trees on both sides, every added file fetched and counted, nothing sampled.
  *Corrected:* the draft said **122** and missed ten ChannelB roots (§4a).
- Every silent-skip mechanism in §3b: read at the line, cited by line.
- `Pure.java` has no upstream oracle: `NativeFunctionTest` and
  `PlatformSurfaceGuardrailTest` grepped for both root properties — neither reads
  either. *Corrected:* the signature count is **881**, not 880 (§3c).
- INV-3: jar-versus-source PCT.test counts for the relation scope (§2).
  *Corrected:* the draft offered the 348-vs-355 pin gap as "the tell", which was
  inference; the measured 346-vs-353 delta now carries it.

**Wrong in the first draft, corrected after challenge**

- **INV-2 was stated as holding, and as deliberate. Both were wrong (§2).** The
  evidence was a pom comment — the repo testifying about itself — and a
  base-version comparison. Measured, the two trees are `diverged` (20 ahead, 11
  behind), so "the oracle is deliberately ahead" has no ordering to stand on. The
  real cause is that jars exist only at release tags while the source pin is a
  non-tag commit. The recommendation reverses: collapse to one release, don't
  document the spread.
- **The protocol surface was absent (§1b), and so were three more like it.** The
  first two passes searched only for *references* to upstream, so everything
  holding a *copy* was invisible. A third pass with capture detectors (§3e) found
  5 such sites totalling **1,072 hand-held facts**: the 17 protocol goldens,
  `Pure.java`'s 881 natives, `PlatformTypes`' 122 FQNs (cross-pinned only to
  `Pure.java` — a closed loop), `NameResolver.CORE_IMPORTS`' 32 entries in
  **main** source, and `TokenStreamPositionsTest`'s 20 captured positions. The
  search method, and the reason no search closes this class, are in §3e.

**Wrong in the draft, corrected by the §3i / §3j homework**

- **"Two new natives → two new `Pure.java` signatures" (§4b) was wrong.** Checked
  against the pinned checkouts: `elementPath` and `lenientPathToElement` already exist
  at the pin (in different files) and are already declared at `Pure.java:1184-1185`;
  `left` and `right` are already declared too. **Zero new signatures.** Upstream is
  reorganising platform sources into per-function files, so "+6 new files" is 2 new
  functions and 4 relocations.
- **The resolution-only native count was over-reported on a first pass.** A
  single-mechanism scan (string literals only) flagged 54 FQNs; the combined scan
  accounting for Java-constant references and `INTERNAL_DESUGAR` gives **42**. The
  `meta::legend::lite` family was the whole error — 11 of 12 are referenced through
  `Lite.*` constants spelled only inside `Pure.java`.

**Reclassified after measurement, in a later pass**

- **`CORE_IMPORTS` and `Pure.java` were ranked apart on a claim that does not hold
  (§3f).** The claim was that only `Pure.java` additions reach the spec census.
  Checked: `Compiler` → `NameResolver.resolve` → `CORE_IMPORTS`, and the census
  compiles through `Compiler.parseSources`, so both get the same partial signal from
  the same test. The split was recency plus conflating *cheap to fix* with *high
  risk*. They are one item now, with the set-versus-sequence distinction called out.
- **`PlatformTypes` was listed as an upstream exposure; it is not (§3g).** Measured:
  its direct source is our own `Pure.java`/`prelude.pure`, the upstream coupling is
  transitive, the drift guard covers 6 of 111 constants by containment against our
  own prelude, and 7 code sites bypass the table with inline literals. It moves from
  the upstream-risk list to a duplication-deletion item. The hypothesis going in —
  that it was a mixed bag of upstream names and our own inventions — was **wrong**:
  110 of 111 are upstream names (`rows` is a real property at `tds.pure:22`,
  `TDSNull` a real class at `:127`); only `csv` could not be placed upstream.
- **`TokenStreamPositionsTest` was over-ranked (§3f).** The position *math* is ours
  (C1); what is borrowed is a convention, and the 20 numbers are test expectations
  that no runtime code reads. It is the weakest item on the list, not a peer of the
  protocol goldens.

**Corrected by the §3n runtime probe (USER terminology challenge)**

- **"Implemented" was measured as "named in Java" (§3j).** Re-measured from the running
  lowering registries: 175 FQNs are in no registry, and ~80 ad-hoc dispatch sites mean
  the true unimplemented count is bounded 42–175, not 42. The proxy under-reported by up
  to 4×, and the reason is structural: nothing records what the platform implements.
- **"8 suppressed bodies" → 8–25** (the 175 intersect the prelude's exclusion list at 25).
- **The program's earlier "generate Pure.java from upstream natives" conflated the two
  axes** and is withdrawn (program §0, §7).

**Sampled — sound but not exhaustive**

- **INV-1's pairing rule is verified on 4 of 24 engine releases** (4.133.0,
  4.137.0, 4.138.2, 4.145.0). The rule is structural — it is how upstream builds
  — and `version-report.sh` re-checks it per target at run time, so nothing here
  depends on the sample. But it is a sample.
- Stereotype counts use line-matching (`grep -c`), so two declarations on one
  line would undercount. Spot-checked by listing all stereotypes in two of the
  nine new corpus files; not checked in all fifteen.

**Inference, labelled as such**

- *Why* the DuckDB spread matters (§1c): that the corpus's expected rows were
  authored under the engine's 1.3.0.0 semantics is reasoning about provenance,
  not a measured divergence. The spread itself is measured; the consequence is
  argued.
- "No written procedure exists" (§5) rests on a grep for the phrase variants
  plus ~200 of `GATES.md`'s 1,128 lines. A procedure hiding under different
  wording would have been missed.

**Quoted from the repo, not re-measured**

- The **49 tests** cost of the exclusion-map failure is the figure recorded in
  `MinimalCorpus`'s own comment (batch 134, reconfirmed by Windows CI
  2026-09-09). I did not reproduce it.
- The ratchet values in §5 Phase 4 are read from source, but their *history* and
  rationale come from `GATES.md` and the surrounding comments.

## 9. Not verified

- **[C]** Whether the target release's PCT jars still expose the `ReportScope` /
  `PCTReportConfiguration` API our suites extend. 12 releases of API drift could
  be a compile break; it is the first thing Phase 1 will discover.
- **[C]** Which `ReportScope` the four new `PCT.function` entries fall into, and
  therefore which of the five Channel B pins moves by how much. Determined by
  running gate 9 after the bump.
- ~~Whether the source commit is an ancestor of the oracle tag.~~ **Resolved
  [V]**: it is neither — the two are `diverged`, 20 ahead and 11 behind (§2).
  Measured through the GitHub compare API, since the local checkouts are owned by
  another account and could not be fetched from this session.
- **[C]** Content drift *within* files that kept their paths — measured only as
  file-set drift here. `CorpusManifestTest`'s 8,891 SHA rows measure it exactly,
  and are the authority once the bump runs.
- **[C]** Whether the DuckDB 1.4.4.0 / 1.3.0.0 spread is deliberate.
- **[C]** `PlatformTypes.TDS_CSV_PROPERTY = "csv"` — the only one of its 111
  constants I could not locate upstream. Either an upstream property I searched for
  in the wrong file, or our own. One grep to settle.
- **[C]** Whether the four absent `coreImport` elements (§3h —
  `constraints::fail`, `constraints::warn`, `tools::profile`,
  `tools::ProfileResult`) are actually reached by any upstream body we compile. If
  they are, the spec census should already be walling them; if it is not, that is a
  census gap worth knowing about.
