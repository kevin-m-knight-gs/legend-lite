// A chart spec and its rows, as a drawing: the ECharts option.
//
// PURE and library-free: this builds a plain object and imports nothing
// from ECharts but its types, so it is tested in node without a DOM
// (chart-render.ts is the one file that draws).
//
// The rows arrive AGGREGATED by the database (`chart-spec.ts`); nothing
// here sums or counts. What it does is presentation: lay the rows out as
// categories and series, and -- for a percent stack only -- scale each
// category's values to its own total.
//
// Marks follow the dataviz rules: bars no wider than 24px with a 4px
// rounded data-end, 2px lines, 8px markers, a 2px surface gap between
// touching fills, hairline recessive grid, ink-coloured text, a legend
// only when there are two series or more, a hover tooltip on every mark,
// and ONE value axis (never two).

import type { EChartsOption } from 'echarts';
import { axisName, drilledView, KPI_TREND_LIMIT, measureName, type ChartMeasure, type ChartSpec, type LayerStat, type MeasureMark } from './chart-spec.ts';
import { isRatio, SHOW_AS_LABELS, statName } from './calc-presets.ts';
import type { ResultTable, Scalar } from '../../engine-client/src/result.ts';
import { numberOf } from '../../engine-client/src/values.ts';
import { UI_LOCALE } from '../../engine-client/src/locale.ts';
import { isNumeric, isTemporal } from '../../engine-client/src/types.ts';
import { TREE_COLUMN } from './treeview.ts';

/** The colours a chart is drawn in, read from CSS tokens by the renderer. */
export interface ChartTheme {
  readonly surface: string;
  readonly ink: string;
  readonly inkSecondary: string;
  readonly muted: string;
  readonly grid: string;
  readonly axis: string;
  /** Categorical slots, in their fixed order. */
  readonly series: readonly string[];
  /** One hue, light to dark, for a heatmap's magnitude. */
  readonly sequential: readonly string[];
  /** The colour of a value on the wrong side of a threshold (and a pinned minimum). */
  readonly bad?: string;
}

/** The reference palette's light mode (dataviz references/palette.md). */
export const LIGHT_THEME: ChartTheme = {
  surface: '#fcfcfb',
  ink: '#0b0b0b',
  inkSecondary: '#52514e',
  muted: '#898781',
  grid: '#e1e0d9',
  axis: '#c3c2b7',
  series: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948'],
  sequential: ['#cde2fb', '#86b6ef', '#3987e5', '#256abf', '#184f95', '#0d366b'],
  bad: '#e34948',
};

/**
 * The theme, from the element's CSS tokens: `--dc-chart-*`, falling back
 * to the reference palette. Canvas cannot read CSS variables itself, so
 * they are read here, once per draw.
 */
export function themeOf(el: Element): ChartTheme {
  const view = el.ownerDocument.defaultView;
  const css = view ? view.getComputedStyle(el) : undefined;
  const read = (name: string, fallback: string): string =>
    css?.getPropertyValue(name).trim() || fallback;
  const list = (name: string, fallback: readonly string[]): string[] => {
    const v = read(name, '');
    return v ? v.split(',').map((s) => s.trim()).filter(Boolean) : [...fallback];
  };
  return {
    surface: read('--dc-chart-surface', LIGHT_THEME.surface),
    ink: read('--dc-chart-ink', LIGHT_THEME.ink),
    inkSecondary: read('--dc-chart-ink-secondary', LIGHT_THEME.inkSecondary),
    muted: read('--dc-chart-muted', LIGHT_THEME.muted),
    grid: read('--dc-chart-grid', LIGHT_THEME.grid),
    axis: read('--dc-chart-axis', LIGHT_THEME.axis),
    series: list('--dc-chart-series', LIGHT_THEME.series),
    sequential: list('--dc-chart-sequential', LIGHT_THEME.sequential),
    // the KPI's own bad colour, the page's one word for a value on the wrong side
    bad: read('--dc-chart-bad', read('--dc-kpi-bad', LIGHT_THEME.bad!)),
  };
}

/** At most this many series; past it the chart says so rather than invent a colour. */
export const MAX_SERIES = 8;
/** A scatter's points overlap, and only the first three slots stay apart for every viewer. */
export const MAX_SCATTER_SERIES = 3;

/** How a value is written where the reader sees it (the grid's own formatter). */
export type LabelOf = (value: Scalar, column: string, type: string | undefined) => string;

/**
 * A column's unit as the grid formats it (its kind, currency and unit: `currency|USD|`), or undefined when the grid
 * gives it no format: two measures of different units take two value axes (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md
 * §6).
 */
export type UnitOf = (column: string) => string | undefined;

const defaultLabel: LabelOf = (v) => (v === null ? '(empty)' : String(v));

/**
 * An option built here, as ECharts' type. Its declared types are stricter than
 * `exactOptionalPropertyTypes` can satisfy for unions of series; what the
 * options DRAW is pinned by the SVG render tests instead.
 */
const asOption = (o: object): EChartsOption => o as EChartsOption;

/** A clicked mark, as the column values it stands for. */
export type MarkKey = Readonly<Record<string, Scalar>>;

/** One area of ECharts' brush, as its event gives it: a range across (lineX / lineY) or a rectangle (rect). */
export interface BrushArea {
  readonly brushType?: string;
  readonly coordRange?: readonly unknown[];
}

/** What a brush took: the categories in a range across, or a scatter's rectangle of values. */
export type Brushed =
  | { readonly kind: 'across'; readonly values: readonly Scalar[]; readonly upper?: Scalar }
  | { readonly kind: 'rect'; readonly x: readonly [number, number]; readonly y: readonly [number, number] };

export interface ChartDrawing {
  readonly option: EChartsOption;
  /**
   * The values behind a clicked mark, or null when a mark is not a group (a scatter point).
   * `datum` is the clicked item itself, for a mark whose index is not enough (a treemap's node).
   */
  keyAt(seriesIndex: number, dataIndex: number, datum?: unknown): MarkKey | null;
  /**
   * For a chart of bins: the edge of the bin after the clicked one (the least category above it), so a click filters
   * to the bin's range; undefined for the last bin, or a chart that is not of bins.
   */
  upperAt?(dataIndex: number): Scalar | undefined;
  /**
   * What a brush took (batch 2 item 11), from ECharts' brush areas: the categories inside a range across (in order,
   * with the edge after the last for a chart of bins), or a scatter's rectangle. Null when nothing was taken.
   */
  brushed?(areas: readonly BrushArea[]): Brushed | null;
  /** Anything the reader should know that the picture cannot say. */
  readonly notes: readonly string[];
  /** The chart in words, for a screen reader (the container's aria-label). */
  readonly description: string;
}

