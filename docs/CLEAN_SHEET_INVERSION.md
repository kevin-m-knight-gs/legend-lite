> ## Design record — banner added 2026-08-06
>
> This is a **dated design record**, kept because the work it describes landed
> and because live code cites it (10 javadoc site(s) under `core/src/main/java`).
> Some class names in it have since moved or been renamed. **Do not delete it**,
> and do not treat its names as current — see `AGENTS.md` and `core/README.md`
> for the live map.

# Clean-Sheet Inversion — Function Form as the Parsed Language

**Status:** Design spec + implementation plan. Supersedes the
"Concrete next step (storage = the embodiment)" section of
`STRUCTURE_VS_BEHAVIOR.md` (the `FunctionSynthesizer` owner-storage
mechanism) — see §5.4 for why and what replaces it.

> **Companion docs:**
> - `MAPPING_CLEAN_SHEET.md` — the clean-sheet function form (the
>   target user-facing language). This doc makes it *parseable*.
> - `MAPPING_LEGACY_TO_FUNCTION.md` — the semantic translation of
>   every legacy DSL shape. This doc changes *what the translation
>   emits*, not the translation itself.
> - `STRUCTURE_VS_BEHAVIOR.md` — the two-layer thesis (expression-free
>   structure + one function sdoepace). This doc is its enforcement plan.

---

## 0. The diagnosis — we built the bridge before the road

The clean-sheet function form was specified as "the **user-facing
language**, independent of legacy DSL conversion"
(`MAPPING_CLEAN_SHEET.md` §0), and `MAPPING_LEGACY_TO_FUNCTION.md`
states "the function form remains the **only** hand-written surface."
What got built is the opposite dependency order:

| Claim (docs) | Reality (code) |
|---|---|
| Function form is the parsed language | `ElementParser.parseRelationalClassMappingBody` parses only the legacy DSL (`~mainTable`, `prop: [db]T.COL`, …) and cites engine's `RelationalParserGrammar.g4` as its spec. `*acme::Person: Relational { acme::funcs::personMapping }` is a parse error. |
| `AssociationMapping` is its own kind tag | Nested inside `Relational { AssociationMapping … }`, engine-legacy shape. |
| Hand-written clean-sheet mappings "bypass the normalizer" (`MappingNormalizer` Javadoc) | Unreachable: no clean-sheet mapping can be parsed, so nothing ever takes the bypass. |
| Clean-sheet primitive surface: `navigate`, `map(@Class)`, `+local`, `otherwise` | Registry has `legacyNavigate` and `otherwise` — exactly the natives the *legacy desugar* needed. No `navigate`, no `map(@Class)`, no `+local`. |
| Layer 1–9 examples are "copy-pasteable user-written setups" | Zero tests parse any of them. All 176 `ElementParserTest` mapping cases and all 86 `MappingNormalizerTest` cases feed legacy DSL. |

The same inversion-failure exists for the other ValueSpec body sites.
Derived properties, constraints, and service queries were supposed to
be "one concept — a function — wearing four structural hats"
(`STRUCTURE_VS_BEHAVIOR.md`). Instead:

- The sugar forms are the only authored surface; **plain-function
  authoring** ("new projects may instead write plain functions" —
  `STRUCTURE_VS_BEHAVIOR.md`) has no binding syntax and no tests.
- The desugared functions are not ordinary model elements. They are
  stored in `synthFunctions` fields **on the parser records**
  (`ClassDefinition.synthFunctions`,
  `MappingDefinition.mappingFunctions`,
  `ServiceDefinition`) — fields the parser never populates and
  Phase E mutates in — then flattened into the global index by a
  bespoke `FunctionSynthesizer` pass in `ModelBuilder.from`.
- Two FQN conventions coexist (`SynthFqn`: `<owner>$prop$<name>`;
  `MappingNormalizer.synthFqn`: `<mapping>$<Simple>`).
- `ParsedModel` goes into `ModelNormalizer.normalize` and `ParsedModel`
  comes out; nothing at the type level says normalization ran, and
  re-running duplicates synth functions.

**Root cause, in one sentence:** we keep designing the clean-sheet
language and then *implementing the legacy transform as the primary
mechanism*, leaving the clean-sheet form as an unreachable IR.

---

## 1. The inversion — one canonical representation, three doors in

The fix is not "also parse the clean-sheet form." It is to make the
clean-sheet function form the **single canonical post-parse
representation**, with every authoring surface — clean-sheet text,
legacy DSL, sugar — converging onto it:

```
                ┌────────────────────────────┐
clean-sheet ───►│                            │
text (primary)  │   CANONICAL REPRESENTATION │
                │                            │──► Phase F (compile)
legacy DSL ────►│   structure: binding tables│──► Phase G (typecheck)
(desugars)      │   behavior:  ordinary      │──► …
                │             FunctionDefs   │
sugar ─────────►│                            │
(desugars)      └────────────────────────────┘
```

The canonical representation is exactly the two-layer model of
`STRUCTURE_VS_BEHAVIOR.md`, taken literally:

1. **Structure is expression-free and references behavior by FQN.**
   A `MappingDefinition` is a binding table. A derived property on a
   `ClassDefinition` is a signature plus a body-function FQN. No
   `ValueSpecification` lives on a structural record after Phase E.
2. **Behavior is ordinary `FunctionDefinition`s in the model's element
   list.** Not in side-fields on their owners. A function lifted from
   sugar or legacy DSL differs from a hand-written one in exactly two
   ways: its FQN uses the reserved `$` sigil, and it carries a
   `synthesizedFrom` provenance tag. Nothing downstream may branch on
   either.

