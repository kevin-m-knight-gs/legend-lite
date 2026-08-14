# Deep audit charter — four dimensions, parallel fan-out

> **Status: NOT STARTED.** This is the brief for a fresh session. It exists
> because the session that wrote it exhausted its 200-subagent budget on three
> earlier audits and could not fan out.

---

## 0. Before you start

### Raise the subagent cap (requires a process restart, not `/clear`)

`CLAUDE_CODE_MAX_SUBAGENTS_PER_SESSION` is read from `~/.claude/settings.json`
when the CLI process launches. `/clear` resets the conversation inside the same
process, so it will **not** pick up a settings change. Exit and relaunch.

Add to `~/.claude/settings.json`:

```json
{
  "model": "claude-opus-5",
  "theme": "dark-daltonized",
  "agentPushNotifEnabled": true,
  "env": { "CLAUDE_CODE_MAX_SUBAGENTS_PER_SESSION": "400" }
}
```

Or apply it without editing by hand. Use the heredoc form — a `python3 -c`
one-liner breaks with `IndentationError` if the terminal wraps the paste, which
is exactly what happened the first time:

```bash
python3 <<'EOF'
import json, os
p = os.path.expanduser('~/.claude/settings.json')
d = json.load(open(p))
d.setdefault('env', {})['CLAUDE_CODE_MAX_SUBAGENTS_PER_SESSION'] = '400'
json.dump(d, open(p, 'w'), indent=2)
print(open(p).read())
EOF
```

(That failure is harmless: `IndentationError` is raised at compile time, so
nothing runs and the settings file is untouched. Verify with
`python3 -c "import json,os;json.load(open(os.path.expanduser('~/.claude/settings.json')))"`.)

400, not 200: the plan below is ~150 agents with headroom for follow-ups and
verification passes.

### Read these first — do not re-derive them

| doc | what it already settles |
|---|---|
| `docs/PARSER_AUDIT_2026_08_14.md` | six dimensions of parser disagreement, measured against live oracles on 3,180 files + 5,622 mutants |
| `docs/INVENTION_AUDIT_2026_08_14.md` | the invented-native census and the dialect-gate leaks (partly executed since — re-verify) |
| `docs/E2E_BURNDOWN_2026_08_14.md` | all 276 non-passing corpus tests, root-caused and clustered |
| `docs/ENGINEERING_LOG.md` | standing context; its Active Queue header points at the above |

Then `git log --oneline -60` — the commit messages carry the design narrative.

### Toolchain

```bash
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$HOME/jdk/apache-maven-3.9.9/bin:$PATH"
```

Gates: `LEGEND_ENGINE_ROOT=~/legend/legend-engine LEGEND_PURE_ROOT=~/legend/legend-pure caffeinate -dims tools/allgates.sh`
then read `${TMPDIR}/gates-$(id -un).log`. The script always exits 0 — read the
log, not the exit code. **Never time anything without `caffeinate`.**

---

## 1. Established, with one open question

The other session's claim — 100% byte parity with legend-engine including full
PMCD — **checks out**, verified independently:

- The comparison is `PmcdParser.parseDocument(src)` vs
  `mapper.writeValueAsString(oracle.parseModel(src))`, compared by strict
  `.equals` (`Comparators.sameBytes`). **Full PMCD JSON, not a subset.**
- A second, stronger claim exists: **the SPI seam** — legend-lite installed as a
  `PureGrammarParser` implementation — also byte-matches. That is the real
  drop-in test.
- `ComparatorSelfTest` proves the comparator can report a difference. Built after
  an incident where every comparator was hardwired to "equal" and the chain
  reported green.
- Corpus: both checkouts, plus Pure snippets extracted from upstream Java tests,
  plus harvested engine fixtures, plus `.txt` resources — deduped by exact text.
- Gate 8 at `1c870bfd`: `oracle accepts 6489 / byte-MATCH 6489 / DIFF 0`.

**One named exclusion:** `m3.pure` is dropped by name
(`Corpus.java`, `removeIf(... endsWith("grammar/m3.pure"))`) because 3,607 lines
of `^Root.children[...]` bootstrap syntax "skews every count". It is the one
file the parity claim does not cover.

**OPEN — settle this first, it is a 30-second probe.** Is `sourceInformation`
inside the compared bytes? If yes, parity includes every source position and the
claim is very strong. If the engine's serializer omits it by default, parity is
structural only. Probe:

```java
ObjectMapper m = new ObjectMapper();
String src = "Class my::P { name: String[1]; }\n";
String eng = m.writeValueAsString(PureGrammarParser.newInstance().parseModel(src));
System.out.println(eng.contains("sourceInformation"));
System.out.println(eng.equals(com.legend.parser.PmcdParser.parseDocument(src)));
```

Build the classpath as in `docs/parser-audit-2026-08-14/README.md`.

---

## 2. The four dimensions

