// Copyright 2026 Legend Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.lite.pct.extension;

import org.eclipse.collections.api.map.MutableMap;
import com.legend.compiler.element.type.Type;
import com.legend.exec.ExecutionResult.Scalar;
import com.legend.exec.ExecutionResult.Collection;

import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation.ValueSpecificationBootstrap;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.primitive.date.DateFunctions;
import org.finos.legend.pure.m4.coreinstance.primitive.date.PureDate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE OUTBOUND BIJECTION (the permanent piece of the derived minimum,
 * ADAPTER_NECESSITY_CENSUS §5b): the platform's typed ExecutionResult
 * values become the reference interpreter's CoreInstances — one arm
 * per entry of the platform's CLOSED egress vocabulary, keyed on the
 * VALUE's class, with identity LOOKUPS where the interpreter's
 * equality is object identity (enums, types) and REAL typed instances
 * built from the PLATFORM's own generic returnType (List, Map, class
 * structs). Declared-type reads are ASSERTIONS (loud walls), never
 * choices. This file exists as long as Mode B does; nothing in it is
 * transport-contingent.
 */
final class ValueBridge {

    private final ModelRepository modelRepository;

    ValueBridge(ModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    /** Graph results ARE JSON text by contract — boxed verbatim. */
    CoreInstance graphString(String json, ProcessorSupport ps) {
        return ValueSpecificationBootstrap.newStringLiteral(
                modelRepository, json, ps);
    }
    CoreInstance handleScalar(Scalar result, ProcessorSupport ps) {
        Object value = result.value();
        if (value == null) {
            return ValueSpecificationBootstrap.wrapValueSpecification(
                    org.eclipse.collections.api.factory.Lists.immutable.empty(), true, ps);
        }
        // B2 (truthfulness burn): the PLATFORM's returnType carries the
        // full generic shape (Map<K,V> / List<E> with arguments) — Java
        // builds the typed instances HERE; the pure side only constructs
        // (newMap) or passes through. No shape decision ever consults
        // the test's declared type.
        if (value instanceof java.util.Map<?, ?> map && isMapReturn(result.returnType())) {
            // (JDBC also hands STRUCT values as java.util.Map — the
            // PLATFORM type gates, or ^Person(...) results would land here.)
            return ValueSpecificationBootstrap.wrapValueSpecification(
                    mapInstance(map, result.returnType(), ps), true, ps);
        }
        if (value instanceof java.util.List<?> list && isListReturn(result.returnType())) {
            return ValueSpecificationBootstrap.wrapValueSpecification(
                    listInstance(list, result.returnType(), ps), true, ps);
        }
        CoreInstance ci = toCoreInstance(value, result.returnType(), ps);
        return ValueSpecificationBootstrap.wrapValueSpecification(ci, true, ps);
    }

    CoreInstance handleCollection(Collection result, ProcessorSupport ps) {
        Type elementType = result.returnType();
        var coreInstances = new ArrayList<CoreInstance>();
        for (Object value : result.values()) {
            if (!emptyCell(value)) {
                coreInstances.add(toCoreInstance(value, elementType, ps));
            }
        }
        // CLASS-typed elements (Pair<T,U>): the interpreted cast validates
        // the WRAPPER's genericType — hand it the declared one explicitly
        if (classFqnOf(elementType) != null && !isMapReturn(elementType)) {
            CoreInstance gt = genericTypeOf(elementType, ps);
            if (gt != null) {
                return ValueSpecificationBootstrap.wrapValueSpecification_ResultGenericTypeIsKnown(
                        org.eclipse.collections.impl.factory.Lists.immutable.withAll(coreInstances),
                        gt, true, ps);
            }
        }
        return ValueSpecificationBootstrap.wrapValueSpecification(
                org.eclipse.collections.impl.factory.Lists.immutable.withAll(coreInstances), true, ps);
    }


    // ===== toCoreInstance: single Java → CoreInstance conversion =====

    /**
     * Converts a Java value to a raw Pure CoreInstance.
     * Dispatches on Java type; uses Type for BigDecimal disambiguation
     * and class instance creation.
     */
    /**
     * A JDBC STRUCT (java.util.Map) whose DECLARED type is a CLASS builds a
     * REAL pure instance (the DynamicNew construction pattern) — Pair has
     * equality keys, so reconstructed pairs compare and print like natives.
     * Recursive: nested pair structs rebuild through the generic arguments.
     */
    private CoreInstance structToInstance(java.util.Map<?, ?> struct, Type declared,
                                          ProcessorSupport ps) {
        String fqn = classFqnOf(declared);
        CoreInstance classifier = fqn == null ? null : ps.package_getByUserPath(fqn);
        if (classifier == null) {
            throw new RuntimeException("cannot rebuild struct instance for type " + declared);
        }
        CoreInstance inst = modelRepository.newEphemeralAnonymousCoreInstance(null, classifier);
        // the INTERPRETED cast validates generics — stamp the classifier
        // generic type (the DynamicNew pattern) from the declared engine type
        CoreInstance cgt = genericTypeOf(declared, ps);
        if (cgt != null) {
            Instance.addValueToProperty(inst, M3Properties.classifierGenericType, cgt, ps);
        }
        for (var en : struct.entrySet()) {
            Object v = en.getValue();
            if (emptyCell(v)) {
                continue;   // an absent property, not a value
            }
            String prop = String.valueOf(en.getKey());
            // NESTED class-typed properties resolve their REAL type from
            // the interpreter metamodel — the declared parent type must
            // not leak onto children (CO_Firm.employees are CO_Person,
            // never CO_Firm)
            Type pt = v instanceof java.util.Map || v instanceof java.util.List
                    ? classPropertyTypeOf(declared, prop, ps)
                    : propertyTypeOf(declared, prop);
            if (v instanceof java.util.List<?> lv) {
                // multi-valued property: EVERY element reconstructs
                var cis = new java.util.ArrayList<CoreInstance>();
                for (Object e : lv) {
                    if (!emptyCell(e)) {
                        cis.add(toCoreInstance(e, pt, ps));
                    }
                }
                Instance.setValuesForProperty(inst, prop,
                        org.eclipse.collections.impl.factory.Lists.immutable
                                .withAll(cis), ps);
                continue;
            }
            CoreInstance ci = toCoreInstance(v, pt, ps);
            Instance.setValuesForProperty(inst, prop,
                    org.eclipse.collections.impl.factory.Lists.immutable.with(ci), ps);
        }
        return inst;
    }

    /** The property's return type from the interpreter metamodel when it
     * is a CLASS (nested struct reconstruction); {@code declared}
     * otherwise — scalar handling stays untouched. */
    private static Type classPropertyTypeOf(Type declared, String prop,
            ProcessorSupport ps) {
        Type byGenerics = propertyTypeOf(declared, prop);
        if (byGenerics != declared) {
            return byGenerics;   // Pair generics already carry it
        }
        String fqn = classFqnOf(declared);
        if (fqn == null) {
            return declared;
        }
        CoreInstance cls = ps.package_getByUserPath(fqn);
        CoreInstance p = cls == null ? null
                : ps.class_findPropertyUsingGeneralization(cls, prop);
        CoreInstance gt = p == null ? null
                : Instance.getValueForMetaPropertyToOneResolved(
                        p, M3Properties.genericType, ps);
        CoreInstance raw = gt == null ? null
                : Instance.getValueForMetaPropertyToOneResolved(
                        gt, M3Properties.rawType, ps);
        if (raw != null && ps.instance_instanceOf(raw,
                org.finos.legend.pure.m3.navigation.M3Paths.Class)) {
            String pf = org.finos.legend.pure.m3.navigation.PackageableElement
                    .PackageableElement.getUserPathForPackageableElement(raw);
            if (pf != null) {
                return new Type.ClassType(pf);
            }
        }
        return declared;
    }

    /** A pure GenericType CoreInstance mirroring the declared engine type (recursive). */
    private CoreInstance genericTypeOf(Type t, ProcessorSupport ps) {
        String raw = rawPathOf(t);
        if (raw == null) {
            return null;
        }
        CoreInstance rawType = ps.package_getByUserPath(raw);
        if (rawType == null) {
            return null;
        }
        CoreInstance gtClass = ps.package_getByUserPath("meta::pure::metamodel::type::generics::GenericType");
        CoreInstance gt = modelRepository.newEphemeralAnonymousCoreInstance(null, gtClass);
        Instance.addValueToProperty(gt, "rawType", rawType, ps);
        int declared = 0;
        if (t instanceof Type.GenericType g) {
            for (Type arg : g.arguments()) {
                CoreInstance argGt = genericTypeOf(arg, ps);
                if (argGt == null) {
                    return null;
                }
                Instance.addValueToProperty(gt, "typeArguments", argGt, ps);
                declared++;
            }
        }
        // the ENGINE BRIDGE erases generic args (raw Pair) — pad missing
        // arguments with Any so the harness's cast is a legal downcast
        int params = rawType.getValueForMetaPropertyToMany("typeParameters").size();
        for (int i = declared; i < params; i++) {
            CoreInstance anyGt = modelRepository.newEphemeralAnonymousCoreInstance(null, gtClass);
            Instance.addValueToProperty(anyGt, "rawType",
                    ps.package_getByUserPath("meta::pure::metamodel::type::Any"), ps);
            Instance.addValueToProperty(gt, "typeArguments", anyGt, ps);
        }
        return gt;
    }

    /** The M3 path of a type's raw classifier (primitives at their simple names). */
    private static String rawPathOf(Type t) {
        return switch (t) {
            case Type.GenericType g -> g.rawFqn();
            case Type.ClassType ct -> ct.fqn();
            case Type.Primitive p -> p.typeName();
            default -> null;
        };
    }

    private static String classFqnOf(Type t) {
        return switch (t) {
            case Type.GenericType g -> g.rawFqn();
            case Type.ClassType ct -> ct.fqn();
            default -> null;
        };
    }

    /** Pair's first/second resolve through the generic ARGUMENTS (nesting recurses). */
    private static Type propertyTypeOf(Type declared, String prop) {
        if (declared instanceof Type.GenericType g && g.arguments().size() == 2
                && "meta::pure::functions::collection::Pair".equals(classFqnOf(g))) {
            return "first".equals(prop) ? g.arguments().get(0)
                    : "second".equals(prop) ? g.arguments().get(1) : declared;
        }
        return declared;
    }

    /** The declared return is the Map<U,V> carrier (never a class STRUCT). */
    private static boolean isMapReturn(Type t) {
        return "meta::pure::functions::collection::Map".equals(classFqnOf(t));
    }

    private static boolean isListReturn(Type t) {
        return "meta::pure::functions::collection::List".equals(classFqnOf(t));
    }

    /** B2 — a REAL {@code ^List<E>} instance built from the PLATFORM's
     * own {@code List<E>} type: classifier + generic stamp + values.
     * The harness's declared cast validates the stamp downstream. */
    private CoreInstance listInstance(java.util.List<?> list, Type declared,
            ProcessorSupport ps) {
        CoreInstance listCls = ps.package_getByUserPath(
                "meta::pure::functions::collection::List");
        CoreInstance inst = modelRepository
                .newEphemeralAnonymousCoreInstance(null, listCls);
        CoreInstance gt = genericTypeOf(declared, ps);
        if (gt != null) {
            Instance.addValueToProperty(inst,
                    M3Properties.classifierGenericType, gt, ps);
        }
        Type elem = declared instanceof Type.GenericType g
                && g.arguments().size() == 1 ? g.arguments().get(0) : declared;
        var cis = new ArrayList<CoreInstance>();
        for (Object v : list) {
            if (!emptyCell(v)) {
                cis.add(toCoreInstance(v, elem, ps));
            }
        }
        Instance.setValuesForProperty(inst, "values",
                org.eclipse.collections.impl.factory.Lists.immutable
                        .withAll(cis), ps);
        return inst;
    }

    /** B2 — a REAL interpreter Map instance ({@code MapCoreInstance},
     * the same class the interpreted {@code newMap} native constructs),
     * stamped with the PLATFORM's own {@code Map<K,V>} generic type and
     * filled directly. The pure side passes it through untouched — no
     * marker class, no rebuild, no declared-type consult anywhere. */
    private CoreInstance mapInstance(java.util.Map<?, ?> map, Type declared,
            ProcessorSupport ps) {
        Type k = declared instanceof Type.GenericType g
                && g.arguments().size() == 2 ? g.arguments().get(0) : null;
        Type v = declared instanceof Type.GenericType g
                && g.arguments().size() == 2 ? g.arguments().get(1) : null;
        CoreInstance mapRawType = ps.package_getByUserPath(
                org.finos.legend.pure.m3.navigation.M3Paths.Map);
        var inst = new org.finos.legend.pure.runtime.java.interpreted
                .natives.MapCoreInstance(
                        org.eclipse.collections.impl.factory.Lists
                                .immutable.empty(),
                        "", null, mapRawType, -1, modelRepository, false, ps);
        CoreInstance gt = genericTypeOf(declared, ps);
        if (gt != null) {
            Instance.addValueToProperty(inst,
                    M3Properties.classifierGenericType, gt, ps);
        }
        var internal = inst.getMap();
        for (var en : map.entrySet()) {
            internal.put(
                    toCoreInstance(en.getKey(),
                            k != null ? k : declared, ps),
                    toCoreInstance(en.getValue(),
                            v != null ? v : declared, ps));
        }
        return inst;
    }


    /** THE channel-scoped empty rule — ONE owner (documented-debts
     * 2026-08-18; the audit counted six scattered unconditional drops
     * that had only been re-commented). A NULL element is a pure EMPTY:
     * pure collections and properties hold no empties, so SQL NULL and
     * JSON-null decay both vanish here — the same convention as the
     * Executor's COLLECTION shaping. The COUNTED case is not at risk:
     * a top-level Collection result reaching this bridge has ALREADY
     * passed the Executor's declared-lower-bound wall, and the
     * per-element sites (list elements, struct properties) have no
     * declared bound to violate; a defect manufacturing spurious NULLs
     * surfaces in the reference asserts, which compare VALUES. */
    private static boolean emptyCell(Object v) {
        return v == null;
    }

    private CoreInstance toCoreInstance(Object value, Type type, ProcessorSupport ps) {
        if (value instanceof java.util.Map<?, ?> struct && classFqnOf(type) != null
                && !isMapReturn(type)) {
            return structToInstance(struct, type, ps);
        }

        if (value instanceof Boolean b) {
            return modelRepository.newBooleanCoreInstance(b);
        }
        if (value instanceof Integer i) {
            return modelRepository.newIntegerCoreInstance(i);
        }
        if (value instanceof Long l) {
            return modelRepository.newIntegerCoreInstance(l);
        }
        if (value instanceof BigInteger bi) {
            return modelRepository.newIntegerCoreInstance(bi.toString());
        }
        // Numeric kinds ride the WIRE's own carriers (slice-4 census,
        // measured on both PCT lanes): a Decimal-contract value arrives
        // as BigDecimal (DECIMAL column), a Float as Double. The old
        // declared-kind consult arms on Double, the Float32 arm and the
        // Number catch-all all measured ZERO — deleted; an unlisted
        // numeric carrier now hits the terminal wall, loudly (F5.5).
        if (value instanceof BigDecimal bd) {
            if (type instanceof Type.Primitive p
                    && (p == Type.Primitive.DECIMAL || p == Type.Primitive.NUMBER)) {
                return modelRepository.newDecimalCoreInstance(bd);
            }
            if (type instanceof Type.PrecisionDecimal) {
                // scale is part of the VALUE surface: abs(-3.0D) prints 3.0D
                return modelRepository.newDecimalCoreInstance(bd);
            }
            throw new UnsupportedOperationException(
                    "DECIMAL wire value under a non-Decimal contract: "
                    + type + " — the census measured this arm zero"
                    + " (Float contracts arrive as DOUBLE)");
        }
        if (value instanceof Double d) {
            return modelRepository.newFloatCoreInstance(BigDecimal.valueOf(d));
        }
        // Dates — THE wire temporal (D-arc 2026-08-21): PureDateLiteral
        // carries written precision; engine's own parser reconstructs
        // the precision-exact PureDate class from the spelling. The
        // old java.sql/java.time arms are DELETED (cut-over-hard): a
        // raw driver temporal reaching this extension is a fetch-seam
        // leak and hits the terminal no-typed-conversion wall, loudly.
        if (value instanceof com.legend.values.PureDateLiteral pdl) {
            // J8a (slice-4 census): the declared-type NARROWING arm is
            // DELETED — its one witness (parseDate over a bare-date
            // literal, refined StrictDate but cast to TIMESTAMP) is
            // cured at the emission (Scalars parseDate rule casts to
            // DATE when the node's own stamp is StrictDate). The wire
            // delivers kind-faithful temporals; the adapter only boxes.
            PureDate pd = DateFunctions.parsePureDate(pdl.toEngineString());
            String topLevel = switch (pdl.precision()) {
                case YEAR, MONTH -> "Date";
                case DAY -> "StrictDate";
                default -> "DateTime";
            };
            return modelRepository.newCoreInstance(pd.toString(),
                    modelRepository.getTopLevel(topLevel), null);
        }
        // (LocalTime/StrictTime arm DELETED by census — zero traffic;
        // StrictTime has no SQL carrier (PureSql), so nothing crosses.
        // A LocalTime reaching here hits the terminal wall by name.)
        // Strings
        if (value instanceof String s) {
            // type(x) crosses the wire as the type's NAME; resolve it to the
            // canonical Type instance — assertIs compares IDENTITY, and
            // package lookup returns the one true instance.
            if ("meta::pure::metamodel::type::Type".equals(classFqnOf(type))) {
                CoreInstance typeInstance = ps.package_getByUserPath(s);
                if (typeInstance != null) {
                    return typeInstance;
                }
            }
            // Enum values cross the wire as their NAME; the declared type
            // carries the enumeration — resolve to the CANONICAL enum-value
            // instance (equality on enums is identity in interpreted pure).
            if (type instanceof Type.EnumType et) {
                CoreInstance enumeration = ps.package_getByUserPath(et.fqn());
                if (enumeration != null) {
                    for (CoreInstance v : Instance.getValueForMetaPropertyToManyResolved(
                            enumeration, M3Properties.values, ps)) {
                        if (s.equals(v.getName())) {
                            return v;
                        }
                    }
                }
            }
            // J8h (slice-4 census): the date-shaped-text reparse arm is
            // DELETED BY MEASUREMENT — zero firings on both PCT lanes;
            // temporals cross as PureDateLiteral (D-arc), never as bare
            // date text. Any temporal-typed String is now the F5.5 wall.
            if (type instanceof Type.Primitive tp && tp.isTemporal()) {
                throw new UnsupportedOperationException(
                        "temporal-typed value crossed as text: '"
                        + s + "' (" + tp + ") — the PureDateLiteral"
                        + " carrier owns temporals");
            }
            return modelRepository.newStringCoreInstance(s);
        }
        // Struct → class instance
        if (value instanceof Map<?, ?> map) {
            // F5.6: the old createClassInstance FABRICATED a Pair type
            // for any unknown struct; a probe throw across all 1,109 PCT
            // tests proved the branch unreachable — keep the wall LOUD
            throw new UnsupportedOperationException(
                    "struct result reached the PCT bridge (type=" + type
                    + ", keys=" + map.keySet() + ") — no fabrication;"
                    + " add a typed conversion");
        }
        // a List VALUE under a List<E> type builds the real instance
        // (B2 — nested list results recurse through the typed builder)
        if (value instanceof List<?> nested && isListReturn(type)) {
            return listInstance(nested, type, ps);
        }
        // List (struct arrays unwrapped by Row.java, e.g. zip → List<Pair>)
        if (value instanceof List<?> list) {
            Type elemType = type;
            var coreInstances = new ArrayList<CoreInstance>();
            for (Object elem : list) {
                if (!emptyCell(elem)) {
                    coreInstances.add(toCoreInstance(elem, elemType, ps));
                }
            }
            // Return as a single-element wrapping — the collection will be wrapped by caller
            if (coreInstances.size() == 1) return coreInstances.get(0);
            // F5.5: multi-element used to 'return first as fallback' and
            // empty fabricated a '[]' STRING — both are silent wrong
            // answers in scalar context
            throw new UnsupportedOperationException(
                    "list of " + coreInstances.size() + " elements in"
                    + " scalar conversion context (type=" + type + ")");
        }
        // J8j/J8k (slice-4 census): the UUID-under-String arm and the
        // DuckDBStruct arm (with its classInstance builder — the second
        // struct→instance owner, whose positional type-arg indexing was
        // a latent wrong-type channel) are DELETED BY MEASUREMENT —
        // zero firings on both PCT lanes; structs cross as
        // java.util.Map and build through structToInstance, the ONE
        // owner. F5.5: the terminal fallback is LOUD — an unconverted
        // kind is a missing typed conversion, not a String.
        throw new UnsupportedOperationException(
                "no typed conversion for " + value.getClass().getName()
                + " (type=" + type + ")");
    }



    // ===== TDS formatting =====

    /**
     * Formats a TabularResult as a TDS string for stringToTDS().
     * Column types come from the compiler schema (already Pure type names).
     */

    /** F5.1: the column's PURE type names the header (the SQL-type-name
     * sniff is gone). F5.3 Stage B: temporals and Variant spell their
     * REAL pure names — the old "interpreted TestTDS cannot build Date
     * columns" claim was stale (the overlay had been writing Date
     * headers green for months); Variant needs its FQN because the 5.88
     * TDS header parser resolves type names without import scanning.
     * Variant cells still travel as quoted JSON text. */


    CoreInstance createTDSResult(String tdsString, ProcessorSupport ps) {
        CoreInstance tdsResultClass = ps.package_getByUserPath("meta::legend::lite::pct::TDSResult");
        if (tdsResultClass == null) {
            throw new RuntimeException("TDSResult class not found in Pure model");
        }
        CoreInstance instance = modelRepository.newCoreInstance("TDSResult", tdsResultClass, null);
        Instance.addValueToProperty(instance, "tdsString",
                modelRepository.newStringCoreInstance(tdsString), ps);
        return ValueSpecificationBootstrap.wrapValueSpecification(instance, true, ps);
    }

}
