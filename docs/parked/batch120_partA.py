import re,sys,os
def rd(p): return open(p).read()
def wr(p,s): open(p,'w').write(s)
def sub1(s,old,new,p):
    assert s.count(old)==1, (p, old[:60], s.count(old)); return s.replace(old,new)

p='core/src/main/java/com/legend/compiler/spec/typed/ExecutionContext.java'; s=rd(p)
s=sub1(s,''' *                       table's primary-key columns
 */''',''' *                       table's primary-key columns
 * @param postProcessors the connection's SQL post-processors (engine
 *                       sqlQueryPostProcessors / MapperPostProcessor): the table
 *                       renames, CTE extraction, the nonExecutable pass — IR
 *                       passes applied over the frame's lowered plan
 */''',p)
s=sub1(s,'''                               boolean driverTablePk) {''','''                               boolean driverTablePk,
                               PostProcessors postProcessors) {
    /** The connection post-processor facts of one frame: {@code tableReplace}
     * renames (TableNameMapper), whether CTE extraction is installed, whether
     * the nonExecutable pass is installed. */
    public record PostProcessors(Map<String, String> tableReplace, boolean extractCtes,
                                 boolean nonExecutable) {
        public static final PostProcessors NONE = new PostProcessors(Map.of(), false, false);

        public PostProcessors {
            tableReplace = Map.copyOf(tableReplace);
        }

        public PostProcessors withNonExecutable(boolean on) {
            return on == nonExecutable ? this
                    : new PostProcessors(tableReplace, extractCtes, on);
        }
    }''',p)
s=s.replace('null, null, null, null, false)','null, null, null, null, false, PostProcessors.NONE)')
assert s.count('PostProcessors.NONE)')==2
s=s.replace('connectionInstance, storeFqn, driverTablePk)','connectionInstance, storeFqn, driverTablePk, postProcessors)')
s=sub1(s,'''                        connectionInstance, storeFqn, pk);
    }''','''                        connectionInstance, storeFqn, pk, postProcessors);
    }

    /** This context with other post-processor facts (a text surface that
     * runs its query under the producer's own nonExecutable pass). */
    public ExecutionContext withPostProcessors(PostProcessors pp) {
        return pp.equals(postProcessors) ? this
                : new ExecutionContext(mapping, runtime, chainMappings, jsonSources, sqlSetups,
                        csvSetups, connectionName, quoteIdentifiers, timeZone, databaseType,
                        connectionInstance, storeFqn, driverTablePk, pp);
    }''',p)
wr(p,s)

p='core/src/main/java/com/legend/lowering/SqlPostProcessors.java'; s=rd(p); lines=s.split('\n')
def idx(pred, start=0):
    for i in range(start,len(lines)):
        if pred(lines[i]): return i
    raise AssertionError('idx')
def back_over_comments(i):
    while i>0 and (lines[i-1].strip().startswith('*') or lines[i-1].strip().startswith('/**') or lines[i-1].strip().startswith('//')): i-=1
    return i
a=back_over_comments(idx(lambda l:'public static Map<String, String> tableReplaceMap(' in l))
b=back_over_comments(idx(lambda l:'public static Map<String, String> reachableRenames(' in l))
c=back_over_comments(idx(lambda l:'private static void collectConnections(' in l))
d=idx(lambda l:'// ===== the IR rewrite =====' in l)
moved=lines[c:d]
while moved and moved[-1].strip()=='': moved.pop()
lines=lines[:a]+lines[b:c]+lines[d:]
s='\n'.join(lines)
s=sub1(s,'''            for (var e : hooks(rt, letBound).tableReplace().entrySet()) {''','''            for (var e : com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(letBound).read(java.util.Optional.empty(), rt)
                    .postProcessors().tableReplace().entrySet()) {''',p)
wr(p,s)
moved_text='\n'.join(moved)
p='core/src/main/java/com/legend/compiler/spec/typed/ContextReading.java'; s=rd(p)
for name in ['elements','peel','stringOf','tableName','calleeOf','composeRename','readHook','mapperLiteral','readMapperPostProcessor','collectConnections']:
    if re.search(r'\b'+name+r'\(', s):
        moved_text=re.sub(r'\b'+name+r'\(', 'pp'+name[0].upper()+name[1:]+'(', moved_text)
        print('renamed clashing helper', name)
