// The chart window: a chart of the cube, and the options that shape it.
//
// Left, the options (mark, what goes across, what is plotted and how it is
// aggregated, the split, orientation, stacking, order, how many, labels,
// legend); right, the chart. The chart follows the cube: its query is the
// cube's own, regrouped (`chart-spec.ts`), so a filter added to the grid
// narrows the chart, and a click on a mark (a bar, a slice, a cell) adds
// that mark's values to the cube's filter.

import {
  CHART_MARKS,
  chartColumns,
  chartProblems,
  chartQuery,
  defaultChart,
  followCube,
  kpiQueries,
  markConditions,
  LAYER_STATS,
  SHOW_AS_KINDS,
  TIME_GRAINS,
  type ChartLayer,
  type ChartLayerKind,
  type ChartMark,
  type ChartMeasure,
  type MeasureMark,
  type ShowAsKind,
  type TimeGrain,
  type ChartSpec,
} from '../chart-spec.ts';
// the drawing (chart-option.ts) comes with ECharts, the first time a chart draws: a grid alone fetches neither
import type { GridShown, LabelOf, MarkKey, UnitOf } from '../chart-option.ts';
// ECharts (about 218 KB gzipped) is loaded the first time a chart draws, never by a grid alone (plan F7)
import type { ChartPicture, MountedChart } from '../chart-render.ts';
import type { ResultTable, Scalar } from '../../../engine-client/src/result.ts';
import type { AggregateFn, CubeSnapshot, FilterNode } from '../snapshot.ts';
import type { Lambda } from '../../../pure-protocol/src/index.ts';
import { checkbox, dropdown, field, numberInput } from './form.ts';
import { aggregatesFor } from './panel-column.ts';
import { GRAIN_LABELS, SHOW_AS_LABELS } from '../calc-presets.ts';
import { isNumeric, isTemporal } from '../../../engine-client/src/types.ts';

/** The most values one chart plots side by side (bars, lines). */
const MAX_MEASURES = 6;

export interface ChartPanelOptions {
  /** The cube as it is NOW (the chart follows it). */
  readonly snapshot: () => CubeSnapshot;
  /** Run a query the cube did not plan, through the cube's own runner. */
  readonly run: (query: Lambda, snapshot: CubeSnapshot, signal: AbortSignal) => Promise<ResultTable>;
  /** How a value is written: the grid's formatter. */
  readonly label: LabelOf;
  /** A column's unit as the grid formats it: two units, two value axes. */
  readonly unit?: UnitOf;
  /** A mark was clicked: filter the cube to these conditions (its values; a period's or a bin's range). */
  readonly onPick: (conditions: FilterNode[]) => void;
  /** Where to start; the cube's own grouping suggests one otherwise. */
  readonly initial?: ChartSpec;
  /** Upstream-style debounce between an edit and the query; a test passes 0. */
  readonly debounceMs?: number;
  /** Whether the options start open (a chart tile starts with the chart alone). */
  readonly formOpen?: boolean;
  /** The chart froze or went live (by the Freeze button, or by an edit to its grouping). */
  readonly onFrozen?: (frozen: boolean) => void;
  /** The user changed the chart (an option in its form). */
  readonly onSpec?: () => void;
  /** What the grid shows now: a treemap draws exactly that, not a query of its own. */
  readonly shown?: () => GridShown | null;
}

export class ChartPanel {
  readonly #root: HTMLElement;
  readonly #doc: Document;
  readonly #options: ChartPanelOptions;
  #spec: ChartSpec | null;
  #form!: HTMLElement;
  #canvas!: HTMLElement;
  #status!: HTMLElement;
  #chart: MountedChart | null = null;
  /** A KPI's trend line, mounted under its number (the number is the canvas's own, not a chart). */
  #trend: MountedChart | null = null;
  #timer: ReturnType<typeof setTimeout> | undefined;
  #inflight: AbortController | null = null;
  #disposed = false;
  /** A treemap's rows as last drawn: what a frozen one keeps. */
  #shownKept: GridShown | null = null;

