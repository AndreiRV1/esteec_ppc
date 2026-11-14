import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer } from '@angular/platform-browser';
import { ApiService, Loader } from '../core/api';
import { APP_CONFIG } from '../core/config';
import { FiringAlert } from '../core/models';
import { isTraceId, traceUrl } from '../core/operations';
import { Status } from '../ui/status';

const DASHBOARDS = [
  { uid: 'filegrid-overview', text: 'Privire de ansamblu' },
  { uid: 'filegrid-storage', text: 'Stocare' },
  { uid: 'filegrid-meters', text: 'Contoare' },
];

const REFRESH_MS = 30_000;

/**
 * What needs attention now (alerts for the business requirements of the spec, the same ones
 * e-mailed to the on-call team), one operation followed end to end, and the Grafana dashboards.
 */
@Component({
  imports: [DatePipe, FormsModule, Status],
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Monitorizare</h1>
        <a [href]="base + '/d/' + selected()" target="_blank" rel="noopener">Deschide în Grafana ↗</a>
      </div>

      <section class="card" aria-labelledby="alerte">
        <div class="page-head">
          <h2 id="alerte">Alerte active</h2>
          <span class="muted small">Trimise automat pe e-mail echipei de gardă</span>
        </div>
        <fg-status [of]="alerts" />
        @if (alerts.data(); as list) {
          @if (list.length === 0) {
            <p class="notice success">Nicio cerință de business nu este în pericol acum.</p>
          } @else {
            <ul class="alerts">
              @for (alert of list; track $index) {
                <li [class]="alert.severity">
                  <div class="alert-head">
                    <strong>{{ alert.requirement ?? alert.name }}</strong>
                    <span class="badge" [class.CRITICAL]="alert.severity === 'critical'" [class.HIGH]="alert.severity !== 'critical'">
                      {{ alert.severity === 'critical' ? 'Critic' : 'Atenție' }}
                    </span>
                  </div>
                  <p>{{ alert.summary }}</p>
                  @if (alert.description) { <p class="muted">{{ alert.description }}</p> }
                  <p class="muted small">
                    {{ alert.name }}@if (alert.category) { · {{ alert.category }} }@if (alert.backend) { · {{ alert.backend }} }
                    @if (alert.startsAt) { · din {{ alert.startsAt | date: 'd MMM, HH:mm' }} }
                  </p>
                </li>
              }
            </ul>
          }
        }
      </section>

      <section class="card" aria-labelledby="operatie">
        <h2 id="operatie">Urmărește o operație</h2>
        <p class="muted">
          Fiecare răspuns al API-ului are un ID de operație (antetul <span class="data">X-Trace-Id</span>), afișat și în
          aplicație după fiecare încărcare. Deschide operația de la proxy până la scrierea în storage.
        </p>
        <form class="row trace-form" (ngSubmit)="openTrace()">
          <label class="grow">ID operație
            <input name="traceId" [(ngModel)]="traceId" placeholder="32 caractere hexazecimale" autocomplete="off" spellcheck="false" />
          </label>
          <button type="submit" [disabled]="!valid()">Deschide în Grafana</button>
        </form>
        @if (traceId && !valid()) { <p class="notice error" role="alert">ID-ul are 32 de caractere 0-9 și a-f.</p> }
      </section>

      <div class="segmented" role="tablist" aria-label="Panou Grafana">
        @for (dashboard of dashboards; track dashboard.uid) {
          <button type="button" role="tab" [attr.aria-selected]="selected() === dashboard.uid" (click)="selected.set(dashboard.uid)">
            {{ dashboard.text }}
          </button>
        }
      </div>
      @if (mock) {
        <p class="notice">
          În modul demo Grafana nu este pornită. Cu platforma reală, aici apare panoul
          <span class="data">{{ selected() }}</span> de la <span class="data">{{ base }}/d/{{ selected() }}</span>.
        </p>
      } @else {
        <iframe [src]="url()" [title]="'Panou Grafana: ' + title()" loading="lazy"></iframe>
      }
    </div>
  `,
  styles: `
    .segmented { justify-self: start; max-width: 100%; overflow-x: auto; }
    iframe { width: 100%; height: max(480px, calc(100vh - 260px)); border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--card); box-shadow: var(--shadow); }
    h2 { white-space: nowrap; }
    .small { font-size: 0.85rem; }
    .alerts { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .alerts li { border: 1px solid var(--line); border-left: 4px solid var(--hazard); border-radius: var(--radius); padding: 12px 16px; display: grid; gap: 4px; }
    .alerts li.critical { border-left-color: var(--critical); }
    .alerts p { margin: 0; }
    .alert-head { display: flex; justify-content: space-between; gap: 12px; align-items: start; }
    .trace-form { align-items: end; }
    .trace-form .grow { flex: 1 1 320px; }
  `,
})
export class MonitoringPage {
  private readonly config = inject(APP_CONFIG);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly api = inject(ApiService);
  protected readonly dashboards = DASHBOARDS;
  protected readonly mock = this.config.mock;
  protected readonly base = this.config.grafanaUrl.replace(/\/$/, '');
  protected readonly selected = signal(DASHBOARDS[0].uid);
  protected readonly title = computed(() => DASHBOARDS.find((d) => d.uid === this.selected())!.text);
  /** The URL is built from our own config and a fixed list of dashboard ids, never from user input. */
  protected readonly url = computed(() =>
    this.sanitizer.bypassSecurityTrustResourceUrl(`${this.base}/d/${this.selected()}?kiosk&theme=light`));

  protected readonly alerts = new Loader<FiringAlert[]>();
  protected traceId = '';

  constructor() {
    const load = () => void this.alerts.run(() => this.api.alerts());
    load();
    const timer = setInterval(load, REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected valid(): boolean {
    return isTraceId(this.traceId);
  }

  protected openTrace(): void {
    if (this.valid()) window.open(traceUrl(this.base, this.traceId), '_blank', 'noopener');
  }
}
