package com.legend.model;

import com.legend.protocol.Realization;

import com.legend.protocol.spec.ValueSpecification;

import java.util.List;
import java.util.Objects;

/**
 * The canonical {@code Mapping} element &mdash; a <strong>binding table</strong>
 * (docs/CLEAN_SHEET_INVERSION.md §2.1). Structure only: each binding pairs a
 * class / association with a realizing function <em>by FQN</em>; no
 * {@code ValueSpecification} and no DSL body lives here. This is the form every
 * phase after E sees and the form the clean-sheet surface parses to directly
 * (Door 1); the legacy DSL ({@link LegacyMappingDefinition}) is rewritten into
 * it by {@code MappingNormalizer}.
 *
 * <p>Bodies live in ordinary {@link FunctionDefinition}s in the model's element
 * list, lifted by Phase E and named per {@code SynthFqn} (the
 * {@code <mapping>$class$<classFqn>} / {@code <mapping>$assoc$<assocFqn>}
 * scheme). A {@link ClassBinding#functionFqn()} is exactly the lifted
 * function's FQN, so dispatch (§6) is: binding table &rarr; FQN &rarr; the one
 * {@code findFunction} index. Nothing reconstructs or parses these strings.
 *
 * @param qualifiedName        fully-qualified mapping name
 * @param includes             included mappings, with optional store substitutions
 * @param classBindings        per-class realizing-function bindings
 * @param associationBindings  per-association predicate-function bindings
 * @param enumerationMappings  per-enumeration mappings (inline static tables &mdash;
 *                             data, not expressions, so they stay structural)
 * @param testSuitesSource     raw {@code testSuites: [...]} text, or {@code null}
 */
