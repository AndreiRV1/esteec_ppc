import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, Loader, downloadAndSave, problemMessage } from '../core/api';
import { BytesPipe, LabelPipe } from '../core/format';
import { AdminRequestDetail, Decision } from '../core/models';
import { Status } from '../ui/status';
import { OperationId } from '../ui/operation-id';

/**
 * One connection request for review: applicant data (decrypted by the backend
 * from the archive, every access audited), the documents, and the decision.
 */
@Component({
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, LabelPipe, BytesPipe, Status, OperationId],
  template: `
    <div class="page">
      <p><a class="back" routerLink="/admin/connection-requests"><svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>Toate cererile</a></p>
      <fg-status [of]="detail" />
      @if (detail.data(); as d) {
        <div class="intro">
          <div class="title">
            <h1>{{ d.request.requestType | label }}, {{ d.request.locality }}</h1>
            <span class="badge {{ d.request.status }}">{{ d.request.status | label }}</span>
          </div>
          <p>
            Depusă {{ d.request.createdAt | date: 'd MMM y, HH:mm' }} de {{ d.applicant.fullName }}.
            Nr. <span class="data">{{ d.request.clientId }}</span>
          </p>
        </div>

        <div class="layout">
        <div class="main">
        <div class="grid-2">
          <section class="card" aria-labelledby="solicitant">
            <h2 id="solicitant">Solicitant</h2>
            <dl class="facts">
              <dt>Nume</dt><dd>{{ d.applicant.fullName }}</dd>
              <dt>{{ d.request.applicantType === 'PERSOANA_FIZICA' ? 'CNP' : 'CUI' }}</dt><dd class="data">{{ d.applicant.identityNumber }}</dd>
              <dt>E-mail</dt><dd>{{ d.applicant.email }}</dd>
              <dt>Telefon</dt><dd>{{ d.applicant.phone }}</dd>
              <dt>Adresă</dt><dd>{{ d.applicant.address }}</dd>
            </dl>
          </section>

          <section class="card" aria-labelledby="solicitare">
            <h2 id="solicitare">Solicitare</h2>
            <dl class="facts">
              <dt>Tip</dt><dd>{{ d.request.requestType | label }}</dd>
              <dt>Loc de consum</dt><dd>{{ d.consumptionPlace.address }}, {{ d.request.locality }}, {{ d.request.county }}</dd>
              <dt>Regiune</dt><dd>{{ d.request.region | label }}</dd>
              <dt>Putere cerută</dt><dd class="data">{{ d.request.requestedPowerKw | number }} kW</dd>
              <dt>{{ d.permit.type | label }}</dt><dd>nr. {{ d.permit.number }}, {{ d.permit.issuer }}</dd>
              <dt>Valabil</dt><dd>{{ d.permit.issuedOn | date: 'd MMM y' }} – {{ d.permit.validUntil | date: 'd MMM y' }}</dd>
            </dl>
          </section>
        </div>

        <section class="card" aria-labelledby="documente">
          <h2 id="documente">Documente</h2>
          <div class="table-wrap">
            <table class="stack">
              <thead><tr><th>Document</th><th>Fișier</th><th>Mărime</th><th><span class="sr">Descarcă</span></th></tr></thead>
              <tbody>
                @for (doc of d.documents; track doc.id) {
                  <tr>
                    <td data-label="Document">{{ doc.documentType | label }}</td>
                    <td data-label="Fișier" class="data">{{ doc.entryName }}</td>
                    <td data-label="Mărime">{{ doc.sizeBytes | bytes }}</td>
                    <td><button class="secondary small" type="button" [disabled]="busy()" (click)="download(doc.id)">Descarcă</button></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="row">
            <button class="secondary" type="button" [disabled]="busy()" (click)="download()">
              Descarcă arhiva completă ({{ d.archive.sizeBytes | bytes }})
            </button>
            <p class="muted small-text">Stocată criptat ({{ d.archive.encryption | label }}) în {{ d.archive.backend | label }}.</p>
            @if (d.archive.traceId) { <fg-operation [traceId]="d.archive.traceId" /> }
          </div>
          @if (downloadNote(); as note) {
            <p class="notice" [class.success]="note.ok" [class.error]="!note.ok" role="status">{{ note.text }}</p>
          }
        </section>

        @if (d.otherRequestsFromApplicant.length) {
          <section class="card" aria-labelledby="altele">
            <h2 id="altele">Alte cereri ale aceluiași solicitant</h2>
            <ul>
              @for (other of d.otherRequestsFromApplicant; track other.clientId) {
                <li>
                  <a [routerLink]="['/admin/connection-requests', other.clientId]">{{ other.createdAt | date: 'd MMM y' }} · {{ other.requestType | label }}</a>
                  <span class="badge {{ other.status }}">{{ other.status | label }}</span>
                </li>
              }
            </ul>
          </section>
        }


        </div>
        <aside class="card decision" aria-labelledby="decizie">
          <h2 id="decizie">Decizie</h2>
          @if (d.request.status === 'PENDING') {
            <label>Motiv (obligatoriu la respingere)
              <textarea [(ngModel)]="reason" maxlength="1000" #reasonBox></textarea>
            </label>
            @if (decisionError(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
            <div class="actions">
              <button type="button" [disabled]="busy() || detail.loading()" (click)="decide('ACCEPTED')">Aprobă cererea</button>
              <button class="danger" type="button" [disabled]="busy() || detail.loading()" (click)="decide('REJECTED') || reasonBox.focus()">Respinge cererea</button>
            </div>
          } @else {
            <p>
              {{ d.request.status === 'ACCEPTED' ? 'Aprobată' : 'Respinsă' }}
              @if (d.request.reviewedAt) { pe {{ d.request.reviewedAt | date: 'd MMM y, HH:mm' }} }
              @if (d.reviewedBy) { de <span class="data">{{ d.reviewedBy }}</span> }.
            </p>
            @if (d.request.statusReason) { <p><strong>Motiv:</strong> {{ d.request.statusReason }}</p> }
          }
        </aside>
        </div>
      }
    </div>
  `,
  styles: `
    .sr { position: absolute; left: -9999px; }
    .back { display: inline-flex; align-items: center; gap: 4px; font-weight: 500; text-decoration: none; }
    .back svg { fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; }
    .layout { display: grid; gap: 24px; grid-template-columns: minmax(0, 1fr); align-items: start; }
    .main { display: grid; gap: 24px; grid-template-columns: minmax(0, 1fr); }
    .actions { display: grid; gap: 10px; }
    @media (min-width: 1080px) {
      .layout { grid-template-columns: minmax(0, 1fr) 300px; }
      .decision { position: sticky; top: 24px; }
      .main .grid-2 { grid-template-columns: minmax(0, 1fr); }
    }
    .small-text { font-size: 0.9rem; align-self: center; }
    ul { margin: 0; padding-left: 20px; display: grid; gap: 8px; }
    li .badge { margin-left: 8px; }
  `,
})
export class RequestDetailPage {
  /** Route parameter (bound by the router). */
  readonly id = input.required<string>();

