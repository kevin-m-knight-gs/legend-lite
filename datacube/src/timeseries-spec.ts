// THE TIMESERIES VIEWER'S SPEC (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §3): what a timeseries tile plots, in
// plain JSON -- a page view of its own (page-document.ts TimeseriesView), saved, undone, merged and made by Python as
// every tile is.
//
// A tile reads SOURCES -- grids of the page, each by its cube, with its own date column and instrument column -- and
// plots SERIES along one time axis. Each series is an EXPRESSION in the vocabulary of gs-quant's
// timeseries package -- the functions Goldman Sachs' PlotTool Pro exports to its expression language
// (`gs_quant.timeseries`, each marked `@plot_function`): returns, volatility, z-scores, rolling statistics, drawdowns,
// correlation and beta, Bollinger bands. A person types one (`volatility(px:close[AAPL], 63)`) or builds it from the
// menus; either way the spec keeps the tree, and its text is printed from the tree (`exprText`), so the words a person
// typed and the words saved never drift.
//
// NOTHING HERE COMPUTES A VALUE. An expression is words; timeseries-query.ts writes each node as a column of the
// series' query -- a window over the dates, an expression over the row -- which the planner plans and the database
// computes, as every calculated column of DataCube's is. This file says what is meant and refuses what is not
// (tsProblem: a saved spec; timeseriesProblems: a spec against its grid's columns), in words.

import type { TimeGrain } from './chart-spec.ts';
import { isNumeric, isTemporal, isTimeOfDay } from '../../engine-client/src/types.ts';

// ------------------------------------------------------------------ the spec

/** How a period's rows become its one value: the latest row's (a price), the earliest's, or an aggregate (a volume's sum). */
export type TsAggregate = 'last' | 'first' | 'average' | 'sum' | 'min' | 'max';

/** A match on a series' rows: `close[AAPL]` (its source's instrument column) or `close[venue=NYSE]`. */
export interface TsMatch {
  /** The column matched; absent, its source's instrument column (TsSource.key). */
  readonly column?: string;
  readonly value: string | number | boolean;
}

/** What a series plots: a tree of the grid's columns, numbers, other series and functions. */
export type TsExpr =
  /**
   * A column of a source along its date: its rows that match, each period's taken by `fn` (default `last`). `source`:
   * the alias of the source it reads (`px:close`); absent, the tile's first source.
   */
  | {
    readonly kind: 'column'; readonly source?: string; readonly column: string; readonly fn?: TsAggregate;
    readonly where?: readonly TsMatch[];
  }
  /** Another series' expression, by its id: `$A`. */
  | { readonly kind: 'ref'; readonly series: string }
  | { readonly kind: 'number'; readonly value: number }
  | { readonly kind: 'binary'; readonly op: TsOperator; readonly left: TsExpr; readonly right: TsExpr }
  /** A function of the catalog (TS_FUNCTIONS): its series arguments, then its parameters by name. */
  | {
    readonly kind: 'call'; readonly fn: TsFunctionName; readonly args: readonly TsExpr[];
    readonly params?: Readonly<Record<string, number | string>>;
  };

export type TsOperator = '+' | '-' | '*' | '/';

/** How a series is drawn. */
export interface TsStyle {
  /** A line (the default), the area under it, bars from zero, steps, dots; a Bollinger band is a band, OHLC candles. */
  readonly kind?: TsStyleKind;
  /** Its colour: a slot of the palette (0 its first), else its place among the lines. */
  readonly color?: number;
  /** Its line's width, 1 to 4 pixels (default 2). */
  readonly width?: number;
  readonly dash?: 'solid' | 'dashed' | 'dotted';
}

export type TsStyleKind = 'line' | 'area' | 'bars' | 'step' | 'dots';

export interface TsSeries {
  /** Its id -- a capital letter, as `$A` refers to it -- unique in the tile. */
  readonly id: string;
  readonly expr: TsExpr;
  /** Its name in the legend; else its expression's text. */
  readonly name?: string;
  /**
   * One line per value of this column (one per symbol), each its own partition of every window: a column of the source
   * the series reads first (sourcesOf).
   */
  readonly split?: string;
  readonly style?: TsStyle;
  /** The pane it is drawn in, 0 the main one at the top, up to MAX_PANES - 1. */
  readonly pane?: number;
  /** Its value axis in its pane; else by its unit (a second unit takes the right axis). */
  readonly axis?: 'left' | 'right';
  /** Left out of the drawing (its legend entry stays, to show it again). */
  readonly hidden?: boolean;
}

/** A period back from the latest date the series have, or all of them. */
export type TsRangeWord = '1m' | '3m' | '6m' | 'ytd' | '1y' | '2y' | '3y' | '5y' | '10y' | 'max';

/** The window a tile shows: a period back from the latest date (`last`), or between two dates (ISO, either open). */
export type TsRange = { readonly last: TsRangeWord } | { readonly from?: string; readonly to?: string };

/** A pane: its share of the height (the main pane's default 3, the others' 1), and its value axis' scale. */
export interface TsPane {
  readonly height?: number;
  readonly log?: boolean;
}

/** What is drawn over the series: a level, an event's date, a shaded period -- each with a label. */
export type TsAnnotation =
  | { readonly kind: 'level'; readonly value: number; readonly pane?: number; readonly label?: string }
  | { readonly kind: 'event'; readonly date: string; readonly label?: string }
  | { readonly kind: 'period'; readonly from: string; readonly to: string; readonly label?: string };

export type TsAnnotationKind = TsAnnotation['kind'];

/** The viewer's own settings: each off or on, every one with a default (TS_VIEW_DEFAULTS). */
export interface TsView {
  /** Every line from one start: 100 at the first date shown (`rebase`), or its percent change from there (`percent`). */
  readonly compare?: 'rebase' | 'percent';
  /** The legend above the chart, each line's latest value and change beside its name. */
  readonly legend?: boolean;
  /** Each line's latest value at its end. */
  readonly lastValues?: boolean;
  /** The navigator under the chart: a slider over every date, to zoom and pan. */
  readonly navigator?: boolean;
  /** The statistics of every line over the dates shown, under the chart. */
  readonly stats?: boolean;
  /** A crosshair across the panes, with every line's value at the date pointed at. */
  readonly crosshair?: boolean;
}

/**
 * A GRID THE TILE READS: its cube on the page (a grid's, or -- its grid removed -- one kept for the tile, detached), the
 * date column its rows are read along, the column naming each row's instrument, and the alias its expressions call it.
 */
export interface TsSource {
  /** A word, unique in the tile: `px:close`. */
  readonly alias: string;
  /** The cube it reads: the page's (a grid's id is its cube's). */
  readonly cube: string;
  /** Its date column: a StrictDate or a DateTime. */
  readonly date: string;
  /** Its column naming each row's instrument -- a symbol, a ticker, a tenor -- that `close[AAPL]` matches. */
  readonly key?: string;
}

export interface TimeseriesSpec {
  /** The grids it reads, the first the one an expression reads when it names none. */
  readonly sources: readonly TsSource[];
  /**
   * The ONE frequency of the tile -- each series' values taken to it by its column's aggregate -- so series of
   * different grids share one time axis. Absent: as the rows are.
   */
  readonly grain?: TimeGrain;
  readonly series: readonly TsSeries[];
  /** The window shown first. Absent: every date. */
  readonly range?: TsRange;
  readonly panes?: readonly TsPane[];
  readonly annotations?: readonly TsAnnotation[];
  readonly view?: TsView;
}

/** The view's settings before anyone sets one. */
export const TS_VIEW_DEFAULTS: Required<Omit<TsView, 'compare'>> = {
  legend: true,
  lastValues: true,
  navigator: true,
  stats: false,
  crosshair: true,
};

/** A view setting as the tile has it: told, else its default. */
export function viewSetting(ts: TimeseriesSpec, key: keyof typeof TS_VIEW_DEFAULTS): boolean {
  return ts.view?.[key] ?? TS_VIEW_DEFAULTS[key];
}

/** The most series a tile plots, and the most lines one split series draws (its first values, in order). */
export const MAX_TS_SERIES = 12;
/** The most grids a tile reads. */
export const MAX_TS_SOURCES = 8;
export const MAX_SPLIT_LINES = 20;
/** The most panes a tile stacks: the main one and three under it. */
export const MAX_PANES = 4;
/** The most points a series' query asks for: past it, the latest ones (a cut is said). */
export const TS_POINT_LIMIT = 100_000;

/** The range buttons, in their order, and how each is labelled. */
export const TS_RANGES: readonly { readonly word: TsRangeWord; readonly label: string }[] = [
  { word: '1m', label: '1M' }, { word: '3m', label: '3M' }, { word: '6m', label: '6M' }, { word: 'ytd', label: 'YTD' },
  { word: '1y', label: '1Y' }, { word: '2y', label: '2Y' }, { word: '3y', label: '3Y' }, { word: '5y', label: '5Y' },
  { word: '10y', label: '10Y' }, { word: 'max', label: 'Max' },
];