**The convergence property (the new invariant, and the new test
class):** a legacy mapping and its hand-written clean-sheet equivalent
produce **identical post-Phase-E models**, modulo `$`-names and
provenance. If they don't, one of the two paths is wrong. §7 makes
this a test corpus.

There is no "synth function subsystem." There is **lambda lifting plus
provenance** — the same thing every functional language does to
closures — and it is the *legacy/sugar door's* concern, invisible to
everything after Phase E.

---

## 1.5 Surface vs. canonical — two grammars, one representation

The inversion does **not** mean one parse representation. It means one
representation **per pipeline boundary**, and an explicit answer to
"where does faithfulness to what the user wrote live?"

> **Faithfulness is a Phase-B property; meaning is a Phase-E
> property.** Each surface grammar parses into records faithful to its
> own syntax. Phase E is the single desugaring point. After E exactly
> one representation exists, and no phase ≥ F can tell which grammar
> the input came through.

This is the standard shape of every desugaring compiler — GHC's
`HsSyn` (faithful, sugar-bearing) lowered to Core; rustc's AST lowered
to HIR, with diagnostics mapped back through spans. Two grammars at
the front is normal. What made engine — and core's `synthFunctions`
fields — messy was never grammar count; it was **leakage**: surface
records and phase-E output flowing past the desugar boundary, so
downstream code had to understand both. The §7.4 guards police
leakage, not grammar count.

### 1.5.1 The record inventory

| Surface (door) | Parse record — faithful to source | Lifetime | Sole consumer |
|---|---|---|---|
| Legacy mapping DSL (Door 2) | **`LegacyMappingDefinition`** — today's `MappingDefinition`, renamed: `classMappings` with `PropertyMapping`s, `~mainTable`/`~filter`/…, nested `AssociationMapping`, the lot. Unchanged shape; it is already a faithful legacy parse tree. | B → E | `MappingNormalizer` |
| Clean-sheet mapping text (Doors 1, 3) | **`MappingDefinition`** — the binding table of §2.1 | permanent (it *is* canonical) | everything |
| Derived-prop / constraint / query **sugar** (Door 3) | today's expression-carrying member records (`DerivedPropertyDefinition`, `ConstraintDefinition`, the service `functionBody`) — the sugar's parse tree | B → E | `ModelNormalizer` |
| Plain-function hats (Door 4) | the binding members of §2.2 | permanent | everything |

So: **yes, the legacy DSL gets its own representation** — it is
today's record, renamed, demoted from "the mapping element" to "the
legacy surface tree," and barred from `NormalizedModel`. What §2.1's
"replaces" means precisely is: the *name* `MappingDefinition` and the
*role* of canonical mapping element move to the binding table; the old
shape survives as `LegacyMappingDefinition` with a B→E lifetime.

### 1.5.2 Whole-element split vs. member-level split

Two different mechanics, chosen by how far surface diverges from
canonical:

- **Mappings: separate element records.** The legacy grammar diverges
  wholesale — a different body language, not a sugared spelling of
  bindings. Cramming both into one record means nullable
  field-pairs and an implicit state machine (exactly today's
  `classMappings` + `mappingFunctions` coexistence). Two records,
  one boundary.
- **Class/service hats: sealed member unions.** The container
  (`ClassDefinition`) is identical in both worlds; only the member
  diverges, and only between "expression body (sugar)" and "FQN
  binding." So the member type is the union:

  ```
  // Definition = sugar, carries the expression (B -> E lifetime).
  // Binding = canonical, carries bodyFunctionFqn.
  public sealed interface DerivedProperty
          permits DerivedPropertyDefinition, DerivedPropertyBinding { }
  ```

  (Same for constraints and the service query.) Phase E maps every
  `Definition` variant to a `Binding` variant + a lifted function;
  `NormalizedModel`'s invariant is that only `Binding` variants
  remain. Note Door 4 means the *parser* can produce `Binding`
  members directly — a binding is legal surface syntax, not a
  compiler-internal form.

### 1.5.3 Where faithfulness goes after E

Surface records do not flow downstream — they die at E. What survives
for "report errors in the user's terms":

- **Source spans** on the lifted function bodies (the
  `ValueSpecification`s inside a lifted function are the *same nodes*
  the legacy parse built — desugaring rearranges, it does not
  re-originate, so positions survive for free where the expression
  came from source; normalizer-fabricated nodes carry the span of the
  legacy construct they realize).
- **`synthesizedFrom` provenance** for naming ("constraint `adult` of
  `model::Person`", "property mapping `firstName` of `my::M`").
- **Re-parsing** for tooling that genuinely needs the surface tree
  (LSP hover/rename over legacy text, formatting, the legacy→clean
  codemod). Those are B-layer tools; `ModelOrchestrator` already
  demand-parses per element, so handing them a
  `LegacyMappingDefinition` is a lookup, not an architecture.

### 1.5.4 The asymmetry that makes clean-sheet "primary"

For Door 1 the surface representation and the canonical representation
**coincide** — a binding parses as a binding; the only desugar is
lambda-lifting inline bodies. That is not a happy accident; it is the
operational meaning of "clean-sheet is the primary language." Legacy
needs a faithful intermediate because its syntax is far from its
meaning. Clean-sheet doesn't, because its syntax *is* the meaning.

---

## 2. Canonical record shapes

### 2.1 `MappingDefinition` — a binding table

The canonical mapping element, **and** Door 1's parse record (§1.5.4 —
they coincide). Today's `classMappings` / `associationMappings` /
`mappingFunctions` shape is not deleted: it is renamed
`LegacyMappingDefinition` and demoted to the legacy surface tree
(§1.5.1), produced only by the legacy grammar branch, consumed only by
`MappingNormalizer`, dead after Phase E:

