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
 * Mapping are fixed Pure SOURCE, parsed once at class load and injected
 * into every model build ({@code Compiler.buildModel}/{@code buildModule})
 * &mdash; the resolver sees them exactly as parsed elements, no parallel
 * lane. (2) {@link #seedStatements} renders the extent of the ACTIVE
 * model context as portable DDL+INSERT; the one execution-setup owner
 * ({@code StatementExecutor.executeTyped}) runs them when a resolved body
 * reads the store. (3) The FQN constants are the exact-FQN identification
 * surface (D1's ambient rule, the executor's table-reference detection).
 *
 * <p><strong>D2 &mdash; one identity for a class value: the FQN.</strong>
 * The row carries both spellings: {@code fqn} is the key
 * ({@code ~primaryKey}), {@code name} is the print form the mapping binds.
 * {@code package} is a column only &mdash; the metaclass gains the
 * property when a witness reads it, not before (charter &sect;4).
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

    /** Schema v1 (charter &sect;4): ONE table; grow BY WITNESS ONLY
     * (properties/generalizations/enum_values are future tables). */
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
                )
            )

            ###Mapping
            Mapping meta::lite::metamodel::MetamodelMapping
            (
                *meta::pure::metamodel::type::Class: Relational
                {
                    ~primaryKey([meta::lite::metamodel::MetamodelStore] metamodel.classes.fqn)
                    ~mainTable [meta::lite::metamodel::MetamodelStore] metamodel.classes
                    name: [meta::lite::metamodel::MetamodelStore] metamodel.classes.name
                }
            )
            """;

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

    /**
     * The seed CONTENT (charter &sect;5): {@code classFqns} — the active
     * context's Class extent — as rows {@code (fqn, name, package)}.
     * Pure data: the registry lookup and the row derivation live here;
     * the SQL spelling belongs to the one DDL owner
     * ({@code exec/Ddl.metamodelSeed}), called by the one
     * execution-setup owner. Rows keep the registry's sorted-by-FQN
     * order.
     */
    public static List<List<String>> seedRows(List<String> classFqns) {
        List<List<String>> rows = new ArrayList<>(classFqns.size());
        for (String fqn : classFqns) {
            int cut = fqn.lastIndexOf("::");
            String name = cut < 0 ? fqn : fqn.substring(cut + 2);
            String pkg = cut < 0 ? "" : fqn.substring(0, cut);
            rows.add(List.of(fqn, name, pkg));
        }
        return rows;
    }
}
