import { Component, input } from '@angular/core';
import { Loader } from '../core/api';

/** Loading and error lines for one page section. Renders nothing once data is there. */
@Component({
  selector: 'fg-status',
  template: `
    @if (of().error(); as message) {
      <p class="notice error" role="alert">{{ message }}</p>
    } @else if (of().loading() && of().data() === undefined) {
      <p class="muted" role="status">Se încarcă…</p>
    }
  `,
})
export class Status {
  readonly of = input.required<Loader<unknown>>();
}
