// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend;
import com.legend.builtin.NativeFn;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.exec.ExecutionResult;
import com.legend.exec.PureAsserts;
import java.util.ArrayList;
import java.util.List;

/**
 * THE HOST JUDGE (task #27, 2026-09-21 — split out of AssertVerdicts): both sides of an
 * assert EXECUTE in the database and Java compares the rows ({@link com.legend.exec.Equality},
 * {@link com.legend.exec.PureAsserts}, {@link com.legend.exec.TdsCompare}) — the verdict of
 * record in host mode, the parallel referee of the byte channel. The router
 * ({@link AssertVerdicts}) classifies the assert and hands the sides over; the database
 * judge ({@link DatabaseJudge}) is the other arm of the same router.
 */

final class HostJudge {
    private HostJudge() {
    }

    /** A zip arm's values: a flat-cells arm ({@code $tds.rows.values})
     * contributes its row-major cells — the same cell view the grid
     * verdicts read; any other side decodes as a value list. */
    static List<Object> sideCells(TypedSpec arm, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        ExecutionResult r = StatementExecutor.evalValue(arm, letPrefix,
                specs, env, null, false, hook);
        return r instanceof ExecutionResult.Tabular t ? AssertVerdicts.cells(t) : AssertVerdicts.decodeSide(r);
    }

    static ExecutionResult finish(String family, boolean wantEqual,
            boolean hostHeld, @com.legend.Nullable Boolean byteHeld,
            String detail,
            java.util.function.Supplier<@com.legend.Nullable String> hostMessage) {
        if (byteHeld != null) {
            com.legend.exec.CanonicalDivergence.probeSqlVerdict(family,
                    hostHeld, byteHeld, detail);
        } else {
            com.legend.exec.CanonicalDivergence.sqlNoChannel();   // census residue, by construction
        }
        // JUDGING_TWO_MODES: the host judge's verdict is the verdict of
        // record; the byte channel reported as a census above and never
        // decides (one judge mode per run, on its options)
        if (hostHeld == wantEqual) {
            return AssertVerdicts.ok();
        }
        if (!wantEqual) {
            return AssertVerdicts.fail("assertNotEquals: both sides are equal");
        }
        String d = hostMessage.get();
        if (d == null) {
            throw new IllegalStateException(family
                    + ": verdict/message divergence — the host lattice"
                    + " failed but its message lattice held");
        }
        return AssertVerdicts.fail(d);
    }

    // ── §8 LEG 1 (grid canon, fusion-spike F2, user-ratified
    // 2026-08-28): a TABULAR side's byte channel is its per-ROW canon
    // (per-cell pure-literal spellings, TDS_CELL_SEP-joined, NULL
    // cells spelling bare TDSNull — disjoint from a quoted string);
    // the value peer's row canons FRAME from its literal-channel
    // element canons (chunked by the grid's width — framing writes
    // only separators, never renders). The host cell lattice stays
    // the PARALLEL REFEREE, and the failure message derives from the
    // SAME judgment that failed (the reverted attempt's 28-row
    // phantom: message and judgment from different lattices with the
    // probe unfired — structurally impossible here).

