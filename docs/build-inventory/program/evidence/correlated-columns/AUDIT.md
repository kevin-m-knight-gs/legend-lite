# Audit: a computed column reads the enclosing row (branch `core/correlated-columns`, 2026-10-14)

The independent audit agent's report on commit 70e595ff7 over main cafedc562. The commit was then amended with the
should-fixes and nits taken (the landed commit is named in GATES, "A computed column reads the enclosing row").
Read-only; the auditor ran no Bazel. Quoted as delivered, the worktree path removed from its file list.

---

Verdict: ready after fixes. 0 blockers, 3 should-fix, 2 nits. I ran no Bazel and no tests. Everything below comes
from reading the code at 70e595ff7.

**Blockers:** none found.

**Should-fix**

S1. The render census, the corpus passes and the reference lane must judge this before it lands, as the memory rules
"Census judges compiler changes" and "Typing changes need the reference lane" require. Neither the commit nor
IN_FLIGHT reports a run; IN_FLIGHT only says "judged as every change here".
- **Where it differs.** The old resolver resolved every read by name against the base, whatever variable it read.
  Now a read of any variable other than the lambda's own goes only to the enclosing scopes
  (Lowerer.java:1718-1742). At the root, where the enclosing stack is empty, such a read is unfoldable and fails
  loudly after the one isolation (Lowerer.java:1426-1433).
- **Shapes I expect to differ:**
  - (a) A correlated scalar subquery or nested extend whose column reads an outer column that has the same name as
    a base column. The parent silently self-correlated here; the SQL now names the outer alias, so these are wrong
    answers being fixed.
  - (b) Resolver-synthesized column lambdas whose body reads a variable other than their parameter, or reads an
    outer row. Before, these resolved silently against the base; now they throw. Candidates:
    - `Substitution.rewriteInstanceProject` (resolver/Substitution.java:3343-3366): its second pass "correlates
      OUTER reads" (`$this.businessDate`) inside project column functions.
    - `Pipelines.rewriteRowReads` with a fresh row variable for bindings (resolver/Pipelines.java:1138-1144).
    - I traced `Substitution.rewriteLambda` (:485-499) and the GraphEmission and RelationalRootForm leaves. They use
      their parameter consistently, so I don't expect a change from them.
  - (c) Queries that threw before and now render. These are new rows, not diffs.

