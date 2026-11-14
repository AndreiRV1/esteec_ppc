import { DecimalPipe } from '@angular/common';
import {
  Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, signal, viewChild,
} from '@angular/core';
import { Bucket, ConsumptionPoint } from '../core/models';

const HEIGHT = 240;
const MARGIN = { top: 14, right: 8, bottom: 26, left: 46 };

const number = (value: number, digits = 2) => value.toLocaleString('ro-RO', { maximumFractionDigits: digits });

/** A "nice" axis step (1, 2, 2.5, 5 × 10ⁿ) giving about four gridlines. */
function niceStep(max: number): number {
  if (max <= 0) return 1;
  const raw = max / 4;
  const power = 10 ** Math.floor(Math.log10(raw));
  const fraction = raw / power;
  return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10) * power;
}

/**
 * Energy per interval as bars: one series, one colour, with a hover/keyboard
 * tooltip and the same data as a table. Intervals whose readings were flagged
 * as anomalous get a marker (shape + legend text, not colour alone).
 */
@Component({
  selector: 'fg-consumption-chart',
  imports: [DecimalPipe],
  template: `
    <div class="box" #box>
      @if (points().length === 0) {
        <p class="muted empty">Nu există citiri în intervalul ales.</p>
      } @else {
        <svg
          [attr.width]="width()" [attr.height]="height" [attr.viewBox]="'0 0 ' + width() + ' ' + height"
          role="img" tabindex="0" [attr.aria-label]="description()"
          (pointermove)="onPointer($event)" (pointerdown)="onPointer($event)" (pointerleave)="active.set(null)"
          (keydown)="onKey($event)" (blur)="active.set(null)">
          <text class="axis" x="0" y="10">kWh</text>
          @for (tick of geometry().ticks; track tick.value) {
            <line class="grid" [attr.x1]="margin.left" [attr.x2]="width() - margin.right" [attr.y1]="tick.y" [attr.y2]="tick.y" />
            <text class="axis" text-anchor="end" [attr.x]="margin.left - 8" [attr.y]="tick.y + 4">{{ tick.text }}</text>
          }
          @for (bar of geometry().bars; track bar.index) {
            <path class="bar" [class.active]="active() === bar.index" [attr.d]="bar.path" />
            @if (bar.marker) {
              <path class="anomaly" [attr.d]="bar.marker" />
            }
          }
          @for (tick of geometry().labels; track tick.x) {
            <text class="axis" text-anchor="middle" [attr.x]="tick.x" [attr.y]="height - 6">{{ tick.text }}</text>
          }
        </svg>
        @if (tooltip(); as tip) {
          <div class="tip" [style.left.px]="tip.left" [style.top.px]="tip.top">
            <span class="muted">{{ tip.when }}</span>
            <strong>{{ tip.point.kwh | number: '1.0-2' }} kWh</strong>
            @if (tip.point.anomalies) {
              <span>▲ {{ tip.point.anomalies }} {{ tip.point.anomalies === 1 ? 'citire neobișnuită' : 'citiri neobișnuite' }}</span>
            }
          </div>
        }
      }
    </div>
    @if (hasAnomalies()) {
      <p class="muted key"><span class="mark" aria-hidden="true">▲</span> interval cu citiri neobișnuite, verificate înainte de facturare</p>
    }
    @if (points().length) {
      <details>
        <summary>Vezi datele ca tabel</summary>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Interval</th><th>Consum (kWh)</th><th>Citiri neobișnuite</th></tr></thead>
            <tbody>
              @for (point of points(); track point.start) {
                <tr><td>{{ when(point.start) }}</td><td class="data">{{ point.kwh | number: '1.0-3' }}</td><td>{{ point.anomalies || '—' }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </details>
    }
  `,
  styles: `
    .box { position: relative; min-height: 120px; }
    svg { display: block; touch-action: pan-y; }
    svg:focus-visible { outline-offset: 4px; }
    .grid { stroke: var(--line); stroke-width: 1; }
    .axis { fill: var(--ink-muted); font: 500 12px var(--font-body); font-variant-numeric: tabular-nums; }
    .bar { fill: var(--chart); rx: 2px; }
    .bar.active { fill: var(--primary-dark); }
    .anomaly { fill: #c2410c; stroke: var(--card); stroke-width: 2; paint-order: stroke; }
    .mark { color: #c2410c; }
    .tip {
      position: absolute; transform: translate(-50%, calc(-100% - 10px)); pointer-events: none;
      background: var(--ink); color: #fff; border-radius: var(--radius); padding: 8px 12px; box-shadow: var(--shadow);
      display: grid; gap: 2px; font-size: 0.85rem; white-space: nowrap;
    }
    .tip .muted { color: #b8c4d1; }
    .key { font-size: 0.9rem; margin-top: 8px; }
    .empty { padding: 40px 0; text-align: center; }
    details { margin-top: 12px; }
    summary { cursor: pointer; color: var(--primary-dark); font-weight: 500; padding: 8px 0; }
    .table-wrap { max-height: 320px; overflow: auto; }
  `,
})
export class ConsumptionChart {
  readonly points = input.required<ConsumptionPoint[]>();
  readonly bucket = input.required<Bucket>();

  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;
  protected readonly width = signal(640);
  protected readonly active = signal<number | null>(null);
  private readonly box = viewChild.required<ElementRef<HTMLElement>>('box');

