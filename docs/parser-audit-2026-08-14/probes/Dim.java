import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*; import java.util.*; import java.util.stream.*;

/** Parser disagreement audit, all dimensions, driven off the REAL corpus.
 *  D1 accept-drift   we accept / engine rejects
 *  D2 reject-drift   we reject / engine accepts
 *  D3 wire-drift     both accept, protocol JSON differs
 *  D4 msg-drift      both reject, message differs
 */
public class Dim {
  static final ObjectMapper M = new ObjectMapper();
  record R(boolean ok, String err, String json) {}
  static R engine(String s){
    try { var m = PureGrammarParser.newInstance().parseModel(s);
          return new R(true,null,M.writeValueAsString(m)); }
    catch (Throwable e){ return new R(false, one(e), null); }
  }
  static R lite(String s, Dialect d){
    try { ElementParser.parse(s,d); return new R(true,null,null); }
    catch (Throwable e){ return new R(false, one(e), null); }
  }
  static String one(Throwable e){ String m=String.valueOf(e.getMessage()); return m.split("\n")[0]; }

  public static void main(String[] a) throws Exception {
    Path root = Path.of(System.getProperty("user.home"),"legend","legend-engine");
    List<Path> files;
    try (var w = Files.walk(root)) {
      files = w.filter(p->p.toString().endsWith(".pure"))
               .filter(p->!p.toString().contains("/.git/")&&!p.toString().contains("/target/"))
               .sorted().collect(Collectors.toList());
    }
    int lim = Integer.parseInt(System.getProperty("limit","100000"));
    int n=0, bothOk=0, bothNo=0;
    List<String> d1=new ArrayList<>(), d2=new ArrayList<>(), d4=new ArrayList<>();
    Map<String,Integer> d1k=new TreeMap<>(), d2k=new TreeMap<>();
    for (Path p: files) {
      if (n>=lim) break;
      String src;
      try { src = Files.readString(p); } catch (Exception e){ continue; }
      n++;
      R e = engine(src), l = lite(src, Dialect.LEGEND_ENGINE);
      String rel = root.relativize(p).toString();
      if (l.ok() && !e.ok()) { d1.add(rel+"  || engine: "+e.err()); d1k.merge(key(e.err()),1,Integer::sum); }
      else if (!l.ok() && e.ok()) { d2.add(rel+"  || lite: "+l.err()); d2k.merge(key(l.err()),1,Integer::sum); }
      else if (l.ok()&&e.ok()) bothOk++;
      else { bothNo++; if (!sameish(l.err(),e.err())) d4.add(rel+"\n      lite:   "+l.err()+"\n      engine: "+e.err()); }
    }
    System.out.println("corpus files parsed: "+n+"   both-accept: "+bothOk+"   both-reject: "+bothNo);
    System.out.println("\n=== D1 ACCEPT-DRIFT (lite LEGEND_ENGINE accepts, real engine rejects): "+d1.size());
    d1k.entrySet().stream().sorted((x,y)->y.getValue()-x.getValue()).limit(25)
       .forEach(x->System.out.println("   "+x.getValue()+"x  engine says: "+x.getKey()));
    d1.stream().limit(12).forEach(x->System.out.println("     "+x));
    System.out.println("\n=== D2 REJECT-DRIFT (lite rejects, real engine accepts): "+d2.size());
    d2k.entrySet().stream().sorted((x,y)->y.getValue()-x.getValue()).limit(25)
       .forEach(x->System.out.println("   "+x.getValue()+"x  lite says: "+x.getKey()));
    d2.stream().limit(12).forEach(x->System.out.println("     "+x));
    System.out.println("\n=== D4 MESSAGE DIVERGENCE on shared rejects: "+d4.size()+" (of "+bothNo+")");
    d4.stream().limit(8).forEach(x->System.out.println("   "+x));
  }
  static String key(String m){ if(m==null) return "null";
    m=m.replaceAll("\\[\\d+:\\d+\\]","[L:C]").replaceAll("'[^']*'","'X'").replaceAll("\\d+","N");
    return m.length()>105? m.substring(0,105): m; }
  static boolean sameish(String a,String b){ return key(a).equals(key(b)); }
}
