import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
public class Term {
  static boolean L(String s){try{ElementParser.parse(s,Dialect.LEGEND_ENGINE);return true;}catch(Throwable e){return false;}}
  static boolean E(String s){try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable e){return false;}}
  static int drift=0, ok=0;
  static void t(String n,String s){ boolean l=L(s),e=E(s);
    if(l&&!e){drift++;System.out.println("  DRIFT  "+n);} else ok++; }
  public static void main(String[] a){
    String C="Class my::P { name: String[1]; }\n";
    String pre=C+"function my::f():Any[*]{ my::P.all()->";
    // graph-fetch island terminator mutations
    t("gf missing close #",        pre+"graphFetch(#{my::P{name}}) }");
    t("gf missing close # +chain", pre+"graphFetch(#{my::P{name}}->serialize()) }");
    t("gf double ##",              pre+"graphFetch(#{my::P{name}}##) }");
    t("gf open ##{",               pre+"graphFetch(##{my::P{name}}#) }");
    t("gf missing open #",         pre+"graphFetch({my::P{name}}#) }");
    t("gf extra brace",            pre+"graphFetch(#{my::P{name}}}#) }");
    // path island #/.../#
    t("path missing close #",      pre+"project([#/my::P/name]) }");
    t("path missing open #",       pre+"project([/my::P/name#]) }");
    t("path double ##",            pre+"project([#/my::P/name##]) }");
    // relation accessor #>{...}#
    t("accessor missing close #",  "function my::f():Any[*]{ #>{my::db.tbl} }");
    t("accessor missing open",     "function my::f():Any[*]{ >{my::db.tbl}# }");
    // TDS island
    t("#TDS missing close #",      "function my::f():Any[*]{ #TDS\n a\n 1\n }");
    // SQL/GQL islands
    t("#SQL{...}# ok",             "function my::f():Any[*]{ #SQL{select 1}# }");
    t("#SQL missing close",        "function my::f():Any[*]{ #SQL{select 1} }");
    t("#GQL{...}# ok",             "function my::f():Any[*]{ #GQL{query{a}}# }");
    t("#GQL missing close",        "function my::f():Any[*]{ #GQL{query{a}} }");
    System.out.println("\nterminator mutations probed: "+(drift+ok)+"   drift rows: "+drift);
  }
}
