import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, Loader } from '../core/api';
import { LabelPipe, dayToIso, toDateInput } from '../core/format';
import { Bucket, Consumption, ConsumptionSummary, Fault, Me, RequestSummary } from '../core/models';
import { ConsumptionChart } from '../ui/consumption-chart';
import { Status } from '../ui/status';

const DAY_MS = 86_400_000;
const MAX_HOURLY_DAYS = 14;

/**
 * The client's home: active fault banner, this month's consumption, the
 * consumption chart and their connection requests. Each section loads and
 * fails on its own, so one unavailable service does not blank the page.
 */
@Component({
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, PercentPipe, LabelPipe, ConsumptionChart, Status],
  template: `
    <div class="page">
      @for (fault of faults.data() ?? []; track fault.id) {
        <div class="hazard" role="alert">
          <svg class="sign" viewBox="0 0 24 24" width="28" height="28" aria-hidden="true"><path d="M12 3 2 20.5h20z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M12 10v4.5M12 17.2v.3" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>
          <div>
            <strong>Avarie în zona ta: {{ fault.locality ?? fault.county }}</strong>
            <p>
              Stația {{ fault.substationCode }}, din {{ fault.occurredAt | date: 'd MMM, HH:mm' }}.
              @if (fault.cause) { Cauză: {{ fault.cause }}. }
              Echipele lucrează la restabilirea alimentării.
            </p>
          </div>
        </div>
      }

      <div class="intro">
        <h1>Consumul meu</h1>
        @if (me.data(); as user) {
          <p>
            @if (user.locality) { {{ user.locality }}, {{ user.county }}. }
            Contorul <span class="data">{{ user.meterId ?? 'nealocat' }}</span>
          </p>
        }
      </div>

      <section class="card" aria-labelledby="luna">
        <h2 id="luna">Luna aceasta, până acum</h2>
        <fg-status [of]="summary" />
        @if (summary.data(); as s) {
          <div class="reading">
            <!-- Styled after a meter's register: one cell per digit, the decimal in red. -->
            <p class="register" [attr.aria-label]="(s.monthToDateKwh | number: '1.1-1') + ' kilowați-oră'">
              @for (digit of register().whole; track $index) { <span aria-hidden="true">{{ digit }}</span> }
              <span class="decimal" aria-hidden="true">{{ register().decimal }}</span>
              <span class="unit" aria-hidden="true">kWh</span>
            </p>
            <div class="compare">
              @if (s.previousMonthKwh > 0) {
                <p class="share">
                  <strong>{{ s.monthToDateKwh / s.previousMonthKwh | percent: '1.0-0' }}</strong>
                  din consumul lunii trecute ({{ s.previousMonthKwh | number: '1.1-1' }} kWh)
                </p>
                <div class="meter" aria-hidden="true">
                  <span [style.width.%]="(s.monthToDateKwh / s.previousMonthKwh) * 100 > 100 ? 100 : (s.monthToDateKwh / s.previousMonthKwh) * 100"></span>
                </div>
              } @else {
                <p><span class="eyebrow">Luna trecută</span><br /><span class="data big">{{ s.previousMonthKwh | number: '1.1-1' }} kWh</span></p>
              }
              @if (s.lastReadingAt) {
                <p class="muted">Ultima citire: {{ s.lastReadingAt | date: 'd MMM, HH:mm' }}</p>
              }
            </div>
          </div>
        }
      </section>

      <section class="card" aria-labelledby="consum">
        <div class="section-head">
          <h2 id="consum">Consum</h2>
          @if (total() !== null) {
            <p class="muted">Total în interval: <span class="data">{{ total() | number: '1.0-1' }} kWh</span></p>
          }
        </div>
        <div class="controls">
          <div class="segmented" role="group" aria-label="Afișare pe">
            <button type="button" [attr.aria-pressed]="bucket() === 'HOUR'" (click)="setBucket('HOUR')">Pe ore</button>
            <button type="button" [attr.aria-pressed]="bucket() === 'DAY'" (click)="setBucket('DAY')">Pe zile</button>
          </div>
          <div class="presets" role="group" aria-label="Interval">
            @for (days of presets(); track days) {
              <button type="button" class="chip" [attr.aria-pressed]="isPreset(days)" (click)="setRange(days)">
                {{ days === 1 ? 'Azi' : 'Ultimele ' + days + ' zile' }}
              </button>
            }
          </div>
          <div class="dates">
            <label>De la <input type="date" [ngModel]="from()" (ngModelChange)="from.set($event)" [max]="to()" /></label>
            <label>Până la <input type="date" [ngModel]="to()" (ngModelChange)="to.set($event)" [min]="from()" [max]="today" /></label>
          </div>
        </div>
        @if (rangeError(); as message) {
          <p class="notice error" role="alert">{{ message }}</p>
        } @else {
          <fg-status [of]="consumption" />
          @if (consumption.data(); as data) {
            <fg-consumption-chart [points]="data.points" [bucket]="data.bucket" />
          }
        }
      </section>

      <section aria-labelledby="cereri" class="requests">
        <div class="section-head">
          <h2 id="cereri">Cererile mele de racordare</h2>
          <a class="button" routerLink="/connection-requests/new">Depune o cerere</a>
        </div>
        <fg-status [of]="requests" />
        @if (requests.data(); as list) {
          @if (list.length === 0) {
            <div class="empty-requests">
              <p>Nu ai depus nicio cerere de racordare. Dacă ai nevoie de un loc de consum nou sau de mai multă putere, poți depune cererea online.</p>
            </div>
          } @else {
            <ul class="request-list">
              @for (request of list; track request.clientId) {
                <li>
                  <div class="what">
                    <strong>{{ request.requestType | label }}, {{ request.locality }}</strong>
                    <span class="muted">
                      {{ request.requestedPowerKw | number }} kW în {{ request.county }}, depusă pe {{ request.createdAt | date: 'd MMMM y' }}
                    </span>
                    @if (request.statusReason) { <p class="reason">{{ request.statusReason }}</p> }
                  </div>
                  <span class="badge {{ request.status }}">{{ request.status | label }}</span>
                </li>
              }
            </ul>
          }
        }
      </section>
    </div>
  `,
  styles: `
    .hazard {
      display: flex; gap: 14px; align-items: flex-start; padding: 14px 16px; border-radius: var(--radius);
      background: var(--warn-tint); color: #4d3400; border: 1px solid #ecc96a; border-radius: var(--radius-lg);
    }
    .sign { flex: none; color: #9a6a00; }
    .reading { display: flex; flex-wrap: wrap; gap: 20px 40px; align-items: center; }
    .register { display: flex; align-items: center; gap: 3px; font: 500 clamp(1.7rem, 7vw, 2.6rem)/1 var(--font-data); }
    .register span { background: var(--ink); color: #fff; padding: 10px 7px; border-radius: 6px; min-width: 1.15ch; text-align: center; }
    .register .decimal { background: var(--critical); margin-left: 3px; }
    .register .unit { background: none; color: var(--ink-muted); font: 600 1.1rem var(--font-body); padding-left: 10px; }
    .compare { display: grid; gap: 8px; flex: 1 1 260px; max-width: 420px; }
    .share strong { font-size: 1.5rem; font-variant-numeric: tabular-nums; margin-right: 4px; }
    .meter { height: 10px; border-radius: 999px; background: #e2e8ef; overflow: hidden; }
    .meter span { display: block; height: 100%; border-radius: inherit; background: var(--chart); }
    .section-head { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: baseline; justify-content: space-between; }
    .big { font-size: 1.25rem; }
    .requests { display: grid; gap: 14px; }
    .controls { display: flex; flex-wrap: wrap; gap: 12px 16px; align-items: end; }
    .presets { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { min-height: 36px; padding: 0 14px; border-radius: 999px; background: var(--card); color: var(--ink); border: 1px solid var(--line-strong); box-shadow: none; font-weight: 500; font-size: 0.92rem; }
    .chip:hover { background: var(--surface); color: var(--ink); border-color: var(--ink-muted); }
    .chip[aria-pressed='true'] { background: var(--ink); color: #fff; border-color: var(--ink); }
    .dates { display: flex; gap: 10px; margin-left: auto; }
    .dates label { font-size: 0.88rem; color: var(--ink-muted); }
    .dates input { min-height: 38px; }
    @media (max-width: 720px) { .dates { margin-left: 0; width: 100%; } .dates label { flex: 1; } }
    .request-list { list-style: none; margin: 0; padding: 0; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius-lg); box-shadow: var(--shadow); }
    .request-list li { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: start; justify-content: space-between; padding: 16px 20px; }
    .request-list li + li { border-top: 1px solid var(--line); }
    .what { display: grid; gap: 2px; min-width: 0; }
    .reason { margin-top: 6px; font-size: 0.92rem; color: var(--ink-muted); padding: 8px 12px; background: var(--surface); border-radius: var(--radius); }
    .empty-requests { padding: 24px; border: 1.5px dashed var(--line-strong); border-radius: var(--radius-lg); color: var(--ink-muted); max-width: 70ch; }
  `,
})
export class ClientPage {
  private readonly api = inject(ApiService);

