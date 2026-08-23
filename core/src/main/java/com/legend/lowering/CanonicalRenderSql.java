// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;

import java.util.List;
import java.util.Objects;

/**
 * R2's SQL-side canonical scalar render (docs/CANONICAL_FORM_SPEC.md
 * §2): the DATABASE computes the byte-channel text — tenet #1, the
 * render IS the semantic work; Java's only remaining act on a verdict
 * is comparing two DB-computed byte strings. The kind comes from the
 * STAMP (types drive construction — never runtime sniffing), and every
 * rule mirrors the host reference render ({@code CanonicalForm}), the
 * pair the divergence census holds together.
 *
 * <p>Returns null for kinds the SQL channel does not (yet) claim —
 * the caller falls back to the host lattice and the decline is
 * counted, never silent.
 */
public final class CanonicalRenderSql {

    private CanonicalRenderSql() {
    }

    /** Canonical VARCHAR of a scalar value expression, by STAMPED kind. */
    public static @com.legend.Nullable SqlExpr scalarCanon(SqlExpr v, Type t) {
        if (t == Type.Primitive.STRING) {
            return v;
        }
        if (t == Type.Primitive.BOOLEAN || t == Type.Primitive.INTEGER
                || t == Type.Primitive.STRICT_DATE) {
            // DuckDB casts: booleans print true/false, integers bare,
            // dates ISO — already the H1 forms
            return new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR);
        }
        if (t == Type.Primitive.DECIMAL
                || t instanceof Type.PrecisionDecimal) {
            // PrecisionDecimal IS Decimal with a declared shape — same
            // scale-normalized canonical form (V6 burn)
            return decimalCanon(v);
        }
        if (t instanceof Type.EnumType) {
            // enum values ride the wire as their NAMES (the canonical
            // form per H1: bare member name) — the kind gate already
            // scoped equality to the SAME enumeration
            return v;
        }
        if (t == Type.Primitive.FLOAT) {
            return floatCanon(v);
        }
        if (t == Type.Primitive.DATE_TIME || t == Type.Primitive.DATE) {
            return temporalCanon(v);
        }
        return null;
    }

    /**
     * V11 — wrap a lowered side plan so the canon rides the SAME query
     * ({@code SELECT value, canon(value) FROM (plan) side}): one
     * execution produces values and canon texts together, deleting the
     * double-execution obligation. Declines (recorded on the rider,
     * plan returned unchanged) when the plan is not a single-column
     * scalar shape or no candidate kind is claimed.
     *
     * <p>An unrefined NUMBER root projects one candidate column per
     * fine kind (the plan's OutputCol type is stamp-derived and cannot
     * name the member — V6-round-2 circularity); the verdict layer
     * selects by runtime value kind. canonicalOrder (assertSameElements)
     * sorts rows by the canon text IN THE DATABASE — declined for
     * multi-candidate sides (no single ordering key exists).
     */
    /** The wrap outcome: a wrapped plan with its candidate kinds, or a
     * decline reason with the plan unchanged. Lowering stays pure of
     * the exec layer (Invariant 6h) — the driver records this on its
     * rider. */
    public record CanonWrap(com.legend.sql.SqlQuery plan,
            List<Type> kinds, boolean many, int literalIndex,
            @com.legend.Nullable String declineReason) {

        static CanonWrap decline(com.legend.sql.SqlQuery plan,
                String reason) {
            return new CanonWrap(plan, List.of(), false, -1, reason);
        }
    }

    public static CanonWrap wrapWithCanon(com.legend.sql.SqlQuery plan,
            com.legend.compiler.element.type.ExprType rootInfo,
            boolean canonicalOrder,
            com.legend.compiler.element.@com.legend.Nullable EqualityKeys
                    instanceKeys) {
        return wrapWithCanon(plan, rootInfo, canonicalOrder, instanceKeys,
                true);
    }

    /** {@code literalChannel} false = the canon-exec tunnel's MIDDLE
     * rung: a typed side re-wraps WITHOUT its literal candidate (a
     * stamp-derived column type can lie about the wire — witness the
     * BLOB byte carrier under a STRING stamp — and the unbindable
     * literal must not demote the whole side to the host; the bare
     * candidates still byte-decide). Literal-ONLY sides (Any/JSON)
     * have no bare channel and are unaffected by the flag. */
    public static CanonWrap wrapWithCanon(com.legend.sql.SqlQuery plan,
            com.legend.compiler.element.type.ExprType rootInfo,
            boolean canonicalOrder,
            com.legend.compiler.element.@com.legend.Nullable EqualityKeys
                    instanceKeys,
            boolean literalChannel) {
        if (plan.outputs().size() != 1) {
            return CanonWrap.decline(plan, "non-scalar plan shape: "
                    + plan.outputs().size() + " columns");
        }
        Type t = rootInfo.type();
        com.legend.sql.OutputCol valueCol = plan.outputs().get(0);
        SqlExpr valueRef = new SqlExpr.Column(null, valueCol.name());
        List<Type> candidates;
        List<SqlExpr> canons = new java.util.ArrayList<>();
        int literalIndex = -1;
        String instFqn = com.legend.compiler.element.EqualityKeys.fqnOf(t);
        boolean jsonCol = valueCol.type() == SqlType.Scalar.JSON;
        if (jsonCol || (t instanceof Type.ClassType anyCt
                && com.legend.compiler.element.type.PlatformTypes
                        .isAny(anyCt))) {
            // F10 v1 — an ANY-stamped side, or ANY side riding the JSON
            // carrier (the carrier is the FACT — PureSql's own doctrine:
            // the JSON decision follows the LOWERED shape, never the
            // pure type alone; witness the Number-stamped mixed lists).
            // A JSON cell dispatches the pure-literal canon on its
            // RUNTIME type in the database (anyJsonCanon); an Any stamp
            // over a PLAIN column (a let-bound scalar erased to Any —
            // witness testLetWithParam's VARCHAR 'echo') renders the
            // literal of the COLUMN's kind, a wire fact. The ONE
            // candidate IS the literal channel. Trees mark and the
            // verdict layer declines on sight.
            SqlExpr lit;
            if (jsonCol) {
                lit = anyJsonCanon(valueRef);
            } else {
                Type colKind = kindOfSqlType(valueCol.type());
                SqlExpr lc = colKind == null ? null
                        : literalCanon(valueRef, colKind);
                if (lc == null) {
                    return CanonWrap.decline(plan,
                            "any-carrier: " + valueCol.type());
                }
                lit = lc;
            }
            candidates = List.of(t);
            canons.add(new SqlExpr.Cast(lit, SqlType.Scalar.VARCHAR));
            literalIndex = 0;
        } else if (instFqn != null
                && com.legend.compiler.element.type.PlatformTypes
                        .isMapCarrier(t)) {
            // F12 — the engine's OWN map rule (EqualityUtilities.
            // mapEquals): equal key SETS then per-key values,
            // order-INSENSITIVE — the canon is the SORTED entry list,
            // so order-insensitivity is byte-decidable.
            SqlExpr c = mapCanon(valueRef, valueCol.type(), instFqn);
            if (c == null) {
                return CanonWrap.decline(plan,
                        "map-key-shape: " + valueCol.type());
            }
            candidates = List.of(t);
            canons.add(new SqlExpr.Cast(c, SqlType.Scalar.VARCHAR));
        } else if (instFqn != null
                && com.legend.compiler.element.type.PlatformTypes.isNil(t)) {
            // the []-born BOTTOM type: a Nil side is the EMPTY value —
            // zero rows, the canon column is never read (the frame's
            // empty rule renders '[]'); claim with a placeholder
            candidates = List.of(t);
            canons.add(new SqlExpr.NullLit());
        } else if (instFqn != null) {
            // X5 — a KEYED instance side: the canon is the key-property
            // render (EqualityUtilities compares keyed classes by key
            // properties only), compiled from the model. The DRIVER
            // resolved the key tree — lowering stays model-free
            // (Invariant 6h, same inversion as CanonWrap itself).
            if (instanceKeys == null) {
                // F13 — SYNTHETIC IDENTITY: a keyless class's engine
                // equality is INSTANCE IDENTITY; the identity-bearing
                // layout carries it as the __id field (minted per
                // construction site), so the canon is the identity
                // itself — '_type' + '_id', JSON-framed like the keyed
                // canon. A side whose layout carries no __id (Any/
                // variant wire trees, layoutless classes) stays a
                // counted decline.
                SqlExpr idc = identityCanon(valueRef, instFqn,
                        valueCol.type());
                if (idc == null) {
                    return CanonWrap.decline(plan,
                            "keyless-instance: " + instFqn);
                }
                candidates = List.of(t);
                canons.add(new SqlExpr.Cast(idc, SqlType.Scalar.VARCHAR));
            } else {
                SqlExpr c = instanceCanon(valueRef, instanceKeys,
                        valueCol.type());
                if (c == null) {
                    return CanonWrap.decline(plan, "instance-key-shape: "
                            + instFqn + " layout=" + valueCol.type());
                }
                candidates = List.of(t);
                // the ROOT canon is byte text (nested levels stay
                // JSON-typed so they embed structurally)
                canons.add(new SqlExpr.Cast(c, SqlType.Scalar.VARCHAR));
            }
        } else {
            List<Type> bare = t == Type.Primitive.NUMBER
                    ? List.of(Type.Primitive.INTEGER, Type.Primitive.FLOAT,
                            Type.Primitive.DECIMAL)
                    : List.of(t);
            for (Type k : bare) {
                SqlExpr c = scalarCanon(valueRef, k);
                if (c == null) {
                    return CanonWrap.decline(plan, "unclaimed kind: " + k);
                }
                canons.add(c);
            }
            if (canonicalOrder && canons.size() > 1) {
                return CanonWrap.decline(plan,
                        "canonical-order over an unrefined Number side");
            }
            // F10 v1 — the LITERAL candidate (pure-literal spelling,
            // ALWAYS LAST): the channel an Any-involving pair compares
            // in (the verdict layer selects it only then). Single-kind
            // sides only — an unrefined Number's literal is ambiguous.
            candidates = new java.util.ArrayList<>(bare);
            // guarded by the COLUMN kind: a column outside the literal
            // vocabulary (BLOB — the byte wire) must not poison the
            // wrapped query with an unbindable candidate (witness
            // testRepeatStringNoString: replace(BLOB,..) rode the
            // canon-exec tunnel and DEMOTED a bare-decided pair)
            if (literalChannel && bare.size() == 1
                    && kindOfSqlType(valueCol.type()) != null) {
                SqlExpr lit = literalCanon(valueRef, t);
                if (lit != null) {
                    canons.add(new SqlExpr.Cast(lit, SqlType.Scalar.VARCHAR));
                    candidates.add(t);
                    literalIndex = canons.size() - 1;
                }
            }
        }
        var mult = rootInfo.multiplicity().requireBounded("canon side");
        boolean many = mult.upper() == null || mult.upper() > 1;
        List<com.legend.sql.SqlSelect.Projection> projections =
                new java.util.ArrayList<>();
        projections.add(new com.legend.sql.SqlSelect.Projection(
                valueRef, valueCol.name()));
        List<com.legend.sql.OutputCol> outputs = new java.util.ArrayList<>();
        outputs.add(valueCol);
        for (int i = 0; i < canons.size(); i++) {
            projections.add(new com.legend.sql.SqlSelect.Projection(
                    canons.get(i), "__canon" + i));
            outputs.add(new com.legend.sql.OutputCol("__canon" + i,
                    SqlType.Scalar.VARCHAR, true));
        }
        List<com.legend.sql.SqlSelect.SortKey> sort = canonicalOrder
                ? List.of(new com.legend.sql.SqlSelect.SortKey(
                        canons.get(0), true, null, null))
                : List.of();
        return new CanonWrap(new com.legend.sql.SqlSelect(projections,
                false,
                new com.legend.sql.SqlSource.Subselect(plan, "side", null),
                null, List.of(), null, null, sort, null, null, outputs),
                candidates, many, literalIndex, null);
    }

    /** F13 — the IDENTITY canon of an instance whose layout carries the
     * synthetic {@code __id}: {@code {_type, _id}}, JSON-framed like the
     * keyed canon. Null when the layout has no identity field (Any/
     * variant wire trees, layoutless classes). */
    static @com.legend.Nullable SqlExpr identityCanon(SqlExpr v,
            String fqn, SqlType layout) {
        String idField = com.legend.compiler.element.ClassLayouts
                .SYNTHETIC_ID;
        if (!(layout instanceof SqlType.Struct st) || st.fields().stream()
                .noneMatch(f -> idField.equals(f.name()))) {
            return null;
        }
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.IS_NULL, v),
                new SqlExpr.NullLit())),
                new SqlExpr.JsonObject(List.of(
                        new SqlExpr.StringLit("_type"),
                        new SqlExpr.StringLit(fqn),
                        new SqlExpr.StringLit("_id"),
                        new SqlExpr.StructGet(v, idField))));
    }

    /** F13c — instance equality's ONE canon (the in-SQL eq/equal arm's
     * entry): keyed classes render their key tree ({@code instanceCanon},
     * the X5 relation), keyless classes their identity. Null =
     * unclaimable shape (the caller keeps its legacy behavior). */
    static @com.legend.Nullable SqlExpr instanceEqualityCanon(SqlExpr v,
            com.legend.compiler.element.@com.legend.Nullable EqualityKeys
                    keys,
            String fqn, SqlType layout) {
        return keys == null ? identityCanon(v, fqn, layout)
                : instanceCanon(v, keys, layout);
    }

    /**
     * X5 — the KEYED-INSTANCE canon: {@code Fqn(k1, k2, ...)} over the
     * struct column's key fields, in the model's key order. The
     * SPELLING of each leaf comes from the struct LAYOUT's field SQL
     * type (the layout was built from the concrete lowered values, so
     * it carries what the erased ClassType stamp cannot — the V11
     * doctrine: SQL types pick the recipe, runtime values gate the
     * rule); each leaf is KIND-TAGGED ('i:8' vs 'd:8') so cross-kind
     * key values can never byte-collide — the engine's same-primitive-
     * kind rule one level down. An empty [0..1] key value renders '[]'
     * (the engine compares key VALUE COLLECTIONS — empty equals
     * empty); a NULL instance cell stays NULL (EMPTY side semantics).
     * Null = unclaimed shape (to-many key, unknown field, unclaimable
     * leaf kind) — the caller declines, counted.
     */
    static @com.legend.Nullable SqlExpr instanceCanon(SqlExpr v,
            com.legend.compiler.element.EqualityKeys keys, SqlType layout) {
        // the BARE-ARRAY carrier (List<T>): the SQL value IS the one
        // to-many key's collection (PureSql — List travels as an array,
        // never a struct), so the key reads the value itself
        if (layout instanceof SqlType.Array at
                && keys.keys().size() == 1 && keys.keys().get(0).many()) {
            var k = keys.keys().get(0);
            SqlExpr leaf = taggedLeaf(new SqlExpr.Column(null, "__e"),
                    at.element(), k.nested());
            if (leaf == null) {
                return null;
            }
            SqlExpr arr = SqlExpr.Call.of(SqlFn.COALESCE,
                    SqlExpr.Call.of(SqlFn.LIST_TRANSFORM, v,
                            new SqlExpr.Lambda(List.of("__e"), leaf)),
                    new SqlExpr.ArrayLit(List.of()));
            return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                    SqlExpr.Call.of(SqlFn.IS_NULL, v),
                    new SqlExpr.NullLit())),
                    new SqlExpr.JsonObject(List.of(
                            new SqlExpr.StringLit("_type"),
                            new SqlExpr.StringLit(keys.classFqn()),
                            new SqlExpr.StringLit(k.name()), arr)));
        }
        if (!(layout instanceof SqlType.Struct st)) {
            return null;
        }
        // JSON is the FRAMING, never the SPELLING (user ruling
        // 2026-08-22): every leaf value is OUR canonical string —
        // json_object contributes structure and escaping only, so
        // distinct key values can never collide ('a, b' vs 'a','b' —
        // the concat-framing ambiguity class is dead here). '_type'
        // carries the classifier FQN in the bytes themselves (the
        // engine's classifier-must-match rule rides the text, not just
        // the stamp gate). Nested keyed instances nest as JSON objects
        // (JSON-typed values embed structurally, no double-escaping);
        // to-many keys (List.values) are arrays of leaf canons via
        // list_transform — the engine compares key value COLLECTIONS
        // under the ordered list rule, which is exactly JSON array
        // equality over the element texts.
        List<SqlExpr> kv = new java.util.ArrayList<>();
        kv.add(new SqlExpr.StringLit("_type"));
        kv.add(new SqlExpr.StringLit(keys.classFqn()));
        for (var k : keys.keys()) {
            SqlType ft = st.fields().stream()
                    .filter(f -> f.name().equals(k.name()))
                    .map(SqlType.Struct.Field::type)
                    .findFirst().orElse(null);
            SqlExpr field = new SqlExpr.StructGet(v, k.name());
            SqlExpr c;
            if (k.many()) {
                SqlType elem = ft instanceof SqlType.Array at
                        ? at.element() : null;
                SqlExpr leaf = elem == null ? null
                        : taggedLeaf(new SqlExpr.Column(null, "__e"),
                                elem, k.nested());
                if (leaf == null) {
                    return null;
                }
                // NULL and empty are both the EMPTY key collection
                // (engine: empty equals empty) — normalize to []
                c = SqlExpr.Call.of(SqlFn.COALESCE,
                        SqlExpr.Call.of(SqlFn.LIST_TRANSFORM, field,
                                new SqlExpr.Lambda(List.of("__e"), leaf)),
                        new SqlExpr.ArrayLit(List.of()));
            } else if (ft == null) {
                return null;
            } else {
                c = taggedLeaf(field, ft, k.nested());
                if (c == null) {
                    return null;
                }
                // an empty [0..1] key value is the EMPTY collection —
                // renderSide '[]' (engine: empty equals empty)
                c = SqlExpr.Call.of(SqlFn.COALESCE, c,
                        new SqlExpr.StringLit("[]"));
            }
            kv.add(new SqlExpr.StringLit(k.name()));
            kv.add(c);
        }
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.IS_NULL, v),
                new SqlExpr.NullLit())),
                new SqlExpr.JsonObject(kv));
    }

    /** F12 — the MAP canon: entry texts {@code [kLeaf, vLeaf]} built
     * per key (map_extract pairs each key with its value), SORTED (the
     * engine's order-insensitive mapEquals becomes byte-decidable),
     * JSON-framed with the carrier fqn. Leaf spellings are pure
     * literals (quoted strings end unambiguously, so the entry text
     * cannot collide across different key/value splits). Key and value
     * kinds come from the MAP layout's static types; an unclaimable
     * kind declines, counted. */
    private static @com.legend.Nullable SqlExpr mapCanon(SqlExpr v,
            SqlType layout, String fqn) {
        if (!(layout instanceof SqlType.Map mt)) {
            return null;
        }
        SqlExpr k = new SqlExpr.Column(null, "__k");
        SqlExpr kLeaf = taggedLeaf(k, mt.key(), null);
        SqlExpr vLeaf = taggedLeaf(SqlExpr.Call.of(SqlFn.LIST_GET,
                SqlExpr.Call.of(SqlFn.MAP_EXTRACT, v, k),
                new SqlExpr.IntLit(1)), mt.value(), null);
        if (kLeaf == null || vLeaf == null) {
            return null;
        }
        SqlExpr entry = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT,
                        SqlExpr.Call.of(SqlFn.CONCAT,
                                new SqlExpr.StringLit("["), kLeaf),
                        new SqlExpr.StringLit(", ")),
                SqlExpr.Call.of(SqlFn.CONCAT, vLeaf,
                        new SqlExpr.StringLit("]")));
        SqlExpr entries = SqlExpr.Call.of(SqlFn.LIST_SORT,
                SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                        SqlExpr.Call.of(SqlFn.MAP_KEYS, v),
                        new SqlExpr.Lambda(List.of("__k"), entry)));
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.IS_NULL, v),
                new SqlExpr.NullLit())),
                new SqlExpr.JsonObject(List.of(
                        new SqlExpr.StringLit("_type"),
                        new SqlExpr.StringLit(fqn),
                        new SqlExpr.StringLit("entries"), entries)));
    }

    /** One key LEAF: a nested keyed instance recurses (JSON-typed,
     * nests structurally); a scalar renders as PURE'S OWN LITERAL
     * SPELLING ({@link #literalCanon}). */
    private static @com.legend.Nullable SqlExpr taggedLeaf(SqlExpr field,
            SqlType ft,
            com.legend.compiler.element.@com.legend.Nullable EqualityKeys
                    nested) {
        if (nested != null) {
            return instanceCanon(field, nested, ft);
        }
        Type kind = kindOfSqlType(ft);
        if (kind == null) {
            return null;
        }
        return literalCanon(field, kind);
    }

    /** PURE'S OWN LITERAL SPELLING of a scalar (user ruling 2026-08-22
     * — no invented tag micro-format: the engine's grammar already
     * carries kind in text). Integer is bare ({@code 1}), Float always
     * has its point ({@code 1.0}), Decimal keeps its D suffix
     * ({@code 8.00D}), String is pure-quoted with pure escaping
     * ({@code 'a, b'}, {@code 'it\\'s'}), Boolean is bare, temporals
     * carry pure's {@code %} prefix — six disjoint spellings, the
     * engine's same-primitive-kind rule carried by engine syntax.
     * (X5's key-leaf rule, promoted to the shared literal channel —
     * F10 v1 compares Any-involving pairs in it.) */
    static @com.legend.Nullable SqlExpr literalCanon(SqlExpr v, Type kind) {
        SqlExpr leaf = scalarCanon(v, kind);
        if (leaf == null) {
            return null;
        }
        if (kind == Type.Primitive.STRING) {
            // pure string literal: backslash then quote escape, quoted
            SqlExpr escaped = SqlExpr.Call.of(SqlFn.REPLACE,
                    SqlExpr.Call.of(SqlFn.REPLACE, leaf,
                            new SqlExpr.StringLit("\\"),
                            new SqlExpr.StringLit("\\\\")),
                    new SqlExpr.StringLit("'"),
                    new SqlExpr.StringLit("\\'"));
            return SqlExpr.Call.of(SqlFn.CONCAT,
                    SqlExpr.Call.of(SqlFn.CONCAT,
                            new SqlExpr.StringLit("'"), escaped),
                    new SqlExpr.StringLit("'"));
        }
        if (kind == Type.Primitive.DECIMAL) {
            return SqlExpr.Call.of(SqlFn.CONCAT, leaf,
                    new SqlExpr.StringLit("D"));
        }
        if (kind == Type.Primitive.STRICT_DATE
                || kind == Type.Primitive.DATE_TIME) {
            return SqlExpr.Call.of(SqlFn.CONCAT,
                    new SqlExpr.StringLit("%"), leaf);
        }
        return leaf;   // Integer bare, Float with its point, Boolean bare
    }

    /** F10 v1 — the unclaimable-tree sentinel: an Any cell holding a
     * JSON array/object canons to this marker, and the VERDICT layer
     * DECLINES the pair on sight (a marker can never be compared — two
     * equal trees byte-matching it would fabricate equality). No
     * legitimate canon text contains U+0001. */
    public static final String TREE_MARKER = "\u0001tree";

    /** F10 v1 — the ANY-cell canon: pure-literal spelling dispatched
     * on the JSON carrier's RUNTIME type IN THE DATABASE (the carrier
     * keeps JSON-native kinds faithful: number-int/number-frac/string/
     * boolean; erased kinds — temporals and Decimals travel as their
     * JSON forms — canon as what the carrier holds, matching the host
     * referee's decode of the same wire; the kind-tagged carrier that
     * retires this blindness is F10 proper). Arrays/objects mark
     * {@link #TREE_MARKER} — decline, never guess. */
    static SqlExpr anyJsonCanon(SqlExpr v) {
        SqlExpr jt = SqlExpr.Call.of(SqlFn.JSON_TYPE, v);
        SqlExpr txt = new SqlExpr.Cast(
                SqlExpr.Call.of(SqlFn.VARIANT_GET, v,
                        new SqlExpr.StringLit("$")),
                SqlType.Scalar.VARCHAR);
        SqlExpr strLit = Objects.requireNonNull(
                literalCanon(txt, Type.Primitive.STRING));
        SqlExpr floatLit = Objects.requireNonNull(
                literalCanon(txt, Type.Primitive.FLOAT));
        return new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.IS_NULL, v),
                        new SqlExpr.NullLit()),
                new SqlExpr.Case.When(eqText(jt, "VARCHAR"), strLit),
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.OR,
                        eqText(jt, "BIGINT"), eqText(jt, "UBIGINT")), txt),
                new SqlExpr.Case.When(eqText(jt, "DOUBLE"), floatLit),
                new SqlExpr.Case.When(eqText(jt, "BOOLEAN"), txt)),
                new SqlExpr.StringLit(TREE_MARKER));
    }

    private static SqlExpr eqText(SqlExpr e, String s) {
        return SqlExpr.Call.of(SqlFn.EQUAL, e, new SqlExpr.StringLit(s));
    }

    /** The pure canon kind a struct field's SQL type spells — the
     * layout is value-built, so this is a WIRE fact, not a stamp echo. */
    private static @com.legend.Nullable Type kindOfSqlType(SqlType t) {
        if (t == SqlType.Scalar.BIGINT || t == SqlType.Scalar.INTEGER
                || t == SqlType.Scalar.HUGEINT) {
            return Type.Primitive.INTEGER;
        }
        if (t == SqlType.Scalar.DOUBLE) {
            return Type.Primitive.FLOAT;
        }
        if (t == SqlType.Scalar.BOOLEAN) {
            return Type.Primitive.BOOLEAN;
        }
        if (t == SqlType.Scalar.VARCHAR) {
            return Type.Primitive.STRING;
        }
        if (t instanceof SqlType.Decimal) {
            return Type.Primitive.DECIMAL;
        }
        if (t == SqlType.Scalar.DATE) {
            return Type.Primitive.STRICT_DATE;
        }
        if (t == SqlType.Scalar.TIMESTAMP) {
            return Type.Primitive.DATE_TIME;
        }
        return null;
    }

    /** Decimal: SCALE-PRESERVING (X2, VERDICT_RULE_AUDIT — engine
     * Decimal equality is getValue().equals, scale-sensitive; the old
     * scale-normalized canon followed the deleted compareTo grant).
     * CAST already preserves scale ('8.00'); only the wire's 'D'
     * representation suffix (variant/identity VARCHAR channel: 2D,
     * 1.0D) normalizes away. */
    private static SqlExpr decimalCanon(SqlExpr v) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE,
                new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("[Dd]$"),
                new SqlExpr.StringLit(""));
    }

    /**
     * Float: fixed-point ALWAYS (H1 — pure never prints exponent
     * notation). DuckDB's CAST is shortest-repr but switches to
     * exponent for small/large magnitudes; those unfold through a
     * DECIMAL(38,18) re-print with trailing zeros stripped (one
     * decimal kept — integral floats keep {@code .0}). Non-finite
     * spellings pass through — out of the claimed domain (§4), they
     * can never equal legitimate canonical text and the parallel host
     * referee names them residue.
     */
    private static SqlExpr floatCanon(SqlExpr v) {
        SqlExpr base = new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR);
        SqlExpr unfolded = exponentUnfold(base);
        return new SqlExpr.Case(List.of(
                // ZEROS UNIFY (spec §3, witness parseFloat('-000.000')):
                // pure grants 0.0 == -0.0, so the canonical render of
                // every zero is '0.0'. Detected TEXTUALLY (F10 slice 1):
                // the old v = 0.0 compare forced SQL to cast the COLUMN
                // to DOUBLE, which errored the whole wrapped query on
                // print-form identity carriers ('7.345D') — the canon
                // must be TOTAL over any column it can meet. For genuine
                // DOUBLE columns the zero texts are exactly 0.0/-0.0,
                // so the regex is equivalence, not leniency.
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.REGEXP_FULL_MATCH, base,
                                new SqlExpr.StringLit("-?0+(\\.0+)?")),
                        new SqlExpr.StringLit("0.0")),
                new SqlExpr.Case.When(has(base, "e"), unfolded),
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.NOT,
                        has(base, ".")),
                        SqlExpr.Call.of(SqlFn.CONCAT, base,
                                new SqlExpr.StringLit(".0")))),
                base);
    }

    /**
     * Temporal (DateTime/Date stamps): the scalar-channel form —
     * {@code T}-separated, trailing subsecond zeros stripped,
     * {@code +0000} on time-bearing values only. Handles BOTH wire
     * spellings (a TIMESTAMP cell's cast and the precision-faithful
     * VARCHAR convention) through one text pipeline.
     */
    private static SqlExpr temporalCanon(SqlExpr v) {
        // an ALREADY-SUFFIXED wire text (the variant-identity channel
        // prints pure's +0000 form) normalizes before the pipeline —
        // the suffix re-appends canonically at the end
        SqlExpr bare = SqlExpr.Call.of(SqlFn.REGEXP_REPLACE,
                new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("(\\+0000|Z)$"),
                new SqlExpr.StringLit(""));
        SqlExpr t = SqlExpr.Call.of(SqlFn.REPLACE, bare,
                new SqlExpr.StringLit(" "), new SqlExpr.StringLit("T"));
        SqlExpr stripped = stripDot(stripTrailingZeros(t), "");
        SqlExpr timeBearing = has(stripped, "T");
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(timeBearing,
                SqlExpr.Call.of(SqlFn.CONCAT, stripped,
                        new SqlExpr.StringLit("+0000")))),
                stripped);
    }

    /**
     * COMPLETE textual exponent unfold (V10c — replaces the bounded
     * DECIMAL(38,18) cast, which silently zeroed values beyond its
     * envelope): the shortest-repr mantissa digits shift by the
     * exponent as TEXT, so any finite double prints fixed-point
     * exactly — {@code 1.3421e-08 → 0.000000013421},
     * {@code 1e+300 → 1000…000.0}. Pure never prints exponent
     * notation (H1); now neither can we, for any magnitude.
     */
    private static SqlExpr exponentUnfold(SqlExpr base) {
        SqlExpr sign = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.STARTS_WITH, base,
                        new SqlExpr.StringLit("-")),
                new SqlExpr.StringLit("-"))),
                new SqlExpr.StringLit(""));
        // mantissa without sign, e.g. '1.3421'; its digits '13421';
        // intLen = digits before the dot; exp as an integer
        SqlExpr mant = SqlExpr.Call.of(SqlFn.REGEXP_EXTRACT, base,
                new SqlExpr.StringLit("-?([0-9]+(?:\\.[0-9]+)?)e"),
                new SqlExpr.IntLit(1));
        SqlExpr digits = SqlExpr.Call.of(SqlFn.REPLACE, mant,
                new SqlExpr.StringLit("."), new SqlExpr.StringLit(""));
        SqlExpr dotPos = SqlExpr.Call.of(SqlFn.STRPOS, mant,
                new SqlExpr.StringLit("."));
        SqlExpr intLen = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL, dotPos, new SqlExpr.IntLit(0)),
                SqlExpr.Call.of(SqlFn.LENGTH, mant))),
                SqlExpr.Call.of(SqlFn.MINUS, dotPos, new SqlExpr.IntLit(1)));
        SqlExpr exp = new SqlExpr.Cast(SqlExpr.Call.of(SqlFn.REGEXP_EXTRACT,
                base, new SqlExpr.StringLit("e([+-]?[0-9]+)$"),
                new SqlExpr.IntLit(1)), SqlType.Scalar.INTEGER);
        SqlExpr pointPos = SqlExpr.Call.of(SqlFn.PLUS, intLen, exp);
        SqlExpr dLen = SqlExpr.Call.of(SqlFn.LENGTH, digits);
        // three shapes by where the point lands
        SqlExpr tiny = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT, new SqlExpr.StringLit("0."),
                        zeros(SqlExpr.Call.of(SqlFn.MINUS,
                                new SqlExpr.IntLit(0), pointPos))),
                digits);
        SqlExpr huge = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT, digits,
                        zeros(SqlExpr.Call.of(SqlFn.MINUS, pointPos, dLen))),
                new SqlExpr.StringLit(".0"));
        SqlExpr mid = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT,
                        SqlExpr.Call.of(SqlFn.SUBSTRING, digits,
                                new SqlExpr.IntLit(1), pointPos),
                        new SqlExpr.StringLit(".")),
                SqlExpr.Call.of(SqlFn.SUBSTRING, digits,
                        SqlExpr.Call.of(SqlFn.PLUS, pointPos,
                                new SqlExpr.IntLit(1))));
        SqlExpr body = new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                        pointPos, new SqlExpr.IntLit(0)), tiny),
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.GREATER_EQUAL,
                        pointPos, dLen), huge)),
                mid);
        return SqlExpr.Call.of(SqlFn.CONCAT, sign, body);
    }

    /** {@code n} zeros (RPAD over empty; negative n yields ''). */
    private static SqlExpr zeros(SqlExpr n) {
        // rpad's length parameter binds INTEGER, not BIGINT
        return SqlExpr.Call.of(SqlFn.RPAD, new SqlExpr.StringLit(""),
                new SqlExpr.Cast(
                        SqlExpr.Call.of(SqlFn.GREATEST, n,
                                new SqlExpr.IntLit(0)),
                        SqlType.Scalar.INTEGER),
                new SqlExpr.StringLit("0"));
    }

    private static SqlExpr has(SqlExpr text, String needle) {
        return SqlExpr.Call.of(SqlFn.GREATER,
                SqlExpr.Call.of(SqlFn.STRPOS, text,
                        new SqlExpr.StringLit(needle)),
                new SqlExpr.IntLit(0));
    }

    /** {@code (\.\d*?)0+$} — trailing zeros after a dot strip; a
     * dotless text is untouched. */
    private static SqlExpr stripTrailingZeros(SqlExpr text) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE, text,
                new SqlExpr.StringLit("(\\.\\d*?)0+$"),
                new SqlExpr.StringLit("\\1"));
    }

    /** A TERMINAL dot rewrites to {@code replacement} ('' = integral
     * bare for Decimal; '.0' keeps one decimal for Float). */
    private static SqlExpr stripDot(SqlExpr text, String replacement) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE, text,
                new SqlExpr.StringLit("\\.$"),
                new SqlExpr.StringLit(replacement));
    }
}
