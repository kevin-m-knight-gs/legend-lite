package com.legend.parser;

import com.legend.protocol.Multiplicity;
import com.legend.protocol.TypeExpression;
import com.legend.model.ParsedModel;
import com.legend.model.ImportScope;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.model.AssociationDefinition;
import com.legend.model.AssociationDefinition.AssociationEndDefinition;
import com.legend.model.AssociationMapping;
import com.legend.model.AssociationPropertyMapping;
import com.legend.model.AuthenticationSpec;
import com.legend.model.ClassDefinition;
import com.legend.protocol.ConstraintDefinition;
import com.legend.protocol.DerivedPropertyDefinition;
import com.legend.protocol.ParameterDefinition;
import com.legend.model.ConnectionDefinition;
import com.legend.model.ConnectionSpecification;
import com.legend.model.DatabaseDefinition;
import com.legend.model.EnumDefinition;
import com.legend.model.EnumerationMapping;
import com.legend.model.ClassMapping;
import com.legend.model.FilterMapping;
import com.legend.model.FilterPointer;
import com.legend.model.FunctionDefinition;
import com.legend.model.NativeFunctionDefinition;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.MappingDefinition;
import com.legend.protocol.Realization;
import com.legend.model.MappingInclude;
import com.legend.model.PropertyMapping;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.model.JsonModelConnection;
import com.legend.model.PackageableElement;
import com.legend.model.ComparisonOp;
import com.legend.model.RelationalDataType;
import com.legend.model.JoinChainElement;
import com.legend.model.JoinType;
import com.legend.model.LogicalOp;
import com.legend.model.ProfileDefinition;
import com.legend.model.RelationalOperation;
import com.legend.model.RuntimeDefinition;
import com.legend.model.ServiceDefinition;
import com.legend.model.StereotypeApplication;
import com.legend.model.TaggedValue;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled recursive-descent parser for Pure top-level element
 * declarations &mdash; the driver for step B of the pipeline.
 *
 * <h2>Usage</h2>
 * <pre>
 *   ParsedModel model = ElementParser.parse(pureSource);    // text overload
 *   ParsedModel model = ElementParser.parse(tokenStream);   // pre-lexed overload
 * </pre>
 *
 * <h2>Status (Phase B.4a)</h2>
 * Handles:
 * <ul>
 *   <li>{@code import} statements (wildcard and specific).</li>
 *   <li>{@code Class} (regular and {@code native}) with stereotypes, tagged
 *       values, generic type parameters, superclasses, properties,
 *       derived properties (body parsed eagerly into {@link ValueSpecification}
 *       statements), and class-level constraints (expression parsed eagerly).</li>
 *   <li>{@code Association} &mdash; exactly two ends.</li>
 *   <li>{@code Enum} &mdash; one or more value names.</li>
 *   <li>{@code Profile} &mdash; stereotypes and tags.</li>
 *   <li>{@code function} &mdash; signature plus body parsed eagerly into a
 *       {@code List<ValueSpecification>} via {@link SpecParser#parseCodeBlock}.</li>
 *   <li>{@code Service} &mdash; pattern, doc, execution (query parsed eagerly as a
 *       single {@link ValueSpecification}; mapping/runtime as FQN refs),
 *       and {@code testSuites} block captured as raw text (decision D-3 &mdash;
 *       still deferred until the test-suite grammar lands).</li>
 *   <li>{@code Runtime} (and {@code SingleConnectionRuntime}) &mdash; mappings,
 *       connection bindings, embedded {@code JsonModelConnection} islands.</li>
 *   <li>{@code RelationalDatabaseConnection} &mdash; store, type, specification,
 *       authentication.</li>
 * </ul>
 *
 * <p>B.4a (this slice) adds {@code Database} with the full relational
 * expression sub-AST &mdash; column refs, target columns, literals,
 * function calls, comparisons, boolean combinations, null tests, groups,
 * array literals, and join-navigation chains. Used by {@code Join},
 * {@code Filter}, {@code MultiGrainFilter}, and view column expressions.
 *
 * <p>NOT yet supported (sub-slices B.4b-f):
 * <ul>
 *   <li>{@code Mapping} (next: B.4b Relational class mappings, then
 *       Association / Enum / M2M class mappings / test-suites).</li>
 * </ul>
 * Encountering an unsupported keyword raises a {@link ParseException}.
 *
 * <h2>Deliberate divergences from engine</h2>
 * <ul>
 *   <li>{@link FunctionDefinition} drops engine's compiler-cache fields
 *       ({@code resolvedBody}, {@code parsedReturnType}, parameter
 *       {@code functionType}/{@code parsedType}) &mdash; those bridge parser
 *       output and compiler output via {@code withResolvedBody}, which is
 *       the F-must-not-trigger-G violation AGENTS.md invariant 1 forbids.
 *       Compile-side output lives on {@code compiler.element.TypedFunction}
 *       (Phase F, future).</li>
 *   <li>{@link ServiceDefinition} drops engine's {@code toRegexPattern()},
 *       {@code extractPathParams()}, and convenience factories &mdash;
 *       those are REST-runtime concerns, not parser data.</li>
 *   <li><strong>Unknown keys inside {@code Runtime} / {@code Connection} /
 *       {@code Service} bodies throw</strong> (engine silently
 *       {@code skipToSemicolon}'s them). Matches AGENTS.md invariant 4
 *       (no fallbacks). May fire on forward-compat Pure syntax &mdash;
 *       tracked as decision D-2 in core's README.</li>
 * </ul>
 *
 * <h2>Stateful only during parse</h2>
 * A parser instance is allocated per call to {@link #parse(TokenStream)},
 * driven to completion, then discarded. The resulting {@link ParsedModel}
 * is the only object that survives.
 *
 * <p>Ported from engine's {@code com.gs.legend.parser.PureModelParser},
 * keeping the same recursive-descent shape and identifier-token set
 * for parity testing.
 */
public final class ElementParser implements TokenStreamCursor {

    // ============================================================
    // Instance state (transient during parse)
    // ============================================================

    final TokenStream tokens;
    int pos;

    /**
     * When non-null, parsing is inside a class mapping body and bare
     * identifiers in relational expressions resolve eagerly to columns of
     * this main table (matching FINOS engine's ScopeInfo behavior). When
     * null (Database context), bare identifiers throw per D-7. Set/cleared
     * around mapping-body parsing in {@link #parseRelationalClassMappingBody}.
     */
    LegacyMappingDefinition.@com.legend.Nullable TableReference currentMappingScope;

    /** {@code prop[setId]} routings of the class mapping being parsed. */
    java.util.@com.legend.Nullable Map<String, String> currentTargetSets;

    /**
     * An active {@code scope([db]path)(...)} block (real mapping grammar):
     * inside it, BARE identifiers are columns of the scoped table and
     * single-segment scopes prefix dotted refs as a schema. Resolution is
     * deferred to each use site — no store lookup at parse time.
     */
    record ScopeBlock(@com.legend.Nullable String db, @com.legend.Nullable String path) { }

    @com.legend.Nullable ScopeBlock currentScopeBlock;

    /** Grammar-section parsers sharing this parser's cursor and scope state. */
    final MappingGrammarParser mappingGrammar = new MappingGrammarParser(this);
    final RelationalGrammarParser relationalGrammar = new RelationalGrammarParser(this);

    ElementParser(TokenStream tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * A parser positioned at an arbitrary token — the protocol-output entry point used by the
     * equivalence harness (formerly via reflection) and by per-element callers. The token stream
     * must be the WHOLE file's, so source positions stay file-absolute; parsing isolated chunks
     * restarts line numbers at 1, which presents as a parser bug in any positional comparison.
     */
    public static ElementParser at(TokenStream tokens, int tokenIndex) {
        ElementParser p = new ElementParser(tokens);
        p.pos = tokenIndex;
        return p;
    }

    /**
     * Indexes of {@code marker} tokens at bracket/brace/paren depth 0 — i.e. real top-level
     * declarations, not occurrences inside bodies. Companion to {@link #at}.
     */
    public static java.util.List<Integer> topLevelIndexes(TokenStream ts, TokenType marker) {
        java.util.List<Integer> out = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < ts.count(); i++) {
            TokenType t = ts.type(i);
            switch (t) {
                case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                default -> {
                    // keywords are legal identifiers in Pure ('function' packages,
                    // 'Class<Any>' types, 'PCT.function' stereotype values) — a marker is
                    // a DECLARATION only where one can start: at stream start (sections
                    // are lexer-skipped), after a block close, or after a semicolon
                    if (depth == 0 && t == marker
                            && (i == 0 || ts.type(i - 1) == TokenType.BRACE_CLOSE
                                    || ts.type(i - 1) == TokenType.SEMI_COLON)
                            && (i + 1 >= ts.count()
                                    || ts.type(i + 1) != TokenType.PATH_SEPARATOR)) {
                        out.add(i);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Tokenize and parse a Pure source string into a {@link ParsedModel}.
     *
     * <p>Linear eager parse: every declared element is parsed in source
     * order. The result is the batch compiler's parser-stage output.
     *
     * <p>Demand-driven element loading (parse only the elements an
     * incremental client touches) is provided by the dormant IDE layer in
     * {@code com.legend.ide}; it is not used by the batch compiler.
     */
    public static ParsedModel parse(String source) {
        return parse(Lexer.tokenize(Objects.requireNonNull(source, "source")));
    }

    /** Parse a pre-lexed token stream into a {@link ParsedModel}. */
    public static ParsedModel parse(TokenStream tokens) {
        return new ElementParser(Objects.requireNonNull(tokens, "tokens")).parseModel();
    }

    /**
     * Parse exactly one packageable element from a token-stream slice.
     *
     * <p>The slice is expected to contain the element's full token range
     * (header + body); the first token must be the element's leading
     * keyword (or {@code native} for native classes). After parsing the
     * element the slice should be fully consumed &mdash; trailing tokens
     * cause a {@link ParseException}.
     *
     * <p>Exposed for the IDE layer ({@code com.legend.ide}), which uses it
     * to deep-parse a single element sliced out of a larger token stream
     * via the shallow indexer. Not used by the batch compiler.
     */
    public static PackageableElement parseSingle(TokenStream slice) {
        ElementParser parser = new ElementParser(slice);
        PackageableElement element = parser.parseSingleElement();
        if (!parser.atEnd()) {
            parser.error("trailing tokens after element body: expected slice to be fully consumed");
        }
        return element;
    }

    /** Parse one {@code import path::* ;} statement from a token-stream slice. */
    public static String parseSingleImport(TokenStream slice) {
        ElementParser parser = new ElementParser(slice);
        String imp = parser.parseImportStatement();
        if (!parser.atEnd()) {
            parser.error("trailing tokens after import statement");
        }
        return imp;
    }

    // ============================================================
    // Top-level
    // ============================================================

    private ParsedModel parseModel() {
        List<PackageableElement> elements = new ArrayList<>();
        ImportScope.Builder imports = new ImportScope.Builder();
        Map<String, Integer> offsets = new HashMap<>();
        // SECTION-scoped imports (real pure): an import following elements
        // opens a NEW section scope; each element records the scope active
        // where it was declared.
        Map<String, ImportScope> elementImports = new HashMap<>();
        ImportScope.Builder sectionImports = new ImportScope.Builder();
        boolean sawElementSinceImport = false;

        while (!atEnd()) {
            if (peek() == TokenType.IMPORT) {
                if (sawElementSinceImport) {
                    sectionImports = new ImportScope.Builder();
                    sawElementSinceImport = false;
                }
                String imp = parseImportStatement();
                imports.add(imp);
                sectionImports.add(imp);
            } else if (skipTopLevelNonElement()) {
                sawElementSinceImport = true;
            } else {
                int at = tokens.start(pos);
                PackageableElement e = parseSingleElement();
                sawElementSinceImport = true;
                elementImports.putIfAbsent(e.qualifiedName(), sectionImports.build());
                offsets.putIfAbsent(e.qualifiedName(), at);
                elements.add(e);
            }
        }

        return new ParsedModel(elements, imports.build(), tokens.source(), offsets, elementImports);
    }

    /**
     * Non-model top-level artifacts real pure files carry — {@code Diagram
     * fqn(w,h) { ... }} blocks and top-level {@code ^Instance(...)}
     * declarations. They define no queryable element; consumed and DROPPED
     * so the elements around them load (previously each sank its whole
     * file's parse). Returns false (nothing consumed) for anything else.
     */
    private boolean skipTopLevelNonElement() {
        if (isIdentifierToken(peek()) && "Diagram".equals(text())) {
            advance();
            parseQualifiedName();       // the diagram's name — anything else
                                        // after it is a parse error, never an
                                        // unbounded token skip (audit 8 S9)
            if (peek() == TokenType.PAREN_OPEN) {
                mappingGrammar.skipBalancedBlock();    // (width=..., height=...)
            }
            if (peek() == TokenType.BRACE_OPEN) {
                mappingGrammar.skipBalancedBlock();    // { TypeView ... }
            }
            return true;
        }
        if (peek() == TokenType.NEW_SYMBOL) {
            advance();
            parseQualifiedName();       // the instance's type reference
            if (peek() != TokenType.PAREN_OPEN) {
                throw error("top-level ^Instance must be followed by (...)");
            }
            mappingGrammar.skipBalancedBlock();
            return true;
        }
        // a STRAY top-level closer: the corpus\u0027s own
        // m2m2rExecutionPlanTests.pure carries an unbalanced extra )
        // (opens=4, closes=5) that the engine tolerates; skip exactly
        // this token, never other junk
        if (peek() == TokenType.PAREN_CLOSE) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * Dispatch on the current token (an element-starting keyword) and
     * parse exactly one packageable element. Used both by
     * {@link #parseModel} when iterating a full file and by
     * {@link #parseSingle} when the IDE layer's shallow scanner
     * ({@code com.legend.ide.ModelIndexer}) has sliced one element out of a
     * larger stream.
     */
    private PackageableElement parseSingleElement() {
        TokenType t = peek();
        return switch (t) {
            case CLASS -> com.legend.model.FromProtocol.toClassDefinition(
                    parseClassDefinition(false));
            case NATIVE -> {
                advance(); // consume 'native'
                yield switch (peek()) {
                    case CLASS -> com.legend.model.FromProtocol.toClassDefinition(
                            parseClassDefinition(true));
                    case FUNCTION -> parseNativeFunction();
                    default -> throw error("expected 'Class' or 'function' after 'native', got "
                            + peek() + " ('" + safeText() + "')");
                };
            }
            case ASSOCIATION -> parseAssociation();
            case ENUM -> com.legend.model.FromProtocol.toEnumDefinition(parseEnumDefinition());
            case PROFILE -> com.legend.model.FromProtocol.toProfileDefinition(
                    parseProfileDefinition());
            case FUNCTION -> parseFunctionDefinition();
            case SERVICE -> parseServiceDefinition();
            case RUNTIME -> parseRuntime();
            case SINGLE_CONNECTION_RUNTIME -> parseSingleConnectionRuntime();
            case RELATIONAL_DATABASE_CONNECTION -> parseConnection();
            case DATABASE -> relationalGrammar.parseDatabase();
            case MAPPING -> mappingGrammar.parseMapping();
            // Primitive my::Ext extends Base [constraint]? — precise primitive
            case VALID_STRING -> {
                if ("Primitive".equals(safeText())) {
                    yield parsePrimitiveExtension();
                }
                throw error("unsupported top-level keyword: " + t + " ('" + safeText() + "')");
            }
            default -> throw error("unsupported top-level keyword: " + t + " ('" + safeText() + "')");
        };
    }

    // ============================================================
    // Import statement
    // ============================================================

    /** {@code import packagePath::* ;} or {@code import packagePath::Type ;} */
    private String parseImportStatement() {
        expect(TokenType.IMPORT);
        StringBuilder sb = new StringBuilder();
        sb.append(parseIdentifier());
        while (match(TokenType.PATH_SEPARATOR)) {
            sb.append("::");
            if (match(TokenType.STAR)) {
                sb.append("*");
                break;
            }
            sb.append(parseIdentifier());
        }
        expect(TokenType.SEMI_COLON);
        return sb.toString();
    }

    // ============================================================
    // Class declaration
    // ============================================================

    /** Parses one {@code Class} declaration at the cursor into its protocol record. Public as
     *  the per-element protocol entry point (see {@link #at}); most callers want
     *  {@link #parse(String)} instead. */
    public com.legend.protocol.Protocol.PClass parseClassDefinition(boolean isNative) {
        int classStartTok = pos;
        expect(TokenType.CLASS);
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        String qualifiedName = parseQualifiedName();

        List<String> typeParams = parseClassTypeParams();

        // PROJECTION class: `Class X projects Y { > name [expr] | +[props]
        // | * }` (engine class-projection grammar). Parsed as a NOMINAL
        // element so referencing mappings/queries resolve the name; the
        // projection semantics (flattened derived surface) stay loud
        // downstream — parse-level unlock only.
        if (peek() == TokenType.VALID_STRING && "projects".equals(safeText())) {
            advance();
            parseQualifiedName();      // the projected source class
            if (peek() == TokenType.BRACE_OPEN) {
                mappingGrammar.skipBalancedBlock();
            }
            String[] pn = com.legend.protocol.Protocol.splitFqn(qualifiedName);
            return new com.legend.protocol.Protocol.PClass(pn[0], pn[1], typeParams, List.of(),
                    List.of(), List.of(), List.of(), stereotypes, taggedValues, isNative,
                    span(classStartTok, pos - 1));
        }

        List<com.legend.protocol.Protocol.PSuperType> superClasses = new ArrayList<>();
        if (match(TokenType.EXTENDS)) {
            int stTok = pos;
            superClasses.add(new com.legend.protocol.Protocol.PSuperType(
                    parseType(), span(stTok, pos - 1)));
            while (match(TokenType.COMMA)) {
                int nTok = pos;
                superClasses.add(new com.legend.protocol.Protocol.PSuperType(
                        parseType(), span(nTok, pos - 1)));
            }
        }

        List<ConstraintDefinition> constraints = peek() == TokenType.BRACKET_OPEN
                ? parseConstraints()
                : List.of();

        expect(TokenType.BRACE_OPEN);

        List<com.legend.protocol.Protocol.PProperty> properties = new ArrayList<>();
        List<DerivedPropertyDefinition> derivedProperties = new ArrayList<>();
        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            if (isDerivedPropertyStart()) {
                derivedProperties.add(parseDerivedProperty());
            } else {
                properties.add(parseProperty());
            }
        }
        expect(TokenType.BRACE_CLOSE);

        String[] pn = com.legend.protocol.Protocol.splitFqn(qualifiedName);
        return new com.legend.protocol.Protocol.PClass(
                pn[0],
                pn[1],
                typeParams,
                superClasses,
                properties,
                derivedProperties,
                constraints,
                stereotypes,
                taggedValues,
                isNative,
                span(classStartTok, pos - 1));
    }

    /** Optional generic type parameters: {@code <T>}, {@code <U, V>}, ... */
    private List<String> parseClassTypeParams() {
        if (peek() != TokenType.LESS_THAN) return List.of();
        advance(); // consume <
        List<String> params = new ArrayList<>();
        params.add(parseIdentifier());
        while (match(TokenType.COMMA)) {
            params.add(parseIdentifier());
        }
        expect(TokenType.GREATER_THAN);
        return params;
    }

    /**
     * Property starts as {@code identifier (':' ...)}; derived property starts
     * as {@code identifier '(' ...}. Distinguish by lookahead at offset +1
     * across allowed stereotype/tag annotations.
     */
    private boolean isDerivedPropertyStart() {
        int saved = pos;
        // skip optional stereotypes <<...>>
        if (peek() == TokenType.LESS_THAN && peek(1) == TokenType.LESS_THAN) {
            int depth = 2;
            pos += 2;
            while (pos < tokens.count() && depth > 0) {
                if (peek() == TokenType.GREATER_THAN) depth--;
                else if (peek() == TokenType.LESS_THAN) depth++;
                pos++;
            }
        }
        // skip optional tagged values { ... }
        if (peek() == TokenType.BRACE_OPEN) {
            int depth = 1;
            pos++;
            while (pos < tokens.count() && depth > 0) {
                if (peek() == TokenType.BRACE_OPEN) depth++;
                else if (peek() == TokenType.BRACE_CLOSE) depth--;
                pos++;
            }
        }
        boolean derived = isIdentifierToken(peek()) && peek(1) == TokenType.PAREN_OPEN;
        pos = saved;
        return derived;
    }

    /**
     * Parse a derived (computed) property:
     * {@code <<stereos>> {tags} name(params) { body }: Type[mult];}.
     * The body between {@code &lcub;...&rcub;} is parsed eagerly via
     * {@link SpecParser#parseCodeBlock} and stored as a
     * {@code List<ValueSpecification>} on the resulting
     * {@link DerivedPropertyDefinition}.
     *
     * <p>NOTE: stereotypes and tagged values on derived properties are
     * parsed-and-discarded for engine parity (engine drops them too).
     * If we want to preserve them later, this is the place.
     */
    private DerivedPropertyDefinition parseDerivedProperty() {
        int declStart = pos;
        // CAPTURED, not dropped: the wire carries qualified-property annotations — the old
        // "engine consumes and drops" comment was engine-lite lore, refuted by the harness
        // (DIFF on ClassWithQualifiedProperties: stereotypes size expected=2 actual=0).
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        String name = parseIdentifier();

        expect(TokenType.PAREN_OPEN);
        List<ParameterDefinition> params = new ArrayList<>();
        if (peek() != TokenType.PAREN_CLOSE) {
            params.add(parseDerivedPropertyParameter());
            while (match(TokenType.COMMA)) {
                params.add(parseDerivedPropertyParameter());
            }
        }
        expect(TokenType.PAREN_CLOSE);

        List<ValueSpecification> body = List.of();
        if (peek() == TokenType.BRACE_OPEN) {
            advance(); // consume {
            int bodyStart = pos;
            int depth = 1;
            while (!atEnd() && depth > 0) {
                TokenType t = peek();
                if (t == TokenType.BRACE_OPEN) depth++;
                else if (t == TokenType.BRACE_CLOSE) depth--;
                if (depth > 0) advance();
            }
            body = SpecParser.parseCodeBlock(tokens.slice(bodyStart, pos));
            expect(TokenType.BRACE_CLOSE);
        }

        expect(TokenType.COLON);
        TypeExpression type = parseType();
        Multiplicity mult = parseMultiplicity();
        expect(TokenType.SEMI_COLON);

        // Door 4: a bare function-reference body binds the derived property to a
        // user function; any other expression is the sugar (inline) form.
        // Engine convention: the span covers the whole declaration incl. the semicolon-
        // terminated tail (name..';' end column is the ';' - 1? — the probe shows the span
        // running to the declaration's last token; the harness arbitrates).
        return new DerivedPropertyDefinition(
                name, params, realizationOf(body), type, mult,
                stereotypes, taggedValues, span(declStart, pos - 1));
    }

    /**
     * Classify a parsed body as a {@link Realization}: a single bare element
     * reference ({@link PackageableElementPtr}) is a function ref (Door 1/4);
     * anything else is an inline expression body (sugar / Door 3). Shared by
     * mapping bindings and the class/service hats.
     */
    static Realization realizationOf(List<ValueSpecification> body) {
        if (body.size() == 1 && body.get(0) instanceof PackageableElementPtr ptr) {
            return new Realization.Ref(ptr.fullPath());
        }
        return new Realization.Inline(body);
    }

    private ParameterDefinition parseDerivedPropertyParameter() {
        int pStart = pos;
        String name = parseIdentifier();
        expect(TokenType.COLON);
        TypeExpression type = parseType();
        Multiplicity mult = parseMultiplicity();
        // Engine convention: a parameter's span covers its whole `name: Type[mult]` decl.
        return new ParameterDefinition(name, type, mult, span(pStart, pos - 1));
    }

    // ============================================================
    // Constraint declarations (class-level)
    // ============================================================

    /**
     * {@code [ constraint (, constraint)* ]} after the class header. Each
     * constraint may be {@code name: expression} or just {@code expression}
     * (named by POSITION INDEX for parity with real m3). The expression is
     * parsed eagerly via {@link SpecParser#parse(com.legend.lexer.TokenStream)}
     * into a single {@link ValueSpecification}.
     */
    private List<ConstraintDefinition> parseConstraints() {
        expect(TokenType.BRACKET_OPEN);
        List<ConstraintDefinition> result = new ArrayList<>();
        if (peek() != TokenType.BRACKET_CLOSE) {
            result.add(parseConstraint(0));
            while (match(TokenType.COMMA)) {
                result.add(parseConstraint(result.size()));
            }
        }
        expect(TokenType.BRACKET_CLOSE);
        return result;
    }

    private ConstraintDefinition parseConstraint(int index) {
        int constraintStart = pos;
        // real m3: an unnamed constraint is named by its POSITION ("0",
        // "1", ...) — the id the checked goldens serialize, and distinct
        // lifted-FQN identity for multiple unnamed constraints
        String name = String.valueOf(index);
        // EXTENDED form (real m3): name( ~function: expr ~enforcementLevel: X
        // ~message: expr ) — the predicate is the ~function expression;
        // enforcement level and message are instantiation-time concerns,
        // parsed and dropped (engine parity for query compilation).
        // Dispatch on `name( ~` — a bare function-call constraint like
        // [ eq($this.a, 1) ] is the SIMPLE form (the real grammar allows any
        // expression there); only `name(~...)` opens the extended clause block.
        if (isIdentifierToken(peek()) && peek(1) == TokenType.PAREN_OPEN
                && peek(2) == TokenType.TILDE) {
            name = parseIdentifier();
            expect(TokenType.PAREN_OPEN);
            expect(TokenType.TILDE);
            String kw = parseIdentifier();
            // real clause order: ~owner? ~externalId? ~function
            // ~enforcementLevel? ~message? — externalId is RECORDED (the wire carries it;
            // dropping it was DIFF #1 the harness ever caught on constraints); owner is
            // recorded as present-only until its wire spelling is probed.
            String externalId = null;
            String owner = null;
            while (kw.equals("owner") || kw.equals("externalId")) {
                expect(TokenType.COLON);
                if (kw.equals("externalId") && peek() == TokenType.STRING) {
                    // escapes resolve — 'Bee\'s' carries a real apostrophe on the wire
                    externalId = TokenStreamCursor.unquoteAndUnescape(text(), this);
                } else if (kw.equals("owner")) {
                    // single identifier (engine REJECTS a bracketed list — probed); the
                    // wire carries it as the string field "owner"
                    owner = text();
                }
                while (!atEnd() && peek() != TokenType.TILDE) {
                    advance();
                }
                expect(TokenType.TILDE);
                kw = parseIdentifier();
            }
            if (!kw.equals("function")) {
                throw error("extended constraint must lead with ~function:, got ~" + kw);
            }
            expect(TokenType.COLON);
            int fnStart = pos;
            int d = 0;
            while (!atEnd()) {
                TokenType t = peek();
                if (t == TokenType.TILDE && d == 0) {
                    break;
                }
                if (t == TokenType.BRACKET_OPEN || t == TokenType.PAREN_OPEN
                        || t == TokenType.BRACE_OPEN) {
                    d++;
                } else if (t == TokenType.BRACKET_CLOSE || t == TokenType.PAREN_CLOSE
                        || t == TokenType.BRACE_CLOSE) {
                    if (d == 0) {
                        break;
                    }
                    d--;
                }
                advance();
            }
            ValueSpecification fn = SpecParser.parse(tokens.slice(fnStart, pos));
            // trailing ~key: value sections — ~message (an EXPRESSION over
            // $this) and ~enforcementLevel feed the validation projection
            // (#45); others parse and drop (engine: instantiation concerns)
            ValueSpecification message = null;
            String level = null;
            while (!atEnd() && peek() == TokenType.TILDE) {
                expect(TokenType.TILDE);
                String kw2 = parseIdentifier();
                expect(TokenType.COLON);
                int vStart = pos;
                int vd = 0;
                while (!atEnd()) {
                    TokenType t = peek();
                    if (t == TokenType.TILDE && vd == 0) {
                        break;
                    }
                    if (t == TokenType.BRACKET_OPEN || t == TokenType.PAREN_OPEN
                            || t == TokenType.BRACE_OPEN) {
                        vd++;
                    } else if (t == TokenType.BRACKET_CLOSE
                            || t == TokenType.PAREN_CLOSE
                            || t == TokenType.BRACE_CLOSE) {
                        if (vd == 0) {
                            break;
                        }
                        vd--;
                    }
                    advance();
                }
                if (kw2.equals("message")) {
                    message = SpecParser.parse(tokens.slice(vStart, pos));
                } else if (kw2.equals("enforcementLevel")) {
                    ValueSpecification lv =
                            SpecParser.parse(tokens.slice(vStart, pos));
                    level = enforcementLevelName(lv);
                }
            }
            expect(TokenType.PAREN_CLOSE);
            // Engine convention: the span covers the whole `name ( ... )` block.
            return new ConstraintDefinition(name, realizationOf(List.of(fn)),
                    message, level, externalId, owner, span(constraintStart, pos - 1));
        }
        if (isIdentifierToken(peek()) && peek(1) == TokenType.COLON) {
            name = parseIdentifier();
            advance(); // consume :
        }

        // Scan until top-level ',' or matching ']' &mdash; balance brackets,
        // parens, and braces so embedded expressions don't fool us.
        int bodyStart = pos;
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (t == TokenType.BRACKET_OPEN || t == TokenType.PAREN_OPEN || t == TokenType.BRACE_OPEN) {
                depth++;
            } else if (t == TokenType.BRACKET_CLOSE || t == TokenType.PAREN_CLOSE || t == TokenType.BRACE_CLOSE) {
                if (depth == 0) break;
                depth--;
            } else if (t == TokenType.COMMA && depth == 0) {
                break;
            }
            advance();
        }
        if (pos == bodyStart) {
            throw error("empty constraint expression for '" + name + "'");
        }
        ValueSpecification expression = SpecParser.parse(tokens.slice(bodyStart, pos));
        // Door 4: `[name: some::fn]` binds the constraint to a predicate
        // function; any other expression is the sugar (inline) predicate.
        // Engine convention: the span covers `name: expr`, name inclusive.
        return new ConstraintDefinition(name, realizationOf(List.of(expression)),
                null, null, null, null, span(constraintStart, pos - 1));
    }

    /** The bare level name of a parsed ~enforcementLevel value —
     * {@code Error} / {@code Warn} spellings arrive as refs or enum-style
     * accesses; the projection wants the simple name. */
    private static @com.legend.Nullable String enforcementLevelName(ValueSpecification lv) {
        if (lv instanceof com.legend.protocol.spec.PackageableElementPtr p) {
            String f = p.fullPath();
            return f.contains("::") ? f.substring(f.lastIndexOf("::") + 2) : f;
        }
        if (lv instanceof com.legend.protocol.spec.CString cs) {
            return cs.value();
        }
        if (lv instanceof com.legend.protocol.spec.AppliedProperty ap) {
            return ap.property();
        }
        if (lv instanceof com.legend.protocol.spec.Variable v) {
            return v.name();
        }
        return null;
    }

    /** {@code Primitive fqn extends Base} with an optional dropped constraint block. */
    private PackageableElement parsePrimitiveExtension() {
        advance();   // 'Primitive'
        String fqn = parseQualifiedName();
        expect(TokenType.EXTENDS);
        String base = parseQualifiedName();
        // optional (args) on the base (e.g. Decimal(10,2)) — dropped
        if (peek() == TokenType.PAREN_OPEN) {
            mappingGrammar.skipBalancedBlock();
        }
        // optional [constraints] — instantiation-time; dropped
        if (peek() == TokenType.BRACKET_OPEN) {
            mappingGrammar.skipBalancedBlock();
        }
        return new com.legend.model.PrimitiveExtensionDefinition(fqn, base);
    }

    // ============================================================
    // Association
    // ============================================================

    /** {@code Association <<stereos>> {tags} qualifiedName { end1; end2; }} */
    private PackageableElement parseAssociation() {
        int declStart = pos;
        expect(TokenType.ASSOCIATION);
        // CAPTURED, not dropped: the wire carries association annotations
        // (ProbeWireShapes "association").
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        String qualifiedName = parseQualifiedName();
        // PROJECTION association: `Association X projects Y<A, B>` — a
        // nominal registration only (like projection classes); the
        // projected navigation semantics stay loud downstream.
        if (peek() == TokenType.VALID_STRING && "projects".equals(safeText())) {
            advance();
            parseQualifiedName();
            if (peek() == TokenType.LESS_THAN) {
                // <A, B> type arguments — consume to the matching '>'
                advance();
                int depth = 1;
                while (!atEnd() && depth > 0) {
                    if (peek() == TokenType.LESS_THAN) depth++;
                    if (peek() == TokenType.GREATER_THAN) depth--;
                    advance();
                }
            }
            return new ClassDefinition(qualifiedName, List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    false);
        }
        return com.legend.model.FromProtocol.toAssociationDefinition(
                parseAssociationBody(declStart, stereotypes, taggedValues, qualifiedName));
    }

    /** Parses one non-projection {@code Association} at the cursor into its protocol
     *  record — the per-element protocol entry point (see {@link #at}). */
    public com.legend.protocol.Protocol.PAssociation parseAssociationDefinition() {
        int declStart = pos;
        expect(TokenType.ASSOCIATION);
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        String qualifiedName = parseQualifiedName();
        if (peek() == TokenType.VALID_STRING && "projects".equals(safeText())) {
            throw error("projection associations are a legend-lite-local form with no"
                    + " protocol shape (engine rejects 'projects')");
        }
        return parseAssociationBody(declStart, stereotypes, taggedValues, qualifiedName);
    }

    private com.legend.protocol.Protocol.PAssociation parseAssociationBody(
            int declStart,
            List<com.legend.protocol.Protocol.PStereotype> stereotypes,
            List<com.legend.protocol.Protocol.PTaggedValue> taggedValues,
            String qualifiedName) {
        expect(TokenType.BRACE_OPEN);

        List<com.legend.protocol.Protocol.PProperty> ends = new ArrayList<>();
        List<DerivedPropertyDefinition> derived = new ArrayList<>();
        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            // real pure allows QUALIFIED properties in associations — they
            // are alternate accessors of one end, owned by the OPPOSITE
            // end's class (adopted there during normalization)
            if (isDerivedPropertyStart()) {
                derived.add(parseDerivedProperty());
                continue;
            }
            // an end is an ordinary wire property (annotations, span and all)
            ends.add(parseProperty());
        }
        expect(TokenType.BRACE_CLOSE);

        if (ends.size() != 2) {
            throw error("Association must have exactly 2 properties, found: " + ends.size());
        }
        String[] pn = com.legend.protocol.Protocol.splitFqn(qualifiedName);
        return new com.legend.protocol.Protocol.PAssociation(pn[0], pn[1], ends, derived,
                stereotypes, taggedValues, span(declStart, pos - 1));
    }

    // ============================================================
    // Enum
    // ============================================================

    /** {@code Enum <<stereos>> {tags} qualifiedName { VAL (, VAL)* }} */
    /** Parses one {@code Enum} declaration at the cursor into its protocol record —
     *  the per-element protocol entry point (see {@link #at}). Annotations are CAPTURED:
     *  the wire carries declaration- and value-level stereotypes/taggedValues. */
    public com.legend.protocol.Protocol.PEnumeration parseEnumDefinition() {
        int declStart = pos;
        expect(TokenType.ENUM);
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        String qualifiedName = parseQualifiedName();
        expect(TokenType.BRACE_OPEN);

        List<com.legend.protocol.Protocol.PEnumValue> values = new ArrayList<>();
        if (peek() != TokenType.BRACE_CLOSE) {
            values.add(parseEnumValue());
            while (match(TokenType.COMMA)) {
                values.add(parseEnumValue());
            }
        }
        expect(TokenType.BRACE_CLOSE);

        if (values.isEmpty()) {
            throw error("Enum '" + qualifiedName + "' must have at least one value");
        }
        String[] pn = com.legend.protocol.Protocol.splitFqn(qualifiedName);
        return new com.legend.protocol.Protocol.PEnumeration(pn[0], pn[1], values,
                stereotypes, taggedValues, span(declStart, pos - 1));
    }

    private com.legend.protocol.Protocol.PEnumValue parseEnumValue() {
        int entryStart = pos;
        List<com.legend.protocol.Protocol.PStereotype> ss = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> ts = parseTaggedValues();
        String value = parseIdentifier();
        // Engine convention: the entry span runs annotations..value name, comma excluded.
        return new com.legend.protocol.Protocol.PEnumValue(value, ss, ts,
                span(entryStart, pos - 1));
    }

    // ============================================================
    // Profile
    // ============================================================

    /**
     * {@code Profile qualifiedName { (stereotypes: [...]; | tags: [...];)* }}
     */
    /** Parses one {@code Profile} declaration at the cursor into its protocol record —
     *  the per-element protocol entry point (see {@link #at}). Entry spans cover the
     *  declared name token only (engine convention, ProbeWireShapes "profile"). */
    public com.legend.protocol.Protocol.PProfile parseProfileDefinition() {
        int declStart = pos;
        expect(TokenType.PROFILE);
        String qualifiedName = parseQualifiedName();
        expect(TokenType.BRACE_OPEN);

        List<com.legend.protocol.Protocol.PProfileEntry> stereotypes = new ArrayList<>();
        List<com.legend.protocol.Protocol.PProfileEntry> tags = new ArrayList<>();

        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            List<com.legend.protocol.Protocol.PProfileEntry> target;
            if (peek() == TokenType.STEREOTYPES) {
                target = stereotypes;
            } else if (peek() == TokenType.TAGS) {
                target = tags;
            } else {
                throw error("expected 'stereotypes' or 'tags' inside Profile, found " + peek()
                        + " ('" + safeText() + "')");
            }
            advance();
            expect(TokenType.COLON);
            expect(TokenType.BRACKET_OPEN);
            if (peek() != TokenType.BRACKET_CLOSE) {
                target.add(parseProfileEntry());
                while (match(TokenType.COMMA)) {
                    target.add(parseProfileEntry());
                }
            }
            expect(TokenType.BRACKET_CLOSE);
            expect(TokenType.SEMI_COLON);
        }

        expect(TokenType.BRACE_CLOSE);
        String[] pn = com.legend.protocol.Protocol.splitFqn(qualifiedName);
        return new com.legend.protocol.Protocol.PProfile(pn[0], pn[1], stereotypes, tags,
                span(declStart, pos - 1));
    }

    private com.legend.protocol.Protocol.PProfileEntry parseProfileEntry() {
        int nameTok = pos;
        String value = parseIdentifier();
        return new com.legend.protocol.Protocol.PProfileEntry(value, span(nameTok, pos - 1));
    }

    // ============================================================
    // Function declaration
    // ============================================================

    /**
     * {@code function <<stereos>> {tags} qualifiedName(params) : returnType[mult]
     * [optional-constraints] { body }}.
     * Body parsed eagerly via {@link SpecParser#parseCodeBlock} into a
     * {@code List<ValueSpecification>} (sequence of statements).
     */
    /**
     * The signature grammar shared by concrete and native functions (audit
     * M2 found it duplicated verbatim): stereotypes, tags, FQN,
     * {@code <T,V|m,n>}, parameters, {@code :R[m]}.
     */
    private record FunctionSignature(
            int declStart,
            String qualifiedName,
            List<String> typeParams,
            List<String> multParams,
            List<com.legend.protocol.ParameterDefinition> params,
            TypeExpression returnType,
            Multiplicity returnMult,
            List<com.legend.protocol.Protocol.PStereotype> stereotypes,
            List<com.legend.protocol.Protocol.PTaggedValue> taggedValues) {
    }

    private FunctionSignature parseFunctionSignature() {
        int declStart = pos;
        expect(TokenType.FUNCTION);
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        String qualifiedName = parseQualifiedName();
        List<String> typeParams = new ArrayList<>();
        List<String> multParams = new ArrayList<>();
        parseTypeAndMultiplicityParameters(typeParams, multParams);
        expect(TokenType.PAREN_OPEN);
        List<com.legend.protocol.ParameterDefinition> params = new ArrayList<>();
        if (peek() != TokenType.PAREN_CLOSE) {
            params.add(parseFunctionParameter());
            while (match(TokenType.COMMA)) {
                params.add(parseFunctionParameter());
            }
        }
        expect(TokenType.PAREN_CLOSE);
        expect(TokenType.COLON);
        TypeExpression returnType = parseType();
        Multiplicity returnMult = parseMultiplicity();
        return new FunctionSignature(declStart, qualifiedName, List.copyOf(typeParams),
                List.copyOf(multParams), params, returnType, returnMult,
                stereotypes, taggedValues);
    }

    private FunctionDefinition parseFunctionDefinition() {
        return com.legend.model.FromProtocol.toFunctionDefinition(parseFunctionProtocol());
    }

    /** Parses one {@code function} declaration at the cursor into its protocol record —
     *  the per-element protocol entry point (see {@link #at}). Constraint blocks are
     *  CAPTURED (the wire carries pre/postConstraints; the emitter walls until probed). */
    public com.legend.protocol.Protocol.PFunction parseFunctionProtocol() {
        FunctionSignature sig = parseFunctionSignature();

        List<ConstraintDefinition> constraints = peek() == TokenType.BRACKET_OPEN
                ? parseConstraints()
                : List.of();

        expect(TokenType.BRACE_OPEN);
        int bodyStart = pos;
        int depth = 1;
        while (!atEnd() && depth > 0) {
            TokenType t = peek();
            if (t == TokenType.BRACE_OPEN) depth++;
            else if (t == TokenType.BRACE_CLOSE) depth--;
            if (depth > 0) advance();
        }
        List<ValueSpecification> body = SpecParser.parseCodeBlock(tokens.slice(bodyStart, pos));
        expect(TokenType.BRACE_CLOSE);

        // Optional TEST-SUITE block: `function f(...) { body } { suite... }` (legend-testable).
        // Parsed into PTestSuite records; the wire's function span covers the block.
        List<com.legend.protocol.Protocol.PTestSuite> suites = List.of();
        if (peek() == TokenType.BRACE_OPEN) {
            suites = parseFunctionTestBlock(sig);
        }

        String[] pn = com.legend.protocol.Protocol.splitFqn(sig.qualifiedName());
        return new com.legend.protocol.Protocol.PFunction(pn[0], pn[1],
                sig.typeParams(), sig.multParams(), sig.params(),
                sig.returnType(), sig.returnMult(), body, constraints, suites,
                sig.stereotypes(), sig.taggedValues(),
                span(sig.declStart(), pos - 1));
    }

    /**
     * The legend-testable trailing block. Two forms (ProbeWireShapes "fn tests wire",
     * "fn tests named suite"): named suites {@code { name ( test; ... ) ... }} and the
     * unnamed brace form {@code { test; ... }} (wire id {@code "default"}, span = the
     * whole block). Mixing both in one block has no probed wire order — refuse loudly.
     */
    private List<com.legend.protocol.Protocol.PTestSuite> parseFunctionTestBlock(
            FunctionSignature sig) {
        int blockOpen = pos;
        expect(TokenType.BRACE_OPEN);
        List<com.legend.protocol.Protocol.PTestSuite> suites = new ArrayList<>();
        List<com.legend.protocol.Protocol.PFunctionTest> unnamed = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            if (peek(1) == TokenType.PAREN_OPEN) {
                int suiteStart = pos;
                String suiteId = parseIdentifier();
                expect(TokenType.PAREN_OPEN);
                List<com.legend.protocol.Protocol.PFunctionTest> tests = new ArrayList<>();
                while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
                    tests.add(parseFunctionTest(sig));
                }
                expect(TokenType.PAREN_CLOSE);
                suites.add(new com.legend.protocol.Protocol.PTestSuite(
                        suiteId, span(suiteStart, pos - 1), tests));
            } else {
                unnamed.add(parseFunctionTest(sig));
            }
        }
        expect(TokenType.BRACE_CLOSE);
        if (!unnamed.isEmpty()) {
            if (!suites.isEmpty()) {
                throw error("a function test block mixing named suites and bare tests"
                        + " has no probed wire order");
            }
            suites.add(new com.legend.protocol.Protocol.PTestSuite(
                    null, span(blockOpen, pos - 1), unnamed));
        }
        return suites;
    }

    /** {@code id | call(args) => expected;} — the call NAME is not on the wire; each
     *  argument binds to the signature parameter at its position. */
    private com.legend.protocol.Protocol.PFunctionTest parseFunctionTest(
            FunctionSignature sig) {
        int testStart = pos;
        String testId = parseIdentifier();
        expect(TokenType.PIPE);
        parseQualifiedName();                       // call spelling — not serialized
        expect(TokenType.PAREN_OPEN);
        List<com.legend.protocol.Protocol.PTestParam> params = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            if (peek() == TokenType.COMMA) {
                advance();
                continue;
            }
            int vStart = pos;
            int depth = 0;
            while (!atEnd()) {
                TokenType t = peek();
                if (depth == 0 && (t == TokenType.COMMA || t == TokenType.PAREN_CLOSE)) {
                    break;
                }
                if (t == TokenType.PAREN_OPEN || t == TokenType.BRACKET_OPEN) depth++;
                else if (t == TokenType.PAREN_CLOSE || t == TokenType.BRACKET_CLOSE) depth--;
                advance();
            }
            int idx = params.size();
            if (idx >= sig.params().size()) {
                throw error("function test passes more arguments than the signature"
                        + " declares parameters");
            }
            params.add(new com.legend.protocol.Protocol.PTestParam(
                    sig.params().get(idx).name(),
                    SpecParser.parse(tokens.slice(vStart, pos)),
                    span(vStart, pos - 1)));
        }
        expect(TokenType.PAREN_CLOSE);
        expect(TokenType.EQUAL);
        expect(TokenType.GREATER_THAN);
        int eStart = pos;
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (depth == 0 && t == TokenType.SEMI_COLON) {
                break;
            }
            if (t == TokenType.PAREN_OPEN || t == TokenType.BRACKET_OPEN) depth++;
            else if (t == TokenType.PAREN_CLOSE || t == TokenType.BRACKET_CLOSE) depth--;
            advance();
        }
        com.legend.protocol.spec.ValueSpecification expected =
                SpecParser.parse(tokens.slice(eStart, pos));
        com.legend.protocol.SourceInfo expectedSpan = span(eStart, pos - 1);
        expect(TokenType.SEMI_COLON);
        return new com.legend.protocol.Protocol.PFunctionTest(testId,
                span(testStart, pos - 1), params, expected, expectedSpan);
    }

    /**
     * Parse a {@code native function ...;} declaration. {@code native} has
     * already been consumed by the caller. Mirrors Pure's
     * {@code nativeFunction} grammar rule: same signature shape as
     * {@link #parseFunctionDefinition()}, but no body block &mdash; the
     * declaration is terminated by a semicolon.
     *
     * <p>Pure syntax:
     * <pre>
     *   native function &lt;&lt;stereo&gt;&gt; {tag=v}
     *       my::pkg::fn&lt;T,V|m,n&gt;(p1:T1[m1], p2:T2[m2]):R[m];
     * </pre>
     */
    private NativeFunctionDefinition parseNativeFunction() {
        FunctionSignature sig = parseFunctionSignature();
        expect(TokenType.SEMI_COLON);
        return new NativeFunctionDefinition(
                sig.qualifiedName(), sig.typeParams(), sig.multParams(),
                com.legend.model.FromProtocol.toFunctionParams(sig.params()),
                sig.returnType(), sig.returnMult(),
                com.legend.model.FromProtocol.stereotypes(sig.stereotypes()),
                com.legend.model.FromProtocol.taggedValues(sig.taggedValues()));
    }

    private com.legend.protocol.ParameterDefinition parseFunctionParameter() {
        int pStart = pos;
        String name = parseIdentifier();
        expect(TokenType.COLON);
        TypeExpression type = parseType();
        Multiplicity mult = parseMultiplicity();
        // Engine convention: a parameter's span covers its whole `name: Type[mult]` decl.
        return new com.legend.protocol.ParameterDefinition(name, type, mult,
                span(pStart, pos - 1));
    }

    /**
     * Optional {@code <T,V|m,n>} block declaring generic type and/or
     * multiplicity parameters on a function (concrete or native).
     *
     * <p>Mirrors Pure's M3 grammar:
     * <pre>
     *   typeAndMultiplicityParameters: '<' ( typeParameters ('|' multParameters)? | multParameters ) '>'
     * </pre>
     * Either side may be omitted &mdash; e.g. {@code <T>}, {@code <T,V>},
     * {@code <T|m>}, {@code <T,V|m,n>}, or just {@code <|m>}.
     *
     * <p>Caller passes empty mutable lists; this method appends discovered
     * names. Does nothing if no leading {@code <}.
     */
    private void parseTypeAndMultiplicityParameters(List<String> typeParams,
                                                     List<String> multParams) {
        if (peek() != TokenType.LESS_THAN) return;
        advance(); // consume '<'

        // Type-parameter side (may be empty if next token is PIPE).
        if (peek() != TokenType.PIPE) {
            typeParams.add(parseIdentifier());
            while (match(TokenType.COMMA)) {
                typeParams.add(parseIdentifier());
            }
        }

        // Optional multiplicity side: '|' multParam (',' multParam)*
        if (match(TokenType.PIPE)) {
            multParams.add(parseIdentifier());
            while (match(TokenType.COMMA)) {
                multParams.add(parseIdentifier());
            }
        }

        expect(TokenType.GREATER_THAN);
    }

    // ============================================================
    // Service declaration
    // ============================================================

    /**
     * {@code Service qualifiedName { pattern: ...; documentation: ...;
     * execution: Single { query: |...; mapping: ...; runtime: ...; }
     * testSuites { ... } }}.
     *
     * <p>Unknown top-level keys throw (no silent skip). The query body
     * is parsed eagerly into a {@link ValueSpecification}; the
     * {@code testSuites} block is still captured as raw text (D-3),
     * pending a test-suite grammar.
     */
    private ServiceDefinition parseServiceDefinition() {
        expect(TokenType.SERVICE);
        String qualifiedName = parseQualifiedName();
        expect(TokenType.BRACE_OPEN);

        String pattern = null;
        String documentation = null;
        ValueSpecification functionBody = null;
        String mappingRef = null;
        String runtimeRef = null;
        String testSuitesSource = null;

        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            // Dispatch on the MINTED TOKEN TYPES — the lexer keyword-izes
            // every service body key; re-reading them as identifier strings
            // was the audit's H2 (two sources of truth for one keyword).
            TokenType key = peek();
            String keyText = safeText();
            advance();
            expect(TokenType.COLON);
            switch (key) {
                case SERVICE_PATTERN -> {
                    pattern = unquoteString(consume(TokenType.STRING));
                    expect(TokenType.SEMI_COLON);
                }
                case SERVICE_DOCUMENTATION -> {
                    documentation = unquoteString(consume(TokenType.STRING));
                    expect(TokenType.SEMI_COLON);
                }
                case SERVICE_AUTO_ACTIVATE_UPDATES -> {
                    // A BOOLEAN TOKEN, not a text re-read (re-audit L5).
                    if (peek() != TokenType.TRUE && peek() != TokenType.FALSE) {
                        throw error("autoActivateUpdates expects true or false, got '"
                                + safeText() + "'");
                    }
                    advance();
                    expect(TokenType.SEMI_COLON);
                }
                case SERVICE_OWNERS -> {
                    expect(TokenType.BRACKET_OPEN);
                    if (peek() != TokenType.BRACKET_CLOSE) {
                        consume(TokenType.STRING);
                        while (match(TokenType.COMMA)) consume(TokenType.STRING);
                    }
                    expect(TokenType.BRACKET_CLOSE);
                    expect(TokenType.SEMI_COLON);
                }
                case SERVICE_EXEC -> {
                    // Only the Single flavor is wired; anything else (Multi,
                    // typos) previously parsed AS Single silently (audit M13a).
                    expect(TokenType.SERVICE_SINGLE);
                    expect(TokenType.BRACE_OPEN);
                    while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
                        TokenType execKey = peek();
                        String execKeyText = safeText();
                        if (execKey != TokenType.MAPPING_TESTS_QUERY
                                && execKey != TokenType.SERVICE_MAPPING
                                && execKey != TokenType.SERVICE_RUNTIME) {
                            // Validate BEFORE consuming: the error points AT
                            // the offender, by TEXT (re-audit M4).
                            throw error("unknown key '" + execKeyText
                                    + "' inside Service.execution (Phase B.3 strict mode; see D-2)");
                        }
                        advance();
                        expect(TokenType.COLON);
                        switch (execKey) {
                            case MAPPING_TESTS_QUERY -> {
                                match(TokenType.PIPE); // optional leading '|'
                                int bs = pos;
                                int d = 0;
                                while (!atEnd()) {
                                    TokenType tk = peek();
                                    if (tk == TokenType.PAREN_OPEN) d++;
                                    else if (tk == TokenType.PAREN_CLOSE) d--;
                                    else if (tk == TokenType.SEMI_COLON && d <= 0) break;
                                    advance();
                                }
                                if (pos == bs) {
                                    throw error("empty query expression in Service '"
                                            + qualifiedName + "'");
                                }
                                functionBody = SpecParser.parse(tokens.slice(bs, pos));
                                expect(TokenType.SEMI_COLON);
                            }
                            case SERVICE_MAPPING -> {
                                mappingRef = parseQualifiedName();
                                expect(TokenType.SEMI_COLON);
                            }
                            case SERVICE_RUNTIME -> {
                                runtimeRef = parseQualifiedName();
                                expect(TokenType.SEMI_COLON);
                            }
                            default -> throw new IllegalStateException(
                                    "unreachable: execution keys validated above");
                        }
                    }
                    expect(TokenType.BRACE_CLOSE);
                }
                case MAPPING_TESTABLE_SUITES -> {
                    // Capture the entire balanced block as raw text — D-3.
                    // testSuites may be followed by '{' or '['; both balance the same way.
                    TokenType opener = peek();
                    if (opener != TokenType.BRACE_OPEN && opener != TokenType.BRACKET_OPEN) {
                        throw error("expected '{' or '[' after testSuites:, got " + opener);
                    }
                    int bs = pos;
                    skipBalancedContent(opener,
                            opener == TokenType.BRACE_OPEN ? TokenType.BRACE_CLOSE : TokenType.BRACKET_CLOSE);
                    testSuitesSource = reconstructText(bs, pos);
                    match(TokenType.SEMI_COLON);
                }
                default -> throw error("unknown key '" + keyText + "' inside Service '"
                        + qualifiedName + "' (Phase B.3 strict mode; see D-2)");
            }
        }
        expect(TokenType.BRACE_CLOSE);

        if (functionBody == null) {
            throw error("Service '" + qualifiedName + "' has no query expression");
        }
        return new ServiceDefinition(
                qualifiedName,
                pattern != null ? pattern : "/",
                functionBody,
                documentation,
                mappingRef,
                runtimeRef,
                testSuitesSource);
    }

    // ============================================================
    // Runtime declaration
    // ============================================================

    /**
     * {@code Runtime qualifiedName { mappings: [...]; connections: [...]; }}.
     * Embedded {@code JsonModelConnection} islands ({@code #{ ... }#}) are
     * captured and parsed via regex (engine parity).
     */
    private RuntimeDefinition parseRuntime() {
        expect(TokenType.RUNTIME);
        String qualifiedName = parseQualifiedName();
        return parseRuntimeBody(qualifiedName);
    }

    /**
     * {@code SingleConnectionRuntime qualifiedName { ... }} &mdash; engine's
     * implementation skips the body and returns an empty runtime. We match
     * that here pending real grammar support.
     */
    private RuntimeDefinition parseSingleConnectionRuntime() {
        expect(TokenType.SINGLE_CONNECTION_RUNTIME);
        String qualifiedName = parseQualifiedName();
        // skipBalancedContent consumes the opening '{' itself, then closing '}'.
        skipBalancedContent(TokenType.BRACE_OPEN, TokenType.BRACE_CLOSE);
        return new RuntimeDefinition(qualifiedName, List.of(), Map.of(), List.of());
    }

    private RuntimeDefinition parseRuntimeBody(String qualifiedName) {
        expect(TokenType.BRACE_OPEN);
        List<String> mappings = new ArrayList<>();
        // HashMap (not LinkedHashMap) — bindings are looked up by store name;
        // iteration order is never observed semantically. Saves the per-put
        // linked-list bookkeeping cost.
        Map<String, String> connectionBindings = new HashMap<>();
        List<JsonModelConnection> jsonConnections = new ArrayList<>();

        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            TokenType key = peek();   // minted token, not a re-read string (audit H2)
            String keyText = safeText();
            advance();
            expect(TokenType.COLON);
            switch (key) {
                case MAPPINGS -> {
                    expect(TokenType.BRACKET_OPEN);
                    if (peek() != TokenType.BRACKET_CLOSE) {
                        mappings.add(parseQualifiedName());
                        while (match(TokenType.COMMA)) mappings.add(parseQualifiedName());
                    }
                    expect(TokenType.BRACKET_CLOSE);
                    match(TokenType.SEMI_COLON);
                }
                case CONNECTIONS -> parseRuntimeConnections(connectionBindings, jsonConnections);
                default -> throw error("unknown key '" + keyText + "' inside Runtime '"
                        + qualifiedName + "' (Phase B.3 strict mode; see D-2)");
            }
        }
        expect(TokenType.BRACE_CLOSE);
        return new RuntimeDefinition(qualifiedName, mappings, connectionBindings, jsonConnections);
    }

    private void parseRuntimeConnections(Map<String, String> bindings,
                                         List<JsonModelConnection> jsonConnections) {
        expect(TokenType.BRACKET_OPEN);
        while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
            String storeName = parseQualifiedName();
            expect(TokenType.COLON);
            expect(TokenType.BRACKET_OPEN);
            while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
                parseIdentifier();      // tag (e.g. "id", "json") — engine doesn't keep this
                expect(TokenType.COLON);
                if (peek() == TokenType.ISLAND_OPEN) {
                    advance(); // consume ISLAND_OPEN ('#{' or '#name{')
                    int embStart = pos;
                    while (peek() != TokenType.ISLAND_END && !atEnd()) advance();
                    String embText = reconstructText(embStart, pos);
                    if (peek() == TokenType.ISLAND_END) advance();
                    // LOUD: an unrecognized embedded connection island was
                    // silently consumed and DISCARDED (audit H1). Strict
                    // mode names what it cannot parse.
                    jsonConnections.add(parseEmbeddedJsonModelConnection(embText));
                } else {
                    if (bindings.put(storeName, parseQualifiedName()) != null) {
                        throw error("duplicate connection binding for store '"
                                + storeName + "'");
                    }
                }
                match(TokenType.COMMA);
            }
            expect(TokenType.BRACKET_CLOSE);
            match(TokenType.COMMA);
        }
        expect(TokenType.BRACKET_CLOSE);
        match(TokenType.SEMI_COLON);
    }

    // Compiled once, used by parseEmbeddedJsonModelConnection.
    private static final Pattern JMC_CLASS_PATTERN =
            Pattern.compile("class\\s*:\\s*([\\w:]+)\\s*;");
    private static final Pattern JMC_URL_PATTERN =
            Pattern.compile("url\\s*:\\s*'([^']*)'\\s*;");

    /**
     * Parse an embedded {@code JsonModelConnection { class: ...; url: '...'; }}
     * block via regex against its raw source text. LOUD on anything else —
     * only JsonModelConnection islands are supported, and a typo'd one must
     * not vanish (audit H1; the old null-return silently dropped it).
     */
    private JsonModelConnection parseEmbeddedJsonModelConnection(String raw) {
        raw = raw.trim();
        if (!raw.startsWith("JsonModelConnection")) {
            throw error("unsupported embedded connection flavor (only"
                    + " JsonModelConnection is supported): "
                    + raw.substring(0, Math.min(40, raw.length())));
        }
        Matcher cm = JMC_CLASS_PATTERN.matcher(raw);
        Matcher um = JMC_URL_PATTERN.matcher(raw);
        if (!cm.find() || !um.find()) {
            throw error("malformed JsonModelConnection (expected class: ...;"
                    + " url: '...';): " + raw.substring(0, Math.min(60, raw.length())));
        }
        return new JsonModelConnection(cm.group(1), um.group(1));
    }

    // ============================================================
    // RelationalDatabaseConnection
    // ============================================================

    /**
     * {@code RelationalDatabaseConnection qualifiedName { store: ...; type: ...;
     * specification: ...; auth: ...; }}.
     * Unknown keys throw (strict mode; D-2).
     */
    private ConnectionDefinition parseConnection() {
        expect(TokenType.RELATIONAL_DATABASE_CONNECTION);
        String qualifiedName = parseQualifiedName();
        expect(TokenType.BRACE_OPEN);

        String storeName = null;
        ConnectionDefinition.DatabaseType dbType = null;
        ConnectionSpecification specification = null;
        AuthenticationSpec authentication = null;

        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            TokenType key = peek();   // minted token, not a re-read string (audit H2)
            String keyText = safeText();
            advance();
            expect(TokenType.COLON);
            switch (key) {
                case STORE -> {
                    storeName = parseQualifiedName();
                    expect(TokenType.SEMI_COLON);
                }
                case TYPE -> {
                    String typeStr = parseIdentifier();
                    try {
                        dbType = ConnectionDefinition.DatabaseType.valueOf(typeStr);
                    } catch (IllegalArgumentException ex) {
                        throw error("unknown database type '" + typeStr + "' (expected one of "
                                + java.util.Arrays.toString(ConnectionDefinition.DatabaseType.values()) + ")");
                    }
                    expect(TokenType.SEMI_COLON);
                }
                case RELATIONAL_DATASOURCE_SPEC -> {
                    String specType = parseIdentifier();
                    expect(TokenType.BRACE_OPEN);
                    Map<String, String> props = parseKeyValueBlock();
                    expect(TokenType.BRACE_CLOSE);
                    specification = switch (specType) {
                        case "InMemory" -> new ConnectionSpecification.InMemory();
                        case "LocalFile" -> new ConnectionSpecification.LocalFile(java.util.Objects.requireNonNull(
                                props.get("path"),
                                "LocalFile connection requires 'path'"));
                        case "Static" -> new ConnectionSpecification.StaticDatasource(
                                java.util.Objects.requireNonNull(props.get("host"),
                                        "Static datasource requires 'host'"),
                                props.containsKey("port") ? Integer.parseInt(
                                        java.util.Objects.requireNonNull(props.get("port"))) : 0,
                                java.util.Objects.requireNonNull(props.get("database"),
                                        "Static datasource requires"
                                        + " 'database'"));
                        // no url is the engine's own shape (LocalH2
                        // DatasourceSpecification has no url field — the
                        // engine synthesizes an in-memory database)
                        case "LocalH2" -> new ConnectionSpecification.LocalH2(
                                props.get("url"));
                        default -> throw error("unknown specification flavor '" + specType
                                + "' (expected InMemory / LocalFile / LocalH2 / Static)");
                    };
                    expect(TokenType.SEMI_COLON);
                }
                case RELATIONAL_AUTH_STRATEGY -> {
                    String authType = parseIdentifier();
                    Map<String, String> props = Map.of();
                    if (match(TokenType.BRACE_OPEN)) {
                        props = parseKeyValueBlock();
                        expect(TokenType.BRACE_CLOSE);
                    }
                    authentication = switch (authType) {
                        case "NoAuth" -> new AuthenticationSpec.NoAuth();
                        case "DefaultH2" -> new AuthenticationSpec.DefaultH2();
                        case "UsernamePassword" -> new AuthenticationSpec.UsernamePassword(
                                java.util.Objects.requireNonNull(props.get("username"),
                                        "UsernamePassword auth requires"
                                        + " 'username'"),
                                java.util.Objects.requireNonNull(props.get("passwordVaultRef"),
                                        "UsernamePassword auth requires"
                                        + " 'passwordVaultRef'"));
                        default -> throw error("unknown auth flavor '" + authType
                                + "' (expected NoAuth / DefaultH2 / UsernamePassword)");
                    };
                    expect(TokenType.SEMI_COLON);
                }
                default -> throw error("unknown key '" + keyText
                        + "' inside RelationalDatabaseConnection '" + qualifiedName
                        + "' (Phase B.3 strict mode; see D-2)");
            }
        }
        expect(TokenType.BRACE_CLOSE);

        if (dbType == null) error("RelationalDatabaseConnection '" + qualifiedName + "' missing required 'type:' key");
        if (specification == null) error("RelationalDatabaseConnection '" + qualifiedName + "' missing required 'specification:' key");
        // auth: defaults to NoAuth for LOCAL specs only (LocalFile /
        // InMemory / LocalH2 — the engine's 'mode: local' shape); a Static
        // (remote) connection without auth stays the loud error the real
        // engine gives (audit).
        if (authentication == null) {
            if (specification instanceof ConnectionSpecification.StaticDatasource) {
                error("RelationalDatabaseConnection '" + qualifiedName
                        + "' missing required 'auth:' key");
            }
            authentication = new AuthenticationSpec.NoAuth();
        }

        return new ConnectionDefinition(qualifiedName, storeName,
                java.util.Objects.requireNonNull(dbType, "RelationalDatabaseConnection '"
                        + qualifiedName + "' missing 'type:'"),
                java.util.Objects.requireNonNull(specification, "RelationalDatabaseConnection '"
                        + qualifiedName + "' missing 'specification:'"),
                authentication);
    }

    // ============================================================
    // Shared key/value-block + balanced-content helpers
    // ============================================================

    /**
     * Parse a sequence of {@code key: value;} pairs inside an already-opened
     * brace block, stopping at (but not consuming) the closing {@code }}.
     * Values are either {@code STRING}, {@code INTEGER}, identifiers, or
     * qualified names; all are stored as their raw text.
     */
    private Map<String, String> parseKeyValueBlock() {
        // HashMap: connection properties are looked up by key, never iterated
        // positionally; JDBC URL construction doesn't depend on order either.
        Map<String, String> props = new HashMap<>();
        while (peek() != TokenType.BRACE_CLOSE && !atEnd()) {
            String key = parseIdentifier();
            expect(TokenType.COLON);
            String value;
            if (peek() == TokenType.STRING) {
                value = unquoteString(consume(TokenType.STRING));
            } else if (peek() == TokenType.QUOTED_STRING) {
                // Double-quoted values appear in connection specs
                // (path: "/tmp/db.duckdb") — same string payload, the
                // OTHER quote character.
                String raw = consume(TokenType.QUOTED_STRING);
                value = raw.length() >= 2 && raw.charAt(0) == '"'
                        && raw.charAt(raw.length() - 1) == '"'
                        ? raw.substring(1, raw.length() - 1) : raw;
            } else if (peek() == TokenType.INTEGER) {
                value = consume(TokenType.INTEGER);
            } else {
                value = parseQualifiedName();
            }
            if (props.put(key, value) != null) {
                throw error("duplicate key '" + key + "' in property block");
            }
            // The last pair's semicolon is optional in corpus sources
            // (LocalH2 { url: '...' }); a following key still needs one.
            if (peek() != TokenType.BRACE_CLOSE) {
                expect(TokenType.SEMI_COLON);
            }
        }
        return props;
    }

    /**
     * Skip over a balanced {@code open..close} region. The opener at the
     * current position is consumed; advance until the matching close is
     * consumed too. Handles arbitrary nesting of the same open/close pair.
     */
    void skipBalancedContent(TokenType open, TokenType close) {
        expect(open);
        int depth = 1;
        while (!atEnd() && depth > 0) {
            TokenType t = peek();
            if (t == open) depth++;
            else if (t == close) depth--;
            advance();
            if (depth == 0) return;
        }
    }

    /** Strip the leading and trailing {@code '} from a {@code STRING} token's raw text. */
    String unquoteString(String raw) {
        // Routes through THE shared decoder (which throws on malformed
        // input) — no legacy fallback: an unterminated literal at EOF used
        // to flow through with its leading quote intact (re-audit M2).
        return TokenStreamCursor.unquoteAndUnescape(raw, this);
    }

    // ============================================================
    // Source-text reconstruction (for lazy expression bodies)
    // ============================================================

    /**
     * Reconstruct the verbatim source text spanning tokens
     * {@code [startToken, endToken)}. Returns empty string if the range is
     * empty. Still used to capture {@code testSuites} blocks (D-3) and
     * embedded JSON-island raw text; expression bodies are now sliced and
     * handed to {@link SpecParser} instead of being kept as text.
     */
    String reconstructText(int startToken, int endToken) {
        if (startToken >= endToken) return "";
        int charStart = tokens.start(startToken);
        int charEnd = tokens.end(endToken - 1);
        return tokens.source().substring(charStart, charEnd);
    }

    // ============================================================
    // Property declaration (regular)
    // ============================================================

    private com.legend.protocol.Protocol.PProperty parseProperty() {
        int startTok = pos;
        List<com.legend.protocol.Protocol.PStereotype> stereotypes = parseStereotypes();
        List<com.legend.protocol.Protocol.PTaggedValue> taggedValues = parseTaggedValues();
        // AGGREGATION KIND — (composite) / (shared) / (none); UPPERCASE on the wire
        // (ProbeWireShapes "agg kind and varchar")
        String aggregation = null;
        if (peek() == TokenType.PAREN_OPEN) {
            advance();
            String kind = parseIdentifier();
            if (!kind.equals("composite") && !kind.equals("shared") && !kind.equals("none")) {
                throw error("unknown aggregation kind '(" + kind + ")'");
            }
            aggregation = kind.toUpperCase(java.util.Locale.ROOT);
            expect(TokenType.PAREN_CLOSE);
        }
        String name = parseIdentifier();
        expect(TokenType.COLON);
        TypeExpression type = parseType();   // parseType threads the type's own span onto the node
        Multiplicity mult = parseMultiplicity();
        // property DEFAULT VALUE (real pure: prop: Boolean[1] = false;). The expression is
        // captured as a value-spec tree via SpecParser over a slice of THIS token stream, so
        // positions stay file-absolute. If SpecParser cannot read it the parser STAYS TOTAL —
        // the default is carried with a null value and the emitter walls loudly (never a
        // silent drop; the harness found exactly that failure mode on its first corpus run).
        com.legend.protocol.Protocol.PDefaultValue defaultValue = null;
        if (match(TokenType.EQUAL)) {
            int defStart = pos;
            int depth = 0;
            while (!atEnd()) {
                TokenType t = peek();
                if (depth == 0 && t == TokenType.SEMI_COLON) {
                    break;
                }
                if (t == TokenType.PAREN_OPEN || t == TokenType.BRACKET_OPEN
                        || t == TokenType.BRACE_OPEN) {
                    depth++;
                } else if (t == TokenType.PAREN_CLOSE
                        || t == TokenType.BRACKET_CLOSE
                        || t == TokenType.BRACE_CLOSE) {
                    depth--;
                }
                advance();
            }
            ValueSpecification value;
            try {
                value = SpecParser.parse(tokens.slice(defStart, pos));
            } catch (ParseException unsupportedExpression) {
                value = null;   // parser stays total; the emitter walls on the null, loudly
            }
            defaultValue = new com.legend.protocol.Protocol.PDefaultValue(
                    value, span(defStart, pos - 1));
        }
        expect(TokenType.SEMI_COLON);
        // Positions are captured HERE, at construction, because this is the only point where the
        // token span of this property is in hand. No side table, no second pass.
        return new com.legend.protocol.Protocol.PProperty(
                name, type, mult, stereotypes, taggedValues,
                span(startTok, pos - 1), defaultValue, aggregation);
    }

    /** A {@link com.legend.protocol.SourceInfo} for an inclusive token range. */
    private com.legend.protocol.SourceInfo span(int fromTok, int toTok) {
        return new com.legend.protocol.SourceInfo("",
                tokens.startLine(fromTok), tokens.startColumn(fromTok),
                tokens.endLine(toTok), tokens.endColumn(toTok));
    }


    // ============================================================
    // Shared helpers (engine-parity)
    // ============================================================

    // -----------------------------------------------------------------
    // TokenStreamCursor accessors.
    //
    // Implementing the interface gives us the entire lexical layer
    // (peek/match/expect/consume/advance/text/safeText/textEquals/error,
    // parseIdentifier, parseQualifiedName) plus the type-expression
    // grammar (parseType / parseTypeArgument / parseMultiplicity and
    // their sub-grammars) as inherited defaults.
    //
    // Previously ElementParser carried its own private duplicates of
    // every primitive AND a bridge that allocated a standalone helper
    // class for each type expression. Both layers are now gone; the
    // interface is the single source of truth.
    // -----------------------------------------------------------------

    @Override
    public TokenStream tokens() {
        return tokens;
    }

    @Override
    public int pos() {
        return pos;
    }

    @Override
    public void setPos(int pos) {
        this.pos = pos;
    }

    /** {@code <<profile.stereotype, ...>>}; returns empty list if absent. */
    List<com.legend.protocol.Protocol.PStereotype> parseStereotypes() {
        if (peek() != TokenType.LESS_THAN || peek(1) != TokenType.LESS_THAN) {
            return List.of();
        }
        advance();
        advance();
        List<com.legend.protocol.Protocol.PStereotype> result = new ArrayList<>();
        result.add(parseStereotype());
        while (match(TokenType.COMMA)) {
            result.add(parseStereotype());
        }
        expect(TokenType.GREATER_THAN);
        expect(TokenType.GREATER_THAN);
        return result;
    }

    private com.legend.protocol.Protocol.PStereotype parseStereotype() {
        int profStart = pos;
        String profile = parseQualifiedName();
        int profEnd = pos - 1;
        expect(TokenType.DOT);
        String name = parseIdentifier();
        // profileSourceInformation covers the profile FQN; sourceInformation the whole ptr.
        return new com.legend.protocol.Protocol.PStereotype(profile, name,
                span(profStart, profEnd), span(profStart, pos - 1));
    }

    /** {@code { profile.tag = 'value', ... }}; returns empty list if not a tag block. */
    List<com.legend.protocol.Protocol.PTaggedValue> parseTaggedValues() {
        if (peek() != TokenType.BRACE_OPEN) return List.of();
        if (!looksLikeTaggedValueBlock(tokens, pos)) return List.of();

        advance(); // skip {
        List<com.legend.protocol.Protocol.PTaggedValue> result = new ArrayList<>();
        result.add(parseTaggedValue());
        while (match(TokenType.COMMA)) {
            result.add(parseTaggedValue());
        }
        expect(TokenType.BRACE_CLOSE);
        return result;
    }

    /**
     * Heuristic: does the {@code {} block starting at {@code bracePos} look
     * like a tagged-value block (e.g.
     * {@code {meta::pure::profiles::doc.doc = 'desc'}}) rather than the
     * element body?
     *
     * <p>Pattern matched: {@code '{' IDENT ('::' IDENT)* '.' IDENT '='}.
     * Shared by the parser (which dispatches on this when consuming tagged
     * values inline) and the shallow scanner in the IDE layer
     * ({@code com.legend.ide.ModelIndexer}), which
     * needs to skip a tagged-value block to reach the FQN. <strong>Both
     * must agree on the heuristic</strong>: if the scanner says yes but
     * the parser says no (or vice versa), token offsets drift and
     * downstream parses fail confusingly. Keeping the predicate in one
     * place prevents that drift.
     */
    public static boolean looksLikeTaggedValueBlock(TokenStream tokens, int bracePos) {
        int n = tokens.count();
        if (bracePos >= n || tokens.type(bracePos) != TokenType.BRACE_OPEN) return false;
        int p = bracePos + 1;
        if (p >= n || !IDENTIFIER_TOKENS.contains(tokens.type(p))) return false;
        while (p < n && IDENTIFIER_TOKENS.contains(tokens.type(p))) {
            p++;
            if (p >= n) return false;
            if (tokens.type(p) == TokenType.PATH_SEPARATOR) p++;
            else break;
        }
        if (p >= n || tokens.type(p) != TokenType.DOT) return false;
        p++;
        if (p >= n || !IDENTIFIER_TOKENS.contains(tokens.type(p))) return false;
        p++;
        return p < n && tokens.type(p) == TokenType.EQUAL;
    }

    private com.legend.protocol.Protocol.PTaggedValue parseTaggedValue() {
        int start = pos;
        int profStart = pos;
        String profile = parseQualifiedName();
        int profEnd = pos - 1;
        expect(TokenType.DOT);
        int tagStart = pos;
        String tag = parseIdentifier();
        int tagEnd = pos - 1;
        expect(TokenType.EQUAL);
        String rawValue = consume(TokenType.STRING);
        // Unquote AND unescape: the wire carries the LOGICAL string ("it's", not "it\\'s") —
        // quote-stripping alone left escapes behind (harness DIFF on dateExtension.pure).
        String value = TokenStreamCursor.unquoteAndUnescape(rawValue, this);
        // NOTE the asymmetry, verified against legend-engine: a TAG's sourceInformation covers only
        // the tag name, while a STEREOTYPE's covers the whole profile.name.
        return new com.legend.protocol.Protocol.PTaggedValue(
                new com.legend.protocol.Protocol.PTag(profile, tag,
                        span(profStart, profEnd), span(tagStart, tagEnd)),
                value, span(start, pos - 1));
    }

    // ============================================================
    // Token cursor: peek/peek(int)/text/safeText/textEquals/advance/
    // atEnd/match/expect/consume/error/parseIdentifier/parseQualifiedName
    // all live on TokenStreamCursor as default methods. The local
    // duplicates that used to sit here were removed when this class
    // started implementing the interface.
    // ============================================================
}