/** One column of the result, with its type. */
function column(t: ResultTable, name: string): { values: readonly Scalar[]; type: string | undefined } | undefined {
  const c = t.columns.find((x) => x.name === name);
  return c ? { values: c.values as readonly Scalar[], type: c.type } : undefined;
}

/** Distinct values in first-seen order, with the value itself kept (not its text). */
function distinct(values: readonly Scalar[]): Scalar[] {
  const seen = new Set<string>();
  const out: Scalar[] = [];
  for (const v of values) {
    const k = keyText(v);
    if (!seen.has(k)) {
      seen.add(k);
      out.push(v);
    }
  }
  return out;
}

function keyText(v: Scalar): string {
  return v === null ? '\u0000null' : `${typeof v}:${String(v)}`;
}

export function chartOption(
  drilled: ChartSpec,
  rows: ResultTable,
  theme: ChartTheme = LIGHT_THEME,
  label: LabelOf = defaultLabel,
  unit?: UnitOf,
): ChartDrawing {
  // drilled, the chart draws the level after its path (its query asked for that level)
  const spec = drilledView(drilled).spec;
  const notes: string[] = [];
  const text = { color: theme.inkSecondary, fontFamily: 'inherit' };
  const axisCommon = {
    axisLine: { lineStyle: { color: theme.axis } },
    axisTick: { show: false },
    axisLabel: { color: theme.muted, hideOverlap: true },
    splitLine: { lineStyle: { color: theme.grid, width: 1, type: 'solid' as const } },
  };
  const tooltip = {
    backgroundColor: theme.surface,
    borderColor: theme.axis,
    textStyle: { color: theme.ink },
    confine: true,
  };
  const base: EChartsOption = {
    backgroundColor: 'transparent',
    textStyle: text,
    color: [...theme.series],
    // ECharts' decal patterns stay available, but its own generated
    // description ("the data for EMEA is 0, 15054092") is off: the
    // container carries ours (`description`).
    aria: { enabled: true, label: { enabled: false } },
    grid: { left: 8, right: 16, top: 40, bottom: 8, containLabel: true },
  };

  // the categories come from the axis's own column (a date's month, a number's bin); a click is said back on `x`
  const across = spec.mark === 'scatter' ? spec.x : axisName(spec);
  const xCol = across !== undefined ? column(rows, across) : undefined;
  const first = spec.y[0];
  if (!xCol || !first) {
    return { option: base, keyAt: () => null, notes: ['Nothing to draw.'], description: 'An empty chart.' };
  }

  // -- scatter: rows are points, x and y both values --------------------
  if (spec.mark === 'scatter') {
    const yCol = column(rows, first.column);
    const splitCol = spec.split !== undefined ? column(rows, spec.split) : undefined;
    const numericX = xCol.values.some((v) => numberOf(v, xCol.type) !== null);
    const groups = splitCol ? distinct(splitCol.values) : [null];
    if (groups.length > MAX_SCATTER_SERIES) {
      notes.push(`Showing the first ${MAX_SCATTER_SERIES} of ${groups.length} groups: more cannot be told apart.`);
    }
    const shown = groups.slice(0, MAX_SCATTER_SERIES);
    const series = shown.map((g) => ({
      type: 'scatter' as const,
      name: splitCol ? label(g, spec.split!, splitCol.type) : first.column,
      symbolSize: 8,
      itemStyle: { borderColor: theme.surface, borderWidth: 2 },
      data: xCol.values.flatMap((xv, i) => {
        if (splitCol && keyText(splitCol.values[i] ?? null) !== keyText(g)) return [];
        const yv = numberOf(yCol?.values[i] ?? null, yCol?.type);
        const xn = numericX ? numberOf(xv, xCol.type) : label(xv, spec.x!, xCol.type);
        return yv === null || xn === null ? [] : [[xn, yv]];
      }),
    }));
    return {
      option: asOption({
        ...base,
        tooltip: { ...tooltip, trigger: 'item' },
        legend: legendOf(spec, series.length, theme),
        xAxis: { ...axisCommon, type: numericX ? 'value' : 'category', name: spec.x, nameTextStyle: text, scale: true },
        yAxis: { ...axisCommon, type: 'value', name: first.column, nameTextStyle: text, scale: true },
        ...(spec.options.brush && numericX ? { brush: { xAxisIndex: 0, yAxisIndex: 0, brushType: 'rect', ...BRUSH_STYLE(theme) }, ...brushToolbox('rect') } : {}),
        series,
      }),
      keyAt: () => null,
      // a rectangle of values: x from-to and y from-to
      ...(spec.options.brush && numericX ? { brushed: (areas: readonly BrushArea[]) => {
        const r = areas[0]?.coordRange as readonly (readonly number[])[] | undefined;
        const [x, y] = r ?? [];
        if (!x || !y || x.length < 2 || y.length < 2) return null;
        return { kind: 'rect' as const, x: [x[0]!, x[1]!] as const, y: [y[0]!, y[1]!] as const };
      } } : {}),
      notes,
      description: describe(spec, rows),
    };
  }

  const measure = (m: ChartSpec['y'][number]) => column(rows, measureName(m));
  // A DATE ACROSS IS A TIME SCALE (batch 2 item 9) on a bar, line or area chart: its points in time order whatever
  // the chart's order, spaced by time, so a missing period is a gap, not a neighbour
  const timeAxis = isTemporal(xCol.type) && (spec.mark === 'line' || spec.mark === 'area' || spec.mark === 'bar')
    && !(spec.mark === 'bar' && spec.options.orientation === 'horizontal');
  const firstSeen = distinct(xCol.values);
  const categories = timeAxis ? [...firstSeen].sort((a, b) => keyText(a).localeCompare(keyText(b))) : firstSeen;
  const catLabels = categories.map((c) => label(c, spec.x!, xCol.type));
  const indexOf = new Map(categories.map((c, i) => [keyText(c), i]));

  // -- pie: one category per slice ---------------------------------------
  if (spec.mark === 'pie') {
    const m = measure(first);
    const data = categories.map((c, i) => {
      const at = xCol.values.findIndex((v) => keyText(v) === keyText(c));
      return { name: catLabels[i]!, value: numberOf(m?.values[at] ?? null, m?.type) ?? 0 };
    });
    if (data.length > MAX_SERIES) {
      notes.push(`${data.length} slices: a bar reads more than ${MAX_SERIES} categories better than a pie.`);
    }
    return {
      option: asOption({
        ...base,
        tooltip: { ...tooltip, trigger: 'item' },
        legend: legendOf(spec, data.length, theme),
        series: [{
          type: 'pie',
          radius: ['45%', '72%'],
          itemStyle: { borderColor: theme.surface, borderWidth: 2, borderRadius: 4 },
          label: { show: spec.options.labels, color: theme.inkSecondary },
          data,
        }],
      }),
      keyAt: (_s, i) => (categories[i] === undefined ? null : { [spec.x!]: categories[i]! }),
      notes,
      description: describe(spec, rows),
    };
  }

  // a treemap draws the GRID'S rows as shown, not a query of its own (`treemapOfGrid`)
  if (spec.mark === 'treemap') {
    throw new Error('a treemap draws the grid\'s rows as shown: treemapOfGrid, not chartOption');
  }
  // a KPI is a number, said as the grid writes it, and its trend (`kpiDrawing`)
  if (spec.mark === 'kpi') throw new Error('a KPI is drawn by kpiDrawing, not chartOption');

  // -- heatmap: x across, split down, the first measure as colour ---------
  if (spec.mark === 'heatmap' && spec.split !== undefined) {
    const splitCol = column(rows, spec.split);
    const m = measure(first);
    const rowsCats = splitCol ? distinct(splitCol.values) : [];
    const rowIndex = new Map(rowsCats.map((c, i) => [keyText(c), i]));
    const data: [number, number, number][] = [];
    let lo = Infinity;
    let hi = -Infinity;
    xCol.values.forEach((xv, i) => {
      const v = numberOf(m?.values[i] ?? null, m?.type);
      const xi = indexOf.get(keyText(xv));
      const yi = rowIndex.get(keyText(splitCol?.values[i] ?? null));
      if (v === null || xi === undefined || yi === undefined) return;
      data.push([xi, yi, v]);
      lo = Math.min(lo, v);
      hi = Math.max(hi, v);
    });
    return {
      option: asOption({
        ...base,
        grid: { ...base.grid, bottom: 48 },
        tooltip: { ...tooltip, trigger: 'item' },
        xAxis: { ...axisCommon, type: 'category', data: catLabels, splitArea: { show: false } },
        yAxis: {
          ...axisCommon,
          type: 'category',
          data: rowsCats.map((c) => label(c, spec.split!, splitCol?.type)),
        },
        visualMap: {
          min: Number.isFinite(lo) ? lo : 0,
          max: Number.isFinite(hi) ? hi : 1,
          calculable: true,
          orient: 'horizontal',
          left: 'center',
          bottom: 0,
          inRange: { color: [...theme.sequential] },
          textStyle: { color: theme.muted },
        },
        series: [{
          type: 'heatmap',
          name: measureName(first),
          data,
          itemStyle: { borderColor: theme.surface, borderWidth: 2 },
          label: { show: spec.options.labels, color: theme.ink },
        }],
      }),
      keyAt: (_s, i) => {
        const cell = data[i];
        if (!cell) return null;
        return { [spec.x!]: categories[cell[0]]!, [spec.split!]: rowsCats[cell[1]]! };
      },
      notes,
      description: describe(spec, rows),
    };
  }

  // -- bar, line, area: categories across, one series per measure or split value ------------------
  //
  // Each measure its own kind of mark (bar and line together, version 2's `mark`), on one value axis -- or two, when
  // the measures' units differ (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §6: each axis then labelled in its series'
  // colour). Every value is written as the grid writes it: an axis, a label, a hover.
  const splitCol = spec.split !== undefined ? column(rows, spec.split) : undefined;
  const chartMark: MeasureMark = spec.mark === 'line' || spec.mark === 'area' ? spec.mark : 'bar';
  type Series = {
    key: Scalar | undefined;
    name: string;
    values: (number | null)[];
    /** The row each value came from, for a hover's further measures and a label's own measure. */
    at: (number | null)[];
    measure: ChartMeasure;
    mark: MeasureMark;
  };
  const point = (): { values: (number | null)[]; at: (number | null)[] } => ({
    values: categories.map(() => null), at: categories.map(() => null),
  });
  let series: Series[];
  if (splitCol) {
    const m = first;
    const c = measure(m);
    const groups = distinct(splitCol.values);
    series = groups.map((g) => ({
      key: g, name: label(g, spec.split!, splitCol.type), ...point(), measure: m, mark: m.mark ?? chartMark,
    }));
    const byGroup = new Map(groups.map((g, i) => [keyText(g), i]));
    xCol.values.forEach((xv, i) => {
      const s = series[byGroup.get(keyText(splitCol.values[i] ?? null)) ?? -1];
      const xi = indexOf.get(keyText(xv));
      if (s && xi !== undefined) {
        s.values[xi] = numberOf(c?.values[i] ?? null, c?.type);
        s.at[xi] = i;
      }
    });
  } else {
    series = spec.y.map((m) => {
      const c = measure(m);
      const s: Series = { key: undefined, name: measureTitle(m), ...point(), measure: m, mark: m.mark ?? chartMark };
      xCol.values.forEach((xv, i) => {
        const xi = indexOf.get(keyText(xv));
        if (xi !== undefined) {
          s.values[xi] = numberOf(c?.values[i] ?? null, c?.type);
          s.at[xi] = i;
        }
      });
      return s;
    });
  }
  if (series.length > MAX_SERIES) {
    notes.push(`Showing the first ${MAX_SERIES} of ${series.length} series; filter or split by a smaller column to see the rest.`);
    series = series.slice(0, MAX_SERIES);
  }
  if (spec.options.stack === 'percent') {
    const totals = categories.map((_, i) => series.reduce((t, s) => t + Math.abs(s.values[i] ?? 0), 0));
    series = series.map((s) => ({
      ...s,
      values: s.values.map((v, i) => (v === null || totals[i] === 0 ? v : (100 * v) / totals[i]!)),
    }));
  }

  // THE VALUE AXES: one, or two when the measures' units differ -- the first measure's unit on the first axis, a
  // measure of another unit on the second. A measure may say its axis itself.
  const unitKey = (m: ChartMeasure): string => {
    if (m.showAs && isRatio(m.showAs.kind)) return 'ratio';
    if (m.showAs?.kind === 'rank') return 'rank';
    if (m.fn === 'count') return 'count';
    return unit?.(m.column) ?? 'number';
  };
  const firstUnit = unitKey(spec.y[0]!);
  const axisOf = (m: ChartMeasure): 0 | 1 => {
    if (m.axis) return m.axis === 'right' ? 1 : 0;
    return splitCol || unitKey(m) === firstUnit ? 0 : 1;
  };
  const units = new Set(spec.y.filter((m) => axisOf(m) === 1).map(unitKey));
  if (units.size > 1) notes.push('Two value axes at most: the measures of a third unit share the second.');
  const percent = spec.options.stack === 'percent';
  const formatOf = (m: ChartMeasure) => (v: number | null): string => (percent ? `${v === null ? '' : round1(v)}%` : measureText(m, v, rows, label));
  const horizontal = spec.mark === 'bar' && spec.options.orientation === 'horizontal'
    && series.every((s) => s.mark === 'bar');
  const stacked = spec.options.stack !== 'none';
  const twoAxes = series.some((s) => axisOf(s.measure) === 1);
  // a time axis takes each point's own time; a category axis its label
  const at = (i: number): string | number => (timeAxis ? String(categories[i]) : catLabels[i]!);
  const categoryAxis = timeAxis ? {
    ...axisCommon,
    type: 'time' as const,
    splitLine: { show: false },
    axisLabel: { ...axisCommon.axisLabel, ...(spec.options.labelRotate !== undefined ? { rotate: spec.options.labelRotate } : {}) },
  } : {
    ...axisCommon,
    type: 'category' as const,
    data: catLabels,
    splitLine: { show: false },
    // a bar's category is a band; a line's points sit on the categories
    boundaryGap: series.some((s) => s.mark === 'bar'),
    axisLabel: { ...axisCommon.axisLabel, ...(spec.options.labelRotate !== undefined ? { rotate: spec.options.labelRotate } : {}) },
  };
  const valueAxis = (index: 0 | 1) => {
    const on = series.find((s) => axisOf(s.measure) === index);
    const colour = twoAxes && on ? theme.series[series.indexOf(on) % theme.series.length]! : undefined;
    const fmt = on ? formatOf(on.measure) : (v: number | null) => String(v);
    return {
      ...axisCommon,
      type: spec.options.logAxis && !percent ? 'log' as const : 'value' as const,
      ...(index === 1 ? { splitLine: { show: false } } : {}),
      ...(percent ? { max: 100 } : {}),
      ...(twoAxes && on ? { name: on.name, nameTextStyle: { ...text, color: colour } } : {}),
      axisLabel: {
        ...axisCommon.axisLabel,
        ...(colour ? { color: colour } : {}),
        formatter: (v: number) => fmt(v),
      },
      ...(spec.options.breaks && spec.options.breaks.length > 0 && index === 0
        ? { breaks: spec.options.breaks.map((b) => ({ start: b.from, end: b.to, gap: '1.5%' })) }
        : {}),
    };
  };
  const valueAxes = twoAxes ? [valueAxis(0), valueAxis(1)] : valueAxis(0);
  const labelMeasure = spec.label;
  const labelCol = labelMeasure ? measure(labelMeasure) : undefined;
  const dim = spec.options.dim !== false;
  const out = series.map((s, si) => {
    const fmt = formatOf(s.measure);
    const axisIndex = axisOf(s.measure);
    const long = categories.length;
    const common = {
      name: s.name,
      data: timeAxis ? s.values.map((v, i) => [at(i), v]) : s.values,
      ...(stacked && s.mark !== 'line' ? { stack: `total-${axisIndex}` } : {}),
      ...(axisIndex === 1 ? (horizontal ? { xAxisIndex: 1 } : { yAxisIndex: 1 }) : {}),
      label: {
        show: spec.options.labels,
        color: spec.options.labelPosition === 'inside' ? theme.surface : theme.inkSecondary,
        position: spec.options.labelPosition === 'inside' ? 'inside' : horizontal ? 'right' : 'top',
        // the label slot's measure, as the grid writes it, else the mark's own value
        formatter: (p: { dataIndex: number; value: unknown }) => {
          if (labelMeasure && labelCol) {
            const row = s.at[p.dataIndex];
            return row === null || row === undefined ? '' : measureText(labelMeasure,
              numberOf(labelCol.values[row] ?? null, labelCol.type), rows, label);
          }
          return fmt(valueOf(p.value));
        },
      },
      tooltip: { valueFormatter: (v: unknown) => fmt(valueOf(v)) },
      emphasis: { focus: dim ? 'series' as const : 'none' as const },
      ...layersOf(spec, s, si, series, categories, at, rows, theme, fmt, xCol.type, horizontal),
    };
    if (s.mark === 'bar') {
      return {
        ...common,
        type: 'bar' as const,
        barMaxWidth: 24,
        // many bars drawn as one shape: a long series stays quick
        ...(long > 2000 ? { large: true } : {}),
        itemStyle: {
          // the data-end rounded, the baseline square; a stack's inner
          // segments are separated by the surface gap instead
          borderRadius: stacked ? 0 : horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0],
          borderColor: theme.surface,
          borderWidth: stacked ? 2 : 0,
        },
      };
    }
    return {
      ...common,
      type: 'line' as const,
      lineStyle: { width: 2 },
      symbol: 'circle',
      symbolSize: 8,
      showSymbol: categories.length <= 40,
      // point thinning (largest-triangle-three-buckets): a long series stays quick and keeps its shape
      ...(long > 500 ? { sampling: 'lttb' as const } : {}),
      ...(spec.options.smooth ? { smooth: true } : {}),
      ...(spec.options.step ? { step: 'middle' as const } : {}),
      ...(s.mark === 'area' ? { areaStyle: { opacity: 0.2 } } : {}),
    };
  });
  // a hover: the category, each series' value as the grid writes it, and the further measures asked for
  const extras = spec.tooltip ?? [];
  const tooltipFormatter = (params: unknown): string => {
    const list = (Array.isArray(params) ? params : [params]) as { seriesIndex: number; dataIndex: number; marker?: string; seriesName?: string; value?: unknown }[];
    const head = list[0];
    if (!head) return '';
    const lines = [`<b>${escapeHtml(catLabels[head.dataIndex] ?? '')}</b>`];
    for (const p of list) {
      const s = series[p.seriesIndex];
      if (!s) continue;
      lines.push(`${p.marker ?? ''}${escapeHtml(s.name)}: <b>${escapeHtml(formatOf(s.measure)(valueOf(p.value)))}</b>`);
      const row = s.at[p.dataIndex];
      if (row === null || row === undefined) continue;
      // with a split each series has its own row; without one, every series reads the category's one row
      if (!splitCol && p !== head) continue;
      for (const m of extras) {
        const c = measure(m);
        if (!c) continue;
        lines.push(`&nbsp;&nbsp;${escapeHtml(measureTitle(m))}: ${escapeHtml(measureText(m, numberOf(c.values[row] ?? null, c.type), rows, label))}`);
      }
    }
    return lines.join('<br>');
  };
  return {
    option: asOption({
      ...base,
      tooltip: {
        ...tooltip,
        trigger: 'axis',
        axisPointer: { type: series.every((s) => s.mark === 'bar') ? 'shadow' : 'line' },
        formatter: tooltipFormatter,
      },
      legend: legendOf(spec, out.length, theme),
      ...zoomOf(spec, categories.length, horizontal),
      ...(spec.options.brush ? {
        brush: { ...(horizontal ? { yAxisIndex: 0 } : { xAxisIndex: 0 }), brushType: horizontal ? 'lineY' : 'lineX', ...BRUSH_STYLE(theme) },
        ...brushToolbox(horizontal ? 'lineY' : 'lineX'),
      } : {}),
      xAxis: horizontal ? valueAxes : categoryAxis,
      yAxis: horizontal ? { ...categoryAxis, inverse: true } : valueAxes,
      ...thresholdMap(spec, series, theme, horizontal),
      series: out,
    }),
    keyAt: (seriesIndex, dataIndex) => {
      if (categories[dataIndex] === undefined) return null;
      const key: Record<string, Scalar> = { [spec.x!]: categories[dataIndex]! };
      const s = series[seriesIndex];
      if (spec.split !== undefined && s && s.key !== undefined) key[spec.split] = s.key;
      return key;
    },
    ...(spec.bins !== undefined ? { upperAt: (dataIndex: number) => nextEdge(categories, dataIndex, xCol.type) } : {}),
    // a range across: on a category axis its first and last index, on a time axis its first and last time
    ...(spec.options.brush ? { brushed: (areas: readonly BrushArea[]) => {
      const r = areas[0]?.coordRange;
      if (!r || r.length < 2) return null;
      const [a, b] = [Number(r[0]), Number(r[1])];
      const inside = categories.map((c, i) => [c, i] as const).filter(([c, i]) => {
        if (!timeAxis) return i >= Math.min(a, b) && i <= Math.max(a, b);
        const t = Date.parse(String(c));
        return t >= Math.min(a, b) && t <= Math.max(a, b);
      });
      if (inside.length === 0) return null;
      const lastIndex = inside[inside.length - 1]![1];
      const upper = spec.bins !== undefined ? nextEdge(categories, lastIndex, xCol.type) : undefined;
      return { kind: 'across' as const, values: inside.map(([c]) => c), ...(upper !== undefined ? { upper } : {}) };
    } } : {}),
    notes,
    description: describe(spec, rows),
  };
}

