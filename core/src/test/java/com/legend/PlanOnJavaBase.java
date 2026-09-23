package com.legend;

/**
 * The program {@link PlannerRunsOnJavaBaseTest} runs in a JVM limited to
 * {@code java.base}: plan a class query, print the SQL. It names nothing outside
 * core and the JDK, so it loads where only java.base does.
 */
public final class PlanOnJavaBase {

    private PlanOnJavaBase() {
    }

    public static void main(String[] args) {
        String model = """
                Class x::Firm { name: String[1]; size: Integer[1]; }
                ###Relational
                Database x::DB ( Table FIRM (ID INTEGER PRIMARY KEY, NAME VARCHAR(32), SIZE INTEGER) )
                ###Mapping
                Mapping x::M ( *x::Firm: Relational { ~mainTable [x::DB] FIRM
                    name: [x::DB] FIRM.NAME, size: [x::DB] FIRM.SIZE } )
                ###Runtime
                Runtime x::RT { mappings: [x::M]; }
                """;
        System.out.println("java.sql visible: " + ModuleLayer.boot().findModule("java.sql").isPresent());
        System.out.println(Compiler.plan(model, "x::Firm.all()->filter(f|$f.size > 10)"
                + "->project(~[n: f|$f.name, s: f|$f.size])->groupBy(~[n], ~[t: x|$x.s: y|$y->plus()])"
                + "->sort(~n->ascending())", "x::RT").sql());
    }
}
