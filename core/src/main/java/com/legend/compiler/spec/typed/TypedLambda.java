package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A type-checked lambda argument (engine {@code TypedLambda}). Its parameter
 * types were solved from the surrounding call (the bidirectional lambda step,
 * §3.4), so by the time the {@code body} was checked every parameter was
 * concrete.
 *
 * <p>{@link #info()} carries the value's CLASSIFIER, m3-true: a lambda
 * literal is a {@code LambdaFunction<ft>} instance (m3.pure — LambdaFunction
 * extends FunctionDefinition extends Function), never a bare structural
 * {@code FunctionType}. The constructor normalizes a bare-FunctionType info
 * to the carrier so every mint site — present and future — produces the
 * engine stamp; a caller that means a DIFFERENT carrier (the eta-expanded
 * reference to a concrete function classifies as
 * {@code ConcreteFunctionDefinition<ft>}) passes that carrier explicitly and
 * it is kept. {@link #functionType()} is the structural signature reader.
 *
 * @param parameters parameter names, in order
 * @param body       the type-checked body statements
 * @param info       the lambda's classifier (a function carrier over its
 *                   {@code FunctionType})
 */
public record TypedLambda(List<String> parameters, List<TypedSpec> body, ExprType info) implements TypedSpec {
    public TypedLambda {
        parameters = List.copyOf(parameters);
        body = List.copyOf(body);
        if (info.type() instanceof com.legend.compiler.element.type.Type.FunctionType ft) {
            info = new ExprType(
                    com.legend.compiler.element.type.PlatformTypes.lambdaType(ft),
                    info.multiplicity());
        }
    }

    /** The structural signature under the classifier — parameter and result
     * shapes for consumers that compute with the lambda, not about it. */
    public com.legend.compiler.element.type.Type.FunctionType functionType() {
        var ft = com.legend.compiler.element.type.PlatformTypes.functionTypeOf(info.type());
        if (ft == null) {
            throw new IllegalStateException(
                    "TypedLambda with a non-function classifier: " + info.type().typeName());
        }
        return ft;
    }

    @Override
    public List<TypedSpec> children() {
        return body;
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        return new TypedLambda(parameters, kids, info);
    }
}
