import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.util.*;

/** Differential mutation fuzzer: valid Pure -> systematic mutants ->
 *  flag every mutant the REAL engine rejects but LEGEND_ENGINE accepts. */
public class Mutate {
  static boolean lite(String s){ try{ElementParser.parse(s,Dialect.LEGEND_ENGINE);return true;}catch(Throwable e){return false;} }
  static boolean eng(String s){ try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable e){return false;} }

  static final String[] SEEDS = {
    "Class my::P { name: String[1]; age: Integer[1]; }",
    "Class my::A extends my::P { z: String[1]; }\nClass my::P { name: String[1]; }",
    "Class my::A [ c1: $this.x->isNotEmpty() ] { x: String[1]; }",
    "Class <<meta::pure::profiles::temporal.businesstemporal>> my::A { x: String[1]; }",
    "Class {doc.doc='d'} my::A { x: String[1]; }",
    "Class my::A { x: String[1]; y(){ $this.x }: String[1]; }",
    "Enum my::E { A, B }",
    "Profile my::Pr { stereotypes: [s1]; tags: [t1]; }",
    "Association my::AB { a: my::P[1]; b: my::Q[1]; }\nClass my::P{n:String[1];}\nClass my::Q{m:String[1];}",
    "import my::*;\nClass my::A { x: String[1]; }",
    "function my::f():Integer[1]{ 1 }",
    "function my::f(a:String[1]):String[1]{ $a }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->filter(p|$p.name=='x') }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->project([#/my::P/name#]) }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->project([#/my::P/name!a#]) }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->graphFetch(#{my::P{name}}#)->serialize(#{my::P{name}}#) }",
    "function my::f():Any[*]{ [1,2,3] }",
    "function my::f():Any[*]{ %2020-01-01 }",
    "function my::f():Any[*]{ %2020-01-01T10:00:00 }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ my::P.all()->cast(@my::P) }",
    "Class my::P{name:String[1];}\nfunction my::f():Any[*]{ ^my::P(name='x') }",
    "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY, c VARCHAR(200)) )",
    "###Relational\nDatabase my::db ( Schema s ( Table t (id INT PRIMARY KEY) ) )",
    "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) Table u (id INT PRIMARY KEY) Join j(t.id = u.id) )",
    "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) View v (id: t.id) )",
    "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) Filter f(t.id = 1) )",
    "###Mapping\nMapping my::m ( my::P: Relational { name: [my::db]t.name } )",
    "###Mapping\nMapping my::m ( my::P: Relational { ~primaryKey([my::db]t.id) name: [my::db]t.name } )",
    "###Mapping\nMapping my::m ( *my::P: Relational { name: [my::db]t.name } )",
    "###Connection\nRelationalDatabaseConnection my::c { store: my::db; type: H2; specification: LocalH2{}; auth: DefaultH2{}; }",
    "###Runtime\nRuntime my::r { mappings: [my::m]; connections: [ my::db: [ c: my::c ] ]; }",
    "###Service\nService my::s { pattern: '/p'; documentation: 'd'; execution: Single { query: |1; mapping: my::m; runtime: my::r; } }",
  };
  static final String SPECIAL = "#;,()[]{}|:~%@^><*.=!";

  public static void main(String[] a){
    List<String> drift=new ArrayList<>();
    int mutants=0, engineRejects=0, seedsOk=0;
    for (String seed : SEEDS) {
      if (!eng(seed)) { System.out.println("SEED NOT ENGINE-VALID, skipped: "+seed.split("\n")[0]); continue; }
      seedsOk++;
      Set<String> seen = new HashSet<>();
      for (int i=0;i<seed.length();i++){
        char c = seed.charAt(i);
        if (SPECIAL.indexOf(c) < 0) continue;
        List<String> muts = List.of(
            seed.substring(0,i)+seed.substring(i+1),        // delete
            seed.substring(0,i)+c+c+seed.substring(i));     // duplicate
        String[] kinds = {"delete '"+c+"'", "double '"+c+"'"};
        for (int k=0;k<muts.size();k++){
          String m = muts.get(k);
          if (m.equals(seed) || !seen.add(m)) continue;
          mutants++;
          if (eng(m)) continue;              // engine accepts -> not drift
          engineRejects++;
          if (lite(m)) {
            drift.add(kinds[k]+" @"+i+"  in: "+seed.split("\n")[0].substring(0,Math.min(52,seed.split("\n")[0].length()))
                      +"\n        -> "+m.replace("\n"," / ").substring(0,Math.min(120,m.replace("\n"," / ").length())));
          }
        }
      }
    }
    System.out.println("\nseeds engine-valid: "+seedsOk+"/"+SEEDS.length
        +"   mutants: "+mutants+"   engine-rejected: "+engineRejects+"   DRIFT: "+drift.size());
    System.out.println("\n=== DRIFT ROWS (lite LEGEND_ENGINE accepts, real engine rejects) ===");
    for (String d: drift) System.out.println("  "+d);
  }
}
