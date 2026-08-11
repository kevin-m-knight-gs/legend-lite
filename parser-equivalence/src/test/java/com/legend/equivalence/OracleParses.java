package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ONE oracle parse per corpus source, shared by both parity layers. The
 * element ledger ({@code ParserEquivalence.referenceElements}) and the
 * whole-document sweep ({@code PmcdEquivalenceTest}) iterate the same
 * {@code Corpus.all()} and each used to run the full legend-engine parser
 * over every source independently — the doubling that took gate 8 from
 * ~65s to ~125s (docs/GATES.md budget table).
 *
 * <p>Entries release after their SECOND acquisition (the two sweeps run
 * class-sequentially, so the full corpus is held across the crossover —
 * ~hundreds of MB against the default heap, measured fine). A scoped run
 * that executes only one sweep leaves entries at one acquisition; same
 * retention, same verdicts. Rejections are memoized and rethrown so the
 * second sweep's oracle-reject rows never re-parse either.
 */
final class OracleParses {

    private OracleParses() {
    }

    private static final PureGrammarParser ORACLE = PureGrammarParser.newInstance();

    private static final class Entry {
        final PureModelContextData pmcd;   // exactly one of the two is set
        final Throwable rejection;
        int acquisitions;

        Entry(PureModelContextData pmcd, Throwable rejection) {
            this.pmcd = pmcd;
            this.rejection = rejection;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /**
     * The oracle's parse of {@code src} — computed on first call, replayed
     * on the second (then released). A memoized rejection rethrows the
     * ORIGINAL throwable, so both sweeps classify the row identically.
     */
    static PureModelContextData acquire(Corpus.Source src) throws Throwable {
        Entry e = CACHE.computeIfAbsent(src.id(), id -> {
            try {
                return new Entry(ORACLE.parseModel(src.text()), null);
            } catch (Throwable t) {
                return new Entry(null, t);
            }
        });
        synchronized (e) {
            e.acquisitions++;
            if (e.acquisitions >= 2) {
                CACHE.remove(src.id());
            }
        }
        if (e.rejection != null) {
            throw e.rejection;
        }
        return e.pmcd;
    }
}