/** The brush's look: the selection's own colours, faint. */
const BRUSH_STYLE = (theme: ChartTheme) => ({
  brushStyle: { color: 'rgba(42, 120, 214, 0.12)', borderColor: theme.series[0], borderWidth: 1 },
  outOfBrush: { colorAlpha: 0.35 },
  throttleType: 'debounce' as const,
  throttleDelay: 300,
  removeOnClick: true,
});

/** The brush's buttons (a range, and clearing it): ECharts' toolbox, which a brush needs to start from. */
function brushToolbox(type: 'lineX' | 'lineY' | 'rect'): Record<string, unknown> {
  return { toolbox: { show: true, right: 8, top: 0, itemSize: 14, feature: { brush: { type: [type, 'clear'] } } } };
}

/** A point's value as ECharts hands it back: a number, or on a time axis the [time, value] pair. */
function valueOf(v: unknown): number | null {
  if (typeof v === 'number') return v;
  if (Array.isArray(v) && typeof v[1] === 'number') return v[1];
  return null;
}

/** Points beyond which a chart starts with a slider to zoom and pan across (unless it says otherwise). */
export const ZOOM_FROM = 60;

/**
 * Zoom and pan across (batch 2 item 9): a slider under the chart and the wheel or a drag inside it, when the chart asks
 * for it -- or, asking nothing, when it has more than ZOOM_FROM points.
 */
