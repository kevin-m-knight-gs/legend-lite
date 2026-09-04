package com.legend.compiler.element;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CANONICAL value layout of a class — the field list an instance VALUE
 * carries when it lowers to a SQL struct. The layout is the MODEL's, never
 * the instance's: declared stored properties in declaration order, inherited
 * first (superclass walk), with a parameterized receiver's type arguments
 * substituted into generic property types. Two instances of one class always
 * share one layout; an instance that omits an optional property still has the
 * field (NULL-valued).
 *
 * <p>Classes with no stored properties (the body-less native carriers:
 * List, SortInfo, _Window, …) have NO layout — {@link Optional#empty()} —
 * so they keep hitting the loud lowering walls rather than becoming empty
 * structs.
 */
public final class ClassLayouts {

    /** F13 — the ONE owner of the synthetic identity field's spelling:
     * the {@code __id} column an identity layout appends to a keyless
     * class (every producer and consumer compares against this). */
    public static final String SYNTHETIC_ID = "__id";
    /** WORLD_MAP §4 — the synthetic CLASS field beside the identity: a
     * value in a polymorphic slot (a {@code RelationalOperationElement[*]}
     * key carrying DynaFunctions and Literals) is judged by ITS OWN
     * class's keys, and the wire can only carry that class as data.
     * Rides the identity layouts only, like {@link #SYNTHETIC_ID}. */
    public static final String SYNTHETIC_TYPE = "__type";

    private ClassLayouts() {
    }

    /** The layout of a class-typed VALUE, or empty when {@code t} is not a layoutable class. */
    public static Optional<List<Type.Column>> layoutOf(ModelContext ctx, Type t) {
        return layoutOf(ctx, t, false);
    }

    /**
     * F13/F13c — {@code withIdentity} appends the synthetic {@code __id}
     * identity field to a model class's layout: engine {@code eq()} is
     * INSTANCE IDENTITY for every non-primitive (keyed classes
     * included — keys govern {@code equal}, never {@code eq}), and a
     * keyless class's {@code equal} is identity too; the wire can only
     * carry identity as data. Identity layouts ride ONLY the verdict
     * lanes (canon rider + assert-condition sides) — the golden-SQL
     * text lanes and corpus value lanes keep the plain layout, so their
     * pinned texts never see the field. Platform CARRIERS (Pair/List/
     * Map) are excluded: their constructors short-circuit to fixed
     * carrier shapes before the layout, and an appended field would rip
     * the layout from the emitted value (the Executor's attr-count
     * wall). Every producer of an identity layout's struct owns the
     * field: constructor/copy sites MINT (each copy is a NEW instance);
     * store-mapped reads would project NULL (no such producer exists in
     * the identity lanes today — the attr-count wall guards it).
     */
    public static Optional<List<Type.Column>> layoutOf(ModelContext ctx, Type t,
                                                      boolean withIdentity) {
        Optional<List<Type.Column>> base = plainLayoutOf(ctx, t);
        if (!withIdentity || base.isEmpty()) {
            return base;
        }
        String fqn = EqualityKeys.fqnOf(t);
        if (fqn == null || PlatformTypes.isPairCarrier(t)
                || PlatformTypes.isListCarrier(t)
                || PlatformTypes.isMapCarrier(t)) {
            return base;   // platform carriers own their SQL shape
        }
        if (base.get().stream().anyMatch(c -> SYNTHETIC_ID.equals(c.name()))) {
            throw new IllegalStateException("class '" + fqn + "' declares a"
                    + " property named __id — colliding with the synthetic"
                    + " identity field");
        }
        List<Type.Column> out = new ArrayList<>(base.get());
        out.add(new Type.Column(SYNTHETIC_ID, Type.Primitive.STRING,
                new com.legend.compiler.element.type.Multiplicity.Bounded(0, 1)));
        out.add(new Type.Column(SYNTHETIC_TYPE, Type.Primitive.STRING,
                new com.legend.compiler.element.type.Multiplicity.Bounded(0, 1)));
        return Optional.of(List.copyOf(out));
    }

    /** The layout of a constructed value of a class with NO stored
     * properties (toPostgresModel's {@code ^NullLiteral()}, {@code
     * ^SQLNull()}): its only data is its class and, in an identity lane,
     * its identity — the synthetic fields alone (a plain lane carries
     * {@code __type} only; an empty struct is not a SQL value). */
    public static List<Type.Column> syntheticOnlyLayout(boolean withIdentity) {
        var opt = new com.legend.compiler.element.type.Multiplicity.Bounded(0, 1);
        Type.Column type = new Type.Column(SYNTHETIC_TYPE, Type.Primitive.STRING, opt);
        return withIdentity
                ? List.of(new Type.Column(SYNTHETIC_ID, Type.Primitive.STRING, opt), type)
                : List.of(type);
    }

    private static Optional<List<Type.Column>> plainLayoutOf(ModelContext ctx, Type t) {
        return switch (t) {
            case Type.ClassType ct when !PlatformTypes.isVariant(ct) ->
                    ctx.findClass(ct.fqn()).flatMap(c -> layout(ctx, c, Map.of()));
            case Type.GenericType g -> ctx.findClass(g.rawFqn()).flatMap(c -> {
                if (c.typeParameters().size() != g.arguments().size()) {
                    return Optional.empty();   // malformed parameterization — let the caller stay loud
                }
                Map<String, Type> args = new LinkedHashMap<>();
                for (int i = 0; i < c.typeParameters().size(); i++) {
                    args.put(c.typeParameters().get(i), g.arguments().get(i));
                }
                return layout(ctx, c, args);
            });
            default -> Optional.empty();
        };
    }

    private static Optional<List<Type.Column>> layout(ModelContext ctx, TypedClass cls,
                                                      Map<String, Type> typeArgs) {
        LinkedHashMap<String, Type.Column> fields = new LinkedHashMap<>();
        collect(ctx, cls, typeArgs, fields, false);
        return fields.isEmpty() ? Optional.empty()
                : Optional.of(List.copyOf(fields.values()));
    }

    /**
     * Inherited stored properties first (super walk), then locally declared
     * ones. A SUBCLASS redeclaration overrides its inherited field (in the
     * inherited position); two SUPERS declaring the same name with different
     * types is a genuine conflict — LOUD, never first-wins (audit).
     */
    private static void collect(ModelContext ctx, TypedClass cls, Map<String, Type> typeArgs,
                                LinkedHashMap<String, Type.Column> out, boolean isSuper) {
        for (String superFqn : cls.superClassFqns()) {
            ctx.findClass(superFqn).ifPresent(s -> collect(ctx, s, typeArgs, out, true));
        }
        for (Property p : cls.properties()) {
            if (!(p instanceof Property.Stored stored)) {
                continue;
            }
            Type.Column col = new Type.Column(stored.name(),
                    substitute(stored.type(), typeArgs), stored.multiplicity());
            Type.Column prev = out.get(stored.name());
            if (prev != null && isSuper) {
                if (!prev.type().equals(col.type())) {
                    throw new IllegalStateException("class '" + cls.qualifiedName()
                            + "' inherits conflicting declarations of property '"
                            + stored.name() + "' (" + prev.type().typeName() + " vs "
                            + col.type().typeName() + ")");
                }
                // D94 (layout row): a DIAMOND duplicate keeps the FIRST
                // super's declaration — multiplicity included — the same
                // extends-order rule findProperty resolves by, so the
                // layout and the property surface can never disagree
                // (last-super-wins laid a [*] field under a [1] property:
                // a list under an Integer[1] stamp)
                continue;
            }
            out.put(stored.name(), col);   // keeps the first position
        }
    }

    /** Positional generic instantiation: replace type-parameter occurrences with the receiver's arguments. */
    private static Type substitute(Type t, Map<String, Type> typeArgs) {
        return switch (t) {
            case Type.TypeVar v -> typeArgs.getOrDefault(v.name(), t);
            case Type.GenericType g -> new Type.GenericType(g.rawFqn(),
                    g.arguments().stream().map(a -> substitute(a, typeArgs)).toList());
            default -> t;
        };
    }
}
