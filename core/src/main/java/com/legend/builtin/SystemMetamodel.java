package com.legend.builtin;

import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;

import java.util.ArrayList;
import java.util.List;

/**
 * THE system metamodel store (METAMODEL_STORE_HANDOFF.md): the metamodel
 * lives IN the database. {@code Class.all()} is an ordinary mapped-class
 * query over {@code metamodel.classes}, seeded from the compiler's own
 * registry ({@code ModelContext.classifierInstances}) &mdash; a real
 * {@code SELECT}, executed by the database, through the EXISTING store
 * lane. The engine's one routing rule ("mapped &rarr; store") then covers
 * metamodel and user classes with ZERO special cases in dispatch.
 *
 * <p><strong>One owner, three surfaces.</strong> (1) The system Database +
 * Mapping (+ the navigation FUNCTIONS over them) are fixed Pure SOURCE,
 * parsed once at class load and injected into every model build
 * ({@code Compiler.buildModel}/{@code buildModule}) &mdash; the resolver
 * sees them exactly as parsed elements, no parallel lane. (2)
 * {@code exec.MetamodelSeeds} renders the extent of the ACTIVE model context
 * per table; the one execution-setup owner ({@code StatementExecutor
 * .executeTyped}) runs the DDL+INSERT when a resolved body reads the
 * store. (3) The FQN constants are the exact-FQN identification surface
 * (D1's ambient rule, the executor's table-reference detection).
 *
 * <p><strong>D2 &mdash; one identity for an element value: the FQN.</strong>
 * Every element table keys on the FQN ({@code ~primaryKey}); {@code name}
 * is the print form the mapping binds. A REFERENCE to a tracked element
 * ({@code B1Mapping} in a query) is the row keyed by its FQN (the
 * resolver's element-reference rule, D3).
 *
 * <p><strong>Metamodel-as-relations step 3 (2026-09-02):</strong> the
 * mapping metamodel joins the store &mdash; {@code mappings},
 * {@code class_mappings} (one row per RELATIONAL class mapping, its
 * extends-resolved main table stamped by the compiler),
 * {@code mapping_includes_closure} (the reflexive-transitive include
 * closure as a ROW ENTITY, {@code meta::lite::metamodel::MappingVisibility}: the navigations need "the sets a mapping can
 * see", which is not a recursion the database should re-derive per
 * query) and {@code tables}. The engine's navigation FUNCTIONS
 * ({@code classMappingById}, {@code mainTable}) are Pure bodies over
 * these rows &mdash; the engine bodies are the SPEC, ours read our own
 * compile-time facts; no engine source is carried. Grow BY WITNESS
 * ONLY.
 */
public final class SystemMetamodel {

    private SystemMetamodel() {
    }

    /** The system store element (charter &sect;4). */
    public static final String STORE_FQN =
            "meta::lite::metamodel::MetamodelStore";

    /** The system mapping — D1's ambient execution context supplies it. */
    public static final String MAPPING_FQN =
            "meta::lite::metamodel::MetamodelMapping";

    private static final String S = "[meta::lite::metamodel::MetamodelStore]";
    private static final String INHERITANCE_OP =
            "meta::pure::router::operations::inheritance_OperationSetImplementation_1__SetImplementation_MANY_()";