/** A period back as the compiler's `adjust` takes it: how many of which DurationUnit (ytd and max are their own). */
export const RANGE_STEP: Readonly<Partial<Record<TsRangeWord, readonly [number, 'MONTHS' | 'YEARS']>>> = {
  '1m': [1, 'MONTHS'], '3m': [3, 'MONTHS'], '6m': [6, 'MONTHS'], '1y': [1, 'YEARS'], '2y': [2, 'YEARS'],
  '3y': [3, 'YEARS'], '5y': [5, 'YEARS'], '10y': [10, 'YEARS'],
};

/**
 * Periods in a year at each grain: what annualizes a figure (gs-quant's `_get_annualization_factor`, which reads it off
 * the spacing of the dates -- here the grain says it, so a series with a gap is annualized the same).
 */
export const PERIODS_PER_YEAR: Readonly<Record<TimeGrain, number>> = {
  day: 252, week: 52, month: 12, quarter: 4, year: 1,
};

// ------------------------------------------------------------------ the catalog

/** What a function's figures are, so the axis and the tooltip say them right. */
export type TsUnit =
  /** As its input: a level, a price, the grid's own format. */
  | 'same'
  /** A ratio, written as a percentage: a return, a drawdown. */
  | 'ratio'
  /** Already in percent (gs-quant's volatility: 20% is 20.0). */
  | 'percent'
  /** A plain number: a z-score, a beta, a correlation. */
  | 'number'
  /** An index level: 100 at its start. */
  | 'index';

export type TsFunctionName =
  | 'returns' | 'prices' | 'diff' | 'lag' | 'index' | 'rebase' | 'change' | 'percent_change' | 'annualize'
  | 'mean' | 'sum' | 'min' | 'max' | 'range' | 'std' | 'var' | 'zscores' | 'winsorize'
  | 'volatility' | 'drawdown' | 'max_drawdown' | 'sharpe_ratio'
  | 'correlation' | 'beta' | 'cov'
  | 'bollinger_bands' | 'rsi'
  | 'log' | 'exp' | 'sqrt' | 'abs' | 'power' | 'floor' | 'ceil';

/** A function's parameter: a whole number (a window, a lag), a number, or a word (a kind of return). */
export interface TsParam {
  readonly name: string;
  readonly kind: 'whole' | 'number' | 'word';
  readonly default: number | string;
  readonly words?: readonly string[];
  /** The least a number may be. */
  readonly min?: number;
  readonly about: string;
}

export type TsFamily = 'Returns' | 'Level' | 'Rolling statistics' | 'Risk' | 'Relationships' | 'Technicals' | 'Math';

export interface TsFunction {
  readonly name: TsFunctionName;
  readonly label: string;
  readonly family: TsFamily;
  /** How many series it reads: one, or two (a correlation's). */
  readonly series: 1 | 2;
  readonly params: readonly TsParam[];
  /** What its figures are; `input` where it depends on a parameter (returns' type). */
  readonly unit: TsUnit | 'input';
  readonly about: string;
  /** The gs-quant function it is, `module.name`, or DataCube's own where gs-quant has none. */
  readonly gsQuant: string;
  /** Where it differs from gs-quant's, said. */
  readonly differs?: string;
  /** It reads the FIRST VALUE SHOWN (an index's base): computed after the window shown starts (timeseries-query.ts). */
  readonly anchored?: true;
  /** It draws two lines, a band between them (a Bollinger band): nothing may read it. */
  readonly band?: true;
  /** Other names a person may type for it. */
  readonly aliases?: readonly string[];
}

const W = (fallback: number, about = 'observations in the window; 0 from the start'): TsParam => ({
  name: 'w', kind: 'whole', default: fallback, min: 0, about,
});
const OBS: TsParam = { name: 'obs', kind: 'whole', default: 1, min: 1, about: 'observations back' };
const RETURN_TYPE: TsParam = {
  name: 'type', kind: 'word', default: 'simple', words: ['simple', 'log', 'absolute'],
  about: 'simple: x / x[-obs] - 1; log: ln x - ln x[-obs]; absolute: x - x[-obs]',
};

/**
 * THE FUNCTIONS, gs-quant's names first (PlotTool Pro's words), each with what it computes -- and, where DataCube's
 * differs, how. Every one is written by timeseries-query.ts as columns the database computes.
 */
