import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, Loader, downloadAndSave, problemMessage } from '../core/api';
import { BytesPipe, LabelPipe } from '../core/format';
import { AdminRequestDetail, Decision } from '../core/models';
import { Status } from '../ui/status';

/**
 * One connection request for review: applicant data (decrypted by the backend
 * from the archive, every access audited), the documents, and the decision.
 */
@Component({
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, LabelPipe, BytesPipe, Status],
  template: `
    <div class="page">
      <p><a routerLink="/admin/cereri">← Toate cererile</a></p>
      <fg-status [of]="detail" />
      @if (detail.data(); as d) {
        <div class="page-head">
          <h1>Cerere de racordare</h1>
          <span class="badge {{ d.request.status }}">{{ d.request.status | label }}</span>
        </div>
        <p class="muted">
          <span class="data">{{ d.request.clientId }}</span> · depusă {{ d.request.createdAt | date: 'd MMM y, HH:mm' }}
        </p>

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
                  <a [routerLink]="['/admin/cereri', other.clientId]">{{ other.createdAt | date: 'd MMM y' }} · {{ other.requestType | label }}</a>
                  <span class="badge {{ other.status }}">{{ other.status | label }}</span>
                </li>
              }
            </ul>
          </section>
        }

        <section class="card" aria-labelledby="decizie">
          <h2 id="decizie">Decizie</h2>
          @if (d.request.status === 'PENDING') {
            <label>Motiv (obligatoriu la respingere)
              <textarea [(ngModel)]="reason" maxlength="1000" #reasonBox></textarea>
            </label>
            @if (decisionError(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
            <div class="row">
              <button type="button" [disabled]="busy()" (click)="decide('ACCEPTED')">Aprobă cererea</button>
              <button class="danger" type="button" [disabled]="busy()" (click)="decide('REJECTED') || reasonBox.focus()">Respinge cererea</button>
            </div>
          } @else {
            <p>
              {{ d.request.status === 'ACCEPTED' ? 'Aprobată' : 'Respinsă' }}
              @if (d.request.reviewedAt) { pe {{ d.request.reviewedAt | date: 'd MMM y, HH:mm' }} }
              @if (d.reviewedBy) { de <span class="data">{{ d.reviewedBy }}</span> }.
            </p>
            @if (d.request.statusReason) { <p><strong>Motiv:</strong> {{ d.request.statusReason }}</p> }
          }
        </section>
      }
    </div>
  `,
  styles: `
    .sr { position: absolute; left: -9999px; }
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
