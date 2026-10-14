// The example page of batch 3 (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3, §3.3): every new kind over the sales
// sample, a sheet per family, each simple to complex. Built as batch 2's page is (charts-dashboard.ts salesPage), so it
// moves with the spec; test/examples.test.ts reads it back and finds every chart drawable, and
// test/chart-queries.test.ts plans and runs each chart's query on the sample.

import type { PageDocument } from '../page-document.ts';
import { BY_TIME, chart, PROFIT, REVENUE, salesPage, type ExampleSheet } from './charts-dashboard.ts';

const COST = { column: 'cost', fn: 'sum' } as const;
const UNITS = { column: 'units', fn: 'sum' } as const;
const TARGET = { column: 'target', fn: 'sum' } as const;

const SHEETS: readonly ExampleSheet[] = [
  {
    name: 'Comparing',
    views: [
      chart('k-lollipop', 'Units by product: a lollipop', { mark: 'lollipop', x: 'product', y: [UNITS], options: { orientation: 'horizontal' } }),
      chart('k-dumbbell', 'Cost against revenue by country: a dumbbell', {
        mark: 'dumbbell', x: 'country', y: [COST, REVENUE], options: { orientation: 'horizontal' },
      }),
      chart('k-range', 'The smallest and largest sale by category: a range', {
        mark: 'range', x: 'category', y: [{ column: 'revenue', fn: 'min' }, { column: 'revenue', fn: 'max' }],
      }),
      chart('k-waterfall', 'Profit by category, adding up: a waterfall', { mark: 'waterfall', x: 'category', y: [PROFIT], options: { labels: true } }),
      chart('k-pareto', 'Revenue by product, the vital few: a Pareto', { mark: 'pareto', x: 'product' }),
      chart('k-bump', 'Each region\'s rank by revenue, month by month: a bump chart', {
        mark: 'line', x: 'sold', grain: 'quarter', split: 'region', y: [{ ...REVENUE, showAs: { kind: 'rank' } }],
        options: { ...BY_TIME, labels: true },
      }),
    ],
  },
  {
    name: 'Around a circle',
    views: [
      chart('k-rose', 'Revenue by category: a rose', { mark: 'rose', x: 'category' }),
      chart('k-polar', 'Revenue by category and channel: a polar bar, stacked', {
        mark: 'polar', x: 'category', split: 'channel', options: { stack: 'stacked' },
      }),
      chart('k-radial', 'Units by region: radial bars', { mark: 'polar', x: 'region', y: [UNITS], options: { orientation: 'horizontal' } }),
      chart('k-half', 'Revenue by channel: half a ring', { mark: 'pie', x: 'channel', options: { half: true, labels: true } }),
    ],
  },
  {
    name: 'Part of a whole',
    views: [
      chart('k-funnel', 'Revenue by channel: a funnel', { mark: 'funnel', x: 'channel', options: { labels: true } }),
      chart('k-rings', 'Revenue by region, then channel: a pie in two rings', { mark: 'pie', x: 'region', split: 'channel' }),
      chart('k-sunburst', 'The grid as shown, as rings: a sunburst', { mark: 'sunburst', x: 'region' }),
      chart('k-tree', 'The grid as shown, as branches: a tree', { mark: 'tree', x: 'region' }),
    ],
  },
  {
    name: 'Flows',
    views: [
      chart('k-sankey', 'Revenue from each region to each channel: a Sankey', { mark: 'sankey', x: 'region', split: 'channel' }),
      chart('k-sankey2', 'Revenue from each category to each region: a Sankey', { mark: 'sankey', x: 'category', split: 'region' }),
      chart('k-chord', 'Revenue between categories and channels: a chord diagram', { mark: 'chord', x: 'category', split: 'channel' }),
    ],
  },
  {
    name: 'Over time',
    views: [
      chart('k-stream', 'Revenue by week, by category: a streamgraph', { mark: 'stream', x: 'sold', grain: 'week', split: 'category', options: BY_TIME }),
      chart('k-calendar', 'Revenue by day: a calendar', { mark: 'calendar', x: 'sold', options: BY_TIME }),
      chart('k-slope', 'Revenue by region, one year to the next: a slope chart', {
        mark: 'line', x: 'sold', grain: 'year', split: 'region', options: { ...BY_TIME, labels: true },
      }),
      chart('k-band', 'The smallest and largest sale each month: a range', {
        mark: 'range', x: 'sold', grain: 'month', y: [{ column: 'revenue', fn: 'min' }, { column: 'revenue', fn: 'max' }], options: BY_TIME,
      }),
    ],
  },
  {
    name: 'Many measures',
    views: [
      chart('k-radar', 'Each region\'s revenue, cost, profit and target: a radar', { mark: 'radar', x: 'region', y: [REVENUE, COST, PROFIT, TARGET] }),
      chart('k-parallel', 'Each product through its revenue, cost, profit and units: parallel coordinates', {
        mark: 'parallel', x: 'product', y: [REVENUE, COST, PROFIT, UNITS],
      }),
    ],
  },
  {
    name: 'One value',
    views: [
      chart('k-gauge', 'Revenue against its target: a gauge', { mark: 'gauge', options: { target: 40_000_000 } }),
      chart('k-ring', 'Profit against its target: a progress ring', { mark: 'gauge', y: [PROFIT], options: { target: 15_000_000, ring: true } }),
    ],
  },
];

/** The page: a sheet per family of batch 3's kinds, and the grid they are views of, its regions open. */
export async function chartsKindsPage(): Promise<PageDocument> {
  return salesPage('More chart kinds', SHEETS, [['EMEA'], ['AMER'], ['APAC']]);
}