    /** The FLAT-CELLS verdict for a pair with at least one TABULAR
     * AssertVerdicts.side (both-wrapped pairs took the grid-pair arm earlier). */
    static ExecutionResult tdsRowValuesVerdict(String name,
            boolean wantEqual, List<TypedSpec> args,
            List<TypedSpec> letPrefix, SideFetch ef, SideFetch af,
            boolean incidental) {
        List<Object> e = ef.values();
        List<Object> a = af.values();
        // audit 22b F2: raw cells (a bare .rows view) never equal a
        // WHOLE-TDS value — flattening the TDS side would fabricate a
        // match its column-name pin refuses. Static stamps decide.
        boolean mixedFlatVsTds =
                (AssertVerdicts.bareRowStamp(args.get(0), letPrefix) && af.grid() != null
                        && AssertVerdicts.wrappedRelationStamp(args.get(1), letPrefix))
                || (AssertVerdicts.bareRowStamp(args.get(1), letPrefix) && ef.grid() != null
                        && AssertVerdicts.wrappedRelationStamp(args.get(0), letPrefix));
        // the grid's DECLARED column types decide each cell (step 2b)
        ExecutionResult.Tabular g = af.grid() != null ? af.grid() : ef.grid();
        List<com.legend.compiler.element.type.Type> kinds = new ArrayList<>();
        if (g != null) {
            g.columns().forEach(c -> kinds.add(c.pureType()));
        }
        boolean hostHeld;
        if (mixedFlatVsTds) {
            hostHeld = false;
        } else {
            List<com.legend.exec.Equality.Typed> te = com.legend.exec.Equality.grid(e, kinds);
            List<com.legend.exec.Equality.Typed> ta = com.legend.exec.Equality.grid(a, kinds);
            hostHeld = com.legend.exec.Equality.ordered(te, ta) == null;
            if (!hostHeld && incidental && e.size() == a.size()) {
                // ROW COHESION (audit 9): the incidental-order fallback
                // matches ROW TUPLES of the grid's width — cross-row
                // cell shuffles must FAIL; width 1 = the pool multiset
                int w = g != null ? g.columns().size() : 1;
                hostHeld = com.legend.exec.Equality.rowMultiset(
                        te, ta, w > 1 && e.size() % w == 0 ? w : 1);
            }
        }
        Boolean byteHeld = null;
        String detail = "";
        if (!mixedFlatVsTds) {
            ExecutionResult.Tabular wg = af.grid() != null ? af.grid()
                    : java.util.Objects.requireNonNull(ef.grid(),
                            "grid verdict without a grid side");
            int w = wg.columns().size();
            List<String> ec = AssertVerdicts.sideRowCanons(ef, w, true);
            List<String> ac = AssertVerdicts.sideRowCanons(af, w, false);
            if (ec != null && ac != null) {
                List<String> es = new ArrayList<>(ec);
                List<String> as2 = new ArrayList<>(ac);
                if (incidental) {
                    es.sort(String::compareTo);
                    as2.sort(String::compareTo);
                }
                byteHeld = es.equals(as2);
                // the DECLARED 2-ULP dialect-arithmetic policy, grid
                // form (the scalar channel's withinDeclaredUlp arm):
                // byte-differing rows whose every POSITIONAL cell pair
                // holds in the lattice with only finite-Double drift
                // hold BY POLICY — counted in the policy's own census
                // row, never a disagreement rescue.
                if (!byteHeld && hostHeld
                        && com.legend.exec.Equality.differByLeniencyOnly(
                                com.legend.exec.Equality.grid(e, kinds),
                                com.legend.exec.Equality.grid(a, kinds))) {
                    com.legend.exec.CanonicalDivergence.sqlUlpPolicy(
                            "grid " + com.legend.exec.TdsCompare
                                    .firstCanonDiff(es, as2));
                    byteHeld = true;
                }
                detail = "tds rows=" + ec.size() + "/" + ac.size()
                        + (byteHeld ? "" : com.legend.exec.TdsCompare
                                .firstCanonDiff(es, as2));
            }
        }
        return finish(name, wantEqual, hostHeld, byteHeld, detail,
                () -> mixedFlatVsTds
                        ? name + " (TDSRow.values) raw cells do not"
                                + " equal a whole TDS value"
                        : tdsHostMessage(name, PureAsserts.assertEqualsTyped(
                                com.legend.exec.Equality.grid(e, kinds),
                                com.legend.exec.Equality.grid(a, kinds))));
    }

    /** The TDSRow.values failure narrative — the host lattice's text
     * with the pure-API prefix; null iff the lattice held. */
    static @com.legend.Nullable String tdsHostMessage(String name,
            @com.legend.Nullable String d) {
        return d == null ? null
                : name + " (TDSRow.values) " + d.replaceFirst("^\\n", "");
    }

