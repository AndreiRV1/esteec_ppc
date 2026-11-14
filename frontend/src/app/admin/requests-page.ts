import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, Loader } from '../core/api';
import { LabelPipe } from '../core/format';
import { Page, REGIONS, Region, RequestStatus, RequestSummary } from '../core/models';
import { Status } from '../ui/status';

const PAGE_SIZE = 20;

/** Review queue: connection requests, oldest first, filtered by status and region. */
@Component({
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, LabelPipe, Status],
  template: `
    <div class="page">
      <div class="intro">
        <h1>Cereri de racordare</h1>
        <p>Verifică documentele și aprobă sau respinge cererile depuse.</p>
      </div>
      <div class="toolbar">
        <div class="segmented" role="group" aria-label="Stare">
          @for (option of statusOptions; track option.value) {
            <button type="button" [attr.aria-pressed]="status() === option.value" (click)="setFilter(status, option.value)">{{ option.text }}</button>
          }
        </div>
        <label class="region"><span class="sr">Regiune</span>
          <select [ngModel]="region()" (ngModelChange)="setFilter(region, $event)">
            <option value="">Toate regiunile</option>
            @for (r of regions; track r) { <option [value]="r">{{ r | label }}</option> }
          </select>
        </label>
      </div>

      <fg-status [of]="list" />
      @if (list.data(); as result) {
        @if (result.items.length === 0) {
          <p class="notice">Nicio cerere nu corespunde filtrelor alese.</p>
        } @else {
          <div class="table-wrap">
            <table class="stack">
              <thead>
                <tr><th>Depusă</th><th>Regiune</th><th>Loc de consum</th><th>Tip</th><th>Putere</th><th>Stare</th><th><span class="sr">Acțiuni</span></th></tr>
              </thead>
              <tbody>
                @for (request of result.items; track request.clientId) {
                  <tr>
                    <td data-label="Depusă">{{ request.createdAt | date: 'd MMM y, HH:mm' }}</td>
                    <td data-label="Regiune">{{ request.region | label }}</td>
                    <td data-label="Loc de consum">{{ request.locality }}, {{ request.county }}</td>
                    <td data-label="Tip">{{ request.requestType | label }} · {{ request.applicantType | label }}</td>
                    <td data-label="Putere" class="data">{{ request.requestedPowerKw | number }} kW</td>
                    <td data-label="Stare"><span class="badge {{ request.status }}">{{ request.status | label }}</span></td>
                    <td class="open"><a class="button secondary small" [routerLink]="[request.clientId]">Deschide</a></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="row pager">
            <button class="secondary small" type="button" [disabled]="page() === 0" (click)="page.set(page() - 1)">Înapoi</button>
            <span class="muted">Pagina {{ page() + 1 }} din {{ pages() }} · {{ result.total }} cereri</span>
            <button class="secondary small" type="button" [disabled]="page() + 1 >= pages()" (click)="page.set(page() + 1)">Înainte</button>
          </div>
        }
      }
    </div>
  `,
  styles: `
    .pager { align-items: center; justify-content: space-between; }
    .sr { position: absolute; left: -9999px; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; }
    .segmented { max-width: 100%; overflow-x: auto; }
    .region { flex: 0 1 240px; }
    td.open { text-align: right; }
  `,
})
export class RequestsPage {
  private readonly api = inject(ApiService);
  protected readonly regions = REGIONS;
  protected readonly statusOptions: { value: RequestStatus | ''; text: string }[] = [
    { value: 'PENDING', text: 'În așteptare' },
    { value: 'ACCEPTED', text: 'Aprobate' },
    { value: 'REJECTED', text: 'Respinse' },
    { value: '', text: 'Toate' },
  ];
  protected readonly status = signal<RequestStatus | ''>('PENDING');
  protected readonly region = signal<Region | ''>('');
  protected readonly page = signal(0);
  protected readonly list = new Loader<Page<RequestSummary>>();
  protected readonly pages = computed(() => Math.max(1, Math.ceil((this.list.data()?.total ?? 0) / PAGE_SIZE)));

  constructor() {
    effect(() => {
      const filter = { status: this.status(), region: this.region(), page: this.page(), size: PAGE_SIZE };
      untracked(() => void this.list.run(() => this.api.adminRequests(filter)));
    });
  }

  protected setFilter<T>(target: { set(value: T): void }, value: T): void {
    target.set(value);
    this.page.set(0);
  }
}
