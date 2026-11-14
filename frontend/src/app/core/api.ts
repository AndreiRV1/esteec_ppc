import { HttpClient, HttpErrorResponse, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom, from, switchMap } from 'rxjs';
import { AuthService } from './auth';
import {
  AdminRequestDetail, Bucket, ConnectionFiles, ConnectionRequestForm, Consumption, ConsumptionSummary, Decision,
  DroneVideo, Fault, FaultFilter, FaultMetadata, FileCategory, Me, Page, Problem, Region, RequestFilter,
  RequestSummary, StoragePolicy, StoragePolicyUpdate, StoredRef, SystemStatistics, UploadSession, UploadStatus,
  VideoFilter, VideoUploadInit,
} from './models';
import { sha256OfBlob } from './sha256';

const BASE = '/api/v1';

/** A downloaded file. `verified` is true when its SHA-256 matches the checksum the server sent. */
export interface Download {
  blob: Blob;
  filename: string;
  verified: boolean | null;
}

/**
 * The only place that knows FileGrid's URLs and payload shapes. If the backend
 * contract changes, this file (and models.ts) is what changes.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  // ---- current user ----------------------------------------------------------
  me = () => this.get<Me>('/me');
  consumption = (fromIso: string, toIso: string, bucket: Bucket) =>
    this.get<Consumption>('/me/consumption', { from: fromIso, to: toIso, bucket });
  consumptionSummary = () => this.get<ConsumptionSummary>('/me/consumption/summary');
  myFaults = () => this.get<Fault[]>('/me/faults');

  // ---- connection requests ---------------------------------------------------
  /** The backend returns a bare array here today; a page object is accepted too. */
  async myRequests(): Promise<RequestSummary[]> {
    const body = await this.get<RequestSummary[] | Page<RequestSummary>>('/connection-requests');
    return Array.isArray(body) ? body : body.items;
  }

  submitRequest(form: ConnectionRequestForm, files: ConnectionFiles): Promise<RequestSummary> {
    const data = new FormData();
    data.append('request', jsonPart(form));
    data.append('cerereRacordare', files.cerereRacordare);
    data.append('actIdentitate', files.actIdentitate);
    data.append('aviz', files.aviz);
    return firstValueFrom(this.http.post<RequestSummary>(`${BASE}/connection-requests`, data));
  }

  adminRequests = (filter: RequestFilter) =>
    this.get<Page<RequestSummary>>('/admin/connection-requests', { ...filter });
  adminRequest = (clientId: string) => this.get<AdminRequestDetail>(`/admin/connection-requests/${clientId}`);
  decide = (clientId: string, decision: Decision, reason?: string) =>
    firstValueFrom(
      this.http.post<RequestSummary>(`${BASE}/admin/connection-requests/${clientId}/decision`, {
        decision,
        ...(reason ? { reason } : {}),
      }),
    );
  downloadArchive = (clientId: string) => this.download(`/admin/connection-requests/${clientId}/archive`);
  downloadDocument = (clientId: string, documentId: string) =>
    this.download(`/admin/connection-requests/${clientId}/documents/${documentId}`);

  // ---- storage policy & statistics -------------------------------------------
  storagePolicies = () => this.get<StoragePolicy[]>('/admin/storage-policies');
  updateStoragePolicy = (category: FileCategory, region: Region, body: StoragePolicyUpdate) =>
    firstValueFrom(this.http.put<StoragePolicy | null>(`${BASE}/admin/storage-policies/${category}/${region}`, body));
  statistics = () => this.get<SystemStatistics>('/admin/statistics');

  // ---- fault records ---------------------------------------------------------
  createFault(metadata: FaultMetadata, file: File): Promise<StoredRef> {
    const data = new FormData();
    data.append('metadata', jsonPart(metadata));
    data.append('file', file);
    return firstValueFrom(this.http.post<StoredRef>(`${BASE}/fault-records`, data));
  }
  faults = (filter: FaultFilter) => this.get<Page<Fault>>('/fault-records', { ...filter });
  downloadFault = (id: string) => this.download(`/fault-records/${id}/file`);

  // ---- drone videos ----------------------------------------------------------
  startVideoUpload = (body: VideoUploadInit) =>
    firstValueFrom(this.http.post<UploadSession>(`${BASE}/drone-videos/uploads`, body));
  /** Part numbers start at 1. */
  putVideoPart = (uploadId: string, partNumber: number, part: Blob) =>
    firstValueFrom(
      this.http.put<void>(`${BASE}/drone-videos/uploads/${uploadId}/parts/${partNumber}`, part, {
        headers: { 'Content-Type': 'application/octet-stream' },
      }),
    );
  videoUploadStatus = (uploadId: string) => this.get<UploadStatus>(`/drone-videos/uploads/${uploadId}`);
  completeVideoUpload = (uploadId: string) =>
    firstValueFrom(this.http.post<StoredRef>(`${BASE}/drone-videos/uploads/${uploadId}/complete`, null));
  videos = (filter: VideoFilter) => this.get<Page<DroneVideo>>('/drone-videos', { ...filter });
  // ponytail: downloads are buffered as a Blob (the browser spools big ones to disk).
  // For multi-GB videos add a short-lived download ticket so the browser can stream with Range.
  downloadVideo = (id: string) => this.download(`/drone-videos/${id}/file`);

  // ---- plumbing --------------------------------------------------------------
  private get<T>(path: string, query: object = {}): Promise<T> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') params = params.set(key, String(value));
    }
    return firstValueFrom(this.http.get<T>(BASE + path, { params }));
  }

  private async download(path: string): Promise<Download> {
    const response = await firstValueFrom(this.http.get(BASE + path, { responseType: 'blob', observe: 'response' }));
    const blob = response.body ?? new Blob();
    const expected = response.headers.get('X-Checksum-SHA256');
    return {
      blob,
      filename: filenameOf(response.headers.get('Content-Disposition')) ?? path.split('/').slice(-2).join('-'),
      verified: expected ? (await sha256OfBlob(blob)) === expected.toLowerCase() : null,
    };
  }
}