export const TS_FUNCTIONS: readonly TsFunction[] = [
  // returns
  { name: 'returns', label: 'Returns', family: 'Returns', series: 1, params: [OBS, RETURN_TYPE], unit: 'input',
    about: 'The return over obs observations.', gsQuant: 'econometrics.returns' },
  { name: 'prices', label: 'Prices from returns', family: 'Returns', series: 1,
    params: [{ name: 'initial', kind: 'number', default: 1, about: 'the level it starts from' }, RETURN_TYPE], unit: 'same',
    about: 'A level compounded from a series of returns, from initial.', gsQuant: 'econometrics.prices',
    differs: 'compounded from the first return the series has: its first level is initial times its first return' },
  { name: 'diff', label: 'Difference', family: 'Returns', series: 1, params: [OBS], unit: 'same',
    about: 'The change over obs observations: x - x[-obs].', gsQuant: 'analysis.diff' },
  { name: 'lag', label: 'Lag', family: 'Returns', series: 1, params: [OBS], unit: 'same',
    about: 'The value obs observations before.', gsQuant: 'analysis.lag',
    differs: 'a whole number of observations; gs-quant also takes a calendar period (1m), not built' },
  { name: 'annualize', label: 'Annualize', family: 'Returns', series: 1, params: [], unit: 'same',
    about: 'Times the square root of the periods in a year (252 days, 52 weeks, 12 months, 4 quarters).',
    gsQuant: 'econometrics.annualize', differs: 'the periods in a year are the tile\'s frequency, not read off the dates' },
  // level
  { name: 'index', label: 'Index', family: 'Level', series: 1,
    params: [{ name: 'initial', kind: 'number', default: 1, about: 'its value at the first date shown' }], unit: 'index',
    about: 'Divided by its value at the first date shown, times initial.', gsQuant: 'econometrics.index', anchored: true },
  { name: 'rebase', label: 'Rebase to 100', family: 'Level', series: 1,
    params: [{ name: 'base', kind: 'number', default: 100, about: 'its value at the first date shown' }], unit: 'index',
    about: 'Its value at the first date shown made base (100): index(x, 100).', gsQuant: 'DataCube (econometrics.index)',
    anchored: true },
  { name: 'change', label: 'Change from start', family: 'Level', series: 1, params: [], unit: 'same',
    about: 'Less its value at the first date shown.', gsQuant: 'econometrics.change', anchored: true },
  { name: 'percent_change', label: '% change from start', family: 'Level', series: 1, params: [], unit: 'ratio',
    about: 'Its change from its value at the first date shown, as a share of it.', gsQuant: 'DataCube (econometrics.index - 1)',
    anchored: true },
  // rolling statistics
  { name: 'mean', label: 'Moving average', family: 'Rolling statistics', series: 1, params: [W(22)], unit: 'same',
    about: 'The average over the last w observations.', gsQuant: 'statistics.mean, technicals.moving_average',
    aliases: ['moving_average', 'sma', 'ma', 'average'] },
  { name: 'sum', label: 'Rolling sum', family: 'Rolling statistics', series: 1, params: [W(0)], unit: 'same',
    about: 'The sum over the last w observations; from the start, a running total.', gsQuant: 'statistics.sum_',
    aliases: ['sum_', 'cumsum'] },
  { name: 'min', label: 'Rolling minimum', family: 'Rolling statistics', series: 1, params: [W(22)], unit: 'same',
    about: 'The least over the last w observations.', gsQuant: 'statistics.min_', aliases: ['min_'] },
  { name: 'max', label: 'Rolling maximum', family: 'Rolling statistics', series: 1, params: [W(22)], unit: 'same',
    about: 'The most over the last w observations.', gsQuant: 'statistics.max_', aliases: ['max_'] },
  { name: 'range', label: 'Rolling range', family: 'Rolling statistics', series: 1, params: [W(22)], unit: 'same',
    about: 'The most less the least over the last w observations.', gsQuant: 'statistics.range_', aliases: ['range_'] },
  { name: 'std', label: 'Rolling standard deviation', family: 'Rolling statistics', series: 1, params: [W(22)], unit: 'same',
    about: 'The sample standard deviation over the last w observations.', gsQuant: 'statistics.std', aliases: ['stdev'] },
  { name: 'var', label: 'Rolling variance', family: 'Rolling statistics', series: 1, params: [W(22)], unit: 'same',
    about: 'The sample variance over the last w observations.', gsQuant: 'statistics.var', aliases: ['variance'] },
  { name: 'zscores', label: 'Z-score', family: 'Rolling statistics', series: 1, params: [W(0, 'observations in the window; 0 every observation')],
    unit: 'number', about: 'How many standard deviations from the mean: over the last w observations, or (0) every one.',
    gsQuant: 'statistics.zscores', aliases: ['zscore', 'z'] },
  { name: 'winsorize', label: 'Winsorize', family: 'Rolling statistics', series: 1,
    params: [{ name: 'limit', kind: 'number', default: 2.5, min: 0, about: 'standard deviations from the mean it is held within' }],
    unit: 'same', about: 'Held within limit standard deviations of its mean over every observation.',
    gsQuant: 'statistics.winsorize' },
  // risk
  { name: 'volatility', label: 'Realized volatility', family: 'Risk', series: 1, params: [W(22), RETURN_TYPE], unit: 'percent',
    about: 'The annualized standard deviation of its returns over the last w observations, in percent (20% is 20).',
    gsQuant: 'econometrics.volatility', aliases: ['vol'],
    differs: 'w defaults to 22 observations (gs-quant: the whole series); annualized by the tile\'s frequency' },
  { name: 'drawdown', label: 'Drawdown', family: 'Risk', series: 1, params: [W(0, 'observations its peak is taken over; 0 from the start')],
    unit: 'ratio', about: 'Below its running peak, as a share of it: x / max(x) - 1.', gsQuant: 'DataCube (econometrics.max_drawdown\'s first step)' },
  { name: 'max_drawdown', label: 'Maximum drawdown', family: 'Risk', series: 1, params: [W(0, 'observations in the window; 0 from the start')],
    unit: 'ratio', about: 'The deepest drawdown over the last w observations (-0.2 for 20%).', gsQuant: 'econometrics.max_drawdown' },
  { name: 'sharpe_ratio', label: 'Sharpe ratio', family: 'Risk', series: 1, params: [W(252)], unit: 'number',
    about: 'Its annualized mean return over its annualized volatility, over the last w observations.',
    gsQuant: 'econometrics.sharpe_ratio',
    differs: 'a risk-free rate of zero and the arithmetic mean of simple returns; gs-quant takes excess returns over a currency\'s rate and compounds' },
  // relationships
  { name: 'correlation', label: 'Rolling correlation', family: 'Relationships', series: 2, params: [W(63)], unit: 'number',
    about: 'The correlation of the two series\' simple returns over the last w observations, on the dates both have.',
    gsQuant: 'econometrics.correlation', aliases: ['corr'] },
  { name: 'beta', label: 'Rolling beta', family: 'Relationships', series: 2, params: [W(63)], unit: 'number',
    about: 'The first\'s beta to the second (a benchmark): the covariance of their simple returns over the second\'s variance.',
    gsQuant: 'econometrics.beta' },
  { name: 'cov', label: 'Rolling covariance', family: 'Relationships', series: 2, params: [W(63)], unit: 'number',
    about: 'The sample covariance of the two series as they are, over the last w observations.', gsQuant: 'statistics.cov' },
  // technicals
  { name: 'bollinger_bands', label: 'Bollinger bands', family: 'Technicals', series: 1,
    params: [W(20), { name: 'k', kind: 'number', default: 2, min: 0, about: 'standard deviations either side' }], unit: 'same',
    about: 'A band k standard deviations either side of the moving average over the last w observations.',
    gsQuant: 'technicals.bollinger_bands', band: true, aliases: ['bollinger'] },
  { name: 'rsi', label: 'Relative strength index', family: 'Technicals', series: 1, params: [W(14)], unit: 'number',
    about: '100 - 100 / (1 + average gain / average loss) over the last w changes: 0 to 100.',
    gsQuant: 'technicals.relative_strength_index', aliases: ['relative_strength_index'],
    differs: 'simple averages of the gains and losses (Cutler\'s); gs-quant smooths them (Wilder\'s), a recursion the database\'s windows have not' },
  // math
  { name: 'log', label: 'Natural log', family: 'Math', series: 1, params: [], unit: 'number', about: 'ln x.', gsQuant: 'algebra.log' },
  { name: 'exp', label: 'Exponential', family: 'Math', series: 1, params: [], unit: 'number', about: 'e to the x.', gsQuant: 'algebra.exp' },
  { name: 'sqrt', label: 'Square root', family: 'Math', series: 1, params: [], unit: 'same', about: 'The square root.', gsQuant: 'algebra.sqrt' },
  { name: 'abs', label: 'Absolute value', family: 'Math', series: 1, params: [], unit: 'same', about: '|x|.', gsQuant: 'algebra.abs_', aliases: ['abs_'] },
  { name: 'power', label: 'Power', family: 'Math', series: 1,
    params: [{ name: 'y', kind: 'number', default: 2, about: 'the power' }], unit: 'number', about: 'x to the power y.',
    gsQuant: 'algebra.power' },
  { name: 'floor', label: 'Floor at a value', family: 'Math', series: 1,
    params: [{ name: 'value', kind: 'number', default: 0, about: 'the least it may be' }], unit: 'same',
    about: 'Never below value: the greater of x and value.', gsQuant: 'algebra.floor' },
  { name: 'ceil', label: 'Cap at a value', family: 'Math', series: 1,
    params: [{ name: 'value', kind: 'number', default: 0, about: 'the most it may be' }], unit: 'same',
    about: 'Never above value: the lesser of x and value.', gsQuant: 'algebra.ceil' },
];

/** The functions by name. */
export const TS_FUNCTION: ReadonlyMap<TsFunctionName, TsFunction> = new Map(TS_FUNCTIONS.map((f) => [f.name, f]));

/** Every name a person may type, an alias to its function. */
const TYPED_NAMES: ReadonlyMap<string, TsFunction> = new Map(TS_FUNCTIONS.flatMap((f) => [
  [f.name, f] as const, ...(f.aliases ?? []).map((a) => [a, f] as const),
]));

/**
 * gs-quant's functions DataCube does not compute, each with why: typed, they are refused in these words rather than
 * read as a column of the grid.
 */
export const NOT_BUILT: Readonly<Record<string, string>> = {
  exponential_moving_average: 'an exponential average is a recursion (each value from the one before), which the database\'s windows have not: use mean(x, w)',
  ema: 'an exponential average is a recursion (each value from the one before), which the database\'s windows have not: use mean(x, w)',
  macd: 'MACD is a difference of exponential averages, a recursion the database\'s windows have not',
  exponential_volatility: 'an exponentially weighted volatility is a recursion the database\'s windows have not: use volatility(x, w)',
  exponential_std: 'an exponentially weighted deviation is a recursion the database\'s windows have not: use std(x, w)',
  smoothed_moving_average: 'a smoothed average is a recursion the database\'s windows have not: use mean(x, w)',
  seasonally_adjusted: 'a seasonal decomposition is a model fitted to the series, not a window over it',
  trend: 'a trend from a seasonal decomposition is a model fitted to the series, not a window over it',
  median: 'a rolling median is a window the planner does not write yet: use mean(x, w) or zscores(x, w)',
  percentiles: 'a rolling percentile rank is a window the planner does not write yet',
  excess_returns: 'excess returns need a risk-free rate series: divide by one of your own',
};

/** The function a typed name means, by its name or an alias. */
export function functionNamed(name: string): TsFunction | undefined {
  return TYPED_NAMES.get(name.toLowerCase());
}

/** A function's parameter as a call has it: told, else its default. */
export function paramOf(call: Extract<TsExpr, { kind: 'call' }>, name: string): number | string {
  const f = TS_FUNCTION.get(call.fn);
  const p = f?.params.find((q) => q.name === name);
  if (!p) throw new Error(`${call.fn} has no parameter ${name}`);
  return call.params?.[name] ?? p.default;
}

// ------------------------------------------------------------------ what an expression reads and is

/** The series an expression reads, by id (its `$A`s), each once. */
export function refsOf(e: TsExpr): string[] {
  const out = new Set<string>();
  const walk = (x: TsExpr): void => {
    if (x.kind === 'ref') out.add(x.series);
    else if (x.kind === 'binary') {
      walk(x.left);
      walk(x.right);
    } else if (x.kind === 'call') x.args.forEach(walk);
  };
  walk(e);
  return [...out];
}

/** A column read as an expression: `column`, of `source` when it is given (absent: the tile's first source), its rows
 *  where its instrument is `instrument` when it is given (absent: every row). */
