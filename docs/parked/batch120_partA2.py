import re
def rd(p): return open(p).read()
def wr(p,s): open(p,'w').write(s)
def sub1(s,old,new,p):
    assert s.count(old)==1, (p, old[:70], s.count(old)); return s.replace(old,new)
EC='com.legend.compiler.spec.typed.ExecutionContext'
# 1. VerdictQueries.fromWrapped with a base context
p='core/src/main/java/com/legend/compiler/spec/VerdictQueries.java'; s=rd(p)
s=sub1(s,'''    public static TypedSpec fromWrapped(TypedSpec query,
            com.legend.compiler.spec.typed.TypedPackageableRef mapping) {
        return new com.legend.compiler.spec.typed.TypedFrom(query,
                java.util.Optional.of(mapping), java.util.Optional.empty(),
                query.info());
    }''','''    public static TypedSpec fromWrapped(TypedSpec query,
            com.legend.compiler.spec.typed.TypedPackageableRef mapping) {
        return fromWrapped(query, mapping,
                com.legend.compiler.spec.typed.ExecutionContext.NONE);
    }

    /** The verdict's read wrapped in the FRAME's own bound context (the
     * producer's post-processors, time zone, options) under the mapping. */
    public static TypedSpec fromWrapped(TypedSpec query,
            com.legend.compiler.spec.typed.TypedPackageableRef mapping,
            com.legend.compiler.spec.typed.ExecutionContext base) {
        return new com.legend.compiler.spec.typed.TypedFrom(query,
                base.withMapping(java.util.Optional.of(mapping)), query.info());
    }''',p); wr(p,s)

# 2. SqlTextVerdicts
p='core/src/main/java/com/legend/SqlTextVerdicts.java'; s=rd(p)
s=sub1(s,'''            String boundDb = com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .read(java.util.Optional.empty(), rt).databaseType();
            if (boundDb == null) {
                // a DRIVER''','''            com.legend.compiler.spec.typed.ExecutionContext frameCtx =
                    com.legend.compiler.spec.typed.ExecutionContext.reader()
                            .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                                    .letBound(v, letPrefix))
                            .read(java.util.Optional.empty(), rt);
            String boundDb = frameCtx.databaseType();
            if (boundDb == null) {
                // a DRIVER''',p)
s=sub1(s,'''            env = env.withFrame(com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, letPrefix))
                    .read(java.util.Optional.empty(), rt));
            java.util.Map<String, String> tr = env.postProcessors().tableReplace();''','''            env = env.withFrame(frameCtx);
            java.util.Map<String, String> tr = env.postProcessors().tableReplace();''',p)
s=sub1(s,'''        final StatementExecutor.ExecEnv envF = env;
        if (!stmtLets.isEmpty() && !isPopulationGolden(golden)) {''','''        final StatementExecutor.ExecEnv envF = env;
        // the leg's from carries the producer's bound context (post-processors,
        // time zone); a toNonExecutableSQLString producer's query runs under
        // the nonExecutable pass (its golden reads zero rows by construction,
        // and so must ours — batch 81; on the from since batch 120)
        com.legend.compiler.spec.typed.ExecutionContext legCtx = legContext(producer, envF);
        if (!stmtLets.isEmpty() && !isPopulationGolden(golden)) {''',p)
s=sub1(s,'''            return underProducerPasses(producer, envF, env2 -> rowsLegAndVerdict(
                    name, goldenF, ours, textEqual, oracle,
                    com.legend.compiler.spec.VerdictQueries.fromWrapped(
                            let0.value(), mapping),
                    null, mapping.fullPath(), letCls, false,
                    lamPrefix, specs, env2, hook, lam));''','''            return rowsLegAndVerdict(
                    name, goldenF, ours, textEqual, oracle,
                    com.legend.compiler.spec.VerdictQueries.fromWrapped(
                            let0.value(), mapping, legCtx),
                    null, mapping.fullPath(), letCls, false,
                    lamPrefix, specs, envF, hook, lam);''',p)
s=sub1(s,'''        return underProducerPasses(producer, envF, env2 -> rowsLegAndVerdict(
                name, goldenF, ours, textEqual, oracle,
                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        query, mapping),
                null, mapping.fullPath(), rootClassFqn(lam),
                com.legend.compiler.spec.VerdictQueries.extentSubset(query), lamPrefix,
                specs, env2, hook, lam));''','''        return rowsLegAndVerdict(
                name, goldenF, ours, textEqual, oracle,
                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        query, mapping, legCtx),
                null, mapping.fullPath(), rootClassFqn(lam),
                com.legend.compiler.spec.VerdictQueries.extentSubset(query), lamPrefix,
                specs, envF, hook, lam);''',p)