s=sub1(s,'''                storeFqn(runtimeArg), false);
    }''','''                storeFqn(runtimeArg), false, postProcessors(runtimeArg));
    }

    /** The connection's SQL post-processors (sqlQueryPostProcessors /
     * sqlQueryPostProcessorsConnectionAware hooks, MapperPostProcessor
     * postProcessors) as the frame's post-processor facts. */
    ExecutionContext.PostProcessors postProcessors(TypedSpec runtimeArg) {
        Map<String, String> out = new LinkedHashMap<>();
        // [0] = CTE extraction installed, [1] = nonExecutable installed
        boolean[] cte = {false, false};
        collectConnections(runtimeArg, out, cte, bind);
        return new ExecutionContext.PostProcessors(out, cte[0], cte[1]);
    }

'''+moved_text+'''
''',p)
s=sub1(s,'import com.legend.compiler.element.type.PlatformTypes;','import com.legend.compiler.element.type.PlatformTypes;\nimport com.legend.error.NotImplementedException;',p)
wr(p,s)

p='core/src/main/java/com/legend/StatementExecutor.java'; s=rd(p)
s=sub1(s,'''            java.util.List<com.legend.protocol.spec.ValueSpecification> protocolBody) {
        /** Without the protocol body''','''            java.util.List<com.legend.protocol.spec.ValueSpecification> protocolBody,
            com.legend.compiler.spec.typed.@com.legend.Nullable ExecutionContext frame) {
        /** Without the protocol body''',p)
s=sub1(s,'''                    tableReplace, instanceIds, assertListener, replayOracle, planRows,
                    java.util.List.of());
        }''','''                    tableReplace, instanceIds, assertListener, replayOracle, planRows,
                    java.util.List.of(), null);
        }
        /** The executing frame's bound context (post-processors, time zone,
         * options) — set where an execute frame is entered. */
        ExecEnv withFrame(com.legend.compiler.spec.typed.ExecutionContext f) {
            return new ExecEnv(ctx, runtimeFqn, dialect, connection, addDriverTablePk,
                    queryLets, tableReplace, instanceIds, assertListener, replayOracle,
                    planRows, protocolBody, f);
        }
        ExecEnv withTableReplace(java.util.Map<String, String> tr) {
            return new ExecEnv(ctx, runtimeFqn, dialect, connection, addDriverTablePk,
                    queryLets, tr, instanceIds, assertListener, replayOracle,
                    planRows, protocolBody, frame);
        }
        ExecEnv withListeners(com.legend.exec.@com.legend.Nullable AssertListener l,
                com.legend.exec.@com.legend.Nullable SqlReplayOracle o) {
            return new ExecEnv(ctx, runtimeFqn, dialect, connection, addDriverTablePk,
                    queryLets, tableReplace, instanceIds, l, o, planRows, protocolBody, frame);
        }
        ExecEnv withPostProcessors(com.legend.compiler.spec.typed.ExecutionContext.PostProcessors pp) {
            return withFrame((frame == null ? com.legend.compiler.spec.typed.ExecutionContext.NONE
                    : frame).withPostProcessors(pp));
        }
        com.legend.compiler.spec.typed.ExecutionContext.PostProcessors postProcessors() {
            return frame == null ? com.legend.compiler.spec.typed.ExecutionContext.PostProcessors.NONE
                    : frame.postProcessors();
        }
        /** The frame connection's time zone (every DateTime literal spells in it). */
        @com.legend.Nullable String timeZone() {
            return frame == null ? null : frame.timeZone();
        }''',p)
s=sub1(s,'''                    instanceIds, assertListener, replayOracle, planRows,
                    protocolBody);
        }''','''                    instanceIds, assertListener, replayOracle, planRows,
                    protocolBody, frame);
        }''',p)
s=sub1(s,'''                    assertListener, replayOracle, planRows, body);''','''                    assertListener, replayOracle, planRows, body, frame);''',p)
s=sub1(s,'''        ExecEnv env = assertListener == null && replayOracle == null ? env0
                : new ExecEnv(env0.ctx(), env0.runtimeFqn(), env0.dialect(),
                        env0.connection(), env0.addDriverTablePk(),
                        env0.queryLets(), env0.tableReplace(),
                        env0.instanceIds(), assertListener, replayOracle,
                        env0.planRows());''','''        ExecEnv env = assertListener == null && replayOracle == null ? env0
                : env0.withListeners(assertListener, replayOracle);''',p)
