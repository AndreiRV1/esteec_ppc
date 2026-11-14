import { HttpErrorResponse, HttpHeaders, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';
import { delay, from } from 'rxjs';
import { AuthService } from './auth';
import { APP_CONFIG } from './config';
import {
  AdminRequestDetail, ConnectionRequestForm, ConsumptionPoint, DroneVideo, Fault, FaultMetadata, Me, Region,
  RequestSummary, StoragePolicy, StoragePolicyUpdate, VideoUploadInit,
} from './models';
import { COUNTIES } from './format';

/**
 * Mock mode: answers every /api call from in-memory fixtures that follow the
 * contract, so each page can be shown without a backend. It sits below
 * ApiService, so the real request code runs in mock mode too.
 */
export const mockBackendInterceptor: HttpInterceptorFn = (request, next) => {
  if (!inject(APP_CONFIG).mock || !request.url.startsWith('/api/v1/')) return next(request);
  const role = inject(AuthService).roles[0];
  return from(handle(request, role)).pipe(delay(250));
};

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();
const uuid = () => crypto.randomUUID();
const page = <T>(items: T[], request: HttpRequest<unknown>) => {
  const size = Number(request.params.get('size') ?? 20);
  const index = Number(request.params.get('page') ?? 0);
  return { items: items.slice(index * size, (index + 1) * size), page: index, size, total: items.length };
};
const ok = (body: unknown, status = 200) => new HttpResponse({ status, body });

function problem(status: number, code: string, detail: string): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: { status, code, detail, traceId: uuid().replaceAll('-', '') } });
}

function fileResponse(text: string, filename: string, type: string): HttpResponse<Blob> {
  const bytes = new TextEncoder().encode(text);
  return new HttpResponse({
    status: 200,
    body: new Blob([bytes], { type }),
    headers: new HttpHeaders({
      'Content-Disposition': `attachment; filename="${filename}"`,
      'X-Checksum-SHA256': bytesToHex(sha256(bytes)),
    }),
  });
}

// ---- fixtures -----------------------------------------------------------------

const me: Me = {
  username: 'C-104233', roles: ['client'], region: 'MUNTENIA', county: 'Ilfov', locality: 'Voluntari',
  meterId: 'RO-MUN-0001042',
};

/** Deterministic household load curve: morning and evening peaks, a little more at weekends. */
function hourlyKwh(hourStart: number): number {
  const date = new Date(hourStart);
  const profile = [.22, .18, .16, .15, .16, .22, .45, .62, .5, .38, .34, .36, .42, .4, .36, .38, .48, .7, .92, 1.02, .9, .68, .44, .3];
  const weekend = date.getDay() === 0 || date.getDay() === 6 ? 1.18 : 1;
  const noise = 0.85 + ((Math.sin(hourStart / HOUR * 12.9898) * 43758.5453) % 1 + 1) % 1 * 0.3;
  return profile[date.getHours()] * weekend * noise;
}

function consumptionPoints(fromMs: number, toMs: number, bucket: 'HOUR' | 'DAY'): ConsumptionPoint[] {
  const points: ConsumptionPoint[] = [];
  const now = Date.now();
  const start = new Date(fromMs);
  start.setMinutes(0, 0, 0);
  if (bucket === 'DAY') start.setHours(0);
  for (let t = start.getTime(); t < Math.min(toMs, now); ) {
    const next = bucket === 'HOUR' ? t + HOUR : new Date(new Date(t).setDate(new Date(t).getDate() + 1)).getTime();
    let kwh = 0;
    for (let h = t; h < Math.min(next, now); h += HOUR) kwh += hourlyKwh(h);
    const anomalies = Math.floor(t / HOUR) % 53 === 0 ? 1 : 0;
    points.push({ start: new Date(t).toISOString(), kwh: Math.round(kwh * 1000) / 1000, anomalies });
    t = next;
  }
  return points;
}

const sumKwh = (fromMs: number, toMs: number) =>
  Math.round(consumptionPoints(fromMs, toMs, 'DAY').reduce((sum, p) => sum + p.kwh, 0) * 10) / 10;

