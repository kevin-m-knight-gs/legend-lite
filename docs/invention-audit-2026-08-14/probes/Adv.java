import com.legend.parser.ElementParser;
import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;

public class Adv {
  static String tryLite(String s, Dialect d) {
    try { ElementParser.parse(s, d); return "OK"; }
    catch (Throwable e) { return "no"; }
  }
  static String tryEngine(String s) {
    try { PureGrammarParser.newInstance().parseModel(s); return "OK"; }
    catch (Throwable e) { return "no"; }
  }
  static void p(String label, String src) {
    String pl = tryLite(src, Dialect.LEGEND_PLATFORM);
    String li = tryLite(src, Dialect.LEGEND_LITE);
    String en = tryLite(src, Dialect.LEGEND_ENGINE);
    String or = tryEngine(src);
    String flag = "";
    if (li.equals("OK") && or.equals("no")) flag = li.equals("OK") && en.equals("OK") ? "  <<< LITE+lite-ENGINE accept, REAL ENGINE REJECTS" : "  << lite extension";
    if (li.equals("no") && or.equals("OK")) flag = "  << WE REJECT what engine accepts";
    System.out.printf("%-44s platform=%-3s lite=%-3s liteENG=%-3s REALENGINE=%-3s%s%n", label, pl, li, en, or, flag);
  }
  public static void main(String[] a) {
    String cls = "Class my::P { name: String[1]; }\n";
    System.out.println("--- graph-fetch tree terminator ---");
    p("#{ P {name} }#  (correct)", cls+"function my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}#) }");
    p("#{ P {name} }   (missing #)", cls+"function my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}) }");
    p("}->  instead of }#->", cls+"function my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}->x()) }");
    System.out.println("--- Database column types ---");
    p("BOOLEAN column", "###Relational\nDatabase my::db ( Table t (c BOOLEAN) )");
    p("BOOL column",    "###Relational\nDatabase my::db ( Table t (c BOOL) )");
    p("BIT column (engine spelling)", "###Relational\nDatabase my::db ( Table t (c BIT) )");
    p("ARRAY column", "###Relational\nDatabase my::db ( Table t (c ARRAY) )");
    System.out.println("--- multiplicity / punctuation ---");
    p("[1] normal", "Class my::A { x: String[1]; }");
    p("[1..1] explicit", "Class my::A { x: String[1..1]; }");
    p("trailing comma in collection", cls+"function my::f():Any[*]{ [1,2,3,] }");
    p("missing semicolon on property", "Class my::A { x: String[1] }");
    System.out.println("--- lite design extensions ---");
    p("SQLite connection", "###Connection\nRelationalDatabaseConnection my::c { store: my::db; type: SQLite; specification: LocalH2{}; auth: DefaultH2{}; }");
    p("H2 connection (engine)", "###Connection\nRelationalDatabaseConnection my::c { store: my::db; type: H2; specification: LocalH2{}; auth: DefaultH2{}; }");
    System.out.println("--- generics / function types ---");
    p("function with <T>", "function my::f<T>(x:T[1]):T[1]{ $x }");
    p("function-type param", "function my::f(g:{Integer[1]->Integer[1]}[1]):Integer[1]{ 1 }");
  }
}
