# Where legend-engine is more permissive than it looks

Parser parity has two failure directions, and only one of them is obvious.

Accepting something legend-engine rejects is the failure everybody plans for — it shows up
as a model that works locally and breaks in production. **Rejecting something legend-engine
accepts is the failure nobody plans for**, because it looks like rigour. It shows up as a
model that has worked for two years and suddenly will not load, and the report reads "your
parser is broken" rather than "your model was always malformed".

This file is the list of places a rewrite would plausibly be *wrongly strict*. Everything
here was found by `mutants.py` damaging a fixture that parses and recording that
legend-engine accepted the damage anyway.

## Do not be stricter than this

**Field order is free almost everywhere.** Of 114 sibling-field swaps across the corpus,
110 were accepted. The rules are unordered `(x)*`, so `type:` before `store:`, `auth:`
before `specification:` and so on are all legal. There are exactly **two** exceptions in the
whole corpus:

- `ExecutionEnvironment` requires `mapping` before `runtime`/`runtimeComponents`
  (`singleExecEnv: identifier COLON BRACE_OPEN serviceMapping (serviceRuntime | serviceRuntimeComponents)+`)
- MongoDB collections require `validationAction` before `validationLevel`

A rewrite that enforces declaration order everywhere rejects valid models. One that enforces
it nowhere accepts those two. Neither is visible from reading a `.g4`.

**A class may declare the same property twice.** `duplicate-field` was accepted 27 times,
including duplicating a property inside a `Class`. Duplicate `import` statements too. The
compiler presumably catches the ones that matter; the parser does not, and a rewrite that
raises "duplicate property" at parse time diverges.

Note the contrast, because it is not a general rule: a repeated `auth:` in a connection **is**
rejected, with `Field 'auth' should be specified only once`. Some fields are single-valued and
enforced by the walker, most are not, and the grammar distinguishes neither.

**Date literals are not validated.** `%2024-13-45` parses. `%9999-13-45T99:99:99.0000`
parses. The compiler is what says `Invalid month: 13`. Validating dates in the lexer is an
obvious thing to do and would be wrong.

**A `###Connection` element does not need its closing brace.** Deleting the final `}` is
accepted, even when another `###Section` follows — both elements still land in the protocol
document. This is Connection-specific: the same deletion is rejected in `###Pure`,
`###Relational`, `###Service` and `###Diagram`. Filed as F19; a rewrite must decide
deliberately whether to reproduce it.

**Section ORDER is free.** All 15 section swaps were accepted. Legend resolves across
sections by name, not by position, so a `###Mapping` may precede the `###Relational` it
references. A rewrite that requires declaration-before-use rejects most real models.

**A file with no `###` header is parsed as Pure.** `Class fx::A { x: String[1]; }` with no
header at all parses; `Database fx::D (...)` with no header is rejected with Pure's
alternatives list (`['Class', 'Association', 'Profile', 'Enum', 'Measure', 'function',
'native', '^']`). Pure is the default section, and a rewrite that demands an explicit header
rejects valid files.

**The same element may be declared twice.** Duplicating a whole `###Pure` body — the same
`Class` defined twice — parses. 41 of 51 duplicate-element mutants were accepted, and the 10
rejections were not about duplication at all: they were files whose duplicated block put an
`import` after an element, which is separately illegal (see below).

**Almost every keyword is a legal property name.** `Class`, `let`, `all`, `toBytes` and
`native` all work as property names in a `Class` body — Domain's `identifier` rule admits
them. The exceptions are `true` and `false`. A rewrite that dispatches on the token before
trying the identifier arm rejects ordinary models.

**Semantic rules are checked one stage later, or not at all.** Several things that look like
parse errors are not:

| written | parses | compiles |
|---|---|---|
| `Class A extends B, C` (multiple supertypes) | yes | **yes** |
| `String[2..1]` (lower bound above upper) | yes | **yes** — filed as F20 |
| `Association` with one or three properties | yes | no — `Expected 2 properties` |
| `%2018-13-45` | yes | no — `Invalid month: 13` |
| `Profile { stereotypes: ['alpha']; }` (quoted where identifiers are expected) | yes | — |
| `Measure` unit with no conversion function | yes | — |
| `Text` with no `type:` (only `content:` is required) | yes | — |
| Diagram `position: (1,2)` (integers, not floats) | yes | — |