const faults: Fault[] = [
  { id: uuid(), region: 'MUNTENIA', county: 'Ilfov', locality: 'Voluntari', substationCode: 'ST-MUN-VOL-20', occurredAt: iso(95 * 60_000), restoredAt: null, severity: 'HIGH', cause: 'Conductor rupt după furtună', affectedCustomers: 1240, sizeBytes: 3_412_990 },
  { id: uuid(), region: 'MUNTENIA', county: 'București', locality: 'Sector 3', substationCode: 'ST-MUN-BUC-SUD-110', occurredAt: iso(2 * DAY), restoredAt: iso(2 * DAY - 3 * HOUR), severity: 'MEDIUM', cause: 'Defect transformator', affectedCustomers: 310, sizeBytes: 1_850_112 },
  { id: uuid(), region: 'DOBROGEA', county: 'Constanța', locality: 'Mangalia', substationCode: 'ST-DOB-MGL-110', occurredAt: iso(3 * DAY), restoredAt: iso(3 * DAY - 5 * HOUR), severity: 'CRITICAL', cause: 'Furtună, stâlpi căzuți', affectedCustomers: 5400, sizeBytes: 9_120_400 },
  { id: uuid(), region: 'DOBROGEA', county: 'Tulcea', locality: 'Babadag', substationCode: 'ST-DOB-BBD-20', occurredAt: iso(3 * DAY + 2 * HOUR), restoredAt: iso(3 * DAY - HOUR), severity: 'HIGH', cause: 'Furtună, scurtcircuit', affectedCustomers: 870, sizeBytes: 4_300_000 },
  { id: uuid(), region: 'BANAT', county: 'Timiș', locality: 'Lugoj', substationCode: 'ST-BAN-LGJ-110', occurredAt: iso(6 * DAY), restoredAt: iso(6 * DAY - 40 * 60_000), severity: 'LOW', cause: 'Declanșare protecție', affectedCustomers: 45, sizeBytes: 1_020_300 },
  { id: uuid(), region: 'BANAT', county: 'Arad', locality: 'Ineu', substationCode: 'ST-BAN-INU-20', occurredAt: iso(9 * DAY), restoredAt: iso(9 * DAY - 2 * HOUR), severity: 'MEDIUM', cause: 'Vegetație pe linie', affectedCustomers: 210, sizeBytes: 2_230_000 },
];

const videos: DroneVideo[] = [
  { id: uuid(), region: 'MUNTENIA', county: 'Ilfov', lineCode: 'LEA-110kV-MUN-BUC-OTOPENI-01', droneId: 'DR-07', flightStartedAt: iso(DAY), durationSeconds: 1260, sizeBytes: 1_420_000_000, filename: 'otopeni-01.mp4' },
  { id: uuid(), region: 'DOBROGEA', county: 'Constanța', lineCode: 'LEA-110kV-DOB-CT-MANGALIA-02', droneId: 'DR-03', flightStartedAt: iso(3 * DAY), durationSeconds: 840, sizeBytes: 760_000_000, filename: 'mangalia-02.mp4', notes: 'Inspecție după furtună' },
  { id: uuid(), region: 'BANAT', county: 'Timiș', lineCode: 'LEA-110kV-BAN-TM-LUGOJ-01', droneId: 'DR-11', flightStartedAt: iso(8 * DAY), durationSeconds: 515, sizeBytes: 402_000_000, filename: 'lugoj-01.mp4' },
];

const summary = (id: string, patch: Partial<RequestSummary>): RequestSummary => ({
  clientId: id, region: 'MUNTENIA', applicantType: 'PERSOANA_FIZICA', requestType: 'LOC_CONSUM_NOU',
  county: 'Ilfov', locality: 'Voluntari', requestedPowerKw: 9.5, status: 'PENDING', statusReason: null,
  createdAt: iso(DAY), reviewedAt: null, ...patch,
});

/** The first two belong to the mock client; all of them appear in the admin queue. */
const requests: RequestSummary[] = [
  summary('01a1d9d9-ddcf-7f07-b1aa-7903afac6a31', { createdAt: iso(2 * DAY) }),
  summary('01a1c2a0-11aa-7c10-9e21-51be0a3317f0', { requestType: 'SPOR_PUTERE', requestedPowerKw: 14, status: 'ACCEPTED', createdAt: iso(40 * DAY), reviewedAt: iso(38 * DAY) }),
  summary('01a1d801-93b1-7d22-8a3e-0c4e5b6a7d10', { region: 'BANAT', county: 'Timiș', locality: 'Lugoj', applicantType: 'PERSOANA_JURIDICA', requestedPowerKw: 80, createdAt: iso(3 * DAY) }),
  summary('01a1d7f3-5c0e-7a90-b7f1-9d2c3e4f5a61', { region: 'DOBROGEA', county: 'Constanța', locality: 'Năvodari', requestedPowerKw: 6, createdAt: iso(4 * DAY) }),
  summary('01a1d6c2-7e11-7b33-9c55-2f1e0d9c8b72', { region: 'DOBROGEA', county: 'Tulcea', locality: 'Babadag', status: 'REJECTED', statusReason: 'Avizul de amplasament a expirat.', createdAt: iso(10 * DAY), reviewedAt: iso(9 * DAY) }),
  summary('01a1d5b1-2a44-7e55-8d66-3a2b1c0d9e83', { county: 'București', locality: 'Sector 2', requestType: 'SEPARARE', requestedPowerKw: 12, status: 'ACCEPTED', createdAt: iso(15 * DAY), reviewedAt: iso(13 * DAY) }),
];
const MY_REQUESTS = 2;