    /** Schema (charter &sect;4 + step 3); grow BY WITNESS ONLY. */
    private static final String SOURCE = """
            ###Relational
            Database meta::lite::metamodel::MetamodelStore
            (
                Schema metamodel
                (
                    Table classes
                    (
                        fqn VARCHAR(1024) PRIMARY KEY,
                        name VARCHAR(256) NOT NULL,
                        package VARCHAR(1024) NOT NULL
                    )
                    Table mappings
                    (
                        fqn VARCHAR(1024) PRIMARY KEY,
                        name VARCHAR(256) NOT NULL
                    )
                    Table mapping_includes_closure
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        included_fqn VARCHAR(1024) PRIMARY KEY
                    )
                    Table class_mappings
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY,
                        class_fqn VARCHAR(1024) NOT NULL,
                        super_set_id VARCHAR(256),
                        main_db VARCHAR(1024),
                        main_schema VARCHAR(256),
                        main_table VARCHAR(256)
                    )
                    Table table_aliases
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY,
                        name VARCHAR(256) NOT NULL,
                        main_db VARCHAR(1024),
                        main_schema VARCHAR(256),
                        main_table VARCHAR(256)
                    )
                    Table tables
                    (
                        db_fqn VARCHAR(1024) PRIMARY KEY,
                        schema_name VARCHAR(256) PRIMARY KEY,
                        name VARCHAR(256) PRIMARY KEY
                    )
                )
                Join MappingsToClosure(metamodel.mappings.fqn = metamodel.mapping_includes_closure.mapping_fqn)
                Join ClosureToVisible(metamodel.mapping_includes_closure.included_fqn = metamodel.mappings.fqn)
                Join ClassMappingsToMappings(metamodel.class_mappings.mapping_fqn = metamodel.mappings.fqn)
                Join ClosureToClassMappings(metamodel.mapping_includes_closure.included_fqn = metamodel.class_mappings.mapping_fqn)
                Join ClassMappingsToAlias(metamodel.class_mappings.mapping_fqn = metamodel.table_aliases.mapping_fqn and metamodel.class_mappings.id = metamodel.table_aliases.id)
                Join AliasToTables(metamodel.table_aliases.main_db = metamodel.tables.db_fqn
                    and metamodel.table_aliases.main_schema = metamodel.tables.schema_name
                    and metamodel.table_aliases.main_table = metamodel.tables.name)
            )

            ###Pure
            Class meta::lite::metamodel::MappingVisibility
            {
            }

            Association meta::lite::metamodel::MappingVisibilities
            {
                viewer: meta::pure::mapping::Mapping[1];
                visibility: meta::lite::metamodel::MappingVisibility[*];
            }

            Association meta::lite::metamodel::VisibleMappings
            {
                visible: meta::pure::mapping::Mapping[1];
                visibleFrom: meta::lite::metamodel::MappingVisibility[*];
            }

            Association meta::lite::metamodel::VisibleSets
            {
                visibilityOf: meta::lite::metamodel::MappingVisibility[*];
                visibleSets: meta::relational::mapping::RootRelationalInstanceSetImplementation[*];
            }

            function meta::lite::metamodel::classMappingById(_this:meta::pure::mapping::Mapping[1], id:String[1]):meta::pure::mapping::SetImplementation[0..1]
            {
                $_this.visibility.visible.classMappings->filter(cm|$cm.id == $id)->first()
            }

            function meta::lite::metamodel::mainTable(_this:meta::relational::metamodel::RelationalMappingSpecification[1]):meta::relational::metamodel::relation::Table[1]
            {
                $_this.mainTableAlias.relationalElement->cast(@meta::relational::metamodel::relation::Table)
            }

            ###Mapping
            Mapping meta::lite::metamodel::MetamodelMapping
            (
                *meta::pure::metamodel::type::Class: Relational
                {
                    ~primaryKey(%1$s metamodel.classes.fqn)
                    ~mainTable %1$s metamodel.classes
                    name: %1$s metamodel.classes.name
                }
                *meta::pure::mapping::Mapping[mapping]: Relational
                {
                    ~primaryKey(%1$s metamodel.mappings.fqn)
                    ~mainTable %1$s metamodel.mappings
                    name: %1$s metamodel.mappings.name,
                    classMappings[rootRel]: %1$s@ClassMappingsToMappings
                }
                *meta::lite::metamodel::MappingVisibility[vis]: Relational
                {
                    ~primaryKey(%1$s metamodel.mapping_includes_closure.mapping_fqn, %1$s metamodel.mapping_includes_closure.included_fqn)
                    ~mainTable %1$s metamodel.mapping_includes_closure
                }
                *meta::pure::mapping::SetImplementation: Operation
                {
                    %2$s
                }
                meta::relational::mapping::RootRelationalInstanceSetImplementation[rootRel]: Relational
                {
                    ~primaryKey(%1$s metamodel.class_mappings.mapping_fqn, %1$s metamodel.class_mappings.id)
                    ~mainTable %1$s metamodel.class_mappings
                    id: %1$s metamodel.class_mappings.id,
                    superSetImplementationId: %1$s metamodel.class_mappings.super_set_id,
                    parent: %1$s@ClassMappingsToMappings,
                    mainTableAlias: %1$s@ClassMappingsToAlias
                }
                meta::relational::metamodel::TableAlias[alias]: Relational
                {
                    ~primaryKey(%1$s metamodel.table_aliases.mapping_fqn, %1$s metamodel.table_aliases.id)
                    ~mainTable %1$s metamodel.table_aliases
                    name: %1$s metamodel.table_aliases.name,
                    relationalElement[tbl]: %1$s@AliasToTables
                }
                *meta::relational::metamodel::RelationalOperationElement: Operation
                {
                    %2$s
                }
                meta::relational::metamodel::relation::Table[tbl]: Relational
                {
                    ~primaryKey(%1$s metamodel.tables.db_fqn, %1$s metamodel.tables.schema_name, %1$s metamodel.tables.name)
                    ~mainTable %1$s metamodel.tables
                    name: %1$s metamodel.tables.name
                }
                meta::lite::metamodel::MappingVisibilities: Relational
                {
                    AssociationMapping
                    (
                        viewer[vis, mapping]: %1$s@MappingsToClosure,
                        visibility[mapping, vis]: %1$s@MappingsToClosure
                    )
                }
                meta::lite::metamodel::VisibleMappings: Relational
                {
                    AssociationMapping
                    (
                        visible[vis, mapping]: %1$s@ClosureToVisible,
                        visibleFrom[mapping, vis]: %1$s@ClosureToVisible
                    )
                }
                meta::lite::metamodel::VisibleSets: Relational
                {
                    AssociationMapping
                    (
                        visibilityOf[rootRel, vis]: %1$s@ClosureToClassMappings,
                        visibleSets[vis, rootRel]: %1$s@ClosureToClassMappings
                    )
                }
            )
            """.formatted(S, INHERITANCE_OP);

    /** The system Pure source (tests inspect it; never edited at run time). */
    public static String source() {
        return SOURCE;
    }

    /** Parsed once at class load — fails loudly if the source rots
     * (the {@code Pure} native-catalog discipline). */
    private static final List<PackageableElement> ELEMENTS =
            com.legend.parser.ElementParser.parse(SOURCE,
                    com.legend.parser.Dialect.LEGEND_LITE).elements();

    /**
     * Append the system elements to a parsed model — the ONE injection
     * seam, called by both build doors. A model that already declares one
     * of the system FQNs keeps its own definition (defensive; the
     * {@code meta::lite} namespace is platform-reserved).
     */
    public static ParsedModel injectInto(ParsedModel parsed) {
        List<PackageableElement> merged =
                new ArrayList<>(parsed.elements());
        for (PackageableElement el : ELEMENTS) {
            boolean shadowed = parsed.elements().stream().anyMatch(
                    e -> e.qualifiedName().equals(el.qualifiedName()));
            if (!shadowed) {
                merged.add(el);
            }
        }
        return new ParsedModel(merged, parsed.imports(), parsed.source(),
                parsed.elementOffsets(), parsed.elementImports(),
                parsed.elementSources(), parsed.unclaimedSections());
    }
}