### D1 — Design / architecture (~30 agents, by package)

- **God classes.** Guardrails say files ≤3,500 lines, methods ≤250. Who is
  near the ceiling, and is the ceiling hiding a class that should be several?
  `wc -l` is the start, not the finding — the finding is *what responsibilities
  are fused*.
- **One home per feature.** Take a feature (dates, variant, milestoning,
  aggregates, unions, enums) and find every file that decides something about
  it. A feature × file matrix exposes smearing.
- **Duplicate code.** Not clones — *parallel implementations of one idea*
  (several date ladders, several type classifiers, two ways to ask the same
  question of a mapping).
- **Extensibility, both directions.** (a) Can legend-lite drop into
  legend-engine — is the SPI seam the only coupling? (b) If legend-lite
  *replaces* legend-engine, can a third party add a grammar without touching
  core? `SectionGrammarRegistry` is the claimed seam; test it by writing a new
  section grammar and seeing what else has to change.
- **Is everything in its own section parser?** 23 section grammars exist. Is any
  section's logic leaking into `ElementParser`, `SpecParser`, or the protocol
  emitters?
- **Re-derivation probe** (highest yield, from `audit-concept-ownership-method`):
  *what does this stage re-derive that an upstream stage already knew?* Mirror:
  *what does it compute that nothing consumes?*

### D2 — Correctness (~40 agents, one per section grammar + subsystem)

- **Hardcoded to pass tests.** Search for corpus-shaped constants: specific
  table names, specific FQNs, magic counts, `if (name.equals("..."))`. Every one
  is a candidate. Ground the judgement in whether the real engine's `.pure`
  source implies the same rule.
- **Silent drops.** Every `catch (Exception ignore)`, every `continue` on a
  parse failure, every `Optional.empty()` fallback. Which of them lose user
  input without a wall? Tenet 3 says loud beats silently-different.
- **Test coverage of our own code.** Not line coverage — *decision* coverage.
  Which branches in the section grammars have no test? Which walls are never
  exercised?
- **Known live leads** from the parser audit: `#TDS`, `Primitive`, `^$x(...)`,
  `%latest` bypass `refusesPlatformDialect()`; the stray-`)` skip in
  `ElementParser` is justified by a comment whose premise is empirically false;
  68 rejections say only `Unsupported syntax`; 15 type messages report the inner
  type instead of the outer.

### D3 — Performance (~15 agents)

- Lexer and parser throughput over the 3,180-file corpus; per-phase split.
- Allocation profile — is the token stream boxing? Are we re-scanning?
- Is `PmcdParser.parseDocument` doing work twice (parse then re-serialize)?
- Compare against the engine's ANTLR front-end as a reference point; being
  faster is a merge argument, being slower is a roadblock.
- **Discipline:** `caffeinate -dims`, `System.nanoTime`, never derive timing
  from CPU samples, never time on a contended machine. A 21× measurement error
  from a slept run is already recorded in `docs/GATES.md`.

### D4 — Adversarial review (~20 agents, distinct hostile stances)

Brief each agent as a different person who does **not** want this merged:

- the original author, who thinks the ANTLR grammar is the spec;
- a maintainer who fears a second parser doubles every future change;
- a reviewer who distrusts a clean-room claim and looks for copied structure;
- someone who will argue the 301 engine NPEs are *our* problem to have found
  quietly, not to publicise;
- someone who attacks the test harness rather than the code;
- someone who asks what happens on day two, when the engine grammar changes.

Their job is to find the argument that sinks the merge, not to be fair. Then a
final pass sorts their objections into: valid and fatal / valid and fixable /
rhetorical.

---

## 3. Fan-out mechanics

- Send independent agents in **one message**, multiple tool calls — they run
  concurrently.
- Give each a **complete brief**: it inherits no context. Name the repo paths,
  the toolchain exports, the tenets, and the "code not docs" rule.
- **Forbid concurrent maven.** Heavy JVMs get killed on this machine and a build
  under a running gate swaps the jar and produces fake failures. Agents do
  static analysis and hand back a PROBES-NEEDED list; the orchestrator runs
  those serially.
- Budget roughly: D1 30, D2 40, D3 15, D4 20, verification 20, follow-ups 25.

## 4. Standing rules for whoever runs this

- **Code, not docs.** Comments are claims to verify — the stray-`)` compensation
  in `ElementParser` carries a comment whose premise is false, and it survived
  because nobody re-tested it.
- **Every finding needs file:line.**
- **Re-verify before publishing.** Upstream moves fast; in one afternoon a
  finding was fixed mid-audit and a line citation drifted by 7 lines.
- **Record false positives.** Four have already been logged across the earlier
  audits; a census method that never produces one is not being checked.
- **Rank by (silence of failure) × (change amplification).** Many owners with
  full compiler enforcement is fine — say so. False alarms cost trust.
