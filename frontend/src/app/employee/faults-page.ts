import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, Loader, downloadAndSave, problemMessage } from '../core/api';
import { BytesPipe, LabelPipe, countiesOf, dayToIso, localToIso } from '../core/format';
import { Fault, FaultFilter, Page, REGIONS, Region, SEVERITIES, Severity, StoredRef } from '../core/models';
import { Status } from '../ui/status';
import { OperationId } from '../ui/operation-id';

const emptyUpload = () => ({
  region: '' as Region | '', county: '', locality: '', substationCode: '', occurredAt: '', restoredAt: '',
  severity: 'MEDIUM' as Severity, cause: '', affectedCustomers: null as number | null, description: '',
});

/** Fault records: upload one (metadata + file), or search and download earlier ones. */
@Component({
  imports: [FormsModule, DatePipe, DecimalPipe, LabelPipe, BytesPipe, Status, OperationId],
  template: `
    <div class="page">
      <div class="intro">
        <h1>Înregistrări de avarie</h1>
        <p>Încarcă fișierul unei avarii noi sau caută înregistrări existente pentru descărcare.</p>
      </div>
      <div class="segmented" role="tablist">
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'upload'" (click)="tab.set('upload')">Încarcă</button>
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'search'" (click)="openSearch()">Caută și descarcă</button>
      </div>

      @if (tab() === 'upload') {
        <form ngNativeValidate class="card" (ngSubmit)="upload()" #f="ngForm">
          <fieldset [disabled]="sending()">
            <div class="grid-2">
              <label>Regiune
                <select name="region" [(ngModel)]="draft.region" (ngModelChange)="draft.county = ''" required>
                  <option value="" disabled>Alege regiunea</option>
                  @for (r of regions; track r) { <option [value]="r">{{ r | label }}</option> }
                </select>
              </label>
              <label>Județ
                <select name="county" [(ngModel)]="draft.county" required [disabled]="!draft.region">
                  <option value="" disabled>Alege județul</option>
                  @for (c of counties(draft.region); track c.code) { <option [value]="c.name">{{ c.name }}</option> }
                </select>
              </label>
              <label>Localitate <input name="locality" [(ngModel)]="draft.locality" required maxlength="100" /></label>
              <label>Cod stație <input name="substation" [(ngModel)]="draft.substationCode" required maxlength="60" placeholder="ST-MUN-VOL-20" /></label>
              <label>Momentul avariei <input name="occurredAt" type="datetime-local" [(ngModel)]="draft.occurredAt" required /></label>
              <label>Alimentare restabilită la (dacă e cazul)
                <input name="restoredAt" type="datetime-local" [(ngModel)]="draft.restoredAt" [min]="draft.occurredAt" />
              </label>
              <label>Severitate
                <select name="severity" [(ngModel)]="draft.severity">
                  @for (s of severities; track s) { <option [value]="s">{{ s | label }}</option> }
                </select>
              </label>
              <label>Clienți afectați <input name="affected" type="number" [(ngModel)]="draft.affectedCustomers" min="0" step="1" /></label>
              <label>Cauză <input name="cause" [(ngModel)]="draft.cause" maxlength="200" /></label>
              <label>Fișierul înregistrării (JSON, raport PDF sau scanat: completăm câmpurile din el)
                <input type="file" required (change)="pick($event)" />
              </label>
            </div>
            <label>Descriere <textarea name="description" [(ngModel)]="draft.description" maxlength="2000"></textarea></label>
          </fieldset>
          @if (autoFilledNote(); as info) {
            <p class="notice" role="status">{{ info }}</p>
          }
          @if (error(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
          @if (stored(); as ref) {
            <div class="notice success" role="status">
              <p><strong>Înregistrarea a fost încărcată</strong> și este disponibilă pentru dispecerat.</p>
              <p>SHA-256: <span class="data">{{ ref.sha256 }}</span></p>
              <fg-operation />
            </div>
          }
          <div class="row"><button type="submit" [disabled]="sending()">{{ sending() ? 'Se încarcă…' : 'Încarcă înregistrarea' }}</button></div>
        </form>
      } @else {
        <form ngNativeValidate class="row filters" (ngSubmit)="search(0)">
          <label>Regiune
            <select name="region" [(ngModel)]="filter.region" (ngModelChange)="filter.county = ''">
              <option value="">Toate</option>
              @for (r of regions; track r) { <option [value]="r">{{ r | label }}</option> }
            </select>
          </label>
          <label>Județ
            <select name="county" [(ngModel)]="filter.county">
              <option value="">Toate</option>
              @for (c of counties(filter.region); track c.code) { <option [value]="c.name">{{ c.name }}</option> }
            </select>
          </label>
          <label>Severitate
            <select name="severity" [(ngModel)]="filter.severity">
              <option value="">Toate</option>
              @for (s of severities; track s) { <option [value]="s">{{ s | label }}</option> }
            </select>
          </label>
          <label>De la <input name="from" type="date" [(ngModel)]="fromDay" /></label>
          <label>Până la <input name="to" type="date" [(ngModel)]="toDay" /></label>
          <button type="submit">Caută</button>
        </form>

        <fg-status [of]="results" />
        @if (note(); as n) { <p class="notice" [class.success]="n.ok" [class.error]="!n.ok" role="status">{{ n.text }}</p> }
        @if (results.data(); as found) {
          @if (found.items.length === 0) {
            <p class="notice">Nicio avarie nu corespunde filtrelor alese.</p>
          } @else {
            <div class="table-wrap">
              <table class="stack">
                <thead><tr><th>Moment</th><th>Regiune</th><th>Stație</th><th>Zonă</th><th>Severitate</th><th>Clienți afectați</th><th>Stare</th><th>Fișier</th></tr></thead>
                <tbody>
                  @for (fault of found.items; track fault.id) {
                    <tr>
                      <td data-label="Moment">{{ fault.occurredAt | date: 'd MMM y, HH:mm' }}</td>
                      <td data-label="Regiune">{{ fault.region | label }}</td>
                      <td data-label="Stație" class="data">{{ fault.substationCode }}</td>
                      <td data-label="Zonă">{{ fault.locality ? fault.locality + ', ' : '' }}{{ fault.county }}</td>
                      <td data-label="Severitate"><span class="severity {{ fault.severity }}">{{ fault.severity | label }}</span></td>
                      <td data-label="Clienți afectați">{{ fault.affectedCustomers === null ? '—' : (fault.affectedCustomers | number) }}</td>
                      <td data-label="Stare">
                        @if (fault.restoredAt) {
                          Restabilită {{ fault.restoredAt | date: 'd MMM, HH:mm' }}
                        } @else {
                          <button class="small" type="button" [disabled]="resolving() === fault.id" (click)="resolve(fault)">
                            Marchează rezolvată
                          </button>
                        }
                      </td>
                      <td>
                        <button class="secondary small" type="button" [disabled]="downloading()" (click)="download(fault)">
                          Descarcă @if (fault.file?.sizeBytes ?? fault.sizeBytes; as size) { ({{ size | bytes }}) }
                        </button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <div class="row pager">
              <button class="secondary small" type="button" [disabled]="found.page === 0" (click)="search(found.page - 1)">Înapoi</button>
              <span class="muted">{{ found.total }} avarii</span>
              <button class="secondary small" type="button" [disabled]="(found.page + 1) * found.size >= found.total" (click)="search(found.page + 1)">Înainte</button>
            </div>
          }
        }
      }
    </div>
  `,
  styles: `
    .segmented { justify-self: start; }
    .filters label { flex: 1 1 150px; }
    .pager { align-items: center; justify-content: space-between; }
  `,
})
export class FaultsPage {
  private readonly api = inject(ApiService);
  protected readonly regions = REGIONS;
  protected readonly severities = SEVERITIES;
  protected readonly counties = countiesOf;
  protected readonly tab = signal<'upload' | 'search'>('upload');

