package com.legend.compiler;

import com.legend.Compiler;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.parser.SpecParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TabularDataSet is the SCHEMA-ERASING nominal over the relation carrier:
 * a corpus function declared over {@code LambdaFunction<{->TabularDataSet[1]}>}
 * accepts the platform's typed row-struct lambdas (both kernel halves —
 * scoring and unification — must agree). The corpus shape is milestoning's
 * {@code concatenateTemporalTdsQueries($lfs)}.
 */
class TdsLambdaProbeTest {

    private static final String MODEL = """
            Class p::Item { name: String[1]; }
            function p::concatQ(lfs:LambdaFunction<{->TabularDataSet[1]}>[*]):Integer[1]
            {
               $lfs->size();
            }
            """;

    @Test
    @DisplayName("row-struct lambda conforms to LambdaFunction<{->TabularDataSet[1]}>")
    void rowStructLambdaConformsToTdsCarrier() {
        var ctx = Compiler.compileModel(MODEL);
        SpecCompiler specs = new SpecCompiler(ctx);
        var body = specs.typeQueryBody(NameResolver.resolveQuery(SpecParser.parse(
                "p::concatQ([{|p::Item.all()->project([x|$x.name],['name'])}])")));
        assertEquals(Type.Primitive.INTEGER,
                body.get(body.size() - 1).info().type());
    }

    @Test
    @DisplayName("VALUE-path conformance: let-bound lambda list into the TDS carrier param")
    void letBoundLambdaListConforms() {
        var ctx = Compiler.compileModel(MODEL);
        SpecCompiler specs = new SpecCompiler(ctx);
        var body = specs.typeQueryBody(NameResolver.resolveQuery(SpecParser.parse(
                "{|let lfs = [%2015-10-16, %2015-10-17]->map(bd|"
                + "{|p::Item.all()->project([x|$x.name],['name'])}"
                + "->meta::pure::functions::meta::evaluateAndDeactivate());"
                + "p::concatQ($lfs);}")));
        Type t = body.get(body.size() - 1).info().type();
        assertEquals(Type.Primitive.INTEGER, t instanceof Type.FunctionType ft
                ? ft.result().type() : t);
    }
}
