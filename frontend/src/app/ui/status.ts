import { Component, input } from '@angular/core';
import { Loader } from '../core/api';

/** Loading and error lines for one page section. Renders nothing once data is there. */
@Component({
  selector: 'fg-status',
  template: `
    @if (of().error(); as message) {
      <p class="notice error" role="alert">{{ message }}</p>
    } @else if (of().loading() && of().data() === undefined) {
      <div class="loading" role="status"><span class="sr">Se încarcă…</span><i></i><i></i><i></i></div>
    }
  `,
  styles: `
    .sr { position: absolute; left: -9999px; }
    .loading { display: grid; gap: 10px; padding: 4px 0; }
    .loading i { height: 14px; border-radius: 6px; background: linear-gradient(90deg, #e4e9ef 0%, #f1f4f7 50%, #e4e9ef 100%) 0 0 / 200% 100%; animation: shimmer 1.4s ease-in-out infinite; }
    .loading i:nth-child(3) { width: 80%; }
    .loading i:nth-child(4) { width: 55%; }
    @keyframes shimmer { to { background-position: -200% 0; } }
  `,
})
export class Status {
  readonly of = input.required<Loader<unknown>>();
}
