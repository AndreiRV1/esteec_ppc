import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, setMockRole } from './core/auth';
import { Role } from './core/models';

interface NavItem { path: string; text: string; role: Role }

const NAV: NavItem[] = [
  { path: '/admin/connection-requests', text: 'Cereri de racordare', role: 'admin' },
  { path: '/admin/storage-policies', text: 'Politici de stocare', role: 'admin' },
  { path: '/admin/statistics', text: 'Statistici și decizii', role: 'admin' },
  { path: '/admin/monitoring', text: 'Monitorizare', role: 'admin' },
  { path: '/employee/fault-records', text: 'Avarii', role: 'employee' },
  { path: '/employee/drone-videos', text: 'Video dronă', role: 'employee' },
  { path: '/connection-requests/new', text: 'Cerere de racordare', role: 'employee' },
  { path: '/client', text: 'Consumul meu', role: 'client' },
];

/** App shell: brand, the navigation for the user's roles, and the session controls. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip" href="#continut">Sari la conținut</a>
    <header>
      <div class="bar">
        <a class="brand" routerLink="/">File<span>Grid</span></a>
        <div class="session">
          @if (auth.mock) {
            <label class="mock">
              <span>Demo ca</span>
              <select #role [value]="auth.roles[0]" (change)="switchRole(role.value)">
                <option value="client">Client</option>
                <option value="admin">Administrator</option>
                <option value="employee">Angajat</option>
              </select>
            </label>
          } @else {
            <span class="data user">{{ auth.username }}</span>
            <button class="secondary small" type="button" (click)="auth.logout()">Ieșire</button>
          }
        </div>
      </div>
      @if (items.length > 1) {
        <nav aria-label="Meniu principal">
          @for (item of items; track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page">{{ item.text }}</a>
          }
        </nav>
      }
    </header>
    <main id="continut" tabindex="-1">
      <router-outlet />
    </main>
  `,
  styles: `
    header { background: var(--ink); color: #fff; }
    .bar { max-width: 1120px; margin: 0 auto; padding: 10px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    .brand { font: 600 1.6rem/1 var(--font-display); letter-spacing: 0.02em; color: #fff; text-decoration: none; }
    .brand span { color: var(--hazard); }
    .session { display: flex; align-items: center; gap: 12px; min-width: 0; }
    .user { color: #c9d6da; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mock { display: flex; align-items: center; gap: 8px; color: #c9d6da; font-size: 0.9rem; }
    .mock select { min-height: 36px; padding: 4px 8px; width: auto; }
    nav { max-width: 1120px; margin: 0 auto; padding: 0 8px; display: flex; overflow-x: auto; }
    nav a { color: #c9d6da; text-decoration: none; padding: 12px 10px; white-space: nowrap; border-bottom: 3px solid transparent; font-weight: 500; }
    nav a:hover { color: #fff; }
    nav a.active { color: #fff; border-bottom-color: var(--hazard); }
    header :focus-visible { outline-color: var(--hazard); }
    .skip { position: absolute; left: -9999px; }
    .skip:focus { left: 8px; top: 8px; background: #fff; padding: 8px 12px; z-index: 10; }
    main:focus { outline: none; }
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly items = NAV.filter((item) => this.auth.hasRole(item.role));

  protected switchRole(role: string): void {
    setMockRole(role as Role);
  }
}