  protected readonly hasAnomalies = computed(() => this.points().some((point) => point.anomalies > 0));

  protected readonly geometry = computed(() => {
    const points = this.points();
    const plotWidth = this.width() - MARGIN.left - MARGIN.right;
    const baseline = HEIGHT - MARGIN.bottom;
    const step = niceStep(Math.max(0, ...points.map((point) => point.kwh)));
    const top = Math.max(step, step * Math.ceil(Math.max(0, ...points.map((p) => p.kwh)) / step));
    const y = (kwh: number) => baseline - (kwh / top) * (baseline - MARGIN.top);
    const band = plotWidth / Math.max(1, points.length);
    const gap = band >= 6 ? 2 : band >= 3 ? 1 : 0;
    const barWidth = Math.max(1, Math.min(48, band - gap));

    const bars = points.map((point, index) => {
      const x = MARGIN.left + index * band + (band - barWidth) / 2;
      const barTop = Math.min(y(point.kwh), baseline - 1);
      const r = Math.min(4, barWidth / 2, baseline - barTop);
      const path = `M${x},${baseline}V${barTop + r}Q${x},${barTop} ${x + r},${barTop}H${x + barWidth - r}` +
        `Q${x + barWidth},${barTop} ${x + barWidth},${barTop + r}V${baseline}Z`;
      const cx = x + barWidth / 2;
      const marker = point.anomalies ? `M${cx - 5},${barTop - 4}L${cx + 5},${barTop - 4}L${cx},${barTop - 12}Z` : null;
      return { index, path, marker, cx, top: barTop };
    });

    const ticks = [];
    for (let value = 0; value <= top + step / 2; value += step) ticks.push({ value, y: y(value), text: number(value) });

    // As many x labels as fit without touching (about one per 90 px).
    const every = Math.max(1, Math.ceil(points.length / Math.max(2, Math.floor(plotWidth / 90))));
    const labels = bars.filter((bar) => bar.index % every === 0).map((bar) => ({ x: bar.cx, text: this.short(points[bar.index].start) }));
    return { bars, ticks, labels, band };
  });

  protected readonly tooltip = computed(() => {
    const index = this.active();
    const bar = index === null ? undefined : this.geometry().bars[index];
    if (!bar) return null;
    return {
      point: this.points()[bar.index],
      when: this.when(this.points()[bar.index].start),
      left: Math.min(this.width() - 90, Math.max(90, bar.cx)),
      top: Math.max(64, bar.top),
    };
  });

  protected readonly description = computed(() => {
    const points = this.points();
    const total = points.reduce((sum, point) => sum + point.kwh, 0);
    const peak = points.reduce((best, point) => (point.kwh > best.kwh ? point : best), points[0]);
    return `Grafic de consum: ${points.length} intervale, total ${number(total, 1)} kWh, ` +
      `maxim ${number(peak.kwh)} kWh la ${this.when(peak.start)}. Săgețile stânga și dreapta parcurg intervalele.`;
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const observer = new ResizeObserver(([entry]) => this.width.set(Math.max(260, Math.floor(entry.contentRect.width))));
      observer.observe(this.box().nativeElement);
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  protected onPointer(event: PointerEvent): void {
    const x = event.offsetX - MARGIN.left;
    const index = Math.floor(x / this.geometry().band);
    this.active.set(index >= 0 && index < this.points().length ? index : null);
  }

  protected onKey(event: KeyboardEvent): void {
    const last = this.points().length - 1;
    const current = this.active();
    if (event.key === 'ArrowRight') this.active.set(current === null ? 0 : Math.min(last, current + 1));
    else if (event.key === 'ArrowLeft') this.active.set(current === null ? last : Math.max(0, current - 1));
    else if (event.key === 'Escape') this.active.set(null);
    else return;
    event.preventDefault();
  }

  protected when(start: string): string {
    return new Date(start).toLocaleString('ro-RO', this.bucket() === 'DAY'
      ? { weekday: 'short', day: 'numeric', month: 'short' }
      : { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  }

  private short(start: string): string {
    const date = new Date(start);
    return this.bucket() === 'DAY' || date.getHours() === 0
      ? date.toLocaleDateString('ro-RO', { day: 'numeric', month: 'short' })
      : date.toLocaleTimeString('ro-RO', { hour: '2-digit', minute: '2-digit' });
  }
}
