// THE CHART GALLERY (the user, 2026-10-23: "add all the new chart types to our chart demo ... one gallery is great and
// turn on fit to page so users don't need to scroll"): one chart of each kind DataCube draws (chart-spec.ts
// CHART_MARKS), titled by its kind, over the sales sample -- a sheet per family, each fitting its window. Built as the
// other example pages are (charts-dashboard.ts salesPage); test/examples.test.ts holds it to every kind, each drawable.

import { boxMeasures, MAP_LIMIT, type ChartMark } from '../chart-spec.ts';
import type { PageDocument } from '../page-document.ts';
import { BY_TIME, chart, PROFIT, REVENUE, salesPage, type ExampleSheet } from './charts-dashboard.ts';

const COST = { column: 'cost', fn: 'sum' } as const;
const UNITS = { column: 'units', fn: 'sum' } as const;
const TARGET = { column: 'target', fn: 'sum' } as const;

/** A kind's chart, its id and title naming it: `g-<kind>`, "<Kind>: <what it shows>". */
const of = (mark: ChartMark, label: string, says: string, spec: Parameters<typeof chart>[2] = {}) =>
  chart(`g-${mark}`, `${label}: ${says}`, { mark, ...spec });

export const GALLERY_SHEETS: readonly ExampleSheet[] = [
  {
    name: 'Bars and lines',
    views: [
      of('bar', 'Bar', 'revenue by region', { x: 'region' }),
      of('line', 'Line', 'revenue by month', { x: 'sold', grain: 'month', options: BY_TIME }),
      of('area', 'Area', 'revenue by month and channel, stacked', {
        x: 'sold', grain: 'month', split: 'channel', options: { ...BY_TIME, stack: 'stacked' },
      }),
      of('scatter', 'Scatter', 'each sale\'s cost against its revenue', { x: 'cost', y: [REVENUE] }),
      of('lollipop', 'Lollipop', 'units by product', { x: 'product', y: [UNITS], options: { orientation: 'horizontal' } }),
      of('dumbbell', 'Dumbbell', 'cost against revenue by country', { x: 'country', y: [COST, REVENUE], options: { orientation: 'horizontal' } }),
    ],
  },
  {
    name: 'Comparing',
    views: [
      of('range', 'Range', 'the smallest and largest sale by category', {
        x: 'category', y: [{ column: 'revenue', fn: 'min' }, { column: 'revenue', fn: 'max' }],
      }),
      of('waterfall', 'Waterfall', 'profit by category, adding up', { x: 'category', y: [PROFIT], options: { labels: true } }),
      of('pareto', 'Pareto', 'revenue by product, the vital few', { x: 'product' }),
      of('funnel', 'Funnel', 'revenue by channel', { x: 'channel', options: { labels: true } }),
      of('heatmap', 'Heatmap', 'revenue by month and region', { x: 'sold', grain: 'month', split: 'region', options: BY_TIME }),
      of('gantt', 'Gantt', 'when each product first and last sold', {
        x: 'product', y: [{ column: 'sold', fn: 'min' }, { column: 'sold', fn: 'max' }], options: { sort: { by: 'y', direction: 'asc' } },
      }),
    ],
  },
  {
    name: 'Part of a whole',
    views: [
      of('pie', 'Pie', 'revenue by channel', { x: 'channel', options: { labels: true } }),
      of('treemap', 'Treemap', 'the grid as shown, as blocks', { x: 'region' }),
      of('sunburst', 'Sunburst', 'the grid as shown, as rings', { x: 'region' }),
      of('tree', 'Tree', 'the grid as shown, as branches', { x: 'region' }),
      of('waffle', 'Waffle', 'revenue by channel, in a hundred cells', { x: 'channel' }),
      of('marimekko', 'Marimekko', 'revenue by region, each made of its channels', {
        x: 'region', split: 'channel', options: { sort: { by: 'x', direction: 'asc' } },
      }),
    ],
  },
  {
    name: 'Circles and flows',
    views: [
      of('rose', 'Rose', 'revenue by category', { x: 'category' }),
      of('polar', 'Polar bar', 'revenue by category and channel, stacked', { x: 'category', split: 'channel', options: { stack: 'stacked' } }),
      of('radar', 'Radar', 'each region\'s revenue, cost, profit and target', { x: 'region', y: [REVENUE, COST, PROFIT, TARGET] }),
      of('sankey', 'Sankey', 'revenue from each region to each channel', { x: 'region', split: 'channel' }),
      of('chord', 'Chord', 'revenue between categories and channels', { x: 'category', split: 'channel' }),
      of('parallel', 'Parallel', 'each product through its revenue, cost, profit and units', { x: 'product', y: [REVENUE, COST, PROFIT, UNITS] }),
    ],
  },
  {
    name: 'Time and spread',
    views: [
      of('stream', 'Streamgraph', 'revenue by week, by category', { x: 'sold', grain: 'week', split: 'category', options: BY_TIME }),
      of('calendar', 'Calendar', 'revenue by day', { x: 'sold', options: BY_TIME }),
      of('boxplot', 'Box plot', 'the spread of each sale\'s revenue by category', { x: 'category', y: boxMeasures('revenue') }),
      of('matrix', 'Scatter matrix', 'each sale\'s revenue, cost and profit against each other', {
        y: [REVENUE, COST, PROFIT], split: 'channel',
      }),
    ],
  },
  {
    name: 'Maps and one value',
    views: [
      of('map', 'Map', 'revenue by country', { x: 'country_code', options: { limit: MAP_LIMIT } }),
      of('bubblemap', 'Bubble map', 'revenue by city, at each city', { x: 'city', at: { lat: 'lat', lon: 'lon' }, options: { limit: MAP_LIMIT } }),
      of('kpi', 'KPI', 'revenue against its target, and its trend by month', { x: 'sold', grain: 'month', options: { target: 40_000_000 } }),
      of('gauge', 'Gauge', 'revenue against its target', { options: { target: 40_000_000 } }),
    ],
  },
];

/** The page: a sheet per family, each of its kinds once, three to a row, every sheet fitting its window. */
export async function chartsGalleryPage(): Promise<PageDocument> {
  return salesPage('Chart gallery', GALLERY_SHEETS, [['EMEA'], ['AMER'], ['APAC']], { fit: true, perRow: 3 });
}