public record MappingDefinition(
        String qualifiedName,
        List<MappingInclude> includes,
        List<ClassBinding> classBindings,
        List<AssociationBinding> associationBindings,
        List<EnumerationMapping> enumerationMappings,
        @com.legend.Nullable String testSuitesSource,
        java.util.Map<String, String> routedTargetSets)
        implements PackageableElement {

    /** The common form: no per-property set-dispatch table. */
    public MappingDefinition(String qualifiedName,
            List<MappingInclude> includes,
            List<ClassBinding> classBindings,
            List<AssociationBinding> associationBindings,
            List<EnumerationMapping> enumerationMappings,
            @com.legend.Nullable String testSuitesSource) {
        this(qualifiedName, includes, classBindings, associationBindings,
                enumerationMappings, testSuitesSource, java.util.Map.of());
    }

    public MappingDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        includes = includes == null ? List.of() : List.copyOf(includes);
        classBindings = classBindings == null ? List.of() : List.copyOf(classBindings);
        associationBindings = associationBindings == null ? List.of() : List.copyOf(associationBindings);
        enumerationMappings = enumerationMappings == null ? List.of() : List.copyOf(enumerationMappings);
        routedTargetSets = routedTargetSets == null
                ? java.util.Map.of() : java.util.Map.copyOf(routedTargetSets);
    }

    /**
     * A class binding: structure only; the body is a {@link Realization}
     * (function ref or &mdash; B&rarr;E only &mdash; an inline expression).
     * SEALED by binding kind &mdash; the kind is a property of the binding
     * relationship, not derivable from the function (MAPPING_CLEAN_SHEET.md
     * §1), and the variant IS the kind: a {@link Relational} binding
     * carries its physical-source stamp as a NON-NULL component (the
     * guarantee is compile-time &mdash; a door that forgets to stamp does
     * not compile), a {@link Pure} (m2m) binding has no physical source by
     * construction. NO convenience constructors on either variant: one
     * silently dropped {@code primaryKeyColumns} (the AssocJoin disease) —
     * every site spells every component.
     */
    public sealed interface ClassBinding permits ClassBinding.Relational, ClassBinding.Pure {
        String classFqn();
        @com.legend.Nullable String setId();
        @com.legend.Nullable String extendsSetId();
        boolean root();
        Realization realization();
        List<String> primaryKeyColumns();

        /**
         * The realizing function's FQN. Valid only when the realization is a
         * {@link Realization.Ref} &mdash; always true after Phase E, since the
         * normalizer lifts every {@link Realization.Inline}. Throws on an
         * unlifted inline binding (a phase-ordering bug, surfaced loudly).
         */
        default String functionFqn() {
            if (realization() instanceof Realization.Ref r) return r.functionFqn();
            throw new IllegalStateException(
                    "class binding for '" + classFqn() + "' is an unlifted inline body");
        }

        /** A relational class binding; {@code source} is never null. */
        record Relational(
                String classFqn,
                @com.legend.Nullable String setId,
                @com.legend.Nullable String extendsSetId,
                boolean root,
                Realization realization,
                List<String> primaryKeyColumns,
                RelationalSource source) implements ClassBinding {
            public Relational {
                Objects.requireNonNull(classFqn, "classFqn");
                Objects.requireNonNull(realization, "realization");
                Objects.requireNonNull(source, "source");
                primaryKeyColumns = primaryKeyColumns == null ? List.of()
                        : List.copyOf(primaryKeyColumns);
            }
        }

        /** A pure (m2m) class binding: no physical source exists. */
        record Pure(
                String classFqn,
                @com.legend.Nullable String setId,
                @com.legend.Nullable String extendsSetId,
                boolean root,
                Realization realization,
                List<String> primaryKeyColumns) implements ClassBinding {
            public Pure {
                Objects.requireNonNull(classFqn, "classFqn");
                Objects.requireNonNull(realization, "realization");
                primaryKeyColumns = primaryKeyColumns == null ? List.of()
                        : List.copyOf(primaryKeyColumns);
            }
        }
    }

    /**
     * This mapping's class bindings PLUS its includes' (transitively,
     * OWN-FIRST &mdash; a local binding shadows an included one at lookup,
     * matching {@code ClassSources.findBinding}; cycle-safe; bare include
     * paths resolve in the includer's package). A symbol-table walk for
     * consumers that read binding METADATA verbatim &mdash; row semantics
     * stay in the lifted functions.
     */
    public List<ClassBinding> classBindingsWithIncludes(
            java.util.function.Function<String,
                    java.util.Optional<MappingDefinition>> find) {
        List<ClassBinding> out = new java.util.ArrayList<>(classBindings);
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(qualifiedName);
        collectIncludedBindings(this, find, out, seen);
        return out;
    }

    private static void collectIncludedBindings(MappingDefinition md,
            java.util.function.Function<String,
                    java.util.Optional<MappingDefinition>> find,
            List<ClassBinding> out, java.util.Set<String> seen) {
        for (MappingInclude inc : md.includes()) {
            String path = inc.mappingPath();
            if (!path.contains("::") && md.qualifiedName().contains("::")) {
                String inPkg = md.qualifiedName().substring(0,
                        md.qualifiedName().lastIndexOf("::")) + "::" + path;
                if (find.apply(inPkg).isPresent()) {
                    path = inPkg;
                }
            }
            if (!seen.add(path)) {
                continue;
            }
            MappingDefinition included = find.apply(path).orElse(null);
            if (included == null) {
                continue;
            }
            out.addAll(included.classBindings());
            collectIncludedBindings(included, find, out, seen);
        }
    }

    /**
     * Construction-time facts about a RELATIONAL binding's physical source
     * &mdash; CACHED ANSWERS stamped at Phase E by the same resolution the
     * function synthesis uses, so the derivation exists in exactly one
     * place. THE RAZOR (docs/LEGACY_MAPPING_REACHBACK_CENSUS.md): the
     * lifted function is the ONLY carrier of row semantics; a stamp is
     * read VERBATIM (compared, printed, dispatched on) and never
     * interpreted &mdash; a consumer that must combine stamps to decide
     * what rows mean is doing an analysis and must walk the function
     * instead. Stamps are never authored outside the Phase-E synthesis
     * (lifted functions are immutable post-E: registration is append-only,
     * every constructor/copy-helper caller is construction-time).
     * Null on non-relational and protocol-sourced bindings.
     *
     * SEALED: {@link Table} (physical main source), {@link Json} (a
     * JsonModelConnection-backed set &mdash; the source is a URL, not a
     * table), {@link Undeclared} (the door could not derive a source:
     * a pre-lift inline binding awaiting Phase E, a protocol
     * function-ref binding, a clean-sheet root that is not a plain
     * table access &mdash; ABSENCE IS SPELLED, never null, so consumers
     * that need a Table must match and fall through explicitly).
     */
    public sealed interface RelationalSource
            permits RelationalSource.Table, RelationalSource.Json,
                    RelationalSource.Undeclared {

        /**
         * @param database              main source's database FQN
         * @param table                 resolved main table (explicit
         *                              {@code ~mainTable} or the
         *                              engine-parity inference &mdash; the
         *                              SAME call the synthesis makes)
         * @param aggregationAwareMain  dispatch flag: this set is the
         *                              AggregationAware main (same species
         *                              as {@code root})
         * @param enumColumns           per-column enum-mapping id spellings
         *                              (serialization metadata; the decode
         *                              SEMANTICS live in the function body)
         */
        record Table(
                String database,
                String table,
                boolean aggregationAwareMain,
                List<EnumColumn> enumColumns) implements RelationalSource {
            public Table {
                Objects.requireNonNull(database, "database");
                Objects.requireNonNull(table, "table");
                enumColumns = enumColumns == null ? List.of()
                        : List.copyOf(enumColumns);
            }
        }

        /** JsonModelConnection-backed set: the source is a URL. */
        record Json(String url) implements RelationalSource {
            public Json {
                Objects.requireNonNull(url, "url");
            }
        }

        /** The door could not derive a source; {@code why} names the door
         * and reason (poison idiom &mdash; loud on inspection). */
        record Undeclared(String why) implements RelationalSource {
            public Undeclared {
                Objects.requireNonNull(why, "why");
            }
        }
    }

    /** A column's declared enum-mapping id ({@code prop: EnumerationMapping
     * synonym: T.COL}) &mdash; the id is a SPELLING read verbatim for
     * plan-text parity, never a decode (that is in the function). */
    public record EnumColumn(String table, String column, String enumMappingId) {
        public EnumColumn {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(enumMappingId, "enumMappingId");
        }
    }

    /**
     * An association binding: the association realized by a predicate. The body
     * is a {@link Realization} (a predicate-function ref, or &mdash; B&rarr;E
     * only &mdash; an inline {@code (Source[1], Target[1]) -> Boolean[1]} lambda).
     *
     * @param associationFqn the mapped association
     * @param realization    how the predicate is realized
     */
    public record AssociationBinding(String associationFqn, Realization realization) {
        public AssociationBinding {
            Objects.requireNonNull(associationFqn, "associationFqn");
            Objects.requireNonNull(realization, "realization");
        }

        /** Convenience: a function-ref predicate binding (Door 1 / post-lift). */
        public AssociationBinding(String associationFqn, String predicateFunctionFqn) {
            this(associationFqn, new Realization.Ref(predicateFunctionFqn));
        }

        /** The predicate function's FQN. Valid only post-lift (see {@link ClassBinding#functionFqn()}). */
        public String predicateFunctionFqn() {
            if (realization instanceof Realization.Ref r) return r.functionFqn();
            throw new IllegalStateException(
                    "association binding for '" + associationFqn + "' is an unlifted inline body");
        }
    }
}
