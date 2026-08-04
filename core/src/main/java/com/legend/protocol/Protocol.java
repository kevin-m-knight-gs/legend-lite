package com.legend.protocol;

import java.util.List;

/**
 * The Legend protocol shape — <em>our</em> types, <em>their</em> bytes.
 *
 * <p>These records mirror the shape legend-engine serialises as
 * {@code PureModelContextData}, so {@link ProtocolEmitter} can reproduce its JSON
 * byte-for-byte. They are a <strong>clean-room reimplementation</strong>: legend-lite takes no
 * dependency on {@code legend-engine-protocol-pure}, and nothing here imports
 * {@code org.finos.legend.engine}.
 *
 * <p><b>The protocol is a serialization contract, not a design constraint.</b> Upstream's protocol
 * is mutable public-field POJOs dispatched by Jackson type-ids. Ours is:
 * <ul>
 *   <li><b>sealed</b> hierarchies with explicit {@code permits} — so {@link ProtocolEmitter}'s
 *       switch is exhaustive and adding a variant without an emit rule is a <em>compile error</em>,
 *       the same discipline {@code AGENTS.md} invariant 3 imposes on MIR &rarr; dialect;</li>
 *   <li><b>100% immutable</b> records;</li>
 *   <li><b>loud</b> — no defaulting, no silent absence (invariant 4).</li>
 * </ul>
 *
 * <p>Positions are captured at construction because that is the only point where token offsets are
 * in hand. {@link SourceInfo} follows the engine's convention exactly: 1-based lines, 1-based start
 * column, and an <em>inclusive</em> end column.
 */
public final class Protocol {

    private Protocol() {
    }

    /** Root: {@code {"_type":"data","elements":[...]}}. Null {@code serializer}/{@code origin} are omitted. */
    public record PureModelContextData(List<Element> elements) {
        public PureModelContextData {
            elements = List.copyOf(elements);
        }
    }

    /** A packageable element. Sealed so the emitter's switch is exhaustive. */
    public sealed interface Element permits PClass, PSectionIndex {
    }

    /** {@code _type:"class"}. */
    public record PClass(String pkg, String name, List<PProperty> properties,
                         SourceInfo sourceInformation) implements Element {
        public PClass {
            properties = List.copyOf(properties);
        }
    }

    /** {@code _type:"sectionIndex"} — synthesised, and always emitted last. */
    public record PSectionIndex(String pkg, String name, List<PSection> sections) implements Element {
        public PSectionIndex {
            sections = List.copyOf(sections);
        }
    }

    /** {@code _type:"importAware"} — the only section kind emitted for {@code ###Pure}. */
    public record PSection(String parserName, List<String> elements, List<String> imports,
                           SourceInfo sourceInformation) {
        public PSection {
            elements = List.copyOf(elements);
            imports = List.copyOf(imports);
        }
    }

    /** A simple (non-derived) property. */
    public record PProperty(String name, PGenericType genericType, PMultiplicity multiplicity,
                            SourceInfo sourceInformation) {
    }

    /** Note: carries no {@code _type} on the wire. */
    public record PGenericType(PPackageableType rawType) {
    }

    /** {@code _type:"packageableType"}. */
    public record PPackageableType(String fullPath, SourceInfo sourceInformation) {
    }

    /** Carries no {@code _type} and no source information. */
    public record PMultiplicity(int lowerBound, Integer upperBound) {
    }

    /**
     * Engine convention: 1-based lines; 1-based start column; <b>inclusive</b> end column
     * ({@code charPositionInLine + text.length()}, deliberately no {@code +1}).
     */
    public record SourceInfo(String sourceId, int startLine, int startColumn,
                             int endLine, int endColumn) {
    }
}