function zoomOf(spec: ChartSpec, points: number, horizontal: boolean): Record<string, unknown> {
  const on = spec.options.zoom ?? points > ZOOM_FROM;
  if (!on) return {};
  const axis = horizontal ? { yAxisIndex: 0 } : { xAxisIndex: 0 };
  return {
    dataZoom: [
      { type: 'inside', ...axis },
      { type: 'slider', ...axis, height: 18, bottom: 6, ...(horizontal ? { right: 6, width: 18, height: undefined, bottom: undefined, orient: 'vertical' } : {}) },
    ],
    grid: { left: 8, right: horizontal ? 36 : 16, top: 40, bottom: horizontal ? 8 : 36, containLabel: true },
  };
}

/** A measure as a legend and a hover name it: its column and aggregate, and what it is shown as. */
export function measureTitle(m: ChartMeasure): string {
  const base = m.fn === 'value' ? m.column : m.fn === 'count' ? 'count' : `${m.fn} of ${m.column}`;
  return m.showAs ? `${base} (${SHOW_AS_LABELS[m.showAs.kind].toLowerCase()})` : base;
}

const round1 = (v: number): string => (Math.round(v * 10) / 10).toLocaleString(UI_LOCALE);

/**
 * A measure's value as the reader sees it: a share or a change as a percentage, a rank and a count as whole numbers,
 * anything else as the grid writes the measure's column (its currency, its scale, its decimals).
 */