```java
public record MappingDefinition(
        String qualifiedName,
        List<MappingInclude> includes,
        List<ClassBinding> classBindings,
        List<AssociationBinding> associationBindings,
        List<EnumerationMapping> enumerationMappings,   // static data — stays structural
        String testSuitesSource)
        implements PackageableElement {

    /** Class / kind-tagged binding: structure only, body by FQN. */
    public record ClassBinding(
            String classFqn,
            Kind kind,              // RELATIONAL | PURE
            String setId,           // nullable
            String extendsSetId,    // nullable
            boolean root,
            String functionFqn) { } // the realizing function — always an FQN

    public record AssociationBinding(
            String associationFqn,
            String predicateFunctionFqn) { }

    public enum Kind { RELATIONAL, PURE }
}
```

Notes:

- The kind tag stays **on the binding** (not derivable from the
  function — both kinds return `Class[*]`; see `MAPPING_CLEAN_SHEET.md`
  §1).
- `EnumerationMapping` keeps its inline static table. It is data, not
  an expression — it belongs in structure (doc §Layer 9).
- **There is no expression variant of a binding.** An inline binding
  body (`Relational { tableReference(…) -> map(…) }`) is
  lambda-lifted at parse/desugar time (§5.3) into an ordinary function;
  the binding stores its FQN. One shape downstream, always.

### 2.2 `ClassDefinition` — hats, not bodies

`DerivedPropertyDefinition` and `ConstraintDefinition` lose their
`ValueSpecification` after Phase E and gain a body FQN:

```java
public record DerivedPropertyBinding(
        String name,
        TypeExpression type, Multiplicity multiplicity,
        List<ParameterDefinition> parameters,
        String bodyFunctionFqn) { }

public record ConstraintBinding(String name, String bodyFunctionFqn) { }
```

Mechanics per §1.5.2: the member type becomes a sealed union of the
sugar variant (expression-carrying, B→E) and the binding variant
(canonical, also directly parseable — Door 4). Phase E maps every
sugar member to a binding + a lifted function; only binding variants
survive into `NormalizedModel` (§7.4 guard).

- `ClassDefinition.synthFunctions` — **deleted.**
- `MappingDefinition.mappingFunctions` — **deleted.**
- `FunctionSynthesizer` — **deleted.**
- The bespoke flatten pass in `ModelBuilder.from` — **deleted.**
  Lifted functions arrive as ordinary elements in the model's element
  list; the existing `case FunctionDefinition fd -> mb.appendFunction(fd)`
  ingests them with no special handling.

### 2.3 `ServiceDefinition`

Same move: the service's query becomes `queryFunctionFqn`; the lifted
function (params from the typed lambda, `Any[*]` return until Phase G)
is an ordinary element.

### 2.4 `FunctionDefinition` — unchanged, plus provenance

Already pure (no compiler-cache fields). Keeps `synthesizedFrom`:

```java
public record Synthesized(String hat, String ownerFqn, String memberName) { }
// hats: "prop" | "constraint" | "query" | "class" | "assoc" | "inline"
```

Provenance has exactly two consumers: **diagnostics** (report
"constraint `adult` of `model::Person`", never
`model::Person$constraint$adult`) and the **`liftedByOwner()`
derivation** (§4 — ownership bookkeeping for invalidation). Neither is
semantic. It is a compile error in review for any phase ≥ F to branch
on it.

---

## 3. One FQN scheme

`SynthFqn` becomes the single owner of every lifted-function name;
`MappingNormalizer.synthFqn` is deleted. Uniform `<owner>$<hat>$<name>`:

| Hat | FQN | Example |
|---|---|---|
| Derived property | `<classFqn>$prop$<name>` | `model::Person$prop$fullName` |
| Constraint | `<classFqn>$constraint$<name>` | `model::Person$constraint$adult` |
| Service query | `<svcFqn>$query` | `my::Svc$query` |
| Class transform (lifted) | `<mappingFqn>$class$<classFqn>[$<setId>]` | `my::M$class$model::Person`, `my::M$class$model::Person$emp` |
| Association predicate (lifted) | `<mappingFqn>$assoc$<assocFqn>` | `my::M$assoc$model::Person_Firm` |
| Inline binding expression | same as class/assoc hat | (an inline body is just an unnamed transform) |

**Every hat embeds the *full* owner/target FQN, never a simple name.**
The lifted name is the key into the one `findFunction` index, so it
must be a **total injective** function of its inputs — embedding only
the class *simple name* is not injective (`a::Person` and `b::Person`
mapped in one mapping would both become `<mapping>$class$Person` and
silently collide). The `prop` / `constraint` / `query` hats already
carry the full owner FQN; the mapping hats do the same. Nothing parses
the package back out of a synth FQN (the names are opaque keys — §1.5.3,
CSI-7), so the embedded `::` is harmless. The `[$<setId>]` discriminator
is for multiple set-ID'd mappings of the *same* class and lands with
set-ID dispatch (M8); the full class FQN already separates distinct
classes.

The `$` sigil stays non-user-writable, so lifted names can never
collide with authored names in the one `findFunction` slot.

