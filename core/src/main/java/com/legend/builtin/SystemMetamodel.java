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
                        main_table VARCHAR(256),
                        distinct_set BIT,
                        user_defined_pk BIT NOT NULL
                    )
                    Table set_ancestry
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY,
                        super_mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        super_id VARCHAR(256) PRIMARY KEY,
                        depth INTEGER NOT NULL
                    )
                    Table group_by_mappings
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY
                    )
                    Table primary_keys
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY,
                        ordinal INTEGER PRIMARY KEY,
                        db_fqn VARCHAR(1024) NOT NULL,
                        schema_name VARCHAR(256) NOT NULL,
                        table_name VARCHAR(256) NOT NULL,
                        pk_column VARCHAR(256) NOT NULL
                    )
                    Table columns
                    (
                        db_fqn VARCHAR(1024) PRIMARY KEY,
                        schema_name VARCHAR(256) PRIMARY KEY,
                        table_name VARCHAR(256) PRIMARY KEY,
                        name VARCHAR(256) PRIMARY KEY
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
                Join SetToAncestry(metamodel.class_mappings.mapping_fqn = metamodel.set_ancestry.mapping_fqn
                    and metamodel.class_mappings.id = metamodel.set_ancestry.id)
                Join AncestryToAncestor(metamodel.set_ancestry.super_mapping_fqn = metamodel.class_mappings.mapping_fqn
                    and metamodel.set_ancestry.super_id = metamodel.class_mappings.id)
                Join SetToGroupBy(metamodel.class_mappings.mapping_fqn = metamodel.group_by_mappings.mapping_fqn
                    and metamodel.class_mappings.id = metamodel.group_by_mappings.id)
                Join SetToPrimaryKeys(metamodel.class_mappings.mapping_fqn = metamodel.primary_keys.mapping_fqn
                    and metamodel.class_mappings.id = metamodel.primary_keys.id)
                Join PrimaryKeyToAlias(metamodel.primary_keys.mapping_fqn = metamodel.table_aliases.mapping_fqn
                    and metamodel.primary_keys.id = metamodel.table_aliases.id)
                Join PrimaryKeyToColumn(metamodel.primary_keys.db_fqn = metamodel.columns.db_fqn
                    and metamodel.primary_keys.schema_name = metamodel.columns.schema_name
                    and metamodel.primary_keys.table_name = metamodel.columns.table_name
                    and metamodel.primary_keys.pk_column = metamodel.columns.name)
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

            Class meta::lite::metamodel::SetAncestry
            {
                depth: Integer[1];
            }

            Association meta::lite::metamodel::SetAncestries
            {
                descendant: meta::relational::mapping::RootRelationalInstanceSetImplementation[1];
                ancestry: meta::lite::metamodel::SetAncestry[*];
            }

            Association meta::lite::metamodel::SetAncestors
            {
                ancestor: meta::relational::mapping::RootRelationalInstanceSetImplementation[1];
                ancestryOf: meta::lite::metamodel::SetAncestry[*];
            }

            function meta::pure::mapping::classMappingById(_this:meta::pure::mapping::Mapping[1], id:String[1]):meta::pure::mapping::SetImplementation[0..1]
            {
                $_this.visibility.visible.classMappings->filter(cm|$cm.id == $id)->first()
            }

            function meta::relational::metamodel::mainTable(_this:meta::relational::metamodel::RelationalMappingSpecification[1]):meta::relational::metamodel::relation::Table[1]
            {
                $_this.mainTableAlias.relationalElement->cast(@meta::relational::metamodel::relation::Table)
            }

            function meta::pure::mapping::superMapping(_this:meta::pure::mapping::PropertyMappingsImplementation[1]):meta::pure::mapping::SetImplementation[0..1]
            {
                $_this->cast(@meta::relational::mapping::RootRelationalInstanceSetImplementation).ancestry->filter(a|$a.depth == 1).ancestor->first()
            }

            function meta::pure::mapping::allSuperSetImplementations(set:meta::pure::mapping::PropertyMappingsImplementation[1], m:meta::pure::mapping::Mapping[1]):meta::pure::mapping::PropertyMappingsImplementation[*]
            {
                $set->cast(@meta::relational::mapping::RootRelationalInstanceSetImplementation).ancestry->filter(a|$a.depth > 0).ancestor
            }

            function meta::relational::mapping::resolvePrimaryKey(_this:meta::relational::mapping::RootRelationalInstanceSetImplementation[1]):meta::relational::metamodel::RelationalOperationElement[*]
            {
                $_this.ancestry->filter(a|$a.depth == 0 || !$a.ancestor.groupBy->isEmpty() || $a.ancestor.distinct == true || $a.ancestor.userDefinedPrimaryKey == true)->sortBy(a|if(!$a.ancestor.groupBy->isEmpty(), |0, |if($a.ancestor.distinct == true, |1000, |if($a.ancestor.userDefinedPrimaryKey == true, |2000, |3000))) + $a.depth)->first().ancestor.primaryKey
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
                    distinct: %1$s metamodel.class_mappings.distinct_set,
                    userDefinedPrimaryKey: %1$s metamodel.class_mappings.user_defined_pk,
                    parent: %1$s@ClassMappingsToMappings,
                    mainTableAlias: %1$s@ClassMappingsToAlias,
                    groupBy[gbm]: %1$s@SetToGroupBy,
                    primaryKey[tac]: %1$s@SetToPrimaryKeys
                }
                meta::relational::mapping::GroupByMapping[gbm]: Relational
                {
                    ~primaryKey(%1$s metamodel.group_by_mappings.mapping_fqn, %1$s metamodel.group_by_mappings.id)
                    ~mainTable %1$s metamodel.group_by_mappings
                }
                meta::lite::metamodel::SetAncestry[anc]: Relational
                {
                    ~primaryKey(%1$s metamodel.set_ancestry.mapping_fqn, %1$s metamodel.set_ancestry.id, %1$s metamodel.set_ancestry.super_mapping_fqn, %1$s metamodel.set_ancestry.super_id)
                    ~mainTable %1$s metamodel.set_ancestry
                    depth: %1$s metamodel.set_ancestry.depth
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
                meta::relational::metamodel::TableAliasColumn[tac]: Relational
                {
                    ~primaryKey(%1$s metamodel.primary_keys.mapping_fqn, %1$s metamodel.primary_keys.id, %1$s metamodel.primary_keys.ordinal)
                    ~mainTable %1$s metamodel.primary_keys
                    columnName: %1$s metamodel.primary_keys.pk_column,
                    alias[alias]: %1$s@PrimaryKeyToAlias,
                    column[col]: %1$s@PrimaryKeyToColumn
                }
                meta::relational::metamodel::Column[col]: Relational
                {
                    ~primaryKey(%1$s metamodel.columns.db_fqn, %1$s metamodel.columns.schema_name, %1$s metamodel.columns.table_name, %1$s metamodel.columns.name)
                    ~mainTable %1$s metamodel.columns
                    name: %1$s metamodel.columns.name
                }
                meta::lite::metamodel::SetAncestries: Relational
                {
                    AssociationMapping
                    (
                        descendant[anc, rootRel]: %1$s@SetToAncestry,
                        ancestry[rootRel, anc]: %1$s@SetToAncestry
                    )
                }
                meta::lite::metamodel::SetAncestors: Relational
                {
                    AssociationMapping
                    (
                        ancestor[anc, rootRel]: %1$s@AncestryToAncestor,
                        ancestryOf[rootRel, anc]: %1$s@AncestryToAncestor
                    )
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

    /** A model element shadows a system element of the same qualified
     * name — for FUNCTIONS only when the parameter types agree too: a
     * same-name function over other parameter types is an OVERLOAD (the
     * engine's own {@code resolvePrimaryKey(rsi:RelationalInstanceSet
     * Implementation)} beside the root-set body), never a shadow. */
    private static boolean shadows(PackageableElement e, PackageableElement sys) {
        if (!e.qualifiedName().equals(sys.qualifiedName())) {
            return false;
        }
        if (e instanceof com.legend.model.FunctionDefinition f
                && sys instanceof com.legend.model.FunctionDefinition g) {
            if (f.parameters().size() != g.parameters().size()) {
                return false;
            }
            for (int i = 0; i < f.parameters().size(); i++) {
                if (!String.valueOf(f.parameters().get(i).type()).equals(
                        String.valueOf(g.parameters().get(i).type()))) {
                    return false;
                }
            }
        }
        return true;
    }

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
                    e -> shadows(e, el));
            if (!shadowed) {
                merged.add(el);
            }
        }
        return new ParsedModel(merged, parsed.imports(), parsed.source(),
                parsed.elementOffsets(), parsed.elementImports(),
                parsed.elementSources(), parsed.unclaimedSections());
    }
}