export function columnRef(column: string, source?: string, instrument?: string): TsExpr {
  return {
    kind: 'column', ...(source !== undefined ? { source } : {}), column, ...(instrument !== undefined ? { where: [{ value: instrument }] } : {}),
  };
}

/** The alias of the source a column of an expression reads: its own, else the tile's first source's. */
export function sourceAlias(ts: Pick<TimeseriesSpec, 'sources'>, e: Extract<TsExpr, { kind: 'column' }>): string | undefined {
  return e.source ?? ts.sources[0]?.alias;
}

/** The sources an expression reads, by alias, in the order it first reads them (refs inlined first: `inlined`). */
export function sourcesOf(ts: Pick<TimeseriesSpec, 'sources'>, e: TsExpr): string[] {
  const out: string[] = [];
  const walk = (x: TsExpr): void => {
    if (x.kind === 'column') {
      const a = sourceAlias(ts, x);
      if (a !== undefined && !out.includes(a)) out.push(a);
    } else if (x.kind === 'binary') {
      walk(x.left);
      walk(x.right);
    } else if (x.kind === 'call') x.args.forEach(walk);
  };
  walk(e);
  return out;
}

/**
 * The columns an expression reads of each source -- its values' and its matches', a match of no column its source's
 * instrument column -- each once, by alias.
 */
export function columnsOf(ts: Pick<TimeseriesSpec, 'sources'>, e: TsExpr): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const add = (alias: string, c: string): void => {
    const cs = out.get(alias) ?? [];
    if (!cs.includes(c)) cs.push(c);
    out.set(alias, cs);
  };
  const walk = (x: TsExpr): void => {
    if (x.kind === 'column') {
      const alias = sourceAlias(ts, x);
      if (alias === undefined) return;
      add(alias, x.column);
      const key = ts.sources.find((src) => src.alias === alias)?.key;
      for (const m of x.where ?? []) {
        const c = m.column ?? key;
        if (c !== undefined) add(alias, c);
      }
    } else if (x.kind === 'binary') {
      walk(x.left);
      walk(x.right);
    } else if (x.kind === 'call') x.args.forEach(walk);
  };
  walk(e);
  return out;
}

/** An alias as a source may be called: a word, a letter first. */
export const ALIAS = /^[A-Za-z][A-Za-z0-9_]*$/;

/** An alias for a grid of this title: its first word, lower case (`Prices` is `prices`), made unique among `taken`. */
export function aliasFor(title: string | undefined, taken: readonly string[]): string {
  const word = (title ?? '').toLowerCase().replace(/[^a-z0-9_]+/g, ' ').trim().split(' ')[0] ?? '';
  // an alias is read before a colon and a function before a bracket: a word that names a function is still one
  const base = /^[a-z][a-z0-9_]*$/.test(word) ? word.slice(0, 16) : 'grid';
  if (!taken.includes(base)) return base;
  for (let n = 2; ; n++) if (!taken.includes(`${base}${n}`)) return `${base}${n}`;
}

/** An expression with every `$A` replaced by A's own expression (refs checked first: timeseriesProblems). */
export function inlined(e: TsExpr, series: readonly TsSeries[], seen: readonly string[] = []): TsExpr {
  switch (e.kind) {
    case 'ref': {
      if (seen.includes(e.series)) throw new Error(`series ${[...seen, e.series].join(' -> ')} reads itself`);
      const s = series.find((x) => x.id === e.series);
      if (!s) throw new Error(`there is no series ${e.series}`);
      return inlined(s.expr, series, [...seen, e.series]);
    }
    case 'binary':
      return { ...e, left: inlined(e.left, series, seen), right: inlined(e.right, series, seen) };
    case 'call':
      return { ...e, args: e.args.map((a) => inlined(a, series, seen)) };
    default:
      return e;
  }
}

/** What an expression's figures are (its top function's, or its input's), so it is written and placed by its unit. */
export function unitOf(e: TsExpr): TsUnit {
  switch (e.kind) {
    case 'column':
    case 'ref':
      return 'same';
    case 'number':
      return 'number';
    case 'binary': {
      const l = unitOf(e.left);
      const r = unitOf(e.right);
      // a level times or over a number is still a level; two levels divided are a number (a ratio of prices)
      if (e.op === '+' || e.op === '-') return l === 'number' ? r : l;
      if (e.right.kind === 'number') return l;
      if (e.left.kind === 'number') return r;
      return 'number';
    }
    case 'call': {
      const f = TS_FUNCTION.get(e.fn);
      if (!f) return 'number';
      if (f.unit === 'input') return paramOf(e, 'type') === 'absolute' ? unitOf(e.args[0]!) : 'ratio';
      if (f.unit === 'same') return e.args[0] ? unitOf(e.args[0]) : 'same';
      return f.unit;
    }
  }
}

/** Whether an expression draws a band (a Bollinger band): two lines, nothing reading it. */
export function isBand(e: TsExpr): boolean {
  return e.kind === 'call' && TS_FUNCTION.get(e.fn)?.band === true;
}

// ------------------------------------------------------------------ the expression language

/** An expression typed that cannot be read, said where. */
export class TsSyntaxError extends Error {
  /** Where in the text, from 0. */
  readonly at: number;
  constructor(message: string, at: number) {
    super(message);
    this.name = 'TsSyntaxError';
    this.at = at;
  }
}

type Token =
  | { readonly t: 'num'; readonly v: number; readonly at: number }
  | { readonly t: 'id'; readonly v: string; readonly at: number; readonly quoted: boolean }
  | { readonly t: 'str'; readonly v: string; readonly at: number }
  | { readonly t: 'ref'; readonly v: string; readonly at: number }
  | { readonly t: 'op'; readonly v: string; readonly at: number }
  | { readonly t: 'end'; readonly at: number };

function tokenize(text: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (/\s/.test(c)) {
      i++;
      continue;
    }
    const at = i;
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(text[i + 1] ?? ''))) {
      const m = /^(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?/i.exec(text.slice(i))!;
      // a number run straight into letters is a word -- a tenor, a period: 10Y, 3M
      if (/[A-Za-z_]/.test(text[i + m[0].length] ?? '')) {
        const w = /^[0-9A-Za-z_]+/.exec(text.slice(i))!;
        out.push({ t: 'id', v: w[0], at, quoted: false });
        i += w[0].length;
        continue;
      }
      out.push({ t: 'num', v: Number(m[0]), at });
      i += m[0].length;
      continue;
    }
    if (/[A-Za-z_]/.test(c)) {
      const m = /^[A-Za-z_][A-Za-z0-9_]*/.exec(text.slice(i))!;
      out.push({ t: 'id', v: m[0], at, quoted: false });
      i += m[0].length;
      continue;
    }
    if (c === '$') {
      const m = /^\$([A-Za-z][A-Za-z0-9]*)/.exec(text.slice(i));
      if (!m) throw new TsSyntaxError('a $ names a series: $A', at);
      out.push({ t: 'ref', v: m[1]!.toUpperCase(), at });
      i += m[0].length;
      continue;
    }
    if (c === '`' || c === '"' || c === '\'') {
      // a column with a name of its own (`total pnl`), or a value ("AAPL")
      const end = text.indexOf(c, i + 1);
      if (end < 0) throw new TsSyntaxError(`a ${c} is not closed`, at);
      const v = text.slice(i + 1, end);
      out.push(c === '`' ? { t: 'id', v, at, quoted: true } : { t: 'str', v, at });
      i = end + 1;
      continue;
    }
    if ('+-*/(),[]=.:'.includes(c)) {
      out.push({ t: 'op', v: c, at });
      i++;
      continue;
    }
    throw new TsSyntaxError(`"${c}" means nothing here`, at);
  }
  out.push({ t: 'end', at: text.length });
  return out;
}

const AGGREGATES: readonly TsAggregate[] = ['last', 'first', 'average', 'sum', 'min', 'max'];

/**
 * AN EXPRESSION AS A PERSON TYPES IT, read into the tree the spec keeps -- PlotTool Pro's style over gs-quant's names:
 *
 *   close                      the first source's column, each period's latest value
 *   px:close, rates:yield      a column of the source of that alias
 *   close[AAPL]                its rows whose instrument column (the source's key) is AAPL
 *   close[venue=NYSE, AAPL]    and whose venue is NYSE
 *   volume[AAPL].sum           each period's rows summed (last, first, average, sum, min, max)
 *   $A                         series A's expression
 *   returns(close[AAPL], 1, log), volatility($A, 63), correlation($A, $B, 126), zscores($A, w=252)
 *   close[AAPL] / close[MSFT], ($A + $B) / 2, 100 * returns($A)
 *
 * A column whose name is not a word is quoted with backticks (`total pnl`); a value that is not a word or a number with
 * quotes ("BRK.B"). A function's parameters follow its series, by place or by name (w=63, type=log).
 */
