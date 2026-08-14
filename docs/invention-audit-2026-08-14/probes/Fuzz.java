import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.util.*;
public class Fuzz {
  static boolean lite(String s, Dialect d){ try{ElementParser.parse(s,d);return true;}catch(Throwable e){return false;} }
  static boolean eng(String s){ try{PureGrammarParser.newInstance().parseModel(s);return true;}catch(Throwable e){return false;} }
  static List<String[]> drift=new ArrayList<>(), weStrict=new ArrayList<>();
  static void p(String label,String src){
    boolean le=lite(src,Dialect.LEGEND_ENGINE), or=eng(src), ll=lite(src,Dialect.LEGEND_LITE);
    if(le&&!or) drift.add(new String[]{label,"liteENGINE accepts, real engine REJECTS"});
    else if(!le&&or) weStrict.add(new String[]{label,"we reject, engine accepts"});
    else if(ll&&!or&&!le) {} // declared extension, fine
  }
  public static void main(String[] a){
    String C="Class my::P { name: String[1]; age: Integer[1]; }\n";
    String F="function my::f():Any[*]{ %s }";
    // graph fetch tree shapes
    p("gf }-> chain",           C+String.format(F,"my::P.all()->graphFetch(#{my::P{name}}->x())"));
    p("gf }# ok",               C+String.format(F,"my::P.all()->graphFetch(#{my::P{name}}#)"));
    p("gf nested }->",          C+String.format(F,"my::P.all()->graphFetch(#{my::P{name, age}}->serialize())"));
    p("gf #{ }# ->serialize",   C+String.format(F,"my::P.all()->graphFetch(#{my::P{name}}#)->serialize(#{my::P{name}}#)"));
    // TDS / relation literals
    p("#TDS literal",           String.format(F,"#TDS\n a\n 1\n#"));
    p("~[col] colspec",         C+String.format(F,"my::P.all()->project(~[n:x|$x.name])"));
    p("~col single",            C+String.format(F,"my::P.all()->project(~n:x|$x.name)"));
    // path / accessor
    p("#/P/name# path",         C+String.format(F,"my::P.all()->project([#/my::P/name#])"));
    p("#/P/name!alias#",        C+String.format(F,"my::P.all()->project([#/my::P/name!a#])"));
    p("#>{db.tbl}# accessor",   String.format(F,"#>{my::db.tbl}#"));
    // literals
    p("date %2020-01-01",       String.format(F,"%2020-01-01"));
    p("datetime with Z",        String.format(F,"%2024-01-01T10:00:00Z"));
    p("latest date %latest",    String.format(F,"%latest"));
    p("decimal 1.0d",           String.format(F,"1.0d"));
    p("float 1.0",              String.format(F,"1.0"));
    // lambdas / arrows
    p("|expr bare lambda",      String.format(F,"|1"));
    p("{|expr} braced",         String.format(F,"{|1}"));
    p("->fn() chain",           C+String.format(F,"my::P.all()->filter(p|$p.age>1)"));
    p("infix ==",               String.format(F,"1 == 1"));
    p("infix ->plus",           String.format(F,"[1,2]->plus()"));
    // enums / casts
    p("@Type cast",             C+String.format(F,"my::P.all()->cast(@my::P)"));
    p("->cast(@X) no at",       C+String.format(F,"my::P.all()->cast(my::P)"));
    // class / property syntax
    p("derived property",       "Class my::A { x: String[1]; y(){ $this.x }: String[1]; }");
    p("constraint block",       "Class my::A [ c1: $this.x->isNotEmpty() ] { x: String[1]; }");
    p("stereotype on class",    "Class <<meta::pure::profiles::temporal.businesstemporal>> my::A { x: String[1]; }");
    p("tagged value",           "Class {doc.doc='x'} my::A { x: String[1]; }");
    p("extends",                "Class my::A { x: String[1]; }\nClass my::B extends my::A { y: String[1]; }");
    // association
    p("Association",            C+"Class my::Q { z: String[1]; }\nAssociation my::AB { a: my::P[1]; b: my::Q[1]; }");
    // imports
    p("import wildcard",        "import my::*;\n"+C);
    p("import specific",        "import my::P;\n"+C);
    // relational
    p("View in Database",       "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) View v (id: t.id) )");
    p("Join",                   "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) Table u (id INT PRIMARY KEY) Join j(t.id = u.id) )");
    p("Filter",                 "###Relational\nDatabase my::db ( Table t (id INT PRIMARY KEY) Filter f(t.id = 1) )");
    p("Schema block",           "###Relational\nDatabase my::db ( Schema s ( Table t (id INT PRIMARY KEY) ) )");
    p("VARCHAR no size",        "###Relational\nDatabase my::db ( Table t (c VARCHAR) )");
    p("VARCHAR(200)",           "###Relational\nDatabase my::db ( Table t (c VARCHAR(200)) )");
    p("DECIMAL(10,2)",          "###Relational\nDatabase my::db ( Table t (c DECIMAL(10,2)) )");
    p("NUMERIC",                "###Relational\nDatabase my::db ( Table t (c NUMERIC(10,2)) )");
    p("TIMESTAMP",              "###Relational\nDatabase my::db ( Table t (c TIMESTAMP) )");
    p("no PRIMARY KEY",         "###Relational\nDatabase my::db ( Table t (c INT) )");
    // mapping
    p("Mapping classic",        "###Mapping\nMapping my::m ( my::P: Relational { name: [my::db]t.name } )");
    p("Mapping empty",          "###Mapping\nMapping my::m ( )");
    p("Mapping ~primaryKey",    "###Mapping\nMapping my::m ( my::P: Relational { ~primaryKey([my::db]t.id) name: [my::db]t.name } )");
    for(String[] d:drift) System.out.println("DRIFT   "+d[0]);
    for(String[] d:weStrict) System.out.println("STRICT  "+d[0]+"  (we reject, engine accepts)");
    System.out.println("\nprobed rows with divergence: drift="+drift.size()+"  we-stricter="+weStrict.size());
  }
}