**The override affordance comes back for free.** Engine treated "user
supplies their own mapping function at the canonical FQN" as a feature.
In clean-sheet form it is not a special case: the user *binds any
function they like* — `acme::Person: Relational { acme::funcs::personMapping }`.
`$`-names exist only for bodies the user didn't name.

---

## 4. `NormalizedModel` — make the phase state a type

`ModelNormalizer.normalize : ParsedModel -> ParsedModel` is replaced by:

```java
public record NormalizedModel(
        List<PackageableElement> elements) {     // structure (binding form) + ALL functions

    /**
     * Derived index: ownerFqn -> lifted function FQNs. Computed (memoized)
     * by grouping elements' synthesizedFrom provenance. Never a constructor
     * input -- it cannot be populated, only derived, so it cannot drift
     * from the element list.
     */
    public Map<String, List<String>> liftedByOwner() { /* memoized group-by */ }
}
```

```
NormalizedModel ModelNormalizer.normalize(ParsedModel parsed)
```

**Is `liftedByOwner` a sidecar?** It is an *index*, and the
distinction is worth pinning because the sidecar pattern is what sank
engine's `MappingResolver`. Engine's `StoreResolution` failed two
tests: the HIR was **not interpretable without it** (lowering read
tree + sidecar — the semantic contract smeared across two layers),
and it was **not derivable from** the tree (identity-keyed context
that any rewrite silently orphaned). The rule:

> An auxiliary structure is a benign **index** iff (1) it is a pure
> function of the canonical representation, (2) nothing needs it to
> *interpret* that representation, and (3) it is keyed by stable names,
> not object identity. Anything failing (1) or (2) is a **sidecar** and
> is banned past its producing phase.

`liftedByOwner` passes all three — no phase F–K reads it (bindings →
FQN → element list is the whole interpretation path; only the
incremental-invalidation layer consumes it), and making it a derived
accessor rather than a stored field turns "derivable" from a claim
into a construction.

- **The idempotency footgun dies at the type level.** You cannot
  re-normalize a `NormalizedModel`; the signature doesn't accept one.
- **The invalidation story that justified owner-storage survives,**
  as a derived view instead of stored state. `liftedByOwner()` answers
  "which functions came from this owner" in O(1) after one group-by —
  re-normalizing a changed owner regenerates its functions in
  `elements` and the index follows automatically; deleting an owner
  drops them the same way. No FQN-prefix scanning, no `$`-name
  parsing, and **no normalizer output written into parser records.**
  This is the same discipline that got engine's `resolvedBody` /
  `parsedReturnType` cache fields stripped from `FunctionDefinition`;
  the `synthFunctions` fields were that mistake reintroduced one level
  up.
- Phase F (`ModelBuilder.from` / `DefaultModelContext.from`) takes
  `NormalizedModel`, not `ParsedModel`. The pipeline in `Compiler.java`
  reads: parse → resolve → **normalize (returns NormalizedModel)** →
  compile.

Phase-B parse records (sugar forms carrying expressions) exist only
*between* B and E. After E, every expression is inside a
`FunctionDefinition` and every structural record is binding-form. An
ArchUnit-style test enforces it (§7.4).

---

## 5. The four doors

### 5.1 Door 1 — clean-sheet mapping text (the primary surface, new)

`parseMappingElement` grows the function-form grammar of
`MAPPING_CLEAN_SHEET.md` §1:

```pure
Mapping acme::M
(
  include other::SharedMapping,

  *acme::Person:                         Relational         { acme::funcs::personMapping },
   acme::Person[emp] extends [Person]:   Relational         { acme::funcs::employeeMapping },
   acme::StaffMember:                    Pure               { acme::funcs::staffMapping },
   acme::Person_Firm:                    AssociationMapping { acme::funcs::personFirmMatch },
   acme::Status:                         EnumerationMapping { Active: ['A','ACTIVE'], Inactive: ['I'] }
)
```

- `AssociationMapping` becomes **its own kind tag** (today it is
  nested inside `Relational { … }`; that nesting remains accepted as
  legacy input only — Door 2).
- A kind block's braces contain either a **function reference** or an
  **inline Pure expression** (doc §10.10: the inner braces ARE the
  body, no extra `{| |}`).

**Body disambiguation rule** (legacy vs clean inside
`Relational { … }` — both surfaces share the kind tag):

> After `{`: `~` followed by an identifier (`~mainTable`, `~filter`,
> …) **or** an identifier followed by `:` (a property mapping) ⇒
> legacy DSL body. Anything else ⇒ Pure expression (clean-sheet).
> `~[` opens a col-spec, not a legacy command, and is an expression.

This is decidable with two tokens of lookahead and zero backtracking.
A qualified name alone (`acme::funcs::personMapping`) parses as an
expression and, when it is a bare function reference, is stored
directly as `functionFqn` — no lift needed.

### 5.2 Door 2 — legacy DSL (the normalizer inversion)

`MappingNormalizer` keeps its entire semantic translation
(`MAPPING_LEGACY_TO_FUNCTION.md` is untouched) but **retargets its
output**:

| | Today | Inverted |
|---|---|---|
| Emits | `FunctionDefinition`s stored into `MappingDefinition.mappingFunctions` via `withMappingFunctions` | Ordinary `FunctionDefinition`s appended to `NormalizedModel.elements` |
| Mapping element | Keeps legacy `classMappings` records; synth functions ride alongside | Rewritten to a **binding table** (`ClassBinding` / `AssociationBinding`) referencing the lifted functions by FQN — *exactly what Door 1 produces for equivalent text* |
| Ownership | `FunctionSynthesizer` flatten in `ModelBuilder` | `liftedByOwner` index in `NormalizedModel` |
| Bypass for clean input | Claimed in Javadoc, unreachable | Real: a binding whose body was already an FQN reference has nothing to desugar |

