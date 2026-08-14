import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
public class Gate {
  static boolean L(String s,Dialect d){try{ElementParser.parse(s,d);return true;}catch(Throwable e){return false;}}
  static boolean E(String s){try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable e){return false;}}
  static void g(String name,String src){
    boolean pl=L(src,Dialect.LEGEND_PLATFORM), en=L(src,Dialect.LEGEND_ENGINE), or=E(src);
    String v = (en && !or) ? "GATE NOT APPLIED — leaks into ENGINE tier"
             : (!en && !or) ? "gated correctly"
             : (en && or) ? "engine accepts it too (not platform-only)"
             : "we reject, engine accepts";
    System.out.printf("  %-34s platform=%-3s engineTier=%-3s realEngine=%-3s  %s%n",
        name, pl?"OK":"no", en?"OK":"no", or?"OK":"no", v);
  }
  public static void main(String[] a){
    System.out.println("Dialect javadoc names these as LEGEND_PLATFORM-only:");
    g("#TDS literal",        "function my::f():Any[*]{ #TDS\n a\n 1\n# }");
    g("^$x(...) copy-instance","Class my::A{x:String[1];}\nfunction my::f():Any[*]{ let a=^my::A(x='1'); ^$a(x='2'); }");
    g("native function decl","native function my::n(x:String[1]):String[1];");
    g("generics <T>",       "function my::f<T>(x:T[1]):T[1]{ $x }");
    g("function-type literal","function my::f(g:{Integer[1]->Integer[1]}[1]):Integer[1]{ 1 }");
    System.out.println("\nOther pure-dialect constructs:");
    g("%latest",            "function my::f():Any[*]{ %latest }");
    g("Relation<T+R> algebra","native function my::f<T,R>(r:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    g("#>{db.tbl}# accessor","function my::f():Any[*]{ #>{my::db.tbl}# }");
    System.out.println("\nMalformed (should be rejected everywhere):");
    g("graphFetch }-> no #","Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}->serialize()) }");
  }
}