function jsonPart(value: unknown): Blob {
  return new Blob([JSON.stringify(value)], { type: 'application/json' });
}

function filenameOf(contentDisposition: string | null): string | null {
  if (!contentDisposition) return null;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition);
  if (encoded) return decodeURIComponent(encoded[1]);
  return /filename="?([^";]+)"?/i.exec(contentDisposition)?.[1] ?? null;
}

/** Hands a downloaded file to the browser's save dialog. */
export function saveBlob(download: Download): void {
  const url = URL.createObjectURL(download.blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = download.filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Downloads a file and saves it, refusing one whose checksum does not match. Returns a line to show the user. */
export async function downloadAndSave(fetch: () => Promise<Download>): Promise<{ ok: boolean; text: string }> {
  try {
    const file = await fetch();
    if (file.verified === false) {
      return { ok: false, text: `${file.filename}: amprenta SHA-256 nu corespunde. Fișierul nu a fost salvat.` };
    }
    saveBlob(file);
    const proof = file.verified ? ' Amprenta SHA-256 corespunde cu cea de pe server.' : '';
    return { ok: true, text: `${file.filename} descărcat.${proof}` };
  } catch (error) {
    return { ok: false, text: problemMessage(error) };
  }
}

/** Adds the bearer token to API calls. Other requests (config, fonts) are left alone. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/')) return next(request);
  return from(inject(AuthService).token()).pipe(
    switchMap((token) => next(token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request)),
  );
};

// ---- errors -------------------------------------------------------------------

export function toProblem(error: unknown): Problem {
  if (error instanceof HttpErrorResponse) {
    const body = error.error && typeof error.error === 'object' && !(error.error instanceof Blob) ? error.error : {};
    return { status: error.status, ...body };
  }
  // Not an HTTP failure (a bug or a local check): -1 keeps it apart from status 0, "network unreachable".
  return { status: -1, detail: error instanceof Error ? error.message : String(error) };
}

const MESSAGES: Record<string, string> = {
  reason_required: 'Respingerea are nevoie de un motiv.',
  already_decided: 'Cererea a fost deja soluționată de alt administrator.',
  own_request: 'Nu poți soluționa propria cerere.',
  invalid_identity_number: 'CNP-ul sau CUI-ul nu este valid.',
  unsupported_document_format: 'Documentele trebuie să fie PDF, JPEG sau PNG.',
  document_missing: 'Lipsește un document obligatoriu.',
  document_too_large: 'Un document depășește 10 MB.',
  file_too_large: 'Fișierul depășește dimensiunea maximă permisă de politica de stocare.',
  storage_unavailable: 'Spațiul de stocare pentru această categorie nu răspunde. Încearcă din nou în câteva minute.',
  integrity_check_failed: 'Fișierul nu a trecut verificarea de integritate și nu a fost livrat.',
  not_found: 'Nu am găsit ce căutai.',
};

/** A message a person can act on, in Romanian, for any failed call. */
export function problemMessage(error: unknown): string {
  const problem = toProblem(error);
  const known = problem.code ? MESSAGES[problem.code] : undefined;
  if (known) return known;
  if (problem.status === 0) return 'Nu am putut contacta serverul. Verifică conexiunea și încearcă din nou.';
  if (problem.status === 401) return 'Sesiunea a expirat. Reîncarcă pagina pentru a te autentifica din nou.';
  if (problem.status === 403) return 'Contul tău nu are acces la această operațiune.';
  if (problem.status === 404) return 'Nu am găsit ce căutai (sau serviciul nu este încă disponibil).';
  if (problem.status === 400) return 'Datele trimise nu sunt valide. Verifică fiecare câmp și încearcă din nou.';
  if (problem.status === 413) return 'Fișierul este prea mare.';
  return problem.detail ?? `Operațiunea a eșuat (cod ${problem.status}).`;
}

/** Loading / error / data state for one independent section of a page. */
export class Loader<T> {
  readonly data = signal<T | undefined>(undefined);
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);
  private latest = 0;

  async run(load: () => Promise<T>): Promise<void> {
    const call = ++this.latest;
    this.loading.set(true);
    this.error.set(null);
    try {
      const data = await load();
      if (call === this.latest) this.data.set(data);
    } catch (error) {
      if (call === this.latest) this.error.set(problemMessage(error));
    } finally {
      if (call === this.latest) this.loading.set(false);
    }
  }
}
