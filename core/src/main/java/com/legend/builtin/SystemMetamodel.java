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

    /** The SQL data-type kinds the store models — m3 datatype class simple
     * names, one filtered set per kind over {@code data_types} (the engine
     * idiom for a hierarchy over one table); the seed writes the same
     * spelling in {@code dt_kind} ({@code OpSeeds.kindOf}). */
    static final String[] DATA_TYPE_KINDS = {"BigInt", "SmallInt", "TinyInt",
        "Integer", "Float", "Double", "Real", "Bit", "Timestamp", "Date", "Distinct",
        "Other", "SemiStructured", "Varchar", "Char", "Binary", "Varbinary",
        "Decimal", "Numeric", "Array", "Object"};

    /** The relational-operation node kinds the store models: {m3 class
     * simple name, set id, property lines}. */
    static final String[][] OP_KINDS = {
        {"DynaFunction", "opDyna", "name: " + S + " metamodel.relational_elements.dyna_name"},
        {"Literal", "opLit", ""},
        {"LiteralList", "opLitList", ""},
        {"TableAliasColumn", "opTac", "columnName: " + S + " metamodel.relational_elements.col_name,\n"
            + "                    column[col]: " + S + "@OpToColumn"},
        {"RelationalOperationElementWithJoin", "opJoin", ""},
    };

    private static String filters() {
        StringBuilder sb = new StringBuilder();
        for (String k : DATA_TYPE_KINDS) {
            sb.append("                Filter Dt").append(k)
                    .append("(metamodel.data_types.dt_kind = '").append(k).append("')\n");
        }
        // the RelationalOperationElement hierarchy: one filter per kind over
        // the ONE relational_elements table (expression nodes, and the
        // relations — Table, View, Column, TableAlias)
        for (String[] k : OP_KINDS) {
            sb.append("                Filter Op").append(k[0])
                    .append("(metamodel.relational_elements.kind = '").append(k[0]).append("')\n");
        }
        for (String k : ELEMENT_KINDS) {
            sb.append("                Filter El").append(k)
                    .append("(metamodel.relational_elements.kind = '").append(k).append("')\n");
        }
        return sb.toString();
    }

    /** The relation kinds of the element table (the op-node kinds are
     * {@link #OP_KINDS}); the seed writes the same spelling in
     * {@code kind} ({@code RelationalOpRows}' factories). */
    static final String[] ELEMENT_KINDS = {"Table", "View", "Column", "TableAlias"};

    private static String dataTypeSets() {
        StringBuilder sb = new StringBuilder();
        for (String k : DATA_TYPE_KINDS) {
            boolean sized = k.equals("Varchar") || k.equals("Char") || k.equals("Binary")
                    || k.equals("Varbinary");
            boolean scaled = k.equals("Decimal") || k.equals("Numeric");
            String props = sized
                    ? "\n                    size: " + S + " metamodel.data_types.type_size"
                    : scaled
                    ? "\n                    precision: " + S + " metamodel.data_types.type_precision,"
                        + "\n                    scale: " + S + " metamodel.data_types.type_scale"
                    : "";
            sb.append("                meta::relational::metamodel::datatype::").append(k)
                    .append("[dt").append(k).append("]: Relational\n")
                    .append("                {\n")
                    .append("                    ~filter ").append(S).append(" Dt").append(k).append("\n")
                    .append("                    ~primaryKey(").append(S).append(" metamodel.data_types.id)\n")
                    .append("                    ~mainTable ").append(S).append(" metamodel.data_types")
                    .append(props).append("\n")
                    .append("                }\n");
        }
        return sb.toString();
    }

    private static String opSets() {
        StringBuilder sb = new StringBuilder();
        for (String[] k : OP_KINDS) {
            sb.append("                meta::relational::metamodel::").append(k[0])
                    .append("[").append(k[1]).append("]: Relational\n")
                    .append("                {\n")
                    .append("                    ~filter ").append(S).append(" Op").append(k[0]).append("\n")
                    .append("                    ~primaryKey(").append(S).append(" metamodel.relational_elements.id)\n")
                    .append("                    ~mainTable ").append(S).append(" metamodel.relational_elements")
                    .append(k[2].isEmpty() ? "" : "\n                    " + k[2]).append("\n")
                    .append("                }\n");
        }
        return sb.toString();
    }

    /** {@code prop[opX]: @Join} per operation kind — a property typed
     * RelationalOperationElement routes to every member set. */
    private static String opRoutes(String prop, String join) {
        List<String> lines = new ArrayList<>();
        for (String[] k : OP_KINDS) {
            lines.add("                    " + prop + "[" + k[1] + "]: " + S + "@" + join);
        }
        return String.join(",\n", lines);
    }

    private static String typeRoutes() {
        List<String> lines = new ArrayList<>();
        for (String k : DATA_TYPE_KINDS) {
            lines.add("                    type[dt" + k + "]: " + S + "@ColumnToType");
        }
        return String.join(",\n", lines);
    }

    private static String inferredTypeEnds() {
        List<String> lines = new ArrayList<>();
        for (String[] op : OP_KINDS) {
            for (String k : DATA_TYPE_KINDS) {
                lines.add("                        inferredType[" + op[1] + ", dt" + k + "]: " + S + "@OpToType");
                lines.add("                        inferredTypeOf[dt" + k + ", " + op[1] + "]: " + S + "@OpToType");
            }
        }
        return String.join(",\n", lines);
    }

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
                        included_fqn VARCHAR(1024) PRIMARY KEY,
                        include_rank INTEGER NOT NULL
                    )
                    Table class_mappings
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY,
                        mapped_class_fqn VARCHAR(1024) NOT NULL,
                        super_set_id VARCHAR(256),
                        main_db VARCHAR(1024),
                        main_schema VARCHAR(256),
                        main_table VARCHAR(256),
                        distinct_set BIT,
                        user_defined_pk BIT NOT NULL,
                        root BIT NOT NULL
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
                    Table databases
                    (
                        fqn VARCHAR(1024) PRIMARY KEY,
                        name VARCHAR(256) NOT NULL
                    )
                    Table schemas
                    (
                        db_fqn VARCHAR(1024) PRIMARY KEY,
                        name VARCHAR(256) PRIMARY KEY
                    )
                    Table properties
                    (
                        owner_fqn VARCHAR(1024) PRIMARY KEY,
                        name VARCHAR(256) PRIMARY KEY
                    )
                    Table data_types
                    (
                        id VARCHAR(2048) PRIMARY KEY,
                        dt_kind VARCHAR(32) NOT NULL,
                        type_size INTEGER,
                        type_precision INTEGER,
                        type_scale INTEGER
                    )
                    Table relational_elements
                    (
                        id VARCHAR(2048) PRIMARY KEY,
                        kind VARCHAR(64) NOT NULL,
                        name VARCHAR(256),
                        db_fqn VARCHAR(1024),
                        schema_name VARCHAR(256),
                        table_name VARCHAR(256),
                        dtype_id VARCHAR(2048),
                        parent_id VARCHAR(2048),
                        ordinal INTEGER,
                        dyna_name VARCHAR(256),
                        literal_value VARCHAR(4000),
                        col_db VARCHAR(1024),
                        col_schema VARCHAR(256),
                        col_table VARCHAR(256),
                        col_name VARCHAR(256),
                        itype_id VARCHAR(2048),
                        pk_mapping_fqn VARCHAR(1024),
                        pk_set_id VARCHAR(256),
                        mapping_fqn VARCHAR(1024),
                        set_id VARCHAR(256),
                        main_db VARCHAR(1024),
                        main_schema VARCHAR(256),
                        main_table VARCHAR(256),
                        view_db VARCHAR(1024),
                        view_schema VARCHAR(256),
                        view_name VARCHAR(256),
                        base_db VARCHAR(1024),
                        base_schema VARCHAR(256),
                        base_table VARCHAR(256)
                    )
                    Table view_column_mappings
                    (
                        db_fqn VARCHAR(1024) PRIMARY KEY,
                        schema_name VARCHAR(256) PRIMARY KEY,
                        view_name VARCHAR(256) PRIMARY KEY,
                        column_name VARCHAR(256) PRIMARY KEY,
                        op_id VARCHAR(2048) NOT NULL
                    )
                    Table property_mappings
                    (
                        mapping_fqn VARCHAR(1024) PRIMARY KEY,
                        id VARCHAR(256) PRIMARY KEY,
                        ordinal INTEGER PRIMARY KEY,
                        prop_owner_fqn VARCHAR(1024),
                        prop_name VARCHAR(256) NOT NULL,
                        op_id VARCHAR(2048) NOT NULL,
                        declared_depth INTEGER NOT NULL
                    )
                )
                Join MappingsToClosure(metamodel.mappings.fqn = metamodel.mapping_includes_closure.mapping_fqn)
                Join ClosureToVisible(metamodel.mapping_includes_closure.included_fqn = metamodel.mappings.fqn)
                Join ClassMappingsToMappings(metamodel.class_mappings.mapping_fqn = metamodel.mappings.fqn)
                Join ClosureToClassMappings(metamodel.mapping_includes_closure.included_fqn = metamodel.class_mappings.mapping_fqn)
                Join ClassMappingsToAlias(metamodel.class_mappings.mapping_fqn = metamodel.relational_elements.mapping_fqn and metamodel.class_mappings.id = metamodel.relational_elements.set_id)
                Join AliasToTables(metamodel.relational_elements.main_db = {target}.db_fqn
                    and metamodel.relational_elements.main_schema = {target}.schema_name
                    and metamodel.relational_elements.main_table = {target}.name)
                Join AliasToViews(metamodel.relational_elements.main_db = {target}.db_fqn
                    and metamodel.relational_elements.main_schema = {target}.schema_name
                    and metamodel.relational_elements.main_table = {target}.name)
                Join AliasToBaseTable(metamodel.relational_elements.base_db = {target}.db_fqn
                    and metamodel.relational_elements.base_schema = {target}.schema_name
                    and metamodel.relational_elements.base_table = {target}.name)
                Join ViewToAlias(metamodel.relational_elements.db_fqn = {target}.view_db
                    and metamodel.relational_elements.schema_name = {target}.view_schema
                    and metamodel.relational_elements.name = {target}.view_name)
                Join SetToAncestry(metamodel.class_mappings.mapping_fqn = metamodel.set_ancestry.mapping_fqn
                    and metamodel.class_mappings.id = metamodel.set_ancestry.id)
                Join AncestryToAncestor(metamodel.set_ancestry.super_mapping_fqn = metamodel.class_mappings.mapping_fqn
                    and metamodel.set_ancestry.super_id = metamodel.class_mappings.id)
                Join SetToGroupBy(metamodel.class_mappings.mapping_fqn = metamodel.group_by_mappings.mapping_fqn
                    and metamodel.class_mappings.id = metamodel.group_by_mappings.id)
                Join SetToPrimaryKeyOps(metamodel.class_mappings.mapping_fqn = metamodel.relational_elements.pk_mapping_fqn
                    and metamodel.class_mappings.id = metamodel.relational_elements.pk_set_id)
                Join DbToSchemas(metamodel.databases.fqn = metamodel.schemas.db_fqn)
                Join SchemaToViews(metamodel.schemas.db_fqn = metamodel.relational_elements.db_fqn
                    and metamodel.schemas.name = metamodel.relational_elements.schema_name)
                Join ViewToColumnMappings(metamodel.relational_elements.db_fqn = metamodel.view_column_mappings.db_fqn
                    and metamodel.relational_elements.schema_name = metamodel.view_column_mappings.schema_name
                    and metamodel.relational_elements.name = metamodel.view_column_mappings.view_name)
                Join ColumnMappingToOp(metamodel.view_column_mappings.op_id = metamodel.relational_elements.id)
                Join ClassMappingsToClass(metamodel.class_mappings.mapped_class_fqn = metamodel.classes.fqn)
                Join SetToPropertyMappings(metamodel.class_mappings.mapping_fqn = metamodel.property_mappings.mapping_fqn
                    and metamodel.class_mappings.id = metamodel.property_mappings.id)
                Join PropertyMappingToOp(metamodel.property_mappings.op_id = metamodel.relational_elements.id)
                Join PropertyMappingToProperty(metamodel.property_mappings.prop_owner_fqn = metamodel.properties.owner_fqn
                    and metamodel.property_mappings.prop_name = metamodel.properties.name)
                Join ColumnToType(metamodel.relational_elements.dtype_id = metamodel.data_types.id)
                Join OpToType(metamodel.relational_elements.itype_id = metamodel.data_types.id)
                Join OpToColumn(metamodel.relational_elements.col_db = {target}.db_fqn
                    and metamodel.relational_elements.col_schema = {target}.schema_name
                    and metamodel.relational_elements.col_table = {target}.table_name
                    and metamodel.relational_elements.col_name = {target}.name)
            %3$s
            )

            ###Pure
            Class meta::lite::metamodel::MappingVisibility
            {
                includeRank: Integer[1];
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

            Association meta::lite::metamodel::AliasBaseTables
            {
                base: meta::relational::metamodel::relation::Table[1];
                baseOf: meta::relational::metamodel::TableAlias[*];
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

            Association meta::lite::metamodel::InferredTypes
            {
                inferredType: meta::relational::metamodel::datatype::DataType[0..1];
                inferredTypeOf: meta::relational::metamodel::RelationalOperationElement[*];
            }

            Association meta::lite::metamodel::EffectivePropertyMappings
            {
                effectivePropertyMappings: meta::relational::mapping::RelationalPropertyMapping[*];
                allPropertyMappingsOf: meta::relational::mapping::RootRelationalInstanceSetImplementation[1];
            }

            function meta::pure::mapping::_classMappingByClass(_this:meta::pure::mapping::Mapping[1], class:meta::pure::metamodel::type::Class<meta::pure::metamodel::type::Any>[1]):meta::pure::mapping::SetImplementation[*]
            {
                $_this.visibility->sortBy(v|$v.includeRank).visible.classMappings->filter(cm|$cm.class == $class)
            }

            function meta::pure::mapping::rootClassMappingByClass(_this:meta::pure::mapping::Mapping[1], class:meta::pure::metamodel::type::Class<meta::pure::metamodel::type::Any>[1]):meta::pure::mapping::SetImplementation[0..1]
            {
                $_this->meta::pure::mapping::_classMappingByClass($class)->filter(s|$s.root == true)->last()
            }

            function meta::relational::metamodel::view(_this:meta::relational::metamodel::Schema[1], name:String[1]):meta::relational::metamodel::relation::View[0..1]
            {
                $_this.views->filter(t|$t.name == $name)->first()
            }

            function meta::pure::mapping::allPropertyMappings(_this:meta::pure::mapping::PropertyMappingsImplementation[1]):meta::pure::mapping::PropertyMapping[*]
            {
                $_this->cast(@meta::relational::mapping::RootRelationalInstanceSetImplementation).effectivePropertyMappings
            }

            function meta::pure::mapping::propertyMappingsByPropertyName(_this:meta::pure::mapping::PropertyMappingsImplementation[1], s:String[1]):meta::pure::mapping::PropertyMapping[*]
            {
                $_this->meta::pure::mapping::allPropertyMappings()->filter(pm|$pm.property.name == $s)
            }

            function meta::pure::mapping::propertyMappingsByPropertyName(i:meta::pure::mapping::InstanceSetImplementation[1], propertyName:String[1]):meta::pure::mapping::PropertyMapping[*]
            {
                $i->meta::pure::mapping::allPropertyMappings()->filter(pm|$pm.property.name == $propertyName)
            }

            function meta::relational::functions::typeInference::inferRelationalType(rop:meta::relational::metamodel::RelationalOperationElement[1]):meta::relational::metamodel::datatype::DataType[0..1]
            {
                $rop.inferredType
            }

            function meta::relational::metamodel::datatype::dataTypeToSqlText(type:meta::relational::metamodel::datatype::DataType[1]):String[1]
            {
                $type->match([
                    i : meta::relational::metamodel::datatype::Integer[1] | 'INT',
                    f : meta::relational::metamodel::datatype::Float[1] | 'FLOAT',
                    v : meta::relational::metamodel::datatype::Varchar[1] | format('VARCHAR(%%d)', $v.size),
                    c : meta::relational::metamodel::datatype::Char[1] | format('CHAR(%%d)', $c.size),
                    d : meta::relational::metamodel::datatype::Decimal[1] | format('DECIMAL(%%d, %%d)', [$d.precision, $d.scale]),
                    t : meta::relational::metamodel::datatype::Timestamp[1] | 'TIMESTAMP',
                    d : meta::relational::metamodel::datatype::Date[1] | 'DATE',
                    b : meta::relational::metamodel::datatype::BigInt[1] | 'BIGINT',
                    s : meta::relational::metamodel::datatype::SmallInt[1] | 'SMALLINT',
                    t : meta::relational::metamodel::datatype::TinyInt[1] | 'TINYINT',
                    d : meta::relational::metamodel::datatype::Double[1] | 'DOUBLE',
                    n : meta::relational::metamodel::datatype::Numeric[1] | format('NUMERIC(%%d, %%d)', [$n.precision, $n.scale]),
                    d : meta::relational::metamodel::datatype::Distinct[1] | 'DISTINCT',
                    o : meta::relational::metamodel::datatype::Other[1] | 'OTHER',
                    b : meta::relational::metamodel::datatype::Bit[1] | 'BIT',
                    b : meta::relational::metamodel::datatype::Binary[1] | format('BINARY(%%d)', $b.size),
                    r : meta::relational::metamodel::datatype::Real[1] | 'REAL',
                    a : meta::relational::metamodel::datatype::Array[1] | 'ARRAY',
                    v : meta::relational::metamodel::datatype::Varbinary[1] | format('VARBINARY(%%d)', $v.size),
                    s : meta::relational::metamodel::datatype::SemiStructured[1] | 'SEMISTRUCTURED',
                    o : meta::relational::metamodel::datatype::Object[1] | 'OBJECT'
                ])
            }

            function meta::pure::mapping::classMappingById(_this:meta::pure::mapping::Mapping[1], id:String[1]):meta::pure::mapping::SetImplementation[0..1]
            {
                $_this.visibility.visible.classMappings->filter(cm|$cm.id == $id)->first()
            }

            function meta::relational::metamodel::mainTable(_this:meta::relational::metamodel::RelationalMappingSpecification[1]):meta::relational::metamodel::relation::Table[1]
            {
                $_this.mainTableAlias.base
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
                *meta::pure::metamodel::type::Class[cls]: Relational
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
                    includeRank: %1$s metamodel.mapping_includes_closure.include_rank
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
                    root: %1$s metamodel.class_mappings.root,
                    class[cls]: %1$s@ClassMappingsToClass,
                    superSetImplementationId: %1$s metamodel.class_mappings.super_set_id,
                    distinct: %1$s metamodel.class_mappings.distinct_set,
                    userDefinedPrimaryKey: %1$s metamodel.class_mappings.user_defined_pk,
                    parent: %1$s@ClassMappingsToMappings,
                    mainTableAlias: %1$s@ClassMappingsToAlias,
                    groupBy[gbm]: %1$s@SetToGroupBy,
                    primaryKey[opTac]: %1$s@SetToPrimaryKeyOps
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
                    ~filter %1$s ElTableAlias
                    ~primaryKey(%1$s metamodel.relational_elements.id)
                    ~mainTable %1$s metamodel.relational_elements
                    name: %1$s metamodel.relational_elements.name,
                    relationalElement[tbl]: %1$s@AliasToTables,
                    relationalElement[vw]: %1$s@AliasToViews
                }
                meta::relational::metamodel::relation::View[vw]: Relational
                {
                    ~filter %1$s ElView
                    ~primaryKey(%1$s metamodel.relational_elements.id)
                    ~mainTable %1$s metamodel.relational_elements
                    name: %1$s metamodel.relational_elements.name,
                    mainTableAlias[alias]: %1$s@ViewToAlias,
                    columnMappings[vcm]: %1$s@ViewToColumnMappings
                }
                *meta::relational::metamodel::Database[db]: Relational
                {
                    ~primaryKey(%1$s metamodel.databases.fqn)
                    ~mainTable %1$s metamodel.databases
                    name: %1$s metamodel.databases.name,
                    schemas[schema]: %1$s@DbToSchemas
                }
                *meta::relational::metamodel::Schema[schema]: Relational
                {
                    ~primaryKey(%1$s metamodel.schemas.db_fqn, %1$s metamodel.schemas.name)
                    ~mainTable %1$s metamodel.schemas
                    name: %1$s metamodel.schemas.name,
                    views[vw]: %1$s@SchemaToViews
                }
                *meta::relational::mapping::ColumnMapping[vcm]: Relational
                {
                    ~primaryKey(%1$s metamodel.view_column_mappings.db_fqn, %1$s metamodel.view_column_mappings.schema_name, %1$s metamodel.view_column_mappings.view_name, %1$s metamodel.view_column_mappings.column_name)
                    ~mainTable %1$s metamodel.view_column_mappings
                    columnName: %1$s metamodel.view_column_mappings.column_name,
            %5$s
                }
                *meta::pure::metamodel::function::property::Property[prop]: Relational
                {
                    ~primaryKey(%1$s metamodel.properties.owner_fqn, %1$s metamodel.properties.name)
                    ~mainTable %1$s metamodel.properties
                    name: %1$s metamodel.properties.name
                }
                *meta::pure::mapping::PropertyMapping: Operation
                {
                    %2$s
                }
                meta::relational::mapping::RelationalPropertyMapping[rpm]: Relational
                {
                    ~primaryKey(%1$s metamodel.property_mappings.mapping_fqn, %1$s metamodel.property_mappings.id, %1$s metamodel.property_mappings.ordinal)
                    ~mainTable %1$s metamodel.property_mappings
                    property[prop]: %1$s@PropertyMappingToProperty,
            %6$s
                }
                *meta::relational::metamodel::datatype::DataType: Operation
                {
                    %2$s
                }
            %4$s
                *meta::relational::metamodel::RelationalOperationElement: Operation
                {
                    %2$s
                }
                meta::relational::metamodel::relation::Table[tbl]: Relational
                {
                    ~filter %1$s ElTable
                    ~primaryKey(%1$s metamodel.relational_elements.id)
                    ~mainTable %1$s metamodel.relational_elements
                    name: %1$s metamodel.relational_elements.name
                }
                meta::relational::metamodel::Column[col]: Relational
                {
                    ~filter %1$s ElColumn
                    ~primaryKey(%1$s metamodel.relational_elements.id)
                    ~mainTable %1$s metamodel.relational_elements
                    name: %1$s metamodel.relational_elements.name,
            %7$s
                }
            %8$s
                meta::lite::metamodel::InferredTypes: Relational
                {
                    AssociationMapping
                    (
            %9$s
                    )
                }
                meta::lite::metamodel::EffectivePropertyMappings: Relational
                {
                    AssociationMapping
                    (
                        effectivePropertyMappings[rootRel, rpm]: %1$s@SetToPropertyMappings,
                        allPropertyMappingsOf[rpm, rootRel]: %1$s@SetToPropertyMappings
                    )
                }
                meta::lite::metamodel::AliasBaseTables: Relational
                {
                    AssociationMapping
                    (
                        base[alias, tbl]: %1$s@AliasToBaseTable,
                        baseOf[tbl, alias]: %1$s@AliasToBaseTable
                    )
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
            """.formatted(S, INHERITANCE_OP, filters(), dataTypeSets(),
                    opRoutes("relationalOperationElement", "ColumnMappingToOp"),
                    opRoutes("relationalOperationElement", "PropertyMappingToOp"),
                    typeRoutes(), opSets(), inferredTypeEnds());

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
                if (!spelling(f.parameters().get(i).type()).equals(
                        spelling(g.parameters().get(i).type()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A parameter type's spelling without source positions (a resolved
     * model type carries its parse position; the system source carries
     * none — the record text never compared equal). */
    private static String spelling(com.legend.protocol.TypeExpression t) {
        return switch (t) {
            case com.legend.protocol.TypeExpression.NameRef nr -> nr.name();
            case com.legend.protocol.TypeExpression.Generic g -> g.name() + "<"
                    + g.arguments().stream().map(SystemMetamodel::spelling)
                            .collect(java.util.stream.Collectors.joining(",")) + ">";
            default -> String.valueOf(t);
        };
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

    /** The system elements as parsed — THE BOOT LAYER's input (user
     * ruling 2026-09-02: compiled once per process, content-addressed by
     * the source's hash, entered into every graph's index exactly like
     * the graph's own elements, protected as system). */
    public static List<PackageableElement> elements() {
        return ELEMENTS;
    }

    /** Every system element's FQN (functions included) — the boot layer's
     * contribution to a graph's name-resolution universe. */
    public static java.util.Set<String> elementFqns() {
        return Fqns.ALL;
    }

    private static final class Fqns {
        static final java.util.Set<String> ALL = ELEMENTS.stream()
                .map(PackageableElement::qualifiedName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Whether {@code fqn} names a system ELEMENT (class, association,
     * store, mapping — not a function body): reserved, never redefined. */
    public static boolean isSystemElement(String fqn) {
        for (PackageableElement el : ELEMENTS) {
            if (!(el instanceof com.legend.model.FunctionDefinition)
                    && el.qualifiedName().equals(fqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ONE injection seam's parsed-level half: the model with every
     * same-signature system FUNCTION shadow removed (a system function
     * body is the platform's implementation of a real engine function; a
     * same-signature function in the model is the ENGINE'S OWN SOURCE
     * riding in the corpus universe — spec material, never our runtime,
     * user-ratified 2026-08-18). A model element redefining a system
     * ELEMENT is an error: the system layer is protected.
     */
    public static ParsedModel withoutSystemShadows(ParsedModel parsed) {
        List<PackageableElement> kept = new ArrayList<>(parsed.elements());
        for (PackageableElement el : ELEMENTS) {
            if (el instanceof com.legend.model.FunctionDefinition) {
                kept.removeIf(e -> shadows(e, el));
                continue;
            }
            for (PackageableElement e : parsed.elements()) {
                if (shadows(e, el)) {
                    throw new com.legend.error.ModelException(
                            com.legend.error.LegendCompileException.Phase.NORMALIZE,
                            "'" + el.qualifiedName() + "' is a system element of the"
                            + " platform's metamodel layer and cannot be redefined",
                            el.qualifiedName());
                }
            }
        }
        return kept.size() == parsed.elements().size() ? parsed
                : new ParsedModel(kept, parsed.imports(), parsed.source(),
                        parsed.elementOffsets(), parsed.elementImports(),
                        parsed.elementSources(), parsed.unclaimedSections());
    }
}
