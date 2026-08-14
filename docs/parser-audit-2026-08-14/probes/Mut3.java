import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.nio.file.*; import java.util.*; import java.util.stream.*;

/** Corpus-driven mutation fuzz, BOTH directions.
 *  Seeds are REAL corpus files both parsers accept, so mutants are realistic. */
public class Mut3 {
  static boolean eng(String s){ try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable e){return false;} }
  static boolean lit(String s){ try{ElementParser.parse(s,Dialect.LEGEND_ENGINE);return true;}catch(Throwable e){return false;} }
  static final String SPECIAL="#;,()[]{}|:~%@^><*.=!$&+-/\"'";
  public static void main(String[] a) throws Exception {
    Path root=Path.of(System.getProperty("user.home"),"legend","legend-engine");
    List<Path> files;
    try(var w=Files.walk(root)){ files=w.filter(p->p.toString().endsWith(".pure"))
      .filter(p->!p.toString().contains("/.git/")&&!p.toString().contains("/target/"))
      .sorted().collect(Collectors.toList()); }
    int wantSeeds=Integer.parseInt(System.getProperty("seeds","60"));
    int maxLen=Integer.parseInt(System.getProperty("maxlen","1800"));
    List<String> seeds=new ArrayList<>(); List<String> names=new ArrayList<>();
    for(Path p:files){ if(seeds.size()>=wantSeeds) break;
      String s; try{s=Files.readString(p);}catch(Exception e){continue;}
      if(s.length()>maxLen||s.isBlank()) continue;
      if(!eng(s)||!lit(s)) continue;
      seeds.add(s); names.add(root.relativize(p).toString());
    }
    System.out.println("seeds (real corpus files both parsers accept): "+seeds.size());
    Map<String,List<String>> acc=new TreeMap<>(), rej=new TreeMap<>();
    int mut=0;
    for(int si=0;si<seeds.size();si++){
      String seed=seeds.get(si), nm=names.get(si);
      Set<String> seen=new HashSet<>();
      for(int i=0;i<seed.length();i++){
        char c=seed.charAt(i); if(SPECIAL.indexOf(c)<0) continue;
        String[] ms={ seed.substring(0,i)+seed.substring(i+1), seed.substring(0,i)+c+c+seed.substring(i) };
        String[] ks={ "delete '"+c+"'", "double '"+c+"'" };
        for(int k=0;k<2;k++){
          String m=ms[k]; if(m.equals(seed)||!seen.add(m)) continue; mut++;
          boolean e=eng(m), l=lit(m);
          if(l&&!e) acc.computeIfAbsent(ks[k],x->new ArrayList<>()).add(nm);
          else if(!l&&e) rej.computeIfAbsent(ks[k],x->new ArrayList<>()).add(nm);
        }
      }
    }
    System.out.println("mutants: "+mut);
    System.out.println("\n=== ACCEPT-DRIFT families (we accept, engine rejects) ===");
    acc.entrySet().stream().sorted((x,y)->y.getValue().size()-x.getValue().size())
       .forEach(e->{System.out.println("  "+e.getValue().size()+"x  "+e.getKey());
         e.getValue().stream().distinct().limit(2).forEach(f->System.out.println("        e.g. "+f));});
    System.out.println("\n=== REJECT-DRIFT families (we reject, engine accepts) ===");
    rej.entrySet().stream().sorted((x,y)->y.getValue().size()-x.getValue().size())
       .forEach(e->{System.out.println("  "+e.getValue().size()+"x  "+e.getKey());
         e.getValue().stream().distinct().limit(2).forEach(f->System.out.println("        e.g. "+f));});
    System.out.println("\ntotals: accept-drift "+acc.values().stream().mapToInt(List::size).sum()
      +"   reject-drift "+rej.values().stream().mapToInt(List::size).sum());
  }
}