s=sub1(s,'''        return union == null ? env
                : new ExecEnv(env.ctx(), env.runtimeFqn(), env.dialect(),
                        env.connection(),
                        env.addDriverTablePk(), env.queryLets(), union,
                        env.instanceIds(), env.assertListener(),
                        env.replayOracle(), env.planRows());''','''        return union == null ? env : env.withTableReplace(union);''',p)
s=sub1(s,'''            com.legend.lowering.SqlPostProcessors.Hooks hooks = com.legend.lowering
                    .SqlPostProcessors.hooks(rtArg, v -> com.legend.compiler.spec
                            .ExecuteChainAssembly.letBound(v, letPrefix));
            java.util.Map<String, String> tr = hooks.tableReplace();
            com.legend.exec.PostProcessBoundary.record(tr);
            // the connection's time zone: every DateTime literal of this
            // frame's SQL spells in it (batch 86)
            com.legend.exec.PostProcessBoundary.recordTimeZone(
                    com.legend.compiler.spec.typed.ExecutionContext.reader()
                            .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                                    .letBound(v, letPrefix))
                            .read(java.util.Optional.empty(), rtArg).timeZone());
            com.legend.exec.PostProcessBoundary.recordExtractCtes(hooks.extractCtes());
            com.legend.exec.PostProcessBoundary.recordNonExecutable(hooks.nonExecutable());
            if (!tr.isEmpty()) {
                env = new ExecEnv(env.ctx(), env.runtimeFqn(), env.dialect(),
                        env.connection(),
                        env.addDriverTablePk(), env.queryLets(), tr,
                        env.instanceIds(), env.assertListener(),
                        env.replayOracle(), env.planRows());
            }''','''            // the frame's bound context — post-processors and the connection's
            // time zone (every DateTime literal of this frame's SQL spells in
            // it, batch 86) — read ONCE by the one reader, carried by the
            // environment of this frame (no static slot)
            env = env.withFrame(com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, letPrefix))
                    .read(java.util.Optional.empty(), rtArg));
            java.util.Map<String, String> tr = env.postProcessors().tableReplace();
            if (!tr.isEmpty()) {
                env = env.withTableReplace(tr);
            }''',p)
s=sub1(s,'''                .withDbTimeZone(com.legend.exec.PostProcessBoundary.timeZone());''','''                .withDbTimeZone(env.timeZone());''',p)
s=sub1(s,'''                        env.tableReplace(),
                        com.legend.exec.PostProcessBoundary.extractCtes(),
                        com.legend.exec.PostProcessBoundary.nonExecutable()),''','''                        env.tableReplace(),
                        env.postProcessors().extractCtes(),
                        env.postProcessors().nonExecutable()),''',p)
s=sub1(s,'''                .apply(es.plan(), com.legend.exec.PostProcessBoundary
                        .tableReplace());''','''                .apply(es.plan(), env.tableReplace());''',p)
assert 'PostProcessBoundary' not in s
wr(p,s)

p='core/src/main/java/com/legend/PlanAllocations.java'; s=rd(p)
s=sub1(s,'''                    es.plan(), com.legend.exec.PostProcessBoundary.tableReplace(),
                    com.legend.exec.PostProcessBoundary.extractCtes(),
                    com.legend.exec.PostProcessBoundary.nonExecutable());''','''                    es.plan(), env.tableReplace(),
                    env.postProcessors().extractCtes(),
                    env.postProcessors().nonExecutable());''',p)
assert 'PostProcessBoundary' not in s; wr(p,s)

p='core/src/main/java/com/legend/SqlTextVerdicts.java'; s=rd(p)
s=sub1(s,'''            java.util.Map<String, String> tr = com.legend.lowering.SqlPostProcessors
                    .hooks(rt, v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, letPrefix)).tableReplace();
            if (!tr.isEmpty()) {
                env = new StatementExecutor.ExecEnv(env.ctx(), env.runtimeFqn(),
                        env.dialect(), env.connection(), env.addDriverTablePk(),
                        env.queryLets(), tr, env.instanceIds(), env.assertListener(),
                        env.replayOracle(), env.planRows());''','''            env = env.withFrame(com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, letPrefix))
                    .read(java.util.Optional.empty(), rt));
            java.util.Map<String, String> tr = env.postProcessors().tableReplace();
            if (!tr.isEmpty()) {
                env = env.withTableReplace(tr);''',p)
