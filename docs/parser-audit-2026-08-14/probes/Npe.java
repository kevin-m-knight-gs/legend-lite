import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.nio.file.*; import java.util.*; import java.util.stream.*;
public class Npe {
  public static void main(String[] a) throws Exception {
    Path root=Path.of(System.getProperty("user.home"),"legend","legend-engine");
    List<Path> files; try(var w=Files.walk(root)){ files=w.filter(p->p.toString().endsWith(".pure"))
      .filter(p->!p.toString().contains("/.git/")&&!p.toString().contains("/target/")).sorted().collect(Collectors.toList()); }
    Map<String,Integer> crash=new TreeMap<>(); int total=0; List<String> liteNamed=new ArrayList<>();
    for(Path p:files){ String s; try{s=Files.readString(p);}catch(Exception x){continue;}
      String em=null, ex=null;
      try{ PureGrammarParser.newInstance().parseModel(s); }
      catch(Throwable t){ em=String.valueOf(t.getMessage()); ex=t.getClass().getSimpleName(); }
      if(em==null) continue;
      if(em.contains("An exception of type") || ex.equals("NullPointerException")){
        total++;
        String kind = em.replaceAll(".*type '([^']*)'.*","$1");
        crash.merge(kind.length()>60?ex:kind,1,Integer::sum);
        String lm=null; try{ ElementParser.parse(s,Dialect.LEGEND_ENGINE);}catch(Throwable t){ lm=String.valueOf(t.getMessage()).split("\n")[0]; }
        if(lm!=null && !lm.contains("exception of type")) liteNamed.add(root.relativize(p).toString()+"  -> lite: "+lm.replaceFirst("^\\[\\d+:\\d+\\]\\s*",""));
      }
    }
    System.out.println("REAL ENGINE internal crashes over the corpus: "+total+" files");
    crash.forEach((k,v)->System.out.println("   "+v+"x  "+k));
    System.out.println("\nof those, legend-lite returns a NAMED error instead: "+liteNamed.size());
    liteNamed.stream().map(x->x.substring(x.indexOf("-> lite: "))).collect(Collectors.groupingBy(x->x,TreeMap::new,Collectors.counting()))
      .entrySet().stream().sorted((x,y)->(int)(y.getValue()-x.getValue())).limit(8)
      .forEach(e->System.out.println("   "+e.getValue()+"x  "+e.getKey()));
  }
}