  constructor(root: HTMLElement, options: ChartPanelOptions) {
    this.#root = root;
    this.#doc = root.ownerDocument;
    this.#options = options;
    this.#spec = options.initial ?? defaultChart(options.snapshot());
    this.#build();
    this.refresh(0);
  }

  /** The spec being drawn (to save it, later). */
  get spec(): ChartSpec | null {
    return this.#spec;
  }

  /** Whether the chart keeps its own grouping, or follows the cube's pivots. */
  get frozen(): boolean {
    return this.#spec?.frozen === true;
  }

  /** Freeze the chart's grouping, or make it live again (it regroups the way the cube is now). */
  setFrozen(frozen: boolean): void {
    if (!this.#spec || this.frozen === frozen) return;
    const { frozen: _was, ...rest } = this.#spec;
    this.#spec = frozen ? { ...rest, frozen: true } : rest;
    this.#options.onFrozen?.(frozen);
    this.refresh(0);
  }

  /** Draw `spec` instead (a pinned chart updated from the live one): its form follows. */
  setSpec(spec: ChartSpec): void {
    const wasFrozen = this.frozen;
    this.#spec = spec;
    this.#paintForm();
    if (this.frozen !== wasFrozen) this.#options.onFrozen?.(this.frozen);
    this.refresh(0);
  }

  /** The cube changed (a new filter, a new pivot, a new calculated column): draw again, regrouped if live. */
  refresh(delay = this.#options.debounceMs ?? 150): void {
    if (this.#spec) {
      const followed = followCube(this.#spec, this.#options.snapshot());
      if (followed !== this.#spec) {
        this.#spec = followed;
        this.#paintForm();
      }
    }
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => void this.#draw(), delay);
  }

  /** Show or hide the options; answers whether they are now shown. */
  toggleForm(): boolean {
    this.#form.hidden = !this.#form.hidden;
    return !this.#form.hidden;
  }

  dispose(): void {
    this.#disposed = true;
    clearTimeout(this.#timer);
    this.#inflight?.abort();
    this.#chart?.dispose();
    this.#chart = null;
    this.#trend?.dispose();
    this.#trend = null;
  }

  // -- layout ---------------------------------------------------------------

  #build(): void {
    const doc = this.#doc;
    this.#root.replaceChildren();
    this.#root.classList.add('dc-chartpanel');
    this.#form = doc.createElement('div');
    this.#form.className = 'dc-chartpanel-form';
    const right = doc.createElement('div');
    right.className = 'dc-chartpanel-main';
    this.#canvas = doc.createElement('div');
    this.#canvas.className = 'dc-chartpanel-chart';
    this.#canvas.setAttribute('role', 'img');
    this.#status = doc.createElement('div');
    this.#status.className = 'dc-chartpanel-status';
    this.#status.setAttribute('role', 'status');
    right.append(this.#canvas, this.#status);
    this.#root.append(this.#form, right);
    this.#form.hidden = this.#options.formOpen === false;
    this.#paintForm();
  }

  #set(change: Partial<ChartSpec>, redrawForm = false): void {
    if (!this.#spec) return;
    // a hand on the grouping of a live chart freezes it: the next pivot
    // change would otherwise undo the choice
    const grouping = 'x' in change || 'y' in change || 'split' in change;
    const freeze = grouping && !this.frozen && this.#spec.mark !== 'scatter';
    this.#spec = { ...this.#spec, ...change, ...(freeze ? { frozen: true } : {}) };
    if (freeze) this.#options.onFrozen?.(true);
    this.#options.onSpec?.();
    if (redrawForm) this.#paintForm();
    this.refresh();
  }

  #setOption<K extends keyof ChartSpec['options']>(key: K, value: ChartSpec['options'][K]): void {
    if (!this.#spec) return;
    this.#set({ options: { ...this.#spec.options, [key]: value } });
  }

  #paintForm(): void {
    const doc = this.#doc;
    const form = this.#form;
    form.replaceChildren();
    const s = this.#options.snapshot();
    const spec = this.#spec;
    if (!spec) {
      form.textContent = 'This cube has no column to chart.';
      return;
    }
    const { dimensions, measures, groupLevel } = chartColumns(s, spec ?? undefined);
    const dims = dimensions.map((c) => ({ value: c.name, label: c.name }));
    const scatter = spec.mark === 'scatter';
    const across = scatter
      ? [...measures, ...dimensions].map((c) => ({ value: c.name, label: c.name }))
      : dims;
    const first = spec.y[0];
    const typeOf = new Map([...dimensions, ...measures].map((c) => [c.name, c.type]));
    const fns = (first && first.fn !== 'value' ? aggregatesFor(typeOf.get(first.column)) : [])
      .filter((a) => a.value !== 'wavg')
      .map((a) => ({ value: a.value, label: a.label }));
    // what a measure can be: a number column, aggregated; or a group-level column, plotted as it is (`fn: 'value'`)
    const GROUP = 'group:';
    const plotted = [
      ...measures.map((c) => ({ value: c.name, label: c.name })),
      ...groupLevel.map((d) => ({ value: `${GROUP}${d.name}`, label: `${d.name} (group level)` })),
    ];
    const plottedValue = first ? (first.fn === 'value' ? `${GROUP}${first.column}` : first.column) : undefined;
    const pick = (v: string | undefined, fn: AggregateFn | undefined): void => {
      if (!v) return;
      const y = v.startsWith(GROUP)
        ? { column: v.slice(GROUP.length), fn: 'value' as const }
        : { column: v, fn: fn ?? 'sum' };
      this.#set({ y: [y] }, true);
    };
    const currentFn = (): AggregateFn | undefined => {
      const f = this.#spec?.y[0]?.fn;
      return f === 'value' ? undefined : f;
    };

    const markField = field(doc, 'Chart:', dropdown<ChartMark>(doc, spec.mark, CHART_MARKS, (v) => {
      if (v) this.#set({ mark: v }, true);
    }));
    if (spec.mark === 'kpi') {
      // a number, what it measures, its trend by a column (or none), and a target with which way is good
      form.append(
        markField,
        field(doc, 'Value:', dropdown<string>(doc, first?.column, measures.map((c) => ({ value: c.name, label: c.name })),
          (v) => { if (v) this.#set({ y: [{ column: v, fn: currentFn() ?? 'sum' }] }, true); })),
        field(doc, 'Aggregate:', dropdown<AggregateFn>(doc, currentFn(), fns, (v) => {
          const now = this.#spec?.y[0];
          if (v && now) this.#set({ y: [{ column: now.column, fn: v }] });
        })),
        field(doc, 'Trend by:', dropdown<string>(doc, spec.x, dims, (v) => {
          if (!this.#spec) return;
          const { x: _old, ...rest } = this.#spec;
          this.#spec = v ? { ...rest, x: v } : rest;
          this.#options.onSpec?.();
          this.refresh();
        }, { allowNone: true })),
        field(doc, 'Target:', numberInput(doc, spec.options.target, (v) => {
          if (!this.#spec) return;
          const { target: _old, ...rest } = this.#spec.options;
          this.#set({ options: v === undefined ? rest : { ...rest, target: v } });
        }, { width: 110 })),
        field(doc, 'Good when:', dropdown(doc, spec.options.better ?? 'higher', [
          { value: 'higher', label: 'Above target' },
          { value: 'lower', label: 'Below target' },
        ] as const, (v) => { if (v) this.#setOption('better', v); })),
      );
      return;
    }
    if (spec.mark === 'treemap') {
      // what is on screen decides the rest: nothing else to choose
      const note = doc.createElement('p');
      note.className = 'dc-chartpanel-note';
      note.textContent = 'Draws the grid\'s rows as shown: each a block, sized by the first number column the grid shows. Expand a group to see inside it.';
      form.append(markField, note);
      return;
    }
    form.append(
      markField,
      field(doc, scatter ? 'X:' : 'Across:', dropdown<string>(doc, spec.x, across, (v) => {
        if (!v || !this.#spec) return;
        // a grain or bins belong to the column they were chosen for
        const { grain: _g, bins: _b, ...rest } = this.#spec;
        this.#spec = rest;
        this.#set({ x: v }, true);
      })),
      ...this.#presetFields(spec, typeOf.get(spec.x ?? '')),
      // every handler reads the spec as it is AT THE EVENT, never the one
      // this form was painted from: a stale copy undid the edit before it
      field(doc, scatter ? 'Y:' : 'Value:', dropdown<string>(
        doc, scatter ? first?.column : plottedValue,
        scatter ? measures.map((c) => ({ value: c.name, label: c.name })) : plotted,
        (v) => pick(v, currentFn()),
      )),
      ...(scatter || first?.fn === 'value' ? [] : [field(doc, 'Aggregate:', dropdown<AggregateFn>(
        doc, currentFn(), fns,
        (v) => {
          const now = this.#spec?.y[0];
          if (v && now) this.#set({ y: [{ ...now, fn: v }] });
        },
      ))]),
      field(doc, spec.mark === 'heatmap' ? 'Rows:' : 'Split by:', dropdown<string>(
        doc, spec.split, dims,
        (v) => {
          if (!this.#spec) return;
          const { split: _old, ...rest } = this.#spec;
          const freeze = !this.frozen && this.#spec.mark !== 'scatter';
          this.#spec = { ...(v ? { ...rest, split: v } : rest), ...(freeze ? { frozen: true } : {}) };
          if (freeze) this.#options.onFrozen?.(true);
          this.#options.onSpec?.();
          this.refresh();
        },
        { allowNone: spec.mark !== 'heatmap' },
      )),
    );
    if (spec.mark === 'bar' || spec.mark === 'line' || spec.mark === 'area') {
      form.append(...this.#measureRows(spec, plotted, typeOf), ...this.#layerFields(spec, plotted));
    }
    if (spec.mark === 'bar') {
      form.append(field(doc, 'Orientation:', dropdown(doc, spec.options.orientation, [
        { value: 'vertical', label: 'Vertical' },
        { value: 'horizontal', label: 'Horizontal' },
      ] as const, (v) => { if (v) this.#setOption('orientation', v); })));
    }
    if (spec.mark === 'bar' || spec.mark === 'area') {
      form.append(field(doc, 'Stack:', dropdown(doc, spec.options.stack, [
        { value: 'none', label: 'None' },
        { value: 'stacked', label: 'Stacked' },
        { value: 'percent', label: '100%' },
      ] as const, (v) => { if (v) this.#setOption('stack', v); })));
    }
    if (!scatter) {
      form.append(
        field(doc, 'Order by:', dropdown(doc, `${spec.options.sort.by}:${spec.options.sort.direction}`, [
          { value: 'y:desc', label: 'Value, largest first' },
          { value: 'y:asc', label: 'Value, smallest first' },
          { value: 'x:asc', label: 'Category, A to Z' },
          { value: 'x:desc', label: 'Category, Z to A' },
        ] as const, (v) => {
          if (!v) return;
          const [by, direction] = v.split(':') as ['x' | 'y', 'asc' | 'desc'];
          this.#setOption('sort', { by, direction });
        })),
        field(doc, 'At most:', numberInput(doc, spec.options.limit, (v) => {
          if (v !== undefined && v >= 1) this.#setOption('limit', Math.floor(v));
        }, { min: 1, max: 5000, step: 10, width: 80 })),
      );
    }
    form.append(
      field(doc, 'Legend:', dropdown(doc, spec.options.legend, [
        { value: 'top', label: 'Top' },
        { value: 'bottom', label: 'Bottom' },
        { value: 'right', label: 'Right' },
        { value: 'none', label: 'None' },
      ] as const, (v) => { if (v) this.#setOption('legend', v); })),
      checkbox(doc, 'Show values', spec.options.labels, (v) => this.#setOption('labels', v)),
    );
    if (!scatter) {
      const hint = doc.createElement('p');
      hint.className = 'dc-chartpanel-hint';
      hint.textContent = 'Click a mark on the chart to filter the cube to it.';
      form.append(hint);
    }
  }

  /**
   * THE MEASURES AFTER THE FIRST (version 2): each its own column and aggregate -- or a group-level column as it is --,
   * its own kind of mark and its value axis (by unit unless told), shown as it says; one added or taken away. A split
   * draws the first measure once per value, so the others wait until it is taken off.
   */
  #measureRows(spec: ChartSpec, plotted: readonly { value: string; label: string }[], typeOf: ReadonlyMap<string, string | undefined>): HTMLElement[] {
    const doc = this.#doc;
    const GROUP = 'group:';
    const out: HTMLElement[] = [];
    const replace = (i: number, m: ChartMeasure | null): void => {
      if (!this.#spec) return;
      const y = [...this.#spec.y];
      if (m === null) y.splice(i, 1);
      else y[i] = m;
      this.#set({ y }, true);
    };
    spec.y.forEach((m, i) => {
      if (i === 0) return;
      const row = doc.createElement('div');
      row.className = 'dc-chartpanel-measure';
      const value = m.fn === 'value' ? `${GROUP}${m.column}` : m.column;
      const fns = (m.fn === 'value' ? [] : aggregatesFor(typeOf.get(m.column)))
        .filter((a) => a.value !== 'wavg').map((a) => ({ value: a.value, label: a.label }));
      row.append(
        field(doc, `Value ${i + 1}:`, dropdown<string>(doc, value, plotted, (v) => {
          if (!v) return;
          replace(i, v.startsWith(GROUP) ? { ...m, column: v.slice(GROUP.length), fn: 'value' }
            : { ...m, column: v, fn: m.fn === 'value' ? 'sum' : m.fn });
        })),
        ...(m.fn === 'value' ? [] : [field(doc, 'Aggregate:', dropdown<AggregateFn>(doc, m.fn, fns, (v) => {
          if (v) replace(i, { ...m, fn: v });
        }))]),
        field(doc, 'Drawn as:', dropdown<MeasureMark>(doc, m.mark ?? (spec.mark === 'bar' ? 'bar' : spec.mark as MeasureMark), [
          { value: 'bar', label: 'Bars' }, { value: 'line', label: 'Line' }, { value: 'area', label: 'Area' },
        ], (v) => { if (v) replace(i, { ...m, mark: v }); })),
        field(doc, 'Axis:', dropdown<string>(doc, m.axis ?? 'auto', [
          { value: 'auto', label: 'By its unit' }, { value: 'left', label: 'Left' }, { value: 'right', label: 'Right' },
        ], (v) => {
          const { axis: _old, ...rest } = m;
          replace(i, v === 'left' || v === 'right' ? { ...rest, axis: v } : rest);
        })),
      );
      const remove = doc.createElement('button');
      remove.type = 'button';
      remove.className = 'dc-btn dc-btn-small';
      remove.textContent = '\u00d7';
      remove.title = `Take value ${i + 1} off the chart`;
      remove.setAttribute('aria-label', remove.title);
      remove.addEventListener('click', () => replace(i, null));
      row.append(remove);
      out.push(row);
    });
    const add = doc.createElement('button');
    add.type = 'button';
    add.className = 'dc-btn dc-btn-small dc-chartpanel-add';
    add.textContent = '+ Add a value';
    add.disabled = spec.split !== undefined || spec.y.length >= MAX_MEASURES;
    add.title = spec.split !== undefined
      ? 'A split draws one value per series: take the split off to plot more values.'
      : 'Another value on the chart: as a line, say, beside the bars.';
    add.addEventListener('click', () => {
      const now = this.#spec;
      const first = now?.y[0];
      if (!now || !first) return;
      const next = plotted.find((p) => !p.value.startsWith(GROUP) && p.value !== first.column) ?? plotted[0];
      if (!next) return;
      this.#set({ y: [...now.y, { column: next.value, fn: 'sum', mark: 'line' }] }, true);
    });
    out.push(add);
    return out;
  }

  /**
   * REFERENCE LINES AND COLOUR BY VALUE (version 2's layers, over the first value): a target where it is told, the
   * average where the database says, the highest and lowest pinned, the marks below a threshold in the bad colour; and
   * what a hover lists besides, and what a mark's label says.
   */
  #layerFields(spec: ChartSpec, plotted: readonly { value: string; label: string }[]): HTMLElement[] {
    const doc = this.#doc;
    const layers = spec.layers ?? [];
    const own = (kind: ChartLayerKind): ChartLayer | undefined => layers.find((l) => l.kind === kind && (l.kind === 'band' || (l.measure ?? 0) === 0));
    const setLayer = (kind: ChartLayerKind, layer: ChartLayer | null): void => {
      if (!this.#spec) return;
      const rest = (this.#spec.layers ?? []).filter((l) => l !== own(kind));
      const next = layer ? [...rest, layer] : rest;
      const { layers: _old, ...spec2 } = this.#spec;
      this.#spec = spec2;
      this.#set(next.length > 0 ? { layers: next } : {});
    };
    const target = own('line');
    const threshold = own('threshold');
    const stat = own('stat');
    const heading = doc.createElement('div');
    heading.className = 'dc-chartpanel-heading';
    heading.textContent = 'Reference lines';
    const measures = plotted.filter((p) => !p.value.startsWith('group:'));
    const extraOf = (m: ChartMeasure | undefined): string | undefined => m?.column;
    return [
      heading,
      field(doc, 'Target:', numberInput(doc, target?.kind === 'line' ? target.value : undefined, (v) => {
        setLayer('line', v === undefined ? null : { kind: 'line', value: v, label: 'Target' });
      }, { width: 110 })),
      field(doc, 'Line at:', dropdown<string>(doc, stat?.kind === 'stat' ? stat.stat : undefined,
        LAYER_STATS.map((st) => ({ value: st, label: { average: 'The average', min: 'The minimum', max: 'The maximum' }[st] })), (v) => {
          setLayer('stat', v === 'average' || v === 'min' || v === 'max' ? { kind: 'stat', stat: v } : null);
        }, { allowNone: true })),
      checkbox(doc, 'Pin the highest and lowest', own('extremes') !== undefined, (on) => setLayer('extremes', on ? { kind: 'extremes' } : null)),
      field(doc, 'Bad below:', numberInput(doc, threshold?.kind === 'threshold' ? threshold.value : undefined, (v) => {
        setLayer('threshold', v === undefined ? null : { kind: 'threshold', value: v });
      }, { width: 110 })),
      field(doc, 'Also in hover:', dropdown<string>(doc, extraOf(spec.tooltip?.[0]), measures, (v) => {
        if (!this.#spec) return;
        const { tooltip: _old, ...rest } = this.#spec;
        this.#spec = rest;
        this.#set(v ? { tooltip: [{ column: v, fn: 'sum' }] } : {});
      }, { allowNone: true })),
      field(doc, 'Label with:', dropdown<string>(doc, extraOf(spec.label), measures, (v) => {
        if (!this.#spec) return;
        const { label: _old, ...rest } = this.#spec;
        this.#spec = rest;
        this.#set(v ? { label: { column: v, fn: 'sum' } } : {});
      }, { allowNone: true })),
    ];
  }

  /**
   * The presets across and up (calc-presets.ts, docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.2): a date across by its
   * period, a number across by its bins, the measure shown as a running total, a share, a change, a moving average or
   * a rank -- each kept in the spec as its words, expanded to calculated columns when the query is built.
   */
  #presetFields(spec: ChartSpec, xType: string | undefined): HTMLElement[] {
    const doc = this.#doc;
    if (spec.mark === 'scatter' || spec.mark === 'pie' && spec.x === undefined) return [];
    const out: HTMLElement[] = [];
    const without = <K extends 'grain' | 'bins'>(key: K): ChartSpec => {
      const { [key]: _gone, ...rest } = this.#spec!;
      return rest as ChartSpec;
    };
    if (isTemporal(xType)) {
      out.push(field(doc, 'By:', dropdown<TimeGrain>(doc, spec.grain,
        TIME_GRAINS.map((g) => ({ value: g, label: GRAIN_LABELS[g] })), (v) => {
          if (!this.#spec) return;
          this.#spec = without('grain');
          this.#set(v ? { grain: v } : {});
        }, { allowNone: true })));
    } else if (isNumeric(xType)) {
      out.push(field(doc, 'Bin width:', numberInput(doc, spec.bins?.width, (v) => {
        if (!this.#spec) return;
        this.#spec = without('bins');
        this.#set(v !== undefined && v > 0 ? { bins: { width: v } } : {});
      }, { min: 0, width: 90 })));
    }
    const first = spec.y[0];
    if (first && spec.mark !== 'pie' && spec.mark !== 'heatmap') {
      out.push(field(doc, 'Show as:', dropdown<ShowAsKind>(doc, first.showAs?.kind,
        SHOW_AS_KINDS.map((k) => ({ value: k, label: SHOW_AS_LABELS[k] })), (v) => {
          const now = this.#spec?.y[0];
          if (!now || !this.#spec) return;
          const { showAs: _old, ...measure } = now;
          this.#set({ y: [v ? { ...measure, showAs: { kind: v } } : measure, ...this.#spec.y.slice(1)] }, true);
        }, { allowNone: true })));
      if (first.showAs?.kind === 'movingAverage') {
        out.push(field(doc, 'Over rows:', numberInput(doc, first.showAs.rows ?? 3, (v) => {
          const now = this.#spec?.y[0];
          if (!now || !this.#spec || v === undefined || v < 1) return;
          this.#set({ y: [{ ...now, showAs: { kind: 'movingAverage', rows: Math.floor(v) } }, ...this.#spec.y.slice(1)] });
        }, { min: 1, max: 365, step: 1, width: 70 })));
      }
    }
    return out;
  }

  // -- drawing ----------------------------------------------------------------

  /** The chart as drawn now, as a picture, for an export of the page; null before it has drawn. */
  picture(): ChartPicture | null {
    return this.#chart?.picture() ?? null;
  }

    async #draw(): Promise<void> {
    if (this.#disposed) return;
    const spec = this.#spec;
    const cube = this.#options.snapshot();
    if (!spec) return;
    // A TREEMAP DRAWS THE GRID AS SHOWN, with no query of its own (the old treemap's way, the user
    // 2026-09-30): following, the rows on screen now; frozen, the rows it last drew.
    if (spec.mark === 'treemap') {
      this.#clearKpi();
      const now = this.frozen && this.#shownKept ? this.#shownKept : this.#options.shown?.() ?? this.#shownKept;
      if (!now) {
        this.#say('A treemap draws the grid\'s rows; the grid has none to draw yet.', true);
        return;
      }
      this.#shownKept = now;
      if (!this.#chart) {
        const { mountChart } = await import('../chart-render.ts');
        if (this.#disposed) return;
        this.#chart ??= mountChart(this.#canvas, (key, upper) => this.#pick(key, upper));
      }
      const { treemapOfGrid, themeOf } = await import('../chart-option.ts');
      if (this.#disposed) return;
      const drawing = treemapOfGrid(now, themeOf(this.#canvas), this.#options.label);
      this.#chart.show(drawing);
      this.#say(drawing.notes.join(' '));
      return;
    }
    const problems = chartProblems(spec, cube);
    if (problems.length > 0) {
      this.#say(problems.join(' '), true);
      return;
    }
    if (spec.mark === 'kpi') {
      await this.#drawKpi(spec, cube);
      return;
    }
    this.#clearKpi();
    this.#inflight?.abort();
    const abort = new AbortController();
    this.#inflight = abort;
    // busy, not a "Loading" line: a line that comes and goes resizes the chart twice
    this.#canvas.setAttribute('aria-busy', 'true');
    try {
      const { query, snapshot } = chartQuery(cube, spec);
      const rows = await this.#options.run(query, snapshot, abort.signal);
      if (abort.signal.aborted || this.#disposed) return;
      if (!this.#chart) {
        const { mountChart } = await import('../chart-render.ts');
        if (abort.signal.aborted || this.#disposed) return;
        this.#chart ??= mountChart(this.#canvas, (key, upper) => this.#pick(key, upper));
      }
      const { chartOption, themeOf } = await import('../chart-option.ts');
      if (abort.signal.aborted || this.#disposed) return;
      const drawing = chartOption(spec, rows, themeOf(this.#canvas), this.#options.label, this.#options.unit);
      this.#chart.show(drawing);
      const capped = rows.rowCount >= (spec.mark === 'scatter' ? Infinity : spec.options.limit);
      this.#say([
        ...drawing.notes,
        ...(capped ? [`The first ${spec.options.limit} by the chosen order; raise "At most" to see more.`] : []),
      ].join(' '));
    } catch (e) {
      if (abort.signal.aborted || this.#disposed) return;
      this.#say(e instanceof Error ? e.message : String(e), true);
    } finally {
      if (this.#inflight === abort) this.#canvas.removeAttribute('aria-busy');
    }
  }

  /**
   * A KPI: its number as the grid writes it, against its target (good or not), and under them its trend's line -- its
   * value from one query, the trend from another, both the cube's own (chart-spec.ts `kpiQueries`).
   */
  async #drawKpi(spec: ChartSpec, cube: CubeSnapshot): Promise<void> {
    this.#inflight?.abort();
    const abort = new AbortController();
    this.#inflight = abort;
    this.#canvas.setAttribute('aria-busy', 'true');
    try {
      const { total, trend } = kpiQueries(cube, spec);
      const [rows, line] = await Promise.all([
        this.#options.run(total.query, total.snapshot, abort.signal),
        trend ? this.#options.run(trend.query, trend.snapshot, abort.signal) : Promise.resolve(null),
      ]);
      if (abort.signal.aborted || this.#disposed) return;
      const { kpiDrawing, themeOf } = await import('../chart-option.ts');
      if (abort.signal.aborted || this.#disposed) return;
      const drawing = kpiDrawing(spec, rows, line, themeOf(this.#canvas), this.#options.label);
      // a chart drawn before (another kind) gives way to the number
      this.#chart?.dispose();
      this.#chart = null;
      this.#trend?.dispose();
      this.#trend = null;
      const doc = this.#doc;
      const box = doc.createElement('div');
      box.className = 'dc-kpi';
      const value = doc.createElement('div');
      value.className = 'dc-kpi-value';
      value.textContent = drawing.value;
      box.append(value);
      if (drawing.target) {
        const against = doc.createElement('div');
        against.className = `dc-kpi-target ${drawing.target.good ? 'dc-kpi-good' : 'dc-kpi-bad'}`;
        against.textContent = drawing.target.text;
        box.append(against);
      }
      const place = doc.createElement('div');
      place.className = 'dc-kpi-trend';
      if (drawing.trend) box.append(place);
      this.#canvas.replaceChildren(box);
      this.#canvas.setAttribute('aria-label', drawing.description);
      this.#canvas.classList.add('dc-chartpanel-kpi');
      if (drawing.trend) {
        const { mountChart } = await import('../chart-render.ts');
        if (abort.signal.aborted || this.#disposed) return;
        this.#trend = mountChart(place);
        this.#trend.show(drawing.trend);
      }
      this.#say(drawing.notes.join(' '));
    } catch (e) {
      if (abort.signal.aborted || this.#disposed) return;
      this.#say(e instanceof Error ? e.message : String(e), true);
    } finally {
      if (this.#inflight === abort) this.#canvas.removeAttribute('aria-busy');
    }
  }

  /** A KPI drawn before gives way to a chart: its number and its trend gone. */
  #clearKpi(): void {
    if (!this.#canvas.classList.contains('dc-chartpanel-kpi')) return;
    this.#trend?.dispose();
    this.#trend = null;
    this.#canvas.replaceChildren();
    this.#canvas.classList.remove('dc-chartpanel-kpi');
  }

  /** A clicked mark, as conditions on the cube's own columns (chart-spec.ts `markConditions`). */
  #pick(key: MarkKey, upper?: Scalar): void {
    const spec = this.#spec;
    if (!spec) return;
    this.#options.onPick(markConditions(spec, this.#options.snapshot(), key, upper));
  }

  #say(text: string, bad = false): void {
    // said only when there is something to say: a small chart needs its height
    this.#status.hidden = text === '';
    this.#status.textContent = text;
    this.#status.classList.toggle('dc-chartpanel-bad', bad);
  }
}
