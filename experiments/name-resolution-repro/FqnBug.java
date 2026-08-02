import com.legend.Compiler;
import java.util.*;

public class FqnBug {
    public static void main(String[] a) throws Exception {
        // File 1: the ONLY myDB in the model lives in pkg::B
        String f1 = "Database pkg::B::myDB ( Table T (ID INTEGER, NAME VARCHAR(50)) )";

        // File 2: imports pkg::A::* — which contains NOTHING — and references [myDB].
        // Correct Pure semantics: unresolvable. pkg::B was never imported here.
        String f2 = "import pkg::A::*;\n"
                  + "Class model::Person { name: String[1]; }\n"
                  + "Mapping my::M ( *model::Person: Relational "
                  + "{ ~mainTable [myDB] T name: T.NAME } )";

        var ctx = Compiler.compileModel(List.of(
                new Compiler.ModelSource("f1.pure", f1),
                new Compiler.ModelSource("f2.pure", f2)));

        System.out.println("findDatabase(\"myDB\")  -> " +
                ctx.findDatabase("myDB").map(d -> d.qualifiedName()).orElse("<unresolved>"));
        System.out.println("findDatabase(\"pkg::A::myDB\") -> " +
                ctx.findDatabase("pkg::A::myDB").map(d -> d.qualifiedName()).orElse("<unresolved>"));
        System.out.println();
        System.out.println("f2 imports pkg::A::* and pkg::A defines NOTHING.");
        System.out.println("Pure says [myDB] is unresolvable here. If line 1 bound pkg::B::myDB,");
        System.out.println("legend-lite bound a store the file never imported.");
    }
}
