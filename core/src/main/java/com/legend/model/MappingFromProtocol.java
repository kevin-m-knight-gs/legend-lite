// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.model;

import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code PMapping} &rarr; {@link LegacyMappingDefinition}: the Mapping half of
 * the protocol-first migration (PARSER_COMPLETENESS_PLAN.md §1, phase M).
 *
 * <p>The target is the LEGACY SURFACE TREE, not the clean-sheet function form.
 * The model is mid-migration on two independent axes — protocol&rarr;model
 * (this class) and surface&rarr;function ({@code MappingNormalizer}) — and all
 * 1,503 corpus mapping elements are legacy-DSL, so this transform feeds the
 * normalizer exactly what {@code MappingGrammarParser} fed it. Conflating the
 * axes would turn a completion into a rewrite (§M0).
 *
 * <p><b>The interesting half is the property-mapping dispatch.</b> The wire
 * carries ONE {@code relationalOperation} per property line; the model splits
 * it into distinct variants by the SHAPE of that operation, because they
 * resolve down different paths (a {@code Join} yields an instance, a
 * {@code JoinTerminalColumn} a primitive, an {@code EnumeratedColumn} routes
 * through a value table). {@code MappingGrammarParser:1160-1277} is the spec
 * for that split and this mirrors it arm for arm.
 *
 * <p><b>Databases are threaded, never nulled.</b> Unlike a Database body —
 * where {@code RelOpFromProtocol} nulls a self-reference to match what the
 * source wrote — {@link PropertyMapping.Column#database()} and friends are
 * documented as ALWAYS populated, from the explicit {@code [DB]} bracket or
 * the enclosing class mapping's main table. So the operation transform runs
 * with a {@code null} enclosing database (nothing is elided) and the db is
 * read back off the node, with the main table as the fallback.
 *
 * <p>Everything not yet ported refuses LOUDLY through
 * {@link UnsupportedMappingShape}. A silent gap here would be invisible until
 * it generated wrong SQL, which is the failure mode this whole programme
 * exists to remove.
 */
public final class MappingFromProtocol {

    private MappingFromProtocol() {
    }

    /** A protocol shape this transform has not yet learned. Carries the
     *  wire's own vocabulary so the census and the caller agree on names. */
    public static final class UnsupportedMappingShape extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnsupportedMappingShape(String message) {
            super(message);
        }
    }

    public static LegacyMappingDefinition toMappingDefinition(Protocol.PMapping m) {
        List<MappingInclude> includes = new ArrayList<>();
        for (Protocol.PMappingInclude inc : m.includedMappings()) {
            // Read the FULL list, not the emitted pair: engine keeps only a
            // lone substitution and nulls both paths when there are several
            // (CorePureGrammarParser:504-516). legend-lite has always
            // honoured every pair, so taking the emitted fields here would
            // have silently changed which store those mappings resolve
            // against. Protocol.PStoreSubstitution carries the superset
            // without putting it on the wire.
            List<MappingInclude.StoreSubstitution> subs = new ArrayList<>();
            for (Protocol.PStoreSubstitution s : inc.substitutions()) {
                subs.add(new MappingInclude.StoreSubstitution(
                        s.sourceDatabasePath(), s.targetDatabasePath()));
            }
            includes.add(new MappingInclude(inc.includedMapping(), subs));
        }

        List<ClassMapping> classMappings = new ArrayList<>();
        for (Protocol.PClassMapping cm : m.classMappings()) {
            classMappings.add(classMapping(cm));
        }

        if (!m.associationMappings().isEmpty()) {
            throw new UnsupportedMappingShape(
                    "association mappings are not ported yet (M3): "
                            + m.associationMappings().size() + " on "
                            + m.qualifiedName());
        }
        if (!m.enumerationMappings().isEmpty()) {
            throw new UnsupportedMappingShape(
                    "enumeration mappings are not ported yet (M3): "
                            + m.enumerationMappings().size() + " on "
                            + m.qualifiedName());
        }
        // testSuites/tests: the model carries only the RAW TEXT
        // (LegacyMappingDefinition.testSuitesSource) and the wire has already
        // parsed them, so the source text is unreconstructable from here.
        // M4 keeps the lexer's verbatim slice; until then, refuse.
        if (!m.testSuites().isEmpty() || !m.tests().isEmpty()) {
            throw new UnsupportedMappingShape(
                    "testSuites/tests need the verbatim source slice (M4): "
                            + m.qualifiedName());
        }

        return new LegacyMappingDefinition(m.qualifiedName(), includes,
                classMappings, List.of(), List.of(), null);
    }

    private static ClassMapping classMapping(Protocol.PClassMapping cm) {
        if (cm instanceof Protocol.PClassMappingRel rel) {
            return relational(rel);
        }
        throw new UnsupportedMappingShape("class mapping kind "
                + cm.getClass().getSimpleName() + " is not ported yet (M3)");
    }

    private static ClassMapping relational(Protocol.PClassMappingRel rel) {
        LegacyMappingDefinition.TableReference mainTable = rel.mainTable() == null
                ? null
                : new LegacyMappingDefinition.TableReference(
                        require(rel.mainTable().database(), "main table database"),
                        tableName(rel.mainTable()));
        String mainDb = mainTable == null ? null : mainTable.database();

        if (rel.filter() != null) {
            throw new UnsupportedMappingShape(
                    "class-mapping ~filter is not ported yet (M2)");
        }

        List<RelationalOperation> groupBy = new ArrayList<>();
        for (Protocol.PRelOp op : rel.groupBy()) {
            groupBy.add(RelOpFromProtocol.op(op, null));
        }
        List<RelationalOperation> primaryKey = new ArrayList<>();
        for (Protocol.PRelOp op : rel.primaryKey()) {
            primaryKey.add(RelOpFromProtocol.op(op, null));
        }

        List<PropertyMapping> pms = new ArrayList<>();
        Map<String, String> targetSets = new LinkedHashMap<>();
        for (Protocol.PPropertyMapping pm : rel.propertyMappings()) {
            pms.add(propertyMapping(pm, mainDb, targetSets));
        }

        return new ClassMapping.Relational(rel.className(), rel.id(),
                rel.extendsClassMappingId(), rel.root(), mainTable,
                null, rel.distinct(), groupBy, primaryKey, pms,
                null, targetSets, false);
    }

    /** The wire splits schema from table; the model carries the name as
     *  written, which for a NAMED schema is {@code "schema.table"}. A
     *  double-quoted relational identifier reaches the wire WITH its quotes
     *  and the model stores it bare. */
    private static String tableName(Protocol.PTablePtr t) {
        String table = unquote(t.table());
        return t.schema() == null || "default".equals(t.schema())
                ? table : unquote(t.schema()) + "." + table;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"'
                ? s.substring(1, s.length() - 1) : s;
    }

    /** The database a property line resolves in, taken from the PROTOCOL
     *  node. It cannot be read back off the model node: the model elides a
     *  self-reference (that is the as-written rule {@code RelOpFromProtocol}
     *  applies), and here the elision is exactly what we need to undo. */
    private static @com.legend.Nullable String protocolDb(Protocol.PRelOp op) {
        if (op instanceof Protocol.PColumnRef c) {
            return c.table().database();
        }
        if (op instanceof Protocol.PElemtWithJoins j && !j.joins().isEmpty()) {
            return j.joins().get(0).db();
        }
        return null;
    }

    private static PropertyMapping propertyMapping(Protocol.PPropertyMapping pm,
            @com.legend.Nullable String mainDb, Map<String, String> targetSets) {
        if (!(pm instanceof Protocol.PRelPropertyMapping rel)) {
            throw new UnsupportedMappingShape("property mapping kind "
                    + pm.getClass().getSimpleName() + " is not ported yet (M2)");
        }
        if (rel.localMappingProperty() != null) {
            throw new UnsupportedMappingShape(
                    "+local property mappings are not ported yet (M2)");
        }
        if (rel.enumMappingId() != null) {
            throw new UnsupportedMappingShape(
                    "EnumerationMapping property mappings are not ported yet (M2)");
        }
        if (rel.target() != null) {
            targetSets.put(rel.property(), rel.target());
        }

        // The PM's own database comes off the PROTOCOL node (always
        // resolved); the OPERATION is transformed under that database so
        // inner self-references elide exactly as the legacy parser left
        // them — `concat(personTable.FIRSTNAME, ...)` keeps a null
        // databaseName on the inner ColumnRef even though the wire
        // resolved it.
        String db = firstNonNull(protocolDb(rel.relationalOperation()), mainDb);
        RelationalOperation op = RelOpFromProtocol.op(rel.relationalOperation(), db);

        if (op instanceof RelationalOperation.JoinNavigation jn) {
            String navDb = require(firstNonNull(db, jn.databaseName(), chainDb(jn)),
                    "join navigation database");
            if (jn.terminal() == null) {
                return new PropertyMapping.Join(rel.property(), navDb, jn.chain(),
                        rel.target());
            }
            return new PropertyMapping.JoinTerminalColumn(rel.property(), navDb,
                    jn.chain(), jn.terminal());
        }
        if (op instanceof RelationalOperation.ColumnRef cr) {
            return new PropertyMapping.Column(rel.property(),
                    require(db, "column database"), cr.table(), cr.column());
        }
        return new PropertyMapping.Expression(rel.property(), op);
    }

    /** A nav's own db may be absent while its FIRST hop carries one. */
    private static @com.legend.Nullable String chainDb(
            RelationalOperation.JoinNavigation jn) {
        return jn.chain().isEmpty() ? null : jn.chain().get(0).databaseName();
    }

    @SafeVarargs
    private static @com.legend.Nullable String firstNonNull(
            @com.legend.Nullable String... candidates) {
        for (String c : candidates) {
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static String require(@com.legend.Nullable String v, String what) {
        if (v == null) {
            throw new UnsupportedMappingShape(
                    "the wire carries no " + what + " here");
        }
        return v;
    }
}
