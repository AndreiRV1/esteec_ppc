import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ApiService, Loader } from '../core/api';
import { LabelPipe } from '../core/format';
import { CategoryStorageFit, DayCategoryMetrics, RegionalGridInsight, SystemStatistics } from '../core/models';
import { Status } from '../ui/status';

type SectionTab = 'storage' | 'peak' | 'grid';

/**
 * PDF §5 — Statistici și interpretarea datelor:
 *   1. Ce tip de storage se potrivește cel mai bine fiecărei categorii de date și de ce?
 *   2. Cum s-a comportat sistemul într-o zi de vârf (furtună 10x), comparativ cu una normală?
 *   3. Ce spun datele însele despre rețea (anomalii contoare, zonele cu cele mai multe avarii)?
 */
@Component({
  imports: [DecimalPipe, LabelPipe, Status],
  template: `
    <div class="page">
      <div class="page-head">
        <div class="intro">
          <h1>Statistici</h1>
        </div>
        <button class="secondary small" type="button" [disabled]="stats.loading()" (click)="reload()">
          Actualizează datele
        </button>
      </div>

      <div class="segmented" role="tablist" aria-label="Secțiune analiză">
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'storage'" (click)="tab.set('storage')">
          Stocare pe categorii
        </button>
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'peak'" (click)="tab.set('peak')">
          Zi normală vs. zi de vârf
        </button>
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'grid'" (click)="tab.set('grid')">
          Rețea pe regiuni
        </button>
      </div>

      <fg-status [of]="stats" />

      @if (stats.data(); as data) {
        @if (tab() === 'storage') {
          @for (fit of data.storageFit; track fit.category) {
            <section class="card" [attr.aria-labelledby]="'cat-' + fit.category">
              <div class="page-head">
                <h2 [id]="'cat-' + fit.category">{{ fit.category | label }}</h2>
                <span class="badge ACCEPTED">Recomandat: {{ fit.recommendedBackend | label }}</span>
              </div>

              <div class="table-wrap">
                <table class="stack">
                  <thead>
                    <tr>
                      <th>Storage testat</th>
                      <th>p50 (ms)</th>
                      <th>p95 (ms)</th>
                      <th>p99 (ms)</th>
                      <th>Throughput</th>
                      <th>Rată erori</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (c of fit.candidates; track c.backend) {
                      <tr [class.recommended-row]="c.backend === fit.recommendedBackend">
                        <td data-label="Storage testat">
                          <strong>{{ c.backend | label }}</strong>
                          @if (c.backend === fit.recommendedBackend) {
                            <span class="badge LOW">Optim</span>
                          }
                        </td>
                        <td data-label="p50 (ms)" class="data">{{ n(c.p50Ms, ' ms') }}</td>
                        <td data-label="p95 (ms)" class="data">{{ n(c.p95Ms, ' ms') }}</td>
                        <td data-label="p99 (ms)" class="data">{{ n(c.p99Ms, ' ms') }}</td>
                        <td data-label="Throughput" class="data">{{ n(c.throughputMbps, ' MB/s') }}</td>
                        <td data-label="Rată erori" class="data">{{ n(c.errorRatePercent, '%', 2) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          }
        }

        @if (tab() === 'peak') {
          <section class="card" aria-labelledby="peak-table">
            <div class="page-head">
              <h2 id="peak-table">Zi normală vs. zi de vârf</h2>
            </div>

            <div class="bars-grid">
              @for (item of data.normalVsPeak; track item.category) {
                <div class="bar-card">
                  <div class="page-head">
                    <strong>{{ item.category | label }}</strong>
                    <span class="badge" [class.ACCEPTED]="item.slaMet" [class.CRITICAL]="!item.slaMet">
                      {{ item.slaMet ? 'SLA respectat' : 'SLA în pericol' }}
                    </span>
                  </div>
                  <div class="bar-pair">
                    <div class="bar-row">
                      <span class="muted">Normal (p95)</span>
                      <div class="bar-track">
                        <div class="bar-fill normal" [style.width.%]="latencyPct(item.normalP95Ms, item)"></div>
                      </div>
                      <span class="data">{{ n(item.normalP95Ms, ' ms', 0) }}</span>
                    </div>
                    <div class="bar-row">
                      <span class="muted">Vârf 10× (p95)</span>
                      <div class="bar-track">
                        <div class="bar-fill peak" [style.width.%]="latencyPct(item.peakP95Ms, item)"></div>
                      </div>
                      <span class="data">{{ n(item.peakP95Ms, ' ms', 0) }}</span>
                    </div>
                  </div>
                  <p class="muted small-note">
                    Volum: <span class="data">{{ item.normalFilesPerDay | number }}</span> →
                    <span class="data">{{ item.peakFilesPerDay | number }}</span> fișiere/zi
                    ({{ item.normalVolumeMb | number: '1.0-0' }} MB → {{ item.peakVolumeMb | number: '1.0-0' }} MB)
                  </p>
                </div>
              }
            </div>

            <div class="table-wrap">
              <table class="stack">
                <thead>
                  <tr>
                    <th>Categorie</th>
                    <th>Fișiere (Normal → Vârf)</th>
                    <th>Volum (Normal → Vârf)</th>
                    <th>Latență p95 (Normal → Vârf)</th>
                    <th>Rată erori finală</th>
                    <th>Reîncercări / Resume</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of data.normalVsPeak; track row.category) {
                    <tr>
                      <td data-label="Categorie"><strong>{{ row.category | label }}</strong></td>
                      <td data-label="Fișiere" class="data">
                        {{ row.normalFilesPerDay | number }} → {{ row.peakFilesPerDay | number }}
                      </td>
                      <td data-label="Volum" class="data">
                        {{ row.normalVolumeMb | number: '1.0-0' }} MB → {{ row.peakVolumeMb | number: '1.0-0' }} MB
                      </td>
                      <td data-label="Latență p95" class="data">
                        {{ n(row.normalP95Ms, ' ms', 0) }} → {{ n(row.peakP95Ms, ' ms', 0) }}
                      </td>
                      <td data-label="Rată erori finală" class="data">
                        {{ row.normalErrorRatePercent | number: '1.2-2' }}% → {{ row.peakErrorRatePercent | number: '1.2-2' }}%
                      </td>
                      <td data-label="Reîncercări / Resume" class="data">{{ n(row.resumedOrRetried, '', 0) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        @if (tab() === 'grid') {
          <div class="grid-3">
            @for (reg of data.regionalInsights; track reg.region) {
              <section class="card region-card" [attr.aria-labelledby]="'reg-' + reg.region">
                <div class="page-head">
                  <h2 [id]="'reg-' + reg.region">{{ reg.region | label }}</h2>
                  @if (reg.activeFaults > 0) {
                    <span class="badge HIGH">{{ reg.activeFaults }} avarii active</span>
                  } @else {
                    <span class="badge LOW">Fără avarii active</span>
                  }
                </div>

                <dl class="facts">
                  <dt>Contoare active</dt>
                  <dd class="data">{{ reg.activeMeters | number }}</dd>
                  <dt>Citiri (24h)</dt>
                  <dd class="data">{{ reg.readings24h | number }}</dd>
                  <dt>Anomalii detectate</dt>
                  <dd class="data warn-text">{{ reg.anomalies24h | number }}</dd>
                  <dt>Mesaje respinse</dt>
                  <dd class="data">{{ reg.duplicatesFiltered24h | number }}</dd>
                  <dt>Tensiune medie</dt>
                  <dd class="data">{{ n(reg.avgVoltageV, ' V') }}</dd>
                  <dt>Vârf de sarcină</dt>
                  <dd class="data">{{ reg.peakLoadWindow }}</dd>
                  <dt>Avarii (30 zile)</dt>
                  <dd class="data">{{ reg.faults30d }} ({{ reg.affectedCustomers30d | number }} clienți)</dd>
                  <dt>Timp mediu remediere</dt>
                  <dd class="data">{{ n(reg.avgRestoreMinutes, ' min', 0) }}</dd>
                  <dt>Zonă vulnerabilă</dt>
                  <dd><strong>{{ reg.hotspotZone }}</strong> ({{ reg.hotspotSubstation }})</dd>
                  <dt>Cauză dominantă</dt>
                  <dd>{{ reg.dominantFaultCause }}</dd>
                  <dt>Inspecții dronă</dt>
                  <dd class="data">{{ reg.droneVideosStored }} video-uri</dd>
                </dl>
              </section>
            }
          </div>
        }
      }
    </div>
  `,
  styles: `
    .segmented { justify-self: start; max-width: 100%; overflow-x: auto; }
    h2, th, .segmented button { white-space: nowrap; }
    h2 { overflow: hidden; text-overflow: ellipsis; min-width: 0; }
    .grid-3 { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); }
    .warn-text { color: var(--warn-ink); font-weight: 600; }
    .recommended-row, .recommended-row:hover { background: var(--primary-tint); }
    .bars-grid { display: grid; gap: 14px; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); }
    .bar-card { background: var(--surface); border-radius: var(--radius); padding: 16px; display: grid; gap: 10px; }
    .bar-pair { display: grid; gap: 6px; }
    .bar-row { display: grid; grid-template-columns: 95px 1fr 64px; gap: 8px; align-items: center; font-size: 0.9rem; }
    .bar-track { height: 10px; background: #dfe6ee; border-radius: 999px; overflow: hidden; }
    .bar-fill { height: 100%; border-radius: 999px; }
    .bar-fill.normal { background: var(--chart); }
    .bar-fill.peak { background: var(--hazard); }
    .small-note { font-size: 0.88rem; }
    .region-card { align-content: space-between; }
  `,
})
export class StatisticsPage {
  private readonly api = inject(ApiService);
  protected readonly tab = signal<SectionTab>('storage');
  protected readonly stats = new Loader<SystemStatistics>();


  constructor() {
    this.reload();
  }

  protected reload(): void {
    void this.stats.run(() => this.api.statistics());
  }

  /** A measured value with its unit, or a dash when the backend has not measured it yet. */
  protected n(value: number | null | undefined, unit: string, digits = 1): string {
    if (value === null || value === undefined) return '—';
    return value.toLocaleString('ro-RO', { maximumFractionDigits: digits }) + unit;
  }

  protected latencyPct(value: number | null, row: DayCategoryMetrics): number {
    if (value === null) return 0;
    const max = Math.max(row.normalP95Ms ?? 0, row.peakP95Ms ?? 0, 1);
    return Math.min(100, Math.max(6, Math.round((value / max) * 100)));
  }
}