s=sub1(s,'''    /** The rows leg under the PRODUCER's own post-processing: a
     * toNonExecutableSQLString producer's query runs with the nonExecutable
     * pass installed (its golden reads zero rows by construction, and so
     * must ours) — the leg runs under an environment whose frame carries the
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
    }''','''    /** The bound context a producer's rows leg runs under: the frame the
     * arm read off the producer's runtime (none when the producer names a
     * DatabaseType, which carries no post-processors), with the
     * nonExecutable pass installed for a toNonExecutableSQLString producer. */
    private static com.legend.compiler.spec.typed.ExecutionContext legContext(
            TypedNativeCall producer, StatementExecutor.ExecEnv env) {
        com.legend.compiler.spec.typed.ExecutionContext base = env.frame() == null
                ? com.legend.compiler.spec.typed.ExecutionContext.NONE : env.frame();
        boolean nonExec = com.legend.compiler.element.type.PlatformTypes
                .TO_NON_EXECUTABLE_SQL_STRING.equals(producer.callee().qualifiedName());
        return nonExec
                ? base.withPostProcessors(base.postProcessors().withNonExecutable(true))
                : base;
    }''',p)
# FrameFacts carries the frame's context
s=sub1(s,'''    private record FrameFacts(@com.legend.Nullable String mapping,
            @com.legend.Nullable String cls, boolean extentSubset,
            @com.legend.Nullable TypedSpec query,
            @com.legend.Nullable TypedPackageableRef mappingRef) {
    }''','''    private record FrameFacts(@com.legend.Nullable String mapping,
            @com.legend.Nullable String cls, boolean extentSubset,
            @com.legend.Nullable TypedSpec query,
            @com.legend.Nullable TypedPackageableRef mappingRef,
            com.legend.compiler.spec.typed.ExecutionContext context) {
        FrameFacts(@com.legend.Nullable String mapping, @com.legend.Nullable String cls,
                boolean extentSubset, @com.legend.Nullable TypedSpec query,
                @com.legend.Nullable TypedPackageableRef mappingRef) {
            this(mapping, cls, extentSubset, query, mappingRef,
                    com.legend.compiler.spec.typed.ExecutionContext.NONE);
        }
    }''',p)
s=sub1(s,'''    private static FrameFacts frameMappingAndClass(TypedSpec resultArg,
            List<TypedSpec> letPrefix,
            AssertVerdicts.@com.legend.Nullable SpliceHook hook) {''','''    private static FrameFacts frameMappingAndClass(TypedSpec resultArg,
            List<TypedSpec> letPrefix,
            AssertVerdicts.@com.legend.Nullable SpliceHook hook, SpecCompiler specs) {''',p)
s=sub1(s,'''            return new FrameFacts(mapping, cls, subset, lamArg,
                    ec.args().get(1) instanceof TypedPackageableRef mr ? mr : null);''','''            return new FrameFacts(mapping, cls, subset, lamArg,
                    ec.args().get(1) instanceof TypedPackageableRef mr ? mr : null,
                    ec.args().size() >= 3
                            ? StatementExecutor.boundContext(ec.args().get(2), letPrefix, specs)
                            : com.legend.compiler.spec.typed.ExecutionContext.NONE);''',p)
assert s.count('frameMappingAndClass(resultArg, letPrefix, hook)')==3
s=s.replace('frameMappingAndClass(resultArg, letPrefix, hook)','frameMappingAndClass(resultArg, letPrefix, hook, specs)')
n=len(re.findall(r'fromWrapped\(\s*[^;]*?, fm\.mappingRef\(\)\)', s, re.S)); assert n==3, n
s=re.sub(r'(fromWrapped\(\s*[^;]*?), fm\.mappingRef\(\)\)', r'\1, fm.mappingRef(), fm.context())', s, flags=re.S)
s=sub1(s,'''        List<TypedSpec> bound = new java.util.ArrayList<>(letPrefix);
        bound.addAll(bindings.lets());''','''        List<TypedSpec> bound = new java.util.ArrayList<>(letPrefix);
        bound.addAll(bindings.lets());
        // the plan producer's bound context rides the verdict's from
        com.legend.compiler.spec.typed.ExecutionContext planCtx = producer.args().size() >= 3
                ? StatementExecutor.boundContext(producer.args().get(2), letPrefix, specs)
                : com.legend.compiler.spec.typed.ExecutionContext.NONE;''',p)
s=sub1(s,'''                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        lam.body().get(lam.body().size() - 1), mapping),''','''                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        lam.body().get(lam.body().size() - 1), mapping, planCtx),''',p)
wr(p,s)

