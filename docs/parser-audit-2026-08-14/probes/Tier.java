import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.nio.file.*; import java.util.*; import java.util.stream.*;
/** Where do the THREE lite tiers disagree with each other and with the engine,
 *  over the real corpus? Exposes constructs only PLATFORM accepts (legend-pure
 *  dialect) vs constructs LITE adds over ENGINE (declared extensions). */
public class Tier {
  static boolean e(String s){try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable t){return false;}}
  static boolean l(String s,Dialect d){try{ElementParser.parse(s,d);return true;}catch(Throwable t){return false;}}
  public static void main(String[] a) throws Exception {
    Path root=Path.of(System.getProperty("user.home"),"legend","legend-engine");
    List<Path> files; try(var w=Files.walk(root)){ files=w.filter(p->p.toString().endsWith(".pure"))
      .filter(p->!p.toString().contains("/.git/")&&!p.toString().contains("/target/")).sorted().collect(Collectors.toList()); }
    int P=0,L=0,E=0,O=0,n=0;
    List<String> platformOnly=new ArrayList<>(), liteOnly=new ArrayList<>();
    for(Path p:files){ String s; try{s=Files.readString(p);}catch(Exception x){continue;} n++;
      boolean bp=l(s,Dialect.LEGEND_PLATFORM), bl=l(s,Dialect.LEGEND_LITE), be=l(s,Dialect.LEGEND_ENGINE), bo=e(s);
      if(bp)P++; if(bl)L++; if(be)E++; if(bo)O++;
      if(bp&&!bl) platformOnly.add(root.relativize(p).toString());
      if(bl&&!be) liteOnly.add(root.relativize(p).toString());
    }
    System.out.println("corpus .pure files: "+n);
    System.out.printf("  LEGEND_PLATFORM accepts %d%n  LEGEND_LITE accepts     %d%n  LEGEND_ENGINE accepts   %d%n  REAL ENGINE accepts     %d%n",P,L,E,O);
    System.out.println("\nPLATFORM-only (legend-pure dialect lite reads but its product tier refuses): "+platformOnly.size());
    platformOnly.stream().limit(10).forEach(x->System.out.println("   "+x));
    System.out.println("\nLITE-only (declared extensions over the drop-in tier): "+liteOnly.size());
    liteOnly.stream().limit(10).forEach(x->System.out.println("   "+x));
  }
}