S2. The comment at CorrelatedComputedColumnTest.java:90 ("the outer row read plain, and in the WHERE only: both
worked before, kept") is very likely false for the plain read.
- SqlToPure.java:2776-2793 turns a select item that reads an outer-scope column (`col.var() != null`) into a computed
  extend column with the value `$e.firstName`. It does not pass the column through.
- On the parent, `(v, name) -> resolveOrThrow(base, "firstName")` would resolve against the inner EMP `x`, which
  also has `firstName`. Case 3 (:91) would then return x's first name, the row with ID 1 ("Ann"), on every row, a
  silent self-correlation that disagrees with the DuckDB oracle.
- IN_FLIGHT's own first repro, `SELECT (SELECT t.a FROM u WHERE u.k = 1)` (docs/IN_FLIGHT.md:1419-1421), says the
  plain read threw when the tables differ.
- So case 3 is a regression pin, not a "kept" case. Reword the comment; the commit message could say so too.

S3. `c.fn().parameters().get(0)` (Lowerer.java:1483) adds an IndexOutOfBounds where the parent had none: the old
resolver never looked at the parameters.
- I found no TypedFuncCol built with zero parameters. ValidateDesugar's `constLambda` uses `x`
  (validation/ValidateDesugar.java:393-396) even though its comments spell `|'n'`. StoreResolver:1270 uses `p`.
- The Typer does type zero-argument lambda literals (compiler/spec/Typer.java:261-270), and I did not prove that
  ExtendChecker or ProjectChecker reject one.
- A one-line guard, `parameters().isEmpty() ? null : get(0)`, keeps a constant column working. With a null own
  variable, `scopedResolver` treats only a `var == null` read as the column's own row. No call site passes a null
  variable: Lowerer.java:2634, :2653, :2845 and RowGetters.java:33 all pass `v.name()` or the row name.

**Checks that hold**

1. **The lambda's own reads are unchanged.** For a read of the column's own row:
   - Column reads, bare `$row` reads (name null) and row-getter reads (RowGetters.java:33) all reach
     `resolveOrThrow(select, name)` through Lowerer.java:1719-1722, the same call as before.
   - A miss is still unfoldable (Lowerer.java:1723-1731).
   - Let bindings and plan parameters are checked before the resolver: Lowerer.java:2640-2649 for a field read,
     :2842-2846 for a bare variable, :269 and :274 for the plan parameters.
   - Inner lambda parameters are caught by `LambdaBinding.lambdaResolver` and its siblings before the column's
     resolver (LambdaBinding.java:65-75, :303-313).
   - A `let` inside a column lambda is dropped by `LambdaBinding.last` (LambdaBinding.java:346-348) both before and
     after this change, so nothing changed there.
2. **Grouped base: the SQL is valid and there is no self-correlation.**
   - Own reads over a grouped base still go through `resolveOrThrow`, as before. `tryPredicate`'s stricter grouped
     resolver (Lowerer.java:1648-1655) exists because of a post-aggregation HAVING self-match. The computed-column
     path never had that rule.
   - An outer reference inside an aggregate query's select list, as in `SELECT MAX(x) + t0.ID ... GROUP BY ...`, is
     legal in standard SQL, Postgres and DuckDB: within one evaluation of the subquery it is a constant.
   - Self-correlation would need a pushed scope over this same select. The pushes at Lowerer.java:616, 918, 965,
     1095, 2865, 3121 and 3198 are all over an enclosing select.
   - Test coverage of a correlated read over a grouped relation in a column:
     - CorrelatedComputedColumnTest.java:109-115: groupBy, then an extend that reads `$p.id`.
     - :87: an aggregate without GROUP BY.
     - CorrelatedRelationCheckerTest.java:121-128 covers a grouped relation, but its column does not read the outer
       row.
3. **No stale snapshot.**
   - The resolver is built per column inside the loop (Lowerer.java:1483), so each of the two attempts
     (Lowerer.java:1422-1425) gets its own.
   - On the second attempt it is built over the isolated base, including the base the many-column path isolates
     (:1458-1461).
   - `enclosing` is balanced by try/finally at every push, so the snapshot (:1718) is the same in both attempts.
4. **`aSameNamedColumnIsTheEnclosingRows` would have failed on the parent.**
   - The extend folds over the filtered `#TDS{id, person}`. The old resolver read `$p.id` as the inner `id`.
   - The column would have come out 100*10+1 = 1001 and 2002, not 11 and 22.
   - The new path: `o` is not `p`, so the read goes to the outer scope, whose own variable is `p` and whose base is
     the outer TDS. That gives 11 and 22.
5. **Test mechanics are sound.**
   - The `sql.replace(EMP, "EMP")` swap (:70) is literal and touches no quoting or ORDER BY. Unquoted `firstName` is
     case-insensitive in DuckDB.
   - All four #SQL cases have `ORDER BY e.ID` on a unique ID.
   - `SqlIslandCases.cell` (SqlIslandCases.java:93-105) normalizes every Number through BigDecimal and strips
     trailing zeros, so DOUBLE and INTEGER sums give the same text; null becomes "null" on both sides.
   - Comparing to `List.of(8, 11)` as Integers matches CorrelatedRelationCheckerTest.java:117 and :126.
   - Dates are 2026-10-14.
   - The commit message's claims match the diff. The one exception is the "worked before" point in S2, which is the
     test's comment, not the commit message.

**Nits**

N1. CorrelatedComputedColumnTest.java:115 is 124 columns, over 120. Break it before `->select`.

N2. IN_FLIGHT's announcement and the commit message describe only the "inside an expression" shape. The plain outer
read, IN_FLIGHT's first repro, is fixed too (see S2) and could be named.

---

## Taken

- S1: the judges ran on the amended commit before the landing; their figures and the shapes that differed (if any)
  are in GATES, "A computed column reads the enclosing row".
- S2: the test's comment says the plain read resolved to the inner row's same-named column before; the commit
  message and the IN_FLIGHT landed note name that shape.
- S3: a column lambda with no parameter owns no row (`scopedResolver`'s own variable is nullable; every read of such
  a column is an enclosing row's).
- N1, N2: taken as written.