    /** The MULTISET flat-cells verdict: loose CELL pool host lattice
     * (direction-aware sentinel — pool matching, never a sorted zip:
     * sorting separates an expected 'TDSNull' from its NULL cell),
     * cell-level canon multiset as the byte channel. */
    static ExecutionResult tdsRowValuesSameElements(String family,
            SideFetch ef, SideFetch af) {
        List<Object> e = ef.values();
        List<Object> a = af.values();
        boolean hostHeld = e.size() == a.size()
                && com.legend.exec.Equality.rowMultiset(
                        com.legend.exec.Equality.Typed.all(e, null),
                        com.legend.exec.Equality.Typed.all(a, null), 1);
        List<String> ec = AssertVerdicts.sideCellCanons(ef, true);
        List<String> ac = AssertVerdicts.sideCellCanons(af, false);
        Boolean byteHeld = null;
        String detail = "";
        if (ec != null && ac != null) {
            List<String> es = new ArrayList<>(ec);
            List<String> as2 = new ArrayList<>(ac);
            es.sort(String::compareTo);
            as2.sort(String::compareTo);
            byteHeld = es.equals(as2);
            detail = "tds cells=" + ec.size() + "/" + ac.size()
                    + (byteHeld ? "" : com.legend.exec.TdsCompare
                            .firstCanonDiff(es, as2));
        }
        return finish(family, true, hostHeld, byteHeld,
                detail,
                () -> {
                    String d = PureAsserts.assertSameElements(e, a);
                    return d != null
                            ? tdsHostMessage(family, d)
                            : family + " (TDSRow.values): cell"
                                    + " multiset differs";
                });
    }

    /** One assert SIDE: the argument expression executed in the
     * database through the ordinary pipeline, flattened to wire values
     * (a null scalar is the EMPTY collection — pure [0..1] emptiness). */
    /** R2a kind gate + DB renders: both sides must have the SAME
     * statically-stamped kind class (cross-kind incl. empty-vs-empty
     * stays the host lattice's — DECLINE, not a guess); the byte
     * verdict is equality of the two DB-computed canonical texts
     * (null text = EMPTY side; empty==empty holds, empty==value
     * fails — pure's own rule). */
    record SqlVerdict(boolean held, String detail) {
    }

