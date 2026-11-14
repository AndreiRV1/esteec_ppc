import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, problemMessage } from '../core/api';
import { AuthService } from '../core/auth';
import { COUNTIES, LabelPipe, toDateInput } from '../core/format';
import { ConnectionRequestForm, Registration, RequestSummary } from '../core/models';
import { OperationId } from '../ui/operation-id';

const MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
type Slot = 'cerereRacordare' | 'actIdentitate' | 'aviz';

const emptyForm = (): ConnectionRequestForm => ({
  applicantType: 'PERSOANA_FIZICA',
  requestType: 'LOC_CONSUM_NOU',
  applicant: { fullName: '', identityNumber: '', email: '', phone: '', address: '' },
  consumptionPlace: { county: '', locality: '', address: '' },
  requestedPowerKw: 6,
  permit: { type: 'AVIZ_AMPLASAMENT', number: '', issuer: '', issuedOn: '', validUntil: '' },
});

/**
 * Files a connection request with its three documents. Used by employees on
 * behalf of an applicant, by clients for themselves, and at /register by people
 * without an account (the answer then carries their new client login).
 */
@Component({
  imports: [FormsModule, RouterLink, LabelPipe, OperationId],
  template: `
    <div class="page">
      <div class="intro">
        <h1>Cerere de racordare</h1>
        <p class="muted">
          {{ onBehalf ? 'Completează datele solicitantului și atașează documentele lui.' : 'Completează datele tale și atașează documentele.' }}
          Documentele sunt arhivate și stocate criptat; un administrator analizează cererea.
          @if (publicForm()) { După depunere primești un ID de client și o parolă pentru a urmări cererea. }
        </p>
      </div>

      @if (registration(); as r) {
        <div class="notice success credentials" role="status">
          <p><strong>Cererea a fost depusă.</strong> Contul tău de client a fost creat.</p>
          <dl>
            <dt>ID client</dt><dd class="data">{{ r.username }}</dd>
            <dt>Parolă</dt><dd class="data">{{ r.password }}</dd>
          </dl>
          <p><strong>Notează parola acum:</strong> nu va mai fi afișată. Cu ele te autentifici ca să urmărești cererea;
            după aprobare vei vedea aici consumul.</p>
        </div>
        <div class="row">
          <button type="button" (click)="copy(r)">{{ copied() ? 'Copiat' : 'Copiază datele' }}</button>
          <a class="button secondary" href="/">Autentifică-te</a>
        </div>
      }

      @if (registration()) {
      } @else if (created(); as request) {
        <div class="notice success" role="status">
          <p><strong>Cererea a fost depusă.</strong> Este în așteptarea unui administrator.</p>
          <p>Număr cerere: <span class="data">{{ request.clientId }}</span> · {{ request.locality }}, {{ request.county }} ({{ request.region | label }})</p>
          <fg-operation />
        </div>
        <div class="row">
          <button type="button" (click)="reset()">Depune altă cerere</button>
          @if (!onBehalf) { <a class="button secondary" routerLink="/client">Înapoi la cererile mele</a> }
        </div>
      } @else {
        <form ngNativeValidate (ngSubmit)="submit()" #f="ngForm" class="card steps">
          <fieldset [disabled]="sending()">
            <legend><h2>Solicitant</h2></legend>
            <div class="grid-2">
              <label>Tip solicitant
                <select name="applicantType" [(ngModel)]="form.applicantType">
                  <option value="PERSOANA_FIZICA">Persoană fizică</option>
                  <option value="PERSOANA_JURIDICA">Persoană juridică</option>
                </select>
              </label>
              <label>{{ company() ? 'Denumirea firmei' : 'Nume și prenume' }}
                <input name="fullName" [(ngModel)]="form.applicant.fullName" required maxlength="200" autocomplete="name" />
              </label>
              <label>{{ company() ? 'CUI' : 'CNP' }}
                <input name="identityNumber" [(ngModel)]="form.applicant.identityNumber" required maxlength="16"
                       [attr.inputmode]="company() ? 'text' : 'numeric'" [pattern]="company() ? '(RO|ro)?\\\\s?[0-9]{2,10}' : '[0-9]{13}'" />
              </label>
              <label>E-mail
                <input name="email" type="email" [(ngModel)]="form.applicant.email" required maxlength="254" autocomplete="email" />
              </label>
              <label>Telefon
                <input name="phone" type="tel" [(ngModel)]="form.applicant.phone" required pattern="\\+?[0-9 ()\\-]{6,20}" autocomplete="tel" />
              </label>
              <label>{{ company() ? 'Sediu' : 'Domiciliu' }}
                <input name="address" [(ngModel)]="form.applicant.address" required maxlength="300" autocomplete="street-address" />
              </label>
            </div>
          </fieldset>

          <fieldset [disabled]="sending()">
            <legend><h2>Loc de consum</h2></legend>
            <div class="grid-2">
              <label>Tip solicitare
                <select name="requestType" [(ngModel)]="form.requestType">
                  <option value="LOC_CONSUM_NOU">Loc de consum nou</option>
                  <option value="SPOR_PUTERE">Spor de putere</option>
                  <option value="SEPARARE">Separare</option>
                  <option value="ACTUALIZARE_DATE">Actualizare date</option>
                </select>
              </label>
              <label>Județ
                <select name="county" [(ngModel)]="form.consumptionPlace.county" required>
                  <option value="" disabled>Alege județul</option>
                  @for (county of counties; track county.code) {
                    <option [value]="county.code">{{ county.name }} ({{ county.region | label }})</option>
                  }
                </select>
              </label>
              <label>Localitate
                <input name="locality" [(ngModel)]="form.consumptionPlace.locality" required maxlength="100" />
              </label>
              <label>Adresa locului de consum
                <input name="placeAddress" [(ngModel)]="form.consumptionPlace.address" required maxlength="300" />
              </label>
              <label>Putere cerută (kW)
                <input name="power" type="number" [(ngModel)]="form.requestedPowerKw" required min="0.1" max="100000" step="0.1" />
              </label>
            </div>
          </fieldset>

          <fieldset [disabled]="sending()">
            <legend><h2>Aviz</h2></legend>
            <div class="grid-2">
              <label>Tip aviz
                <select name="permitType" [(ngModel)]="form.permit.type">
                  <option value="AVIZ_AMPLASAMENT">Aviz de amplasament</option>
                  <option value="CERTIFICAT_URBANISM">Certificat de urbanism</option>
                </select>
              </label>
              <label>Număr
                <input name="permitNumber" [(ngModel)]="form.permit.number" required maxlength="50" />
              </label>
              <label>Emitent
                <input name="issuer" [(ngModel)]="form.permit.issuer" required maxlength="200" />
              </label>
              <label>Emis la
                <input name="issuedOn" type="date" [(ngModel)]="form.permit.issuedOn" required [max]="today" />
              </label>
              <label>Valabil până la
                <input name="validUntil" type="date" [(ngModel)]="form.permit.validUntil" required [min]="today" />
              </label>
            </div>
          </fieldset>

          <fieldset [disabled]="sending()">
            <legend><h2>Documente</h2></legend>
            <p class="muted">PDF, JPEG sau PNG, cel mult 10 MB fiecare.</p>
            <div class="grid-2">
              @for (slot of slots; track slot.key) {
                <label>{{ slot.text }}
                  <input type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" required
                         (change)="pick(slot.key, $event)" />
                </label>
              }
            </div>
          </fieldset>

          @if (error(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
          <div class="row">
            @if (publicForm()) {
              <label class="check consent">
                <input type="checkbox" name="consent" required />
                <span>Am citit <a routerLink="/privacy" target="_blank">Politica de confidențialitate</a> și accept
                  <a routerLink="/terms" target="_blank">Termenii și condițiile</a>.</span>
              </label>
            }
            <button type="submit" [disabled]="sending()">{{ sending() ? 'Se trimite…' : 'Depune cererea' }}</button>
          </div>
        </form>
      }
    </div>
  `,
  // The form really is a sequence of four parts, so the parts are numbered.
  styles: `
    .steps { counter-reset: step; gap: 0; padding: 0; }
    .steps fieldset { padding: 24px; border-bottom: 1px solid var(--line); }
    .steps legend { float: left; width: 100%; display: flex; align-items: center; gap: 12px; margin-bottom: 16px; counter-increment: step; }
    .steps legend::before {
      content: counter(step); flex: none; width: 30px; height: 30px; border-radius: 50%;
      display: grid; place-items: center; background: var(--primary-tint); color: var(--primary-dark); font-weight: 700;
    }
    .steps legend + * { clear: both; }
    .consent { flex: 1 0 100%; }
    .credentials dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 16px; margin: 12px 0; }
    .credentials dd { margin: 0; font-size: 1.15rem; font-weight: 600; user-select: all; }
    .steps > .notice { margin: 20px 24px 0; }
    .steps > .row:last-child { border-top: 0; margin: 0; padding: 20px 24px; background: #f8fafc; border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
    @media (max-width: 720px) { .steps fieldset { padding: 18px; } .steps > .row:last-child { padding: 16px 18px; } }
  `,
})
export class ConnectionFormPage {
  private readonly api = inject(ApiService);
  protected readonly onBehalf = inject(AuthService).hasRole('employee');
  protected readonly counties = COUNTIES;
  protected readonly today = toDateInput(new Date());
  protected readonly slots: { key: Slot; text: string }[] = [
    { key: 'cerereRacordare', text: 'Cererea de racordare' },
    { key: 'actIdentitate', text: 'Actul de identitate' },
    { key: 'aviz', text: 'Avizul sau certificatul' },
  ];