function detailOf(request: RequestSummary): AdminRequestDetail {
  const doc = (n: number, type: string, slug: string, ext: string, mime: string, size: number) => ({
    id: `${request.clientId.slice(0, 35)}${n}`, documentType: type, entryName: `0${n}-${slug}.${ext}`, contentType: mime,
    sizeBytes: size, sha256: bytesToHex(sha256(new TextEncoder().encode(request.clientId + n))),
    issuer: n === 3 ? 'Rețele Electrice – Sucursala ' + request.county : null,
    issuedOn: n === 3 ? '2026-09-01' : null, validUntil: n === 3 ? '2027-09-01' : null,
  });
  const company = request.applicantType === 'PERSOANA_JURIDICA';
  return {
    request,
    submittedBy: 'C-104233',
    reviewedBy: request.status === 'PENDING' ? null : 'admin.demo',
    applicant: {
      fullName: company ? 'Agro Lugoj SRL' : 'Ion Popescu',
      identityNumber: company ? '14399840' : '1960529460012',
      email: company ? 'office@agrolugoj.example' : 'ion.popescu@example.com',
      phone: '+40 721 000 000',
      address: `Str. Exemplului nr. 1, ${request.locality}, ${request.county}`,
    },
    consumptionPlace: {
      county: COUNTIES.find((c) => c.name === request.county)?.code ?? request.county,
      locality: request.locality,
      address: 'Str. Exemplului nr. 2',
    },
    permit: {
      type: 'AVIZ_AMPLASAMENT', number: 'AA-2026-0042', issuer: 'Rețele Electrice – Sucursala ' + request.county,
      issuedOn: '2026-09-01', validUntil: '2027-09-01',
    },
    documents: [
      doc(1, 'CERERE_RACORDARE', 'cerere-racordare', 'pdf', 'application/pdf', 182_400),
      doc(2, 'ACT_IDENTITATE', 'act-identitate', 'jpg', 'image/jpeg', 961_200),
      doc(3, 'AVIZ_AMPLASAMENT', 'aviz-amplasament', 'pdf', 'application/pdf', 240_100),
    ],
    archive: {
      fileId: uuid(), backend: 'S3', sizeBytes: 1_301_877,
      sha256: bytesToHex(sha256(new TextEncoder().encode(request.clientId))), encryption: 'AES_256_GCM',
      storedAt: request.createdAt, lastVerifiedAt: new Date().toISOString(),
    },
    otherRequestsFromApplicant: requests.slice(0, MY_REQUESTS).filter((r) => r.clientId !== request.clientId && !company),
  };
}

const REGION_LIST: Region[] = ['MUNTENIA', 'BANAT', 'DOBROGEA'];
const policies: StoragePolicy[] = REGION_LIST.flatMap((region): StoragePolicy[] => [
  { category: 'FAULT_RECORD', region, backend: 'FTP', target: '/files/fault-records', encryptAtRest: false, maxSizeBytes: 50 * 2 ** 20, updatedAt: iso(20 * DAY), updatedBy: 'filegrid_migrator' },
  { category: 'CONNECTION_DOCUMENTS', region, backend: 'S3', target: 'filegrid-connection-documents', encryptAtRest: true, maxSizeBytes: 50 * 2 ** 20, updatedAt: iso(20 * DAY), updatedBy: 'filegrid_migrator' },
  { category: 'DRONE_VIDEO', region, backend: 'S3', target: 'filegrid-drone-videos', encryptAtRest: false, maxSizeBytes: 10 * 2 ** 30, updatedAt: iso(20 * DAY), updatedBy: 'filegrid_migrator' },
]);

/** In-progress video uploads survive a reload (localStorage), like a real upload session. */
interface MockUpload { init: VideoUploadInit; receivedParts: number[] }
const UPLOADS_KEY = 'fg.mock.uploads';
const PART_SIZE = 8 * 2 ** 20;
const loadUploads = (): Record<string, MockUpload> => JSON.parse(localStorage.getItem(UPLOADS_KEY) ?? '{}');
const saveUploads = (uploads: Record<string, MockUpload>) => localStorage.setItem(UPLOADS_KEY, JSON.stringify(uploads));
let partCalls = 0;

