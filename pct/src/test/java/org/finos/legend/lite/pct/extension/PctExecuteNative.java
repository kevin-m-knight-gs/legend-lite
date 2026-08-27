// Copyright 2026 Legend Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.lite.pct.extension;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.stack.MutableStack;
import com.legend.compiler.element.type.Type;
import com.legend.exec.ExecutionResult;
import com.legend.exec.ExecutionResult.Scalar;
import com.legend.exec.ExecutionResult.Collection;
import com.legend.exec.ExecutionResult.Tabular;
import com.legend.exec.ExecutionResult.Graph;
import com.legend.server.QueryService;

import org.finos.legend.pure.m3.compiler.Context;
import org.finos.legend.pure.m3.exception.PureExecutionException;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.PrimitiveUtilities;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.interpreted.ExecutionSupport;
import org.finos.legend.pure.runtime.java.interpreted.FunctionExecutionInterpreted;
import org.finos.legend.pure.runtime.java.interpreted.VariableContext;
import org.finos.legend.pure.runtime.java.interpreted.natives.InstantiationContext;
import org.finos.legend.pure.runtime.java.interpreted.natives.NativeFunction;
import org.finos.legend.pure.runtime.java.interpreted.profiler.Profiler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * Native function that bridges PCT tests to Legend-Lite's QueryService.
 *
 * Pure expressions are executed via QueryService (compile → SQL → DuckDB),
 * and the typed ExecutionResult is converted back to Pure CoreInstances.
 *
 * Type information flows from Type on ExecutionResult: column names,
 * pure types, and multiplicities are the PLATFORM's typed facts (F5.1
 * replaced the sqlType-name sniff; F5.3 Stage B deleted the
 * declared-header overlay and the null-scan — PCT sees the wire).
 */
/**
 * THE THIN ENTRY (the derived minimum's orchestration piece, census
 * §5b): read the expression + semantic roots, open the session, hand
 * the packed model to QueryService, route the typed result through
 * the {@link ValueBridge}, re-address errors to the test's own call
 * site. Every decision lives in the platform or in the two named
 * pieces — this file only sequences them.
 */
public class PctExecuteNative extends NativeFunction {

    // (The PURE_MODEL scaffold — a fixed Doy model/mapping/connection/
    // runtime — is DELETED, truthfulness burn B1: PCT expressions are
    // STORELESS, and the platform executes them against a bare
    // connection with no model and no runtime; the dialect derives
    // from the CONNECTION's own product metadata (Compiler.dialectOf's
    // connection seam), which the scaffold's `type: H2;` flip was
    // shadowing. Probed on both backends before the cut.)

    // (The five discovery regexes — INSTANCE_CLASS/TYPE_REF/ENUM_REF/
    // PARAM_TYPE/BARE_REF/FQN_TOKEN — are DELETED: R1 differential,
    // 2026-08-27. Discovery is the pure-side collectRoots M3 walk.)

    private final ValueBridge bridge;
    private final ModelPacker packer;

    public PctExecuteNative(FunctionExecutionInterpreted functionExecution, ModelRepository modelRepository) {
        this.bridge = new ValueBridge(modelRepository);
        this.packer = new ModelPacker(functionExecution);
    }

    // ===== execute =====

