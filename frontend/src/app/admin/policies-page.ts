import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, problemMessage } from '../core/api';
import { LabelPipe } from '../core/format';
import { BACKENDS, FileCategory, StorageBackend, StoragePolicy } from '../core/models';

const MB = 1024 * 1024;
const DATABASE_MAX_MB = 100;
const CATEGORIES: FileCategory[] = ['FAULT_RECORD', 'CONNECTION_DOCUMENTS', 'DRONE_VIDEO'];

/** Editable copy of one policy, with the size in MB. */
interface Row {
  policy: StoragePolicy;
  backend: StorageBackend;
  target: string;
  encryptAtRest: boolean;
  maxSizeMb: number;
  saving: boolean;
  message: { ok: boolean; text: string } | null;
}

/**
 * Storage policy: where each file category is written, per region. Saving a row
 * applies to the next upload; files already stored stay where they are.
 */
@Component({
  imports: [FormsModule, DatePipe, LabelPipe],
  template: `
    <div class="page">
      <div class="intro">
        <h1>Politici de stocare</h1>
        <p class="muted">
          Alege unde se scrie fiecare categorie de fișiere, pe regiune. Schimbarea se aplică fișierelor încărcate de acum
          înainte; cele existente rămân unde sunt.
        </p>
      </div>
      @if (error(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
      @if (loading()) { <p class="muted" role="status">Se încarcă…</p> }

      @for (category of categories; track category) {
        <section class="card" [attr.aria-labelledby]="category">
          <h2 [id]="category">{{ category | label }}</h2>
          @if (category === 'CONNECTION_DOCUMENTS') {
            <p class="muted">Conțin date personale, deci criptarea este obligatorie și nu poate fi oprită.</p>
          }
          @for (row of rowsOf(category); track row.policy.region) {
            <form ngNativeValidate class="policy" (ngSubmit)="save(row)" #form="ngForm">
              <fieldset [disabled]="row.saving">
                <legend><h3>{{ row.policy.region | label }}</h3></legend>
                <div class="fields">
                  <label>Stocare
                    <select name="backend" [(ngModel)]="row.backend" (ngModelChange)="onBackend(row)">
                      @for (backend of backends; track backend) { <option [value]="backend">{{ backend | label }}</option> }
                    </select>
                  </label>
                  <label class="target">{{ row.backend === 'FTP' ? 'Director FTP' : row.backend === 'S3' ? 'Bucket S3' : 'Țintă' }}
                    <input name="target" [(ngModel)]="row.target" [disabled]="row.backend === 'DATABASE'"
                           [required]="row.backend !== 'DATABASE'" maxlength="200"
                           [placeholder]="row.backend === 'DATABASE' ? 'Nu se aplică' : ''" />
                  </label>
                  <label>Mărime maximă (MB)
                    <input name="max" type="number" [(ngModel)]="row.maxSizeMb" required min="1"
                           [max]="row.backend === 'DATABASE' ? databaseMaxMb : 102400" step="1" />
                  </label>
                  <label class="check">
                    <input name="encrypt" type="checkbox" [(ngModel)]="row.encryptAtRest"
                           [disabled]="category === 'CONNECTION_DOCUMENTS'" />
                    Criptare la stocare
                  </label>
                  <button class="small" type="submit" [disabled]="form.invalid || row.saving">Salvează</button>
                </div>
                @if (row.backend === 'DATABASE') {
                  <p class="muted hint">În baza de date încap fișiere de cel mult {{ databaseMaxMb }} MB.</p>
                }
                @if (row.message; as message) {
                  <p class="notice" [class.success]="message.ok" [class.error]="!message.ok" role="status">{{ message.text }}</p>
                } @else {
                  <p class="muted hint">Modificată {{ row.policy.updatedAt | date: 'd MMM y, HH:mm' }} de {{ row.policy.updatedBy }}</p>
                }
              </fieldset>
            </form>
          }
        </section>
      }
    </div>
  `,
  styles: `
    .policy { border-top: 1px solid var(--line); padding-top: 14px; }
    .fields { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; }
    .fields label { flex: 1 1 140px; }
    .fields label.target { flex: 3 1 260px; }
    .fields label.check { flex: 0 0 auto; min-height: 44px; }
    .fields button { min-height: 44px; }
    .hint { font-size: 0.9rem; }
  `,
})
export class PoliciesPage {
  private readonly api = inject(ApiService);
  protected readonly categories = CATEGORIES;
  protected readonly backends = BACKENDS;
  protected readonly databaseMaxMb = DATABASE_MAX_MB;
  protected readonly rows = signal<Row[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.storagePolicies()
      .then((policies) => this.rows.set(policies.map((policy) => this.toRow(policy))))
      .catch((error) => this.error.set(problemMessage(error)))
      .finally(() => this.loading.set(false));
  }

  protected rowsOf(category: FileCategory): Row[] {
    return this.rows().filter((row) => row.policy.category === category);
  }

  protected onBackend(row: Row): void {
    if (row.backend === 'DATABASE') {
      row.target = '';
      row.maxSizeMb = Math.min(row.maxSizeMb, DATABASE_MAX_MB);
    }
  }

  protected async save(row: Row): Promise<void> {
    row.saving = true;
    row.message = null;
    this.rows.update((rows) => [...rows]);
    const update = {
      backend: row.backend,
      target: row.backend === 'DATABASE' ? null : row.target.trim(),
      // Connection documents hold personal data: always encrypted, whatever the form state.
      encryptAtRest: row.policy.category === 'CONNECTION_DOCUMENTS' ? true : row.encryptAtRest,
      maxSizeBytes: Math.round(row.maxSizeMb * MB),
    };
    try {
      const saved = await this.api.updateStoragePolicy(row.policy.category, row.policy.region, update);
      row.policy = saved ?? { ...row.policy, ...update, updatedAt: new Date().toISOString() };
      row.message = { ok: true, text: 'Salvat. Se aplică de la următoarea încărcare.' };
    } catch (error) {
      row.message = { ok: false, text: problemMessage(error) };
    } finally {
      row.saving = false;
      this.rows.update((rows) => [...rows]);
    }
  }

  private toRow(policy: StoragePolicy): Row {
    return {
      policy, backend: policy.backend, target: policy.target ?? '',
      encryptAtRest: policy.category === 'CONNECTION_DOCUMENTS' ? true : policy.encryptAtRest,
      maxSizeMb: Math.round(policy.maxSizeBytes / MB), saving: false, message: null,
    };
  }
}
