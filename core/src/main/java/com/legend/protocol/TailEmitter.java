// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol;

import java.util.List;

/**
 * Wire emission for the TAIL-section elements — Text,
 * GenerationSpecification, FileGeneration, Deephaven store, MongoDB,
 * Elasticsearch and the DataQuality trio. Byte shapes probed
 * (ZTailProbe "tailShapes*"); the DUPLICATED {@code _type} /
 * {@code _pure_protocol_type} keys are the engine serializer's own
 * output and are reproduced literally.
 */
final class TailEmitter {

    private TailEmitter() {
    }

    static void text(StringBuilder b, Protocol.PText t) {
        b.append("{\"_type\":\"text\",\"content\":");
        ProtocolEmitter.str(b, t.content());
        b.append(",\"name\":");
        ProtocolEmitter.str(b, t.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, t.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, t.sourceInformation());
        if (t.type() != null) {
            b.append(",\"type\":");
            ProtocolEmitter.str(b, t.type());
        }
        b.append('}');
    }

    static void generationSpecification(StringBuilder b,
            Protocol.PGenerationSpecification g) {
        b.append("{\"_type\":\"generationSpecification\","
                + "\"fileGenerations\":[");
        for (int i = 0; i < g.fileGenerations().size(); i++) {
            Protocol.PPointer p = g.fileGenerations().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"path\":");
            ProtocolEmitter.str(b, p.path());
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, p.sourceInformation());
            b.append(",\"type\":\"FILE_GENERATION\"}");
        }
        b.append("],\"generationNodes\":[");
        for (int i = 0; i < g.generationNodes().size(); i++) {
            Protocol.PGenerationNode n = g.generationNodes().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"generationElement\":");
            ProtocolEmitter.str(b, n.generationElement());
            b.append(",\"id\":");
            ProtocolEmitter.str(b, n.id());
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, n.sourceInformation());
            b.append('}');
        }
        b.append("],\"name\":");
        ProtocolEmitter.str(b, g.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, g.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, g.sourceInformation());
        b.append('}');
    }

    static void fileGeneration(StringBuilder b, Protocol.PFileGeneration f) {
        b.append("{\"_type\":\"fileGeneration\","
                + "\"configurationProperties\":[");
        for (int i = 0; i < f.configurationProperties().size(); i++) {
            Protocol.PConfigProperty p = f.configurationProperties().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"name\":");
            ProtocolEmitter.str(b, p.name());
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, p.sourceInformation());
            b.append(",\"value\":");
            configValue(b, p.value());
            b.append('}');
        }
        b.append(']');
        if (f.generationOutputPath() != null) {
            b.append(",\"generationOutputPath\":");
            ProtocolEmitter.str(b, f.generationOutputPath());
        }
        b.append(",\"name\":");
        ProtocolEmitter.str(b, f.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, f.pkg());
        b.append(",\"scopeElements\":[");
        for (int i = 0; i < f.scopeElements().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            ProtocolEmitter.str(b, f.scopeElements().get(i));
        }
        b.append("],\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, f.sourceInformation());
        b.append(",\"type\":");
        ProtocolEmitter.str(b, f.type());
        b.append(",\"typeSourceInformation\":");
        ProtocolEmitter.srcInfo(b, f.typeSourceInformation());
        b.append('}');
    }

    private static void configValue(StringBuilder b, Protocol.PConfigValue v) {
        switch (v) {
            case Protocol.PConfigValue.PCString s ->
                    ProtocolEmitter.str(b, s.value());
            case Protocol.PConfigValue.PCBoolean bo -> b.append(bo.value());
            case Protocol.PConfigValue.PCInteger i -> b.append(i.value());
            case Protocol.PConfigValue.PCStrings ss -> {
                b.append('[');
                for (int i = 0; i < ss.values().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    ProtocolEmitter.str(b, ss.values().get(i));
                }
                b.append(']');
            }
            case Protocol.PConfigValue.PCMap m -> {
                b.append('{');
                boolean first = true;
                for (var e : m.entries().entrySet()) {
                    if (!first) {
                        b.append(',');
                    }
                    first = false;
                    ProtocolEmitter.str(b, e.getKey());
                    b.append(':');
                    ProtocolEmitter.str(b, e.getValue());
                }
                b.append('}');
            }
        }
    }

    static void deephavenStore(StringBuilder b, Protocol.PDeephavenDatabase d) {
        b.append("{\"_type\":\"deephavenStore\",\"includedStores\":[],"
                + "\"name\":");
        ProtocolEmitter.str(b, d.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, d.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, d.sourceInformation());
        b.append(",\"tables\":[");
        for (int t = 0; t < d.tables().size(); t++) {
            var table = d.tables().get(t);
            if (t > 0) {
                b.append(',');
            }
            b.append("{\"columns\":[");
            for (int i = 0; i < table.columns().size(); i++) {
                Protocol.PDeephavenColumn col = table.columns().get(i);
                if (i > 0) {
                    b.append(',');
                }
                b.append("{\"_type\":\"column\",\"name\":");
                ProtocolEmitter.str(b, col.name());
                // the engine serializer prints the type discriminator
                // TWICE — reproduced literally for byte parity
                b.append(",\"type\":{\"_type\":\"").append(col.kind())
                        .append("\",\"_type\":\"").append(col.kind())
                        .append('"');
                if (col.precision() != null) {
                    b.append(",\"precision\":").append(col.precision())
                            .append(",\"scale\":").append(col.scale());
                }
                b.append("}}");
            }
            b.append("],\"name\":");
            ProtocolEmitter.str(b, table.name());
            b.append('}');
        }
        b.append("]}");
    }

    static void elasticsearchStore(StringBuilder b,
            Protocol.PElasticsearch7Cluster s) {
        b.append("{\"_type\":\"elasticsearch7Store\",\"includedStores\":[],"
                + "\"indices\":[");
        for (int i = 0; i < s.indices().size(); i++) {
            var idx = s.indices().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"indexName\":");
            ProtocolEmitter.str(b, idx.indexName());
            b.append(",\"properties\":[");
            for (int p = 0; p < idx.properties().size(); p++) {
                var prop = idx.properties().get(p);
                if (p > 0) {
                    b.append(',');
                }
                String k = prop.typeKey();
                b.append("{\"property\":{\"").append(k)
                        .append("\":{\"_pure_protocol_type\":\"").append(k)
                        .append("Property\",\"_pure_protocol_type\":\"")
                        .append(k).append("Property\",\"type\":\"").append(k)
                        .append("\"}},\"propertyName\":");
                ProtocolEmitter.str(b, prop.propertyName());
                b.append('}');
            }
            b.append("]}");
        }
        b.append("],\"name\":");
        ProtocolEmitter.str(b, s.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, s.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, s.sourceInformation());
        b.append('}');
    }

    static void mongoDatabase(StringBuilder b, Protocol.PMongoDatabase m) {
        b.append("{\"_type\":\"MongoDatabase\",\"collections\":[");
        for (int i = 0; i < m.collections().size(); i++) {
            var coll = m.collections().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"name\":");
            ProtocolEmitter.str(b, coll.name());
            b.append(",\"validator\":{\"validationAction\":");
            ProtocolEmitter.str(b, coll.validationAction());
            b.append(",\"validationLevel\":");
            ProtocolEmitter.str(b, coll.validationLevel());
            b.append(",\"validatorExpression\":{\"_type\":"
                    + "\"jsonSchemaExpression\",\"schemaExpression\":");
            bsonSchema(b, coll.schema(), true);
            b.append("}}}");
        }
        b.append("],\"includedStores\":[],\"name\":");
        ProtocolEmitter.str(b, m.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, m.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, m.sourceInformation());
        b.append(",\"views\":[]}");
    }

    /** The BSON schema wire: object nodes carry
     *  additionalPropertiesAllowed/properties/required; scalar nodes only
     *  their facets. Slots alphabetical, arrays always present. */
    private static void bsonSchema(StringBuilder b, Protocol.PBsonSchema s,
            boolean object) {
        b.append("{\"_type\":\"").append(s.wireType())
                .append("\",\"_enum\":[]");
        boolean isObject = "schema".equals(s.wireType())
                || "objectType".equals(s.wireType());
        if (isObject) {
            b.append(",\"additionalPropertiesAllowed\":").append(
                    s.additionalPropertiesAllowed() != null
                            && s.additionalPropertiesAllowed());
        }
        b.append(",\"allOf\":[],\"anyOf\":[]");
        if (s.description() != null) {
            b.append(",\"description\":");
            ProtocolEmitter.str(b, s.description());
        }
        if (s.maxLength() != null) {
            b.append(",\"maxLength\":").append(s.maxLength());
        }
        if (s.minLength() != null) {
            b.append(",\"minLength\":").append(s.minLength());
        }
        b.append(",\"oneOf\":[]");
        if (isObject) {
            b.append(",\"properties\":[");
            for (int i = 0; i < s.properties().size(); i++) {
                var e = s.properties().get(i);
                if (i > 0) {
                    b.append(',');
                }
                b.append("{\"key\":");
                ProtocolEmitter.str(b, e.getKey());
                b.append(",\"value\":");
                bsonSchema(b, e.getValue(), false);
                b.append('}');
            }
            b.append("],\"required\":[");
            for (int i = 0; i < s.required().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                ProtocolEmitter.str(b, s.required().get(i));
            }
            b.append(']');
            if (s.title() != null) {
                b.append(",\"title\":");
                ProtocolEmitter.str(b, s.title());
            }
        }
        b.append('}');
    }

    static void schemaSet(StringBuilder b, Protocol.PSchemaSet s) {
        b.append("{\"_type\":\"externalFormatSchemaSet\",\"format\":");
        ProtocolEmitter.str(b, s.format());
        b.append(",\"name\":");
        ProtocolEmitter.str(b, s.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, s.pkg());
        b.append(",\"schemas\":[");
        for (int i = 0; i < s.schemas().size(); i++) {
            Protocol.PSchema sc = s.schemas().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"content\":");
            ProtocolEmitter.str(b, sc.content());
            b.append(",\"contentSourceInformation\":");
            ProtocolEmitter.srcInfo(b, sc.contentSourceInformation());
            if (sc.id() != null) {
                b.append(",\"id\":");
                ProtocolEmitter.str(b, sc.id());
            }
            if (sc.location() != null) {
                b.append(",\"location\":");
                ProtocolEmitter.str(b, sc.location());
            }
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, sc.sourceInformation());
            b.append('}');
        }
        b.append("],\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, s.sourceInformation());
        b.append('}');
    }

    static void binding(StringBuilder b, Protocol.PBinding bd) {
        b.append("{\"_type\":\"binding\",\"contentType\":");
        ProtocolEmitter.str(b, bd.contentType());
        b.append(",\"modelUnit\":{\"packageableElementExcludes\":[");
        for (int i = 0; i < bd.modelExcludes().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            ProtocolEmitter.str(b, bd.modelExcludes().get(i));
        }
        b.append("],\"packageableElementIncludes\":[");
        for (int i = 0; i < bd.modelIncludes().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            ProtocolEmitter.str(b, bd.modelIncludes().get(i));
        }
        b.append("]},\"name\":");
        ProtocolEmitter.str(b, bd.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, bd.pkg());
        if (bd.schemaId() != null) {
            b.append(",\"schemaId\":");
            ProtocolEmitter.str(b, bd.schemaId());
        }
        if (bd.schemaSet() != null) {
            b.append(",\"schemaSet\":");
            ProtocolEmitter.str(b, bd.schemaSet());
        }
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, bd.sourceInformation());
        b.append('}');
    }

    static void serviceStore(StringBuilder b,
            Protocol.PServiceStoreDefinition s) {
        b.append("{\"_type\":\"serviceStore\",\"elements\":[");
        for (int i = 0; i < s.elements().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            serviceStoreElement(b, s.elements().get(i));
        }
        b.append("],\"includedStores\":[],\"name\":");
        ProtocolEmitter.str(b, s.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, s.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, s.sourceInformation());
        b.append('}');
    }

    private static void serviceStoreElement(StringBuilder b,
            Protocol.PServiceStoreElement e) {
        switch (e) {
            case Protocol.PSsService sv -> ssService(b, sv);
            case Protocol.PSsServiceGroup g -> {
                b.append("{\"_type\":\"serviceGroup\",\"elements\":[");
                for (int i = 0; i < g.elements().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    serviceStoreElement(b, g.elements().get(i));
                }
                b.append("],\"id\":");
                ProtocolEmitter.str(b, g.id());
                b.append(",\"path\":");
                ProtocolEmitter.str(b, g.path());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, g.sourceInformation());
                b.append('}');
            }
        }
    }

    private static void ssService(StringBuilder b, Protocol.PSsService sv) {
        b.append("{\"_type\":\"service\",\"id\":");
        ProtocolEmitter.str(b, sv.id());
        b.append(",\"method\":");
        ProtocolEmitter.str(b, sv.method());
        if (sv.parameters() != null) {
            b.append(",\"parameters\":[");
            for (int i = 0; i < sv.parameters().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                ssParam(b, sv.parameters().get(i));
            }
            b.append(']');
        }
        b.append(",\"path\":");
        ProtocolEmitter.str(b, sv.path());
        if (sv.requestBody() != null) {
            b.append(",\"requestBody\":");
            ssTypeRef(b, sv.requestBody());
        }
        b.append(",\"response\":");
        ssTypeRef(b, sv.response());
        if (!sv.security().isEmpty()) {
            throw new IllegalStateException("non-empty ServiceStore security"
                    + " wire is unprobed");
        }
        b.append(",\"security\":[],\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, sv.sourceInformation());
        b.append('}');
    }

    private static void ssParam(StringBuilder b, Protocol.PSsParam p) {
        b.append('{');
        if (p.allowReserved() != null) {
            b.append("\"allowReserved\":").append(p.allowReserved())
                    .append(',');
        }
        if (p.enumeration() != null) {
            b.append("\"enumeration\":");
            ProtocolEmitter.str(b, p.enumeration());
            b.append(',');
        }
        b.append("\"location\":");
        ProtocolEmitter.str(b, p.location());
        b.append(",\"name\":");
        ProtocolEmitter.str(b, p.name());
        if (p.required() != null) {
            b.append(",\"required\":").append(p.required());
        }
        b.append(",\"serializationFormat\":{");
        if (p.explode() != null) {
            b.append("\"explode\":").append(p.explode())
                    .append(",\"explodeSourceInformation\":");
            ProtocolEmitter.srcInfo(b, java.util.Objects.requireNonNull(
                    p.explodeSpan()));
        }
        if (p.style() != null) {
            if (p.explode() != null) {
                b.append(',');
            }
            b.append("\"style\":");
            ProtocolEmitter.str(b, p.style());
            b.append(",\"styleSourceInformation\":");
            ProtocolEmitter.srcInfo(b, java.util.Objects.requireNonNull(
                    p.styleSpan()));
        }
        b.append("},\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, p.sourceInformation());
        b.append(",\"type\":");
        ssTypeRef(b, p.type());
        b.append('}');
    }

    private static void ssTypeRef(StringBuilder b, Protocol.PSsTypeRef t) {
        if (t.complexType() != null) {
            b.append("{\"_type\":\"complex\",\"binding\":");
            ProtocolEmitter.str(b,
                    java.util.Objects.requireNonNull(t.binding()));
            b.append(",\"list\":").append(t.list())
                    .append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, t.sourceInformation());
            b.append(",\"type\":");
            ProtocolEmitter.str(b, t.complexType());
            b.append('}');
        } else {
            b.append("{\"_type\":\"")
                    .append(java.util.Objects.requireNonNull(t.primitive())
                            .toLowerCase(java.util.Locale.ROOT))
                    .append("\",\"list\":").append(t.list())
                    .append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, t.sourceInformation());
            b.append('}');
        }
    }

    static void diagram(StringBuilder b, Protocol.PDiagram d) {
        b.append("{\"_type\":\"diagram\",\"classViews\":[");
        for (int i = 0; i < d.classViews().size(); i++) {
            Protocol.PClassView v = d.classViews().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"class\":");
            ProtocolEmitter.str(b, v.classPath());
            b.append(",\"classSourceInformation\":");
            ProtocolEmitter.srcInfo(b, v.classSpan());
            if (v.hideProperties() != null) {
                b.append(",\"hideProperties\":").append(v.hideProperties());
            }
            if (v.hideStereotypes() != null) {
                b.append(",\"hideStereotypes\":").append(v.hideStereotypes());
            }
            if (v.hideTaggedValues() != null) {
                b.append(",\"hideTaggedValues\":")
                        .append(v.hideTaggedValues());
            }
            b.append(",\"id\":");
            ProtocolEmitter.str(b, v.id());
            b.append(",\"position\":{\"x\":").append(v.x())
                    .append(",\"y\":").append(v.y())
                    .append("},\"rectangle\":{\"height\":").append(v.height())
                    .append(",\"width\":").append(v.width())
                    .append("},\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, v.sourceInformation());
            b.append('}');
        }
        b.append("],\"generalizationViews\":[");
        for (int i = 0; i < d.generalizationViews().size(); i++) {
            Protocol.PGeneralizationView v = d.generalizationViews().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append('{');
            diagramLine(b, v.points());
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, v.sourceInformation());
            diagramEnds(b, v.sourceView(), v.sourceViewSpan(),
                    v.targetView(), v.targetViewSpan());
            b.append('}');
        }
        b.append("],\"name\":");
        ProtocolEmitter.str(b, d.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, d.pkg());
        b.append(",\"propertyViews\":[");
        for (int i = 0; i < d.propertyViews().size(); i++) {
            Protocol.PPropertyView v = d.propertyViews().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append('{');
            diagramLine(b, v.points());
            b.append(",\"property\":{\"class\":");
            ProtocolEmitter.str(b, v.propertyClass());
            b.append(",\"property\":");
            ProtocolEmitter.str(b, v.propertyName());
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, v.propertySpan());
            b.append("},\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, v.sourceInformation());
            diagramEnds(b, v.sourceView(), v.sourceViewSpan(),
                    v.targetView(), v.targetViewSpan());
            b.append('}');
        }
        b.append("],\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, d.sourceInformation());
        b.append('}');
    }

    private static void diagramLine(StringBuilder b,
            List<Protocol.PDiagramPoint> points) {
        b.append("\"line\":{\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"x\":").append(points.get(i).x())
                    .append(",\"y\":").append(points.get(i).y())
                    .append('}');
        }
        b.append("]}");
    }

    private static void diagramEnds(StringBuilder b, String sourceView,
            SourceInfo sourceViewSpan, String targetView,
            SourceInfo targetViewSpan) {
        b.append(",\"sourceView\":");
        ProtocolEmitter.str(b, sourceView);
        b.append(",\"sourceViewSourceInformation\":");
        ProtocolEmitter.srcInfo(b, sourceViewSpan);
        b.append(",\"targetView\":");
        ProtocolEmitter.str(b, targetView);
        b.append(",\"targetViewSourceInformation\":");
        ProtocolEmitter.srcInfo(b, targetViewSpan);
    }

    static void dataSpace(StringBuilder b, Protocol.PDataSpace d) {
        b.append("{\"_type\":\"dataSpace\"");
        if (d.defaultExecutionContext() != null) {
            b.append(",\"defaultExecutionContext\":");
            ProtocolEmitter.str(b, d.defaultExecutionContext());
        }
        if (d.description() != null) {
            b.append(",\"description\":");
            ProtocolEmitter.str(b, d.description());
        }
        if (d.diagrams() != null) {
            b.append(",\"diagrams\":[");
            for (int i = 0; i < d.diagrams().size(); i++) {
                Protocol.PDataSpaceDiagram g = d.diagrams().get(i);
                if (i > 0) {
                    b.append(',');
                }
                b.append('{');
                if (g.description() != null) {
                    b.append("\"description\":");
                    ProtocolEmitter.str(b, g.description());
                    b.append(',');
                }
                b.append("\"diagram\":{\"path\":");
                ProtocolEmitter.str(b, g.diagram());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, g.diagramSpan());
                b.append("},\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, g.sourceInformation());
                b.append(",\"title\":");
                ProtocolEmitter.str(b, g.title());
                b.append('}');
            }
            b.append(']');
        }
        if (d.elements() != null) {
            b.append(",\"elements\":[");
            for (int i = 0; i < d.elements().size(); i++) {
                Protocol.PDataSpaceElementRef ref = d.elements().get(i);
                if (i > 0) {
                    b.append(',');
                }
                b.append('{');
                if (ref.exclude()) {
                    b.append("\"exclude\":true,");
                }
                b.append("\"path\":");
                ProtocolEmitter.str(b, ref.path());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, ref.sourceInformation());
                b.append('}');
            }
            b.append(']');
        }
        if (d.executables() != null) {
            b.append(",\"executables\":[");
            for (int i = 0; i < d.executables().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                dataSpaceExecutable(b, d.executables().get(i));
            }
            b.append(']');
        }
        b.append(",\"executionContexts\":[");
        for (int i = 0; i < d.executionContexts().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            dataSpaceContext(b, d.executionContexts().get(i));
        }
        b.append("],\"name\":");
        ProtocolEmitter.str(b, d.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, d.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, d.sourceInformation());
        b.append(",\"stereotypes\":");
        ProtocolEmitter.stereotypes(b, d.stereotypes());
        if (d.supportInfo() != null) {
            b.append(",\"supportInfo\":");
            dataSpaceSupport(b, d.supportInfo());
        }
        b.append(",\"taggedValues\":");
        ProtocolEmitter.taggedValues(b, d.taggedValues());
        if (d.title() != null) {
            b.append(",\"title\":");
            ProtocolEmitter.str(b, d.title());
        }
        b.append('}');
    }

    private static void dataSpaceContext(StringBuilder b,
            Protocol.PDataSpaceContext ctx) {
        b.append("{\"defaultRuntime\":");
        pointer(b, ctx.defaultRuntime(), ctx.runtimeSpan(), "RUNTIME");
        if (ctx.description() != null) {
            b.append(",\"description\":");
            ProtocolEmitter.str(b, ctx.description());
        }
        b.append(",\"mapping\":");
        pointer(b, ctx.mapping(), ctx.mappingSpan(), "MAPPING");
        b.append(",\"name\":");
        ProtocolEmitter.str(b, ctx.name());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, ctx.sourceInformation());
        Protocol.PDataSpaceTestData td = ctx.testData();
        if (td != null) {
            if (!"Reference".equals(td.kind())) {
                throw new IllegalStateException("unprobed dataspace testData"
                        + " kind: " + td.kind());
            }
            b.append(",\"testData\":{\"_type\":\"reference\","
                    + "\"dataElement\":");
            pointer(b, td.path(), td.sourceInformation(), "DATA");
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, td.sourceInformation());
            b.append('}');
        }
        if (ctx.title() != null) {
            b.append(",\"title\":");
            ProtocolEmitter.str(b, ctx.title());
        }
        b.append('}');
    }

    private static void dataSpaceExecutable(StringBuilder b,
            Protocol.PDataSpaceExecutable e) {
        boolean template = e.query() != null;
        b.append("{\"_type\":\"").append(template
                ? "dataSpaceTemplateExecutable"
                : "dataSpacePackageableElementExecutable").append('"');
        if (e.description() != null) {
            b.append(",\"description\":");
            ProtocolEmitter.str(b, e.description());
        }
        if (!template) {
            String path = java.util.Objects.requireNonNull(e.executable());
            b.append(",\"executable\":{\"path\":");
            ProtocolEmitter.str(b, path);
            b.append(",\"sourceInformation\":");
            ProtocolEmitter.srcInfo(b, java.util.Objects.requireNonNull(
                    e.executableSpan()));
            // a FUNCTION-pointer executable (signature form) carries a
            // type tag; a plain element pointer does not (DIFF-pinned)
            if (path.indexOf('(') >= 0) {
                b.append(",\"type\":\"FUNCTION\"");
            }
            b.append('}');
        }
        if (e.executionContextKey() != null) {
            b.append(",\"executionContextKey\":");
            ProtocolEmitter.str(b, e.executionContextKey());
        }
        if (e.id() != null) {
            b.append(",\"id\":");
            ProtocolEmitter.str(b, e.id());
        }
        if (template) {
            b.append(",\"query\":");
            ProtocolEmitter.valueSpec(b,
                    java.util.Objects.requireNonNull(e.query()));
        }
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, e.sourceInformation());
        b.append(",\"title\":");
        ProtocolEmitter.str(b, e.title());
        b.append('}');
    }

    private static void dataSpaceSupport(StringBuilder b,
            Protocol.PDataSpaceSupport s) {
        switch (s) {
            case Protocol.PDataSpaceSupport.PSupportEmail e -> {
                b.append("{\"_type\":\"email\",\"address\":");
                ProtocolEmitter.str(b, e.address());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, e.sourceInformation());
                b.append('}');
            }
            case Protocol.PDataSpaceSupport.PSupportCombined cb -> {
                b.append("{\"_type\":\"combined\"");
                if (cb.documentationUrl() != null) {
                    b.append(",\"documentationUrl\":");
                    ProtocolEmitter.str(b, cb.documentationUrl());
                }
                if (cb.emails() != null) {
                    b.append(",\"emails\":[");
                    for (int i = 0; i < cb.emails().size(); i++) {
                        if (i > 0) {
                            b.append(',');
                        }
                        ProtocolEmitter.str(b, cb.emails().get(i));
                    }
                    b.append(']');
                }
                if (cb.faqUrl() != null) {
                    b.append(",\"faqUrl\":");
                    ProtocolEmitter.str(b, cb.faqUrl());
                }
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, cb.sourceInformation());
                if (cb.supportUrl() != null) {
                    b.append(",\"supportUrl\":");
                    ProtocolEmitter.str(b, cb.supportUrl());
                }
                if (cb.website() != null) {
                    b.append(",\"website\":");
                    ProtocolEmitter.str(b, cb.website());
                }
                b.append('}');
            }
        }
    }

    static void dataQualityValidation(StringBuilder b,
            Protocol.PDataQualityValidation v) {
        b.append("{\"_type\":\"dataQualityValidation\",\"context\":");
        dqContext(b, v);
        b.append(",\"dataQualityRootGraphFetchTree\":");
        dqTree(b, v.validationTree(), true);
        if (v.filter() != null) {
            b.append(",\"filter\":");
            ProtocolEmitter.valueSpec(b, v.filter());
        }
        b.append(",\"name\":");
        ProtocolEmitter.str(b, v.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, v.pkg());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, v.sourceInformation());
        b.append(",\"stereotypes\":");
        ProtocolEmitter.stereotypes(b, v.stereotypes());
        b.append(",\"taggedValues\":");
        ProtocolEmitter.taggedValues(b, v.taggedValues());
        b.append('}');
    }

    private static void dqContext(StringBuilder b,
            Protocol.PDataQualityValidation v) {
        switch (v.contextKind()) {
            case "fromMappingAndRuntime" -> {
                b.append("{\"_type\":"
                        + "\"mappingAndRuntimeDataQualityExecutionContext\","
                        + "\"_type\":"
                        + "\"mappingAndRuntimeDataQualityExecutionContext\","
                        + "\"mapping\":");
                pointer(b, v.contextPath(), v.contextPathSpan(), "MAPPING");
                b.append(",\"runtime\":");
                pointer(b, java.util.Objects.requireNonNull(v.contextSecond()),
                        java.util.Objects.requireNonNull(v.contextSecondSpan()),
                        "RUNTIME");
                b.append('}');
            }
            case "fromDataSpace" -> {
                b.append("{\"_type\":"
                        + "\"dataSpaceDataQualityExecutionContext\","
                        + "\"_type\":"
                        + "\"dataSpaceDataQualityExecutionContext\","
                        + "\"context\":");
                ProtocolEmitter.str(b,
                        java.util.Objects.requireNonNull(v.contextSecond()));
                b.append(",\"dataSpace\":");
                pointer(b, v.contextPath(), v.contextPathSpan(), "DATASPACE");
                b.append('}');
            }
            default -> throw new IllegalStateException(
                    "unmapped DataQuality context kind: " + v.contextKind());
        }
    }

    private static void pointer(StringBuilder b, String path,
            SourceInfo span, String type) {
        b.append("{\"path\":");
        ProtocolEmitter.str(b, path);
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, span);
        b.append(",\"type\":\"").append(type).append("\"}");
    }

    private static void dqTree(StringBuilder b, Protocol.PDqTreeNode n,
            boolean root) {
        String t = root ? "dataQualityRootGraphFetchTree"
                : "dataQualityPropertyGraphFetchTree";
        b.append("{\"_type\":\"").append(t).append("\",\"_type\":\"")
                .append(t).append('"');
        if (root) {
            b.append(",\"class\":");
            ProtocolEmitter.str(b,
                    java.util.Objects.requireNonNull(n.className()));
        }
        b.append(",\"constraints\":[");
        for (int i = 0; i < n.constraints().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            ProtocolEmitter.str(b, n.constraints().get(i));
        }
        b.append(']');
        if (!root) {
            b.append(",\"parameters\":[],\"property\":");
            ProtocolEmitter.str(b,
                    java.util.Objects.requireNonNull(n.property()));
        }
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, n.sourceInformation());
        b.append(",\"subTrees\":[");
        for (int i = 0; i < n.subTrees().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            dqTree(b, n.subTrees().get(i), false);
        }
        b.append("],\"subTypeTrees\":[]}");
    }

    static void dataQualityRelationValidation(StringBuilder b,
            Protocol.PDataQualityRelationValidation v) {
        b.append("{\"_type\":\"dataqualityRelationValidation\",\"name\":");
        ProtocolEmitter.str(b, v.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, v.pkg());
        b.append(",\"query\":");
        ProtocolEmitter.valueSpec(b, v.query());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, v.sourceInformation());
        b.append(",\"stereotypes\":");
        ProtocolEmitter.stereotypes(b, v.stereotypes());
        b.append(",\"taggedValues\":");
        ProtocolEmitter.taggedValues(b, v.taggedValues());
        b.append(",\"validations\":[");
        for (int i = 0; i < v.validations().size(); i++) {
            Protocol.PDqRelationCheck ch = v.validations().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"assertion\":");
            ProtocolEmitter.valueSpec(b, ch.assertion());
            if (ch.description() != null) {
                b.append(",\"description\":");
                ProtocolEmitter.str(b, ch.description());
            }
            b.append(",\"name\":");
            ProtocolEmitter.str(b, ch.name());
            b.append('}');
        }
        b.append("]}");
    }

    static void dataQualityRelationComparison(StringBuilder b,
            Protocol.PDataQualityRelationComparison v) {
        b.append("{\"_type\":\"dataQualityRelationComparison\","
                + "\"columnsToCompare\":[],\"keys\":[");
        List<String> keys = v.keys();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            ProtocolEmitter.str(b, keys.get(i));
        }
        b.append("],\"name\":");
        ProtocolEmitter.str(b, v.name());
        b.append(",\"package\":");
        ProtocolEmitter.str(b, v.pkg());
        b.append(",\"source\":");
        ProtocolEmitter.valueSpec(b, v.source());
        b.append(",\"sourceInformation\":");
        ProtocolEmitter.srcInfo(b, v.sourceInformation());
        b.append(",\"strategy\":{\"_type\":\"")
                .append(strategyWire(v.strategy()))
                .append("\"},\"target\":");
        ProtocolEmitter.valueSpec(b, v.target());
        b.append('}');
    }

    private static String strategyWire(String strategy) {
        if ("MD5Hash".equals(strategy)) {
            return "md5Hash";
        }
        throw new IllegalStateException(
                "unmapped comparison strategy: " + strategy);
    }
}
