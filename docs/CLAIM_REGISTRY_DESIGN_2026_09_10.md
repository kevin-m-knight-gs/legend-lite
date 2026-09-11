# The claim registry — design for batches 3–5 (program §6.1)

**Status: APPROVED by the USER 2026-09-10, then REVISED AT LANDING (§1a) — the hand table is
gone.** Prelude carries ALL upstream natives in the read roots (§6.2); bump cadence = Legend's
own, every two weeks (§6.5).
Facts below are measured from the tree at a6b1487eb (batch 1) unless marked [inference].

## 0. What a claim is

`Pure.java` holds 881 overloads / 520 FQNs, each a `NativeFunctionDefinition` constant
parsed from a signature string at class-load. Program §0: an entry there is the platform's
**semantic claim** — "we lower this." Today nothing verifies that claim: lowering is spread
over four catalog-keyed maps, a `CoreFn` enum, a six-entry wall map, a 26-row
`IMPLEMENTATION_KIND` table, and ~80 files that switch on a callee's *simple name*
(`case "annualized"`, `case "assertEquals"`). Only 15 files in `core/main` reference a
`Pure.X` constant at all; the rest match strings.

A **claim** is one fact: *this overload (by signature key) is implemented by this owner in
this way*. The registry is the set of all claims. Two tests make it a computed fact:

1. **Completeness:** every `Pure.all()` overload has **exactly one** claim.
2. **Honesty:** every claim names an owner class that exists and mentions the function.

## 1. The shape — DECISION: derived claims + one typed table of explicit claims

Three shapes were on the table (program §6.1). Measured against the code:

| shape | fits the code? | verdict |
|---|---|---|
| **annotation on the lowering rule** | the rules are map entries and lambdas, not methods; an annotation cannot carry a `NativeFunctionDefinition` (not a compile-time constant), so it would carry the FQN as a *string* — the string-hacking the audit bans | **no** |
| **a `Registrar` interface per lowering file** | ~80 owner files would each implement a method returning their overloads; the four registries already ARE registrations, so this duplicates them; class-load order decides whether a site's registration ran — a claim that exists only when its class happens to load is not a fact | **no** |
| **explicit `claims(Pure.X)` in ONE table + claims DERIVED from the registries that already exist** | the registries are keyed by `signatureKey` today (`Scalars.family` → `Pure.nativeKeysAt`); `CoreFn` is keyed by parse name; `WALLED_NATIVES` and `IMPLEMENTATION_KIND` by FQN — all four are *already claims*, just uncollected. Only the ad-hoc sites need to say what they do, and they say it in one place, with the typed constant | **yes** |

So: **`com.legend.builtin.Claims`** (core/main, beside `Pure`), a static registry fixed at
class-load like the catalog (the constraint the program keeps: no runtime-mutable
registry). It holds:

```
record Claim(NativeFunctionDefinition overload, Kind kind, Class<?> owner)
enum Kind { SCALAR_RULE, REDUCER, WINDOW_FN, WINDOW_AGG,      // derived: the four registries
            CORE_FN,                                            // derived: CoreFn parse names
            WALL,                                               // derived: Pure.WALLED_NATIVES
            JAVA_ROUTINE, HANDLE, EFFECT, CARRIER, CONTEXT_OWNER, // derived: IMPLEMENTATION_KIND
            ARM }                                               // explicit: an ad-hoc dispatch site
```

- **Derived claims** are computed by `Claims` from the existing maps — `Scalars.RULES`
  keys, `Aggregates.REDUCERS` keys, `Windows.FNS` / `AGGREGATES` keys, `CoreFn` parse names
  (every overload whose bare name is a `CoreFn` parse name — exactly how the Typer
  dispatches today), `Pure.WALLED_NATIVES` FQNs, `PlatformTypes.IMPLEMENTATION_KIND` FQNs.
  Nothing new to write in those files; the registry reads them.
- **Explicit claims** are the ONE table, in `Claims.java`, grouped by owner:
  ```java
  arm(CalendarAgg.class, Pure.CAL_ANNUALIZED, Pure.CAL_CME, Pure.CAL_CW, …);   // 32
  arm(AssertVerdicts.class, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY, …);       // 14
  arm(Lowerer.class, …); arm(Lexicon.class, …); arm(Fold.class, …); arm(JoinChecker.class, …);
  ```
  Typed: a deleted constant is a compile error; a renamed owner is a compile error. The
  `Class<?>` is the owner's identity, never a string.
- **`Lite` natives** (29, `meta::legend::lite::*`) claim the same way; the three the
  homework found in no registry (`otherwise`, `legacyAssocPredicate`, `legacyLocalProperty`)
  claim `ARM` from their desugar site.

**Exactly one.** An overload with two claims is a conflict the test reports (e.g. a
scalar rule AND an arm). The rule for resolving it: the registry claim wins and the arm
row is deleted — an arm that also runs for a registered overload is a *reference*, not a
claim. The wall kind is exclusive: a walled overload has no other claim by definition.

