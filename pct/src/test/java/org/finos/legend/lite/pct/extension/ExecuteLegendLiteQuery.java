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

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.stack.MutableStack;
import com.legend.compiler.element.type.Type;
import com.legend.exec.Column;
import com.legend.exec.ExecutionResult;
import com.legend.exec.ExecutionResult.Scalar;
import com.legend.exec.ExecutionResult.Collection;
import com.legend.exec.ExecutionResult.Tabular;
import com.legend.exec.ExecutionResult.Graph;
import com.legend.server.QueryService;

import org.finos.legend.pure.m3.compiler.Context;
import org.finos.legend.pure.m3.exception.PureExecutionException;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.PrimitiveUtilities;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation.ValueSpecificationBootstrap;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.primitive.date.DateFunctions;
import org.finos.legend.pure.m4.coreinstance.primitive.date.PureDate;
import org.finos.legend.pure.runtime.java.interpreted.ExecutionSupport;
import org.finos.legend.pure.runtime.java.interpreted.FunctionExecutionInterpreted;
import org.finos.legend.pure.runtime.java.interpreted.VariableContext;
import org.finos.legend.pure.runtime.java.interpreted.natives.InstantiationContext;
import org.finos.legend.pure.runtime.java.interpreted.natives.NativeFunction;
import org.finos.legend.pure.runtime.java.interpreted.profiler.Profiler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * Native function that bridges PCT tests to Legend-Lite's QueryService.
 *
 * Pure expressions are executed via QueryService (compile → SQL → DuckDB),
 * and the typed ExecutionResult is converted back to Pure CoreInstances.
 *
 * Type information flows from Type on ExecutionResult: column names,
 * pure types, and multiplicities are the PLATFORM's typed facts (F5.1
 * replaced the sqlType-name sniff; F5.3 Stage B deleted the
 * declared-header overlay and the null-scan — PCT sees the wire).
 */
public class ExecuteLegendLiteQuery extends NativeFunction {

    // (The PURE_MODEL scaffold — a fixed Doy model/mapping/connection/
    // runtime — is DELETED, truthfulness burn B1: PCT expressions are
    // STORELESS, and the platform executes them against a bare
    // connection with no model and no runtime; the dialect derives
    // from the CONNECTION's own product metadata (Compiler.dialectOf's
    // connection seam), which the scaffold's `type: H2;` flip was
    // shadowing. Probed on both backends before the cut.)

    // (The five discovery regexes — INSTANCE_CLASS/TYPE_REF/ENUM_REF/
    // PARAM_TYPE/BARE_REF/FQN_TOKEN — are DELETED: R1 differential,
    // 2026-08-27. Discovery is the pure-side collectRoots M3 walk.)

    private final ModelRepository modelRepository;
    private final FunctionExecutionInterpreted functionExecution;

    public ExecuteLegendLiteQuery(FunctionExecutionInterpreted functionExecution, ModelRepository modelRepository) {
        this.modelRepository = modelRepository;
        this.functionExecution = functionExecution;
    }

    // ===== execute =====