`ModelNormalizer` E.2–E.4 invert identically: derived-property /
constraint / service sugar lifts to ordinary functions + binding
records on the structural element (§2.2, §2.3), instead of
`withSynthFunctions` writes into parser records.

The post-condition for both normalizers, and the heart of this doc:

> **Phase E output contains no trace of which door the input came
> through**, except `$`-FQNs and provenance tags.

### 5.3 Door 3 — inline binding expressions (lambda lifting)

`Relational { tableReference('db','PERSON') -> map(@acme::Person) }`
lifts to `my::M$class$Person` with the expression as its body, binding
stores the FQN. Same machinery as Door 2's lifts, hat `"inline"`.
This is the only function creation Door 1 ever performs.

### 5.4 Door 4 — plain-function authoring for the other hats

The clean-sheet form for derived properties, constraints, and service
queries — promised in `STRUCTURE_VS_BEHAVIOR.md` ("new projects may
instead write plain functions"), never implemented. With §2.2's
binding records this becomes parseable surface:

```pure
function model::funcs::fullName(p: model::Person[1]): String[1] = {|
  $p.firstName + ' ' + $p.lastName
}

Class model::Person
[
  adult: model::funcs::isAdult        -- constraint binds a predicate fn by FQN
]
{
  firstName: String[1];
  lastName:  String[1];
  fullName() { model::funcs::fullName }: String[1];   -- derived prop binds by FQN
}
```

Sugar (inline expression bodies) remains fully supported and desugars
through Door 3's lift. Same disambiguation principle as §5.1: a body
that is a bare function reference binds directly; an expression lifts.

This subsumes the `FunctionSynthesizer` owner-storage design: the
co-location/invalidation argument is served by `liftedByOwner` (§4),
and what owner-storage could never give us — the four hats being
*writable as ordinary functions* — falls out of the binding records.

---

## 6. Dispatch — bindings are the scope, the function index stays global

How `Class.all()` resolves under a mapping, post-inversion:

1. All functions — authored and lifted — live permanently in the one
   `findFunction` index. Multiple mappings over the same class coexist
   there without conflict because **nothing dispatches by name
   pattern**.
2. `acme::Person.all()` under active mapping `M` resolves through
   **`M`'s binding table**: find the `ClassBinding` for
   `acme::Person` (root marker / set-id rules per
   `MAPPING_CLEAN_SHEET.md` §10.5), take its `functionFqn`, look the
   function up in the one index. Includes chase through
   `MappingInclude` with store substitution.
3. The binding table **is** the per-query mapping scope.

This lands engine's lesson without engine's mechanism. Engine needed a
per-query `ModelContext` overlay because its synth functions weren't
model elements; the overlay was the patch that kept the global registry
honest. Here functions are model elements and *scoping moves to the
structural layer where it semantically belongs* — the binding table —
so Phase G/H need no overlay, no side table, no
`CompiledDependencies.mappingFunctions` analog. `InlineClassFetch`
(Phase H, Rule 2) reads: binding table → FQN → one index → splice.

Same shape for the other hats: `$p.fullName` resolves through the
class's `DerivedPropertyBinding`; constraint enforcement enumerates
`ConstraintBinding`s; service routing reads `queryFunctionFqn`.
Binding lookup, never body walk, never name probing.

---

## 7. Test plan — the part we skipped last time

### 7.1 Clean-sheet parse corpus (Door 1)

Every Layer 1–9 example in `MAPPING_CLEAN_SHEET.md` §6 is
copy-pasteable **by design**. They become, verbatim, the test corpus
for the function-form grammar: `CleanSheetMappingParserTest`, one test
per Layer example plus §1's full binding-table example, asserting full
record equality on the parsed `MappingDefinition` (house style: record
asserts, not field probes). Examples using not-yet-landed primitives
(`navigate` pre-map/inline, `map(@Class)`, `+local`) parse — they are
ordinary applied functions / constructor syntax — and are
execution-gated, not parse-gated.

### 7.2 Convergence corpus (the inversion's correctness proof)

`LegacyCleanSheetConvergenceTest`: for each legacy shape in
`MAPPING_LEGACY_TO_FUNCTION.md` §5's catalogue, a pair —

```
(legacy DSL text, hand-written clean-sheet equivalent text)
```

— asserting both produce **equal `NormalizedModel`s** after renaming
`$`-FQNs and erasing provenance. The two deliberate exceptions are the
two bridge helpers (`legacyNavigate`, `legacyAssocPredicate`): their
pairs assert the documented bridge form instead. This suite is what
makes "desugars to semantically identical clean-sheet form" a tested
claim instead of a doc sentence.

### 7.3 Plain-function hat corpus (Door 4)

Parse + normalize tests for FQN-bound derived properties, constraints,
and service queries; convergence pairs (sugar body vs bound plain
function) exactly as §7.2.

### 7.4 Structural enforcement

Three guards, ArchUnit-style, alongside the wall:

1. **No expression after E:** every `PackageableElement` in a
   `NormalizedModel` either is a `FunctionDefinition` or contains no
   `ValueSpecification` reachable from its record graph.
2. **No surface record after E:** a `NormalizedModel` contains no
   `LegacyMappingDefinition` and no sugar member variant
   (`DerivedPropertyDefinition`, expression-carrying
   `ConstraintDefinition`, …) — only canonical elements and bindings
   (§1.5.1 lifetimes).
3. **No door-sniffing:** no class in `compiler/` (Phase F+) references
   `Synthesized` or `LegacyMappingDefinition` except diagnostics
   rendering.

### 7.5 Dispatch tests

Two mappings over one class in one model: assert `Class.all()`
resolution picks by binding table + root/set-id, both functions
coexisting in the index. (The case engine's global-registry design
couldn't represent and core currently has no answer for.)

---

## 8. Migration sequence

Ordered so the suite is green after every step; each step is
independently land-able.

| # | Step | Contents |
|---|---|---|
| M1 ✅ | `NormalizedModel` | New output type + derived `liftedByOwner()`; normalizers append functions to the element list instead of `withSynthFunctions` / `withMappingFunctions`; deleted `FunctionSynthesizer`, the synth fields, and the bespoke `ModelBuilder` flatten; Phase F entry (`ModelBuilder.from` / `DefaultModelContext.from`) takes `NormalizedModel`. Pure refactor — no grammar change, all 840 tests green. **Landed.** |
| M2 ✅ | One `SynthFqn` | Folded `MappingNormalizer.synthFqn` into `SynthFqn` (`mappingClass` / `mappingAssoc`, §3 scheme); the lifted-mapping FQN gained its `$class$` / `$assoc$` hat segment and embeds the **full** class/assoc FQN for injectivity (`my::M$class$model::Person`, `my::M$assoc$model::Person_Firm` — see CSI-10); the provenance hat `"mapping"` split into `"class"` / `"assoc"` to match. 841 tests green (incl. the cross-package collision test). **Landed.** |
| M3 ✅ | Surface/canonical split for mappings | Forked today's record → `LegacyMappingDefinition` (legacy surface tree, B→E lifetime, sole consumers: the resolution-time `ModelBuilder` cross-bake + `MappingNormalizer`); rewrote `MappingDefinition` as the canonical binding table (`ClassBinding` / `AssociationBinding` / `Kind`, §2.1). `MappingNormalizer.normalize` now emits the binding table + lifted functions; each binding references its realizing function by the function's own FQN (no regeneration). `ModelBuilder` split into `legacyMappings`/`findLegacyMapping` (resolution) and `mappings`/`findMapping` (canonical, Phase F). New tests assert the binding table, the §7.4 no-legacy-past-E guard, and association bindings. 844 green. **Landed.** |
| M4 ✅ | Door 1, function-ref form | The clean-sheet **function-ref** mapping form now parses straight to the canonical binding table — the first real second-parser path. `parseMappingElement` disambiguates per §5.1 (two-token lookahead: `~directive`/`prop:` → legacy, bare FQN → clean-sheet); `AssociationMapping` is now its own top-level kind tag; a mapping is all-legacy or all-clean-sheet (mixing rejected). Clean-sheet mappings pass through Phase E unchanged (no lift — functions are user-authored). Also promoted `MappingInclude` to a shared top-level record, removing the M3 duplication (CSI-11). 7 parser tests + 1 end-to-end passthrough. 852 green. **Landed.** |
| M4.5 ✅ | Canonical-mapping name resolution | `NameResolver` resolves the canonical `MappingDefinition`'s binding FQNs (`classFqn`, `functionFqn`, `associationFqn`, `predicateFunctionFqn`) instead of passing the record through — the half of M4 needed for clean-sheet mappings written with `import` + simple names. `setId`/`extendsSetId`/`kind`/`root` are not names and pass through; reference-equality preserved. 2 tests (simple-name resolution + already-FQN round-trip). 854 green. **Landed.** **Recurring concern:** each later Door (M5 inline expressions, M7 plain-function hats) must extend canonical-surface resolution to its new content. |
| M5 ✅ | Door 3 | Inline expression bindings + the lambda lift, for **all** binding kinds (Relational, Pure, Association). `ClassBinding`/`AssociationBinding` realization became a sealed `Ref \| Inline` (§1.5.2; convenience ctor + `functionFqn()` accessor kept M4's code untouched). The parser parses the clean-sheet body as a `ValueSpecification` and classifies by shape (`PackageableElementPtr` → Ref, else → Inline). NameResolver resolves the inline body. The normalizer's canonical pass-through became a *lift*: class inline → param-less `Class[*]` fn; association inline → 2-param `Boolean[1]` predicate (param names from the lambda, types from the association ends). No `Inline` survives Phase E (§7.4). 7 tests (parser ref-vs-inline for class+assoc, end-to-end lift for class/assoc/Pure, the no-inline-survives guard). 861 green. **Landed.** Deferred: validating inline arity/association-existence is the Phase-F/G validation layer's job, not the lift's. |
| M6 ✅ | Convergence suite | `LegacyCleanSheetConvergenceTest`: a legacy mapping and its hand-written clean-sheet equivalent produce the same canonical model. **Convergence is stronger than §7.2 predicted** — M2M *and* relational-column lifted bodies converge **byte-for-byte** (the parser normalizes `X.all()`→`getAll(X)` and the `->` pipeline to the same AST the desugarer emits; no `$`-FQN renaming is even needed since lifted FQNs are deterministic and identical, and provenance matches). Only the **association** case is binding-table-only — the documented bridge-helper exception (legacy desugars via `legacyAssocPredicate`; clean-sheet writes a plain lambda; both lift to the same `$assoc$` FQN). 4 pairs (M2M, M2M+filter, relational column, association). 865 green. **Landed.** |
| M7 ✅ | Door 4 (in-class hats) | Plain-function authoring for **derived properties** and **constraints**: a bare-FQN body (`fullName() { my::funcs::fullName }: String[1]`, `[adult: my::funcs::isAdult]`) binds the member to a user function instead of an inline expression. `Realization` (`Ref \| Inline`) was **promoted to a shared top-level type** used by mappings AND class hats (CSI-13); `DerivedPropertyDefinition`/`ConstraintDefinition` carry a `Realization` (convenience ctor + `expression()` accessor kept all sugar code untouched). Parser classifies by shape (`PackageableElementPtr` → Ref); NameResolver resolves either; the normalizer lifts only `Inline` (a Ref is already user-realized); Phase F points the typed member at the bound FQN for Ref, the lifted `$prop$`/`$constraint$` FQN for sugar. **In-class, not the Mapping section** — derived/constraints are logical-model behavior (axis 2), distinct from physical realization (axis 3); a future `extend`-block is for open extension only (CSI-9). 5 tests. 870 green. **Landed.** Deferred to **M7.5**: service-query binding (same `Realization` infra, different site — the service's `query:`, a top-level element not a class member). |
| M7.5 ✅ | Door 4 (service query) | Bind a service's `query:` to a function FQN (`query: my::funcs::peopleQuery`) instead of an inline lambda. The query already parses to a `PackageableElementPtr` for a bare FQN (no parser change needed); the normalizer simply skips the `$query` lift when the query is a bare ref (the user function is the realization). 1 test. 871 green. **Landed** — Door 4 now complete across all four hats. |
| M8 ➜ moved | Clean-sheet primitives | **Relocated to `COMPILE_MODEL_AND_SIGNATURES.md` Appendix A.** `navigate` / `map(@Class)` / `+local` are native functions whose *signatures* are a Phase-F concern and whose *semantics* are Phase-G+ — they belong with the consumer that gives them meaning (the type checker), not the inversion's parse/representation work, which doesn't depend on them. Designed **after** Phase G so their semantics are validated, not guessed. |

Phases G–K then build against the canonical representation only:
binding tables + one function index. No phase after E ever learns the
legacy DSL existed.

---

## 9. Decision journal

- **CSI-1 (open).** Kind-block body disambiguation: the two-token
  lookahead rule of §5.1. Alternative considered: a distinct kind tag
  for function-form (`RelationalFn`). Rejected — doubles the tag
  vocabulary and contradicts `MAPPING_CLEAN_SHEET.md` §1's "engine
  compatibility: same keyword set."
- **CSI-2 (open).** Whether Door 2 (legacy DSL parse) eventually gates
  behind a flag / lint for new projects, per "legacy is for ingestion,
  migration, back-compat only" (`MAPPING_LEGACY_TO_FUNCTION.md` §0).
  Default: keep always-on until a migration tool exists.
- **CSI-3 (closed).** Lifted functions in the permanent element list
  vs a per-query overlay (engine's mechanism): element list + binding
  -table dispatch (§6). The overlay solved a problem ("synth functions
  aren't model elements") that the inversion removes.
- **CSI-4 (closed).** Owner-stored synth functions vs `liftedByOwner`
  derived index: the index (§4). Owner-storage put Phase-E output
  inside Phase-B records — the exact pattern core was founded to
  reject.
- **CSI-5 (open).** Derived-property / constraint FQN-binding surface
  syntax (§5.4 sketch: `name() { fqn }: Type[m]` / `name: fqn`).
  Settle when M7 starts; constraint: must keep the sugar form's parse
  unambiguous.
- **CSI-6 (closed).** How to represent the legacy surface faithfully
  without two competing downstream representations: per-surface parse
  records with a B→E lifetime (§1.5). Mappings get a whole-element
  split (`LegacyMappingDefinition` vs `MappingDefinition`) because the
  grammars diverge wholesale; class/service hats get member-level
  sealed unions because only the member diverges. Rejected: one record
  with nullable field-pairs for both worlds (today's
  `classMappings` + `mappingFunctions` coexistence — an implicit state
  machine); retaining surface ASTs past E for diagnostics (spans +
  provenance suffice; tooling re-parses at the B layer).
- **CSI-7 (closed).** Whether `liftedByOwner` is a sidecar (the
  pattern that sank engine's `MappingResolver`): no, by the index/
  sidecar test of §4 — it is a pure function of `elements`, nothing
  needs it to interpret the model, and it is FQN-keyed. Resolved
  structurally by making it a **derived, memoized accessor** rather
  than a constructor input, so it cannot be populated out of sync.
  Engine's `StoreResolution` failed the first two prongs
  (identity-keyed semantics the HIR was uninterpretable without);
  guard for the future: any proposed auxiliary map that fails prong
  (1) or (2) must instead be baked into the canonical records.
- **CSI-8 (closed).** Binding edge direction: **element → function**
  (binding records on structural elements), not function → element
  (per-function annotations like `<<mapping(M, Person, root)>>` on
  free-floating functions). The annotation form carries identical
  information but relocates it where it costs the most: binding
  conflicts become global-sweep errors between distant files (the
  orphan-instance problem); "what does mapping M map" / "what is
  Person's API" become query results instead of declarations; and an
  element's meaning stops being content-hashable (M's meaning = a
  whole-model query, so adding any file can silently change existing
  queries). Rust is the precedent *for* the chosen direction, not
  against it: `impl` blocks are grouped binding tables with hard
  locality rules (orphan rule, crate-bounded coherence, trait-in-scope
  resolution) — behavior floats on a leash. If cross-project open
  extension ever becomes a goal, the additive path is an impl-style
  **binding/extension block** (grouped, typed, legal only in the
  project owning the target), never per-function annotations.
- **CSI-9 (open, deferred).** Whether to generalize the surface into a
  unified impl-like block — one construct holding derived-property
  impls, constraint impls, and mapping bindings (Rust's inherent vs.
  protocol impls, with `Mapping` as a named protocol). Deferred until
  after M7: it is purely a surface-grammar question, and the canonical
  post-E representation (bindings + floating functions) is identical
  regardless of which blocks declare the bindings, so deferring costs
  nothing. Related Rust insight worth keeping: a class's API need not
  be declared in one syntactic site — Rust assembles a type's inherent
  API from same-crate impl blocks; the defensible invariant is
  "assemblable from a bounded, owner-controlled set of sites," not
  "single site." In-class declaration stays the default surface for
  now (Legend models are reviewed schema-style; class shape is
  consumed structurally by mappings/graph-fetch).
- **CSI-10 (closed).** Lifted-mapping FQN embeds the **full class FQN**,
  not the class simple name. The simple-name scheme (the original §3
  spec and engine's `MappingSimple_ClassSimple`) is **not injective**:
  `a::Person` and `b::Person` mapped in one mapping both encode to
  `<mapping>$class$Person`, colliding in the one `findFunction` index
  and silently dropping a mapping — a real bug, not a rare one (any two
  same-simple-name classes across packages in one mapping). Rejected
  alternatives: a guard that *errors* on the collision (rejects a
  perfectly valid model — lazy); a set-ID suffix (disambiguates the
  wrong axis — same class / different sets, not different classes /
  same name). The fix is the encoding the other three hats already use
  — embed the full owner/target FQN — making the whole synth-FQN family
  collision-free by construction. The "embedded `::` reads as a
  package" worry that motivated simple names is void: nothing parses a
  synth FQN's package (CSI-7, §1.5.3), and `prop`/`constraint` already
  embed a full FQN with no issue. Pinned by
  `sameSimpleNameDifferentPackagesDoNotCollide`.
- **CSI-13 (closed).** `Realization` (`Ref | Inline`) is one shared top-level
  type used by every binding site — mapping class/association bindings *and*
  the class hats (derived properties, constraints). It started nested in
  `MappingDefinition` (M5); M7 would have needed two or three more identical
  copies for the other hats. Promoting it (same move as `MappingInclude`,
  CSI-11) makes "how is this behavior realized — ref or inline" one concept
  across the whole model, the literal embodiment of "everything is a function,
  bound by ref-or-inline." Rejected: per-hat sealed types (N identical copies);
  a nullable `functionFqn`+`inlineBody` pair (the implicit-state-machine smell
  §1.5.2 bans).
- **CSI-14 (closed, decision).** Derived properties and constraints bind
  **in-class** (Door 4), not in the Mapping section and not (yet) in a separate
  block. Three axes, not two: data (stored props) / logical behavior (derived,
  constraints) / physical realization (mappings). Mappings *must* be a separate
  construct because a class has *many* of them (query-scoped, many-to-one) —
  that external pressure is absent for derived/constraints, which are intrinsic
  and singular, so they live with the class where models are read schema-style.
  The structure-vs-behavior thesis is still honored: it constrains the
  *compiled* model (the typed class is ValueSpec-free; bodies are floating
  functions), and Phase E *decouples where you write it from where it lives* —
  something Rust can't do without a desugar layer. A future `extend Person { … }`
  block stays reserved for **open extension** only (adding behavior to a class
  you don't own, orphan-ruled), as an additive class-impl surface — never the
  Mapping section, and a different block kind from mapping bindings (CSI-9).
- **CSI-12 (closed).** The synth-function "hat" is a `SynthHat` enum, not a
  string. It was a `String` (`"prop"`/`"class"`/…) written as literals at five
  lift sites, read by a non-exhaustive `describe()` switch, with the FQN
  segment (`"$class$"`) as a *separate* literal in `SynthFqn` — two stringly
  decoupled copies that could silently drift, and a switch that fell through
  to malformed text on an unknown hat. The enum is now the single source of
  truth: `SynthHat.segment()` drives both the provenance tag and the
  `<owner>$<segment>$<name>` FQN, `describe()` is an exhaustive enum switch
  (no default), and `SynthFqn` guards null/`$`-in-operand at construction.
  A runtime test pins `fqn.contains("$" + hat.segment() + "$")` so the two can
  never diverge. (Surfaced by an adversarial self-review of M1–M4.5.)
- **CSI-11 (closed).** `MappingInclude` (+ `StoreSubstitution`) is one
  shared top-level record, not a copy nested on each mapping form. M3
  had nested it on both `LegacyMappingDefinition` and `MappingDefinition`,
  forcing a field-by-field converter in the normalizer (and a second one
  would have been needed in the parser for Door 1). The include is a
  structural concept that survives the legacy→canonical rewrite
  unchanged, so it belongs to neither record exclusively — promoting it
  deletes both converters (the rewrite passes `md.includes()` straight
  through) and lets the clean-sheet parser build includes once. The
  reverse (canonical referencing legacy's nested type, or vice-versa)
  was rejected as a cross-direction dependency between the disposable
  legacy surface and the forward canonical type.