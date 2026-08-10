package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.builtin.Pure;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.Property;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedAggCol;
import com.legend.compiler.spec.typed.TypedAggColSpec;
import com.legend.compiler.spec.typed.TypedAggColSpecArray;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCDate;
import com.legend.compiler.spec.typed.TypedCLatestDate;
import com.legend.compiler.spec.typed.TypedCTime;
import com.legend.compiler.spec.typed.TypedCDecimal;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedColSpec;
import com.legend.compiler.spec.typed.TypedColSpecArray;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedFuncColSpec;
import com.legend.compiler.spec.typed.TypedFuncColSpecArray;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTypeRef;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.TypeExpression;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CDate;
import com.legend.protocol.spec.CLatestDate;
import com.legend.protocol.spec.CTime;
import com.legend.protocol.spec.CDecimal;
import com.legend.protocol.spec.CFloat;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.PathLiteral;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.NewInstance;
import com.legend.protocol.spec.NewInstanceCast;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.protocol.spec.PureCollection;
import com.legend.values.PureDateLiteral;
import com.legend.protocol.spec.TypeAnnotation;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The bidirectional expression type-checker (engine {@code TypeChecker}'s
 * expression half, PHASE_G_SPEC_COMPILER.md §2/§6): {@link #typeBody} walks a
 * parsed {@link ValueSpecification} into a {@link TypedSpec}, either
 * <em>synthesizing</em> its type or <em>checking</em> it against an
 * {@link Expected} one.
 *
 * <p><strong>Layering (Driver &rarr; Typer &rarr; Checkers &rarr; Kernel).</strong>
 * The {@link SpecCompiler} driver owns whole-function compilation; this class owns
 * exactly two things &mdash; the <em>forms</em> (literals, variables, collections,
 * property access, colspec/enum values) and the one <em>generic application
 * rule</em> ({@link #checkGeneric}: overload resolution, deferred lambda/colspec
 * arguments, signature-driven outputs). Each core construct's shape decisions,
 * desugars, and HIR emission live in its own {@code *Checker} class (engine's
 * checker layout, minus the god-base), reached through the exhaustive
 * {@link CoreFn} switch in {@link #applyCore}; the pure type machinery
 * (unify / constraints / resolve / lattice) is the {@link InferenceKernel}.
 */
final class Typer {

    private final ModelContext ctx;
    private final InferenceKernel kernel;

    /** A class name's FQN through the model (identity when unknown) —
     * TypedFrom's unchecked-body walk canonicalizes refs with it. */
    String classFqnOf(String name) {
        return ctx.findClass(name)
                .map(c -> c.qualifiedName()).orElse(name);
    }

    Typer(ModelContext ctx, InferenceKernel kernel) {
        this.ctx = ctx;
        this.kernel = kernel;
    }

    /** The model snapshot &mdash; the checkers' lookup surface. */
    ModelContext model() {
        return ctx;
    }

    /** The type machinery &mdash; unification, constraints, resolution, the lattice. */
    InferenceKernel kernel() {
        return kernel;
    }

    /**
     * Type-check {@code vs} in {@code env}, under the bidirectional {@code expected}
     * mode &mdash; the expression-level entry point used by the {@link SpecCompiler}
     * driver (and, via its delegate, by in-package tests).
     */
    TypedSpec typeBody(ValueSpecification vs, Env env, Expected expected) {
        TypedSpec node = synth(vs, env);
        if (expected instanceof Expected.Check check) {
            requireConforms(node.info(), check.expected());
        }
        return node;
    }

    /** Synthesis (inference) mode: produce the node and its intrinsic type. */
    TypedSpec synth(ValueSpecification vs, Env env) {
        return switch (vs) {
            // Path literals normally dissolve at resolution; an unresolved one types as
            // its desugared lambda.
            case PathLiteral pl -> synth(pl.desugared(), env);
            case com.legend.protocol.spec.GraphFetchLiteral gf -> synth(gf.desugared(), env);
            case com.legend.protocol.spec.TdsLiteral tl -> synth(tl.desugared(), env);
            case com.legend.protocol.spec.SqlIsland si ->
                    throw new com.legend.error.NotImplementedException(
                            "#SQL{...}# expression islands are not compilable —"
                                    + " an inline SQL string bypasses the typed"
                                    + " lowering pipeline");
            case CInteger lit -> new TypedCInteger(lit.value(), ExprType.one(Type.Primitive.INTEGER));
            case CString lit -> new TypedCString(lit.value(), ExprType.one(Type.Primitive.STRING));
            case CBoolean lit -> new TypedCBoolean(lit.value(), ExprType.one(Type.Primitive.BOOLEAN));
            case CFloat lit -> new TypedCFloat(lit.value(), ExprType.one(Type.Primitive.FLOAT));
            case CDecimal lit -> new TypedCDecimal(lit.value(), ExprType.one(decimalType(lit.value())));
            // Date literals type by PRECISION (engine's CStrictDate/CDateTime split):
            // year/year-month -> Date, full day -> StrictDate, any time part -> DateTime.
            case CDate lit -> new TypedCDate(lit.value(), ExprType.one(dateType(lit.value())));
            case CTime lit -> new TypedCTime(lit.requireValue(),
                    ExprType.one(Type.Primitive.STRICT_TIME));
            case CLatestDate ignored -> new TypedCLatestDate(ExprType.one(Type.Primitive.LATEST_DATE));
            case TypeAnnotation ta -> typeRef(ta);
            case Variable v -> new TypedVariable(v.name(), env.lookup(v.name()).orElseThrow(
                    () -> new TypeInferenceException("unbound variable '$" + v.name() + "'")));
            case AppliedFunction af -> applyFunction(af, env);
            case AppliedProperty ap -> accessProperty(ap, env);
            case PureCollection coll -> collection(coll, env);
            // a BARE TDSNull reference ($v != TDSNull, tds.pure
            // firstNotNull) is the same null-cell value as ^TDSNull() —
            // one funnel: both resolve to sqlNull()
            case PackageableElementPtr ref
                    when ref.fullPath().equals("TDSNull")
                    || ref.fullPath().equals("meta::pure::tds::TDSNull") ->
                    synth(new AppliedFunction("sqlNull", List.of()), env);
            case PackageableElementPtr ref -> classReference(ref);
            case NewInstance ni -> {
                // ^TDSNull() — the TDS null-cell literal (engine
                // meta::pure::tds::TDSNull): IS the SQL NULL. Exact names.
                if (ni.className().equals("TDSNull")
                        || ni.className().equals("meta::pure::tds::TDSNull")) {
                    yield synth(new AppliedFunction("sqlNull", List.of()), env);
                }
                yield NewChecker.check(this, ni, env);
            }
            case ColSpec cs -> typedColSpec(cs);
            case ColSpecArray arr -> typedColSpecArray(arr);
            case EnumValue ev -> enumValue(ev);
            // EXHAUSTIVE over sealed ValueSpecification — no default arm
            // (root package-info invariant): a new AST variant is a COMPILE
            // error here, not a runtime surprise. The two arms below are the
            // deliberate not-yet-implemented forms.
            case LambdaFunction lf -> {
                // A lambda LITERAL types WITHOUT a call when its parameter
                // types are knowable: zero-arg, or FULLY-ANNOTATED params
                // ({id:Integer[1], name:String[*]|...} — real pure types
                // these directly; audit 19d B4 + the executionPlan family).
                // Leading lets bind statement-style (multi-statement zero-arg
                // thunks); a partially-annotated lambda stays loud.
                boolean annotated = !lf.parameters().isEmpty()
                        && lf.parameters().stream().allMatch(pv -> pv.type() != null);
                if (lf.parameters().isEmpty() || annotated) {
                    Env scope = env;
                    List<String> names = new ArrayList<>();
                    List<Type.Param> params = new ArrayList<>();
                    for (Variable pv : lf.parameters()) {
                        Type pt = namedType(java.util.Objects.requireNonNull(pv.type(),
                                "lambda parameter without a declared type"));
                        Multiplicity pm = pv.multiplicity() == null
                                ? Multiplicity.Bounded.ONE
                                : Multiplicity.from(pv.multiplicity());
                        names.add(pv.name());
                        params.add(new Type.Param(pt, pm));
                        scope = scope.with(pv.name(), new ExprType(pt, pm));
                    }
                    List<TypedSpec> stmts = new ArrayList<>();
                    for (int si = 0; si < lf.body().size() - 1; si++) {
                        if (lf.body().get(si) instanceof AppliedFunction lset
                                && lset.function().equals("letFunction")
                                && lset.parameters().size() == 2
                                && lset.parameters().get(0) instanceof CString ln) {
                            TypedSpec val = synth(lset.parameters().get(1), scope);
                            scope = scope.with(ln.value(), val.info());
                            stmts.add(new com.legend.compiler.spec.typed.TypedLet(
                                    ln.value(), val, val.info()));
                            continue;
                        }
                        throw new TypeInferenceException("a non-let intermediate"
                                + " statement in a bare lambda literal is not"
                                + " supported");
                    }
                    TypedSpec body = synth(lf.body().get(lf.body().size() - 1), scope);
                    stmts.add(body);
                    var fnType = new Type.FunctionType(params,
                            new Type.Param(body.info().type(),
                                    body.info().multiplicity()));
                    yield new TypedLambda(names, List.copyOf(stmts),
                            new ExprType(fnType, Multiplicity.Bounded.ONE));
                }
                if (System.getenv("LL_TMP_DEBUG") != null) {
                    System.err.println("[bare-lambda] " + lf);
                    Thread.dumpStack();
                }
                throw new TypeInferenceException(
                        "a bare lambda has no type outside a call position"
                                + " (lambdas type against their call's signature)");
            }
            // ^Class($src): the MAPPING CAST — an upstream class value fed
            // through Class's mapping (M2M). Typed nominally here; the
            // RESOLVER composes it during class-source extraction (H5).
            case NewInstanceCast nc -> {
                if (!nc.typeArguments().isEmpty()) {
                    throw new TypeInferenceException("generic mapping cast ^"
                            + nc.className() + "<...>($src) is not supported yet");
                }
                TypedSpec src = synth(nc.src(), env);
                String fqn = nc.className();   // NameResolver already qualified it
                if (ctx.findClass(fqn).isEmpty()) {
                    throw new TypeInferenceException("Unknown type: '" + fqn
                            + "' is not a known class (in ^" + fqn + "(...) cast)");
                }
                yield new com.legend.compiler.spec.typed.TypedNewInstanceCast(fqn, src,
                        new ExprType(new Type.ClassType(fqn),
                                src.info().multiplicity()),
                        nc.targetSetId());
            }
        };
    }

    // =====================================================================
    // Application &mdash; CoreFn dispatch + the generic signature-driven path
    // =====================================================================

    /**
     * A function application. The name resolves to a {@link CoreFn} exactly once;
     * a core construct dispatches through the exhaustive {@code switch} in
     * {@link #applyCore}, anything else is a library call on the generic path.
     */
    private TypedSpec applyFunction(AppliedFunction af, Env env) {
        // The engine-shaped variadic spelling of infix arithmetic — one
        // collection parameter holding the whole same-op run. The compiler's
        // internal convention (and Pure.java's registered binary signatures)
        // is pairwise; desugar by LEFT FOLD (InfixArith.binarize). Wire
        // fidelity is unaffected: the model keeps the n-ary node; this
        // rewrite exists only inside typing.
        if (InfixArith.isNaryCarrier(af)) {
            return synth(InfixArith.binarize(af), env);
        }
        // Legacy TDS surface desugars (engine's TDS-era spellings):
        // col(fn, 'name') is the function-column spec — the modern ~name:fn
        if (af.function().equals("col") && af.parameters().size() == 2
                && af.parameters().get(0) instanceof LambdaFunction fn
                && af.parameters().get(1) instanceof CString name) {
            return synth(new ColSpec(name.value(), fn, null), env);
        }
        TypedSpec tdsSchema = tdsSchemaDesugars(af, env);
        if (tdsSchema != null) {
            return tdsSchema;
        }
        // tdsRows(tds) = $tds.rows (real tds.pure:301) — the rows-marker
        // read; emptiness et al. compose over it like any rows access
        if ((af.function().equals("tdsRows")
                    || af.function().equals("meta::pure::tds::tdsRows"))
                && af.parameters().size() == 1) {
            return synth(new AppliedProperty(af.parameters().get(0),
                    com.legend.compiler.element.type.PlatformTypes.ROWS_MARKER),
                    env);
        }
        // $r.getString('COL') / getInteger / ... — TDSRow typed accessors
        // read the named COLUMN of the relation row (a plain property
        // access post-desugar; a type mismatch is loud downstream). The
        // CELL is one value whatever the column's declared multiplicity —
        // an auto-mapped to-many path types its column [*] but projection
        // explodes it row-wise (engine TDSRow getters return T[1]) — so a
        // non-[1] column read conforms BY EMISSION (toOne; lowering is
        // erasure).
        if ((TDS_ROW_GETTERS.contains(af.function())
                    || af.function().equals("getNullableString"))
                && af.parameters().size() == 2
                && literalColName(af.parameters().get(1)) != null
                && synth(af.parameters().get(0), env).info().type()
                        instanceof Type.RelationType) {
            String colRef = java.util.Objects.requireNonNull(
                    literalColName(af.parameters().get(1)),
                    "TDS cell read requires a literal column name");
            TypedSpec cell = synth(new AppliedProperty(
                    af.parameters().get(0), colRef), env);
            // getNullableString returns String[0..1] (tds.pure:82/112) —
            // the optional cell read IS the semantics, no strictening
            if (af.function().equals("getNullableString")
                    || (cell.info().multiplicity() instanceof Multiplicity.Bounded b
                            && Integer.valueOf(1).equals(b.upper())
                            && b.lower() == 1)) {
                return cell;
            }
            return synth(new AppliedFunction("toOne", List.of(
                    new AppliedProperty(af.parameters().get(0), colRef))), env);
        }
        // $r.isNotNull('COL') / isNull — TDSRow null tests on the named
        // cell (tds.pure); the cell read is optional-typed, so the tests
        // ARE emptiness (same conform-by-emission as the dynafunction
        // spellings in RelOpTranslator)
        if ((af.function().equals("isNotNull") || af.function().equals("isNull"))
                && af.parameters().size() == 2
                && literalColName(af.parameters().get(1)) != null
                && synth(af.parameters().get(0), env).info().type()
                        instanceof Type.RelationType) {
            return synth(new AppliedFunction(
                    af.function().equals("isNotNull") ? "isNotEmpty" : "isEmpty",
                    List.of(new AppliedProperty(af.parameters().get(0),
                            java.util.Objects.requireNonNull(
                                    literalColName(af.parameters().get(1)),
                                    "TDS null test requires a literal column"
                                    + " name")))), env);
        }
        // engine TDSRow.get()->toString(): a NULL cell prints 'TDSNull'
        // (tds.pure:131-133 — the engine materializes ^TDSNull() instances;
        // our erasure emits the equivalent conditional string)
        if (af.function().equals("toString") && af.parameters().size() == 1
                && af.parameters().get(0) instanceof AppliedFunction g
                && g.function().equals("get") && g.parameters().size() == 2
                && g.parameters().get(1) instanceof CString gc) {
            TypedSpec grecv0 = synth(g.parameters().get(0), env);
            if (grecv0.info().type() instanceof Type.RelationType) {
                var read = new AppliedProperty(g.parameters().get(0), gc.value());
                return synth(new AppliedFunction("if", List.of(
                        new AppliedFunction("isEmpty", List.of(read)),
                        new com.legend.protocol.spec.LambdaFunction(List.of(),
                                List.of(new CString(com.legend.compiler.element.type
                                        .PlatformTypes.TDS_NULL_CELL))),
                        new com.legend.protocol.spec.LambdaFunction(List.of(),
                                List.of(new AppliedFunction("toString", List.of(
                                        new AppliedFunction("toOne",
                                                List.of(read)))))))), env);
            }
        }
        // the UNTYPED TDSRow getter $r.get('COL') — same desugar, but the
        // name collides with variant/map get: divert ONLY when the receiver
        // is relation-shaped (type-aware, unlike the typed getters above).
        // The CELL is one value like the typed getters (engine tds.pure
        // get: Any[1]) — same toOne emission over non-[1] columns.
        if (af.function().equals("get") && af.parameters().size() == 2
                && af.parameters().get(1) instanceof CString gcol) {
            TypedSpec grecv = synth(af.parameters().get(0), env);
            if (grecv.info().type() instanceof Type.RelationType) {
                TypedSpec gcell = synth(new AppliedProperty(
                        af.parameters().get(0), gcol.value()), env);
                if (gcell.info().multiplicity() instanceof Multiplicity.Bounded gb
                        && Integer.valueOf(1).equals(gb.upper()) && gb.lower() == 1) {
                    return gcell;
                }
                return synth(new AppliedFunction("toOne", List.of(
                        new AppliedProperty(af.parameters().get(0), gcol.value()))), env);
            }
        }
        // restrict(['c1','c2']) — the legacy TDS column-subset select
        if ((af.function().equals("restrict") || af.function().equals("restrictDistinct"))
                && af.parameters().size() == 2) {
            List<ValueSpecification> cols = af.parameters().get(1) instanceof PureCollection c
                    ? c.values() : List.of(af.parameters().get(1));
            if (!cols.isEmpty() && cols.stream().allMatch(v -> v instanceof CString)) {
                List<ColSpec> specs = cols.stream()
                        .map(v -> new ColSpec(stripQuotes(((CString) v).value()), null, null))
                        .toList();
                AppliedFunction select = new AppliedFunction("select",
                        List.of(af.parameters().get(0),
                                new com.legend.protocol.spec.ColSpecArray(specs)));
                return synth(af.function().equals("restrictDistinct")
                        ? new AppliedFunction("distinct", List.of(select)) : select, env);
            }
        }
        if ((af.function().equals("extractEnumValue") || af.function().equals(
                "meta::pure::functions::lang::extractEnumValue"))
                && af.parameters().size() == 2) {
            TypedSpec folded = extractEnumValueFold(af, env);
            if (folded != null) {
                return folded;
            }
            // not Enumeration-shaped: the generic path types it against
            // the registered signature (loud on mismatch)
        }
        Optional<CoreFn> core = CoreFn.of(af.function());
        if (core.isPresent()) {
            return applyCore(core.get(),
                    aliasNormalized(core.get(), af), env);
        }
        // instanceOf(cell, TDSNull): the null-cell type test IS the SQL
        // null test (the engine materializes ^TDSNull() for null cells;
        // tds.pure) — typed as isEmpty so every consumer shares the scalar
        // IS NULL lowering. Exact names only; instanceOf against any other
        // type stays the loud unknown (audit 19d B7 — this transplant
        // lived in the harness's pre-typing substitute).
        if (af.function().equals("instanceOf")
                && af.parameters().size() == 2
                && af.parameters().get(1)
                        instanceof com.legend.protocol.spec.PackageableElementPtr pep
                && (pep.fullPath().equals("meta::pure::tds::TDSNull")
                        || pep.fullPath().equals("TDSNull"))) {
            return synth(new AppliedFunction("isEmpty",
                    List.of(af.parameters().get(0))), env);
        }
        // PARAMETERIZED qualified property: $p.synonymByType(X) routes to the
        // externalized body function <owner>$prop$<name>(this, args...) and
        // β-inlines with every other user call — never shadows a real function
        if (!af.parameters().isEmpty() && functionCandidates(af).isEmpty()) {
            TypedSpec recv = synth(af.parameters().get(0), env);
            String classFqn = recv.info().type() instanceof Type.ClassType ct ? ct.fqn()
                    : recv.info().type() instanceof Type.GenericType g ? g.rawFqn() : null;
            if (classFqn != null
                    && ctx.findProperty(classFqn, af.function()).orElse(null)
                            instanceof Property.Derived d
                    && d.parameters().size() == af.parameters().size() - 1) {
                // AUTO-MAP: a qualifier call on a MANY receiver applies per
                // element (engine qualified-property auto-map:
                // $o.product($bd).qualifier() over a [*] milestoned read)
                // — rewrite as receiver->map(v|$v.qualifier(args))
                if (recv.info().multiplicity()
                        instanceof Multiplicity.Bounded rb && rb.isMany()) {
                    Variable mv = new Variable("v_qam");
                    java.util.List<ValueSpecification> inner =
                            new ArrayList<>(af.parameters());
                    inner.set(0, mv);
                    return synth(new AppliedFunction("map", List.of(
                            af.parameters().get(0),
                            new LambdaFunction(List.of(mv), List.of(
                                    new AppliedFunction(af.function(), inner))))),
                            env);
                }
                return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(),
                        af.parameters()), env);
            }
            // MILESTONED property functions (real pure GENERATES these on
            // ends targeting a temporal class): prop(date) — point access;
            // propAllVersions() — version sweep; propAllVersionsInRange(s, e).
            if (classFqn != null) {
                String name = af.function();
                String base = name;
                boolean sweep = false;
                int wantDates = 1;
                if (name.endsWith("AllVersionsInRange")) {
                    base = name.substring(0, name.length() - "AllVersionsInRange".length());
                    sweep = true;
                    wantDates = 2;
                } else if (name.endsWith("AllVersions")) {
                    base = name.substring(0, name.length() - "AllVersions".length());
                    sweep = true;
                    wantDates = 0;
                }
                var prop = ctx.findProperty(classFqn, base).orElse(null);
                String targetFqn = prop != null
                        && prop.type() instanceof Type.ClassType pct ? pct.fqn() : null;
                com.legend.compiler.element.MilestoningStrategy targetStrat
                        = targetFqn == null ? null
                        : com.legend.compiler.element.Temporal.strategyOf(ctx, targetFqn);
                boolean arityOk = af.parameters().size() - 1 == wantDates;
                if (targetStrat == com.legend.compiler.element
                        .MilestoningStrategy.BITEMPORAL && !sweep) {
                    // product(processingDate, businessDate) — or the 1-date
                    // generated form (the owner's dimension fills the other)
                    int n2 = af.parameters().size() - 1;
                    arityOk = n2 == 2 || n2 == 1;
                }
                if (targetFqn != null && arityOk && targetStrat != null) {
                    List<TypedSpec> dates = new ArrayList<>();
                    for (int i = 1; i < af.parameters().size(); i++) {
                        dates.add(synth(af.parameters().get(i), env));
                    }
                    var mprop = java.util.Objects.requireNonNull(prop, "prop");
                    return new com.legend.compiler.spec.typed.TypedMilestonedAccess(
                            recv, base, dates, sweep,
                            new ExprType(mprop.type(), mprop.multiplicity()));
                }
            }
        }
        return applyGeneric(af, env);
    }

    private static String stripQuotes(String name) {
        return name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")
                ? name.substring(1, name.length() - 1) : name;
    }

    /**
     * The SCHEMA-computing legacy TDS spellings (engine tds.pure host-graph
     * bodies), desugared to modern natives or folded to literals; null when
     * none applies — the caller continues down the ordinary dispatch.
     */
    private @com.legend.Nullable TypedSpec tdsSchemaDesugars(AppliedFunction af, Env env) {
        // renameColumn(tds,'a','b') / renameColumns(tds, pair('a','b')...)
        // — desugar to the modern rename native (STATIC pair literals only)
        if (tdsVocab(af.function(), "renameColumn") && af.parameters().size() == 3
                && af.parameters().get(1) instanceof CString ro
                && af.parameters().get(2) instanceof CString rn) {
            return synth(new AppliedFunction("rename", List.of(
                    af.parameters().get(0),
                    new ColSpec(stripQuotes(ro.value()), null, null),
                    new ColSpec(stripQuotes(rn.value()), null, null))), env);
        }
        if (tdsVocab(af.function(), "renameColumns") && af.parameters().size() == 2) {
            return renameColumnsDesugar(af, env);
        }
        // TDSColumn-metadata computations over `.columns` fold to literals
        // (engine TabularDataSet reflection: `$tds.columns->map(c|$c.name +
        // ':' + $c.type->elementToPath())` — column names and types are
        // STATIC FACTS of the typed relation). Only a FULLY static result
        // rewrites; anything else keeps the ordinary path and its walls.
        if (af.function().equals("map") && af.parameters().size() == 2
                && af.parameters().get(0) instanceof AppliedProperty colsRead
                && colsRead.property().equals("columns")) {
            ValueSpecification lit = new StaticFold(this, env).foldToLiteral(af);
            if (lit != null) {
                return synth(lit, env);
            }
        }
        // projectWithColumnSubset — the engine's demand-pruned project: the
        // emitted SQL computes ONLY the subset-named columns, so the desugar
        // IS project over the filtered column list. Two spellings:
        // (src, [col(fn,'name')...], [subsetNames]) and
        // (src, [lambdas], [allNames], [subsetNames]).
        if (tdsVocab(af.function(), "projectWithColumnSubset")) {
            AppliedFunction pcs = projectWithColumnSubsetDesugar(af);
            if (pcs != null) {
                return synth(pcs, env);
            }
        }
        // window cols in PROJECT position — the OLAP col overloads
        if (tdsVocab(af.function(), "project")) {
            AppliedFunction wcd = windowColsProjectDesugar(af);
            if (wcd != null) {
                return synth(wcd, env);
            }
        }
        // #/A/b!alias# outside project position: the VALUE is the path's
        // navigation lambda; the alias is projection metadata (ProjectChecker
        // consumes the raw carrier before typing ever sees it)
        if (af.function().equals("pathWithAlias") && af.parameters().size() == 2) {
            return synth(af.parameters().get(0), env);
        }
        // paginated(set, page, size) — real pure collectionExtension.pure:236
        // body verbatim: slice((page-1)*size, page*size)
        if ((af.function().equals("paginated")
                || af.function().endsWith("::paginated"))
                && af.parameters().size() == 3) {
            ValueSpecification pg = af.parameters().get(1);
            ValueSpecification sz = af.parameters().get(2);
            return synth(new AppliedFunction("slice", List.of(
                    af.parameters().get(0),
                    new AppliedFunction("times", List.of(
                            new AppliedFunction("minus", List.of(pg,
                                    new CInteger(1L))),
                            sz)),
                    new AppliedFunction("times", List.of(pg, sz)))), env);
        }
        // olapGroupBy — the legacy TDS OLAP spellings; the modern construct
        // IS the windowed extend (see olapGroupByDesugar)
        if (tdsVocab(af.function(), "olapGroupBy")) {
            AppliedFunction olap = olapGroupByDesugar(af);
            if (olap != null) {
                return synth(olap, env);
            }
        }
        // union(a, b) — SQL UNION: distinct over the concatenation (the
        // same shape is pure's collection set-union, so both spellings
        // mean exactly this)
        if (tdsVocab(af.function(), "union") && af.parameters().size() == 2) {
            // union(a, [b,c,d]) — the collection overload chains the
            // concatenation member by member
            ValueSpecification acc = af.parameters().get(0);
            List<ValueSpecification> members =
                    af.parameters().get(1) instanceof PureCollection pc
                            ? pc.values() : List.of(af.parameters().get(1));
            for (ValueSpecification m : members) {
                acc = new AppliedFunction("concatenate", List.of(acc, m));
            }
            return synth(new AppliedFunction("distinct", List.of(acc)), env);
        }
        // columnValues(tds,'c') — the rows-mapped cell read
        if (tdsVocab(af.function(), "columnValues") && af.parameters().size() == 2
                && af.parameters().get(1) instanceof CString cvCol) {
            return synth(new AppliedFunction("map", List.of(
                    new AppliedProperty(af.parameters().get(0), "rows"),
                    new LambdaFunction(List.of(new Variable("_cvr")),
                            List.of(new AppliedFunction("get", List.of(
                                    new Variable("_cvr"),
                                    new CString(cvCol.value()))))))), env);
        }
        return null;
    }

    /**
     * The legacy TDS OLAP spellings as the modern windowed extend:
     * {@code olapGroupBy([parts]?, [sortKeys]?, func('col',agg) | rankLambda,
     * 'name')} &rarr; {@code extend(over(~parts, [sortKeys]), ~name:…)}.
     * The agg form's column becomes the {p,w,r|$r.col} map lambda with the
     * user's reducer; a bare rank lambda ({@code x|$x->rank()}) becomes the
     * modern window-function call ({@code {p,w,r|$p->rank($w,$r)}}). Null on
     * any other shape — the unknown-function wall stays loud.
     */
    private static @com.legend.Nullable AppliedFunction olapGroupByDesugar(AppliedFunction af) {
        List<ValueSpecification> ps = af.parameters();
        if (ps.size() < 3 || !(ps.get(ps.size() - 1) instanceof CString outName)) {
            return null;
        }
        int i = 1;
        List<ValueSpecification> partSpecs = new ArrayList<>();
        if (i < ps.size() - 2 && ps.get(i) instanceof CString p1) {
            partSpecs.add(new ColSpec(p1.value()));
            i++;
        } else if (i < ps.size() - 2 && ps.get(i) instanceof PureCollection pc
                && pc.values().stream().allMatch(v -> v instanceof CString)) {
            pc.values().forEach(v -> partSpecs.add(new ColSpec(((CString) v).value())));
            i++;
        }
        List<ValueSpecification> sortKeys = new ArrayList<>();
        if (i < ps.size() - 2 && isLegacySortKey(ps.get(i))) {
            sortKeys.add(ps.get(i));
            i++;
        } else if (i < ps.size() - 2 && ps.get(i) instanceof PureCollection sc
                && !sc.values().isEmpty()
                && sc.values().stream().allMatch(Typer::isLegacySortKey)) {
            sortKeys.addAll(sc.values());
            i++;
        }
        if (i != ps.size() - 2) {
            return null;
        }
        ValueSpecification op = ps.get(i);
        List<ValueSpecification> overArgs = new ArrayList<>();
        if (!partSpecs.isEmpty()) {
            overArgs.add(new PureCollection(partSpecs));
        }
        if (!sortKeys.isEmpty()) {
            overArgs.add(new PureCollection(sortKeys));
        }
        if (overArgs.isEmpty()) {
            return null;
        }
        Variable p = new Variable("_olp");
        Variable w = new Variable("_olw");
        Variable r = new Variable("_olr");
        ColSpec col;
        if (op instanceof AppliedFunction fc && tdsVocab(fc.function(), "func")
                && fc.parameters().size() == 2
                && fc.parameters().get(0) instanceof CString aggCol
                && fc.parameters().get(1) instanceof LambdaFunction aggFn) {
            col = new ColSpec(outName.value(),
                    new LambdaFunction(List.of(p, w, r), List.of(
                            new AppliedProperty(r, aggCol.value()))),
                    aggFn);
        } else {
            LambdaFunction rankLam = op instanceof AppliedFunction fr
                    && tdsVocab(fr.function(), "func")
                    && fr.parameters().size() == 1
                    && fr.parameters().get(0) instanceof LambdaFunction inner
                    ? inner
                    : op instanceof LambdaFunction direct ? direct : null;
            String rankFn = rankLam == null ? null : legacyRankName(rankLam);
            if (rankFn == null) {
                return null;
            }
            List<ValueSpecification> rankArgs = rankFn.equals("rowNumber")
                    ? List.of(p, r) : List.of(p, w, r);
            col = new ColSpec(outName.value(),
                    new LambdaFunction(List.of(p, w, r), List.of(
                            new AppliedFunction(rankFn, rankArgs))), null);
        }
        return new AppliedFunction("extend", List.of(ps.get(0),
                new AppliedFunction("over", overArgs), col));
    }

    private static boolean isLegacySortKey(ValueSpecification v) {
        return v instanceof AppliedFunction sf
                && (simpleFnName(sf.function()).equals("asc")
                        || simpleFnName(sf.function()).equals("desc"))
                && sf.parameters().size() == 1
                && sf.parameters().get(0) instanceof CString;
    }

    /** The modern window-function name behind a legacy rank lambda
     * ({@code x|$x->rank()}); null for anything unrecognized. */
    private static @com.legend.Nullable String legacyRankName(LambdaFunction lam) {
        if (lam.parameters().size() != 1 || lam.body().size() != 1
                || !(lam.body().get(0) instanceof AppliedFunction call)
                || call.parameters().size() != 1
                || !(call.parameters().get(0) instanceof Variable v)
                || !v.name().equals(lam.parameters().get(0).name())) {
            return null;
        }
        return switch (simpleFnName(call.function())) {
            case "rank" -> "rank";
            case "denseRank" -> "denseRank";
            case "rowNumber" -> "rowNumber";
            case "averageRank" -> null;   // no modern counterpart yet — loud
            default -> null;
        };
    }

    private static String simpleFnName(String fn) {
        int cut = fn.lastIndexOf("::");
        return cut < 0 ? fn : fn.substring(cut + 2);
    }

    /** A window-col carrier: declared name, hidden partition/map input
     * column names, and the user's reducer lambda. */
    private record WinCol(String name, List<String> partCols, String mapCol,
            LambdaFunction agg) {
    }

    /** The WINDOW-COL project overloads (REAL tds.pure:233 —
     * {@code col(window(parts...), func(map, agg), name)}, name LAST,
     * OlapAggregation form) as the MODERN windowed extend: the project
     * carries hidden partition/map INPUT columns ({@code <name>__wpN} /
     * {@code <name>__wm}), each window col extends with
     * {@code over(~parts)} + the user's reducer, and a closing restrict
     * returns exactly the declared names in declaration order (hidden
     * inputs drop there). Null when no window col is present; the
     * sortInfo/rank overload variants keep the loud project wall. */
    private static @com.legend.Nullable AppliedFunction windowColsProjectDesugar(AppliedFunction af) {
        List<ValueSpecification> ps = af.parameters();
        if (ps.size() != 2 || !(ps.get(1) instanceof PureCollection cols)) {
            return null;
        }
        if (cols.values().stream().noneMatch(v -> v instanceof AppliedFunction cf
                && simpleFnName(cf.function()).equals("col")
                && !cf.parameters().isEmpty()
                && cf.parameters().get(0) instanceof AppliedFunction w0
                && tdsVocab(w0.function(), "window"))) {
            return null;
        }
        List<ValueSpecification> projCols = new ArrayList<>();
        List<ValueSpecification> declared = new ArrayList<>();
        List<WinCol> wins = new ArrayList<>();
        for (ValueSpecification v : cols.values()) {
            if (!(v instanceof AppliedFunction cf)
                    || !simpleFnName(cf.function()).equals("col")) {
                return null;
            }
            List<ValueSpecification> cps = cf.parameters();
            if (!(cps.get(0) instanceof AppliedFunction w
                    && tdsVocab(w.function(), "window"))) {
                // plain col (2-arg or 3-arg with doc): name at index 1
                if (cps.size() < 2 || !(cps.get(1) instanceof CString pn)) {
                    return null;
                }
                projCols.add(cf);
                declared.add(new CString(pn.value()));
                continue;
            }
            if (cps.size() != 3
                    || !(cps.get(1) instanceof AppliedFunction fc)
                    || !tdsVocab(fc.function(), "func")
                    || fc.parameters().size() != 2
                    || !(fc.parameters().get(0) instanceof LambdaFunction mapLam)
                    || !(fc.parameters().get(1) instanceof LambdaFunction aggLam)
                    || !(cps.get(2) instanceof CString wn)) {
                return null;
            }
            List<ValueSpecification> parts = w.parameters().size() == 1
                    && w.parameters().get(0) instanceof PureCollection wp
                    ? wp.values() : w.parameters();
            List<String> partCols = new ArrayList<>();
            for (int j = 0; j < parts.size(); j++) {
                if (!(parts.get(j) instanceof LambdaFunction pl)) {
                    return null;
                }
                String pn = wn.value() + "__wp" + j;
                projCols.add(new AppliedFunction("col",
                        List.of(pl, new CString(pn))));
                partCols.add(pn);
            }
            String mn = wn.value() + "__wm";
            projCols.add(new AppliedFunction("col",
                    List.of(mapLam, new CString(mn))));
            wins.add(new WinCol(wn.value(), partCols, mn, aggLam));
            declared.add(new CString(wn.value()));
        }
        ValueSpecification chain = new AppliedFunction("project",
                List.of(ps.get(0), new PureCollection(projCols)));
        for (WinCol wc : wins) {
            List<ValueSpecification> partSpecs = new ArrayList<>();
            for (String pc : wc.partCols()) {
                partSpecs.add(new ColSpec(pc));
            }
            Variable p = new Variable("_wcp");
            Variable ww = new Variable("_wcw");
            Variable r = new Variable("_wcr");
            chain = new AppliedFunction("extend", List.of(chain,
                    new AppliedFunction("over",
                            List.of(new PureCollection(partSpecs))),
                    new ColSpec(wc.name(),
                            new LambdaFunction(List.of(p, ww, r), List.of(
                                    new AppliedProperty(r, wc.mapCol()))),
                            wc.agg())));
        }
        return new AppliedFunction("restrict",
                List.of(chain, new PureCollection(declared)));
    }

    /** {@code projectWithColumnSubset} as plain {@code project} over the
     * subset-named columns (subset-list order, engine parity); null when the
     * shape is not the static legacy spelling — the generic path stays loud. */
    private static @com.legend.Nullable AppliedFunction projectWithColumnSubsetDesugar(AppliedFunction af) {
        List<ValueSpecification> ps = af.parameters();
        java.util.LinkedHashMap<String, LambdaFunction> byName = new java.util.LinkedHashMap<>();
        List<String> subset;
        if (ps.size() == 3 && ps.get(1) instanceof PureCollection cols
                && ps.get(2) instanceof PureCollection subs) {
            subset = literalStrings(subs);
            if (subset == null) {
                return null;
            }
            for (ValueSpecification v : cols.values()) {
                if (v instanceof AppliedFunction cf && cf.function().equals("col")
                        && cf.parameters().size() == 2
                        && cf.parameters().get(0) instanceof LambdaFunction fn
                        && cf.parameters().get(1) instanceof CString nm) {
                    byName.put(nm.value(), fn);
                } else if (v instanceof ColSpec cs && cs.function1() != null) {
                    byName.put(cs.name(), cs.function1());
                } else {
                    return null;
                }
            }
        } else if (ps.size() == 4 && ps.get(1) instanceof PureCollection lams
                && ps.get(2) instanceof PureCollection allNames
                && ps.get(3) instanceof PureCollection subs) {
            subset = literalStrings(subs);
            List<String> names = literalStrings(allNames);
            if (subset == null || names == null
                    || names.size() != lams.values().size()
                    || !lams.values().stream().allMatch(v -> v instanceof LambdaFunction)) {
                return null;
            }
            for (int i = 0; i < names.size(); i++) {
                byName.put(names.get(i), (LambdaFunction) lams.values().get(i));
            }
        } else {
            return null;
        }
        List<ValueSpecification> outLams = new ArrayList<>(subset.size());
        List<ValueSpecification> outNames = new ArrayList<>(subset.size());
        for (String s : subset) {
            LambdaFunction fn = byName.get(s);
            if (fn == null) {
                return null;
            }
            outLams.add(fn);
            outNames.add(new CString(s));
        }
        return new AppliedFunction("project", List.of(ps.get(0),
                new PureCollection(outLams), new PureCollection(outNames)));
    }

    private static @com.legend.Nullable List<String> literalStrings(PureCollection c) {
        List<String> out = new ArrayList<>(c.values().size());
        for (ValueSpecification v : c.values()) {
            if (!(v instanceof CString s)) {
                return null;
            }
            out.add(s.value());
        }
        return out;
    }

    /** The LITERAL column name of a TDSRow accessor argument: a plain
     * 'COL' string, or the TDSColumn-object spelling
     * {@code $tds.columnByName('COL')[->toOne()]} (tds.pure:21/111-112 —
     * the qualified property filters columns by name, so a literal
     * argument IS the name; non-literal column expressions stay null and
     * the caller's arm passes). */
    private static @com.legend.Nullable String literalColName(ValueSpecification v) {
        if (v instanceof CString cs) {
            return cs.value();
        }
        if (v instanceof AppliedFunction tf
                && (tf.function().equals("toOne") || tf.function().equals(
                        "meta::pure::functions::multiplicity::toOne"))
                && tf.parameters().size() == 1) {
            return literalColName(tf.parameters().get(0));
        }
        if (v instanceof AppliedFunction cf
                && tdsVocab(cf.function(), "columnByName")
                && cf.parameters().size() == 2
                && cf.parameters().get(1) instanceof CString name) {
            return name.value();
        }
        return null;
    }

    /** The legacy TDSRow typed column accessors (getString('COL') et al). */
    private static final java.util.Set<String> TDS_ROW_GETTERS = java.util.Set.of(
            "getString", "getInteger", "getFloat", "getDecimal", "getNumber",
            "getBoolean", "getDate", "getDateTime", "getStrictDate", "getEnum");

    /** Segment-aware legacy tds.pure vocabulary match: the BARE simple
     * name or the exact {@code meta::pure::tds::} FQN — never a SUFFIX of
     * a longer user name (exact-FQN rule, audit 23 A1;
     * {@code my::customRenameColumn} calls the user function). */
    private static boolean tdsVocab(String fn, String simple) {
        return fn.equals(simple) || fn.equals("meta::pure::tds::" + simple);
    }

    /** renameColumns(tds, pairs) desugar — literal pair(,)/^Pair(first=,second=) chains into rename natives. */
    private TypedSpec renameColumnsDesugar(AppliedFunction af, Env env) {
            List<ValueSpecification> pairs =
                    af.parameters().get(1) instanceof PureCollection pc
                            ? pc.values() : List.of(af.parameters().get(1));
            ValueSpecification acc = af.parameters().get(0);
            for (ValueSpecification pv : pairs) {
                String po = null;
                String pn = null;
                if (pv instanceof AppliedFunction pf
                        && (pf.function().equals("pair") || pf.function()
                                .equals("meta::pure::functions::collection::pair"))
                        && pf.parameters().size() == 2
                        && pf.parameters().get(0) instanceof CString pos
                        && pf.parameters().get(1) instanceof CString pns) {
                    po = pos.value();
                    pn = pns.value();
                }
                // the corpus's other literal spelling:
                // ^Pair<String,String>(first='old', second='new') — the
                // parser wraps the ctor as AppliedFunction("new",
                // [receiver, NewInstance])
                ValueSpecification pu = pv instanceof AppliedFunction nf
                        && nf.function().equals("new")
                        && nf.parameters().size() == 2
                        ? nf.parameters().get(1) : pv;
                if (pu instanceof NewInstance ni
                        && (ni.className().equals("Pair") || ni.className()
                                .equals("meta::pure::functions::collection::Pair"))
                        && ni.properties().get("first") != null
                        && ni.properties().get("first").value()
                                instanceof CString pof
                        && ni.properties().get("second") != null
                        && ni.properties().get("second").value()
                                instanceof CString pnf) {
                    po = pof.value();
                    pn = pnf.value();
                }
                if (po == null) {
                    throw new SchemaInvariantException("renameColumns expects"
                            + " literal pair('old','new') /"
                            + " ^Pair(first=,second=) mappings");
                }
                acc = new AppliedFunction("rename", List.of(acc,
                        new ColSpec(stripQuotes(po), null, null),
                        new ColSpec(stripQuotes(java.util.Objects
                                .requireNonNull(pn, "pn")), null, null)));
            }
            return synth(acc, env);
    }

    /** extractEnumValue(Enumeration, 'NAME') — SPECIAL FORM against the
     * registered signature (real pure extractEnumValue.pure:25): a LITERAL
     * name constant-folds to the enum VALUE so downstream enum-literal
     * consumers (adjust's DurationUnit arm) see it; a non-literal name is
     * loud, never a silent string. Null = arg0 not Enumeration-shaped. */
    private @com.legend.Nullable TypedSpec extractEnumValueFold(AppliedFunction af, Env env) {
        TypedSpec e0 = synth(af.parameters().get(0), env);
        if (!(e0.info().type() instanceof Type.GenericType gt)
                || !gt.rawFqn().equals(Pure.ENUMERATION.qualifiedName())
                || gt.arguments().size() != 1
                || !(gt.arguments().get(0) instanceof Type.EnumType et)) {
            return null;
        }
        if (!(af.parameters().get(1) instanceof CString nm)) {
            throw new com.legend.error.NotImplementedException(
                    "extractEnumValue with a non-literal name is not"
                    + " supported yet");
        }
        var en = ctx.findEnum(et.fqn()).orElseThrow(() ->
                new TypeInferenceException("unknown enumeration '"
                        + et.fqn() + "'"));
        if (!en.values().contains(nm.value())) {
            throw new TypeInferenceException("enumeration '" + et.fqn()
                    + "' has no value '" + nm.value() + "'");
        }
        return new TypedEnumValue(et.fqn(), nm.value(), ExprType.one(et));
    }

    /** CURATED-alias spellings (tds::distinct, relation::eval) have no
     * FQN-registered native — dispatch under the bare parse name so the
     * checker's checkGeneric resolves candidates. */
    private static AppliedFunction aliasNormalized(CoreFn core, AppliedFunction af) {
        return !af.function().equals(core.parseName())
                && af.function().contains("::")
                && com.legend.builtin.Pure.nativeFunctionsAt(af.function()).isEmpty()
                ? new AppliedFunction(core.parseName(), af.parameters()) : af;
    }

    /**
     * The core-construct dispatch &mdash; exhaustive over {@link CoreFn} (a new
     * construct cannot be added without a rule here), one line per construct: the
     * construct's shape decisions, desugars, and emission live in its
     * {@code *Checker}. An arm owns <em>all</em> overloads of its name; where only
     * some shapes are structural (relation vs collection {@code sort}), its checker
     * delegates the rest back to {@link #applyGeneric}.
     */
    private TypedSpec applyCore(CoreFn fn, AppliedFunction af, Env env) {
        return switch (fn) {
            case LET -> LetChecker.check(this, af, env);
            case IF -> IfChecker.check(this, af, env);
            // ^Class(...) desugars to new(PackageableElementPtr, NewInstance); the inner node
            // carries the payload. ^$var(...) (a Variable receiver) is COPY-
            // with-update — the class is the variable's static type. Other
            // arities/shapes of `new` ride the generic path.
            case NEW -> {
                if (af.parameters().size() == 2
                        && af.parameters().get(1) instanceof NewInstance ni) {
                    // the non-copy branch routes through synth's NewInstance
                    // case so BOTH spellings share its arms — the direct
                    // NewChecker call bypassed the ^TDSNull() short-circuit
                    // (41 corpus tests: user-written ^TDSNull() desugars to
                    // new(...) and died at 'unknown class').
                    // COPY dispatch keys on the EMPTY className (the parser
                    // contract for ^$var(...)), NOT on the receiver being a
                    // Variable: the harness let-substitution legitimately
                    // replaces the receiver with the let's RHS expression
                    // (^$runtime(...) after substitute() carries the
                    // testRuntime() call), and checkCopy types any receiver.
                    yield ni.className().isEmpty()
                            ? NewChecker.checkCopy(this, af.parameters().get(0), ni, env)
                            : synth(ni, env);
                }
                yield applyGeneric(af, env);
            }
            case CAST, TO, TO_MANY -> CastChecker.check(this, af, env);
            // typeAsDeclared: the MAPPING-side type assertion (engine
            // parity — the engine types binding reads by the DECLARED
            // property and never casts the SQL). Shape (value, @Type);
            // types as the annotation, KEEPS the value's multiplicity,
            // lowers to the value unchanged (Scalars passthrough).
            case TYPE_AS_DECLARED, CAST_AS_DECLARED -> {
                Application ta = checkGeneric(af, env);
                if (ta.args().size() != 2
                        || !(ta.args().get(1) instanceof
                                com.legend.compiler.spec.typed.TypedTypeRef tr)) {
                    throw new TypeInferenceException(
                            af.function() + " expects (value, @Type)");
                }
                ExprType out = new ExprType(tr.target(),
                        ta.args().get(0).info().multiplicity());
                if ("castAsDeclared".equals(af.function())) {
                    // a WIRE-flagged cast: every TypedCast consumer rides
                    // it unchanged; only the lowering treats it specially
                    yield new com.legend.compiler.spec.typed.TypedCast(
                            ta.args().get(0), tr.target(), out, true);
                }
                var callees = model().findFunction(
                        "meta::legend::lite::typeAsDeclared");
                yield new com.legend.compiler.spec.typed.TypedNativeCall(
                        callees.get(0),
                        List.of(ta.args().get(0)), out);
            }
            case MATCH -> MatchChecker.check(this, af, env);
            case EVAL -> EvalChecker.check(this, af, env);
            case TDS -> TdsChecker.check(this, af, env);
            case SORT_BY -> SortChecker.sortBy(this, af, env, true);
            case SORT_BY_REVERSED -> SortChecker.sortBy(this, af, env, false);
            case GET_ALL -> GetAllChecker.check(this, af, env);
            case GET_ALL_FOR_EACH_DATE ->
                    GetAllChecker.checkForEachDate(this, af, env);
            case GET_ALL_VERSIONS, GET_ALL_VERSIONS_IN_RANGE ->
                    GetAllChecker.checkVersions(this, af, env);
            case FROM -> FromChecker.check(this, af, env);
            case WRITE -> WriteChecker.check(this, af, env);
            case FOLD -> FoldChecker.check(this, af, env);
            case NAVIGATE -> NavigateChecker.check(this, af, env);
            // legacyNavigate: the pre-map rule under the legacy bridge's
            // name, with the target's table rows spelled into the call.
            case LEGACY_NAVIGATE -> NavigateChecker.legacy(this, af, env);
            case GRAPH_FETCH -> GraphFetchChecker.graphFetch(this, af, env);
            case GRAPH_FETCH_CHECKED ->
                    GraphFetchChecker.graphFetchChecked(this, af, env);
            case SERIALIZE -> GraphFetchChecker.serialize(this, af, env);
            case OVER -> OverChecker.check(this, af, env);
            case SOURCE_URL -> SourceUrlChecker.check(this, af, env);
            case FLATTEN -> FlattenChecker.check(this, af, env);
            case PIVOT -> PivotChecker.check(this, af, env);
            case TABLE_REFERENCE -> TableReferenceChecker.check(this, af);
            case TABLE_TO_TDS -> TableReferenceChecker.checkTableToTds(this, af, env);
            case PROJECT -> ProjectChecker.check(this, af, env);
            case EXTEND -> ExtendChecker.check(this, af, env);
            case GROUP_BY -> GroupByChecker.check(this, af, env);
            case AGGREGATE -> AggregateChecker.check(this, af, env);
            case JOIN -> JoinChecker.check(this, af, env);
            case AS_OF_JOIN -> AsOfJoinChecker.check(this, af, env);
            case SORT -> SortChecker.check(this, af, env);
            case ASC -> SortChecker.sortInfo(this, af, env, true);
            case DESC -> SortChecker.sortInfo(this, af, env, false);
            case RENAME -> RenameChecker.check(this, af, env);
            case SELECT -> SelectChecker.check(this, af, env);
            case DISTINCT -> DistinctChecker.check(this, af, env);
            case CONCATENATE -> ConcatenateChecker.check(this, af, env);
            case LIMIT, TAKE -> SlicingChecker.limit(this, af, env);
            case DROP -> SlicingChecker.drop(this, af, env);
            case SLICE -> SlicingChecker.slice(this, af, env);
            case FILTER -> FilterChecker.check(this, af, env);
            case MAP -> MapChecker.check(this, af, env);
        };
    }

    /**
     * The one generic application rule (engine {@code ScalarChecker}): type the
     * arguments, resolve the overload against the registered signatures, and read
     * the output from the resolved return (§5) &mdash; emitting the plain call
     * node. Checkers whose non-structural overloads ride this path (collection
     * {@code sort}, non-{@code ^} {@code new}) call it directly.
     */
    TypedSpec applyGeneric(AppliedFunction af, Env env) {
        Application a = checkGeneric(af, env);
        if (requiresNormalization(a.chosen())) {
            return inlineNormalized(af, a.chosen(), env);
        }
        return emitCall(a.chosen(), a.args(), a.out());
    }

    /**
     * Engine {@code <<functionType.NormalizeRequiredFunction>>} doctrine: a
     * TDS-erased module function's body COMPUTES the plan (its schema
     * expressions read {@code .columns} facts and build colspecs), so it is
     * normalized away at its CALL SITE — β-substitute the raw arguments into
     * the parsed body, statically fold the schema vocabulary
     * ({@link StaticFold}), and type the result in the caller's env. The
     * gate is the stereotype, plus the TDS-erased helper shape those
     * functions call privately ({@code TDSColumn[*]} params —
     * {@code extendMatchColumns}): a signature over the schema-erasing
     * nominals cannot type standalone, only monomorphized.
     */
    private boolean requiresNormalization(TypedFunction f) {
        if (f.isNative() || f.body().isEmpty() || f.definition() == null) {
            return false;
        }
        boolean stereotyped = f.definition().stereotypes().stream()
                .anyMatch(s -> s.stereotypeName().equals("NormalizeRequiredFunction"));
        return stereotyped || f.parameters().stream()
                .anyMatch(p -> isSchemaErased(p.type()))
                || isSchemaErased(f.returnType());
    }

    private static boolean isSchemaErased(com.legend.compiler.element.type.Type t) {
        // a FUNCTION over the erased nominals is itself erased — a helper
        // returning Function<{TDSRow[1]->Boolean[1]}> exists only inlined
        // (bare or Function<{...}>-wrapped alike)
        com.legend.compiler.element.type.Type.FunctionType ft = asFunctionType(t);
        if (ft != null) {
            return ft.params().stream().anyMatch(p -> isSchemaErased(p.type()))
                    || isSchemaErased(ft.result().type());
        }
        String raw = switch (t) {
            case com.legend.compiler.element.type.Type.ClassType c -> c.fqn();
            case com.legend.compiler.element.type.Type.GenericType g -> g.rawFqn();
            default -> null;
        };
        return com.legend.compiler.element.type.PlatformTypes.TABULAR_DATA_SET.equals(raw)
                || com.legend.compiler.element.type.PlatformTypes.TDS_ROW.equals(raw)
                || "meta::pure::tds::TDSColumn".equals(raw)
                // column specs are PLAN vocabulary — a spec-building helper
                // (getCols():ColumnSpecification<T>[*]) exists only inlined.
                // The bare spellings appear because module signatures keep
                // the IMPORT-scoped name unresolved (corpus testSimple.pure
                // declares `ColumnSpecification<Person>` under an import) —
                // retire them when signature types resolve through imports.
                || "meta::pure::tds::ColumnSpecification".equals(raw)
                || "meta::pure::tds::BasicColumnSpecification".equals(raw)
                || "ColumnSpecification".equals(raw)
                || "BasicColumnSpecification".equals(raw)
                // aggregate specs are the same plan vocabulary (legacy
                // groupBy's agg(mapFn, aggFn) literals)
                || "meta::pure::functions::collection::AggregateValue".equals(raw)
                || "AggregateValue".equals(raw);
    }

    private final java.util.ArrayDeque<String> normalizing = new java.util.ArrayDeque<>();

    private TypedSpec inlineNormalized(AppliedFunction af, TypedFunction chosen, Env env) {
        String key = chosen.signatureKey();
        if (normalizing.contains(key)) {
            throw new TypeInferenceException("recursive NormalizeRequired function '"
                    + chosen.qualifiedName() + "' cannot be inlined ("
                    + String.join(" -> ", normalizing) + " -> " + key + ")");
        }
        LambdaFunction folded = SourceSubst.inlineLets(
                new LambdaFunction(List.of(),
                        chosen.body().orElseThrow()));
        if (folded == null) {
            throw new TypeInferenceException("NormalizeRequired function '"
                    + chosen.qualifiedName()
                    + "' has non-let intermediate statements — cannot inline");
        }
        java.util.Map<String, ValueSpecification> subst = new java.util.LinkedHashMap<>();
        for (int i = 0; i < chosen.parameters().size(); i++) {
            subst.put(chosen.parameters().get(i).name(), af.parameters().get(i));
        }
        // α-hygiene (the UserCallInliner rule at source level): the body's
        // lambda binders rename to fresh _nr<N> — a caller variable spelled
        // like a body binder (corpus: `let r = execute(...)` vs the body's
        // `filter(r:TDSRow[1]|…)`) must never be captured by the splice.
        ValueSpecification body = SourceSubst.substitute(
                alphaRename(folded.body().get(0)), subst);
        normalizing.push(key);
        try {
            return synth(new StaticFold(this, env).fold(body), env);
        } finally {
            normalizing.pop();
        }
    }

    /** A helper CALL returning a function value over the schema-erasing TDS
     * nominals ({@code getFilterLambda():Function<{TDSRow[1]->Boolean[1]}>})
     * expands to its lambda literal IN ARGUMENT POSITION — the literal then
     * types against the surrounding signature like any inline lambda (a
     * {@code TDSRow} annotation refines nothing; the concrete row wins).
     * A function VALUE over TDSRow can never unify with a row-bound type
     * variable, so un-expanded it is a guaranteed loud failure. */
    private AppliedFunction expandFunctionValuedHelperArgs(AppliedFunction af) {
        List<ValueSpecification> np = null;
        for (int i = 0; i < af.parameters().size(); i++) {
            if (!(af.parameters().get(i) instanceof AppliedFunction call)) {
                continue;
            }
            boolean erasedFn = functionCandidates(call).stream()
                    .filter(c -> c.parameters().size() == call.parameters().size())
                    .anyMatch(c -> {
                        Type.FunctionType ft = asFunctionType(c.returnType());
                        return ft != null && isSchemaErased(ft);
                    });
            if (!erasedFn) {
                continue;
            }
            ValueSpecification ex = rawSchemaErasedExpansion(call);
            if (ex == null) {
                continue;
            }
            if (np == null) {
                np = new ArrayList<>(af.parameters());
            }
            np.set(i, ex);
        }
        return np == null ? af : new AppliedFunction(af.function(), np);
    }

    /**
     * RAW β-expansion of a schema-erased helper call in a SPEC position
     * ({@code project(getCols())} — the col() literals must reach the
     * checker's SHAPE normalization, so typed inlining is too late).
     * Exactly one arity-matching NormalizeRequired candidate with a body
     * expands; anything else returns null and the checker's wall stands.
     */
    @com.legend.Nullable ValueSpecification rawSchemaErasedExpansion(ValueSpecification v) {
        if (!(v instanceof AppliedFunction af)) {
            return null;
        }
        List<TypedFunction> arityCands = functionCandidates(af).stream()
                .filter(c -> c.parameters().size() == af.parameters().size())
                .toList();
        // a NATIVE overload owns the call (concatenateTemporalTdsQueries:
        // the corpus re-definition is M3-reflective plan surgery; the
        // registered native is the platform's semantics) — never expand
        if (arityCands.stream().anyMatch(TypedFunction::isNative)) {
            return null;
        }
        List<TypedFunction> cands = arityCands.stream()
                .filter(this::requiresNormalization)
                .toList();
        if (System.getenv("LEGEND_LITE_RAW_EXPAND_TRACE") != null) {
            System.err.println("[raw-expand] " + af.function() + " cands="
                    + cands.size() + " all=" + functionCandidates(af).stream()
                            .map(c -> c.qualifiedName() + " ret="
                                    + c.returnType().typeName()).toList());
        }
        if (cands.size() != 1) {
            return null;
        }
        TypedFunction chosen = cands.get(0);
        LambdaFunction folded = SourceSubst.inlineLets(
                new LambdaFunction(List.of(),
                        chosen.body().orElseThrow()));
        if (folded == null) {
            return null;
        }
        java.util.Map<String, ValueSpecification> subst = new java.util.LinkedHashMap<>();
        for (int i = 0; i < chosen.parameters().size(); i++) {
            subst.put(chosen.parameters().get(i).name(), af.parameters().get(i));
        }
        return SourceSubst.substitute(
                alphaRename(folded.body().get(0)), subst);
    }

    private int nrFresh;

    /** Rename every lambda binder in an inlined body to a fresh {@code _nr<N>}
     * (outermost-first; occurrence substitution is shadow-aware, so inner
     * same-named binders keep their own scopes until their own rename). */
    private ValueSpecification alphaRename(ValueSpecification v) {
        return switch (v) {
            case LambdaFunction lf -> {
                java.util.Map<String, ValueSpecification> ren = new java.util.LinkedHashMap<>();
                List<com.legend.protocol.spec.Variable> params = new ArrayList<>(lf.parameters().size());
                for (com.legend.protocol.spec.Variable p : lf.parameters()) {
                    String fresh = "_nr" + nrFresh++;
                    ren.put(p.name(), new com.legend.protocol.spec.Variable(
                            fresh, p.type(), p.multiplicity()));
                    params.add(new com.legend.protocol.spec.Variable(
                            fresh, p.type(), p.multiplicity()));
                }
                yield new LambdaFunction(params, lf.body().stream()
                        .map(b -> alphaRename(SourceSubst.substitute(b, ren)))
                        .toList());
            }
            case AppliedFunction af2 -> new AppliedFunction(af2.function(),
                    af2.parameters().stream().map(this::alphaRename).toList(),
                    af2.candidateFqns());
            case com.legend.protocol.spec.AppliedProperty ap -> new com.legend.protocol.spec.AppliedProperty(
                    alphaRename(ap.receiver()), ap.property());
            case com.legend.protocol.spec.PureCollection pc -> new com.legend.protocol.spec.PureCollection(
                    pc.values().stream().map(this::alphaRename).toList());
            case com.legend.protocol.spec.ColSpec cs -> new com.legend.protocol.spec.ColSpec(cs.name(),
                    cs.function1() == null ? null : (LambdaFunction) alphaRename(cs.function1()),
                    cs.function2() == null ? null : (LambdaFunction) alphaRename(cs.function2()),
                    cs.alias(),
                    cs.args().stream().map(this::alphaRename).toList());
            case com.legend.protocol.spec.ColSpecArray ca -> new com.legend.protocol.spec.ColSpecArray(
                    ca.colSpecs().stream()
                            .map(c -> (com.legend.protocol.spec.ColSpec) alphaRename(c)).toList());
            default -> v.mapChildren(this::alphaRename);
        };
    }

    /**
     * Run the generic application rule without emitting a node &mdash; the CHECK
     * half of the check/emit split ({@link Application}). Calls with
     * <em>deferred</em> arguments take {@link #checkWithDeferred}.
     */
    Application checkGeneric(AppliedFunction af, Env env) {
        af = expandFunctionValuedHelperArgs(af);
        if (af.parameters().stream().anyMatch(Typer::deferredArg)) {
            return checkWithDeferred(af, env);
        }
        List<TypedSpec> args = new ArrayList<>(af.parameters().size());
        for (ValueSpecification p : af.parameters()) {
            args.add(synth(p, env));
        }
        List<ExprType> argTypes = args.stream().map(TypedSpec::info).toList();

        List<TypedFunction> candidates = functionCandidates(af);
        if (candidates.isEmpty()) {
            // C0.5a: zero candidates means the name is NOT IN THE CATALOG
            // (usually an unported platform function) — say so instead of
            // implying the model called something malformed
            throw new TypeInferenceException("unknown function '"
                    + af.function() + "' — no function of this name in the"
                    + " native or user catalog (unported platform function,"
                    + " or a misspelling)");
        }
        InferenceKernel.Resolution r = kernel.resolveOverload(candidates, argTypes);
        return new Application(r.chosen(), args,
                refineParseDate(r.chosen(), args, refineDecimalCarrier(r.chosen(), r.output())));
    }

    /**
     * parseDate over a LITERAL refines its abstract Date output to the
     * concrete kind the string's shape determines ('...T...' is a DateTime,
     * a bare date is a StrictDate) — real pure's parseDate returns the
     * written kind, and the abstract-Date root otherwise cannot tell a
     * midnight DateTime from a StrictDate at the wire.
     */
    private static ExprType refineParseDate(TypedFunction chosen, List<TypedSpec> args, ExprType out) {
        if (out.type() == com.legend.compiler.element.type.Type.Primitive.DATE
                && "meta::pure::functions::string::parseDate".equals(chosen.qualifiedName())
                && args.size() == 1
                && args.get(0) instanceof com.legend.compiler.spec.typed.TypedCString s) {
            String v = s.value().trim();
            if (v.matches("-?\\d{4,}-\\d{2}-\\d{2}[T ]\\d.*")) {
                return new ExprType(
                        com.legend.compiler.element.type.Type.Primitive.DATE_TIME,
                        out.multiplicity());
            }
            if (v.matches("-?\\d{4,}-\\d{2}-\\d{2}")) {
                return new ExprType(
                        com.legend.compiler.element.type.Type.Primitive.STRICT_DATE,
                        out.multiplicity());
            }
            return out;   // partial or exotic shapes keep the abstract Date
        }
        return out;
    }

    /** Build the call node for the chosen overload &mdash; the resolved callee rides the node, never a name. */
    static TypedSpec emitCall(TypedFunction chosen, List<TypedSpec> args, ExprType out) {
        return chosen.isNative()
                ? new TypedNativeCall(chosen, args, out)
                : new TypedUserCall(chosen, args, out);
    }

    /**
     * A call carrying <em>deferred</em> arguments &mdash; lambdas and mapped column
     * specifications ({@code ~alias:x|…}), whose types are not known until the
     * surrounding call is partly resolved (the bidirectional step, §3.4). So: type
     * the value args, pick the overload from them (plus the deferred args'
     * <em>syntactic shape</em>), solve its type variables, then type each deferred
     * slot against its now-concrete parameter &mdash; a lambda against its function
     * type (binding any unbound return variable, e.g. {@code map}'s {@code V}), a
     * mapped colspec against its {@code FuncColSpec<F,Z>} (binding {@code Z} from
     * the checked lambda bodies).
     */
    private Application checkWithDeferred(AppliedFunction af, Env env) {
        List<ValueSpecification> raw = af.parameters();
        List<TypedFunction> candidates = functionCandidates(af);
        List<TypedFunction> arity = candidates.stream()
                .filter(c -> c.parameters().size() == raw.size())
                .filter(c -> deferredShapesMatch(c, raw))
                .toList();
        if (arity.isEmpty()) {
            throw new TypeInferenceException("no overload of '" + af.function()
                    + "' matches " + raw.size() + " argument(s) of these shapes"
                    + (candidates.isEmpty() ? " (no candidates at all)"
                            : " — candidates: " + candidates.stream()
                                    .map(c -> c.qualifiedName() + "/"
                                            + c.parameters().size())
                                    .distinct().toList()));
        }

        TypedSpec[] typed = new TypedSpec[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            if (!deferredArg(raw.get(i))) {
                typed[i] = synth(raw.get(i), env);   // value args first
            }
        }

        // REAL Pure searches with rollback (FunctionExpressionProcessor):
        // when the best-scored candidate dies typing a DEFERRED slot
        // (lambda/colspec), the next candidate gets its turn — selection
        // previously COMMITTED on non-lambda args and a deferred-slot
        // mismatch was a hard failure even with a fitting overload next
        // in line (study #14; program meaning depended on source order).
        List<TypedFunction> ranked =
                selectRankedByPresentArgs(af.function(), arity, typed, raw);
        TypeInferenceException firstFailure = null;
        for (TypedFunction cand : ranked) {
            try {
                return bindDeferredAndBuild(cand, raw, typed.clone(), env);
            } catch (SchemaInvariantException invariant) {
                throw invariant;   // the program's defect, never a
                                   // candidate mismatch — no retry
            } catch (TypeInferenceException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        throw java.util.Objects.requireNonNull(firstFailure);
    }

    /** The post-selection phase: unify present args, type deferred slots
     *  against the candidate, resolve the output. Throws
     *  {@link TypeInferenceException} when the candidate cannot host the
     *  deferred arguments — the caller's retry loop moves on. */
    private Application bindDeferredAndBuild(TypedFunction chosen,
            List<ValueSpecification> raw, TypedSpec[] typed, Env env) {
        Bindings b = new Bindings();
        for (int i = 0; i < raw.size(); i++) {
            if (typed[i] != null) {
                kernel.unify(chosen.parameters().get(i).type(), typed[i].info().type(), b);
                kernel.unifyMult(chosen.parameters().get(i).multiplicity(),
                        typed[i].info().multiplicity(), typed[i].info().type(), b);
            }
        }

        for (int i = 0; i < raw.size(); i++) {
            if (typed[i] == null) {
                if (raw.get(i) instanceof LambdaFunction
                        || (isLambdaCollection(raw.get(i))
                                && chosen.parameters().get(i).type()
                                        instanceof Type.TypeVar)) {
                    if (chosen.parameters().get(i).type()
                            instanceof Type.TypeVar) {
                        // self-typable lambda against T: synthesize
                        // standalone, bind the variable to its type
                        typed[i] = synth(raw.get(i), env);
                        kernel.unify(chosen.parameters().get(i).type(),
                                typed[i].info().type(), b);
                        kernel.unifyMult(chosen.parameters().get(i).multiplicity(),
                                typed[i].info().multiplicity(),
                                typed[i].info().type(), b);
                        continue;
                    }
                    if (!(raw.get(i) instanceof LambdaFunction lam)) {
                        throw new IllegalStateException("typer bug: lambda"
                                + " collection against a non-variable"
                                + " non-function param slipped the shape gate");
                    }
                    typed[i] = typeLambda(lam, chosen.parameters().get(i).type(), b, env);
                } else if (isLambdaCollection(raw.get(i))) {
                    // pure [f] ≡ f in call position — each element types
                    // against the chosen signature's function parameter
                    // (the corpus's filter([t|...]) / project([λ..], names));
                    // a SINGLETON collapses to the bare lambda (downstream
                    // consumers dispatch on TypedLambda)
                    PureCollection pc = (PureCollection) raw.get(i);
                    List<TypedSpec> els = new ArrayList<>(pc.values().size());
                    for (ValueSpecification v : pc.values()) {
                        els.add(typeLambda((LambdaFunction) v,
                                chosen.parameters().get(i).type(), b, env));
                    }
                    typed[i] = els.size() == 1 ? els.get(0)
                            : new TypedCollection(els, new ExprType(
                                    els.get(0).info().type(),
                                    new Multiplicity.Bounded(els.size(), els.size())));
                } else if (genericRawIs(chosen.parameters().get(i).type(),
                        Pure.COL_SPEC_ARRAY)) {
                    // an empty colspec array chosen against a PLAIN
                    // ColSpecArray param types as an ordinary value (it
                    // deferred only because its flavor was parameter-
                    // determined) — unify like the value loop would have
                    typed[i] = synth(raw.get(i), env);
                    kernel.unify(chosen.parameters().get(i).type(),
                            typed[i].info().type(), b);
                    kernel.unifyMult(chosen.parameters().get(i).multiplicity(),
                            typed[i].info().multiplicity(), typed[i].info().type(), b);
                } else {
                    typed[i] = typeFuncColSpec(raw.get(i),
                            chosen.parameters().get(i).type(), b, env);
                }
            }
        }

        ExprType out = kernel.resolveOutput(chosen.returnType(), chosen.returnMultiplicity(), b);
        return new Application(chosen, List.of(typed), refineDecimalCarrier(chosen, out));
    }

    /**
     * Decimal-PRODUCING conversions refine their declared bare Decimal to
     * the carrier precision — the engine's Decimal(38,18); a refinement of
     * the registered signature's output, never a bypass of its checks.
     */
    private static ExprType refineDecimalCarrier(TypedFunction chosen, ExprType out) {
        if (out.type() == com.legend.compiler.element.type.Type.Primitive.DECIMAL
                && DECIMAL_CARRIER_PRODUCERS.contains(chosen.qualifiedName())) {
            return new ExprType(
                    new com.legend.compiler.element.type.Type.PrecisionDecimal(38, 18),
                    out.multiplicity());
        }
        return out;
    }

    private static final java.util.Set<String> DECIMAL_CARRIER_PRODUCERS = java.util.Set.of(
            "meta::pure::functions::string::parseDecimal",
            "meta::pure::functions::math::toDecimal");

    /** An argument whose typing must wait for the chosen signature: a lambda, or a
     * colspec carrying one. An EMPTY colspec array also defers — its flavor
     * (plain/Func/Agg) is nominal-only and the chosen parameter decides it
     * (legacy groupBy([], aggs, ids): a global aggregate's keys). */
    private static boolean deferredArg(ValueSpecification p) {
        return p instanceof LambdaFunction
                || isLambdaCollection(p)
                || (p instanceof ColSpec cs && cs.function1() != null)
                || (p instanceof ColSpecArray arr
                        && (arr.colSpecs().isEmpty()
                                || arr.colSpecs().stream()
                                        .anyMatch(c -> c.function1() != null)));
    }

    /** A NON-EMPTY collection literal of lambdas — {@code filter([t|...])} /
     * {@code project([t|...x, t|...y], names)}: pure's [f] ≡ f value
     * semantics in call position; each element types against the chosen
     * signature's function parameter. */
    private static boolean isLambdaCollection(ValueSpecification p) {
        return p instanceof PureCollection pc && !pc.values().isEmpty()
                && pc.values().stream().allMatch(v -> v instanceof LambdaFunction);
    }

    /**
     * Prefilter candidates by the deferred arguments' <em>syntactic shape</em>
     * (engine dispatches its colspec overloads the same way): a lambda needs a
     * function-typed parameter; {@code ~a:x|…} needs {@code FuncColSpec} (or
     * {@code AggColSpec} when it carries a reducer {@code function2}); the array
     * forms need the {@code …Array} classes. Value-argument scoring cannot see
     * this, since deferred slots are not yet typed.
     */
    private static boolean deferredShapesMatch(TypedFunction c, List<ValueSpecification> raw) {
        for (int i = 0; i < raw.size(); i++) {
            ValueSpecification p = raw.get(i);
            if (!deferredArg(p)) {
                continue;
            }
            Type t = c.parameters().get(i).type();
            boolean ok = switch (p) {
                // A SELF-TYPABLE lambda (zero-arg, or fully annotated)
                // also matches a bare type-variable param — it synthesizes
                // standalone and T binds to its function type
                // (evaluateAndDeactivate<T|m>(var:T[m]) over {|...}).
                case LambdaFunction lf -> isFunctionTyped(t)
                        || (t instanceof Type.TypeVar
                                && selfTypable(lf));
                // a collection of SELF-TYPABLE lambdas also matches a bare
                // type-variable param ([{|q1},{|q2}]->evaluateAndDeactivate())
                case PureCollection pc0
                        when t instanceof Type.TypeVar
                        && pc0.values().stream().allMatch(v ->
                                v instanceof LambdaFunction plf
                                        && selfTypable(plf)) -> true;
                case PureCollection ignored -> isFunctionTyped(t);
                case ColSpec cs -> genericRawIs(t,
                        cs.function2() != null ? Pure.AGG_COL_SPEC : Pure.FUNC_COL_SPEC);
                case ColSpecArray arr when arr.colSpecs().isEmpty() ->
                        genericRawIs(t, Pure.COL_SPEC_ARRAY)
                                || genericRawIs(t, Pure.FUNC_COL_SPEC_ARRAY)
                                || genericRawIs(t, Pure.AGG_COL_SPEC_ARRAY);
                case ColSpecArray arr -> genericRawIs(t,
                        arr.colSpecs().stream().anyMatch(x -> x.function2() != null)
                                ? Pure.AGG_COL_SPEC_ARRAY : Pure.FUNC_COL_SPEC_ARRAY);
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** A lambda literal that can type WITHOUT an expected signature:
     * zero-arg, or every parameter annotated (Typer's standalone arm). */
    private static boolean selfTypable(LambdaFunction lf) {
        return lf.parameters().isEmpty()
                || lf.parameters().stream().allMatch(pv -> pv.type() != null);
    }

    private static boolean isFunctionTyped(Type t) {
        return t instanceof Type.FunctionType
                || (t instanceof Type.GenericType g && g.arguments().size() == 1
                        && g.arguments().get(0) instanceof Type.FunctionType);
    }

    private static boolean genericRawIs(Type t, com.legend.model.ClassDefinition def) {
        return t instanceof Type.GenericType g && g.rawFqn().equals(def.qualifiedName());
    }

    /** Pick the best-scoring overload by its already-typed arguments (deferred slots are skipped). */
    private TypedFunction selectByPresentArgs(String name, List<TypedFunction> arity, TypedSpec[] typed) {
        return selectByPresentArgs(name, arity, typed, null);

    }

    /** Candidates best-score-first (stable — declaration order breaks
     *  ties, preserving first-max semantics for the winner); arity
     *  misfits filtered; empty = the same loud no-overload error. */
    private List<TypedFunction> selectRankedByPresentArgs(String name,
            List<TypedFunction> arity, TypedSpec[] typed,
            @com.legend.Nullable List<ValueSpecification> raw) {
        List<ExprType> argTypes = new ArrayList<>(typed.length);
        for (TypedSpec t : typed) {
            argTypes.add(t == null ? null : t.info());
        }
        record Scored(TypedFunction fn, long score, int declIdx) {
        }
        List<Scored> scored = new ArrayList<>();
        String arityRejection = null;
        for (int i = 0; i < arity.size(); i++) {
            TypedFunction c = arity.get(i);
            if (raw != null && !lambdaAritiesFit(c, raw, typed)) {
                if (arityRejection == null) {
                    arityRejection = lambdaArityMismatch(c, raw, typed);
                }
                continue;
            }
            scored.add(new Scored(c, kernel.scoreNonLambda(c, argTypes), i));
        }
        if (scored.isEmpty()) {
            throw new TypeInferenceException(
                    "no overload of '" + name + "' matches the argument types"
                            + (arityRejection != null
                                    ? " (" + arityRejection + ")" : ""));
        }
        scored.sort(java.util.Comparator
                .comparingLong(Scored::score).reversed()
                .thenComparingInt(Scored::declIdx));
        return scored.stream().map(Scored::fn).toList();
    }

    /** With {@code raw} supplied, a candidate whose function-typed
     * parameter cannot accept a deferred lambda argument's ARITY is
     * filtered before scoring — non-lambda args tie between the
     * {@code Function<{->T}>} / {@code Function<{P1->T}>} overload
     * families, and declaration-order first-max would otherwise pin the
     * wrong one (the executionPlan P1/P2 family). */
    private TypedFunction selectByPresentArgs(String name, List<TypedFunction> arity, TypedSpec[] typed,
            @com.legend.Nullable List<ValueSpecification> raw) {
        List<ExprType> argTypes = new ArrayList<>(typed.length);
        for (TypedSpec t : typed) {
            argTypes.add(t == null ? null : t.info());   // null = deferred slot, not yet typed
        }
        TypedFunction best = null;
        long bestScore = -1;
        String arityRejection = null;
        for (TypedFunction c : arity) {
            if (raw != null && !lambdaAritiesFit(c, raw, typed)) {
                if (arityRejection == null) {
                    arityRejection = lambdaArityMismatch(c, raw, typed);
                }
                continue;
            }
            long s = kernel.scoreNonLambda(c, argTypes);
            if (s > bestScore) {
                best = c;
                bestScore = s;
            }
        }
        if (best == null) {
            // when every candidate died on a deferred lambda's parameter
            // count, say THAT — the generic line hides the actual defect
            // from the caller (the engine-suite arity pin)
            throw new TypeInferenceException(
                    "no overload of '" + name + "' matches the argument types"
                            + (arityRejection != null
                                    ? " (" + arityRejection + ")" : ""));
        }
        return best;
    }

    /** Whether every DEFERRED lambda argument's parameter count fits the
     * candidate's function-typed parameter at that slot. TypeVar params
     * accept any lambda (self-typable); a non-function param facing a
     * lambda rejects the candidate. */
    private static boolean lambdaAritiesFit(TypedFunction c,
            List<ValueSpecification> raw, TypedSpec[] typed) {
        for (int i = 0; i < raw.size() && i < c.parameters().size(); i++) {
            if (typed[i] != null) {
                continue;
            }
            Type pt = c.parameters().get(i).type();
            if (pt instanceof Type.TypeVar) {
                continue;
            }
            Integer want;
            try {
                want = extractFunctionType(pt).params().size();
            } catch (TypeInferenceException e) {
                // a deferred LAMBDA against a non-function, non-variable
                // param can never type
                if (raw.get(i) instanceof LambdaFunction) {
                    return false;
                }
                continue;
            }
            if (raw.get(i) instanceof LambdaFunction lf
                    && lf.parameters().size() != want) {
                return false;
            }
            if (raw.get(i) instanceof PureCollection pc) {
                for (ValueSpecification v : pc.values()) {
                    if (v instanceof LambdaFunction lf2
                            && lf2.parameters().size() != want) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The first lambda-vs-function-type parameter-count mismatch that
     * made {@code lambdaAritiesFit} reject {@code c}, spelled for the
     * no-overload diagnostic ("lambda has 2 parameter(s) but the
     * function type expects 1"). */
    private static @com.legend.Nullable String lambdaArityMismatch(
            TypedFunction c, List<ValueSpecification> raw, TypedSpec[] typed) {
        for (int i = 0; i < raw.size() && i < c.parameters().size(); i++) {
            if (typed[i] != null
                    || c.parameters().get(i).type() instanceof Type.TypeVar) {
                continue;
            }
            int want;
            try {
                want = extractFunctionType(c.parameters().get(i).type())
                        .params().size();
            } catch (TypeInferenceException e) {
                continue;
            }
            LambdaFunction lf = raw.get(i) instanceof LambdaFunction l ? l
                    : raw.get(i) instanceof PureCollection pc
                            ? pc.values().stream()
                                    .filter(v -> v instanceof LambdaFunction l2
                                            && l2.parameters().size() != want)
                                    .map(v -> (LambdaFunction) v)
                                    .findFirst().orElse(null)
                            : null;
            if (lf != null && lf.parameters().size() != want) {
                return "lambda has " + lf.parameters().size()
                        + " parameter(s) but the function type expects " + want;
            }
        }
        return null;
    }

    /** Type a lambda argument against its function-type parameter, with type vars partly solved in {@code b}. */
    TypedSpec typeLambda(LambdaFunction lam, Type functionParamType, Bindings b, Env env) {
        Type.FunctionType ftype = extractFunctionType(functionParamType);
        if (ftype.params().size() != lam.parameters().size()) {
            throw new TypeInferenceException("lambda has " + lam.parameters().size()
                    + " parameter(s) but the function type expects " + ftype.params().size());
        }
        if (lam.body().size() != 1 && !lam.parameters().isEmpty()) {
            // A parameterized lambda's [let*, final] body FOLDS by
            // source-level let-inlining (pure lets are value bindings —
            // β-substitution is exact, and the single-expression result
            // drops nothing at lowering). Non-let intermediates stay loud.
            LambdaFunction folded = SourceSubst.inlineLets(lam);
            if (folded == null) {
                throw new TypeInferenceException(
                        "only single-expression lambdas are supported yet");
            }
            lam = folded;
        }

        Env lambdaScope = env;
        List<String> names = new ArrayList<>();
        List<Type.Param> scopeParams = new ArrayList<>();
        for (int i = 0; i < lam.parameters().size(); i++) {
            Type paramType = kernel.resolve(ftype.params().get(i).type(), b);   // T -> the solved element type
            Multiplicity paramMult = ftype.params().get(i).multiplicity();
            Variable pv = lam.parameters().get(i);
            // a SOURCE annotation refines a signature-side Any (real pure:
            // the declared annotation is authoritative for the lambda's
            // own scope — executionPlan's Function<{Any[1]->Any[*]}>
            // param family relies on it for {var:String[1]|...})
            if (pv.type() != null && paramType instanceof Type.ClassType ct
                    && "meta::pure::metamodel::type::Any".equals(ct.fqn())) {
                paramType = namedType(pv.type());
                if (pv.multiplicity() != null) {
                    paramMult = Multiplicity.from(pv.multiplicity());
                }
            }
            names.add(pv.name());
            scopeParams.add(new Type.Param(paramType, paramMult));
            lambdaScope = lambdaScope.with(pv.name(),
                    new ExprType(paramType, paramMult));
        }

        // ZERO-ARG multi-statement bodies: leading lets bind into scope
        // (real pure statement semantics; the typed lets stay as STATEMENTS
        // for the consumer's sequencing), the final expression is the value.
        List<TypedSpec> typedStmts = new ArrayList<>();
        for (int si = 0; si < lam.body().size() - 1; si++) {
            ValueSpecification st = lam.body().get(si);
            if (st instanceof AppliedFunction lf2
                    && lf2.function().equals("letFunction")
                    && lf2.parameters().size() == 2
                    && lf2.parameters().get(0) instanceof CString ln) {
                TypedSpec val = synth(lf2.parameters().get(1), lambdaScope);
                lambdaScope = lambdaScope.with(ln.value(), val.info());
                typedStmts.add(new com.legend.compiler.spec.typed.TypedLet(
                        ln.value(), val, val.info()));
                continue;
            }
            throw new TypeInferenceException("only trailing-expression lambda"
                    + " bodies are supported (a non-let intermediate statement)");
        }
        TypedSpec body = synth(lam.body().get(lam.body().size() - 1), lambdaScope);

        // An unbound return variable (map's V) is inferred from the body. A
        // solved return is resolved then CHECKED (subtype-friendly, bindings
        // untouched — the long-standing semantics; SchemaAlgebra always takes
        // this path, since resolve() owns return-position algebra). ONLY a
        // structured return still carrying free vars ({->Relation<T>[1]}: the
        // slot-join thunk) — where the resolve path could only throw — unifies
        // UNRESOLVED into b, so the body's shape SOLVES the vars and later
        // parameters (the cond lambda's T[1] rows) see the solution.
        Type retType = ftype.result().type();
        if (retType instanceof Type.TypeVar rv && !b.hasType(rv.name())) {
            b.bindType(rv.name(), body.info().type());
        } else if (retType instanceof Type.TypeVar rv
                && kernel.resolve(retType, b) instanceof Type.ClassType nil
                && nil.fqn().equals(com.legend.compiler.element.type.PlatformTypes.NIL)) {
            // The return variable was solved to Nil by a []-born argument
            // (fold's init): BOTTOM carries no constraint — the body's type
            // IS the solution (covariant upgrade; Nil vanishes, the same rule
            // as in collection LUBs and type-var accumulation).
            b.bindType(rv.name(), body.info().type());
        } else if (retType instanceof Type.SchemaAlgebra || !kernel.hasFreeTypeVars(retType, b)) {
            kernel.unify(kernel.resolve(retType, b), body.info().type(), new Bindings());
        } else {
            kernel.unify(retType, body.info().type(), b);
        }
        // The body's MULTIPLICITY must satisfy the declared return too — a many-valued
        // body cannot serve a to-one slot (engine rejects sortBy on a to-many key:
        // {T[1]->U[1]} with a [*] body is a type error, not a silent acceptance).
        // EXCEPT a NIL-typed body (println side effects: Nil[0] is the
        // bottom VALUE and conforms to any return slot — real pure
        // compiles rows->map(r|println(...)); corpus testWithFilterGroupBy).
        boolean nilBody = body.info().type()
                instanceof Type.ClassType nbc
                && com.legend.compiler.element.type.PlatformTypes.NIL
                        .equals(nbc.fqn());
        if (!nilBody) {
            kernel.unifyMult(ftype.result().multiplicity(), body.info().multiplicity(),
                    body.info().type(), b);
        }

        ExprType info = new ExprType(
                new Type.FunctionType(scopeParams,
                        new Type.Param(body.info().type(), body.info().multiplicity())),
                Multiplicity.Bounded.ONE);
        typedStmts.add(body);
        return new TypedLambda(names, List.copyOf(typedStmts), info);
    }

    /** Unwrap a {@code Function<{…}>} (or a bare {@code FunctionType}) parameter to its function type. */
    /** The function type a declared type carries — bare or Function<{...}>
     * wrapped — or null when it is not function-typed at all. */
    private static Type.@com.legend.Nullable FunctionType asFunctionType(Type t) {
        if (t instanceof Type.FunctionType ft) {
            return ft;
        }
        if (t instanceof Type.GenericType g && g.arguments().size() == 1
                && g.arguments().get(0) instanceof Type.FunctionType ft) {
            return ft;
        }
        return null;
    }

    static Type.FunctionType extractFunctionType(Type t) {
        if (t instanceof Type.FunctionType ft) {
            return ft;
        }
        if (t instanceof Type.GenericType g && g.arguments().size() == 1
                && g.arguments().get(0) instanceof Type.FunctionType ft) {
            return ft;
        }
        throw new TypeInferenceException("expected a function-typed parameter, got " + t.typeName());
    }

    /**
     * Type a mapped column specification against its {@code FuncColSpec<F,Z>} /
     * {@code FuncColSpecArray<F,Z>} parameter: each {@code alias:x|body} lambda is
     * checked against {@code F} (whose parameter is the already-bound source row /
     * element), and {@code Z} binds to the row the aliases + body types form. This
     * is the one place unification <em>drives the lambda typer</em> &mdash; the
     * output schema stays signature-computed ({@code Relation<Z>}, {@code T+Z}).
     */
    private TypedSpec typeFuncColSpec(ValueSpecification vs, Type formal, Bindings b, Env env) {
        if (!(formal instanceof Type.GenericType g) || g.arguments().size() < 2) {
            throw new TypeInferenceException("expected a mapped column-spec parameter, got "
                    + formal.typeName());
        }
        if (genericRawIs(formal, Pure.AGG_COL_SPEC) || genericRawIs(formal, Pure.AGG_COL_SPEC_ARRAY)) {
            return typeAggColSpec(vs, g, b, env);
        }
        Type.FunctionType f = extractFunctionType(g.arguments().get(0));
        List<ColSpec> specs = vs instanceof ColSpecArray arr ? arr.colSpecs() : List.of((ColSpec) vs);

        List<TypedFuncCol> cols = new ArrayList<>(specs.size());
        List<Type.Column> schema = new ArrayList<>(specs.size());
        for (ColSpec cs : specs) {
            if (cs.function1() == null) {
                throw new TypeInferenceException(
                        "~" + cs.name() + " needs a mapping expression (alias:x|…) here");
            }
            if (schema.stream().anyMatch(c -> c.name().equals(cs.name()))) {
                throw new SchemaInvariantException("duplicate column '" + cs.name() + "' in ~[…]");
            }
            TypedLambda lam = (TypedLambda) typeLambda(cs.function1(), f, b, env);
            Type.Param result = ((Type.FunctionType) lam.info().type()).result();
            cols.add(new TypedFuncCol(cs.name(), lam));
            schema.add(new Type.Column(cs.name(), result.type(), result.multiplicity()));
        }

        if (!(g.arguments().get(1) instanceof Type.TypeVar z)) {
            throw new TypeInferenceException("the column-spec schema slot must be a variable, got "
                    + g.arguments().get(1).typeName());
        }
        b.bindType(z.name(), new Type.RelationType(schema));
        Type solved = new Type.GenericType(g.rawFqn(), List.of(f, new Type.RelationType(schema)));
        return vs instanceof ColSpecArray
                ? new TypedFuncColSpecArray(cols, ExprType.one(solved))
                : new TypedFuncColSpec(cols.get(0), ExprType.one(solved));
    }

    /**
     * Type an aggregate column specification against {@code AggColSpec<F1,F2,R>} /
     * {@code AggColSpecArray<F1,F2,R>}: per column, the map lambda checks against
     * {@code F1 = {T[1]->K[0..1]}} (binding {@code K} from its body) and the reduce
     * lambda against {@code F2 = {K[*]->V[0..1]}}; the column's type is the reduce
     * body's. {@code K}/{@code V} solve in a <em>per-column copy</em> of the
     * bindings &mdash; the array signature shares them only syntactically, each
     * aggregate's value type is its own (engine compiles each colspec independently).
     * {@code R} binds in the parent for the enclosing {@code Z+R}/{@code T+R} output.
     */
    private TypedSpec typeAggColSpec(ValueSpecification vs, Type.GenericType g, Bindings b, Env env) {
        if (g.arguments().size() != 3) {
            throw new TypeInferenceException("an aggregate column-spec parameter needs <map, reduce, R>, got "
                    + g.typeName());
        }
        Type.FunctionType mapF = extractFunctionType(g.arguments().get(0));
        Type.FunctionType reduceF = extractFunctionType(g.arguments().get(1));
        List<ColSpec> specs = vs instanceof ColSpecArray arr ? arr.colSpecs() : List.of((ColSpec) vs);

        List<TypedAggCol> cols = new ArrayList<>(specs.size());
        List<Type.Column> schema = new ArrayList<>(specs.size());
        for (ColSpec cs : specs) {
            if (cs.function1() == null || cs.function2() == null) {
                throw new TypeInferenceException("~" + cs.name()
                        + " needs a map and a reduce expression (alias:x|…:y|…) here");
            }
            if (schema.stream().anyMatch(c -> c.name().equals(cs.name()))) {
                throw new SchemaInvariantException("duplicate column '" + cs.name() + "' in ~[…]");
            }
            Bindings local = b.copy();   // K/V are per-column (see javadoc)
            TypedLambda map = (TypedLambda) typeLambda(cs.function1(), mapF, local, env);
            TypedLambda reduce = (TypedLambda) typeLambda(cs.function2(), reduceF, local, env);
            Type.Param result = ((Type.FunctionType) reduce.info().type()).result();
            cols.add(new TypedAggCol(cs.name(), map, reduce, null, true));
            schema.add(new Type.Column(cs.name(), result.type(), result.multiplicity()));
        }

        if (!(g.arguments().get(2) instanceof Type.TypeVar r)) {
            throw new TypeInferenceException("the aggregate schema slot must be a variable, got "
                    + g.arguments().get(2).typeName());
        }
        b.bindType(r.name(), new Type.RelationType(schema));
        Type solved = new Type.GenericType(g.rawFqn(),
                List.of(mapF, reduceF, new Type.RelationType(schema)));
        return vs instanceof ColSpecArray
                ? new TypedAggColSpecArray(cols, ExprType.one(solved))
                : new TypedAggColSpec(cols.get(0), ExprType.one(solved));
    }

    // =====================================================================
    // Forms &mdash; the non-application ValueSpecification shapes
    // =====================================================================

    /**
     * PCT function-POINTER spellings carry the engine's mangled signature
     * tail ({@code tanh_Number_1__Float_1_}) — strip it and resolve the
     * plain name; the call's ACTUAL arguments pick the overload. A plain
     * name that resolves directly never demangles.
     */
    private List<TypedFunction> functionCandidates(String name) {
        List<TypedFunction> found = ctx.findFunction(name);
        if (!found.isEmpty()) {
            return found;
        }
        String base = SignatureMangle.stripTail(name);
        if (base != null) {
            // ARITY-VALIDATED: the tail encodes the parameter count, and a
            // demangle that lands on a different-arity function is a
            // MISS, not a redirect (compute_Step_2_ must never call
            // compute() — text-surgery audit §1.1 #4)
            int arity = SignatureMangle.tailArity(name);
            String ret = SignatureMangle.tailReturnTypeName(name);
            return ctx.findFunction(base).stream()
                    .filter(f -> f.parameters().size() == arity
                            && f.returnType().typeName().endsWith(
                                    String.valueOf(ret)))
                    .toList();
        }
        return found;
    }

    /**
     * Call-aware overload set: when the resolver recorded IMPORT-AMBIGUITY
     * candidates on the node (several imported packages define the simple
     * name), the overload set is the UNION across all of them — real
     * pure's function matching collects across imports and signature
     * scoring picks. Single-referent calls keep the plain name path.
     */
    private List<TypedFunction> functionCandidates(AppliedFunction af) {
        if (af.candidateFqns().isEmpty()) {
            return functionCandidates(af.function());
        }
        List<TypedFunction> union = new ArrayList<>();
        RuntimeException firstBroken = null;
        for (String fqn : af.candidateFqns()) {
            try {
                union.addAll(ctx.findFunction(fqn));
            } catch (RuntimeException e) {
                // an import candidate whose overloads are ALL signature-
                // broken (tolerant module): it cannot be meant — the
                // healthy candidates decide, exactly like a broken overload
                // inside one FQN. Surface it only if NOTHING is healthy.
                if (firstBroken == null) {
                    firstBroken = e;
                }
            }
        }
        if (union.isEmpty() && firstBroken != null) {
            throw firstBroken;
        }
        return union;
    }

    /**
     * A packageable-element reference used as a value &mdash; currently a class
     * reference (the {@code Person} in {@code Person.all()}), typed as
     * {@code Class<Person>[1]} so {@code getAll<T>(Class<T>[1]):T[*]} resolves to
     * {@code Person[*]} via the generic native path.
     */
    private TypedSpec classReference(PackageableElementPtr ref) {
        var cls = ctx.findClass(ref.fullPath());
        if (cls.isPresent()) {
            // The node carries the RESOLVED FQN — a bare name accepted by
            // the simple-name fallback must not leak downstream (the H
            // resolver's mapping bindings are FQN-keyed).
            String fqn = cls.get().qualifiedName();
            Type classOf = new Type.GenericType(Pure.CLASS.qualifiedName(),
                    List.of(new Type.ClassType(fqn)));
            return new TypedPackageableRef(fqn, ExprType.one(classOf));
        }
        // A bare ENUMERATION reference (STR_GeographicEntityType->toString())
        // is a value of Enumeration<E>[1] (real m3's enumeration metaclass).
        var en = ctx.findEnum(ref.fullPath());
        if (en.isPresent()) {
            String fqn = en.get().qualifiedName();
            Type enumOf = new Type.GenericType(Pure.ENUMERATION.qualifiedName(),
                    List.of(new Type.EnumType(fqn)));
            return new TypedPackageableRef(fqn, ExprType.one(enumOf));
        }
        // A DATABASE reference is a value of the store metaclass (real m3:
        // meta::relational::metamodel::Database) — the corpus's
        // testRuntime(db:Database[1]) overload family dispatches on it.
        // Database <: Any, so from/write's Any[1] parameters still accept it.
        if (ctx.isDatabase(ref.fullPath())) {
            return new TypedPackageableRef(ref.fullPath(), ExprType.one(
                    new Type.ClassType("meta::relational::metamodel::Database")));
        }
        // A MAPPING reference is a value of the mapping metaclass (real m3:
        // meta::pure::mapping::Mapping) — corpus helpers dispatch on it
        // (getModelChainRuntime(m:Mapping[1]); the Database precedent
        // above). Mapping <: Any keeps from/execute's Any[1] params fine.
        if (ctx.findMapping(ref.fullPath()).isPresent()) {
            return new TypedPackageableRef(ref.fullPath(), ExprType.one(
                    new Type.ClassType("meta::pure::mapping::Mapping")));
        }
        // An execution-context element (runtime/connection) is a value
        // of type Any[1] — exactly what from/write's signature parameters declare.
        if (ctx.isExecutionContextElement(ref.fullPath())) {
            return new TypedPackageableRef(ref.fullPath(), ExprType.one(InferenceKernel.anyType()));
        }
        // A FUNCTION REFERENCE used as a value (removeDuplicates(eq_Any_1__...))
        // ETA-EXPANDS: the reference becomes the lambda calling it — one
        // uniform function-value story, no new node kind. Only an
        // UNAMBIGUOUS (single-overload) target expands.
        List<TypedFunction> fns = functionCandidates(ref.fullPath());
        if (fns.size() > 1) {
            // a MANGLED id names ONE overload — the signature tail's
            // segment count (params + return) disambiguates (the corpus's
            // generateUsageFor metadata: groupBy_TabularDataSet_1__…_)
            int arity = SignatureMangle.tailArity(ref.fullPath());
            if (arity >= 0) {
                List<TypedFunction> byArity = fns.stream()
                        .filter(f2 -> f2.parameters().size() == arity)
                        .toList();
                if (byArity.size() == 1) {
                    fns = byArity;
                } else {
                    // a mangled id naming an overload we don't carry
                    // standalone (the legacy TDS groupBy the checker
                    // desugars at call sites): the REFERENCE is an opaque
                    // Function<Any> value — metadata like generateUsageFor
                    // holds it, invocation stays loud at its own site
                    return new TypedPackageableRef(ref.fullPath(),
                            ExprType.one(new Type.GenericType(
                                    "meta::pure::metamodel::function::Function",
                                    List.of(InferenceKernel.anyType()))));
                }
            }
        }
        if (fns.size() == 1) {
            TypedFunction fn = fns.get(0);
            List<String> params = new ArrayList<>(fn.parameters().size());
            List<TypedSpec> argRefs = new ArrayList<>(fn.parameters().size());
            List<Type.FunctionType.Param> ftParams = new ArrayList<>(fn.parameters().size());
            for (int i = 0; i < fn.parameters().size(); i++) {
                var fp = fn.parameters().get(i);
                String name = "_fr" + i;
                params.add(name);
                argRefs.add(new TypedVariable(name,
                        new ExprType(fp.type(), fp.multiplicity())));
                ftParams.add(new Type.FunctionType.Param(fp.type(), fp.multiplicity()));
            }
            ExprType out = new ExprType(fn.returnType(), fn.returnMultiplicity());
            TypedSpec body = Typer.emitCall(fn, argRefs, out);
            Type ft = new Type.FunctionType(ftParams,
                    new Type.FunctionType.Param(fn.returnType(), fn.returnMultiplicity()));
            return new TypedLambda(params, List.of(body), ExprType.one(ft));
        }
        // Semantically a RESOLUTION failure (an unresolvable name), even
        // though it surfaces during type-checking — typed for what it MEANS.
        throw new com.legend.error.ResolutionException("'" + ref.fullPath()
                + "' is not a known class, mapping, runtime, connection, or database"
                + (ref.fullPath().contains("::")
                        ? "" : " — user elements in a query need a fully qualified name"));
    }

    /** A collection literal {@code [a,b,c]}: element type = common supertype; multiplicity = exact count. */
    private TypedSpec collection(PureCollection coll, Env env) {
        List<TypedSpec> elements = new ArrayList<>(coll.values().size());
        for (ValueSpecification v : coll.values()) {
            TypedSpec e = synth(v, env);
            // pure has NO nested collections: [['a','b'],'c'] IS
            // ['a','b','c'] — a collection-valued element SPLICES into
            // the enclosing literal (real pure value semantics)
            if (e instanceof TypedCollection tc) {
                elements.addAll(tc.elements());
            } else {
                elements.add(e);
            }
        }
        Type elementType = elements.stream()
                .map(e -> e.info().type())
                .reduce(kernel::commonSupertype)
                // The empty collection [] types as Nil[0] — the BOTTOM type
                // (real pure), so it conforms to any expected element type
                // and vanishes in LUBs: if(c, {|Status}, {|[]}) is
                // Status[0..1], not Any.
                .orElseGet(() -> new Type.ClassType(com.legend.compiler.element.type.PlatformTypes.NIL));
        Multiplicity mult = new Multiplicity.Bounded(elements.size(), elements.size());
        return new TypedCollection(elements, new ExprType(elementType, mult));
    }

    /**
     * Object-graph property access {@code $source.property}: type the receiver
     * (which must be a class), look up the property's signature via
     * {@link ModelContext#findProperty} (which walks inheritance and
     * association-injected properties), and <em>compose</em> the receiver's
     * multiplicity with the property's along the path.
     */
    /** The relation's column NAMES or pure TYPE NAMES as a static string collection. */
    private static TypedSpec columnsMeta(Type.RelationType rt, boolean typeNames) {
        ExprType one = ExprType.one(Type.Primitive.STRING);
        List<TypedSpec> items = new java.util.ArrayList<>(rt.columns().size());
        for (Type.RelationType.Column c : rt.columns()) {
            String v = typeNames ? simpleTypeName(c.type()) : c.name();
            items.add(new com.legend.compiler.spec.typed.TypedCString(v, one));
        }
        return new com.legend.compiler.spec.typed.TypedCollection(items,
                new ExprType(Type.Primitive.STRING,
                        new com.legend.compiler.element.type.Multiplicity.Bounded(
                                items.size(), items.size())));
    }

    /** Pure's simple type name for a column type (String, Integer, Date...). */
    private static String simpleTypeName(Type t) {
        String qn = t.typeName();
        int cut = qn.lastIndexOf("::");
        return cut < 0 ? qn : qn.substring(cut + 2);
    }

    /** Surrounding double quotes are SPELLING, not identity, for the
     * quote-fallback column match (both sides normalize). */
    private static String stripColQuotes(String n) {
        return n.length() >= 2 && n.startsWith("\"") && n.endsWith("\"")
                ? n.substring(1, n.length() - 1) : n;
    }

    private TypedSpec accessProperty(AppliedProperty ap, Env env) {
        // TDS COLUMN METADATA — engine TabularDataSet.columns.name/.type.
        // Column names and pure type names are STATIC FACTS of the typed
        // relation (no execution): they fold to string collections here.
        if (ap.receiver() instanceof AppliedProperty inner
                && inner.property().equals("columns")
                && (ap.property().equals("name") || ap.property().equals("type"))) {
            TypedSpec rel = synth(inner.receiver(), env);
            if (rel.info().type() instanceof Type.RelationType rt) {
                return columnsMeta(rt, ap.property().equals("type"));
            }
        }
        // .columns.documentation — col()'s optional metadata (TDSColumn
        // .documentation is String[0..1]: undocumented columns FLATTEN
        // away). A static fact of the PROJECT node, like name/type above.
        if (ap.receiver() instanceof AppliedProperty inner2
                && inner2.property().equals("columns")
                && ap.property().equals("documentation")) {
            TypedSpec rel = synth(inner2.receiver(), env);
            TypedSpec un = rel;
            // column metadata is invariant under ROW ops — walk through
            // from() rescopes and relation-in/relation-out wrappers
            // (at/toOne/first — the Result-envelope peel) to the project
            boolean walked = true;
            while (walked) {
                walked = false;
                if (un instanceof com.legend.compiler.spec.typed.TypedFrom f) {
                    un = f.source();
                    walked = true;
                } else if (un instanceof TypedNativeCall w
                        && !w.args().isEmpty()
                        && w.args().get(0).info().type()
                                instanceof Type.RelationType) {
                    un = w.args().get(0);
                    walked = true;
                }
            }
            if (rel.info().type() instanceof Type.RelationType) {
                if (un instanceof com.legend.compiler.spec.typed.TypedProject tp) {
                    return tp.docsFold();
                }
                // an ENVELOPE read ($result.values->at(0)...): the project
                // is only visible after the K-side splice (G-half) — emit
                // the identity-typed MARKER the splice hook resolves (the
                // .rows-marker discipline, audit 19d B2)
                return new com.legend.compiler.spec.typed.TypedPropertyAccess(
                        rel, "columns.documentation",
                        new ExprType(Type.Primitive.STRING,
                                com.legend.compiler.element.type.Multiplicity
                                        .Bounded.ZERO_MANY));
            }
        }
        TypedSpec source = synth(ap.receiver(), env);
        if (source.info().type() instanceof Type.RelationType rt2) {
            // TDS surface over relation values (engine TabularDataSet):
            // .rows IS the relation viewed as its row collection; bare
            // .columns is the column-name collection (assertSize targets)
            if (ap.property().equals("rows")) {
                // the relation IS its row collection — but the node SURVIVES
                // as an identity-typed MARKER: the statement executor's
                // result frame must tell `$r.values.rows->at(k)` (a REAL row
                // index) from `$r.values->at(k)` (the Result envelope, k=0
                // only) — erasing here made the two spellings collide (audit
                // 19d B2). The K-side splice hook erases the marker after
                // disambiguation; no other consumer sees it.
                return new com.legend.compiler.spec.typed.TypedPropertyAccess(
                        source, "rows", source.info());
            }
            if (ap.property().equals("values")) {
                // On a ROW VARIABLE ($r inside map/filter): TDSRow.values =
                // the row's CELLS in column order, statically enumerable.
                // On a RELATION value ($tds.rows.values / ->at(0).values):
                // identity — the relation's wire flatten IS row-major cell
                // order. (The Result-ENVELOPE .values never reaches the
                // Typer: the test driver peels it at substitution.)
                if (source instanceof com.legend.compiler.spec.typed.TypedVariable) {
                    // Row-var cells are TYPED per-column reads (at(N) and
                    // typed compares keep their kinds); print consumers
                    // (makeString/joinStrings) stringify at LOWERING, which
                    // also bypasses the Any-JSON carrier's quoting.
                    List<TypedSpec> cells = new java.util.ArrayList<>(rt2.columns().size());
                    Type elem = null;
                    boolean mixed = false;
                    for (Type.RelationType.Column c : rt2.columns()) {
                        cells.add(new com.legend.compiler.spec.typed.TypedPropertyAccess(
                                source, c.name(),
                                new ExprType(c.type(), c.multiplicity())));
                        if (elem == null) {
                            elem = c.type();
                        } else if (!elem.equals(c.type())) {
                            mixed = true;
                        }
                    }
                    Type collElem = mixed || elem == null
                            ? new Type.ClassType(
                                    com.legend.compiler.element.type.PlatformTypes.ANY)
                            : elem;
                    return new com.legend.compiler.spec.typed.TypedCollection(cells,
                            new ExprType(collElem,
                                    new com.legend.compiler.element.type.Multiplicity.Bounded(
                                            cells.size(), cells.size())));
                }
                return source;
            }
            if (ap.property().equals("columns")) {
                return columnsMeta(rt2, false);
            }
        }
        // a zero-arg DERIVED read IS a call of its externalized body —
        // route and β-inline so downstream sees plain navigation
        if (source.info().type() instanceof Type.ClassType ct
                && ctx.findProperty(ct.fqn(), ap.property()).orElse(null)
                        instanceof Property.Derived d
                && d.parameters().isEmpty()) {
            // AUTO-MAP (real pure — map.pure grammarDoc: "map is auto
            // generated when the . operator is used to access a property
            // value on a element of multiplicity different from [1]" —
            // that INCLUDES [0..1], audit 22a H2: β-inlining a NON-STRICT
            // derived body over a possibly-empty receiver manufactures a
            // value where pure yields empty). Only an exactly-[1]
            // receiver β-inlines directly.
            boolean exactlyOne = source.info().multiplicity()
                    instanceof com.legend.compiler.element.type
                            .Multiplicity.Bounded b1
                    && b1.lower() == 1 && b1.upper() != null
                    && b1.upper() == 1;
            if (!exactlyOne && source.info().multiplicity().isMany()) {
                return synth(new AppliedFunction("map", List.of(ap.receiver(),
                        new LambdaFunction(
                                List.of(new Variable("_am0")),
                                List.of(new AppliedProperty(
                                        new Variable("_am0"), ap.property()))))),
                        env);
            }
            if (!exactlyOne) {
                // [0..1] receiver: β-inline ONLY when the derived body is
                // provably STRICT in $this (SQL null propagation then
                // equals pure's auto-map — audit 22a H2). A body outside
                // the strict whitelist would manufacture a value over an
                // empty receiver — loud wall. (A presence-guarded
                // if/isEmpty spelling was tried and REVERTED: its
                // emptiness test materialized through a DIFFERENT join
                // instance than the value read — wrong values,
                // testQualifierWithInThroughJoin.)
                if (!derivedBodyStrictInThis(d)) {
                    throw new TypeInferenceException("derived property '"
                            + ap.property() + "' over a [0..1] receiver has"
                            + " a body outside the null-strict whitelist —"
                            + " empty-receiver semantics needs the"
                            + " presence-guarded emission (roadmap)");
                }
                // strict body: fall through to the β-inline below
            }
            return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(),
                    List.of(ap.receiver())), env);
        }
        // the AllVersions PROPERTY spelling (no parens): a version-sweep
        // navigation — normalized to the same TypedMilestonedAccess the
        // call spelling produces, so every downstream layer sees ONE shape
        if (source.info().type() instanceof Type.ClassType ctv
                && ap.property().endsWith("AllVersions")
                // a DECLARED property of that exact name wins — never
                // shadowed by the generated spelling (audit 10)
                && ctx.findProperty(ctv.fqn(), ap.property()).isEmpty()) {
            String base = ap.property().substring(0,
                    ap.property().length() - "AllVersions".length());
            var bp = ctx.findProperty(ctv.fqn(), base).orElse(null);
            String tFqn = bp != null && bp.type() instanceof Type.ClassType bct
                    ? bct.fqn() : null;
            if (tFqn != null && com.legend.compiler.element.Temporal
                    .strategyOf(ctx, tFqn) != null) {
                var mbp = java.util.Objects.requireNonNull(bp, "bp");
                return new com.legend.compiler.spec.typed.TypedMilestonedAccess(
                        source, base, List.of(), true,
                        new ExprType(mbp.type(),
                                com.legend.compiler.element.type.Multiplicity
                                        .Bounded.ZERO_MANY));
            }
        }
        // The member is either a class property ($obj.prop) or a relation column ($row.col).
        String relColName = null;
        ExprType member = switch (source.info().type()) {
            case Type.ClassType ct -> {
                Property prop = ctx.findProperty(ct.fqn(), ap.property()).orElse(null);
                if (prop == null) {
                    // real pure GENERATES the milestoning member surface
                    // (businessDate/processingDate, the milestoning struct
                    // and its members) — ONE registry, shared with graph
                    // trees (Temporal.generatedMember)
                    ExprType gen = com.legend.compiler.element.Temporal
                            .generatedMember(ctx, ct.fqn(), ap.property());
                    if (gen != null) {
                        yield gen;
                    }
                    // real M3: Any.elementOverride surfaces on every class
                    // (the corpus KeyInformation guard); folded to empty
                    // below — Any itself stays property-FREE (its shape is
                    // load-bearing for the struct/variant carrier)
                    if (ap.property().equals("elementOverride")) {
                        yield new ExprType(new Type.ClassType(
                                com.legend.builtin.Pure.ELEMENT_OVERRIDE
                                        .qualifiedName()),
                                Multiplicity.Bounded.ZERO_ONE);
                    }
                    throw new TypeInferenceException("class " + ct.fqn()
                            + " has no property '" + ap.property() + "'");
                }
                yield new ExprType(prop.type(), prop.multiplicity());
            }
            // A PARAMETERIZED class receiver (Pair<Integer,String>.first): the
            // property's declared type is written in the class's type parameters
            // — instantiate them at the receiver's arguments (positional, real
            // pure's generic instantiation).
            case Type.GenericType g -> {
                var cls = ctx.findClass(g.rawFqn()).orElseThrow(() -> new TypeInferenceException(
                        "unknown class '" + g.rawFqn() + "'"));
                Property prop = ctx.findProperty(g.rawFqn(), ap.property()).orElseThrow(() ->
                        new TypeInferenceException("class " + g.rawFqn()
                                + " has no property '" + ap.property() + "'"));
                if (cls.typeParameters().size() != g.arguments().size()) {
                    throw new TypeInferenceException("class " + g.rawFqn() + " declares "
                            + cls.typeParameters().size() + " type parameter(s) but the receiver "
                            + g.typeName() + " supplies " + g.arguments().size());
                }
                Bindings b = new Bindings();
                for (int i = 0; i < cls.typeParameters().size(); i++) {
                    b.bindType(cls.typeParameters().get(i), g.arguments().get(i));
                }
                yield new ExprType(kernel.resolve(prop.type(), b), prop.multiplicity());
            }
            case Type.RelationType rel -> {
                // QUOTE-BEARING column identity (the pivot rule's sibling):
                Type.Column col = relationColumn(rel, ap.property());
                relColName = col.name();
                yield new ExprType(col.type(), col.multiplicity());
            }
            default -> throw new TypeInferenceException("cannot access '" + ap.property()
                    + "' on " + source.info().type().typeName());
        };
        Multiplicity mult = compose(source.info().multiplicity(), member.multiplicity());
        if (ap.property().equals("elementOverride")   // M3: never
                && source.info().type() instanceof Type.ClassType) {
            return new com.legend.compiler.spec.typed.TypedCollection(
                    List.of(), new ExprType(member.type(),
                            Multiplicity.Bounded.ZERO_ONE));
        }
        return new TypedPropertyAccess(source,
                relColName != null ? relColName : ap.property(),
                new ExprType(member.type(), mult));
    }

    /** Store-declared column lookup: a quoted "FIRST NAME" carries its
     * quotes as identity — exact match wins, then the quote-stripped
     * fallback; the access adopts the column's own spelling (task #78). */
    private static Type.Column relationColumn(Type.RelationType rel,
            String name) {
        return rel.columns().stream()
                .filter(c -> c.name().equals(name)).findFirst()
                .orElseGet(() -> rel.columns().stream()
                        .filter(c -> stripColQuotes(c.name())
                                .equals(stripColQuotes(name)))
                        .findFirst()
                        .orElseThrow(() -> new TypeInferenceException(
                                "relation has no column '" + name + "'")));
    }

    /**
     * Multiplicity composition along a navigation path: {@code [a..b] . [c..d] =
     * [a*c .. b*d]} (an unbounded upper on either side stays unbounded). So a
     * {@code [*]} hop makes everything after it {@code [*]}, and an optional hop
     * makes the result optional.
     */
    private static Multiplicity compose(Multiplicity outer, Multiplicity inner) {
        if (outer instanceof Multiplicity.Bounded a && inner instanceof Multiplicity.Bounded b) {
            int lower = a.lower() * b.lower();
            Integer upper = (a.upper() == null || b.upper() == null) ? null : a.upper() * b.upper();
            return new Multiplicity.Bounded(lower, upper);
        }
        return inner;   // multiplicity variables do not occur on object-graph paths
    }

    /** An enum value reference {@code Kind.VALUE}: both the enumeration and the value must exist. */
    private TypedSpec enumValue(EnumValue ev) {
        if (System.getenv("LL_TDG_DEBUG") != null
                && ev.fullPath().contains("DatabaseType")) {
            System.err.println("[tdg-debug] enumValue fqn=" + ev.fullPath()
                    + " found=" + ctx.findEnum(ev.fullPath()).isPresent());
        }
        // Enum.VALUE and <dbElement>.property parse identically — a
        // DATABASE element on the left is store-METAMODEL property
        // access (db.schemas, the typeInference walk surface)
        if (ctx.findEnum(ev.fullPath()).isEmpty()
                && ctx.findDatabase(ev.fullPath()).isPresent()) {
            String dbCls = "meta::relational::metamodel::Database";
            var dbRef = new com.legend.compiler.spec.typed
                    .TypedPackageableRef(ev.fullPath(),
                            ExprType.one(new Type.ClassType(dbCls)));
            var pd = ctx.findProperty(dbCls, ev.value()).orElseThrow(
                    () -> new TypeInferenceException("class Database has"
                            + " no property '" + ev.value() + "'"));
            return new TypedPropertyAccess(dbRef, ev.value(),
                    new ExprType(pd.type(), pd.multiplicity()));
        }
        var en = ctx.findEnum(ev.fullPath()).orElseThrow(() -> new TypeInferenceException(
                "unknown enumeration '" + ev.fullPath() + "'"));
        if (!en.values().contains(ev.value())) {
            throw new TypeInferenceException("enumeration " + ev.fullPath()
                    + " has no value '" + ev.value() + "'");
        }
        return new TypedEnumValue(ev.fullPath(), ev.value(),
                ExprType.one(new Type.EnumType(ev.fullPath())));
    }

    /**
     * A {@code @Type} annotation used as a value: resolved to its target type and
     * typed as a <em>prototype value of that type</em> ({@code target[1]}) &mdash;
     * real Pure's convention, so {@code cast<T|m>(Any[m], type:T[1]):T[m]} and the
     * {@code to}/{@code toMany} signatures bind their target variable from this
     * value on the plain generic path (see {@link TypedTypeRef}).
     */
    private TypedSpec typeRef(TypeAnnotation ta) {
        Type target = annotationType(ta);
        return new TypedTypeRef(target, ExprType.one(target));
    }

    private Type annotationType(TypeAnnotation ta) {
        return switch (ta) {
            case TypeAnnotation.Named n -> namedType(n.type());
            // A relation target is the BARE row-struct — the computed-value form (G-α),
            // so cast(@Relation<(…)>) yields the same representation every relation op emits.
            case TypeAnnotation.RelationShape rs -> relationShapeType(rs);
            case TypeAnnotation.Wildcard ignored -> throw new TypeInferenceException(
                    "the ? wildcard is only legal as a column type inside @Relation<(…)>");
        };
    }

    /**
     * A named type reference used in a value position ({@code @Integer},
     * {@code t:Person[1]|…} branch/parameter declarations). Names are FQN-resolved
     * by NameResolver in the full pipeline; for primitive short names (the prelude)
     * we fall back to the fixed primitive package, so direct query checking
     * ({@code @Integer}) works without an import scope. Package-private: the
     * checkers that read declared types ({@code match} branches, {@code eval}
     * lambda params) resolve through this single point.
     */
    /** Strictness = EMPTY-PRESERVING composition over at least one $this
     * read. The BANNED set is exactly the constructs that produce a
     * NON-EMPTY value from an EMPTY input (conditionals, emptiness
     * tests, reducers over possibly-empty collections); plain property
     * chains, scalar natives and empty-preserving collection ops
     * (filter/map/toOne/first...) propagate emptiness in SQL as null —
     * pure's auto-map result. A literal-only body has no $this read and
     * fails the sawThis requirement (the manufactured-constant case,
     * audit 22a H2). Unknown node kinds are conservatively non-strict. */
    private static final java.util.Set<String> EMPTY_MANUFACTURING_FNS =
            java.util.Set.of("if", "match", "isEmpty", "isNotEmpty",
                    "coalesce", "orElse", "defaultIfEmpty", "size", "count",
                    "sum", "average", "mean", "min", "max", "joinStrings",
                    "makeString", "isDistinct", "exists", "forAll",
                    // in() lowers COALESCE(..., false) — total like pure's,
                    // so it is strict-safe for the derived [0..1] inline
                    "contains");

    private boolean derivedBodyStrictInThis(Property.Derived d) {
        var fns = ctx.findFunction(d.bodyFunctionFqn());
        if (fns.size() != 1 || fns.get(0).body().isEmpty()
                || fns.get(0).body().get().size() != 1) {
            return false;
        }
        int flags = strictScan(fns.get(0).body().get().get(0));
        return (flags & 1) != 0 && (flags & 2) == 0;   // sawThis && !nonStrict
    }

    /** bit 0 = saw a $this read; bit 1 = saw a non-strict construct. */
    private static int strictScan(ValueSpecification n) {
        return switch (n) {
            case Variable v -> "this".equals(v.name()) ? 1 : 0;
            case AppliedProperty ap2 -> strictScan(ap2.receiver());
            case AppliedFunction af2 -> {
                String simple = af2.function()
                        .substring(af2.function().lastIndexOf(':') + 1);
                int acc = EMPTY_MANUFACTURING_FNS.contains(simple) ? 2 : 0;
                for (ValueSpecification p2 : af2.parameters()) {
                    acc |= strictScan(p2);
                }
                yield acc;
            }
            case LambdaFunction lf2 -> {
                int acc = 0;
                for (ValueSpecification b2 : lf2.body()) {
                    acc |= strictScan(b2);
                }
                yield acc;
            }
            case PureCollection pc2 -> {
                int acc = 0;
                for (ValueSpecification e2 : pc2.values()) {
                    acc |= strictScan(e2);
                }
                yield acc;
            }
            case com.legend.protocol.spec.PackageableElementPtr ignored -> 0;
            case com.legend.protocol.spec.EnumValue ignored -> 0;
            case CString ignored -> 0;
            case com.legend.protocol.spec.CInteger ignored -> 0;
            case com.legend.protocol.spec.CFloat ignored -> 0;
            case com.legend.protocol.spec.CDecimal ignored -> 0;
            case com.legend.protocol.spec.CBoolean ignored -> 0;
            default -> 2;   // unknown construct: conservatively non-strict
        };
    }

    Type namedType(TypeExpression te) {
        // GENERIC annotations (@Pair<String, Integer>): the base resolves
        // like a NameRef; arguments resolve recursively.
        if (te instanceof TypeExpression.Generic g) {
            Type base = namedType(new TypeExpression.NameRef(g.name()));
            java.util.List<Type> args = g.arguments().stream()
                    .map(this::namedType).toList();
            String fqn = base instanceof Type.ClassType ct ? ct.fqn()
                    : base instanceof Type.GenericType gt ? gt.rawFqn() : null;
            if (fqn == null) {
                throw new TypeInferenceException(
                        "generic annotation over a non-class type: " + g.name());
            }
            return new Type.GenericType(fqn, args);
        }
        // FUNCTION-TYPE annotations (f:Function<{T[1]->R[*]}>[1] spelled
        // structurally — domainManagement/tds postprocessor library
        // params): same conversion TypeClassifier applies to signatures
        if (te instanceof TypeExpression.FunctionType ft) {
            java.util.List<com.legend.compiler.element.type.Type.Param> ps =
                    new java.util.ArrayList<>(ft.parameters().size());
            for (TypeExpression.TypedParameter tp : ft.parameters()) {
                ps.add(new com.legend.compiler.element.type.Type.Param(
                        namedType(tp.type()),
                        com.legend.compiler.element.type.Multiplicity
                                .from(tp.multiplicity())));
            }
            return new Type.FunctionType(ps,
                    new com.legend.compiler.element.type.Type.Param(
                            namedType(ft.result().type()),
                            com.legend.compiler.element.type.Multiplicity
                                    .from(ft.result().multiplicity())));
        }
        if (!(te instanceof TypeExpression.NameRef nr)) {
            throw new TypeInferenceException(
                    "unsupported type annotation form: " + te.getClass().getSimpleName());
        }
        String name = nr.name();
        // The legacy TDS surface: a NOMINAL — the value level is the
        // relation carrier (CastChecker treats cast(@TabularDataSet) over
        // a relation as a schema-preserving assertion). The EXACT FQN wins
        // outright; the BARE name is a fallback AFTER user types (audit
        // 22b LOW: a model class named TabularDataSet must not be
        // shadowed — prelude-fallback ordering).
        if (com.legend.compiler.element.type.PlatformTypes.TABULAR_DATA_SET
                .equals(name)) {
            return new Type.GenericType(
                    com.legend.compiler.element.type.PlatformTypes.TABULAR_DATA_SET,
                    List.of());
        }
        return ctx.findType(name)
                .or(() -> "TabularDataSet".equals(name)
                        ? Optional.of((Type) new Type.GenericType(
                                com.legend.compiler.element.type.PlatformTypes
                                        .TABULAR_DATA_SET, List.of()))
                        : Optional.empty())
                .or(() -> name.contains("::")
                        ? Optional.empty()
                        : ctx.findType("meta::pure::metamodel::type::" + name)
                                .or(() -> ctx.findType(Pure.VARIANT_PKG + "::" + name)))
                .orElseThrow(() -> new TypeInferenceException(
                        "unknown type '" + name + "' in @" + name));
    }

    /** {@code @Relation<(name:Type[m], …)>}: each column resolves recursively; multiplicity defaults to [1]. */
    private Type relationShapeType(TypeAnnotation.RelationShape rs) {
        List<Type.Column> cols = new ArrayList<>(rs.columns().size());
        for (TypeAnnotation.RelationShape.Column c : rs.columns()) {
            if (c.name() == null || c.type() instanceof TypeAnnotation.Wildcard) {
                throw new TypeInferenceException(
                        "wildcard columns in @Relation<(…)> are not implemented yet");
            }
            Multiplicity m = c.multiplicity() == null
                    ? Multiplicity.Bounded.ONE : Multiplicity.from(c.multiplicity());
            cols.add(new Type.Column(c.name(), annotationType(c.type()), m));
        }
        return new Type.RelationType(cols);
    }

    /** Date-literal precision: year/year-month = Date; a full day = StrictDate; any time part = DateTime. */
    private static Type dateType(PureDateLiteral lit) {
        PureDateLiteral.Precision p = lit.precision();
        return p.atLeast(PureDateLiteral.Precision.HOUR) ? Type.Primitive.DATE_TIME
                : p == PureDateLiteral.Precision.DAY ? Type.Primitive.STRICT_DATE
                : Type.Primitive.DATE;
    }

    /** A bare {@code ~col}: a first-class {@code ColSpec<(col:?)>[1]} value (see {@link TypedColSpec}). */
    private TypedSpec typedColSpec(ColSpec cs) {
        if (cs.function1() != null) {
            throw new TypeInferenceException("~" + cs.name()
                    + ": mapped/aggregate column specifications need an enclosing call to type against");
        }
        Type row = new Type.RelationType(List.of(unknownColumn(cs.name())));
        return new TypedColSpec(cs.name(),
                ExprType.one(new Type.GenericType(Pure.COL_SPEC.qualifiedName(), List.of(row))));
    }

    /** A bare {@code ~[a,b]}: a first-class {@code ColSpecArray<(a:?, b:?)>[1]} value. */
    private TypedSpec typedColSpecArray(ColSpecArray arr) {
        // ~[] is LEGAL where zero columns mean something: groupBy(~[], aggs)
        // is the whole-relation aggregate (the engine's empty-key grouping).
        if (arr.colSpecs().isEmpty()) {
            return new TypedColSpecArray(List.of(),
                    ExprType.one(new Type.GenericType(Pure.COL_SPEC_ARRAY.qualifiedName(),
                            List.of(new Type.RelationType(List.of())))));
        }
        List<Type.Column> cols = new ArrayList<>(arr.colSpecs().size());
        List<String> names = new ArrayList<>(arr.colSpecs().size());
        for (ColSpec cs : arr.colSpecs()) {
            if (cs.function1() != null) {
                throw new TypeInferenceException("~" + cs.name()
                        + ": mapped/aggregate column specifications need an enclosing call to type against");
            }
            if (names.contains(cs.name())) {
                throw new SchemaInvariantException("duplicate column '" + cs.name() + "' in ~[…]");
            }
            names.add(cs.name());
            cols.add(unknownColumn(cs.name()));
        }
        Type row = new Type.RelationType(cols);
        return new TypedColSpecArray(names,
                ExprType.one(new Type.GenericType(Pure.COL_SPEC_ARRAY.qualifiedName(), List.of(row))));
    }

    /** A column of a colspec VALUE: named, with the unknown type {@code ?} until ⊆/= solves it. */
    private static Type.Column unknownColumn(String name) {
        return new Type.Column(name, InferenceKernel.UNKNOWN_COLUMN_TYPE, Multiplicity.Bounded.ONE);
    }

    // =====================================================================
    // Small shared helpers
    // =====================================================================

    /** Check mode: the synthesized type/multiplicity must conform to {@code expected}. */
    void requireConforms(ExprType actual, ExprType expected) {
        // Reuse the kernel: unify(expected, actual) checks actual <: expected for scalars
        // (throws on mismatch). Empty bindings — expected is concrete, nothing to solve.
        // NOTE: class-subtype conformance for user-call arguments is deferred with the user path.
        kernel.unify(expected.type(), actual.type(), new Bindings());
        kernel.unifyMult(expected.multiplicity(), actual.multiplicity(), actual.type(), new Bindings());
    }

    /**
     * Decimal literal type: precision 38, scale from the literal text (§8). A negative scale
     * (e.g. {@code 1E3d}) normalizes to 0; a scale that genuinely exceeds 38 cannot be represented
     * as a {@code DECIMAL(38, s)} &mdash; reject it loudly rather than silently truncate the value.
     */
    private static Type decimalType(BigDecimal value) {
        int scale = Math.max(0, value.scale());
        if (scale > Type.PrecisionDecimal.MAX_PRECISION) {
            throw new TypeInferenceException("decimal literal '" + value.toPlainString()
                    + "' needs scale " + scale + ", exceeding the maximum of " + Type.PrecisionDecimal.MAX_PRECISION);
        }
        return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, scale);
    }
}
