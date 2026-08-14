// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.spec;

import java.util.List;
import java.util.Objects;

/**
 * The GraphQL AST carried by a {@code #GQL{...}#} island — typed nodes
 * mirroring the engine's {@code executableDocument} wire (probe gql-wire
 * 2026-08-14). The wire carries NO source positions, so none are modeled;
 * {@code com.legend.protocol.GqlEmitter} owns the byte-exact rendering.
 *
 * <p>Numeric values keep their RAW source text: the engine serializes
 * numbers through Jackson, and the raw spelling is byte-identical for
 * every corpus-evidenced literal while staying honest about ours.
 */
public final class Gql {

    private Gql() {
    }

    /** A whole {@code executableDocument}. */
    public record Document(List<Definition> definitions) {
        public Document {
            definitions = List.copyOf(definitions);
        }
    }

    /** One top-level definition. */
    public sealed interface Definition
            permits Operation, Fragment, ObjectType {
    }

    /** {@code operationDefinition} — {@code type} is null for a BARE
     *  selection document (the wire omits the field). */
    public record Operation(@com.legend.Nullable String type,
            @com.legend.Nullable String name,
            List<VariableDef> variables,
            List<Directive> directives,
            List<Selection> selectionSet) implements Definition {
        public Operation {
            variables = List.copyOf(variables);
            directives = List.copyOf(directives);
            selectionSet = List.copyOf(selectionSet);
        }
    }

    /** {@code fragmentDefinition}. */
    public record Fragment(String name, String typeCondition,
            List<Directive> directives,
            List<Selection> selectionSet) implements Definition {
        public Fragment {
            Objects.requireNonNull(name, "name");
            directives = List.copyOf(directives);
            selectionSet = List.copyOf(selectionSet);
        }
    }

    /** SDL {@code objectTypeDefinition} ({@code type X { f: T }}). */
    public record ObjectType(String name,
            List<FieldDef> fields) implements Definition {
        public ObjectType {
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(fields);
        }
    }

    /** One SDL field definition. */
    public record FieldDef(String name, TypeRef type) {
    }

    /** One selection inside a selection set. */
    public sealed interface Selection permits Field, FragmentSpread {
    }

    /** {@code field} — the alias keeps its trailing colon verbatim
     *  ({@code "h:"}), matching the engine's serialization. */
    public record Field(@com.legend.Nullable String alias, String name,
            List<Argument> arguments, List<Directive> directives,
            List<Selection> selectionSet) implements Selection {
        public Field {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
            directives = List.copyOf(directives);
            selectionSet = List.copyOf(selectionSet);
        }
    }

    /** {@code fragmentSpread} ({@code ...name}). */
    public record FragmentSpread(String name,
            List<Directive> directives) implements Selection {
        public FragmentSpread {
            directives = List.copyOf(directives);
        }
    }

    /** {@code name: value}. */
    public record Argument(String name, Value value) {
    }

    /** {@code @name(args)}. */
    public record Directive(String name, List<Argument> arguments) {
        public Directive {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code $name: Type = default} — the wire keeps the {@code $} in
     *  the definition's name (uses drop it). */
    public record VariableDef(String dollarName, TypeRef type,
            @com.legend.Nullable Value defaultValue) {
    }

    /** A type reference. */
    public sealed interface TypeRef permits NamedType, ListType {
    }

    /** {@code Name} / {@code Name!}. */
    public record NamedType(String name, boolean nullable)
            implements TypeRef {
    }

    /** {@code [T]} / {@code [T]!}. */
    public record ListType(TypeRef itemType, boolean nullable)
            implements TypeRef {
    }

    /** A GraphQL value. */
    public sealed interface Value permits IntValue, FloatValue, StringValue,
            BooleanValue, NullValue, EnumValue, VariableRef, ListValue,
            ObjectValue {
    }

    /** Raw spelling preserved (see class doc). */
    public record IntValue(String raw) implements Value {
    }

    /** Raw spelling preserved (see class doc). */
    public record FloatValue(String raw) implements Value {
    }

    public record StringValue(String value) implements Value {
    }

    public record BooleanValue(boolean value) implements Value {
    }

    public record NullValue() implements Value {
    }

    public record EnumValue(String value) implements Value {
    }

    /** A {@code $var} USE — the name carries no {@code $}. */
    public record VariableRef(String name) implements Value {
    }

    public record ListValue(List<Value> values) implements Value {
        public ListValue {
            values = List.copyOf(values);
        }
    }

    /** {@code {a: 1}} — fields in source order. */
    public record ObjectValue(List<Argument> fields) implements Value {
        public ObjectValue {
            fields = List.copyOf(fields);
        }
    }
}