export function parseExpr(text: string): TsExpr {
  const toks = tokenize(text);
  let p = 0;
  const peek = (): Token => toks[p]!;
  const next = (): Token => toks[p++]!;
  const isOp = (v: string): boolean => {
    const t = peek();
    return t.t === 'op' && t.v === v;
  };
  const expect = (v: string): void => {
    const t = next();
    if (t.t !== 'op' || t.v !== v) throw new TsSyntaxError(`a "${v}" was expected here`, t.at);
  };

  const sum = (): TsExpr => {
    let left = product();
    while (isOp('+') || isOp('-')) {
      const op = (next() as { v: string }).v as TsOperator;
      left = { kind: 'binary', op, left, right: product() };
    }
    return left;
  };
  const product = (): TsExpr => {
    let left = unary();
    while (isOp('*') || isOp('/')) {
      const op = (next() as { v: string }).v as TsOperator;
      left = { kind: 'binary', op, left, right: unary() };
    }
    return left;
  };
  const unary = (): TsExpr => {
    if (isOp('-')) {
      next();
      const operand = unary();
      return operand.kind === 'number' ? { kind: 'number', value: -operand.value }
        : { kind: 'binary', op: '*', left: { kind: 'number', value: -1 }, right: operand };
    }
    if (isOp('+')) {
      next();
      return unary();
    }
    return primary();
  };
  const value = (): string | number | boolean => {
    const t = next();
    if (t.t === 'num') return t.v;
    if (t.t === 'str') return t.v;
    if (t.t === 'id') return t.v === 'true' ? true : t.v === 'false' ? false : t.v;
    if (t.t === 'op' && t.v === '-' && peek().t === 'num') return -(next() as { v: number }).v;
    throw new TsSyntaxError('a value was expected here (AAPL, "BRK.B", 42)', t.at);
  };
  const column = (name: string, source?: string): TsExpr => {
    const where: TsMatch[] = [];
    let fn: TsAggregate | undefined;
    if (isOp('[')) {
      next();
      do {
        const t = peek();
        const named = (t.t === 'id') && toks[p + 1]?.t === 'op' && (toks[p + 1] as { v: string }).v === '=';
        if (named) {
          next();
          next();
          where.push({ column: (t as { v: string }).v, value: value() });
        } else {
          where.push({ value: value() });
        }
      } while (isOp(',') && next());
      expect(']');
    }
    if (isOp('.')) {
      next();
      const t = next();
      if (t.t !== 'id' || !AGGREGATES.includes(t.v as TsAggregate)) {
        throw new TsSyntaxError(`a period's value is one of ${AGGREGATES.join(', ')}`, t.at);
      }
      fn = t.v as TsAggregate;
    }
    return {
      kind: 'column', ...(source !== undefined ? { source } : {}), column: name, ...(fn !== undefined ? { fn } : {}),
      ...(where.length > 0 ? { where } : {}),
    };
  };
  const call = (name: string, at: number): TsExpr => {
    const f = functionNamed(name);
    if (!f) {
      const why = NOT_BUILT[name.toLowerCase()];
      throw new TsSyntaxError(why ? `${name}: ${why}` : `there is no function ${name}`, at);
    }
    next();
    const args: TsExpr[] = [];
    const params: Record<string, number | string> = {};
    let positional = 0;
    if (!isOp(')')) {
      do {
        const t = peek();
        const named = t.t === 'id' && toks[p + 1]?.t === 'op' && (toks[p + 1] as { v: string }).v === '=';
        if (named) {
          next();
          next();
          const param = f.params.find((q) => q.name === (t as { v: string }).v);
          if (!param) throw new TsSyntaxError(`${f.name} has no parameter ${(t as { v: string }).v} (it has ${f.params.map((q) => q.name).join(', ') || 'none'})`, t.at);
          params[param.name] = paramValue(param);
          continue;
        }
        if (args.length < f.series) {
          args.push(sum());
          continue;
        }
        const param = f.params[positional++];
        if (!param) throw new TsSyntaxError(`${f.name} takes ${f.series === 1 ? 'one series' : 'two series'}${f.params.length > 0 ? ` and ${f.params.map((q) => q.name).join(', ')}` : ''}`, t.at);
        params[param.name] = paramValue(param);
      } while (isOp(',') && next());
    }
    expect(')');
    if (args.length < f.series) throw new TsSyntaxError(`${f.name} reads ${f.series === 1 ? 'a series' : 'two series'}`, at);
    return { kind: 'call', fn: f.name, args, ...(Object.keys(params).length > 0 ? { params } : {}) };
  };
  const paramValue = (param: TsParam): number | string => {
    const t = next();
    if (param.kind === 'word') {
      const v = t.t === 'id' || t.t === 'str' ? t.v.toLowerCase() : undefined;
      if (v === undefined || !param.words!.includes(v)) throw new TsSyntaxError(`${param.name} is one of ${param.words!.join(', ')}`, t.at);
      return v;
    }
    let n: number | undefined;
    if (t.t === 'num') n = t.v;
    else if (t.t === 'op' && t.v === '-' && peek().t === 'num') n = -(next() as { v: number }).v;
    if (n === undefined) throw new TsSyntaxError(`${param.name} is a number`, t.at);
    if (param.kind === 'whole' && !Number.isInteger(n)) throw new TsSyntaxError(`${param.name} is a whole number`, t.at);
    if (param.min !== undefined && n < param.min) throw new TsSyntaxError(`${param.name} is at least ${param.min}`, t.at);
    return n;
  };
  const primary = (): TsExpr => {
    const t = next();
    if (t.t === 'num') return { kind: 'number', value: t.v };
    if (t.t === 'ref') return { kind: 'ref', series: t.v };
    if (t.t === 'op' && t.v === '(') {
      const inner = sum();
      expect(')');
      return inner;
    }
    if (t.t === 'id') {
      if (!t.quoted && isOp('(')) return call(t.v, t.at);
      // a source's column: `px:close`, `rates:yield[10Y]`
      if (!t.quoted && isOp(':')) {
        if (!ALIAS.test(t.v)) throw new TsSyntaxError(`"${t.v}" is not a source's name`, t.at);
        next();
        const c = next();
        if (c.t !== 'id') throw new TsSyntaxError(`a column of ${t.v} was expected here`, c.at);
        return column(c.v, t.v);
      }
      if (!t.quoted && NOT_BUILT[t.v.toLowerCase()]) throw new TsSyntaxError(`${t.v}: ${NOT_BUILT[t.v.toLowerCase()]}`, t.at);
      return column(t.v);
    }
    if (t.t === 'end') throw new TsSyntaxError('the expression stops before its end', t.at);
    throw new TsSyntaxError(`"${t.t === 'str' ? t.v : (t as { v: string }).v}" was not expected here`, t.at);
  };

  const e = sum();
  const last = peek();
  if (last.t !== 'end') throw new TsSyntaxError('the expression goes on past its end', last.at);
  return e;
}

const WORD = /^[A-Za-z_][A-Za-z0-9_]*$/;
const PRECEDENCE: Readonly<Record<TsOperator, number>> = { '+': 1, '-': 1, '*': 2, '/': 2 };

/** A number as an expression writes it. */
function numberText(n: number): string {
  return Number.isInteger(n) || Math.abs(n) >= 1e-6 ? String(n) : n.toExponential();
}

/** A value of a match as an expression writes it: a word as it is, anything else quoted. */
function valueText(v: string | number | boolean): string {
  if (typeof v !== 'string') return String(v);
  // a word as it is (AAPL, 10Y), unless it would read back as something else: a number, true or false
  return /^[A-Za-z0-9_]+$/.test(v) && !Number.isFinite(Number(v)) && !/^\d+(\.\d*)?e\d*$/i.test(v) && v !== 'true' && v !== 'false'
    ? v : JSON.stringify(v);
}

/** A column's name as an expression writes it: a word as it is, anything else in backticks. */
function columnText(c: string): string {
  return WORD.test(c) && !functionNamed(c) && !AGGREGATES.includes(c as TsAggregate) ? c : `\`${c}\``;
}

/**
 * AN EXPRESSION AS TEXT, the one way it is written (parseExpr reads it back to the same tree): a function's parameters
 * by place, those left at their defaults left off its end.
 */
