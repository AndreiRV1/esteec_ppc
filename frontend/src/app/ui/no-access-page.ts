import { Component, inject } from '@angular/core';
import { AuthService } from '../core/auth';

/** Shown to a signed-in account that has none of the FileGrid roles. */
@Component({
  template: `
    <div class="page">
      <h1>Contul nu are acces la FileGrid</h1>
      <p>
        Contul <span class="data">{{ auth.username }}</span> nu are niciun rol FileGrid (client, angajat sau
        administrator). Cere unui administrator să îți atribuie un rol, apoi autentifică-te din nou.
      </p>
      <div><button type="button" (click)="auth.logout()">Ieșire</button></div>
    </div>
  `,
})
export class NoAccessPage {
  protected readonly auth = inject(AuthService);
}
