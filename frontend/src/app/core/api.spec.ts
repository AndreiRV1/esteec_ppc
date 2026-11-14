import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiService, authInterceptor, problemMessage, toProblem } from './api';
import { AuthService } from './auth';
import { ConnectionRequestForm } from './models';

/** Checks ApiService against the API contract: method, URL, query, body and auth header of each call. */
describe('ApiService', () => {
  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: new AuthService('u', ['admin'], async () => 'token-123', () => {}) },
      ],
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** The auth interceptor resolves the token asynchronously, so the request appears a tick later. */
  const expectOne = async (method: string, url: string) => {
    await new Promise((resolve) => setTimeout(resolve));
    const request = http.expectOne((r) => r.method === method && r.url === url);
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-123');
    return request;
  };

  it('requests consumption with from, to and bucket', async () => {
    const result = api.consumption('2026-11-01T00:00:00.000Z', '2026-11-08T00:00:00.000Z', 'HOUR');
    const call = await expectOne('GET', '/api/v1/me/consumption');
    expect(call.request.params.get('from')).toBe('2026-11-01T00:00:00.000Z');
    expect(call.request.params.get('to')).toBe('2026-11-08T00:00:00.000Z');
    expect(call.request.params.get('bucket')).toBe('HOUR');
    call.flush({ meterId: 'M1', bucket: 'HOUR', points: [{ start: '2026-11-01T00:00:00Z', kwh: 0.4, anomalies: 0 }] });
    expect((await result).points[0].kwh).toBe(0.4);
  });

  it('submits a connection request as multipart with a JSON "request" part and three files', async () => {
    const form = { applicantType: 'PERSOANA_FIZICA', requestedPowerKw: 9.5 } as ConnectionRequestForm;
    const file = (name: string) => new File(['x'], name, { type: 'application/pdf' });
    const result = api.submitRequest(form, {
      cerereRacordare: file('cerere.pdf'), actIdentitate: file('ci.pdf'), aviz: file('aviz.pdf'),
    });
    const call = await expectOne('POST', '/api/v1/connection-requests');
    const body = call.request.body as FormData;
    const requestPart = body.get('request') as Blob;
    expect(requestPart.type).toBe('application/json');
    expect(JSON.parse(await requestPart.text())).toEqual(form);
    expect((body.get('cerereRacordare') as File).name).toBe('cerere.pdf');
    expect((body.get('actIdentitate') as File).name).toBe('ci.pdf');
    expect((body.get('aviz') as File).name).toBe('aviz.pdf');
    call.flush({ clientId: 'c1', status: 'PENDING' }, { status: 201, statusText: 'Created' });
    expect((await result).status).toBe('PENDING');
  });

  it('accepts both a bare array and a page for "my requests"', async () => {
    const asArray = api.myRequests();
    (await expectOne('GET', '/api/v1/connection-requests')).flush([{ clientId: 'a' }]);
    expect((await asArray).map((r) => r.clientId)).toEqual(['a']);

    const asPage = api.myRequests();
    (await expectOne('GET', '/api/v1/connection-requests')).flush({ items: [{ clientId: 'b' }], page: 0, size: 20, total: 1 });
    expect((await asPage).map((r) => r.clientId)).toEqual(['b']);
  });

  it('lists admin requests with only the filters that are set', async () => {
    void api.adminRequests({ status: 'PENDING', region: '', page: 0, size: 20 });
    const call = await expectOne('GET', '/api/v1/admin/connection-requests');
    expect(call.request.params.keys().sort()).toEqual(['page', 'size', 'status']);
    call.flush({ items: [], page: 0, size: 20, total: 0 });
  });

  it('posts a decision, sending the reason only when there is one', async () => {
    void api.decide('c1', 'REJECTED', 'Aviz expirat');
    const rejected = await expectOne('POST', '/api/v1/admin/connection-requests/c1/decision');
    expect(rejected.request.body).toEqual({ decision: 'REJECTED', reason: 'Aviz expirat' });
    rejected.flush({});

    void api.decide('c1', 'ACCEPTED');
    const accepted = await expectOne('POST', '/api/v1/admin/connection-requests/c1/decision');
    expect(accepted.request.body).toEqual({ decision: 'ACCEPTED' });
    accepted.flush({});
  });

  it('updates a storage policy by category and region', async () => {
    const update = { backend: 'FTP' as const, target: '/files/x', encryptAtRest: false, maxSizeBytes: 1024 };
    void api.updateStoragePolicy('FAULT_RECORD', 'BANAT', update);
    const call = await expectOne('PUT', '/api/v1/admin/storage-policies/FAULT_RECORD/BANAT');
    expect(call.request.body).toEqual(update);
    call.flush(null);
  });

  it('uploads a fault record as multipart with "metadata" and "file"', async () => {
    void api.createFault(
      { region: 'BANAT', county: 'Timiș', locality: 'Lugoj', substationCode: 'ST-1', occurredAt: '2026-11-01T10:00:00.000Z', severity: 'HIGH' },
      new File(['{}'], 'fault.json'),
    );
    const call = await expectOne('POST', '/api/v1/fault-records');
    const body = call.request.body as FormData;
    expect(JSON.parse(await (body.get('metadata') as Blob).text()).substationCode).toBe('ST-1');
    expect((body.get('file') as File).name).toBe('fault.json');
    call.flush({ id: 'f1', fileId: 'x', sha256: 'abc' }, { status: 201, statusText: 'Created' });
  });

  it('drives the video upload endpoints: init, raw part, status, complete', async () => {
    void api.startVideoUpload({
      region: 'BANAT', county: 'Timiș', lineCode: 'LEA-1', flightStartedAt: '2026-11-01T10:00:00.000Z',
      filename: 'v.mp4', contentType: 'video/mp4', sizeBytes: 10, sha256: 'ab',
    });
    (await expectOne('POST', '/api/v1/drone-videos/uploads')).flush({ uploadId: 'u1', partSizeBytes: 8 });

    const part = new Blob(['12345678']);
    void api.putVideoPart('u1', 1, part);
    const put = await expectOne('PUT', '/api/v1/drone-videos/uploads/u1/parts/1');
    expect(put.request.body).toBe(part);
    expect(put.request.headers.get('Content-Type')).toBe('application/octet-stream');
    put.flush(null, { status: 204, statusText: 'No Content' });

    void api.videoUploadStatus('u1');
    (await expectOne('GET', '/api/v1/drone-videos/uploads/u1')).flush({ status: 'IN_PROGRESS', bytesReceived: 8, receivedParts: [1] });

    void api.completeVideoUpload('u1');
    (await expectOne('POST', '/api/v1/drone-videos/uploads/u1/complete')).flush({ id: 'v1', fileId: 'f', sha256: 'ab' });
  });

  it('verifies a download against the X-Checksum-SHA256 header and reads its file name', async () => {
    // SHA-256 of "hello"
    const digest = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824';
    const headers = { 'X-Checksum-SHA256': digest, 'Content-Disposition': 'attachment; filename="racordare-c1.zip"' };

    const good = api.downloadArchive('c1');
    (await expectOne('GET', '/api/v1/admin/connection-requests/c1/archive')).flush(new Blob(['hello']), { headers });
    expect(await good).toMatchObject({ verified: true, filename: 'racordare-c1.zip' });

    const tampered = api.downloadArchive('c1');
    (await expectOne('GET', '/api/v1/admin/connection-requests/c1/archive')).flush(new Blob(['hellO']), { headers });
    expect((await tampered).verified).toBe(false);
  });

  it('turns RFC 9457 problems into a code and a Romanian message', async () => {
    const result = api.decide('c1', 'ACCEPTED').catch((error) => error);
    (await expectOne('POST', '/api/v1/admin/connection-requests/c1/decision')).flush(
      { status: 409, code: 'already_decided', detail: 'Request is no longer PENDING', traceId: 't1' },
      { status: 409, statusText: 'Conflict' },
    );
    const error = await result;
    expect(toProblem(error)).toMatchObject({ status: 409, code: 'already_decided', traceId: 't1' });
    expect(problemMessage(error)).toContain('deja soluționată');
  });
});