  // ---- upload
  protected draft = emptyUpload();
  private file: File | undefined;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly stored = signal<StoredRef | null>(null);
  protected readonly autoFilledNote = signal<string | null>(null);

  // ---- search
  protected filter: FaultFilter = { region: '', county: '', severity: '' };
  protected fromDay = '';
  protected toDay = '';
  protected readonly results = new Loader<Page<Fault>>();
  protected readonly downloading = signal(false);
  protected readonly note = signal<{ ok: boolean; text: string } | null>(null);
  protected readonly resolving = signal<string | null>(null);

  /** Power is back: the fault stops showing as active for the customers in that area. */
  protected async resolve(fault: Fault): Promise<void> {
    this.resolving.set(fault.id);
    try {
      await this.api.restoreFault(fault.id);
      this.search(this.results.data()?.page ?? 0); // clears the note, so it runs first
      this.note.set({ ok: true, text: `Avaria de la ${fault.substationCode} a fost marcată ca rezolvată.` });
    } catch (error) {
      this.note.set({ ok: false, text: problemMessage(error) });
    } finally {
      this.resolving.set(null);
    }
  }

  protected async pick(event: Event): Promise<void> {
    this.file = (event.target as HTMLInputElement).files?.[0];
    this.autoFilledNote.set(null);
    if (this.file && /\.(pdf|jpe?g|png)$/i.test(this.file.name)) {
      await this.fillFromDocument(this.file);
      return;
    }
    if (!this.file || (!this.file.name.endsWith('.json') && this.file.type !== 'application/json')) return;
    try {
      const parsed = JSON.parse(await this.file.text());
      const rec = parsed?.fault_record ?? parsed;
      const reg = (rec?.region ?? parsed?.region) as Region | undefined;
      if (reg && this.regions.includes(reg)) {
        const toLocalInput = (isoStr?: string | null) => {
          if (!isoStr) return '';
          const d = new Date(isoStr);
          if (Number.isNaN(d.getTime())) return '';
          const pad = (n: number) => String(n).padStart(2, '0');
          return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
        };
        this.draft = {
          region: reg,
          county: rec.county ?? this.draft.county,
          locality: rec.locality ?? this.draft.locality,
          substationCode: rec.substation_code ?? rec.substationCode ?? this.draft.substationCode,
          occurredAt: toLocalInput(rec.occurred_at ?? rec.occurredAt) || this.draft.occurredAt,
          restoredAt: toLocalInput(rec.restored_at ?? rec.restoredAt),
          severity: this.severities.includes(rec.severity) ? rec.severity : this.draft.severity,
          cause: rec.cause ?? this.draft.cause,
          affectedCustomers: rec.affected_customers ?? rec.affectedCustomers ?? this.draft.affectedCustomers,
          description: rec.description ?? this.draft.description,
        };
        const samplesCount = parsed?.oscillogram_samples_count ?? parsed?.oscillogram_samples?.length;
        const ied = parsed?.protection_relay?.ied_device;
        this.autoFilledNote.set(
          `Date preluate automat din fișierul JSON (${reg} · ${this.draft.substationCode}` +
            (ied ? ` · releu ${ied}` : '') +
            (samplesCount ? ` · ${Number(samplesCount).toLocaleString('ro-RO')} eșantioane oscilogramă` : '') +
            ').',
        );
      }
    } catch {
      // Non-FileGrid JSON or binary file: user fills fields manually
    }
  }