The first two are the ones to be careful about: they survive compilation, so a rewrite that
rejects them at parse time is stricter than legend-engine is at *any* stage.

**Most capitalised words are not keywords.** Of 152 lowercase-the-first-letter mutations, 64
were accepted — because the word was a package segment or an element name, not a keyword.
Case sensitivity is real (88 rejections) but narrower than it looks.

## Where it is strict, and a rewrite might not be

The mirror image, recorded here because these are the operators with **zero** accepts across
all 51 fixtures — every single site rejected:

| mutation | sites | meaning |
|---|---|---|
| drop a trailing `;` | 133 | never optional, anywhere |
| body `{` where `(` is required, or the reverse | 113 | Mapping's parentheses are load-bearing |
| `###Section` changed to the wrong one | 51 | routing is strict; no element type is shared |
| unterminated block comment | 51 | must swallow to EOF |
| `#{` or `}#` written as a plain brace | 62 | island delimiters are not interchangeable |
| unterminated string literal | 109 | must run to EOF, not resynchronise |
| `[1]` written `[1..]` | 37 | multiplicity is validated in the grammar |
| `::` written `.` | 149 | the package separator is not a generic dotted path |

And one ordering rule that IS enforced, discovered by accident while investigating the
above: **`import` statements must precede every element in their section.** An `import`
after a `Class` in the same `###Pure` section is rejected with
`Unexpected token '*'. Valid alternatives: ['import']`. This is the exception to "field
order is free" being about fields rather than section structure.

## Silently discarded, not rejected

The worst case is neither accept nor reject: **parsed, then dropped without a word.**

- `mode: local` alongside `specification:` and `auth:` — both blocks parse and are replaced
  by synthesised placeholders. A vault reference written by the author appears nowhere in the
  output.
- `mappings:` on a `JsonModelConnection`, `class:` on a `ModelChainConnection` — all three
  model connections share one `definition` rule, and each walker extracts only what its own
  type needs.
- `trigger: Manual #{ whatever: 1; }#` — the `Manual` processor ignores its island body
  entirely; arbitrary content is parsed and discarded.

Filed as F21. It means a round-trip through parse-and-compose does not preserve the file, and
the author is never told which lines stopped existing.

## Validation that is not there at all

- **Any identifier is a valid `###FileGeneration` type.** `CompletelyMadeUpType fx::G { }`
  parses and records `"type": "completelyMadeUpType"`. The walker's
  `"Generation type '...' is not supported."` sits behind a catch for an exception the code
  above it cannot throw — dead code. Filed as F22.
- **`accountType` on a Snowflake connection is unvalidated** while `permissionScheme` in the
  sibling grammar is walker-checked against a closed set. Upstream's own roundtrip test feeds
  `accountType` the value `BadOption`.
- **`port` is `Integer.valueOf`'d at parse time for MemSql and not for Databricks** — same
  field name, same STRING type, different behaviour.

None of these is derivable from a `.g4`. All three are places a rewrite would guess wrong in
either direction.

## Reading the manifest

`mutants.tsv` has one row per mutant: fixture, operator, site, verdict, message. It records
**behaviour, not correctness** — nothing in the harness knows what *should* happen. Its use
is differential: legend-lite must produce the same verdict for the same input, and any
disagreement is a divergence that needs an explanation rather than a fix in either
direction.

Regenerate with `python3 mutants.py`; `--check` fails on drift instead of overwriting, and
`--accepted` prints the review queue.

One caveat worth knowing when adding operators: mutation sites are masked to **code**
regions. An earlier version happily unterminated a string inside a comment and recorded
"accepted", which reported the parser as permissive somewhere it had never been asked
anything. That inflated the accepted column by 56 mutants in `keyword-lowercase` alone and
produced 15 phantom accepts in the island operators.
