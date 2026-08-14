import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.nio.file.*; import java.util.*; import java.util.stream.*;
public class Msg {
  static String eng(String s){ try{PureGrammarParser.newInstance().parseModel(s);return null;}catch(Throwable e){return one(e);} }
  static String lit(String s){ try{ElementParser.parse(s,Dialect.LEGEND_ENGINE);return null;}catch(Throwable e){return one(e);} }
  static String one(Throwable e){ String m=String.valueOf(e.getMessage()); return m.split("\n")[0].trim(); }
  static String strip(String m){ return m==null?null:m.replaceFirst("^\\[\\d+:\\d+\\]\\s*",""); }
  public static void main(String[] a) throws Exception {
    Path root=Path.of(System.getProperty("user.home"),"legend","legend-engine");
    List<Path> files; try(var w=Files.walk(root)){ files=w.filter(p->p.toString().endsWith(".pure"))
      .filter(p->!p.toString().contains("/.git/")&&!p.toString().contains("/target/")).sorted().collect(Collectors.toList()); }
    int same=0, diff=0, litePos=0, enginePos=0;
    Map<String,Integer> pairs=new HashMap<>(); Map<String,String> ex=new HashMap<>();
    for(Path p:files){ String s; try{s=Files.readString(p);}catch(Exception x){continue;}
      String le=lit(s), en=eng(s);
      if(le==null||en==null) continue;
      if(le.matches("^\\[\\d+:\\d+\\].*")) litePos++;
      if(en.matches("^\\[\\d+:\\d+\\].*")) enginePos++;
      String l=strip(le), e=strip(en);
      if(l.equals(e)) same++;
      else { diff++; String k=norm(l)+"  ||  "+norm(e);
             pairs.merge(k,1,Integer::sum); ex.putIfAbsent(k, root.relativize(p).toString()); }
    }
    System.out.println("shared rejections: "+(same+diff));
    System.out.println("  message text IDENTICAL after stripping position prefix: "+same);
    System.out.println("  message text GENUINELY different                      : "+diff);
    System.out.println("  lite emits a [line:col] prefix on   "+litePos+" of them");
    System.out.println("  engine emits a [line:col] prefix on "+enginePos+" of them");
    System.out.println("\n=== genuine message divergences, by pair ===");
    pairs.entrySet().stream().sorted((x,y)->y.getValue()-x.getValue()).limit(22).forEach(e->{
      String[] parts=e.getKey().split("  \\|\\|  ");
      System.out.println("  "+e.getValue()+"x");
      System.out.println("     lite:   "+parts[0]);
      System.out.println("     engine: "+(parts.length>1?parts[1]:""));
      System.out.println("     e.g.    "+ex.get(e.getKey()));
    });
  }
  static String norm(String m){ return m.replaceAll("'[^']*'","'X'").replaceAll("\\d+","N"); }
}
