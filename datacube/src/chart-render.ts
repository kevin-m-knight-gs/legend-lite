// The ONE file that draws with ECharts.
//
// Only what DataCube uses is registered (`echarts/core`), never the whole
// library: bar, line, scatter, pie and heatmap, with the grid, tooltip,
// legend, visual map, dataset and aria components, drawn on canvas. (The
// tests draw the same options as SVG in node, registering the SVG renderer
// themselves, so the page does not ship it.)
//
// Everything a chart shows comes from `chart-option.ts`; this file only
// mounts it, keeps it sized to its box, reads the theme from CSS tokens,
// and turns a click on a mark into the values behind it.

import * as echarts from 'echarts/core';
import { BarChart, HeatmapChart, LineChart, PieChart, ScatterChart, TreemapChart } from 'echarts/charts';
import {
  AriaComponent,
  DatasetComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';
import type { ChartDrawing, MarkKey } from './chart-option.ts';
import type { Scalar } from '../../engine-client/src/result.ts';

echarts.use([
  BarChart, LineChart, ScatterChart, PieChart, HeatmapChart, TreemapChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  DatasetComponent, VisualMapComponent, AriaComponent,
  CanvasRenderer,
]);

/**
 * A chart as a picture, for an export of the page: the live chart's own pixels at `ratio`
 * times its size, on white (a PNG for a page or a workbook, a JPEG a PDF embeds as it is).
 */
export interface ChartPicture {
  /** The chart's size on screen, in CSS pixels. */
  readonly width: number;
  readonly height: number;
  /** The image's size in pixels: `width` and `height` times the ratio. */
  readonly pixelWidth: number;
  readonly pixelHeight: number;
  readonly png: Uint8Array;
  readonly jpeg: Uint8Array;
}

/** A chart mounted in an element. */
export interface MountedChart {
  /** Draw (or redraw) a drawing. */
  show(drawing: ChartDrawing): void;
  /** The chart as drawn now, as a picture; null before it has drawn or where there is no canvas. */
  picture(ratio?: number): ChartPicture | null;
  dispose(): void;
}

/** A data: URL's bytes. */
function bytesOf(dataUrl: string): Uint8Array {
  const b64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Mount a chart in `el`: canvas, sized to the element and kept so.
 * `onPick` receives the values behind a clicked mark (a bar's category,
 * a slice, a heatmap cell); a mark that is not a group (a scatter point)
 * picks nothing.
 */
export function mountChart(el: HTMLElement, onPick?: (key: MarkKey, upper?: Scalar) => void): MountedChart {
  const chart = echarts.init(el, null, { renderer: 'canvas' });
  let drawing: ChartDrawing | null = null;
  chart.on('click', (params: unknown) => {
    const p = params as { seriesIndex?: number; dataIndex?: number; data?: unknown };
    if (!drawing || !onPick || p.seriesIndex === undefined || p.dataIndex === undefined) return;
    const key = drawing.keyAt(p.seriesIndex, p.dataIndex, p.data);
    if (key) onPick(key, drawing.upperAt?.(p.dataIndex));
  });
  const view = el.ownerDocument.defaultView;
  const Observer = view?.ResizeObserver;
  const observer = Observer ? new Observer(() => chart.resize()) : undefined;
  observer?.observe(el);
  return {
    picture(ratio = 2) {
      if (!drawing) return null;
      const width = chart.getWidth();
      const height = chart.getHeight();
      if (!(width > 0 && height > 0)) return null;
      try {
        const png = bytesOf(chart.getDataURL({ type: 'png', pixelRatio: ratio, backgroundColor: '#ffffff' }));
        const jpeg = bytesOf(chart.getDataURL({ type: 'jpeg', pixelRatio: ratio, backgroundColor: '#ffffff' }));
        if (png.length === 0 || jpeg.length === 0) return null;
        return { width, height, pixelWidth: Math.round(width * ratio), pixelHeight: Math.round(height * ratio), png, jpeg };
      } catch {
        // no canvas to read (a DOM without one): the export goes on without the picture
        return null;
      }
    },
    show(d) {
      drawing = d;
      el.setAttribute('aria-label', d.description);
      chart.setOption(d.option as EChartsOption, { notMerge: true });
    },
    dispose() {
      observer?.disconnect();
      chart.dispose();
    },
  };
}
