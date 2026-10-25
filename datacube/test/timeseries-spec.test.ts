// A TIMESERIES TILE'S SPEC (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §3, §4; src/timeseries-spec.ts): the
// expression language read and printed back to the same tree, what it refuses and where, the saved spec's strict reader,
// the problems against the sources' columns, and a first tile of a grid.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  aliasFor, columnsOf, defaultTimeseries, exprText, inlined, nextSeriesId, parseExpr, refsOf, sourcesOf, timeseriesProblems,
  tsProblem, TsSyntaxError, unitOf, TS_FUNCTIONS, type TimeseriesSpec, type TsColumn, type TsExpr,
} from '../src/timeseries-spec.ts';

const ROUND_TRIPS = [
  'close',
  'close[AAPL]',
  'close[venue=NYSE, AAPL]',
  'volume[AAPL].sum',
  'px:close[SPX]',
  'rates:yield[10Y]',
  '`total pnl`',
  'close["BRK.B"]',
  'close["10"]',
  '$A',
  'returns(close[AAPL])',
  'returns(close[AAPL], 5)',
  'returns(close[AAPL], 1, log)',
  'volatility($A, 63)',
  'volatility($A, 63, log)',
  'zscores($A, 252)',
  'correlation(px:close[SPX], rates:yield[10Y], 126)',
  'bollinger_bands(close[AAPL], 20, 2.5)',
  'close[AAPL] / close[MSFT]',
  '($A + $B) / 2',
  '$A - ($B - $C)',
  '$A / ($B * 2)',
  '100 * returns($A)',
  '-1 * $A',
  'rebase(close[AAPL])',
  'rebase(close[AAPL], 1)',
  'floor(close[AAPL], -5)',
];