s=sub1(s,'''            return underProducerPasses(producer, () -> rowsLegAndVerdict(
                    name, goldenF, ours, textEqual, oracle,
                    com.legend.compiler.spec.VerdictQueries.fromWrapped(
                            let0.value(), mapping),
                    null, mapping.fullPath(), letCls, false,
                    lamPrefix, specs, envF, hook, lam));''','''            return underProducerPasses(producer, envF, env2 -> rowsLegAndVerdict(
                    name, goldenF, ours, textEqual, oracle,
                    com.legend.compiler.spec.VerdictQueries.fromWrapped(
                            let0.value(), mapping),
                    null, mapping.fullPath(), letCls, false,
                    lamPrefix, specs, env2, hook, lam));''',p)
s=sub1(s,'''        return underProducerPasses(producer, () -> rowsLegAndVerdict(
                name, goldenF, ours, textEqual, oracle,
                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        query, mapping),
                null, mapping.fullPath(), rootClassFqn(lam),
                com.legend.compiler.spec.VerdictQueries.extentSubset(query), lamPrefix,
                specs, envF, hook, lam));''','''        return underProducerPasses(producer, envF, env2 -> rowsLegAndVerdict(
                name, goldenF, ours, textEqual, oracle,
                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        query, mapping),
                null, mapping.fullPath(), rootClassFqn(lam),
                com.legend.compiler.spec.VerdictQueries.extentSubset(query), lamPrefix,
                specs, env2, hook, lam));''',p)
s=sub1(s,'''     * must ours) — recorded on the boundary for the leg, restored after
     * (batch 81). */
    private static @com.legend.Nullable ExecutionResult underProducerPasses(
            TypedNativeCall producer,
            java.util.function.Supplier<@com.legend.Nullable ExecutionResult> leg) {
        boolean nonExec = com.legend.compiler.element.type.PlatformTypes
                .TO_NON_EXECUTABLE_SQL_STRING.equals(producer.callee().qualifiedName());
        if (!nonExec) {
            return leg.get();
        }
        boolean prev = com.legend.exec.PostProcessBoundary.nonExecutable();
        com.legend.exec.PostProcessBoundary.recordNonExecutable(true);
        try {
            return leg.get();
        } finally {
            com.legend.exec.PostProcessBoundary.recordNonExecutable(prev);
        }
    }''','''     * must ours) — the leg runs under an environment whose frame carries the
     * pass (batch 81; batch 120: on the bound context, no static slot). */
    private static @com.legend.Nullable ExecutionResult underProducerPasses(
            TypedNativeCall producer, StatementExecutor.ExecEnv env,
            java.util.function.Function<StatementExecutor.ExecEnv,
                    @com.legend.Nullable ExecutionResult> leg) {
        boolean nonExec = com.legend.compiler.element.type.PlatformTypes
                .TO_NON_EXECUTABLE_SQL_STRING.equals(producer.callee().qualifiedName());
        return leg.apply(nonExec
                ? env.withPostProcessors(env.postProcessors().withNonExecutable(true))
                : env);
    }''',p)
s=sub1(s,'''    private static List<SqlReplayOracle.TempTable> inListTemps(String golden,
            TypedSpec query, List<TypedSpec> letPrefix) {''','''    private static List<SqlReplayOracle.TempTable> inListTemps(String golden,
            TypedSpec query, List<TypedSpec> letPrefix,
            @com.legend.Nullable String zone) {''',p)
s=sub1(s,'''                // the engine seeds the temp in the connection's zone (batch 86)
                String zone = com.legend.exec.PostProcessBoundary.timeZone();
''','''                // the engine seeds the temp in the connection's zone (batch 86)
''',p)
s=sub1(s,'''                                ? inListTemps(golden, query != null ? query : rowsRead,
                                        letPrefix)''','''                                ? inListTemps(golden, query != null ? query : rowsRead,
                                        letPrefix, env.timeZone())''',p)
assert 'PostProcessBoundary' not in s; wr(p,s)
os.remove('core/src/main/java/com/legend/exec/PostProcessBoundary.java')
p='core/src/test/java/com/legend/JavaEvalLedgerTest.java'; s=rd(p)
s=sub1(s,'"PctRenderOption.java", "PostProcessBoundary.java",','"PctRenderOption.java",',p); wr(p,s)
print('part A patched')
