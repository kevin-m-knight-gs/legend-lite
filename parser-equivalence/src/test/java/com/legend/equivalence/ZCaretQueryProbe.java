package com.legend.equivalence;

import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperValueSpecificationBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.m3.function.LambdaFunction;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.junit.jupiter.api.Test;

/** PROBE (^Class() in queries): the REAL engine's verdict, end to end —
 *  compile the lambda against a compiled model, then generate a
 *  relational execution plan. Diagnostic only. */
class ZCaretQueryProbe {

    private static final String MODEL = """
            Class model::Person { name: String[1]; }
            ###Relational
            Database store::DB ( Table T_PERSON (NAME VARCHAR(100) PRIMARY KEY) )
            ###Mapping
            Mapping model::M
            (
                model::Person: Relational { ~mainTable [store::DB] T_PERSON
                    name: [store::DB] T_PERSON.NAME }
            )
            ###Connection
            RelationalDatabaseConnection store::Conn
            {
                store: store::DB; type: H2;
                specification: LocalH2 {};
                auth: DefaultH2;
            }
            ###Runtime
            Runtime test::RT
            {
                mappings: [ model::M ];
                connections: [ store::DB: [ c: store::Conn ] ];
            }
            """;

    private void verdict(String name, String lambdaText) {
        try {
            PureModelContextData pmcd =
                    PureGrammarParser.newInstance().parseModel(MODEL);
            PureModel pm = Compiler.compile(pmcd, DeploymentMode.TEST, null);
            LambdaFunction lambda = PureGrammarParser.newInstance()
                    .parseLambda(lambdaText);
            var compiled = HelperValueSpecificationBuilder.buildLambda(
                    lambda.body, lambda.parameters, pm.getContext());
            System.out.println("@@ COMPILE-OK " + name);
            var mapping = pm.getMapping("model::M");
            var runtime = pm.getRuntime("test::RT");
            var plan = org.finos.legend.engine.plan.generation.PlanGenerator
                    .generateExecutionPlan(compiled, mapping, runtime, null,
                            pm, "vX_X_X",
                            org.finos.legend.engine.plan.platform
                                    .PlanPlatform.JAVA,
                            null,
                            org.finos.legend.engine.pure.code.core
                                    .PureCoreExtensionLoader.extensions()
                                    .flatCollect(e -> e.extraPureCoreExtensions(
                                            pm.getExecutionSupport())),
                            org.finos.legend.engine.plan.generation
                                    .transformers.LegendPlanTransformers
                                    .transformers);
            System.out.println("@@ PLAN-OK " + name + " ("
                    + plan.rootExecutionNode.getClass().getSimpleName() + ")");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.out.println("@@ REFUSE " + name + " :: "
                    + root.getClass().getSimpleName() + " :: "
                    + String.valueOf(root.getMessage())
                            .replaceAll("\\s+", " "));
        }
    }

    @Test
    void engineVerdictsOnCaretInQueries() {
        verdict("control-project",
                "|model::Person.all()->project([p|$p.name], ['n'])");
        verdict("caret-bare", "|^model::Person(name='x')");
        verdict("caret-in-filter",
                "|model::Person.all()"
                        + "->filter(p|$p.name == ^model::Person(name='x').name)"
                        + "->project([p|$p.name], ['n'])");
        verdict("caret-in-project",
                "|model::Person.all()"
                        + "->project([p|^model::Person(name=$p.name).name], ['n'])");
    }
}
