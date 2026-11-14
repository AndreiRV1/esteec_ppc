import { Component, inject, input } from '@angular/core';
import { AuthService } from '../core/auth';
import { APP_CONFIG } from '../core/config';
import { OperationTracker, traceUrl } from '../core/operations';

/**
 * "ID operație" of the last change: quote it to support, or (admins) open the whole operation in
 * Grafana, from the proxy down to the storage write.
 */
@Component({
  selector: 'fg-operation',
  template: `
    @if (id(); as traceId) {
      <p class="op">
        ID operație: <span class="data">{{ traceId }}</span>
        @if (admin) { · <a [href]="link(traceId)" target="_blank" rel="noopener">Urmărește operația ↗</a> }
      </p>
    }
  `,
  styles: `.op { margin: 0; font-size: 0.9rem; color: var(--ink-muted); word-break: break-all; }`,
})
export class OperationId {
  private readonly tracker = inject(OperationTracker);
  private readonly grafana = inject(APP_CONFIG).grafanaUrl;
  protected readonly admin = inject(AuthService).hasRole('admin');
  /** A specific trace id; defaults to the user's last change. */
  readonly traceId = input<string | null | undefined>(undefined);
  protected id = () => this.traceId() ?? this.tracker.last();
  protected link = (id: string) => traceUrl(this.grafana, id);
}
