// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol;

import com.legend.protocol.spec.Gql;

/**
 * The GraphQL AST wire ({@code executableDocument} et al.) — byte-exact
 * against the live oracle (probe gql-wire 2026-08-14; the gql-islands
 * battery in AdversarialParityTest pins every shape). Fields alphabetical
 * per node; a bare-selection operation OMITS {@code type}; the alias
 * carries its colon; variable definitions keep {@code $}.
 */
final class GqlEmitter {

    private GqlEmitter() {
    }

    static void document(StringBuilder b, Gql.Document doc) {
        b.append("{\"_type\":\"executableDocument\",\"definitions\":[");
        for (int i = 0; i < doc.definitions().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            definition(b, doc.definitions().get(i));
        }
        b.append("]}");
    }

    private static void definition(StringBuilder b, Gql.Definition d) {
        switch (d) {
            case Gql.Operation op -> {
                b.append("{\"_type\":\"operationDefinition\","
                        + "\"directives\":");
                directives(b, op.directives());
                if (op.name() != null) {
                    b.append(",\"name\":");
                    ProtocolEmitter.str(b, op.name());
                }
                b.append(",\"selectionSet\":");
                selections(b, op.selectionSet());
                if (op.type() != null) {
                    b.append(",\"type\":");
                    ProtocolEmitter.str(b, op.type());
                }
                b.append(",\"variables\":[");
                for (int i = 0; i < op.variables().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    variableDef(b, op.variables().get(i));
                }
                b.append("]}");
            }
            case Gql.Fragment f -> {
                b.append("{\"_type\":\"fragmentDefinition\","
                        + "\"directives\":");
                directives(b, f.directives());
                b.append(",\"name\":");
                ProtocolEmitter.str(b, f.name());
                b.append(",\"selectionSet\":");
                selections(b, f.selectionSet());
                b.append(",\"typeCondition\":");
                ProtocolEmitter.str(b, f.typeCondition());
                b.append('}');
            }
            case Gql.ObjectType t -> {
                b.append("{\"_type\":\"objectTypeDefinition\","
                        + "\"_implements\":[],\"directives\":[],"
                        + "\"fields\":[");
                for (int i = 0; i < t.fields().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    Gql.FieldDef fd = t.fields().get(i);
                    b.append("{\"argumentDefinitions\":[],\"directives\":[],"
                            + "\"name\":");
                    ProtocolEmitter.str(b, fd.name());
                    b.append(",\"type\":");
                    typeRef(b, fd.type());
                    b.append('}');
                }
                b.append("],\"name\":");
                ProtocolEmitter.str(b, t.name());
                b.append('}');
            }
        }
    }

    private static void selections(StringBuilder b,
            java.util.List<Gql.Selection> sels) {
        b.append('[');
        for (int i = 0; i < sels.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            switch (sels.get(i)) {
                case Gql.Field f -> {
                    b.append("{\"_type\":\"field\"");
                    if (f.alias() != null) {
                        b.append(",\"alias\":");
                        ProtocolEmitter.str(b, f.alias());
                    }
                    b.append(",\"arguments\":");
                    arguments(b, f.arguments());
                    b.append(",\"directives\":");
                    directives(b, f.directives());
                    b.append(",\"name\":");
                    ProtocolEmitter.str(b, f.name());
                    b.append(",\"selectionSet\":");
                    selections(b, f.selectionSet());
                    b.append('}');
                }
                case Gql.FragmentSpread fs -> {
                    b.append("{\"_type\":\"fragmentSpread\",\"directives\":");
                    directives(b, fs.directives());
                    b.append(",\"name\":");
                    ProtocolEmitter.str(b, fs.name());
                    b.append('}');
                }
            }
        }
        b.append(']');
    }

    private static void arguments(StringBuilder b,
            java.util.List<Gql.Argument> args) {
        b.append('[');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"name\":");
            ProtocolEmitter.str(b, args.get(i).name());
            b.append(",\"value\":");
            value(b, args.get(i).value());
            b.append('}');
        }
        b.append(']');
    }

    private static void directives(StringBuilder b,
            java.util.List<Gql.Directive> dirs) {
        b.append('[');
        for (int i = 0; i < dirs.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"arguments\":");
            arguments(b, dirs.get(i).arguments());
            b.append(",\"name\":");
            ProtocolEmitter.str(b, dirs.get(i).name());
            b.append('}');
        }
        b.append(']');
    }

    private static void variableDef(StringBuilder b, Gql.VariableDef v) {
        b.append('{');
        if (v.defaultValue() != null) {
            b.append("\"defaultValue\":");
            value(b, v.defaultValue());
            b.append(',');
        }
        b.append("\"directives\":[],\"name\":");
        ProtocolEmitter.str(b, v.dollarName());
        b.append(",\"type\":");
        typeRef(b, v.type());
        b.append('}');
    }

    private static void typeRef(StringBuilder b, Gql.TypeRef t) {
        switch (t) {
            case Gql.NamedType n -> {
                b.append("{\"_type\":\"namedTypeReference\",\"name\":");
                ProtocolEmitter.str(b, n.name());
                b.append(",\"nullable\":").append(n.nullable()).append('}');
            }
            case Gql.ListType l -> {
                b.append("{\"_type\":\"listTypeReference\",\"itemType\":");
                typeRef(b, l.itemType());
                b.append(",\"nullable\":").append(l.nullable()).append('}');
            }
        }
    }

    private static void value(StringBuilder b, Gql.Value v) {
        switch (v) {
            case Gql.IntValue i -> b.append("{\"_type\":\"intValue\","
                    + "\"value\":").append(i.value()).append('}');
            case Gql.FloatValue f -> b.append("{\"_type\":\"floatValue\","
                    + "\"value\":").append(f.value()).append('}');
            case Gql.StringValue s -> {
                b.append("{\"_type\":\"stringValue\",\"value\":");
                ProtocolEmitter.str(b, s.value());
                b.append('}');
            }
            case Gql.BooleanValue bo -> b.append("{\"_type\":\"booleanValue\","
                    + "\"value\":").append(bo.value()).append('}');
            case Gql.NullValue ignored ->
                    b.append("{\"_type\":\"nullValue\"}");
            case Gql.EnumValue e -> {
                b.append("{\"_type\":\"enumValue\",\"value\":");
                ProtocolEmitter.str(b, e.value());
                b.append('}');
            }
            case Gql.VariableRef vr -> {
                b.append("{\"_type\":\"variable\",\"name\":");
                ProtocolEmitter.str(b, vr.name());
                b.append('}');
            }
            case Gql.ListValue l -> {
                b.append("{\"_type\":\"listValue\",\"values\":[");
                for (int i = 0; i < l.values().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    value(b, l.values().get(i));
                }
                b.append("]}");
            }
            case Gql.ObjectValue o -> {
                b.append("{\"_type\":\"objectValue\",\"fields\":");
                arguments(b, o.fields());
                b.append('}');
            }
        }
    }
}
