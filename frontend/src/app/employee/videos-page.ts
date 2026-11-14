import { DatePipe, PercentPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, Loader, downloadAndSave, problemMessage, toProblem } from '../core/api';
import { UploadPausedError, UploadProgress, uploadParts } from '../core/chunked-upload';
import { BytesPipe, LabelPipe, countiesOf, dayToIso, formatBytes, localToIso } from '../core/format';
import { DroneVideo, Page, REGIONS, Region, StoredRef, VideoFilter } from '../core/models';
import { sha256OfBlob } from '../core/sha256';
import { Status } from '../ui/status';

/**
 * What is needed to continue an upload after a reload. Kept in localStorage:
 * it holds no credentials, only the upload id, the file's identity and the form.
 */
interface PendingUpload {
  uploadId: string;
  partSizeBytes: number;
  name: string;
  size: number;
  lastModified: number;
  sha256: string;
  draft: Draft;
}
interface Draft {
  region: Region | '';
  county: string;
  lineCode: string;
  droneId: string;
  flightStartedAt: string;
  durationSeconds: number | null;
  notes: string;
}

const PENDING_KEY = 'fg.videoUpload';
const emptyDraft = (): Draft => ({
  region: '', county: '', lineCode: '', droneId: '', flightStartedAt: '', durationSeconds: null, notes: '',
});
type Phase = 'idle' | 'hashing' | 'uploading' | 'pausing' | 'paused' | 'finishing' | 'done' | 'failed';

