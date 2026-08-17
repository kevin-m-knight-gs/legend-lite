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
import com.legend.compiler.element.type.PlatformTypes;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native function that bridges PCT tests to Legend-Lite's QueryService.
 *
 * Pure expressions are re-escaped, executed via QueryService (compile → SQL → DuckDB),
 * and the typed ExecutionResult is converted back to Pure CoreInstances.
 *
 * Type information flows from Type on ExecutionResult: column names,
 * pure types, and multiplicities are the PLATFORM's typed facts (F5.1
 * replaced the sqlType-name sniff; F5.3 Stage B deleted the
 * declared-header overlay and the null-scan — PCT sees the wire).
 */
public class ExecuteLegendLiteQuery extends NativeFunction {

    private static final String PURE_MODEL = """
                Class model::DoyRecord { eventDate: StrictDate[1]; }
            ###Relational
                Database store::DoyDb ( Table T_DOY ( ID INTEGER, EVENT_DATE DATE ) )
            ###Mapping
                Mapping model::DoyMap ( DoyRecord: Relational { ~mainTable [DoyDb] T_DOY eventDate: [DoyDb] T_DOY.EVENT_DATE } )
            ###Connection
                RelationalDatabaseConnection store::TestConn { type: DuckDB; specification: DuckDB { }; auth: Test; }
            ###Runtime
                Runtime test::TestRuntime { mappings: [ model::DoyMap ]; connections: [ store::DoyDb: [ environment: store::TestConn ] ]; }

            ###Pure
                function meta::pure::functions::relation::tests::composition::testVariantColumn_functionComposition_filterValues(val: Integer[*]):Boolean[1]
                {
                    $val->filter(y | $y->mod(2) == 0)->size() == 2
                }

                function meta::pure::functions::lang::tests::letFn::letAsLastStatement():String[1]
                {
                    let last = 'last statement string'
                }

                function meta::pure::functions::lang::tests::letFn::letWithParam(val: String[1]):Any[*]
                {
                    let a = $val
                }

                function meta::pure::functions::lang::tests::letFn::letChainedWithAnotherFunction(elements: ModelElement[*]):ModelElement[*]
                {
                    let classes = $elements->removeDuplicates()
                }
            """;

    private static final Pattern INSTANCE_CLASS_PATTERN = Pattern.compile("\\^([\\w:]+)\\(");
    private static final Pattern TYPE_REF_PATTERN = Pattern.compile("@(\\w+(?:::\\w+)+)");
    private static final Pattern ENUM_REF_PATTERN = Pattern.compile("(\\w+(?:::\\w+)+)\\.\\w+");
    /** Parameter type annotations — match clauses ({@code a: My::Type[1]|...}), typed lambdas. */
    private static final Pattern PARAM_TYPE_PATTERN = Pattern.compile(":\\s*(\\w+(?:::\\w+)+)\\s*\\[");
    /** Bare element references as values ({@code STR_Person->toString()}). */
    private static final Pattern BARE_REF_PATTERN = Pattern.compile("(\\w+(?:::\\w+)+)\\s*->");

    private final ModelRepository modelRepository;