export function exprText(e: TsExpr, outer = 0): string {
  switch (e.kind) {
    case 'number':
      return numberText(e.value);
    case 'ref':
      return `$${e.series}`;
    case 'column': {
      const where = (e.where ?? []).map((m) => (m.column !== undefined ? `${m.column}=${valueText(m.value)}` : valueText(m.value)));
      return `${e.source !== undefined ? `${e.source}:` : ''}${columnText(e.column)}${where.length > 0 ? `[${where.join(', ')}]` : ''}${e.fn !== undefined ? `.${e.fn}` : ''}`;
    }
    case 'binary': {
      const mine = PRECEDENCE[e.op];
      // a minus or a division binds its right side tighter: a - (b - c), a / (b * c)
      const right = e.op === '-' || e.op === '/' ? mine + 1 : mine;
      const text = `${exprText(e.left, mine)} ${e.op} ${exprText(e.right, right)}`;
      return mine < outer ? `(${text})` : text;
    }
    case 'call': {
      const f = TS_FUNCTION.get(e.fn);
      const args = e.args.map((a) => exprText(a));
      if (f) {
        const told = f.params.map((q) => e.params?.[q.name]);
        let lastTold = told.length - 1;
        while (lastTold >= 0 && (told[lastTold] === undefined || told[lastTold] === f.params[lastTold]!.default)) lastTold--;
        for (let i = 0; i <= lastTold; i++) {
          const v = told[i] ?? f.params[i]!.default;
          args.push(typeof v === 'number' ? numberText(v) : v);
        }
      }
      return `${e.fn}(${args.join(', ')})`;
    }
  }
}

/** A series' name as the legend says it: its own, else its expression's text. */
export function seriesName(s: TsSeries): string {
  return s.name ?? exprText(s.expr);
}

/** The next free series id: the first capital letter no series has (then A2, B2, ...). */
export function nextSeriesId(series: readonly TsSeries[]): string {
  const taken = new Set(series.map((s) => s.id));
  for (let round = 1; ; round++) {
    for (let c = 65; c <= 90; c++) {
      const id = round === 1 ? String.fromCharCode(c) : `${String.fromCharCode(c)}${round}`;
      if (!taken.has(id)) return id;
    }
  }
}

// ------------------------------------------------------------------ the reader

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

const TS_KEYS: Readonly<Record<keyof TimeseriesSpec, true>> = {
  sources: true, grain: true, series: true, range: true, panes: true, annotations: true, view: true,
};
const SOURCE_KEYS: Readonly<Record<keyof TsSource, true>> = { alias: true, cube: true, date: true, key: true };
const SERIES_KEYS: Readonly<Record<keyof TsSeries, true>> = {
  id: true, expr: true, name: true, split: true, style: true, pane: true, axis: true, hidden: true,
};
const STYLE_KEYS: Readonly<Record<keyof TsStyle, true>> = { kind: true, color: true, width: true, dash: true };
const PANE_KEYS: Readonly<Record<keyof TsPane, true>> = { height: true, log: true };
const VIEW_KEYS: Readonly<Record<keyof TsView, true>> = {
  compare: true, legend: true, lastValues: true, navigator: true, stats: true, crosshair: true,
};
const MATCH_KEYS: Readonly<Record<keyof TsMatch, true>> = { column: true, value: true };
const EXPR_KEYS: { readonly [K in TsExpr['kind']]: Readonly<Record<string, true>> } = {
  column: { kind: true, source: true, column: true, fn: true, where: true },
  ref: { kind: true, series: true },
  number: { kind: true, value: true },
  binary: { kind: true, op: true, left: true, right: true },
  call: { kind: true, fn: true, args: true, params: true },
};
const ANNOTATION_KEYS: { readonly [K in TsAnnotationKind]: Readonly<Record<string, true>> } = {
  level: { kind: true, value: true, pane: true, label: true },
  event: { kind: true, date: true, label: true },
  period: { kind: true, from: true, to: true, label: true },
};
const GRAINS: readonly TimeGrain[] = ['day', 'week', 'month', 'quarter', 'year'];
const STYLE_KINDS: readonly TsStyleKind[] = ['line', 'area', 'bars', 'step', 'dots'];
const DATE = /^\d{4}-\d{2}-\d{2}$/;

function unknownKey(o: Readonly<Record<string, unknown>>, keys: Readonly<Record<string, true>>, what: string): string | null {
  const extra = Object.keys(o).find((k) => !Object.hasOwn(keys, k));
  return extra === undefined ? null : `there is no ${JSON.stringify(extra)} in ${what} (it says ${Object.keys(keys).join(', ')})`;
}

function exprProblem(e: unknown, what: string, depth = 0): string | null {
  if (depth > 64) return `${what} is nested too deep`;
  if (!isRecord(e) || typeof e['kind'] !== 'string' || !Object.hasOwn(EXPR_KEYS, e['kind'])) {
    return `${what} is not an expression (a column, a ref, a number, a binary or a call)`;
  }
  const kind = e['kind'] as TsExpr['kind'];
  const extra = unknownKey(e, EXPR_KEYS[kind], `${what} (a ${kind})`);
  if (extra) return extra;
  switch (kind) {
    case 'column': {
      if (typeof e['column'] !== 'string' || e['column'] === '') return `${what} names no column`;
      if (e['source'] !== undefined && (typeof e['source'] !== 'string' || !ALIAS.test(e['source']))) {
        return `${what}'s source ${JSON.stringify(e['source'])} is not a source's alias (a word)`;
      }
      if (e['fn'] !== undefined && !AGGREGATES.includes(e['fn'] as TsAggregate)) {
        return `${what}'s period value ${JSON.stringify(e['fn'])} is not one of ${AGGREGATES.join(', ')}`;
      }
      const where = e['where'];
      if (where !== undefined) {
        if (!Array.isArray(where)) return `${what}'s matches are not a list`;
        for (const [i, m] of where.entries()) {
          if (!isRecord(m) || !['string', 'number', 'boolean'].includes(typeof m['value'])
            || (m['column'] !== undefined && typeof m['column'] !== 'string')) {
            return `${what}'s match ${i + 1} is not a value (and a column)`;
          }
          const mExtra = unknownKey(m, MATCH_KEYS, `${what}'s match ${i + 1}`);
          if (mExtra) return mExtra;
        }
      }
      return null;
    }
    case 'ref':
      return typeof e['series'] === 'string' && e['series'] !== '' ? null : `${what} names no series`;
    case 'number':
      return typeof e['value'] === 'number' && Number.isFinite(e['value']) ? null : `${what} is not a finite number`;
    case 'binary':
      if (!['+', '-', '*', '/'].includes(e['op'] as string)) return `${what}'s operator ${JSON.stringify(e['op'])} is not +, -, * or /`;
      return exprProblem(e['left'], `${what}'s left side`, depth + 1) ?? exprProblem(e['right'], `${what}'s right side`, depth + 1);
    case 'call': {
      const f = typeof e['fn'] === 'string' ? TS_FUNCTION.get(e['fn'] as TsFunctionName) : undefined;
      if (!f) {
        const why = typeof e['fn'] === 'string' ? NOT_BUILT[e['fn']] : undefined;
        return `${what} calls ${JSON.stringify(e['fn'] ?? null)}, ${why ?? 'which this DataCube does not know'}`;
      }
      if (!Array.isArray(e['args']) || e['args'].length !== f.series) {
        return `${what}: ${f.name} reads ${f.series === 1 ? 'one series' : 'two series'}`;
      }
      for (const [i, a] of e['args'].entries()) {
        const p = exprProblem(a, `${f.name}'s series ${i + 1}`, depth + 1);
        if (p) return p;
      }
      const params = e['params'];
      if (params !== undefined) {
        if (!isRecord(params)) return `${what}'s parameters are not an object`;
        for (const [name, v] of Object.entries(params)) {
          const q = f.params.find((x) => x.name === name);
          if (!q) return `${f.name} has no parameter ${JSON.stringify(name)} (it has ${f.params.map((x) => x.name).join(', ') || 'none'})`;
          if (q.kind === 'word') {
            if (!q.words!.includes(v as string)) return `${f.name}'s ${name} ${JSON.stringify(v)} is not one of ${q.words!.join(', ')}`;
          } else {
            if (typeof v !== 'number' || !Number.isFinite(v)) return `${f.name}'s ${name} ${JSON.stringify(v)} is not a number`;
            if (q.kind === 'whole' && !Number.isInteger(v)) return `${f.name}'s ${name} ${v} is not a whole number`;
            if (q.min !== undefined && v < q.min) return `${f.name}'s ${name} ${v} is below ${q.min}`;
          }
        }
      }
      return null;
    }
  }
}

/** The aliases an expression's columns name, each once (a column naming none reads the first source). */
function columnSourcesOf(e: TsExpr): string[] {
  const out = new Set<string>();
  const walk = (x: TsExpr): void => {
    if (x.kind === 'column' && x.source !== undefined) out.add(x.source);
    else if (x.kind === 'binary') {
      walk(x.left);
      walk(x.right);
    } else if (x.kind === 'call') x.args.forEach(walk);
  };
  walk(e);
  return [...out];
}

