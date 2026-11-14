import { Component, computed, inject, signal } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { APP_CONFIG } from '../core/config';

const DASHBOARDS = [
  { uid: 'filegrid-overview', text: 'Privire de ansamblu' },
  { uid: 'filegrid-storage', text: 'Stocare' },
  { uid: 'filegrid-meters', text: 'Contoare' },
];

/** Grafana dashboards, embedded from the same origin (Grafana signs the user in through Keycloak). */
@Component({
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Monitorizare</h1>
        <a [href]="base + '/d/' + selected()" target="_blank" rel="noopener">Deschide în Grafana ↗</a>
      </div>
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
    iframe { width: 100%; height: max(480px, calc(100vh - 260px)); border: 1px solid var(--line); border-radius: var(--radius); background: var(--card); }
  `,
})
export class MonitoringPage {
  private readonly config = inject(APP_CONFIG);
  private readonly sanitizer = inject(DomSanitizer);
  protected readonly dashboards = DASHBOARDS;
  protected readonly mock = this.config.mock;
  protected readonly base = this.config.grafanaUrl.replace(/\/$/, '');
  protected readonly selected = signal(DASHBOARDS[0].uid);
  protected readonly title = computed(() => DASHBOARDS.find((d) => d.uid === this.selected())!.text);
  /** The URL is built from our own config and a fixed list of dashboard ids, never from user input. */
  protected readonly url = computed(() =>
    this.sanitizer.bypassSecurityTrustResourceUrl(`${this.base}/d/${this.selected()}?kiosk&theme=light`));
}