# 3. StatementExecutor
p='core/src/main/java/com/legend/StatementExecutor.java'; s=rd(p)
s=sub1(s,'''    static com.legend.compiler.spec.typed.ExecutionContext boundContext(
            TypedSpec runtimeArg, SpecCompiler specs) {
        TypedSpec value = new com.legend.compiler.spec.UserCallInliner(specs)
                .inlineBody(java.util.List.of(runtimeArg)).get(0);
        return com.legend.compiler.spec.typed.ExecutionContext.reader()
                .read(java.util.Optional.empty(), value);
    }''','''    static com.legend.compiler.spec.typed.ExecutionContext boundContext(
            TypedSpec runtimeArg, SpecCompiler specs) {
        TypedSpec value = new com.legend.compiler.spec.UserCallInliner(specs)
                .inlineBody(java.util.List.of(runtimeArg)).get(0);
        return com.legend.compiler.spec.typed.ExecutionContext.reader()
                .read(java.util.Optional.empty(), value);
    }

    /** The same read under a statement: the argument and the values it
     * names (a let-bound runtime, a let-bound hook operand) chase the
     * statement's preceding lets. */
    static com.legend.compiler.spec.typed.ExecutionContext boundContext(
            TypedSpec runtimeArg, java.util.List<TypedSpec> letPrefix, SpecCompiler specs) {
        TypedSpec value = new com.legend.compiler.spec.UserCallInliner(specs)
                .inlineBody(java.util.List.of(com.legend.compiler.spec.ExecuteChainAssembly
                        .letBound(runtimeArg, letPrefix))).get(0);
        return com.legend.compiler.spec.typed.ExecutionContext.reader()
                .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly.letBound(v, letPrefix))
                .read(java.util.Optional.empty(), value);
    }''',p)
s=sub1(s,'''        TypedSpec root = body.get(body.size() - 1);
        // from() is context-only, but its info is the PRE-RESOLUTION''','''        TypedSpec root = body.get(body.size() - 1);
        // the from being executed IS the frame: its bound context carries the
        // post-processors, the connection's time zone and the options this
        // execution runs under (batch 120 — no static slot, no env guess)
        if (root instanceof com.legend.compiler.spec.typed.TypedFrom outer) {
            env = env.withFrame(outer.context());
        }
        // from() is context-only, but its info is the PRE-RESOLUTION''',p)
s=sub1(s,'''            String boundDb = com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .read(java.util.Optional.empty(), rt).databaseType();''','''            String boundDb = com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, letPrefix))
                    .read(java.util.Optional.empty(), rt).databaseType();''',p)
s=sub1(s,'''            String dbBound = com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .read(java.util.Optional.empty(), dbArg).databaseType();''','''            String dbBound = com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, letPrefix))
                    .read(java.util.Optional.empty(), dbArg).databaseType();''',p)
s=sub1(s,'''        ExecEnv withPostProcessors(com.legend.compiler.spec.typed.ExecutionContext.PostProcessors pp) {
            return withFrame((frame == null ? com.legend.compiler.spec.typed.ExecutionContext.NONE
                    : frame).withPostProcessors(pp));
        }
''','',p)
wr(p,s)

# 4. Compiler.programFacts reads each statement under its preceding lets
p='core/src/main/java/com/legend/Compiler.java'; s=rd(p)
s=sub1(s,'''        var reader = com.legend.compiler.spec.typed.ExecutionContext.reader();
        for (TypedSpec s : body) {
            effects |= StatementExecutor.containsEffect(s, specs, memo)
                    || containsTdgGenerator(s);
            // the ONE reader of runtime shapes: inline CSV test data anywhere
            // in the statement (a from(), an execute's runtime argument, a
            // let-bound connection copy) is a bound-context fact
            seeds |= !reader.read(java.util.Optional.empty(), s).csvSetups().isEmpty();''','''        for (int i = 0; i < body.size(); i++) {
            TypedSpec s = body.get(i);
            java.util.List<TypedSpec> preceding = body.subList(0, i);
            effects |= StatementExecutor.containsEffect(s, specs, memo)
                    || containsTdgGenerator(s);
            // the ONE reader of runtime shapes: inline CSV test data anywhere
            // in the statement (a from(), an execute's runtime argument, a
            // let-bound connection copy) is a bound-context fact; values the
            // statement names chase its preceding lets
            seeds |= !com.legend.compiler.spec.typed.ExecutionContext.reader()
                    .bind(v -> com.legend.compiler.spec.ExecuteChainAssembly
                            .letBound(v, preceding))
                    .read(java.util.Optional.empty(), s).csvSetups().isEmpty();''',p)
wr(p,s)

# 5. RoutingContext binds the resolver's let chase
p='core/src/main/java/com/legend/resolver/RoutingContext.java'; s=rd(p)
s=sub1(s,'''        var bound = com.legend.compiler.spec.typed.ExecutionContext.reader()
                .read(java.util.Optional.of(mr), rt)''','''        var bound = com.legend.compiler.spec.typed.ExecutionContext.reader()
                .bind(bind)
                .read(java.util.Optional.of(mr), rt)''',p)
wr(p,s)
print('part A2 patched')
