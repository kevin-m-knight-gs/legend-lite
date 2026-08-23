// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.ClassLayouts;
import com.legend.compiler.element.EqualityKeys;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * F13c — the in-SQL INSTANCE-EQUALITY arm family (identity lane only):
 * ONE owner of the equality relation — the same canonical renders the
 * verdict layer byte-judges with ({@link CanonicalRenderSql}).
 *
 * <ul>
 *   <li>{@code eq(a,b)} is IDENTITY — {@code a.__id = b.__id} — with NO
 *       static classifier fold: a supertype-stamped alias of the same
 *       instance still compares TRUE (ids are unique per construction
 *       site, so cross-class ids can never collide).</li>
 *   <li>{@code equal(a,b)}/{@code ==} compares canonical renders (keyed
 *       classes their key tree, keyless their identity); a static
 *       classifier mismatch folds FALSE (the engine's exact-classifier
 *       rule — the witnessed shapes are static-exact).</li>
 *   <li>{@code contains}/{@code in} is {@code equal()} per element
 *       (contains.pure) — membership over canon texts, never the raw
 *       structs (which carry the identity field in this lane).</li>
 * </ul>
 *
 * <p>Null returns = unclaimed shape (no identity field in the layout,
 * unclaimable key tree) — the caller keeps the generic rule. The claim
 * gates are TYPE-ONLY and never lower.
 */
final class InstanceEquality {

    private InstanceEquality() {
    }

    /** Type-only claim gate over the four owned natives. */
    static boolean claims(TypedNativeCall n) {
        if (n.args().size() != 2) {
            return false;
        }
        String callee = n.callee().qualifiedName();
        return switch (callee) {
            case "meta::pure::functions::boolean::eq",
                    "meta::pure::functions::boolean::equal" ->
                    instanceFqn(n.args().get(0)) != null
                            && instanceFqn(n.args().get(1)) != null;
            case "meta::pure::functions::collection::contains" ->
                    instanceFqn(n.args().get(1)) != null
                            && classInstanceFqn(
                                    n.args().get(0).info().type()) != null;
            case "meta::pure::functions::collection::in" ->
                    instanceFqn(n.args().get(0)) != null
                            && classInstanceFqn(
                                    n.args().get(1).info().type()) != null;
            default -> false;
        };
    }

    /** The claimed lowering, or null for an unclaimable shape. */
    static @com.legend.Nullable SqlExpr lower(TypedNativeCall n,
            Function<String, com.legend.compiler.element
                    .@com.legend.Nullable EqualityKeys> keysOf,
            Function<Type, SqlType> sqlTypeOf,
            Function<TypedSpec, SqlExpr> scalar,
            Supplier<String> freshVar) {
        String callee = n.callee().qualifiedName();
        return switch (callee) {
            case "meta::pure::functions::boolean::eq",
                    "meta::pure::functions::boolean::equal" ->
                    equality(n, "meta::pure::functions::boolean::eq"
                            .equals(callee), keysOf, sqlTypeOf, scalar);
            default -> contains(n,
                    "meta::pure::functions::collection::contains"
                            .equals(callee),
                    keysOf, sqlTypeOf, scalar, freshVar);
        };
    }

    private static @com.legend.Nullable SqlExpr equality(TypedNativeCall n,
            boolean isEq,
            Function<String, com.legend.compiler.element
                    .@com.legend.Nullable EqualityKeys> keysOf,
            Function<Type, SqlType> sqlTypeOf,
            Function<TypedSpec, SqlExpr> scalar) {
        TypedSpec l = n.args().get(0);
        TypedSpec r = n.args().get(1);
        String lf = Objects.requireNonNull(instanceFqn(l));
        String rf = Objects.requireNonNull(instanceFqn(r));
        SqlType lt = sqlTypeOf.apply(l.info().type());
        SqlType rt = sqlTypeOf.apply(r.info().type());
        if (!hasIdentityField(lt) || !hasIdentityField(rt)) {
            return null;
        }
        if (isEq) {
            return SqlExpr.Call.of(SqlFn.EQUAL,
                    new SqlExpr.StructGet(scalar.apply(l),
                            ClassLayouts.SYNTHETIC_ID),
                    new SqlExpr.StructGet(scalar.apply(r),
                            ClassLayouts.SYNTHETIC_ID));
        }
        if (!lf.equals(rf)) {
            return new SqlExpr.BoolLit(false);
        }
        EqualityKeys keys = keysOf.apply(lf);
        SqlExpr ca = CanonicalRenderSql.instanceEqualityCanon(
                scalar.apply(l), keys, lf, lt);
        SqlExpr cb = CanonicalRenderSql.instanceEqualityCanon(
                scalar.apply(r), keys, rf, rt);
        if (ca == null || cb == null) {
            return null;
        }
        return SqlExpr.Call.of(SqlFn.EQUAL,
                new SqlExpr.Cast(ca, SqlType.Scalar.VARCHAR),
                new SqlExpr.Cast(cb, SqlType.Scalar.VARCHAR));
    }

