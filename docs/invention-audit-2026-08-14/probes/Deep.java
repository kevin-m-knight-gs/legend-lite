import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
public class Deep {
  static void what(String label,String src){
    System.out.println("=== "+label);
    try{ var m=ElementParser.parse(src,Dialect.LEGEND_ENGINE);
      m.elements().forEach(e->System.out.println("   lite parsed -> "+e.getClass().getSimpleName()+" "+
        (e instanceof com.legend.model.FunctionDefinition f? f.qualifiedName():"")));
    }catch(Throwable e){ System.out.println("   lite REJECTS: "+String.valueOf(e.getMessage()).split("\n")[0]); }
    try{ PureGrammarParser.newInstance().parseModel(src); System.out.println("   engine: OK"); }
    catch(Throwable e){ String m=String.valueOf(e.getMessage()); System.out.println("   engine REJECTS: "+m.split("\n")[0].substring(0,Math.min(110,m.split("\n")[0].length()))); }
  }
  public static void main(String[] a){
    String C="Class my::P { name: String[1]; }\n";
    what("#{my::P{name}}->serialize()   (missing #)",
         C+"function my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}->serialize()) }");
    what("#{my::P{name}}#->serialize(...)  (correct)",
         C+"function my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}#)->serialize(#{my::P{name}}#) }");
    what("#TDS literal at ENGINE tier",
         "function my::f():Any[*]{ #TDS\n a\n 1\n# }");
    what("%latest at ENGINE tier",
         "function my::f():Any[*]{ %latest }");
  }
}