export function measureText(m: ChartMeasure, v: number | null, rows: ResultTable, label: LabelOf): string {
  if (v === null) return label(null, m.column, undefined);
  if (m.showAs && isRatio(m.showAs.kind)) {
    return new Intl.NumberFormat(UI_LOCALE, { style: 'percent', maximumFractionDigits: 1 }).format(v);
  }
  if (m.showAs?.kind === 'rank' || m.fn === 'count') return Math.round(v).toLocaleString(UI_LOCALE);
  return label(v, m.column, column(rows, measureName(m))?.type);
}

/** Text put into a hover's HTML: a value or a name from the data is never markup. */
export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

/**
 * A series' reference lines, bands and pinned extremes (version 2's layers), each drawn on the first series of the
 * measure it reads. A statistic's value is the database's (chart-spec.ts adds it to the chart's query), never summed
 * here; a band across is placed on the categories it spans, compared, never computed.
 */
function layersOf(
  spec: ChartSpec,
  s: { measure: ChartMeasure; values: readonly (number | null)[]; at: readonly (number | null)[] },
  index: number,
  all: readonly { measure: ChartMeasure }[],
  categories: readonly Scalar[],
  xAt: (i: number) => string | number,
  rows: ResultTable,
  theme: ChartTheme,
  fmt: (v: number | null) => string,
  xType: string | undefined,
  horizontal: boolean,
): Record<string, unknown> {
  // on a horizontal bar the values run across and the categories down
  const valueKey = horizontal ? 'xAxis' : 'yAxis';
  const categoryKey = horizontal ? 'yAxis' : 'xAxis';
  const layers = spec.layers ?? [];
  if (layers.length === 0) return {};
  const lines: Record<string, unknown>[] = [];
  const areas: Record<string, unknown>[][] = [];
  const points: Record<string, unknown>[] = [];
  const firstOf = (measureIndex: number): boolean => {
    const m = spec.y[measureIndex];
    return m !== undefined && all.findIndex((x) => x.measure === m) === index;
  };
  const statValue = (st: LayerStat, m: ChartMeasure): number | null => {
    const c = column(rows, statName(st, measureName(m)));
    return c ? numberOf(c.values[0] ?? null, c.type) : null;
  };
  const words = { average: 'Average', min: 'Minimum', max: 'Maximum' } as const;
  const lineStyle = { color: theme.inkSecondary, type: 'dashed' as const, width: 1 };
  for (const l of layers) {
    if (l.kind === 'band') {
      if (index !== 0) continue;
      const band = { itemStyle: { color: theme.grid, opacity: 0.5 }, ...(l.label ? { name: l.label } : {}) };
      if (l.axis === 'y') {
        areas.push([{ ...band, [valueKey]: l.from, label: { show: l.label !== undefined, color: theme.inkSecondary } }, { [valueKey]: l.to }]);
      } else {
        // the categories inside the band: from the first at or after `from` to the last at or before `to`
        const cmp = (c: Scalar, v: string | number): number => {
          const a = numberOf(c, xType);
          return a !== null && typeof v === 'number' ? a - v : String(c).localeCompare(String(v));
        };
        const inside = categories.map((c, i) => [c, i] as const).filter(([c]) => c !== null && cmp(c, l.from) >= 0 && cmp(c, l.to) <= 0);
        const lo = inside[0];
        const hi = inside[inside.length - 1];
        if (lo && hi) {
          areas.push([{ ...band, [categoryKey]: xAt(lo[1]), label: { show: l.label !== undefined, color: theme.inkSecondary } }, { [categoryKey]: xAt(hi[1]) }]);
        }
      }
      continue;
    }
    if (l.kind === 'threshold') continue;
    const at = l.measure ?? 0;
    if (!firstOf(at)) continue;
    const m = spec.y[at]!;
    if (l.kind === 'line') {
      lines.push({ [valueKey]: l.value, name: l.label ?? fmt(l.value), lineStyle, label: { formatter: `${escapeLabel(l.label ?? '')}${l.label ? ': ' : ''}${escapeLabel(fmt(l.value))}`, color: theme.inkSecondary } });
    } else if (l.kind === 'stat') {
      const v = statValue(l.stat, m);
      if (v === null) continue;
      const name = l.label ?? words[l.stat];
      lines.push({ [valueKey]: v, name, lineStyle, label: { formatter: `${escapeLabel(name)}: ${escapeLabel(fmt(v))}`, color: theme.inkSecondary } });
    } else if (l.kind === 'extremes') {
      for (const st of ['max', 'min'] as const) {
        const v = statValue(st, m);
        if (v === null) continue;
        // the marks equal to the database's maximum (minimum): compared, never computed
        s.values.forEach((x, i) => {
          if (x !== null && x === v) {
            points.push({ coord: horizontal ? [x, xAt(i)] : [xAt(i), x], value: fmt(x), name: words[st], symbol: 'pin', symbolSize: 36,
              itemStyle: { color: st === 'max' ? theme.series[2] : theme.bad ?? theme.series[7] } });
          }
        });
      }
    }
  }
  return {
    ...(lines.length > 0 ? { markLine: { silent: true, symbol: 'none', data: lines } } : {}),
    ...(areas.length > 0 ? { markArea: { silent: true, data: areas } } : {}),
    ...(points.length > 0 ? { markPoint: { data: points, label: { color: theme.surface, fontSize: 10 } } } : {}),
  };
}