  protected form = emptyForm();
  private files: Partial<Record<Slot, File>> = {};
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly created = signal<RequestSummary | null>(null);
  /** Set by the /register route: no account yet, the backend creates one. */
  readonly publicForm = input(false);
  protected readonly registration = signal<Registration | null>(null);
  protected readonly copied = signal(false);

  protected company(): boolean {
    return this.form.applicantType === 'PERSOANA_JURIDICA';
  }

  protected pick(slot: Slot, event: Event): void {
    this.files[slot] = (event.target as HTMLInputElement).files?.[0];
  }

  protected async submit(): Promise<void> {
    const { cerereRacordare, actIdentitate, aviz } = this.files;
    if (!cerereRacordare || !actIdentitate || !aviz) {
      this.error.set('Atașează toate cele trei documente.');
      return;
    }
    const tooLarge = [cerereRacordare, actIdentitate, aviz].find((file) => file.size > MAX_DOCUMENT_BYTES);
    if (tooLarge) {
      this.error.set(`${tooLarge.name} depășește 10 MB. Alege un fișier mai mic.`);
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    try {
      const files = { cerereRacordare, actIdentitate, aviz };
      if (this.publicForm()) {
        const registration = await this.api.register(this.form, files);
        this.registration.set(registration);
        this.created.set(registration.request);
      } else {
        this.created.set(await this.api.submitRequest(this.form, files));
      }
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.sending.set(false);
    }
  }

  protected async copy(r: Registration): Promise<void> {
    try {
      await navigator.clipboard.writeText(`ID client: ${r.username}\nParolă: ${r.password}`);
      this.copied.set(true);
    } catch {
      this.copied.set(false); // clipboard blocked: the values stay on screen to copy by hand
    }
  }

  protected reset(): void {
    this.form = emptyForm();
    this.files = {};
    this.created.set(null);
  }
}