/** Drone videos: resumable upload in parts, and search/download of earlier videos. */
@Component({
  imports: [FormsModule, DatePipe, PercentPipe, LabelPipe, BytesPipe, Status],
  template: `
    <div class="page">
      <div class="intro">
        <h1>Video-uri de la inspecțiile cu drona</h1>
        <p>Fișierele mari se încarcă pe bucăți și pot fi reluate dacă se întrerupe conexiunea.</p>
      </div>
      <div class="segmented" role="tablist">
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'upload'" (click)="tab.set('upload')">Încarcă</button>
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'search'" (click)="openSearch()">Caută și descarcă</button>
      </div>

      @if (tab() === 'upload') {
        @if (pending(); as p) {
          @if (phase() === 'idle') {
            <div class="notice" role="status">
              <p><strong>Ai o încărcare neterminată:</strong> {{ p.name }} ({{ p.size | bytes }}).</p>
              <p>Alege din nou același fișier și apasă „Continuă încărcarea”. Părțile deja trimise nu se mai retrimit.</p>
              <button class="secondary small" type="button" (click)="discard()">Renunță la această încărcare</button>
            </div>
          }
        }

        <form ngNativeValidate class="card" (ngSubmit)="start()" #f="ngForm">
          <fieldset [disabled]="busy() || !!pending()">
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
              <label>Codul liniei inspectate
                <input name="lineCode" [(ngModel)]="draft.lineCode" required maxlength="80" placeholder="LEA-110kV-MUN-BUC-OTOPENI-01" />
              </label>
              <label>Dronă <input name="droneId" [(ngModel)]="draft.droneId" maxlength="40" placeholder="DR-07" /></label>
              <label>Începutul zborului <input name="flight" type="datetime-local" [(ngModel)]="draft.flightStartedAt" required /></label>
              <label>Durată (secunde) <input name="duration" type="number" [(ngModel)]="draft.durationSeconds" min="1" step="1" /></label>
            </div>
            <label>Observații <textarea name="notes" [(ngModel)]="draft.notes" maxlength="2000"></textarea></label>
          </fieldset>
          <label>Fișierul video
            <input type="file" accept="video/*" required [disabled]="busy()" (change)="pick($event)" />
          </label>

          @if (phase() !== 'idle') {
            <div class="progress" role="status" aria-live="polite">
              <progress max="1" [value]="fraction()"></progress>
              <p>
                @switch (phase()) {
                  @case ('hashing') { Calculez amprenta SHA-256 a fișierului: {{ fraction() | percent }} }
                  @case ('uploading') { Trimis {{ sent() || '0 MB' }} ({{ fraction() | percent }}) }
                  @case ('pausing') { Se oprește după partea curentă… }
                  @case ('paused') { În pauză la {{ fraction() | percent }}. Poți continua oricând, și după reîncărcarea paginii. }
                  @case ('finishing') { Serverul verifică fișierul… }
                  @case ('failed') { Încărcarea s-a oprit la {{ fraction() | percent }}. }
                  @case ('done') { Încărcare încheiată. }
                }
              </p>
              @if (retry(); as r) {
                <p class="muted">Conexiune instabilă: reîncerc partea {{ r.partNumber }} (încercarea {{ r.attempt }}).</p>
              }
            </div>
          }
          @if (error(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
          @if (stored(); as ref) {
            <div class="notice success" role="status">
              <p><strong>Video-ul a fost stocat.</strong> {{ checksumMatches() ? 'Amprenta de pe server corespunde cu cea calculată local.' : '' }}</p>
              <p>SHA-256: <span class="data">{{ ref.sha256 }}</span></p>
            </div>
          }

          <div class="row">
            @if (phase() === 'uploading' || phase() === 'pausing') {
              <button class="secondary" type="button" [disabled]="phase() === 'pausing'" (click)="pause()">Pauză</button>
            } @else {
              <button type="submit" [disabled]="busy()">
                {{ pending() || phase() === 'paused' || phase() === 'failed' ? 'Continuă încărcarea' : 'Încarcă video-ul' }}
              </button>
            }
          </div>
        </form>
      } @else {
        <form ngNativeValidate class="row filters" (ngSubmit)="search(0)">
          <label>Regiune
            <select name="region" [(ngModel)]="filter.region">
              <option value="">Toate</option>
              @for (r of regions; track r) { <option [value]="r">{{ r | label }}</option> }
            </select>
          </label>
          <label>Codul liniei <input name="lineCode" [(ngModel)]="filter.lineCode" /></label>
          <label>De la <input name="from" type="date" [(ngModel)]="fromDay" /></label>
          <label>Până la <input name="to" type="date" [(ngModel)]="toDay" /></label>
          <button type="submit">Caută</button>
        </form>

        <fg-status [of]="results" />
        @if (note(); as n) { <p class="notice" [class.success]="n.ok" [class.error]="!n.ok" role="status">{{ n.text }}</p> }
        @if (results.data(); as found) {
          @if (found.items.length === 0) {
            <p class="notice">Niciun video nu corespunde filtrelor alese.</p>
          } @else {
            <div class="table-wrap">
              <table class="stack">
                <thead><tr><th>Zbor</th><th>Linie</th><th>Zonă</th><th>Dronă</th><th>Durată</th><th>Fișier</th></tr></thead>
                <tbody>
                  @for (video of found.items; track video.id) {
                    <tr>
                      <td data-label="Zbor">{{ video.flightStartedAt | date: 'd MMM y, HH:mm' }}</td>
                      <td data-label="Linie" class="data">{{ video.lineCode }}</td>
                      <td data-label="Zonă">{{ video.county }}, {{ video.region | label }}</td>
                      <td data-label="Dronă">{{ video.droneId || '—' }}</td>
                      <td data-label="Durată">{{ duration(video.durationSeconds) }}</td>
                      <td>
                        <button class="secondary small" type="button" [disabled]="downloading()" (click)="download(video)">
                          Descarcă @if (video.sizeBytes) { ({{ video.sizeBytes | bytes }}) }
                        </button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <div class="row pager">
              <button class="secondary small" type="button" [disabled]="found.page === 0" (click)="search(found.page - 1)">Înapoi</button>
              <span class="muted">{{ found.total }} video-uri</span>
              <button class="secondary small" type="button" [disabled]="(found.page + 1) * found.size >= found.total" (click)="search(found.page + 1)">Înainte</button>
            </div>
          }
        }
      }
    </div>
  `,
  styles: `
    .segmented { justify-self: start; }
    .progress { display: grid; gap: 6px; }
    .filters label { flex: 1 1 150px; }
    .pager { align-items: center; justify-content: space-between; }
  `,
})
export class VideosPage {
  private readonly api = inject(ApiService);
  protected readonly regions = REGIONS;
  protected readonly counties = countiesOf;
  protected readonly tab = signal<'upload' | 'search'>('upload');