describe('the expression language', () => {
  for (const text of ROUND_TRIPS) {
    it(`reads and prints back ${text}`, () => {
      const tree = parseExpr(text);
      assert.equal(exprText(tree), text);
      assert.deepEqual(parseExpr(exprText(tree)), tree);
    });
  }

  it('a parameter left at its default is left off; one told by name is placed', () => {
    assert.equal(exprText(parseExpr('returns(close, 1, simple)')), 'returns(close)');
    assert.equal(exprText(parseExpr('volatility(close, type=log)')), 'volatility(close, 22, log)');
    assert.equal(exprText(parseExpr('returns(close, type=absolute)')), 'returns(close, 1, absolute)');
  });

  it('aliases are gs-quant\'s other names, and its own spellings', () => {
    for (const [alias, name] of [['vol', 'volatility'], ['sma', 'mean'], ['moving_average', 'mean'], ['corr', 'correlation'],
      ['zscore', 'zscores'], ['relative_strength_index', 'rsi'], ['abs_', 'abs'], ['sum_', 'sum']] as const) {
      const e = parseExpr(name === 'correlation' ? `${alias}(close, open)` : `${alias}(close)`);
      assert.equal(e.kind === 'call' && e.fn, name, alias);
    }
  });

  it('a tree as the spec keeps it', () => {
    assert.deepEqual(parseExpr('px:volume[AAPL, venue=NYSE].sum'), {
      kind: 'column', source: 'px', column: 'volume', fn: 'sum',
      where: [{ value: 'AAPL' }, { column: 'venue', value: 'NYSE' }],
    });
    assert.deepEqual(parseExpr('volatility($A, w=63)'), {
      kind: 'call', fn: 'volatility', args: [{ kind: 'ref', series: 'A' }], params: { w: 63 },
    });
    assert.deepEqual(parseExpr('-2'), { kind: 'number', value: -2 });
    assert.deepEqual(parseExpr('close[10Y, 2]'), { kind: 'column', column: 'close', where: [{ value: '10Y' }, { value: 2 }] });
  });

  const refused = (text: string, words: RegExp): void => {
    assert.throws(() => parseExpr(text), (e: unknown) => e instanceof TsSyntaxError && words.test(e.message), text);
  };

  it('refuses what it cannot read, saying where', () => {
    refused('returns(close', /"\)" was expected/);
    refused('close +', /stops before its end/);
    refused('close close', /goes on past its end/);
    refused('foo(close)', /there is no function foo/);
    refused('returns(close, 1.5)', /obs is a whole number/);
    refused('returns(close, 1, cubic)', /type is one of simple, log, absolute/);
    refused('volatility(close, w=-1)', /w is at least 0/);
    refused('correlation(close)', /reads two series/);
    refused('returns(close, 1, log, 3)', /takes one series and obs, type/);
    refused('volatility(close, k=2)', /has no parameter k/);
    refused('close.median', /a period's value is one of/);
    refused('close[', /a value was expected/);
    refused('#', /means nothing here/);
    const at = ((): number => {
      try {
        parseExpr('returns(close, x)');
      } catch (e) {
        return (e as TsSyntaxError).at;
      }
      return -1;
    })();
    assert.equal(at, 15, 'the refusal says where it is');
  });

  it('refuses gs-quant\'s functions DataCube does not compute, saying why', () => {
    refused('exponential_moving_average(close)', /recursion/);
    refused('macd(close)', /recursion/);
    refused('ema(close)', /recursion/);
    refused('median(close)', /rolling median/);
  });

  it('every function in the catalog says what it is, and what of gs-quant it is', () => {
    for (const f of TS_FUNCTIONS) {
      assert.ok(f.about.length > 0 && f.gsQuant.length > 0, f.name);
      const args = Array.from({ length: f.series }, () => 'close').join(', ');
      assert.equal(exprText(parseExpr(`${f.name}(${args})`)), `${f.name}(${args})`, f.name);
    }
  });
});

describe('what an expression reads, and is', () => {
  const TS: TimeseriesSpec = {
    sources: [{ alias: 'px', cube: 'g1', date: 'day', key: 'symbol' }, { alias: 'rates', cube: 'g2', date: 'd', key: 'tenor' }],
    series: [
      { id: 'A', expr: parseExpr('px:close[SPX]') },
      { id: 'B', expr: parseExpr('rates:yield[10Y]') },
      { id: 'C', expr: parseExpr('correlation($A, $B, 63)') },
    ],
  };

  it('its refs, inlined; its sources in the order it reads them; their columns', () => {
    const c = TS.series[2]!;
    assert.deepEqual(refsOf(c.expr), ['A', 'B']);
    const whole = inlined(c.expr, TS.series);
    assert.deepEqual(sourcesOf(TS, whole), ['px', 'rates']);
    assert.deepEqual([...columnsOf(TS, whole)], [['px', ['close', 'symbol']], ['rates', ['yield', 'tenor']]]);
    assert.deepEqual(sourcesOf(TS, parseExpr('close')), ['px'], 'a column of no source reads the first');
  });

  it('a ref round in a circle is said', () => {
    const series = [{ id: 'A', expr: parseExpr('$B') }, { id: 'B', expr: parseExpr('$A + 1') }];
    assert.throws(() => inlined(series[0]!.expr, series), /A -> B -> A|B -> A/);
  });

  it('its unit: a level, a ratio, a percent, a number, an index', () => {
    const u = (t: string): string => unitOf(parseExpr(t));
    assert.equal(u('close'), 'same');
    assert.equal(u('mean(close, 20)'), 'same');
    assert.equal(u('returns(close)'), 'ratio');
    assert.equal(u('returns(close, 1, absolute)'), 'same');
    assert.equal(u('volatility(close)'), 'percent');
    assert.equal(u('zscores(close)'), 'number');
    assert.equal(u('rebase(close)'), 'index');
    assert.equal(u('close[A] / close[B]'), 'number');
    assert.equal(u('close * 2'), 'same');
    assert.equal(u('drawdown(close)'), 'ratio');
  });

  it('series ids run A to Z, then A2', () => {
    assert.equal(nextSeriesId([]), 'A');
    assert.equal(nextSeriesId([{ id: 'A', expr: parseExpr('close') }, { id: 'C', expr: parseExpr('close') }]), 'B');
    const all = Array.from({ length: 26 }, (_, i) => ({ id: String.fromCharCode(65 + i), expr: parseExpr('close') as TsExpr }));
    assert.equal(nextSeriesId(all), 'A2');
  });

  it('an alias from a grid\'s title: its first word, made unique', () => {
    assert.equal(aliasFor('Prices', []), 'prices');
    assert.equal(aliasFor('US Rates (daily)', ['us']), 'us2');
    assert.equal(aliasFor(undefined, []), 'grid');
    assert.equal(aliasFor('2024 file', []), 'grid');
    assert.equal(aliasFor('returns', []), 'returns', 'a function\'s name too: an alias is read before a colon');
  });
});

describe('the saved spec\'s reader', () => {
  const OK: TimeseriesSpec = {
    sources: [{ alias: 'px', cube: 'grid-1', date: 'day', key: 'symbol' }],
    grain: 'day',
    series: [{ id: 'A', expr: parseExpr('rebase(close[AAPL])'), name: 'Apple', style: { kind: 'line', color: 2, width: 2, dash: 'dashed' }, pane: 0, axis: 'left' }],
    range: { last: '5y' },
    panes: [{ height: 3, log: true }],
    annotations: [{ kind: 'level', value: 100, label: 'par' }, { kind: 'event', date: '2024-03-20', label: 'FOMC' }, { kind: 'period', from: '2020-02-19', to: '2020-04-07' }],
    view: { compare: 'rebase', legend: true, lastValues: true, navigator: true, stats: false, crosshair: true },
  };
  const with_ = (change: Record<string, unknown>): unknown => ({ ...JSON.parse(JSON.stringify(OK)), ...change });

  it('reads a spec of every slot', () => {
    assert.equal(tsProblem(JSON.parse(JSON.stringify(OK))), null);
  });

  it('refuses a word, a key or a number it has no place for, by name', () => {
    const cases: [unknown, RegExp][] = [
      [with_({ colour: 1 }), /no "colour" in its timeseries/],
      [with_({ sources: [] }), /reads no grid/],
      [with_({ sources: [{ alias: 'p x', cube: 'g', date: 'd' }] }), /alias "p x" is not a word/],
      [with_({ sources: [{ alias: 'px', cube: 'g', date: 'd' }, { alias: 'px', cube: 'h', date: 'd' }] }), /two sources are called px/],
      [with_({ grain: 'hour' }), /frequency "hour"/],
      [with_({ series: [{ id: 'a', expr: { kind: 'number', value: 1 } }] }), /not a capital letter/],
      [with_({ series: [{ id: 'A', expr: { kind: 'call', fn: 'macd', args: [] } }] }), /macd.*recursion/],
      [with_({ series: [{ id: 'A', expr: { kind: 'call', fn: 'returns', args: [{ kind: 'column', column: 'c' }], params: { obs: 0 } } }] }), /obs 0 is below 1/],
      [with_({ series: [{ id: 'A', expr: { kind: 'column', source: 'fx', column: 'c' } }] }), /reads fx:..., which is none of its sources/],
      [with_({ series: [{ id: 'A', expr: { kind: 'column', column: 'c' }, pane: 4 }] }), /pane 4 is not one of 0 to 3/],
      [with_({ series: [{ id: 'A', expr: { kind: 'column', column: 'c' }, style: { kind: 'candles' } }] }), /style "candles"/],
      [with_({ range: { last: '7y' } }), /range "7y"/],
      [with_({ range: { from: '2024-02-01', to: '2024-01-01' } }), /ends before it starts/],
      [with_({ range: { last: '1y', from: '2024-01-01' } }), /not both/],
      [with_({ annotations: [{ kind: 'event', date: 'March' }] }), /is not a date/],
      [with_({ view: { compare: 'log' } }), /compare "log"/],
    ];
    for (const [spec, words] of cases) {
      const p = tsProblem(spec);
      assert.ok(p !== null && words.test(p), `${JSON.stringify(spec).slice(0, 80)}: ${p}`);
    }
  });
});

describe('a timeseries against its sources\' columns', () => {
  const PX: TsColumn[] = [
    { name: 'symbol', type: 'String' }, { name: 'day', type: 'StrictDate' }, { name: 'close', type: 'Float' },
    { name: 'volume', type: 'Integer' }, { name: 'sector', type: 'String' },
  ];
  const RATES: TsColumn[] = [{ name: 'tenor', type: 'String' }, { name: 'obs', type: 'StrictDate' }, { name: 'yield', type: 'Float' }];
  const TS = (series: Record<string, string>, more: Partial<TimeseriesSpec> = {}): TimeseriesSpec => ({
    sources: [{ alias: 'px', cube: 'g1', date: 'day', key: 'symbol' }, { alias: 'rates', cube: 'g2', date: 'obs', key: 'tenor' }],
    series: Object.entries(series).map(([id, text]) => ({ id, expr: parseExpr(text) })),
    ...more,
  });
  const cols = new Map([['px', PX], ['rates', RATES]]);
  const problems = (ts: TimeseriesSpec, c: ReadonlyMap<string, readonly TsColumn[] | undefined> = cols): string[] => timeseriesProblems(ts, c);

  it('draws what reads what is there', () => {
    assert.deepEqual(problems(TS({ A: 'px:close[SPX]', B: 'rates:yield[10Y]', C: 'correlation($A, $B, 63)' })), []);
  });

  it('says each thing that stops it', () => {
    const has = (ts: TimeseriesSpec, words: RegExp, c?: ReadonlyMap<string, readonly TsColumn[] | undefined>): void => {
      const p = problems(ts, c);
      assert.ok(p.some((x) => words.test(x)), `${words}: ${p.join(' | ')}`);
    };
    has(TS({ A: 'px:price' }), /reads 'price', which source px has not/);
    has(TS({ A: 'px:sector' }), /plots 'sector', which is not a number/);
    has(TS({ A: '$Z' }), /reads \$Z, which there is not/);
    has(TS({}), /Add a series/);
    has(TS({ A: 'px:close' }), /Source rates's grid is not on the page/, new Map([['px', PX], ['rates', undefined]]));
    has({ ...TS({ A: 'close' }), sources: [{ alias: 'px', cube: 'g1', date: 'close' }] }, /is not a date/);
    has({ ...TS({ A: 'close[AAPL]' }), sources: [{ alias: 'px', cube: 'g1', date: 'day' }] }, /choose source px's instrument column/);
    has({ ...TS({ A: 'bollinger_bands(px:close, 20)', B: 'returns($A)' }) }, /a band is drawn, never read/);
    const split: TimeseriesSpec = { ...TS({}), series: [{ id: 'A', expr: parseExpr('px:close'), split: 'symbol' }, { id: 'B', expr: parseExpr('returns($A)') }] };
    has(split, /split by 'symbol': a series reads only series split as it is/);
    const wrongSplit: TimeseriesSpec = { ...TS({}), series: [{ id: 'A', expr: parseExpr('rates:yield'), split: 'symbol' }] };
    has(wrongSplit, /which source rates -- the one it reads first -- has not/);
  });
});

describe('a first timeseries of a grid', () => {
  it('its date, its instruments, its price, one line per instrument', () => {
    const ts = defaultTimeseries([
      { name: 'id', type: 'Integer' }, { name: 'trade_date', type: 'StrictDate' }, { name: 'ticker', type: 'String' },
      { name: 'volume', type: 'Integer' }, { name: 'close_px', type: 'Float' },
    ], [], 'grid-1', 'prices');
    assert.deepEqual(ts, {
      sources: [{ alias: 'prices', cube: 'grid-1', date: 'trade_date', key: 'ticker' }],
      series: [{ id: 'A', expr: { kind: 'column', column: 'close_px' }, split: 'ticker' }],
    });
  });

  it('none of a grid with no date, or no number', () => {
    assert.equal(defaultTimeseries([{ name: 'a', type: 'String' }, { name: 'n', type: 'Float' }], [], 'g', 'a'), null);
    assert.equal(defaultTimeseries([{ name: 'd', type: 'StrictDate' }, { name: 's', type: 'String' }], [], 'g', 'a'), null);
  });

  it('a grid grouped by a text column takes it as the instruments', () => {
    const ts = defaultTimeseries([{ name: 'd', type: 'DateTime' }, { name: 'desk', type: 'String' }, { name: 'pnl', type: 'Float' }], ['desk'], 'g', 'pnl');
    assert.equal(ts?.sources[0]?.key, 'desk');
  });
});