    @Override
    public CoreInstance execute(
            ListIterable<? extends CoreInstance> params,
            Stack<MutableMap<String, CoreInstance>> resolvedTypeParameters,
            Stack<MutableMap<String, CoreInstance>> resolvedMultiplicityParameters,
            VariableContext variableContext,
            MutableStack<CoreInstance> functionExpressionCallStack,
            Profiler profiler,
            InstantiationContext instantiationContext,
            ExecutionSupport executionSupport,
            Context context,
            ProcessorSupport processorSupport) throws PureExecutionException {

        // J2 (slice-4 census): reEscapeStringLiterals is DELETED BY
        // MEASUREMENT — zero input-changing calls across the full DuckDB
        // lane (1115) and the h2 Relation lane. The serializer hands the
        // expression over parse-ready.
        String pureExpression = PrimitiveUtilities.getStringValue(
                Instance.getValueForMetaPropertyToOneResolved(params.get(0), M3Properties.values, processorSupport));

        // R1 (census §5b): the SEMANTIC dependency roots — element paths
        // the pure-side collectRoots walk read off the M3 tree. The
        // differential below judges the regex discovery against them.
        java.util.List<String> semanticRoots = new ArrayList<>();
        for (CoreInstance v : Instance.getValueForMetaPropertyToManyResolved(
                params.get(1), M3Properties.values, processorSupport)) {
            semanticRoots.add(PrimitiveUtilities.getStringValue(v));
        }

        System.out.println("[LegendLite PCT] Executing: " + pureExpression);

        // LEGENDLITE_PCT_BACKEND=h2 runs the SAME suite on the H2
        // execution dialect (env, not -D: it must survive the surefire
        // fork) — the session mirrors the portability sweep's settings.
        boolean h2 = "h2".equalsIgnoreCase(
                String.valueOf(System.getenv("LEGENDLITE_PCT_BACKEND")));
        try (Connection connection = DriverManager.getConnection(h2
                ? "jdbc:h2:mem:" + com.legend.exec.H2Settings.SETTINGS
                : "jdbc:duckdb:", h2 ? "sa" : null, h2 ? "" : null)) {
            // DuckDB pins the session to UTC (its driver's Timestamps are
            // wall-preserving under it). H2 must NOT: its driver funnels
            // zone-less TIMESTAMPs through the SESSION zone, so a UTC
            // session + local JVM shifted every wall time by the offset
            // (witnessed: 2026-01-07T00:00 read back as 01-06T19:00);
            // the JVM-local default round-trips wall times exactly, the
            // same contract the corpus sweep runs under.
            if (!h2) {
                try (var tzStmt = connection.createStatement()) {
                    tzStmt.execute("SET TimeZone='UTC'");
                }
            }

            // R1 (census §5b + §6): the model injection builds from the
            // SEMANTIC roots the pure-side collectRoots walk supplied —
            // discovery reads the M3 tree the interpreter holds. The
            // five discovery regexes are DELETED BY DIFFERENTIAL
            // (2026-08-27): one full-lane run built the injection both
            // ways — the walk found every element the regexes found,
            // and the only regex-only rows were the ^Pair(...) sites
            // WRONGLY injecting a shadow copy of the platform's native
            // Pair (the walk's native-class filter refuses it; all four
            // tests pass on the real Pair).
            java.util.TreeMap<String, String> injection =
                    packer.injectionFromRoots(semanticRoots, processorSupport);
            StringBuilder defs = new StringBuilder();
            // grouped enums → classes → functions (the E:/C:/F: keys)
            for (String prefix : List.of("E:", "C:", "F:")) {
                for (var en : injection.entrySet()) {
                    if (en.getKey().startsWith(prefix)) {
                        defs.append(en.getValue()).append("\n");
                    }
                }
            }
            if (defs.length() > 0) {
                System.out.println("[LegendLite PCT] Injected model:\n" + defs);
            }
            String model = defs.toString();

            // E1 (JAVA_EVICTION_PLAN): relation-rooted queries render
            // their PCT wire text IN THE PLAN (Lowerer PCT-TDS root
            // mode) — the adapter receives one Scalar String and hands
            // it over verbatim; formatAsTds/formatValue are gone.
            ExecutionResult result;
            boolean tdsRendered;
            try (AutoCloseable ignored2 =
                    com.legend.exec.PctRenderOption.enable()) {
                result = new QueryService().execute(model, pureExpression,
                        null, connection);
                tdsRendered = com.legend.exec.PctRenderOption.wasRendered();
            }
            if (tdsRendered) {
                String tdsString = String.valueOf(((Scalar) result).value());
                System.out.println("[LegendLite PCT] TDS: "
                        + tdsString.replace("\n", "\\n"));
                return bridge.createTDSResult(tdsString, processorSupport);
            }

            return switch (result) {
                case Scalar s -> bridge.handleScalar(s, processorSupport);
                case Collection c -> bridge.handleCollection(c, processorSupport);
                case Tabular t -> throw new IllegalStateException(
                        "PCT tabular result outside the render mode — the"
                        + " root-mode wrap missed a relation root");
                case Graph g -> bridge.graphString(g.json(), processorSupport);
            };
        } catch (Exception e) {
            // the error's SOURCE INFO must point at the TEST's own call site
            // (assertError checks line/column) — walk past adapter frames
            org.finos.legend.pure.m4.coreinstance.SourceInformation src =
                    functionExpressionCallStack.peek().getSourceInformation();
            for (var frame : functionExpressionCallStack) {
                var fs = frame.getSourceInformation();
                if (fs != null && fs.getSourceId() != null
                        && !fs.getSourceId().contains("core_legend_lite_pct")) {
                    src = fs;
                    break;
                }
            }
            // B7 (RaisedErrors): the message arrives ALREADY clean — the
            // platform's Executor funnel unwrapped the transport envelope
            // from platform-raised text (provenance-sentinel scoped).
            // remapErrorMessage — the last adapter arm that ever touched
            // a message — is DELETED; native errors cross whole.
            throw new PureExecutionException(src, e.getMessage(), e);
        }
    }
}
