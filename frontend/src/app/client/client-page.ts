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
          <span class="sign" aria-hidden="true">⚠</span>
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

      <div class="page-head">
        <h1>Consumul meu</h1>
        @if (me.data(); as user) {
          <p class="muted">
            Contor <span class="data">{{ user.meterId ?? '—' }}</span>
            @if (user.locality) { · {{ user.locality }}, {{ user.county }} }
          </p>
        }
      </div>

      <section class="card" aria-labelledby="luna">
        <h2 id="luna" class="eyebrow">Luna aceasta, până acum</h2>
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
              <p><span class="eyebrow">Luna trecută</span><br /><span class="data big">{{ s.previousMonthKwh | number: '1.1-1' }} kWh</span></p>
              @if (s.previousMonthKwh > 0) {
                <p class="muted">
                  Ai consumat până acum {{ s.monthToDateKwh / s.previousMonthKwh | percent: '1.0-0' }} din totalul lunii trecute.
                </p>
              }
              @if (s.lastReadingAt) {
                <p class="muted">Ultima citire: {{ s.lastReadingAt | date: 'd MMM, HH:mm' }}</p>
              }
            </div>
          </div>
        }
      </section>

      <section class="card" aria-labelledby="consum">
        <div class="page-head">
          <h2 id="consum">Consum</h2>
          @if (total() !== null) {
            <p class="muted">Total în interval: <span class="data">{{ total() | number: '1.0-1' }} kWh</span></p>
          }
        </div>
        <div class="row">
          <div class="segmented" role="group" aria-label="Afișare pe">
            <button type="button" [attr.aria-pressed]="bucket() === 'HOUR'" (click)="setBucket('HOUR')">Pe ore</button>
            <button type="button" [attr.aria-pressed]="bucket() === 'DAY'" (click)="setBucket('DAY')">Pe zile</button>
          </div>
          <label>De la <input type="date" [ngModel]="from()" (ngModelChange)="from.set($event)" [max]="to()" /></label>
          <label>Până la <input type="date" [ngModel]="to()" (ngModelChange)="to.set($event)" [min]="from()" [max]="today" /></label>
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
        <div class="page-head">
          <h2 id="cereri">Cererile mele de racordare</h2>
          <a class="button secondary" routerLink="/connection-requests/new">Depune o cerere</a>
        </div>
        <fg-status [of]="requests" />
        @if (requests.data(); as list) {
          @if (list.length === 0) {
            <p class="notice">Nu ai depus nicio cerere de racordare.</p>
          } @else {
            <div class="table-wrap">
              <table class="stack">
                <thead><tr><th>Depusă</th><th>Tip</th><th>Loc de consum</th><th>Putere</th><th>Stare</th></tr></thead>
                <tbody>
                  @for (request of list; track request.clientId) {
                    <tr>
                      <td data-label="Depusă">{{ request.createdAt | date: 'd MMM y' }}</td>
                      <td data-label="Tip">{{ request.requestType | label }}</td>
                      <td data-label="Loc de consum">{{ request.locality }}, {{ request.county }}</td>
                      <td data-label="Putere" class="data">{{ request.requestedPowerKw | number }} kW</td>
                      <td data-label="Stare">
                        <span class="badge {{ request.status }}">{{ request.status | label }}</span>
                        @if (request.statusReason) { <div class="muted reason">{{ request.statusReason }}</div> }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
      </section>
    </div>
  `,
  styles: `
    .hazard {
      display: flex; gap: 14px; align-items: flex-start; padding: 14px 16px; border-radius: var(--radius);
      background: var(--hazard); color: #1a1400; border: 2px solid var(--ink);
    }
    .sign { font-size: 1.8rem; line-height: 1; }
    .reading { display: flex; flex-wrap: wrap; gap: 20px 40px; align-items: center; }
    .register { display: flex; align-items: center; gap: 3px; font: 500 clamp(1.7rem, 7vw, 2.6rem)/1 var(--font-data); }
    .register span { background: var(--ink); color: #fff; padding: 8px 6px; border-radius: 3px; min-width: 1.15ch; text-align: center; }
    .register .decimal { background: var(--critical); margin-left: 3px; }
    .register .unit { background: none; color: var(--ink-muted); font: 600 1.1rem var(--font-display); letter-spacing: 0.08em; padding-left: 8px; }
    .compare { display: grid; gap: 4px; }
    .big { font-size: 1.25rem; }
    .requests { display: grid; gap: 12px; }
    .reason { font-size: 0.9rem; }
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

  protected setBucket(bucket: Bucket): void {
    // Hourly detail is only readable over a few days, so the range follows the switch.
    this.from.set(toDateInput(new Date(Date.now() - (bucket === 'HOUR' ? 2 : 29) * DAY_MS)));
    this.to.set(this.today);
    this.bucket.set(bucket);
  }
}