    public ExecuteLegendLiteQuery(FunctionExecutionInterpreted functionExecution, ModelRepository modelRepository) {
        this.modelRepository = modelRepository;
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

        String pureExpression = PrimitiveUtilities.getStringValue(
                Instance.getValueForMetaPropertyToOneResolved(params.get(0), M3Properties.values, processorSupport));
        pureExpression = reEscapeStringLiterals(pureExpression);

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

            // Inject class definitions from the interpreter's model
            java.util.Set<String> discoveredEnums = new java.util.LinkedHashSet<>();
            Map<String, String> extractedClasses =
                    extractClassMetadata(pureExpression, discoveredEnums, processorSupport);
            java.util.List<String> enumDefs =
                    extractEnumDefinitions(pureExpression, discoveredEnums, processorSupport);
            String model = h2
                    ? PURE_MODEL.replace("type: DuckDB;", "type: H2;")
                    : PURE_MODEL;
            if (!extractedClasses.isEmpty() || !enumDefs.isEmpty()) {
                StringBuilder classDefs = new StringBuilder();
                for (String ed : enumDefs) {
                    classDefs.append(ed).append("\n");
                }
                for (String classText : extractedClasses.values()) {
                    classDefs.append(classText).append("\n");
                }
                System.out.println("[LegendLite PCT] Injected model:\n" + classDefs);
                model = classDefs + model;
            }

            ExecutionResult result = new QueryService().execute(model, pureExpression,
                    "test::TestRuntime", connection);

            return switch (result) {
                case Scalar s -> handleScalar(s, processorSupport);
                case Collection c -> handleCollection(c, processorSupport);
                case Tabular t -> handleTabular(t, processorSupport);
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
        if (value instanceof java.util.Map<?, ?> map && isMapReturn(result.returnType())) {
            // Map<U,V> results flatten to [k1, v1, k2, v2, ...]; the pure
            // side rebuilds via pair()/newMap() (both native there).
            // (JDBC also hands STRUCT values as java.util.Map — the DECLARED
            // type gates, or ^Person(...) results would flatten here.)
            var flat = new ArrayList<CoreInstance>();
            for (var en : map.entrySet()) {
                flat.add(toCoreInstance(en.getKey(), result.returnType(), ps));
                flat.add(toCoreInstance(en.getValue(), result.returnType(), ps));
            }
            return ValueSpecificationBootstrap.wrapValueSpecification(
                    org.eclipse.collections.impl.factory.Lists.immutable.withAll(flat), true, ps);
        }
        if (value instanceof java.util.List<?> list) {
            // a List<T>-typed scalar (drop(1)->list()): elements convert
            // individually; the pure side wraps them back into ^List
            var elems = new ArrayList<CoreInstance>();
            for (Object v : list) {
                if (v != null) {
                    elems.add(toCoreInstance(v, result.returnType(), ps));
                }
            }
            return ValueSpecificationBootstrap.wrapValueSpecification(
                    org.eclipse.collections.impl.factory.Lists.immutable.withAll(elems), true, ps);
        }
        CoreInstance ci = toCoreInstance(value, result.returnType(), ps);
        return ValueSpecificationBootstrap.wrapValueSpecification(ci, true, ps);
    }

    private CoreInstance handleCollection(Collection result, ProcessorSupport ps) {
        Type elementType = result.returnType();
        var coreInstances = new ArrayList<CoreInstance>();
        for (Object value : result.values()) {
            if (value != null) {
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

    private CoreInstance handleTabular(Tabular result, ProcessorSupport ps) {
        String tdsString = formatAsTds(result);
        System.out.println("[LegendLite PCT] TDS: " + tdsString.replace("\n", "\\n"));
        return createTDSResult(tdsString, ps);
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
            if (v == null) {
                continue;
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
                    if (e != null) {
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
        if (value instanceof BigDecimal bd) {
            if (type instanceof Type.Primitive p
                    && (p == Type.Primitive.DECIMAL || p == Type.Primitive.NUMBER)) {
                return modelRepository.newDecimalCoreInstance(bd);
            }
            if (type instanceof Type.PrecisionDecimal) {
                // scale is part of the VALUE surface: abs(-3.0D) prints 3.0D
                return modelRepository.newDecimalCoreInstance(bd);
            }
            return modelRepository.newFloatCoreInstance(bd);
        }
        if (value instanceof Double d) {
            if (type instanceof Type.Primitive p && p == Type.Primitive.DECIMAL) {
                return modelRepository.newDecimalCoreInstance(BigDecimal.valueOf(d));
            }
            if (type instanceof Type.PrecisionDecimal) {
                return modelRepository.newDecimalCoreInstance(BigDecimal.valueOf(d).stripTrailingZeros());
            }
            return modelRepository.newFloatCoreInstance(BigDecimal.valueOf(d));
        }
        if (value instanceof Float f) {
            return modelRepository.newFloatCoreInstance(BigDecimal.valueOf(f.doubleValue()));
        }
        if (value instanceof Number n) {
            return modelRepository.newFloatCoreInstance(BigDecimal.valueOf(n.doubleValue()));
        }
        // Dates
        if (value instanceof java.sql.Date sqlDate) {
            return toPureDateInstance(sqlDate.toLocalDate());
        }
        if (value instanceof LocalDate ld) {
            return toPureDateInstance(ld);
        }
        if (value instanceof java.sql.Timestamp ts) {
            // Type tells us if this was originally a StrictDate promoted to Timestamp
            if (type instanceof Type.Primitive p && p == Type.Primitive.STRICT_DATE) {
                return toPureDateInstance(ts.toLocalDateTime().toLocalDate());
            }
            return toPureDateTimeInstance(ts.toLocalDateTime());
        }
        if (value instanceof LocalDateTime ldt) {
            return toPureDateTimeInstance(ldt);
        }
        if (value instanceof OffsetDateTime odt) {
            return toPureDateTimeInstance(odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        if (value instanceof LocalTime lt) {
            PureDate pd = DateFunctions.newPureDate(1, 1, 1, lt.getHour(), lt.getMinute(), lt.getSecond());
            return modelRepository.newCoreInstance(pd.toString(), modelRepository.getTopLevel("StrictTime"), null);
        }
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
            // Precision-faithful date STRINGS (the wire's date convention:
            // partial dates, subsecond digit counts beyond the TIMESTAMP
            // carrier) — parse preserving every written digit.
            if (type instanceof Type.Primitive p
                    && (p == Type.Primitive.DATE || p == Type.Primitive.DATE_TIME
                            || p == Type.Primitive.STRICT_DATE)
                    && s.matches("-?\\d{4,}(-\\d{2})?(-\\d{2})?([T ].*)?")) {
                PureDate pd = DateFunctions.parsePureDate(s);
                String classifier = pd.hasHour() ? "DateTime"
                        : pd.hasDay() ? "StrictDate" : "Date";
                return modelRepository.newCoreInstance(pd.toString(),
                        modelRepository.getTopLevel(classifier), null);
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
        // List (struct arrays unwrapped by Row.java, e.g. zip → List<Pair>)
        if (value instanceof List<?> list) {
            Type elemType = type;
            var coreInstances = new ArrayList<CoreInstance>();
            for (Object elem : list) {
                if (elem != null) {
                    coreInstances.add(toCoreInstance(elem, elemType, ps));
                }
            }
            // Return as a single-element wrapping — the collection will be wrapped by caller
            if (coreInstances.size() == 1) return coreInstances.get(0);
            // For multi-element, this shouldn't happen in scalar context
            // but return first as fallback
            return coreInstances.isEmpty() ? modelRepository.newStringCoreInstance("[]")
                    : coreInstances.get(0);
        }
        // Fallback
        return modelRepository.newStringCoreInstance(value.toString());
    }

    private CoreInstance toPureDateInstance(LocalDate ld) {
        PureDate pd = DateFunctions.newPureDate(ld.getYear(), ld.getMonthValue(), ld.getDayOfMonth());
        return modelRepository.newCoreInstance(pd.toString(),
                modelRepository.getTopLevel("StrictDate"), null);
    }

    private CoreInstance toPureDateTimeInstance(LocalDateTime ldt) {
        PureDate pd;
        int nanos = ldt.getNano();
        if (nanos > 0) {
            String subsecond = stripTrailingZeros(String.format("%09d", nanos));
            pd = DateFunctions.newPureDate(ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth(),
                    ldt.getHour(), ldt.getMinute(), ldt.getSecond(), subsecond);
        } else {
            pd = DateFunctions.newPureDate(ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth(),
                    ldt.getHour(), ldt.getMinute(), ldt.getSecond());
        }
        return modelRepository.newCoreInstance(pd.toString(),
                modelRepository.getTopLevel("DateTime"), null);
    }

    // ===== TDS formatting =====

    /**
     * Formats a TabularResult as a TDS string for stringToTDS().
     * Column types come from the compiler schema (already Pure type names).
     */
    private String formatAsTds(ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        var columns = result.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(",");
            Column col = columns.get(i);
            String colName = col.name();
            if (colName.startsWith("'")) {
                // quote-BEARING identity ('2011__|__newCol' with its
                // quotes): escape the inner pair inside an outer pair
                // (the rule the deleted overlay spelled — now here, the
                // one wire writer)
                colName = "'" + colName.replace("'", "\\'") + "'";
            } else if (colName.contains("__|__")) {
                // Pure pivot column IDENTITY includes the quotes
                // ('UK__|__LDN__|__sum'); TDSExtension strips ONE outer
                // layer, so the header carries an escaped inner pair.
                colName = "'\\'" + colName + "\\''";
            } else if (!colName.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                // Non-identifier names ('other kind') quote — the parser
                // strips the pair; the IDENTITY stays unquoted.
                colName = "'" + colName + "'";
            }
            // Pure 5.88's TDS parser resolves header types as PURE paths
            // (VARCHAR was "not found") — spell the Pure primitive, WITH a
            // data-driven multiplicity: the tests cast results to declared
            // Relation<(col:T[1])>/[0..1] shapes, and a header without the
            // annotation builds [0..1] columns that no longer cast to [1].
            // F5.3 Stage B: multiplicity is the column's TYPED fact
            // (F5.2 carried it across the bridge) — the value-scan that
            // used to sniff NULLs is deleted; a null arity means the
            // producer did not declare one and the column is to-one
            var m = col.multiplicity();
            boolean optional = m instanceof com.legend.compiler.element
                    .type.Multiplicity.Bounded b && b.lower() == 0;
            sb.append(colName).append(":").append(purePctName(col))
                    .append(optional ? "[0..1]" : "[1]");
        }
        for (var row : result.rows()) {
            sb.append("\n");
            var values = row.values();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(",");
                // Pure prints VARIANT cells ALWAYS quoted ("[]", "null"),
                // comma or not.
                boolean variant = com.legend.compiler.element.type
                        .PlatformTypes.isVariant(
                                columns.get(i).pureType());
                Object v = values.get(i);
                if (variant && v != null) {
                    sb.append("\"").append(v.toString().replace("\"", "\"\"")).append("\"");
                } else {
                    sb.append(formatValue(v));
                }
            }
        }
        return sb.toString();
    }

    /** F5.1: the column's PURE type names the header (the SQL-type-name
     * sniff is gone). F5.3 Stage B: temporals and Variant spell their
     * REAL pure names — the old "interpreted TestTDS cannot build Date
     * columns" claim was stale (the overlay had been writing Date
     * headers green for months); Variant needs its FQN because the 5.88
     * TDS header parser resolves type names without import scanning.
     * Variant cells still travel as quoted JSON text. */
    private static String purePctName(com.legend.exec.Column col) {
        var t = col.pureType();
        if (t instanceof com.legend.compiler.element.type.Type.Primitive p) {
            return p.typeName();
        }
        if (com.legend.compiler.element.type.PlatformTypes.isVariant(t)) {
            // F5.3 Stage B: the wire says what the column IS (cells
            // travel as quoted JSON text). FULLY QUALIFIED — the 5.88 TDS
            // header parser resolves type names as pure paths without
            // import scanning ('Variant not found!').
            return "meta::pure::metamodel::variant::Variant";
        }
        return t.typeName();
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        // Pure's date print forms: DateTime = ISO with millis + +0000
        // offset; StrictDate = plain date (the PCT expected strings).
        if (value instanceof java.sql.Timestamp ts) {
            var ldt = ts.toLocalDateTime();
            return ldt.format(java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")) + "+0000";
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")) + "+0000";
        }
        if (value instanceof Double d && !d.isInfinite() && !d.isNaN()) {
            // pure float PRINT: plain decimal, never scientific; integral
            // floats keep a trailing .0 (2.25e19 -> '22500000000000000000.0')
            java.math.BigDecimal bd = java.math.BigDecimal.valueOf(d);
            String plain = bd.toPlainString();
            return bd.scale() <= 0 ? plain + ".0" : plain;
        }
        String str = value.toString();
        // NOTE: an empty string CANNOT survive the TDS wire — the parser's
        // null literals are ["", "null"], quoted or not (probe-verified);
        // the one affected test is the ledgered expected failure.
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

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
    private java.util.List<String> extractEnumDefinitions(String pureExpression,
            java.util.Set<String> discoveredEnums, ProcessorSupport ps) {
        java.util.List<String> defs = new java.util.ArrayList<>();
        try {
            java.util.Set<String> enumFqns = new java.util.LinkedHashSet<>(discoveredEnums);
            Matcher enumRef = ENUM_REF_PATTERN.matcher(pureExpression);
            while (enumRef.find()) {
                enumFqns.add(enumRef.group(1));
            }
            Matcher enumTypeRef = TYPE_REF_PATTERN.matcher(pureExpression);
            while (enumTypeRef.find()) {
                enumFqns.add(enumTypeRef.group(1));
            }
            for (String fqn : enumFqns) {
                if (fqn.startsWith("meta::pure::metamodel")) {
                    continue;
                }
                CoreInstance enumCls = ps.package_getByUserPath(fqn);
                if (enumCls == null
                        || !Instance.instanceOf(enumCls, "meta::pure::metamodel::type::Enumeration", ps)
                        || com.legend.builtin.Pure.findNativeEnum(fqn).isPresent()) {
                    continue;
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
                defs.add(def.toString());
            }
        } catch (Exception e) {
            System.out.println("[LegendLite PCT] Enum extraction failed: " + e.getMessage());
        }
        return defs;
    }

    private Map<String, String> extractClassMetadata(String pureExpression,
            java.util.Set<String> discoveredEnums, ProcessorSupport ps) {
        try {
            Map<String, String> classes = new HashMap<>();
            Set<String> visited = new HashSet<>();
            Matcher matcher = INSTANCE_CLASS_PATTERN.matcher(pureExpression);
            while (matcher.find()) {
                extractClassRecursive(matcher.group(1), classes, visited, discoveredEnums, ps);
            }
            // MODEL classes referenced as type arguments (to(@X), cast(@X))
            // or as parameter type annotations (match clauses a: X[1]|...):
            // multi-segment FQNs outside the metamodel/platform space whose
            // resolved element is a Class.
            java.util.Set<String> typeFqns = new java.util.LinkedHashSet<>();
            Matcher typeRef = TYPE_REF_PATTERN.matcher(pureExpression);
            while (typeRef.find()) {
                typeFqns.add(typeRef.group(1));
            }
            Matcher paramType = PARAM_TYPE_PATTERN.matcher(pureExpression);
            while (paramType.find()) {
                typeFqns.add(paramType.group(1));
            }
            Matcher bareRef = BARE_REF_PATTERN.matcher(pureExpression);
            while (bareRef.find()) {
                typeFqns.add(bareRef.group(1));
            }
            for (String fqn : typeFqns) {
                if (fqn.startsWith("meta::pure::metamodel")
                        || fqn.startsWith("meta::pure::precisePrimitives")) {
                    continue;
                }
                CoreInstance cls = ps.package_getByUserPath(fqn);
                // Never inject a class core knows NATIVELY (Pair, List, ...):
                // the extraction degrades type parameters to Any and a
                // redefinition could silently shift platform semantics.
                if (cls == null || com.legend.builtin.Pure.findNativeClass(fqn).isPresent()) {
                    continue;
                }
                if (Instance.instanceOf(cls, "meta::pure::metamodel::type::Enumeration", ps)) {
                    discoveredEnums.add(fqn);
                } else if (Instance.instanceOf(cls, "meta::pure::metamodel::type::Class", ps)) {
                    extractClassRecursive(fqn, classes, visited, discoveredEnums, ps);
                }
            }
            return classes;
        } catch (Exception e) {
            System.out.println("[LegendLite PCT] Class metadata extraction failed: " + e.getMessage());
            return Map.of();
        }
    }

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
            return m.group(1);
        }
        return message;
    }

    /**
     * Re-escapes literal special characters inside single-quoted strings.
     * The Pure interpreter resolves escape sequences before passing the expression,
     * but our parser expects them unresolved.
     */
    private static String reEscapeStringLiterals(String expr) {
        StringBuilder sb = new StringBuilder(expr.length());
        boolean inString = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\'' && !inString) {
                inString = true;
                sb.append(c);
            } else if (c == '\'' && inString) {
                inString = false;
                sb.append(c);
            } else if (inString) {
                switch (c) {
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\\' -> {
                        // An ALREADY-escaped sequence (\', \n, \r, \t, \\)
                        // must pass through unchanged — the serializer emits
                        // control chars pre-escaped; doubling the backslash
                        // turned \n into a literal backslash-n (and \' into
                        // a string TERMINATOR, shredding pivot names).
                        char next = i + 1 < expr.length() ? expr.charAt(i + 1) : 0;
                        if (next == '\'' || next == 'n' || next == 'r'
                                || next == 't' || next == '\\') {
                            sb.append('\\').append(next);
                            i++;
                        } else {
                            sb.append("\\\\");
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String stripTrailingZeros(String subsecond) {
        int end = subsecond.length();
        while (end > 1 && subsecond.charAt(end - 1) == '0') {
            end--;
        }
        return subsecond.substring(0, end);
    }
}