/** A date as a spec writes it, an ISO day. */
const isDate = (v: unknown): v is string => typeof v === 'string' && DATE.test(v) && !Number.isNaN(Date.parse(v));

/**
 * What stops a saved `ts` slot from being read, in words, or null: every key, word and number, a newer DataCube's
 * refused by name -- never ignored, never drawn as something else (chart-spec.ts specProblem's rule).
 */
export function tsProblem(ts: unknown): string | null {
  if (!isRecord(ts)) return 'its timeseries is not an object';
  const extra = unknownKey(ts, TS_KEYS, 'its timeseries');
  if (extra) return extra;
  const sources = ts['sources'];
  if (!Array.isArray(sources) || sources.length === 0) return 'its timeseries reads no grid (sources)';
  if (sources.length > MAX_TS_SOURCES) return `a timeseries reads at most ${MAX_TS_SOURCES} grids (it reads ${sources.length})`;
  const aliases = new Set<string>();
  for (const [i, src] of sources.entries()) {
    const what = `source ${i + 1}`;
    if (!isRecord(src)) return `${what} is not an object`;
    const srcExtra = unknownKey(src, SOURCE_KEYS, what);
    if (srcExtra) return srcExtra;
    if (typeof src['alias'] !== 'string' || !ALIAS.test(src['alias'])) return `${what}'s alias ${JSON.stringify(src['alias'] ?? null)} is not a word`;
    if (aliases.has(src['alias'])) return `two sources are called ${src['alias']}`;
    aliases.add(src['alias']);
    if (typeof src['cube'] !== 'string' || src['cube'] === '') return `source ${src['alias']} names no cube`;
    if (typeof src['date'] !== 'string' || src['date'] === '') return `source ${src['alias']} names no date column`;
    if (src['key'] !== undefined && (typeof src['key'] !== 'string' || src['key'] === '')) return `source ${src['alias']}'s key is not a column`;
  }
  if (ts['grain'] !== undefined && !GRAINS.includes(ts['grain'] as TimeGrain)) {
    return `its timeseries' frequency ${JSON.stringify(ts['grain'])} is not one of ${GRAINS.join(', ')}`;
  }
  const series = ts['series'];
  if (!Array.isArray(series)) return 'its timeseries\' series are not a list';
  if (series.length > MAX_TS_SERIES) return `a timeseries plots at most ${MAX_TS_SERIES} series (it has ${series.length})`;
  const ids = new Set<string>();
  for (const [i, s] of series.entries()) {
    const what = `series ${i + 1}`;
    if (!isRecord(s)) return `${what} is not an object`;
    const sExtra = unknownKey(s, SERIES_KEYS, what);
    if (sExtra) return sExtra;
    if (typeof s['id'] !== 'string' || !/^[A-Z][A-Z0-9]*$/.test(s['id'])) return `${what}'s id ${JSON.stringify(s['id'] ?? null)} is not a capital letter (A, B, ...)`;
    if (ids.has(s['id'])) return `two series are ${s['id']}`;
    ids.add(s['id']);
    const p = exprProblem(s['expr'], `series ${s['id']}`);
    if (p) return p;
    const named = columnSourcesOf(s['expr'] as TsExpr).find((a) => !aliases.has(a));
    if (named !== undefined) return `series ${s['id']} reads ${named}:..., which is none of its sources (${[...aliases].join(', ')})`;
    if (s['name'] !== undefined && typeof s['name'] !== 'string') return `series ${s['id']}'s name is not text`;
    if (s['split'] !== undefined && typeof s['split'] !== 'string') return `series ${s['id']}'s split is not a column`;
    if (s['pane'] !== undefined && !(Number.isInteger(s['pane']) && (s['pane'] as number) >= 0 && (s['pane'] as number) < MAX_PANES)) {
      return `series ${s['id']}'s pane ${JSON.stringify(s['pane'])} is not one of 0 to ${MAX_PANES - 1}`;
    }
    if (s['axis'] !== undefined && s['axis'] !== 'left' && s['axis'] !== 'right') return `series ${s['id']}'s axis ${JSON.stringify(s['axis'])} is not left or right`;
    if (s['hidden'] !== undefined && typeof s['hidden'] !== 'boolean') return `series ${s['id']}'s hidden is not true or false`;
    const style = s['style'];
    if (style !== undefined) {
      if (!isRecord(style)) return `series ${s['id']}'s style is not an object`;
      const stExtra = unknownKey(style, STYLE_KEYS, `series ${s['id']}'s style`);
      if (stExtra) return stExtra;
      if (style['kind'] !== undefined && !STYLE_KINDS.includes(style['kind'] as TsStyleKind)) {
        return `series ${s['id']}'s style ${JSON.stringify(style['kind'])} is not one of ${STYLE_KINDS.join(', ')}`;
      }
      if (style['color'] !== undefined && !(Number.isInteger(style['color']) && (style['color'] as number) >= 0)) {
        return `series ${s['id']}'s colour ${JSON.stringify(style['color'])} is not a slot of the palette (0, 1, ...)`;
      }
      if (style['width'] !== undefined && !(typeof style['width'] === 'number' && style['width'] >= 1 && style['width'] <= 4)) {
        return `series ${s['id']}'s width ${JSON.stringify(style['width'])} is not 1 to 4`;
      }
      if (style['dash'] !== undefined && !['solid', 'dashed', 'dotted'].includes(style['dash'] as string)) {
        return `series ${s['id']}'s dash ${JSON.stringify(style['dash'])} is not solid, dashed or dotted`;
      }
    }
  }
  const range = ts['range'];
  if (range !== undefined) {
    if (!isRecord(range)) return 'its timeseries\' range is not an object';
    if (range['last'] !== undefined) {
      if (Object.keys(range).length !== 1) return 'its timeseries\' range is a period back (last) or two dates (from, to), not both';
      if (!TS_RANGES.some((r) => r.word === range['last'])) return `its timeseries' range ${JSON.stringify(range['last'])} is not one of ${TS_RANGES.map((r) => r.word).join(', ')}`;
    } else {
      const rExtra = unknownKey(range, { from: true, to: true }, 'its timeseries\' range');
      if (rExtra) return rExtra;
      for (const end of ['from', 'to'] as const) {
        if (range[end] !== undefined && !isDate(range[end])) return `its timeseries' range's ${end} ${JSON.stringify(range[end])} is not a date (2024-01-31)`;
      }
      if (typeof range['from'] === 'string' && typeof range['to'] === 'string' && range['from'] > range['to']) {
        return 'its timeseries\' range ends before it starts';
      }
    }
  }
  const panes = ts['panes'];
  if (panes !== undefined) {
    if (!Array.isArray(panes) || panes.length > MAX_PANES) return `its timeseries' panes are not a list of at most ${MAX_PANES}`;
    for (const [i, pane] of panes.entries()) {
      if (!isRecord(pane)) return `pane ${i} is not an object`;
      const pExtra = unknownKey(pane, PANE_KEYS, `pane ${i}`);
      if (pExtra) return pExtra;
      if (pane['height'] !== undefined && !(typeof pane['height'] === 'number' && pane['height'] > 0 && pane['height'] <= 10)) {
        return `pane ${i}'s height ${JSON.stringify(pane['height'])} is not a share from above 0 to 10`;
      }
      if (pane['log'] !== undefined && typeof pane['log'] !== 'boolean') return `pane ${i}'s log is not true or false`;
    }
  }
  const annotations = ts['annotations'];
  if (annotations !== undefined) {
    if (!Array.isArray(annotations)) return 'its timeseries\' annotations are not a list';
    for (const [i, a] of annotations.entries()) {
      const what = `annotation ${i + 1}`;
      if (!isRecord(a) || !Object.hasOwn(ANNOTATION_KEYS, a['kind'] as string)) return `${what} is not a level, an event or a period`;
      const aExtra = unknownKey(a, ANNOTATION_KEYS[a['kind'] as TsAnnotationKind], `${what} (a ${a['kind']})`);
      if (aExtra) return aExtra;
      if (a['label'] !== undefined && typeof a['label'] !== 'string') return `${what}'s label is not text`;
      if (a['kind'] === 'level') {
        if (typeof a['value'] !== 'number' || !Number.isFinite(a['value'])) return `${what} has no value`;
        if (a['pane'] !== undefined && !(Number.isInteger(a['pane']) && (a['pane'] as number) >= 0 && (a['pane'] as number) < MAX_PANES)) {
          return `${what}'s pane is not one of 0 to ${MAX_PANES - 1}`;
        }
      }
      if (a['kind'] === 'event' && !isDate(a['date'])) return `${what}'s date ${JSON.stringify(a['date'] ?? null)} is not a date (2024-01-31)`;
      if (a['kind'] === 'period') {
        if (!isDate(a['from']) || !isDate(a['to'])) return `${what} needs a from and a to, each a date (2024-01-31)`;
        if (a['from'] > a['to']) return `${what} ends before it starts`;
      }
    }
  }
  const view = ts['view'];
  if (view !== undefined) {
    if (!isRecord(view)) return 'its timeseries\' view is not an object';
    const vExtra = unknownKey(view, VIEW_KEYS, 'its timeseries\' view');
    if (vExtra) return vExtra;
    if (view['compare'] !== undefined && view['compare'] !== 'rebase' && view['compare'] !== 'percent') {
      return `its timeseries' compare ${JSON.stringify(view['compare'])} is not rebase or percent`;
    }
    for (const flag of ['legend', 'lastValues', 'navigator', 'stats', 'crosshair'] as const) {
      if (view[flag] !== undefined && typeof view[flag] !== 'boolean') return `its timeseries' ${flag} is not true or false`;
    }
  }
  return null;
}