/** ECharts reads `{a}`-style templates in a label's formatter string: a value's braces are text. */
function escapeLabel(s: string): string {
  return s.replace(/[{}]/g, '');
}

/**
 * A threshold layer: its measure's series coloured by value -- below the threshold in the bad colour (a piecewise
 * visual map over that series, hidden: the legend says the series, the colour says the side).
 */
function thresholdMap(
  spec: ChartSpec,
  series: readonly { measure: ChartMeasure }[],
  theme: ChartTheme,
  horizontal: boolean,
): Record<string, unknown> {
  const maps = (spec.layers ?? []).flatMap((l) => {
    if (l.kind !== 'threshold') return [];
    const m = spec.y[l.measure ?? 0];
    const indices = series.map((s, i) => [s, i] as const).filter(([s]) => s.measure === m).map(([, i]) => i);
    return indices.map((i) => ({
      type: 'piecewise' as const,
      show: false,
      seriesIndex: i,
      // the value's dimension: across on a horizontal bar, up otherwise
      dimension: horizontal ? 0 : 1,
      pieces: [
        { lt: l.value, color: theme.bad ?? theme.series[7] },
        { gte: l.value, color: theme.series[i % theme.series.length] },
      ],
    }));
  });
  return maps.length > 0 ? { visualMap: maps } : {};
}