## 1a. What landed instead (batch 3, 2026-09-10) — measured, then decided with the USER

Two measurements overturned §1's table and its "exactly one":

1. **Derived claims alone cover 805 of 881 overloads.** With no hand table — reading only the
   four lowering registries, the `CoreFn` parse names (aliases included), the wall list and
   `IMPLEMENTATION_KIND` — 76 overloads / 56 FQNs were unclaimed, not the census's 175 (the
   census had not counted CoreFn aliases or `IMPLEMENTATION_KIND`). Of the 76, ~57 belonged to
   THREE string-switch families: the 32 calendar functions, the asserts, the TDS row getters.
2. **112 overloads carry MORE THAN ONE derived claim, legitimately.** `max` is a scalar rule in
   scalar position and a reducer under `groupBy`; `first` is scalar, reducer and window
   function. The same overload lowers differently by POSITION. "Exactly one primary claim" was
   a false model of this platform.

USER (2026-09-10): "what is it actually giving us besides another hand maintained mapping that
could get out of sync with grep?" — the explicit table WAS that: its only job was to vouch for
the three families, and it could drift. So:

- **No hand table.** The registry is derived sources only. A `Pure.java` entry that nothing
  the compiler dispatches from registers is UNCLAIMED — and stays visible in the ledger until
  batch 4 moves it to the prelude.
- **The three families became CLOSED TYPES (the CoreFn pattern):** `CalendarFn` (32,
  `com.legend.lowering`), `AssertFn` (14, `com.legend`), `RowGetter` (9, `com.legend.lowering`).
  Each constant carries its catalog overload(s) — `Pure.CAL_YTD`, typed. `CalendarAgg`,
  `AssertVerdicts` and `RowGetters` dispatch through the enum: switch EXPRESSIONS with no
  `default`, so adding a constant fails to COMPILE until every switch handles it; the registry
  reads `values()`. The enum is the set AND the dispatch key — nothing beside the code to keep in
  sync, which is what a runtime `default -> throw` could never give. (`getEnum`, which the old
  string set named, has no catalog signature and was never reachable; it is gone.)
- **Every kind is recorded.** The ledger's `kinds` column lists all registrations of an overload
  (`REDUCER|SCALAR_RULE|WINDOW_FN`); the invariant is NONE UNCLAIMED, ratcheted shrink-only.
- **Placement, decided by the architecture guards at landing.** The family enums live in
  `com.legend.builtin` beside `Pure` (lowering may not depend on the model type — invariant
  6h; `builtin` may). `Claims` is TEST-scope measurement code (`core/src/test/java/com/legend/claims`)
  — main never dispatches through it, so it stays outside the production dependency rules
  (invariants 4, 6e); its main-scope artifact is the generated ledger. The four registries are
  read through a test-tree helper in their own package (`LoweringClaims`), so nothing internal
  was made public for the registry.