  protected readonly me = new Loader<Me>();
  protected readonly summary = new Loader<ConsumptionSummary>();
  protected readonly faults = new Loader<Fault[]>();
  protected readonly consumption = new Loader<Consumption>();
  protected readonly requests = new Loader<RequestSummary[]>();

  protected readonly today = toDateInput(new Date());
  protected readonly bucket = signal<Bucket>('DAY');
  protected readonly from = signal(toDateInput(new Date(Date.now() - 29 * DAY_MS)));
  protected readonly to = signal(this.today);

  protected readonly rangeError = computed(() => {
    if (!this.from() || !this.to()) return 'Alege ambele date ale intervalului.';
    const days = (Date.parse(this.to()) - Date.parse(this.from())) / DAY_MS + 1;
    if (days < 1) return 'Data de început trebuie să fie înaintea datei de sfârșit.';
    if (this.bucket() === 'HOUR' && days > MAX_HOURLY_DAYS) {
      return `Pentru afișarea pe ore alege cel mult ${MAX_HOURLY_DAYS} zile.`;
    }
    return null;
  });

  protected readonly total = computed(() => {
    const points = this.rangeError() ? undefined : this.consumption.data()?.points;
    return points ? points.reduce((sum, point) => sum + point.kwh, 0) : null;
  });