  /** A fault report as PDF (text or scanned) or photo: the server reads it (OCR if needed) and we fill the form. */
  private async fillFromDocument(file: File): Promise<void> {
    this.autoFilledNote.set('Se citește raportul…');
    try {
      const { source, fields } = await this.api.extractDocument('FAULT', file);
      const f = fields;
      this.draft = {
        ...this.draft,
        ...(f['region'] && { region: f['region'] as Region }),
        ...(f['county'] && { county: f['county'] }),
        ...(f['locality'] && { locality: f['locality'] }),
        ...(f['substationCode'] && { substationCode: f['substationCode'] }),
        ...(f['occurredAt'] && { occurredAt: f['occurredAt'] }),
        ...(f['restoredAt'] && { restoredAt: f['restoredAt'] }),
        ...(f['severity'] && { severity: f['severity'] as Severity }),
        ...(f['cause'] && { cause: f['cause'] }),
        ...(f['affectedCustomers'] && { affectedCustomers: Number(f['affectedCustomers']) }),
        ...(f['description'] && { description: f['description'] }),
      };
      const count = Object.keys(f).length;
      this.autoFilledNote.set(count
        ? `Am completat ${count} câmpuri din raport${source === 'OCR' ? ' (document scanat, citit prin OCR)' : ''}. Verifică-le înainte de încărcare.`
        : 'Nu am găsit date de completat în acest document. Completează câmpurile manual.');
    } catch {
      this.autoFilledNote.set('Documentul nu a putut fi citit automat. Completează câmpurile manual.');
    }
  }

  protected async upload(): Promise<void> {
    const { region, occurredAt } = this.draft;
    if (!this.file || !region || !occurredAt) {
      this.error.set('Completează câmpurile obligatorii și alege fișierul înregistrării.');
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.stored.set(null);
    try {
      const ref = await this.api.createFault({
        region, county: this.draft.county, locality: this.draft.locality.trim(),
        substationCode: this.draft.substationCode.trim(), occurredAt: localToIso(occurredAt)!,
        restoredAt: localToIso(this.draft.restoredAt), severity: this.draft.severity,
        cause: this.draft.cause.trim() || undefined, affectedCustomers: this.draft.affectedCustomers ?? undefined,
        description: this.draft.description.trim() || undefined,
      }, this.file);
      this.stored.set(ref);
      // Keep the area: during a storm several faults come from the same place.
      this.draft = { ...emptyUpload(), region, county: this.draft.county };
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.sending.set(false);
    }
  }

  protected openSearch(): void {
    this.tab.set('search');
    if (!this.results.data()) this.search(0);
  }

  protected search(page: number): void {
    this.note.set(null);
    const filter = { ...this.filter, from: dayToIso(this.fromDay), to: dayToIso(this.toDay, 1), page, size: 20 };
    void this.results.run(() => this.api.faults(filter));
  }

  protected async download(fault: Fault): Promise<void> {
    this.downloading.set(true);
    this.note.set(await downloadAndSave(() => this.api.downloadFault(fault.id)));
    this.downloading.set(false);
  }
}