  private readonly api = inject(ApiService);
  protected readonly detail = new Loader<AdminRequestDetail>();
  protected readonly busy = signal(false);
  protected readonly decisionError = signal<string | null>(null);
  protected readonly downloadNote = signal<{ ok: boolean; text: string } | null>(null);
  protected reason = '';

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.downloadNote.set(null);
        this.decisionError.set(null);
        this.reason = '';
        void this.detail.run(() => this.api.adminRequest(id));
      });
    });
  }

  /** Returns false when the decision was not sent (so the caller can focus the reason box). */
  protected decide(decision: Decision): boolean {
    const reason = this.reason.trim();
    if (decision === 'REJECTED' && !reason) {
      this.decisionError.set('Scrie motivul respingerii. Solicitantul îl va vedea.');
      return false;
    }
    this.busy.set(true);
    this.decisionError.set(null);
    this.api.decide(this.id(), decision, reason || undefined)
      .then(() => this.detail.run(() => this.api.adminRequest(this.id())))
      .catch((error) => this.decisionError.set(problemMessage(error)))
      .finally(() => this.busy.set(false));
    return true;
  }

  /** Downloads one document, or the whole archive when no document id is given. */
  protected async download(documentId?: string): Promise<void> {
    this.busy.set(true);
    this.downloadNote.set(null);
    this.downloadNote.set(await downloadAndSave(() =>
      documentId ? this.api.downloadDocument(this.id(), documentId) : this.api.downloadArchive(this.id())));
    this.busy.set(false);
  }
}
