import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.util.*;
public class Mutate2 {
  static boolean lite(String s){ try{ElementParser.parse(s,Dialect.LEGEND_ENGINE);return true;}catch(Throwable e){return false;} }
  static boolean eng(String s){ try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable e){return false;} }
  static final String[] SEEDS = {
    "Class my::P { name: String[1]; age: Integer[1]; }",
    "Class my::A [ c1: $this.x->isNotEmpty() ] { x: String[1]; }",
    "Enum my::E { A, B }",
    "Profile my::Pr { stereotypes: [s1]; tags: [t1]; }",
    "Association my::AB { a: my::P[1]; b: my::Q[1]; }\nClass my::P{n:String[1];}\nClass my::Q{m:String[1];}",
    "function my::f(a:String[1]):String[1]{ $a }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->filter(p|$p.name=='x') }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->project([#/my::P/name#]) }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}#)->serialize(#{my::P{name}}#) }",
    "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY, c VARCHAR(200)) )",
    "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) Table u (id INT PRIMARY KEY) Join j(t.id = u.id) )",
    "###Mapping\nMapping my::m ( my::P: Relational { name: [my::db]t.name } )",
    "###Connection\nRelationalDatabaseConnection my::c { store: my::db; type: H2; specification: LocalH2{}; auth: DefaultH2{}; }",
    "###Runtime\nRuntime my::r { mappings: [my::m]; connections: [ my::db: [ c: my::c ] ]; }",
    "###Service\nService my::s { pattern: '/p'; documentation: 'd'; execution: Single { query: |1; mapping: my::m; runtime: my::r; } }",
  };
  // junk appended after a complete element / at end of file
  static final String[] TAIL = { ")", "}", "]", ";", ",", ")))", "}}}", "garbage", "###Nope", "|", "->", "#", "@", "~", "*" };
  static Map<String,List<String>> fam = new LinkedHashMap<>();
  static void rec(String family,String detail){ fam.computeIfAbsent(family,k->new ArrayList<>()).add(detail); }
  public static void main(String[] a){
    int mut=0, engRej=0, drift=0;
    for (String seed: SEEDS) {
      if(!eng(seed)) continue;
      // (1) TRAILING JUNK
      for (String t: TAIL){
        String m = seed + " " + t;
        mut++; if(eng(m)) continue; engRej++;
        if(lite(m)){ drift++; rec("trailing-junk '"+t+"'", seed.split("\n")[0].substring(0,Math.min(46,seed.split("\n")[0].length()))); }
      }
      // (2) TOKEN DELETION (whitespace-delimited)
      String[] toks = seed.split("(?<=\\s)|(?=\\s)");
      for (int i=0;i<toks.length;i++){
        if (toks[i].isBlank()) continue;
        StringBuilder sb=new StringBuilder();
        for(int j=0;j<toks.length;j++) if(j!=i) sb.append(toks[j]);
        String m=sb.toString();
        mut++; if(m.equals(seed)||eng(m)) continue; engRej++;
        if(lite(m)){ drift++; rec("token-delete '"+toks[i].trim()+"'", seed.split("\n")[0].substring(0,Math.min(46,seed.split("\n")[0].length()))); }
      }
      // (3) KEYWORD CASE
      for (String kw: new String[]{"Class","function","Enum","Association","Table","Join","Mapping","Runtime","Service","Database"}){
        if(!seed.contains(kw)) continue;
        String m=seed.replace(kw, kw.toLowerCase());
        mut++; if(eng(m)) continue; engRej++;
        if(lite(m)){ drift++; rec("keyword-lowercase '"+kw+"'", seed.split("\n")[0].substring(0,Math.min(46,seed.split("\n")[0].length()))); }
      }
    }
    System.out.println("mutants: "+mut+"  engine-rejected: "+engRej+"  DRIFT: "+drift+"\n");
    System.out.println("=== DRIFT FAMILIES ===");
    fam.entrySet().stream().sorted((x,y)->y.getValue().size()-x.getValue().size()).forEach(e->{
      System.out.println("  "+e.getValue().size()+"x  "+e.getKey());
      e.getValue().stream().distinct().limit(3).forEach(s->System.out.println("         in: "+s));
    });
  }
}