- Exhaustive types where a family is closed and owned by one Java arm; the completeness TEST
  where the set is open (the lowering tables are string-keyed maps, and for them the test plays
  the compiler's role).

## 2. The tests (batch 3's done-criterion)

`ClaimRegistryTest` (core, gate 1):

1. **Completeness, ratcheted.** `unclaimed = Pure.all() − claimed`, the count a **shrink-only
   pin** (`UNCLAIMED_MAX`) with the list printed to `target/unclaimed-natives.txt`; batch 4
   takes it to **0** by moving the rest to the prelude. (The measured numbers are in the batch-3
   record in docs/GATES.md.)
2. **The ledger is byte-equal** to `Claims` — every registration of every overload.
3. ~~Exactly one~~ — false for this platform (§1a); every kind is recorded instead.
4. ~~Honesty grep~~ — there are no hand claims to check; the family enums are typed.
5. **`Scalars.KNOWN_ABSENT` deleted** (all 39 names were present in the catalog — the branch
   was dead) — `familyIfPresent` is `family`; a registration that finds no catalog overload dies.

`native-catalog.txt` (the snapshot of Pure.java's own output) gains nothing; it is
superseded in batch 5 by the generated text.

## 2a. Two additions (USER 2026-09-10)

**The implemented surface is a COMMITTED LEDGER**, `core/src/main/resources/com/legend/builtin/native-claims.tsv`,
one row per overload — `fqn, signatureKey, kind, owner, also` — regenerated by `ClaimRegistryTest`
every run and asserted byte-equal to the committed file (the `prelude.pure` contract). Any change
to the surface is a reviewed diff, not a number in a log; `UNCLAIMED` rows stay visible in git
until batch 4 empties them, so the ratchet is the count of `UNCLAIMED` rows in a committed file.
It replaces `native-catalog.txt` (a snapshot of Pure.java's own output, silent on implementation)
and, with a `constant` column, IS the batch-5 membership list (§3) — one file, not two.

**Multiple claims are recorded, not hidden.** Exactly one PRIMARY claim (the place the compiler
dispatches to); every OTHER site that touches the overload goes in the `also` column. `also` is
MEASURED, never typed: the same source scan the honesty check uses (a file naming the `Pure.X`
constant or the FQN string, other than the owner). Truthful in both directions; a CoreFn row with an
unexpected `also` owner is where the CoreFn over-claim concern gets eyeballed; promoting an `also`
site to owner is a one-line move in the explicit table and the diff shows it.

## 2b. Batch 4b landing note (2026-09-10)

The "family enums" of §1a became ONE file, `com.legend.builtin.NativeFn`, one nested
closed enum per implementer (USER: "only on typed things that are registered"; "one
file of registered enums broken out by family"; name chosen over "Families"). The
executor kinds (`PlatformTypes.IMPLEMENTATION_KIND`) merged into it as families; the
`Kind` values JAVA_ROUTINE / HANDLE / EFFECT / CARRIER / CONTEXT_OWNER of §2 are gone —
those rows are FAMILY claims now. The registry reads `NativeFn.families()`; nothing is
hand-listed twice. UNCLAIMED_MAX reached 0.

## 3. The membership list (batch 5's input) — DECISION: a TSV resource, one row per overload

When batch 5 flips Pure.java from hand-typed to generated, the generator needs the
membership and nothing else (the text comes from the checkout). Format:

```
# native-membership.tsv — the platform's implemented surface. Sorted by fqn, then key.
# constant<TAB>fqn<TAB>signatureKey
CAL_ANNUALIZED	meta::pure::functions::date::calendar::annualized	annualized_Date_$0_1$__String_1__Date_1__Number_$0_1$__Number_$0_1$_
```

- `constant` is the Java name `Pure.java` exposes (the code references it; it is ours).
- `signatureKey` is the upstream-derived identity the registries key on today, and the
  join key to the checkout's declaration (native OR bodied — same signature text).
- The generator writes `Pure.java` from this list + the pinned checkouts; the parity test
  (in `spec`, batch 7) regenerates and asserts byte-equal, the same contract as
  `prelude.pure`. Membership changes are a one-line diff in the TSV; signature drift is
  impossible by construction (D4).
- `Lite.java` (29 inventions) is hand-declared and NOT in the TSV — the ours-only bucket of
  the signature oracle must equal exactly that set.

Why a TSV and not "the claims table is the membership": the claims table references
constants that the generator has to *produce*; a generator whose input is the file it
generates is circular. The TSV is the fixed point: claims reference constants, constants
come from the TSV, the test asserts the two agree (every TSV row claimed, every claim in
the TSV).

## 4. The prelude's exclusion rule (batch 4)

Today `PreludeGeneratorTest.platformOwnedNames` excludes an upstream body when
`Pure.nativeFunctionsAt(simpleName)` is non-empty or `CoreFn.of(simpleName)` is present —
keyed on the **bare name**, which is why 8–25 upstream bodies are suppressed by a
signature we never lowered (and why a same-named function in another package is
suppressed by accident). The rule becomes: exclude the body iff **`Claims` holds a claim
for that FQN** (any kind, including WALL). Once batch 4 empties the unclaimed set the two
rules coincide; from then on the rule is correct by construction.

## 5. Sequence

- **Batch 3 (claims):** `Claims` + `ClaimRegistryTest` (red at the measured count, then the
  70 off-registry owners claim, then the pin); `KNOWN_ABSENT` deleted; the 35 grey rows
  adjudicated in the batch record, one line each. Done: the test exists, is green at its
  ratchet, and prints the implemented surface.
- **Batch 4 (membership):** the unclaimed leave `Pure.java` → the prelude carries the body
  (upstream-bodied) or a respelled native (upstream-native); the generator stops dropping
  upstream natives by token position and carries **all** of them (§6.2, recommended:
  whole — "unknown function" is never the answer for a function upstream declares);
  exclusion rule keys on `Claims`. Done: unclaimed = 0; corpus pass count expected UP.
- **Batch 5 (generated text):** the TSV; the signature oracle (match / same-FQN-different-
  signature / ours-only) run once as verification; then `Pure.java` generated; `CORE_IMPORTS`
  and the names table (PlatformTypes + SystemMetamodel's 104) generated the same way.

## 6. Decisions asked of the user

1. **§6.1 shape** — this document: derived claims + one typed explicit table, exactly one
   claim per overload, honesty by owner-source mention. Approve / amend.
2. **§6.2** — DECIDED: the prelude carries **all** upstream natives in the read roots (the
   44 undeclared today + whatever leaves Pure.java), respelled.
3. **§6.5 cadence** — DECIDED: **every two weeks**, Legend's own release cadence; one bump PR
   opened by a scheduled job once `tools/bump.sh` exists (batch 8 builds it).