  /** Digits for the register display: five whole-number cells and one decimal. */
  protected readonly register = computed(() => {
    const [whole, decimal] = (this.summary.data()?.monthToDateKwh ?? 0).toFixed(1).split('.');
    return { whole: whole.padStart(5, '0').split(''), decimal };
  });

  constructor() {
    void this.me.run(() => this.api.me());
    void this.summary.run(() => this.api.consumptionSummary());
    void this.faults.run(async () => (await this.api.myFaults()).filter((fault) => !fault.restoredAt));
    void this.requests.run(() => this.api.myRequests());

    effect(() => {
      const [bucket, fromIso, toIso] = [this.bucket(), dayToIso(this.from()), dayToIso(this.to(), 1)];
      if (this.rangeError() || !fromIso || !toIso) return;
      untracked(() => void this.consumption.run(() => this.api.consumption(fromIso, toIso, bucket)));
    });
  }

  /** Quick ranges that stay within what each view can show. */
  protected readonly presets = computed(() => (this.bucket() === 'HOUR' ? [1, 3, 7] : [7, 30, 90]));

  protected isPreset(days: number): boolean {
    return this.to() === this.today && this.from() === toDateInput(new Date(Date.now() - (days - 1) * DAY_MS));
  }

  protected setRange(days: number): void {
    this.from.set(toDateInput(new Date(Date.now() - (days - 1) * DAY_MS)));
    this.to.set(this.today);
  }

  protected setBucket(bucket: Bucket): void {
    // Hourly detail is only readable over a few days, so the range follows the switch.
    this.from.set(toDateInput(new Date(Date.now() - (bucket === 'HOUR' ? 2 : 29) * DAY_MS)));
    this.to.set(this.today);
    this.bucket.set(bucket);
  }
}