  // ---- upload
  protected readonly pending = signal<PendingUpload | null>(readPending());
  protected draft: Draft = this.pending()?.draft ?? emptyDraft();
  private file: File | undefined;
  private controller: AbortController | undefined;
  protected readonly phase = signal<Phase>('idle');
  protected readonly fraction = signal(0);
  protected readonly sent = signal('');
  protected readonly retry = signal<UploadProgress['retrying'] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly stored = signal<StoredRef | null>(null);
  protected readonly checksumMatches = signal(false);

  // ---- search
  protected filter: VideoFilter = { region: '', lineCode: '' };
  protected fromDay = '';
  protected toDay = '';
  protected readonly results = new Loader<Page<DroneVideo>>();
  protected readonly downloading = signal(false);
  protected readonly note = signal<{ ok: boolean; text: string } | null>(null);

  protected busy(): boolean {
    return ['hashing', 'uploading', 'pausing', 'finishing'].includes(this.phase());
  }

  protected pick(event: Event): void {
    this.file = (event.target as HTMLInputElement).files?.[0];
    this.error.set(null);
    if (!this.file || this.pending()) return;

    // Auto-fill regional metadata when picking a generated FileGrid MP4 (`video_<REGION>_<LINE>_<TAG>_<ISO>_<ID>.mp4`)
    const m = /^video_(MUNTENIA|BANAT|DOBROGEA)_([A-Za-z0-9-]+)_(?:INSP|STORM|LARGE1GB)_(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z_/i.exec(
      this.file.name,
    );
    if (m) {
      const reg = m[1].toUpperCase() as Region;
      const lineCode = m[2];
      const cs = this.counties(reg);
      const defaultCounty = cs[0]?.name ?? '';
      const dronePrefix = reg === 'MUNTENIA' ? 'DRN-MUN-M350-01' : reg === 'BANAT' ? 'DRN-BAN-M350-01' : 'DRN-DOB-M350-01';
      this.draft = {
        region: this.draft.region || reg,
        county: this.draft.county || defaultCounty,
        lineCode: this.draft.lineCode || lineCode,
        droneId: this.draft.droneId || dronePrefix,
        flightStartedAt: this.draft.flightStartedAt || `${m[3]}-${m[4]}-${m[5]}T${m[6]}:${m[7]}`,
        durationSeconds: this.draft.durationSeconds,
        notes: this.draft.notes || `Inspecție video cu drona pe linia ${lineCode} (${reg}).`,
      };
    }
  }

  /** Starts a new upload, or continues the pending one when the same file is chosen again. */
  protected async start(): Promise<void> {
    const file = this.file;
    if (!file) {
      this.error.set('Alege fișierul video.');
      return;
    }
    let pending = this.pending();
    if (pending && !sameFile(pending, file)) {
      this.error.set(`Fișierul ales nu este cel început (${pending.name}). Alege-l pe acela sau renunță la încărcarea neterminată.`);
      return;
    }
    this.error.set(null);
    this.stored.set(null);
    this.retry.set(null);
    try {
      if (!pending) {
        pending = await this.begin(file);
      }
      this.phase.set('uploading');
      this.controller = new AbortController();
      await uploadParts(file, pending.uploadId,
        { status: this.api.videoUploadStatus, putPart: this.api.putVideoPart },
        { partSize: pending.partSizeBytes, signal: this.controller.signal, onProgress: (p) => this.onProgress(p) });

      this.phase.set('finishing');
      const ref = await this.api.completeVideoUpload(pending.uploadId);
      this.checksumMatches.set(ref.sha256?.toLowerCase() === pending.sha256);
      this.stored.set(ref);
      this.forget();
      this.draft = emptyDraft();
      this.phase.set('done');
    } catch (error) {
      if (error instanceof UploadPausedError) {
        this.phase.set('paused');
      } else if (pending && toProblem(error).status === 404) {
        // The server no longer knows this upload (expired or cleaned up): start over.
        this.forget();
        this.phase.set('idle');
        this.error.set('Sesiunea de încărcare a expirat pe server. Apasă din nou „Încarcă video-ul” pentru a o lua de la început.');
      } else {
        this.phase.set(pending ? 'failed' : 'idle');
        this.error.set(problemMessage(error));
      }
    } finally {
      this.retry.set(null);
    }
  }

  /** Hashes the file (reading it in slices) and opens an upload session. */
  private async begin(file: File): Promise<PendingUpload> {
    const { region, flightStartedAt } = this.draft;
    if (!region || !flightStartedAt) throw new Error('Completează regiunea și începutul zborului.');
    this.phase.set('hashing');
    this.fraction.set(0);
    const digest = await sha256OfBlob(file, (fraction) => this.fraction.set(fraction));
    const session = await this.api.startVideoUpload({
      region, county: this.draft.county, lineCode: this.draft.lineCode.trim(),
      droneId: this.draft.droneId.trim() || undefined, flightStartedAt: localToIso(flightStartedAt)!,
      durationSeconds: this.draft.durationSeconds ?? undefined, notes: this.draft.notes.trim() || undefined,
      filename: file.name, contentType: file.type || 'video/mp4', sizeBytes: file.size, sha256: digest,
    });
    const pending: PendingUpload = {
      ...session, name: file.name, size: file.size, lastModified: file.lastModified, sha256: digest, draft: this.draft,
    };
    localStorage.setItem(PENDING_KEY, JSON.stringify(pending));
    this.pending.set(pending);
    this.fraction.set(0);
    return pending;
  }

  private onProgress(progress: UploadProgress): void {
    this.fraction.set(progress.bytesTotal ? progress.bytesDone / progress.bytesTotal : 1);
    this.sent.set(`${formatBytes(progress.bytesDone)} din ${formatBytes(progress.bytesTotal)}`);
    this.retry.set(progress.retrying ?? null);
  }

  protected pause(): void {
    this.phase.set('pausing');
    this.controller?.abort();
  }

  protected discard(): void {
    this.forget();
    this.draft = emptyDraft();
    this.phase.set('idle');
    this.error.set(null);
  }

  private forget(): void {
    localStorage.removeItem(PENDING_KEY);
    this.pending.set(null);
  }

  // ---- search

  protected openSearch(): void {
    this.tab.set('search');
    if (!this.results.data()) this.search(0);
  }

  protected search(page: number): void {
    this.note.set(null);
    const filter = { ...this.filter, from: dayToIso(this.fromDay), to: dayToIso(this.toDay, 1), page, size: 20 };
    void this.results.run(() => this.api.videos(filter));
  }

  protected async download(video: DroneVideo): Promise<void> {
    this.downloading.set(true);
    this.note.set({ ok: true, text: 'Se descarcă… fișierele mari pot dura câteva minute.' });
    this.note.set(await downloadAndSave(() => this.api.downloadVideo(video.id)));
    this.downloading.set(false);
  }

  protected duration(seconds: number | undefined): string {
    if (!seconds) return '—';
    return `${Math.floor(seconds / 60)} min ${String(seconds % 60).padStart(2, '0')} s`;
  }
}

function readPending(): PendingUpload | null {
  try {
    return JSON.parse(localStorage.getItem(PENDING_KEY) ?? 'null') as PendingUpload | null;
  } catch {
    return null;
  }
}

function sameFile(pending: PendingUpload, file: File): boolean {
  return pending.name === file.name && pending.size === file.size && pending.lastModified === file.lastModified;
}