    @Override
    public CoreInstance execute(
            ListIterable<? extends CoreInstance> params,
            Stack<MutableMap<String, CoreInstance>> resolvedTypeParameters,
            Stack<MutableMap<String, CoreInstance>> resolvedMultiplicityParameters,
            VariableContext variableContext,
            MutableStack<CoreInstance> functionExpressionCallStack,
            Profiler profiler,
            InstantiationContext instantiationContext,
            ExecutionSupport executionSupport,
            Context context,
            ProcessorSupport processorSupport) throws PureExecutionException {

        // J2 (slice-4 census): reEscapeStringLiterals is DELETED BY
        // MEASUREMENT — zero input-changing calls across the full DuckDB
        // lane (1115) and the h2 Relation lane. The serializer hands the
        // expression over parse-ready.
        String pureExpression = PrimitiveUtilities.getStringValue(
                Instance.getValueForMetaPropertyToOneResolved(params.get(0), M3Properties.values, processorSupport));

        // R1 (census §5b): the SEMANTIC dependency roots — element paths
        // the pure-side collectRoots walk read off the M3 tree. The
        // differential below judges the regex discovery against them.
        java.util.List<String> semanticRoots = new ArrayList<>();
        for (CoreInstance v : Instance.getValueForMetaPropertyToManyResolved(
                params.get(1), M3Properties.values, processorSupport)) {
            semanticRoots.add(PrimitiveUtilities.getStringValue(v));
        }

        System.out.println("[LegendLite PCT] Executing: " + pureExpression);

        // LEGENDLITE_PCT_BACKEND=h2 runs the SAME suite on the H2
        // execution dialect (env, not -D: it must survive the surefire
        // fork) — the session mirrors the portability sweep's settings.
        boolean h2 = "h2".equalsIgnoreCase(
                String.valueOf(System.getenv("LEGENDLITE_PCT_BACKEND")));
        try (Connection connection = DriverManager.getConnection(h2
                ? "jdbc:h2:mem:" + com.legend.exec.H2Settings.SETTINGS
                : "jdbc:duckdb:", h2 ? "sa" : null, h2 ? "" : null)) {
            // DuckDB pins the session to UTC (its driver's Timestamps are
            // wall-preserving under it). H2 must NOT: its driver funnels
            // zone-less TIMESTAMPs through the SESSION zone, so a UTC
            // session + local JVM shifted every wall time by the offset
            // (witnessed: 2026-01-07T00:00 read back as 01-06T19:00);
            // the JVM-local default round-trips wall times exactly, the
            // same contract the corpus sweep runs under.
            if (!h2) {
                try (var tzStmt = connection.createStatement()) {
                    tzStmt.execute("SET TimeZone='UTC'");
                }
            }

            // R1 (census §5b + §6): the model injection builds from the
            // SEMANTIC roots the pure-side collectRoots walk supplied —
            // discovery reads the M3 tree the interpreter holds. The
            // five discovery regexes are DELETED BY DIFFERENTIAL
            // (2026-08-27): one full-lane run built the injection both
            // ways — the walk found every element the regexes found,
            // and the only regex-only rows were the ^Pair(...) sites
            // WRONGLY injecting a shadow copy of the platform's native
            // Pair (the walk's native-class filter refuses it; all four
            // tests pass on the real Pair).
            java.util.TreeMap<String, String> injection =
                    injectionFromRoots(semanticRoots, processorSupport);
            StringBuilder defs = new StringBuilder();
            // grouped enums → classes → functions (the E:/C:/F: keys)
            for (String prefix : List.of("E:", "C:", "F:")) {
                for (var en : injection.entrySet()) {
                    if (en.getKey().startsWith(prefix)) {
                        defs.append(en.getValue()).append("\n");
                    }
                }
            }
            if (defs.length() > 0) {
                System.out.println("[LegendLite PCT] Injected model:\n" + defs);
            }
            String model = defs.toString();

            // E1 (JAVA_EVICTION_PLAN): relation-rooted queries render
            // their PCT wire text IN THE PLAN (Lowerer PCT-TDS root
            // mode) — the adapter receives one Scalar String and hands
            // it over verbatim; formatAsTds/formatValue are gone.
            ExecutionResult result;
            boolean tdsRendered;
            try (AutoCloseable ignored2 =
                    com.legend.exec.PctRenderOption.enable()) {
                result = new QueryService().execute(model, pureExpression,
                        null, connection);
                tdsRendered = com.legend.exec.PctRenderOption.wasRendered();
            }
            if (tdsRendered) {
                String tdsString = String.valueOf(((Scalar) result).value());
                System.out.println("[LegendLite PCT] TDS: "
                        + tdsString.replace("\n", "\\n"));
                return createTDSResult(tdsString, processorSupport);
            }

            return switch (result) {
                case Scalar s -> handleScalar(s, processorSupport);
                case Collection c -> handleCollection(c, processorSupport);
                case Tabular t -> throw new IllegalStateException(
                        "PCT tabular result outside the render mode — the"
                        + " root-mode wrap missed a relation root");
                case Graph g -> ValueSpecificationBootstrap.newStringLiteral(
                        modelRepository, g.json(), processorSupport);
            };
        } catch (Exception e) {
            // the error's SOURCE INFO must point at the TEST's own call site
            // (assertError checks line/column) — walk past adapter frames
            org.finos.legend.pure.m4.coreinstance.SourceInformation src =
                    functionExpressionCallStack.peek().getSourceInformation();
            for (var frame : functionExpressionCallStack) {
                var fs = frame.getSourceInformation();
                if (fs != null && fs.getSourceId() != null
                        && !fs.getSourceId().contains("core_legend_lite_pct")) {
                    src = fs;
                    break;
                }
            }
            throw new PureExecutionException(src, remapErrorMessage(e.getMessage()), e);
        }
    }