// ------------------------------------------------------------------ against the grid

/** A grid's column as a timeseries reads it: its name and its type as the compiler types it. */
export interface TsColumn {
  readonly name: string;
  readonly type: string | undefined;
}

/**
 * What stops a timeseries from being drawn over its sources' columns, in words; empty when it can be. `columns`: each
 * source's columns by its alias, as its grid has them now -- undefined for a source whose grid the page cannot give
 * (its cube gone). Each source's date a date, its key there; each series reading what its sources have -- a value a
 * number, its refs there and not round in a circle, of one split, its split a column of the source it reads first --
 * a band nothing reads.
 */
export function timeseriesProblems(ts: TimeseriesSpec, columns: ReadonlyMap<string, readonly TsColumn[] | undefined>): string[] {
  const out: string[] = [];
  const types = new Map<string, Map<string, string | undefined>>();
  for (const src of ts.sources) {
    const cols = columns.get(src.alias);
    if (cols === undefined) {
      out.push(`Source ${src.alias}'s grid is not on the page.`);
      continue;
    }
    const typeOf = new Map(cols.map((c) => [c.name, c.type]));
    types.set(src.alias, typeOf);
    const dateType = typeOf.get(src.date);
    if (!typeOf.has(src.date)) out.push(`Source ${src.alias} has no column '${src.date}' to read its series along.`);
    else if (!isTemporal(dateType) || isTimeOfDay(dateType)) out.push(`Source ${src.alias}'s '${src.date}' is not a date: a timeseries is read along a date or a date-time.`);
    if (src.key !== undefined && !typeOf.has(src.key)) out.push(`Source ${src.alias} has no column '${src.key}' to name its instruments by.`);
  }
  if (ts.series.length === 0) out.push('Add a series to plot.');
  for (const s of ts.series) {
    const name = `Series ${s.id}`;
    for (const ref of refsOf(s.expr)) {
      const other = ts.series.find((x) => x.id === ref);
      if (!other) {
        out.push(`${name} reads $${ref}, which there is not.`);
        continue;
      }
      if ((other.split ?? null) !== (s.split ?? null)) out.push(`${name} reads $${ref}, split ${other.split === undefined ? 'by nothing' : `by '${other.split}'`}: a series reads only series split as it is.`);
      if (isBand(other.expr)) out.push(`${name} reads $${ref}, a band: a band is drawn, never read.`);
    }
    let whole: TsExpr;
    try {
      whole = inlined(s.expr, ts.series);
    } catch (e) {
      out.push(`${name}: ${e instanceof Error ? e.message : String(e)}.`);
      continue;
    }
    const read = sourcesOf(ts, whole);
    for (const alias of read) {
      if (!ts.sources.some((src) => src.alias === alias)) out.push(`${name} reads ${alias}:..., which is none of the tile's sources.`);
    }
    for (const [alias, cs] of columnsOf(ts, whole)) {
      const typeOf = types.get(alias);
      if (!typeOf) continue;
      for (const c of cs) if (!typeOf.has(c)) out.push(`${name} reads '${c}', which source ${alias} has not.`);
    }
    if (s.split !== undefined) {
      const first = read[0];
      const typeOf = first === undefined ? undefined : types.get(first);
      const date = ts.sources.find((src) => src.alias === first)?.date;
      if (typeOf && !typeOf.has(s.split)) out.push(`${name} is split by '${s.split}', which source ${first} -- the one it reads first -- has not.`);
      if (s.split === date) out.push(`${name} is split by its own date column.`);
      if (read.length === 0) out.push(`${name} is split by '${s.split}' but reads no column.`);
    }
    const walk = (e: TsExpr, top: boolean): void => {
      if (e.kind === 'column') {
        const alias = sourceAlias(ts, e);
        const typeOf = alias === undefined ? undefined : types.get(alias);
        if (typeOf?.has(e.column) && !isNumeric(typeOf.get(e.column))) out.push(`${name} plots '${e.column}', which is not a number.`);
        const src = ts.sources.find((x) => x.alias === alias);
        if (src && src.key === undefined && (e.where ?? []).some((m) => m.column === undefined)) {
          out.push(`${name} matches a value with no column (close[AAPL]): choose source ${src.alias}'s instrument column (Key).`);
        }
      } else if (e.kind === 'binary') {
        walk(e.left, false);
        walk(e.right, false);
      } else if (e.kind === 'call') {
        if (!top && TS_FUNCTION.get(e.fn)?.band) out.push(`${name} reads a Bollinger band: a band is drawn, never read.`);
        e.args.forEach((a) => walk(a, false));
      }
    };
    walk(whole, true);
  }
  return [...new Set(out)];
}

// ------------------------------------------------------------------ a first source, a first timeseries

/** Words a column's name has when it is a price, most likely first: the default series reads the first that matches. */
const PRICE_WORDS = ['close', 'adj_close', 'price', 'last', 'px', 'value', 'level', 'rate', 'yield', 'spot', 'mid', 'nav'];
/** Words a column's name has when it names the instrument. */
const KEY_WORDS = ['symbol', 'ticker', 'instrument', 'asset', 'security', 'tenor', 'pair', 'name', 'id', 'code', 'series'];

const named = (words: readonly string[], among: readonly TsColumn[]): TsColumn | undefined => {
  for (const w of words) {
    const hit = among.find((c) => c.name.toLowerCase() === w) ?? among.find((c) => c.name.toLowerCase().includes(w));
    if (hit) return hit;
  }
  return undefined;
};

/**
 * A GRID AS A SOURCE: read along its first date column, its instruments named by its first text column the grid groups
 * by, else the first named like one (symbol, ticker, tenor, ...). Null when it has no date.
 */
export function sourceOf(columns: readonly TsColumn[], rows: readonly string[], cube: string, alias: string): TsSource | null {
  const date = columns.find((c) => isTemporal(c.type) && !isTimeOfDay(c.type));
  if (!date) return null;
  const texts = columns.filter((c) => !isNumeric(c.type) && !isTemporal(c.type) && c.name !== date.name);
  const key = texts.find((c) => rows.includes(c.name)) ?? named(KEY_WORDS, texts);
  return { alias, cube, date: date.name, ...(key ? { key: key.name } : {}) };
}

/** The value a first series of a source plots: the first number named like a price (close, price, ...), else its first. */
export function firstValue(columns: readonly TsColumn[], source: TsSource): string | undefined {
  const numbers = columns.filter((c) => isNumeric(c.type) && c.name !== source.date);
  return (named(PRICE_WORDS, numbers) ?? numbers[0])?.name;
}

/**
 * A FIRST TIMESERIES OF A GRID: the grid its one source (sourceOf), its first series that source's price-like value, one
 * line per instrument when it names them. Null when the grid has no date or no number.
 */
export function defaultTimeseries(columns: readonly TsColumn[], rows: readonly string[], cube: string, alias: string): TimeseriesSpec | null {
  const source = sourceOf(columns, rows, cube, alias);
  if (!source) return null;
  const value = firstValue(columns, source);
  if (value === undefined) return null;
  return {
    sources: [source],
    series: [{ id: 'A', expr: columnRef(value), ...(source.key ? { split: source.key } : {}) }],
  };
}

/**
 * A saved tile's spec as a page reads it (page-document.ts), and as its commands take one (page-commands.ts): the spec,
 * or what stops it from being read, in words (tsProblem). Python's are read the same, headless: one reader.
 */
export function readTimeseries(spec: unknown): TimeseriesSpec | string {
  const problem = tsProblem(spec);
  return problem !== null ? problem : spec as TimeseriesSpec;
}

/** The cubes a tile reads, each once, in its sources' order: what the page keeps its sources' references to. */
export function cubesOf(ts: Pick<TimeseriesSpec, 'sources'>): string[] {
  return [...new Set(ts.sources.map((s) => s.cube))];
}