/** The least category above the one at `i` (a bin's next edge): compared, never computed. */
function nextEdge(categories: readonly Scalar[], i: number, type: string | undefined): Scalar | undefined {
  const at = numberOf(categories[i] ?? null, type);
  if (at === null) return undefined;
  let best: Scalar | undefined;
  let bestN = Infinity;
  for (const c of categories) {
    const n = numberOf(c, type);
    if (n !== null && n > at && n < bestN) {
      best = c;
      bestN = n;
    }
  }
  return best;
}

const MARK_WORDS: Record<ChartSpec['mark'], string> = {
  bar: 'Bar chart', line: 'Line chart', area: 'Area chart', scatter: 'Scatter plot',
  pie: 'Pie chart', heatmap: 'Heatmap', treemap: 'Treemap', kpi: 'KPI',
};

/** A KPI as drawn: its number as the grid writes it, how it stands against its target, and its trend's line. */
export interface KpiDrawing {
  /** The measure over every row, as the grid writes it; "—" when there is none (no rows). */
  readonly value: string;
  /** Against its target: "12% above target (13.5M)", good or not; absent with no target. */
  readonly target?: { readonly text: string; readonly good: boolean };
  /** Its trend, a line with no axes, when it has a column across. */
  readonly trend?: ChartDrawing;
  /** The KPI in words, for a screen reader. */
  readonly description: string;
  readonly notes: readonly string[];
}

/**
 * A KPI (chart-spec.ts `kpiQueries`): `total`'s one row, its measure written as the grid writes the measure's column;
 * against `options.target`, the difference as a share of the target -- good when it is on the side `options.better`
 * says (above, unless it says below) -- and, given `trend`'s rows, the measure along the column across, a line with
 * nothing but the line (a hover says each point).
 */
export function kpiDrawing(
  spec: ChartSpec,
  total: ResultTable,
  trend: ResultTable | null,
  theme: ChartTheme = LIGHT_THEME,
  label: LabelOf = defaultLabel,
): KpiDrawing {
  const first = spec.y[0];
  const notes: string[] = [];
  if (!first) return { value: '\u2014', description: 'A KPI with nothing to measure.', notes: ['Pick something to measure.'] };
  const name = measureName(first);
  const col = column(total, name);
  const raw = col?.values[0] ?? null;
  const v = numberOf(raw, col?.type);
  const value = raw === null ? '\u2014' : label(raw, first.column, col?.type);
  const what = `${first.fn} of ${first.column}`;
  let target: KpiDrawing['target'];
  const t = spec.options.target;
  if (t !== undefined && v !== null) {
    const higher = spec.options.better !== 'lower';
    const goal = label(t, first.column, col?.type);
    if (v === t) {
      target = { text: `On target (${goal})`, good: true };
    } else {
      const share = t === 0 ? null : Math.abs((v - t) / t) * 100;
      const above = v > t;
      const mark = above ? '\u25B2' : '\u25BC';
      // past ten times the target, said as how many times it (a percentage of thousands reads as nothing)
      const text = share !== null && share >= 1000 && v / t > 0
        ? `${mark} ${Math.round(v / t).toLocaleString(UI_LOCALE)}\u00D7 the target (${goal})`
        : `${mark} ${share === null ? '' : `${share < 10 ? share.toFixed(1) : Math.round(share)}% `}${above ? 'above' : 'below'} target (${goal})`;
      target = { text, good: above === higher };
    }
  }
  let line: ChartDrawing | undefined;
  if (trend && spec.x !== undefined) {
    const xs = column(trend, spec.x);
    const ys = column(trend, name);
    if (xs && ys) {
      // asked latest first, one past the most it shows (kpiQueries): drawn oldest first, the latest KPI_TREND_LIMIT
      const latest = xs.values.map((x, i) => [label(x, spec.x!, xs.type), numberOf(ys.values[i] ?? null, ys.type)] as const)
        .slice(0, KPI_TREND_LIMIT);
      const points = [...latest].reverse();
      line = {
        option: asOption({
          backgroundColor: 'transparent',
          animation: false,
          aria: { enabled: true, label: { enabled: false } },
          grid: { left: 0, right: 0, top: 4, bottom: 4 },
          tooltip: {
            trigger: 'axis', confine: true, backgroundColor: theme.surface, borderColor: theme.axis,
            textStyle: { color: theme.ink },
          },
          xAxis: { type: 'category', show: false, boundaryGap: false, data: points.map((p) => p[0]) },
          yAxis: { type: 'value', show: false, scale: true },
          series: [{
            type: 'line', name: what, data: points.map((p) => p[1]), showSymbol: false,
            lineStyle: { width: 2, color: theme.series[0] }, itemStyle: { color: theme.series[0] },
            areaStyle: { opacity: 0.12, color: theme.series[0] },
          }],
        }),
        keyAt: () => null,
        notes: [],
        description: `${what} by ${spec.x}: ${points.length} points.`,
      };
      if (xs.values.length > KPI_TREND_LIMIT) notes.push(`The trend shows the latest ${KPI_TREND_LIMIT} values of ${spec.x}.`);
    }
  }
  const description = `KPI: ${what} is ${value}${target ? `; ${target.text}` : ''}.`;
  return { value, ...(target ? { target } : {}), ...(line ? { trend: line } : {}), description, notes };
}

/** The chart in one sentence: what is plotted against what, and how much of it. */
export function describe(spec: ChartSpec, rows: ResultTable): string {
  const what = spec.mark === 'scatter'
    ? `${spec.y[0]?.column ?? ''} against ${spec.x ?? ''}`
    : `${spec.y.map(measureName).join(', ')} by ${spec.x ?? ''}`;
  const split = spec.split !== undefined ? `, split by ${spec.split}` : '';
  return `${MARK_WORDS[spec.mark]} of ${what}${split}: ${rows.rowCount} rows.`;
}

function legendOf(spec: ChartSpec, count: number, theme: ChartTheme): EChartsOption['legend'] {
  // one series is named by the chart's title; a legend box is for two or more
  if (count < 2 || spec.options.legend === 'none') return { show: false };
  const at = spec.options.legend;
  return {
    show: true,
    type: 'scroll',
    textStyle: { color: theme.inkSecondary },
    ...(at === 'right' ? { right: 0, top: 'middle', orient: 'vertical' } : at === 'bottom' ? { bottom: 0 } : { top: 0 }),
  };
}