    static @com.legend.Nullable SqlVerdict sqlByteVerdict(
            TypedSpec eSpec, TypedSpec aSpec, SideFetch ef, SideFetch af,
            List<TypedSpec> letPrefix, StatementExecutor.ExecEnv env,
            boolean hostHeld) {
        List<Object> eVals = ef.values();
        List<Object> aVals = af.values();
        KindClass ke = KindClass.of(eSpec.info().type());
        KindClass ka = KindClass.of(aSpec.info().type());
        boolean eAny = AssertVerdicts.isAnyStamped(eSpec);
        boolean aAny = AssertVerdicts.isAnyStamped(aSpec);
        // MIXED-KIND numeric collections are unsound under SQL column
        // promotion (pure refuses 1 == 1.0 element-wise; one DOUBLE
        // column erases the distinction) — the HOST-fetched element
        // kinds gate the route (a routing fact, not a verdict). An
        // ANY side is EXEMPT (F10 v1): its JSON carrier never promotes
        // — each cell keeps its own kind and the literal channel spells
        // 1 and 1.0 apart.
        // ... and a LITERAL-ONLY AssertVerdicts.side (the F10 kind-faithful carrier —
        // Number-stamped mixed LITERAL collections and mixed-sort
        // results ride it, label LITERAL) is equally exempt: its cells
        // never promote. RESIDUAL GUARD (F10 slice 2): only a COMPUTED
        // mixed collection (concatenated/derived, not a literal — no
        // carrier claim yet) can reach this decline; zero witnesses
        // today and the ceiling is pinned 0, so a firing is a NAMED
        // work item, never a silent count.
        if ((!eAny && !ef.rider().literalOnly() && KindClass.Fine.mixed(eVals))
                || (!aAny && !af.rider().literalOnly()
                        && KindClass.Fine.mixed(aVals))) {
            com.legend.exec.CanonicalDivergence.sqlDeclined(
                    "mixed-kind-collection");
            return null;
        }
        // X5: a Nil-stamped side is the []-born EMPTY value — pure
        // equality against ANY kind is decided by emptiness alone
        // (equal([], x) is element-wise vacuous), so the kind classes
        // need not match; both canons frame '[]' when empty and any
        // non-empty side byte-differs from '[]' — the engine's answer.
        boolean anyNil = com.legend.compiler.element.type.PlatformTypes
                .isNil(eSpec.info().type())
                || com.legend.compiler.element.type.PlatformTypes
                        .isNil(aSpec.info().type());
        // F10 v1: an ANY-stamped side has no static kind — the pair
        // compares in the pure-LITERAL channel (six disjoint spellings
        // carry kind in the bytes), so the static gate defers
        boolean anyAny = eAny || aAny;
        if (ke == null || ka == null
                || (!anyNil && !anyAny && !ke.equals(ka))) {
            com.legend.exec.CanonicalDivergence.sqlDeclined("kind-gate: "
                    + AssertVerdicts.typeName(eSpec) + " / " + AssertVerdicts.typeName(aSpec));
            return null;
        }
        // V11: the canon rode each side's OWN query (wrapWithCanon) —
        // a decline recorded by the wrap (non-SQL arm, unclaimed kind,
        // non-scalar shape) routes the pair to the host lattice.
        if (ef.rider().declined() != null) {
            com.legend.exec.CanonicalDivergence.sqlDeclined(
                    "side-e: " + ef.rider().declined());
            return null;
        }
        if (af.rider().declined() != null) {
            com.legend.exec.CanonicalDivergence.sqlDeclined(
                    "side-a: " + af.rider().declined());
            return null;
        }
        // F13 — IDENTITY-pair guards (keyless class: the canon claimed
        // via the synthetic __id identity field). Map carriers are NOT
        // identity pairs — mapEquals (F12) is their own claimed rule.
        if (!anyNil && !anyAny && ke instanceof KindClass.Instance
                && !com.legend.compiler.element.type.PlatformTypes
                        .isMapCarrier(eSpec.info().type())
                && AssertVerdicts.instanceKeys(eSpec, aSpec, env) == null) {
            // v1 exclusion: a constructor under a LAMBDA evaluates per
            // element but mints ONE site id (no row index reaches
            // list_transform) — identity would conflate distinct
            // instances; decline, counted (OPEN_REGISTER F13).
            List<TypedSpec> scope = new ArrayList<>(letPrefix);
            scope.add(eSpec);
            scope.add(aSpec);
            if (AssertVerdicts.keylessCtorUnderLambda(scope, env)) {
                com.legend.exec.CanonicalDivergence.sqlDeclined(
                        "keyless-ctor-in-lambda: " + ke);
                return null;
            }
            // an instance wire that carries NO id (a producer outside
            // the minting sites) must never byte-judge — identity
            // unknown is a decline, never a fabricated equality
            for (Object v : AssertVerdicts.concat(eVals, aVals)) {
                if (v instanceof java.util.Map<?, ?> m
                        && m.get(com.legend.compiler.element.ClassLayouts
                                .SYNTHETIC_ID) == null) {
                    com.legend.exec.CanonicalDivergence.sqlDeclined(
                            "identityless-instance-wire: " + ke);
                    return null;
                }
            }
        }
        // X4 (VERDICT_RULE_AUDIT): the engine has NO cross-primitive-
        // kind equality — numeric pairs must be the SAME fine kind.
        // Abstract Number stamps projected one candidate column per
        // kind; the RUNTIME value kinds (pure's own Number dispatch)
        // SELECT the column — selection, never evaluation. Cross-kind
        // pairs decline to the host lattice's engine-FALSE.
        if (!anyNil && anyAny
                && (ke instanceof KindClass.Enum || ka instanceof KindClass.Enum)) {
            com.legend.exec.CanonicalDivergence.sqlDeclined(
                    "any-pair: enum against an untyped (Any) wire: "
                            + (ke instanceof KindClass.Enum ? ke : ka));
            return null;
        }
        int ei = 0;
        int ai = 0;
        if ((anyAny || ef.rider().literalOnly()
                || af.rider().literalOnly()) && !anyNil) {
            // both sides compare in the literal channel; a side without
            // AssertVerdicts.one (unrefined Number, non-literal kind) declines
            ei = ef.rider().literalIndex();
            ai = af.rider().literalIndex();
            if (ei < 0 || ai < 0) {
                com.legend.exec.CanonicalDivergence.sqlDeclined(
                        "any-pair: no literal channel: " + AssertVerdicts.typeName(eSpec)
                                + " / " + AssertVerdicts.typeName(aSpec));
                return null;
            }
        } else if (!anyNil && ke == KindClass.Primitive.NUMERIC) {
            KindClass.Fine fe = selectedFineKind(ef, eVals);
            KindClass.Fine fa = selectedFineKind(af, aVals);
            if (fe == null || fa == null) {
                com.legend.exec.CanonicalDivergence.sqlDeclined(
                        "unrefined-number: " + AssertVerdicts.typeName(eSpec) + " / "
                                + AssertVerdicts.typeName(aSpec));
                return null;
            }
            if (fe != fa) {
                com.legend.exec.CanonicalDivergence.sqlDeclined(
                        "cross-kind-numeric: " + fe + "/" + fa);
                return null;
            }
            ei = candidateIndex(ef, fe);
            ai = candidateIndex(af, fa);
            if (ei < 0 || ai < 0) {
                com.legend.exec.CanonicalDivergence.sqlDeclined(
                        "unrefined-number: no candidate for " + fe);
                return null;
            }
        }
        Framed fe2 = frame(ef, ei);
        Framed fa2 = frame(af, ai);
        if (fe2.decline() != null || fa2.decline() != null) {
            com.legend.exec.CanonicalDivergence.sqlDeclined(
                    fe2.decline() != null ? "render-e: " + fe2.decline()
                            : "render-a: " + fa2.decline());
            return null;
        }
        if (AssertVerdicts.containsTreeMarker(fe2.text())
                || AssertVerdicts.containsTreeMarker(fa2.text())) {
            // an Any cell held a JSON tree — the literal channel cannot
            // spell it (F10 proper's kind-tagged carrier will); decline,
            // never compare markers (equal trees would fabricate)
            com.legend.exec.CanonicalDivergence.sqlDeclined(
                    "any-wire-tree: " + AssertVerdicts.typeName(eSpec) + " / "
                            + AssertVerdicts.typeName(aSpec));
            return null;
        }
        boolean byteEqual = java.util.Objects.equals(fe2.text(), fa2.text());
        String detail = "kinds=" + ef.rider().kinds().get(ei) + "/"
                + af.rider().kinds().get(ai)
                + " e<" + fe2.text() + "> a<" + fa2.text() + ">";
        // DECLARED 2-ULP dialect-arithmetic policy (OPEN_REGISTER §5,
        // X6/R3 owns its retirement): cross-dialect libm computes
        // transcendentals a last ULP apart (H2-derived corpus goldens
        // vs DuckDB acos/log/tan). The policy rides ON TOP of the byte
        // channel — byte-differing all-finite-Double pairs within
        // 2 ULP hold BY POLICY, counted in their own census row (the
        // host lattice carries the same policy, so this is never a
        // disagreement rescue). Before runtime-kind refinement these
        // pairs declined as unrefined NUMBER and the host policy
        // decided; the refinement must not silently retire the policy.
        if (!byteEqual && AssertVerdicts.withinDeclaredUlp(eVals, aVals)) {
            com.legend.exec.CanonicalDivergence.sqlUlpPolicy(detail);
            return new SqlVerdict(true, "2ulp-policy " + detail);
        }
        // DECLARED TDSNull-sentinel policy (PureAsserts equalScalar:
        // an EXPECTED literal 'TDSNull' equals an actual NULL cell —
        // the engine golden's null spelling; audit 16 F5 keeps it
        // direction-aware). The canon spells the two differently by
        // construction, so a byte-differing pair that the host lattice
        // HOLDS and whose expected side carries the sentinel holds BY
        // POLICY — counted in its own census row (the 2-ULP shape),
        // never a silent rescue.
        if (!byteEqual && hostHeld && AssertVerdicts.containsTdsNullSentinel(eVals)) {
            com.legend.exec.CanonicalDivergence.sqlTdsNullPolicy(detail);
            return new SqlVerdict(true, "tdsnull-policy " + detail);
        }
        return new SqlVerdict(byteEqual, detail);
    }

