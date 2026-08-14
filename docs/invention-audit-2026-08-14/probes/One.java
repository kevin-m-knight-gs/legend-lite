import com.legend.parser.ElementParser; import com.legend.parser.Dialect;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import java.nio.file.*;
public class One {
  public static void main(String[] a) throws Exception {
    String s = Files.readString(Path.of(a[0]));
    try { PureGrammarParser.newInstance().parseModel(s); System.out.println("REAL ENGINE: accepts the file as-is"); }
    catch (Throwable e){ String m=String.valueOf(e.getMessage()); System.out.println("REAL ENGINE: REJECTS -> "+m.split("\n")[0]); }
    for (Dialect d : Dialect.values()) {
      try { ElementParser.parse(s,d); System.out.println("lite "+d+": OK"); }
      catch (Throwable e){ System.out.println("lite "+d+": "+String.valueOf(e.getMessage()).split("\n")[0]); }
    }
  }
}