/**
 * What the grid shows now, as a treemap draws it: its rows, their tree, its visible columns in
 * order, and the row dimensions (a path's keys, level by level).
 */
export interface GridShown {
  readonly rows: ResultTable;
  readonly tree: readonly {
    readonly path: readonly Scalar[];
    readonly level: number;
    readonly isGroup: boolean;
    readonly expanded: boolean;
    readonly isTotal: boolean;
    readonly isDetail?: boolean;
  }[];
  readonly leaves: readonly { readonly name: string; readonly type: string; readonly label?: string }[];
  readonly levels: readonly string[];
}

const TREE = TREE_COLUMN;

/**
 * A TREEMAP OF THE GRID AS SHOWN (the user, 2026-09-30: the old treemap's way): one block per
 * row on screen -- a collapsed group is a block, an expanded one a box around its visible
 * children -- sized by the first numeric column the grid shows, labelled and valued in the grid's
 * formats. It cannot disagree with the grid beside it: every filter, pivot, sort and expansion is
 * already in what it is given. The grand total is the whole, not a block.
 *
 * NON-POSITIVE VALUES ARE DROPPED, not clamped: a treemap encodes magnitude as AREA, and a
 * negative area does not exist -- drawing a zero-size box would hide the row silently, and its
 * absolute value would say the opposite of the truth. How many went is said.
 */
export function treemapOfGrid(shown: GridShown, theme: ChartTheme = LIGHT_THEME, label: LabelOf = defaultLabel): ChartDrawing {
  const notes: string[] = [];
  const rows = shown.rows;
  const tree = shown.tree;
  const value = shown.leaves.find((l) => l.name !== TREE && isNumeric(l.type));
  const valueCol = value ? column(rows, value.name) : undefined;
  const treeCol = column(rows, TREE);
  // a flat cube's label: the first visible column that is not a number
  const flatLabel = shown.leaves.find((l) => l.name !== TREE && !isNumeric(l.type));
  const flatCol = flatLabel ? column(rows, flatLabel.name) : undefined;
  type Node = { name: string; value?: number; text?: string; key: MarkKey; children?: Node[]; level: number };
  const top: Node[] = [];
  const open: Node[] = [];
  let dropped = 0;
  for (let i = 0; i < rows.rowCount; i++) {
    const t = tree[i];
    // the grand total is the whole treemap, not a block of it
    if (t && t.isTotal && t.level === 0) continue;
    const level = t ? t.level : 1;
    const path = t?.path ?? [];
    const key: MarkKey = Object.fromEntries(path.map((v, j) => [shown.levels[j] ?? `level${j}`, v]));
    const raw = treeCol?.values[i] ?? null;
    const last = path.length > 0 ? path[path.length - 1]! : null;
    const name = t
      ? (raw !== null && raw !== '' ? String(raw)
        : label(last, shown.levels[path.length - 1] ?? '', undefined))
      : label(flatCol?.values[i] ?? null, flatLabel?.name ?? '', flatLabel?.type);
    while (open.length > 0 && open[open.length - 1]!.level >= level) open.pop();
    const parent = open[open.length - 1];
    const into = (n: Node): void => { if (parent) (parent.children ??= []).push(n); else top.push(n); };
    if (t && t.isGroup && t.expanded) {
      const box: Node = { name, key, level, children: [] };
      into(box);
      open.push(box);
      continue;
    }
    const v = numberOf(valueCol?.values[i] ?? null, valueCol?.type);
    if (v === null || v <= 0) {
      dropped += 1;
      continue;
    }
    into({ name, value: v, text: label(valueCol?.values[i] ?? null, value?.name ?? '', value?.type), key, level });
  }
  // a box whose children were all dropped is not a box
  const prune = (nodes: Node[]): Node[] => nodes.flatMap((n) => {
    if (!n.children) return [n];
    const kids = prune(n.children);
    return kids.length > 0 ? [{ ...n, children: kids }] : [];
  });
  const data = prune(top);
  if (dropped > 0) {
    notes.push(`${dropped} ${dropped === 1 ? 'row' : 'rows'} not shown: zero or negative cannot have an area.`);
  }
  if (!value) notes.push('The grid shows no numeric column to size the blocks by.');
  const depth = (nodes: Node[]): number => nodes.reduce((m, n) => Math.max(m, n.children ? 1 + depth(n.children) : 1), 0);
  const levels = Math.max(1, depth(data));
  const text = { fontFamily: 'Roboto, ui-sans-serif, system-ui, sans-serif', fontSize: 11, color: theme.ink };
  return {
    option: asOption({
      backgroundColor: 'transparent',
      textStyle: text,
      color: [...theme.series],
      aria: { enabled: true, label: { enabled: false } },
      tooltip: {
        backgroundColor: theme.surface,
        borderColor: theme.axis,
        textStyle: { color: theme.ink },
        confine: true,
        trigger: 'item',
        // the value as the grid writes it
        formatter: (p: { name?: string; data?: { text?: string } }) =>
          (p.data?.text !== undefined ? `${p.name ?? ''}: ${p.data.text}` : (p.name ?? '')),
      },
      series: [{
        type: 'treemap',
        roam: false,
        nodeClick: false,
        breadcrumb: { show: false },
        top: 8, left: 8, right: 8, bottom: 8,
        leafDepth: levels,
        label: { show: true, color: '#ffffff', overflow: 'truncate' },
        upperLabel: { show: levels > 1, height: 18, color: theme.ink },
        itemStyle: { borderColor: theme.surface, borderWidth: 1, gapWidth: 1 },
        levels: [
          { itemStyle: { borderColor: theme.surface, borderWidth: 2, gapWidth: 2 } },
          { colorSaturation: [0.35, 0.6], itemStyle: { gapWidth: 1 } },
        ],
        data,
      }],
    }),
    // the clicked block's own row: its path, column by column
    keyAt: (_s, _i, datum) => {
      const key = (datum as { key?: MarkKey } | undefined)?.key;
      return key && Object.keys(key).length > 0 ? key : null;
    },
    notes,
    description: `Treemap of ${value?.label ?? value?.name ?? 'nothing'} by the grid's rows as shown.`,
  };
}