    /** The fine numeric kind whose candidate column judges this side:
     * a refined stamp names it directly; an unrefined Number resolves
     * from the RUNTIME value kinds; null = undeterminable (decline). */
    static KindClass.@com.legend.Nullable Fine selectedFineKind(
            SideFetch f, List<Object> vals) {
        List<com.legend.compiler.element.type.Type> kinds = f.rider().kinds();
        if (kinds.size() == 1) {
            return KindClass.Fine.ofType(kinds.get(0));
        }
        KindClass.Fine k = KindClass.Fine.ofValues(vals);
        // B8: a runtime BigDecimal is evidence of the CARRIER, not the
        // kind — precision-exact Float literals are decimal-carried BY
        // DESIGN (the reference's own interpreted Float is
        // BigDecimal-backed; receipt in CFloat). When the compiler's
        // candidate set rules pure-Decimal OUT (no decimal candidate)
        // and a float candidate exists, the value IS a decimal-carried
        // Float and judges through the float canon. Static truth gates
        // the resolution; the value alone never decides a kind.
        if (k == KindClass.Fine.DECIMAL && candidateIndex(f, KindClass.Fine.DECIMAL) < 0
                && candidateIndex(f, KindClass.Fine.FLOAT) >= 0) {
            return KindClass.Fine.FLOAT;
        }
        return k;
    }