    private CoreInstance handleScalar(Scalar result, ProcessorSupport ps) {
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

    private CoreInstance handleCollection(Collection result, ProcessorSupport ps) {
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


    private CoreInstance createTDSResult(String tdsString, ProcessorSupport ps) {
        CoreInstance tdsResultClass = ps.package_getByUserPath("meta::legend::lite::pct::TDSResult");
        if (tdsResultClass == null) {
            throw new RuntimeException("TDSResult class not found in Pure model");
        }
        CoreInstance instance = modelRepository.newCoreInstance("TDSResult", tdsResultClass, null);
        Instance.addValueToProperty(instance, "tdsString",
                modelRepository.newStringCoreInstance(tdsString), ps);
        return ValueSpecificationBootstrap.wrapValueSpecification(instance, true, ps);
    }


    // ===== Class metadata extraction =====

    /**
     * Extracts class definitions from the Pure interpreter for ^className() patterns
     * in the expression. Returns a map of qualified name → PureClass.
     */
    /**
     * TEST-MODEL ENUM definitions referenced by the expression (My::Enum.VALUE
     * or @My::Enum) — platform enums are registered natively in core and
     * skipped; unknown FQNs resolve against the interpreter's graph.
     */
    /** F5.7: CONCRETE test-support functions the expression references
     *  extract from the interpreter's OWN source registry (the five
     *  verbatim copies were an unversioned fork of engine test source —
     *  if upstream changed a body we silently kept testing the old one).
     *  Definition text is sliced by the function's source span;
     *  stereotype/tagged-value decorations ({@code <<...>>}, {@code
     *  {doc...}}) strip — they are PCT-harness metadata, not semantics,
     *  and their profiles are not part of the lite compile. */
    /** Slices every ConcreteFunctionDefinition matching {@code path}
     * (a {@code pkg::bareName} or a graph-mangled id) from the
     * interpreter's source registry into {@code out}, keyed
     * {@code pkg::bareName} — the mangling-free identity both discovery
     * pipelines share (F5.7's registry slice; stereotype/doc decorations
     * strip, they are PCT-harness metadata). */
    private void sliceFunctionsNamed(String path,
            java.util.Map<String, String> out, ProcessorSupport ps) {
        // a graph-mangled id (pkg::name_A_1__B_1_) resolves DIRECTLY;
        // a bare name scans the package's children by functionName
        CoreInstance direct = ps.package_getByUserPath(path);
        if (direct != null && Instance.instanceOf(direct,
                "meta::pure::metamodel::function::ConcreteFunctionDefinition", ps)) {
            sliceFunction(direct, out, ps);
            return;
        }
        int cut = path.lastIndexOf("::");
        if (cut < 0) {
            return;
        }
        CoreInstance pkg = ps.package_getByUserPath(path.substring(0, cut));
        if (pkg == null) {
            return;
        }
        String name = path.substring(cut + 2);
        for (CoreInstance child : Instance.getValueForMetaPropertyToManyResolved(
                pkg, M3Properties.children, ps)) {
            if (!Instance.instanceOf(child,
                    "meta::pure::metamodel::function::ConcreteFunctionDefinition", ps)) {
                continue;
            }
            CoreInstance fn = child.getValueForMetaPropertyToOne(
                    M3Properties.functionName);
            if (fn != null && name.equals(fn.getName())) {
                sliceFunction(child, out, ps);
            }
        }
    }

    private void sliceFunction(CoreInstance fnDef,
            java.util.Map<String, String> out, ProcessorSupport ps) {
        var si = fnDef.getSourceInformation();
        if (si == null) {
            return;
        }
        var src = functionExecution.getRuntime().getSourceById(si.getSourceId());
        if (src == null) {
            return;
        }
        // key = pkg::<graph-mangled id> — unique per OVERLOAD (a bare
        // functionName key would collapse overload siblings)
        String pkgPath = getQualifiedName(
                fnDef.getValueForMetaPropertyToOne("package"));
        String key = (pkgPath != null ? pkgPath : "") + "::" + fnDef.getName();
        if (out.containsKey(key)) {
            return;
        }
        String[] lines = src.getContent().split("\n", -1);
        StringBuilder def = new StringBuilder();
        for (int ln = si.getStartLine(); ln <= si.getEndLine()
                && ln <= lines.length; ln++) {
            def.append(lines[ln - 1]).append('\n');
        }
        out.put(key, def.toString()
                .replaceAll("<<[^>]*>>", "")
                .replaceAll("\\{doc[^}]*\\}", ""));
    }

    /** Renders {@code Enum fqn { A, B }} into {@code out} when the fqn
     * is a real, non-native, non-metamodel Enumeration. */
    private static void renderEnumDef(String fqn,
            java.util.Map<String, String> out, ProcessorSupport ps) {
        if (fqn.startsWith("meta::pure::metamodel") || out.containsKey(fqn)) {
            return;
        }
        CoreInstance enumCls = ps.package_getByUserPath(fqn);
        if (enumCls == null
                || !Instance.instanceOf(enumCls, "meta::pure::metamodel::type::Enumeration", ps)
                || com.legend.builtin.Pure.findNativeEnum(fqn).isPresent()) {
            return;
        }
        StringBuilder def = new StringBuilder("Enum ").append(fqn).append(" { ");
        boolean first = true;
        for (CoreInstance v : Instance.getValueForMetaPropertyToManyResolved(
                enumCls, M3Properties.values, ps)) {
            if (!first) {
                def.append(", ");
            }
            def.append(v.getName());
            first = false;
        }
        def.append(" }");
        out.put(fqn, def.toString());
    }

    // ===== R1 — the canonical injection map (differential seam) =====

    /** One comparable identity for an injection, whichever discovery
     * produced it: {@code E:fqn} / {@code C:fqn} / {@code F:pkg::id}
     * → definition text. */
    private static java.util.TreeMap<String, String> canonicalInjection(
            java.util.Map<String, String> enums,
            java.util.Map<String, String> classes,
            java.util.Map<String, String> fns) {
        var out = new java.util.TreeMap<String, String>();
        enums.forEach((k, v) -> out.put("E:" + k, v));
        classes.forEach((k, v) -> out.put("C:" + k, v));
        fns.forEach((k, v) -> out.put("F:" + k, v));
        return out;
    }

    /** The injection built from the SEMANTIC roots the pure-side
     * collectRoots walk supplied — no text pattern-matching anywhere:
     * each root resolves in the M3 graph and dispatches on what it IS;
     * classes expand through the same M3-recursive extraction. */
    private java.util.TreeMap<String, String> injectionFromRoots(
            java.util.List<String> roots, ProcessorSupport ps) {
        Map<String, String> classes = new HashMap<>();
        Set<String> visited = new HashSet<>();
        java.util.Set<String> enums = new java.util.LinkedHashSet<>();
        var enumDefs = new java.util.LinkedHashMap<String, String>();
        var fnDefs = new java.util.LinkedHashMap<String, String>();
        for (String fqn : roots) {
            if (fqn.startsWith("meta::pure::metamodel")
                    || fqn.startsWith("meta::pure::precisePrimitives")) {
                continue;
            }
            CoreInstance el = ps.package_getByUserPath(fqn);
            if (el != null && Instance.instanceOf(el,
                    "meta::pure::metamodel::type::Enumeration", ps)) {
                enums.add(fqn);
            } else if (el != null && Instance.instanceOf(el,
                    "meta::pure::metamodel::type::Class", ps)) {
                if (com.legend.builtin.Pure.findNativeClass(fqn).isEmpty()) {
                    extractClassRecursive(fqn, classes, visited, enums, ps);
                }
            } else if (fqn.contains("::tests::")) {
                sliceFunctionsNamed(fqn, fnDefs, ps);
            }
        }
        for (String fqn : enums) {
            renderEnumDef(fqn, enumDefs, ps);
        }
        return canonicalInjection(enumDefs, classes, fnDefs);
    }

    // (extractClassMetadata — the regex-driven root discovery — is
    // DELETED: R1 differential 2026-08-27. injectionFromRoots consumes
    // the pure walk's roots; extractClassRecursive below remains the
    // M3-recursive expansion it always was.)

    private void extractClassRecursive(String className, Map<String, String> classes,
                                       Set<String> visited, java.util.Set<String> discoveredEnums,
                                       ProcessorSupport ps) {
        if (visited.contains(className)) return;
        visited.add(className);

        CoreInstance cls = ps.package_getByUserPath(className);
        if (cls == null) return;
        // An ENUMERATION is a class in M3 (it has a 'name' property) but must
        // inject as an Enum — injecting both a Class and an Enum under one
        // FQN split the type in two ("expected X, got X" with identical names).
        if (Instance.instanceOf(cls, "meta::pure::metamodel::type::Enumeration", ps)) {
            discoveredEnums.add(className);
            return;
        }

        List<String> propertyLines = new ArrayList<>();
        for (CoreInstance prop : ps.class_getSimpleProperties(cls)) {
            CoreInstance nameInstance = prop.getValueForMetaPropertyToOne(M3Properties.name);
            if (nameInstance == null) continue;
            String propName = PrimitiveUtilities.getStringValue(nameInstance);
            if (propName == null) continue;

            CoreInstance mult = prop.getValueForMetaPropertyToOne(M3Properties.multiplicity);
            if (mult == null) continue;
            int upper = org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity
                    .multiplicityUpperBoundToInt(mult);
            int lower = org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity
                    .multiplicityLowerBoundToInt(mult);
            String multiplicity = multText(lower, upper < 0 ? null : upper);

            CoreInstance genericType = prop.getValueForMetaPropertyToOne(M3Properties.genericType);
            CoreInstance rawType = (genericType != null)
                    ? genericType.getValueForMetaPropertyToOne(M3Properties.rawType) : null;
            if (rawType != null) {
                rawType = org.finos.legend.pure.m3.navigation.importstub.ImportStub
                        .withImportStubByPass(rawType, ps);
            }
            // Resolve the property type via FQN. Primitives print as their
            // Pure simple names ("Integer"); classes and enums print as FQNs
            // (the engine PureClass.toString convention, preserved verbatim).
            // rawType==null means "no declared type" → Any.
            String typeRef;
            if (rawType == null) {
                typeRef = "Any";
            } else {
                String qualifiedTypeName = getQualifiedName(rawType);
                String primitive = qualifiedTypeName != null
                        ? primitivePureName(qualifiedTypeName) : null;
                if (primitive != null) {
                    typeRef = primitive;
                } else if (qualifiedTypeName == null
                        || qualifiedTypeName.startsWith("meta::pure::metamodel")) {
                    continue;
                } else if (Instance.instanceOf(rawType,
                        "meta::pure::metamodel::type::Enumeration", ps)) {
                    // enum-typed property: reference by name; the DEFINITION
                    // is injected as an Enum, never as a shadow Class
                    discoveredEnums.add(qualifiedTypeName);
                    typeRef = qualifiedTypeName;
                } else {
                    extractClassRecursive(qualifiedTypeName, classes, visited, discoveredEnums, ps);
                    if (!classes.containsKey(qualifiedTypeName)) continue;
                    typeRef = qualifiedTypeName;
                }
            }
            propertyLines.add(propName + ": " + typeRef + multiplicity);
        }

        // SUPERCLASSES ride along (concatenate's LUB is the common
        // supertype; without the extends chain everything collapses to Any)
        List<String> superFqns = new ArrayList<>();
        for (CoreInstance gen : Instance.getValueForMetaPropertyToManyResolved(
                cls, M3Properties.generalizations, ps)) {
            CoreInstance general = gen.getValueForMetaPropertyToOne(M3Properties.general);
            CoreInstance raw = general == null ? null
                    : general.getValueForMetaPropertyToOne(M3Properties.rawType);
            if (raw != null) {
                raw = org.finos.legend.pure.m3.navigation.importstub.ImportStub
                        .withImportStubByPass(raw, ps);
            }
            String superFqn = raw == null ? null : getQualifiedName(raw);
            if (superFqn != null && !superFqn.startsWith("meta::pure::metamodel")) {
                extractClassRecursive(superFqn, classes, visited, discoveredEnums, ps);
                if (classes.containsKey(superFqn)) {
                    superFqns.add(superFqn);
                }
            }
        }

        String qualifiedName = getQualifiedName(cls);
        String fqn = qualifiedName != null ? qualifiedName : className;
        var sb = new StringBuilder("Class ").append(fqn);
        if (!superFqns.isEmpty()) {
            sb.append(" extends ").append(String.join(", ", superFqns));
        }
        sb.append(" {\n");
        for (String line : propertyLines) {
            sb.append("    ").append(line).append(";\n");
        }
        sb.append("}");
        classes.put(fqn, sb.toString());
    }

    /** The Pure multiplicity print form: [1], [0..1], [*], [2..*]. */
    private static String multText(int lower, Integer upper) {
        if (upper == null) {
            return lower == 0 ? "[*]" : "[" + lower + "..*]";
        }
        if (lower == upper) {
            return "[" + upper + "]";
        }
        return "[" + lower + ".." + upper + "]";
    }

    /** The Pure simple name of a primitive FQN, or null if not a primitive. */
    private static String primitivePureName(String fqn) {
        for (var p : com.legend.compiler.element.type.Type.Primitive.values()) {
            if (p.qualifiedName().equals(fqn)) {
                return p.typeName();
            }
        }
        return null;
    }

    private String getQualifiedName(CoreInstance element) {
        try {
            return org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement
                    .getUserPathForPackageableElement(element);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Utilities =====

    private static String remapErrorMessage(String message) {
        if (message == null) return null;
        // The shift-message remap is DELETED (deep-audit H4: it returned
        // the PCT expectation verbatim for ANY shift error, so the tests
        // could never detect the real boundary). The 62-bit bound now
        // guards AT RUNTIME in the lowering (Scalars bit-shift rule), so
        // the database raises pure's own message.
        //
        // The prefix strip covers DuckDB's transport prefixes on raised
        // and native errors. KNOWN WEAKNESS (deep-audit H4 second half,
        // kept deliberately: PCT interval tests depend on the native
        // Out-of-Range text surfacing bare): the strip erases the error
        // CLASS, so class-confusions can compare equal.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:Invalid Input Error|Out of Range Error|Conversion Error): (.*)$",
                        java.util.regex.Pattern.DOTALL)
                .matcher(message);
        if (m.matches()) {
            // slice-4 census J6: LOAD-BEARING — 18 firings on the full
            // DuckDB lane (interval tests pin the bare native text).
            // Burn belongs to the error-shape leg (Bucket 2), never a
            // silent delete here.
            message = m.group(1);
        }
        return message;
    }

}
