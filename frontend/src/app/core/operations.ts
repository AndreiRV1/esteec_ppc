import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { tap } from 'rxjs';

/**
 * The trace id of the last change the user made (upload, submission, decision). The backend
 * returns it as X-Trace-Id; the same id is stored with the file, so one operation can be
 * followed from the proxy down to the storage call in Grafana (Tempo).
 */
@Injectable({ providedIn: 'root' })
export class OperationTracker {
  readonly last = signal<string | null>(null);
}

export const traceInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.method === 'GET') return next(request);
  const tracker = inject(OperationTracker);
  return next(request).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) tracker.last.set(event.headers.get('X-Trace-Id'));
    }),
  );
};

const TRACE_ID = /^[0-9a-f]{32}$/;

export function isTraceId(value: string): boolean {
  return TRACE_ID.test(value.trim().toLowerCase());
}

/** Grafana Explore with that trace open (Tempo datasource uid "tempo"). Admins only: Grafana requires the admin role. */
export function traceUrl(grafanaBase: string, traceId: string): string {
  const panes = {
    t: {
      datasource: 'tempo',
      queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: traceId.trim().toLowerCase() }],
      range: { from: 'now-7d', to: 'now' },
    },
  };
  return `${grafanaBase.replace(/\/$/, '')}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}