const inRange = (value: string, request: HttpRequest<unknown>) => {
  const fromIso = request.params.get('from');
  const toIso = request.params.get('to');
  return (!fromIso || value >= fromIso) && (!toIso || value < toIso);
};
const matches = (param: string, value: string | undefined, request: HttpRequest<unknown>) =>
  !request.params.get(param) || request.params.get(param) === value;

// ---- router -------------------------------------------------------------------

async function handle(request: HttpRequest<unknown>, role: string | undefined): Promise<HttpResponse<unknown>> {
  const path = request.url.replace('/api/v1', '');
  const route = `${request.method} ${path}`;
  let m: RegExpExecArray | null;
  const need = (...allowed: string[]) => {
    if (!role || !allowed.includes(role)) throw problem(403, 'forbidden', 'Access denied');
  };

  if (route === 'GET /me') return ok({ ...me, roles: [role] });
  if (route === 'GET /me/consumption') {
    const bucket = request.params.get('bucket') === 'HOUR' ? 'HOUR' : 'DAY';
    const points = consumptionPoints(Date.parse(request.params.get('from')!), Date.parse(request.params.get('to')!), bucket);
    return ok({ meterId: me.meterId, bucket, points });
  }
  if (route === 'GET /me/consumption/summary') {
    const now = new Date();
    const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
    const previousStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime();
    return ok({
      monthToDateKwh: sumKwh(monthStart, now.getTime()), previousMonthKwh: sumKwh(previousStart, monthStart),
      lastReadingAt: iso(7 * 60_000),
    });
  }
  if (route === 'GET /me/faults') return ok(faults.filter((f) => f.county === me.county && !f.restoredAt));

  // ---- connection requests
  if (route === 'GET /connection-requests') return ok(requests.slice(0, MY_REQUESTS));
  if (route === 'POST /connection-requests') {
    need('client', 'employee');
    const data = request.body as FormData;
    const form = JSON.parse(await (data.get('request') as Blob).text()) as ConnectionRequestForm;
    if (!/^\d{13}$|^(RO)?\d{2,10}$/i.test(form.applicant.identityNumber.replace(/\s/g, ''))) {
      throw problem(400, 'invalid_identity_number', 'applicant.identityNumber is not valid');
    }
    const county = COUNTIES.find((c) => c.code === form.consumptionPlace.county)!;
    const created = summary(uuid(), {
      region: county.region, county: county.name, locality: form.consumptionPlace.locality,
      applicantType: form.applicantType, requestType: form.requestType, requestedPowerKw: form.requestedPowerKw,
      createdAt: new Date().toISOString(),
    });
    requests.unshift(created);
    return ok(created, 201);
  }
  if (route === 'GET /admin/connection-requests') {
    need('admin');
    const found = requests
      .filter((r) => matches('status', r.status, request) && matches('region', r.region, request))
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    return ok(page(found, request));
  }
  if ((m = /^GET \/admin\/connection-requests\/([^/]+)(\/archive|\/documents\/[^/]+)?$/.exec(route))) {
    need('admin');
    const found = requests.find((r) => r.clientId === m![1]);
    if (!found) throw problem(404, 'not_found', 'Connection request not found');
    if (!m[2]) return ok(detailOf(found));
    if (m[2] === '/archive') return fileResponse(`Arhivă demo pentru ${found.clientId}`, `racordare-${found.clientId}.zip`, 'application/zip');
    return fileResponse('%PDF-1.4\n% document demo FileGrid\n', 'document-demo.pdf', 'application/pdf');
  }
  if ((m = /^POST \/admin\/connection-requests\/([^/]+)\/decision$/.exec(route))) {
    need('admin');
    const found = requests.find((r) => r.clientId === m![1]);
    const body = request.body as { decision: 'ACCEPTED' | 'REJECTED'; reason?: string };
    if (!found) throw problem(404, 'not_found', 'Connection request not found');
    if (body.decision === 'REJECTED' && !body.reason?.trim()) throw problem(400, 'reason_required', 'A reason is required');
    if (found.status !== 'PENDING') throw problem(409, 'already_decided', 'Request is no longer PENDING');
    Object.assign(found, { status: body.decision, statusReason: body.reason ?? null, reviewedAt: new Date().toISOString() });
    return ok(found);
  }

  // ---- storage policies
  if (route === 'GET /admin/storage-policies') {
    need('admin');
    return ok(policies);
  }
  if ((m = /^PUT \/admin\/storage-policies\/([A-Z_]+)\/([A-Z]+)$/.exec(route))) {
    need('admin');
    const policy = policies.find((p) => p.category === m![1] && p.region === m![2]);
    const body = request.body as StoragePolicyUpdate;
    if (!policy) throw problem(404, 'not_found', 'Policy not found');
    if (policy.category === 'CONNECTION_DOCUMENTS' && !body.encryptAtRest) {
      throw problem(400, 'encryption_required', 'Connection documents must be encrypted at rest');
    }
    Object.assign(policy, body, { updatedAt: new Date().toISOString(), updatedBy: 'admin.demo' });
    return ok(policy);
  }

  // ---- fault records
  if (route === 'POST /fault-records') {
    need('employee', 'admin');
    const data = request.body as FormData;
    const metadata = JSON.parse(await (data.get('metadata') as Blob).text()) as FaultMetadata;
    const file = data.get('file') as File;
    const created: Fault = {
      id: uuid(), ...metadata, restoredAt: metadata.restoredAt ?? null, cause: metadata.cause ?? null,
      affectedCustomers: metadata.affectedCustomers ?? null, sizeBytes: file.size,
    };
    faults.unshift(created);
    return ok({ id: created.id, fileId: uuid(), sha256: bytesToHex(sha256(new Uint8Array(await file.arrayBuffer()))) }, 201);
  }
  if (route === 'GET /fault-records') {
    need('employee', 'admin');
    const found = faults.filter((f) =>
      matches('region', f.region, request) && matches('county', f.county, request) &&
      matches('severity', f.severity, request) && inRange(f.occurredAt, request));
    return ok(page(found, request));
  }
  if (/^GET \/fault-records\/[^/]+\/file$/.test(route)) {
    return fileResponse('{"fault_record":"demo","samples":[]}', 'avarie-demo.json', 'application/json');
  }

  // ---- drone videos
  if (route === 'POST /drone-videos/uploads') {
    need('employee', 'admin');
    const uploads = loadUploads();
    const uploadId = 'mock-' + uuid();
    uploads[uploadId] = { init: request.body as VideoUploadInit, receivedParts: [] };
    saveUploads(uploads);
    return ok({ uploadId, partSizeBytes: PART_SIZE }, 201);
  }
  if ((m = /^(PUT|GET|POST) \/drone-videos\/uploads\/([^/]+)(?:\/parts\/(\d+)|\/(complete))?$/.exec(route))) {
    const uploads = loadUploads();
    const upload = uploads[m[2]];
    if (!upload) throw problem(404, 'not_found', 'Upload not found');
    if (m[3]) {
      // Every 7th part fails once, so the retry-with-backoff path is visible in a demo.
      if (++partCalls % 7 === 0) throw problem(503, 'storage_unavailable', 'Simulated unstable link');
      await new Promise((resolve) => setTimeout(resolve, 350));
      upload.receivedParts = [...new Set([...upload.receivedParts, Number(m[3])])].sort((a, b) => a - b);
      saveUploads(uploads);
      return ok(null, 204);
    }
    if (m[4]) {
      const { filename, contentType, sizeBytes, sha256: digest, ...metadata } = upload.init;
      const created: DroneVideo = { id: uuid(), ...metadata, filename, sizeBytes, sha256: digest, createdAt: new Date().toISOString() };
      void contentType;
      videos.unshift(created);
      delete uploads[m[2]];
      saveUploads(uploads);
      return ok({ id: created.id, fileId: uuid(), sha256: digest }, 201);
    }
    const bytesReceived = Math.min(upload.init.sizeBytes, upload.receivedParts.length * PART_SIZE);
    return ok({ status: 'IN_PROGRESS', bytesReceived, receivedParts: upload.receivedParts });
  }
  if (route === 'GET /drone-videos') {
    need('employee', 'admin');
    const lineCode = request.params.get('lineCode')?.toLowerCase();
    const found = videos.filter((v) =>
      matches('region', v.region, request) && (!lineCode || v.lineCode.toLowerCase().includes(lineCode)) &&
      inRange(v.flightStartedAt, request));
    return ok(page(found, request));
  }
  if (/^GET \/drone-videos\/[^/]+\/file$/.test(route)) {
    return fileResponse('video demo FileGrid', 'video-demo.mp4', 'video/mp4');
  }

  throw problem(404, 'not_found', `No mock for ${route}`);
}