    private static @com.legend.Nullable SqlExpr contains(TypedNativeCall n,
            boolean isContains,
            Function<String, com.legend.compiler.element
                    .@com.legend.Nullable EqualityKeys> keysOf,
            Function<Type, SqlType> sqlTypeOf,
            Function<TypedSpec, SqlExpr> scalar,
            Supplier<String> freshVar) {
        TypedSpec coll = n.args().get(isContains ? 0 : 1);
        TypedSpec needle = n.args().get(isContains ? 1 : 0);
        String cf = Objects.requireNonNull(
                classInstanceFqn(coll.info().type()));
        String nf = Objects.requireNonNull(instanceFqn(needle));
        if (!cf.equals(nf)) {
            // engine equal(): classifiers must match — cross-class
            // containment is statically FALSE
            return new SqlExpr.BoolLit(false);
        }
        SqlType lt = sqlTypeOf.apply(needle.info().type());
        if (!hasIdentityField(lt)) {
            return null;
        }
        EqualityKeys keys = keysOf.apply(cf);
        String elemVar = freshVar.get();
        SqlExpr elemCanon = CanonicalRenderSql.instanceEqualityCanon(
                new SqlExpr.Column(null, elemVar), keys, cf, lt);
        SqlExpr needleCanon = CanonicalRenderSql.instanceEqualityCanon(
                scalar.apply(needle), keys, cf, lt);
        if (elemCanon == null || needleCanon == null) {
            return null;
        }
        SqlExpr collE = PureSql.asList(scalar.apply(coll), isMany(coll));
        SqlExpr mapped = SqlExpr.Call.of(SqlFn.LIST_TRANSFORM, collE,
                new SqlExpr.Lambda(List.of(elemVar),
                        new SqlExpr.Cast(elemCanon, SqlType.Scalar.VARCHAR)));
        return SqlExpr.Call.of(SqlFn.COALESCE,
                new SqlExpr.Membership(
                        new SqlExpr.Cast(needleCanon, SqlType.Scalar.VARCHAR),
                        mapped),
                new SqlExpr.BoolLit(false));
    }

    private static boolean hasIdentityField(SqlType t) {
        return t instanceof SqlType.Struct st && st.fields().stream()
                .anyMatch(f -> ClassLayouts.SYNTHETIC_ID.equals(f.name()));
    }

    private static boolean isMany(TypedSpec s) {
        return s.info().multiplicity() instanceof Multiplicity.Bounded b
                ? b.isMany()
                : true;
    }

    /** The MODEL-class fqn of a [1]-multiplicity instance operand, or
     * null (platform carriers, Any/variant/Nil, collections). */
    private static @com.legend.Nullable String instanceFqn(TypedSpec s) {
        if (!(s.info().multiplicity() instanceof Multiplicity.Bounded b)
                || b.lower() != 1 || b.upper() == null || b.upper() != 1) {
            return null;
        }
        return classInstanceFqn(s.info().type());
    }

    /** The MODEL-class fqn of an instance TYPE (any multiplicity), or
     * null (platform carriers, Any/variant/Nil). */
    private static @com.legend.Nullable String classInstanceFqn(Type t) {
        if (PlatformTypes.isPairCarrier(t) || PlatformTypes.isListCarrier(t)
                || PlatformTypes.isMapCarrier(t)
                || (t instanceof Type.ClassType ct
                        && (PlatformTypes.isVariant(ct)
                                || PlatformTypes.isAny(ct)
                                || PlatformTypes.isNil(ct)))) {
            return null;
        }
        return EqualityKeys.fqnOf(t);
    }
}