    /** Index of the fine kind's candidate column in the rider's
     * projection order, or -1. */
    static int candidateIndex(SideFetch f, KindClass.Fine fine) {
        List<com.legend.compiler.element.type.Type> kinds = f.rider().kinds();
        for (int i = 0; i < kinds.size(); i++) {
            if (fine == KindClass.Fine.ofType(kinds.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** A framed side canon: {@code text} null = EMPTY (two empties are
     * byte-equal, as before); {@code decline} = an unframeable side. */
    record Framed(@com.legend.Nullable String text,
            @com.legend.Nullable String decline) {
    }

    /** CanonicalForm.renderSide framing over the DB-computed element
     * texts (V11): 0 elements → '[]', 1 → the bare text, N → '[a, b]'.
     * The DATABASE computed every element's canonical text and (for
     * assertSameElements) the canonical order; this join writes only
     * the spec's separators — framing, never rendering. */
    static Framed frame(SideFetch f, int idx) {
        List<String[]> rows = f.rider().rows();
        if (!f.rider().many()) {
            // EVERY empty form canons '[]' (X5 unification): pure has
            // no null value — a [0..1] with no row and a NULL cell are
            // both the EMPTY collection, and equal([],[]) holds across
            // multiplicities, so scalar-empty must byte-match
            // collection-empty ('[]' == '[]'), never null-vs-'[]'.
            if (rows.isEmpty() || rows.get(0)[idx] == null) {
                return new Framed("[]", null);
            }
            return new Framed(rows.get(0)[idx], null);
        }
        if (rows.isEmpty()) {
            return new Framed("[]", null);
        }
        if (rows.size() == 1) {
            String t = rows.get(0)[idx];
            return t == null ? new Framed(null, "null-canon-cell")
                    : new Framed(t, null);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            String t = rows.get(i)[idx];
            if (t == null) {
                return new Framed(null, "null-canon-cell");
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(t);
        }
        return new Framed(sb.append(']').toString(), null);
    }

    /** Every wire value is an instance stamped {@code __type} = {@code cls}. */
    static boolean allWireType(List<Object> vals, String cls) {
        if (vals.isEmpty()) {
            return false;
        }
        for (Object v : vals) {
            if (!(v instanceof java.util.Map<?, ?> m)
                    || !cls.equals(m.get(com.legend.compiler.element.ClassLayouts.SYNTHETIC_TYPE))) {
                return false;
            }
        }
        return true;
    }

    /** X5 — the HOST lattice's keyed-instance rule, applied as
     * MODEL-DRIVEN evidence projection at the K-arm: EqualityUtilities
     * compares a keyed class BY ITS KEY PROPERTIES ONLY, so both
     * sides' wire maps restrict to the key tree before the ONE lattice
     * judges (non-key fields are outside the equality relation — this
     * is the engine's rule, not a leniency). Keyless classes are
     * untouched (their sides never produce a non-null key tree). */
    static List<Object> restrictToKeys(List<Object> vals,
            com.legend.compiler.element.EqualityKeys keys, ModelContext ctx) {
        List<Object> out = new ArrayList<>(vals.size());
        for (Object v : vals) {
            out.add(restrictOne(v, keys, ctx));
        }
        return out;
    }

    static Object restrictOne(Object v,
            com.legend.compiler.element.EqualityKeys keys, ModelContext ctx) {
        if (!(v instanceof java.util.Map<?, ?> m)) {
            return v;
        }
        var out = new java.util.LinkedHashMap<String, Object>();
        for (var k : keys.keys()) {
            Object val = m.get(k.name());
            if (val != null) {
                val = val instanceof List<?> l
                        ? l.stream().map(x -> restrictNested(x, k.nested(), ctx))
                                .toList()
                        : restrictNested(val, k.nested(), ctx);
            }
            out.put(k.name(), val);
        }
        return out;
    }

    /** A value inside a key slot: restricted to the keys of the slot's
     * DECLARED class when that class is keyed, else to the keys of the
     * value's OWN class — the wire carries it as {@code __type}
     * (WORLD_MAP §4: a polymorphic slot's elements are judged by their
     * own classifier, the engine's rule). A keyless class stays the
     * whole map (identity). */
    static Object restrictNested(Object v,
            com.legend.compiler.element.@com.legend.Nullable EqualityKeys declared,
            ModelContext ctx) {
        if (declared != null) {
            return restrictOne(v, declared, ctx);
        }
        if (v instanceof java.util.Map<?, ?> m
                && m.get(com.legend.compiler.element.ClassLayouts.SYNTHETIC_TYPE)
                        instanceof String own) {
            var keys = com.legend.compiler.element.EqualityKeys.resolve(ctx, own);
            if (keys != null) {
                return restrictOne(v, keys, ctx);
            }
        }
        return v;
    }

    /** One assert side under V11: the values (host referee, gates,
     * declared policies) and the canon rider (byte verdict texts) —
     * both produced by the SAME single execution. §8 leg 1: a TABULAR
     * side keeps its grid ({@code grid} non-null; {@code values} are
     * the row-major CELLS, NULL slots kept — engine TDSRow semantics,
     * column names OUT) and its canon is the rider's per-ROW texts. */
    record SideFetch(List<Object> values,
            com.legend.exec.CanonRider rider,
            ExecutionResult.@com.legend.Nullable Tabular grid) {
    }

    /** NUMERIC CHARTER Rule 3: both sides DECLARED Float — their kind is
     * Float whatever carrier the wire chose (PureAsserts judges by value). */
    /** A side's values paired with the kind its DECLARATION gives them — the
     * argument's type (a collection's element type); the judge lets a fine
     * primitive kind decide and takes the carrier's for Number / classes. */
    static List<com.legend.exec.Equality.Typed> typedSide(List<Object> values,
            TypedSpec arg) {
        return com.legend.exec.Equality.Typed.all(values, arg.info().type());
    }

    static SideFetch sideCanon(TypedSpec arg,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env, boolean canonicalOrder,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        var rider = new com.legend.exec.CanonRider(canonicalOrder);
        ExecutionResult r = StatementExecutor.evalValue(arg, letPrefix,
                specs, env, rider, false, hook);
        if (r instanceof ExecutionResult.Tabular t) {
            List<Object> cells = AssertVerdicts.cells(t);
            com.legend.exec.CanonicalDivergence.v7SideRows(cells.size());
            return new SideFetch(cells, rider, t);
        }
        // NUMERIC CHARTER Rule 2 (docs/NUMERIC_CHARTER_2026_09_17.md): a
        // side's cells are converted ONCE by the side's DECLARED kind —
        // IN SQL, on the side's own root select (the value-root envelope
        // the Lowerer applies to every Float-declared root), never here:
        // the referee judges values, it never evaluates them (tenet #1,
        // JavaEvalLedgerTest). Both sides of every verdict ride that same
        // path, so a DECIMAL carrier under a Float declaration and a Float
        // literal meet as the SAME kind (Rule 3 then judges same-kind,
        // same-value).
        return new SideFetch(AssertVerdicts.decodeSide(r), rider, null);
    }

    /** F13c — a side on the IDENTITY LANE without a canon rider: the
     * assert-condition/predicate evaluator (in-SQL eq/equal needs
     * instance identity; the boolean egress keeps every other lane
     * blind to the field). */
    static List<Object> identitySide(TypedSpec arg,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env, @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        return AssertVerdicts.decodeSide(StatementExecutor.evalValue(arg, letPrefix,
                specs, env, null, true, hook));
    }

    /** Failure-message sketch of a frame (columns + row count — the
     * spec's toString(true) grid rendering is message-position only;
     * no witness pins its spelling). */
    static String summarize(ExecutionResult.Tabular t) {
        return t.columns().stream().map(com.legend.exec.Column::name)
                .toList() + " (" + t.rows().size() + " rows)";
    }

}
