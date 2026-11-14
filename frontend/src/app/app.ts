import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, setMockRole } from './core/auth';
import { OPERATOR } from './ui/legal-page';
import { Role } from './core/models';

interface NavItem { path: string; text: string; roles: Role[]; icon: string }

// 24px stroke icons, one consistent weight.
const NAV: NavItem[] = [
  { path: '/admin/connection-requests', text: 'Cereri de racordare', roles: ['admin'], icon: 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6' },
  { path: '/admin/storage-policies', text: 'Politici de stocare', roles: ['admin'], icon: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3' },
  { path: '/admin/statistics', text: 'Statistici și decizii', roles: ['admin'], icon: 'M4 20h16M7 16v-5M12 16V6M17 16v-8' },
  { path: '/admin/monitoring', text: 'Monitorizare', roles: ['admin'], icon: 'M3 12h4l3-7 4 14 3-7h4' },
  { path: '/employee/fault-records', text: 'Avarii', roles: ['employee', 'admin'], icon: 'M13 3 6 13h5l-1 8 7-10h-5z' },
  { path: '/employee/drone-videos', text: 'Video dronă', roles: ['employee', 'admin'], icon: 'M3 7h12v10H3zM15 10l6-3v10l-6-3' },
  { path: '/connection-requests/new', text: 'Cerere de racordare', roles: ['employee'], icon: 'M7 3h7l5 5v13H7zM14 3v5h5M12 11v6M9 14h6' },
  { path: '/client', text: 'Consumul meu', roles: ['client'], icon: 'M3 11 12 4l9 7M5 10v10h14V10M10 20v-5h4v5' },
];

const ROLE_NAME: Record<Role, string> = { admin: 'Administrator', employee: 'Angajat', client: 'Client' };

/** App shell: brand, the navigation for the user's roles, and the session controls. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip" href="#continut">Sari la conținut</a>
    <div class="shell" [class.with-nav]="items.length > 1">
      <header class="side">
        <a class="brand" routerLink="/">
          <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true"><rect width="24" height="24" rx="7" fill="var(--ink)"/><path d="M13.2 4.5 7.5 13.2h4.1l-1 6.3 5.9-8.9h-4.2z" fill="var(--hazard)"/></svg>
          FileGrid
        </a>
        @if (items.length > 1) {
          <nav aria-label="Meniu principal">
            @for (item of items; track item.path) {
              <a [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page">
                <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path [attr.d]="item.icon" /></svg>
                <span>{{ item.text }}</span>
              </a>
            }
          </nav>
        }
        <div class="session">
          @if (auth.mock) {
            <label class="mock">
              <span>Vezi aplicația ca</span>
              <select #role [value]="auth.roles[0]" (change)="switchRole(role.value)">
                <option value="client">Client</option>
                <option value="admin">Administrator</option>
                <option value="employee">Angajat</option>
              </select>
            </label>
          } @else if (!auth.username) {
            <button class="secondary small" type="button" (click)="auth.logout()">Autentificare</button>
          } @else {
            <span class="avatar" aria-hidden="true">{{ initial }}</span>
            <span class="who">
              <span class="user">{{ auth.username }}</span>
              <span class="role">{{ roleName }}</span>
            </span>
            <div class="actions">
              @if (auth.changePassword; as change) {
                <button class="secondary small" type="button" (click)="change()">Schimbă parola</button>
              }
              <button class="secondary small" type="button" (click)="auth.logout()">Ieșire</button>
            </div>
          }
        </div>
      </header>
      <main id="continut" tabindex="-1">
        <router-outlet />
        <footer class="site-footer">
          <nav aria-label="Informații legale">
            <a routerLink="/privacy">Confidențialitate</a>
            <a routerLink="/terms">Termeni și condiții</a>
            <a routerLink="/cookies">Cookie-uri</a>
            <a [href]="'mailto:' + operator.email">Contact</a>
            <a href="https://anpc.ro" rel="noopener" target="_blank">ANPC</a>
          </nav>
          <p>© {{ year }} {{ operator.name }} · FileGrid</p>
        </footer>
      </main>
    </div>
  `,
  styles: `
    .side { background: var(--card); border-bottom: 1px solid var(--line); padding: 14px clamp(16px, 4vw, 48px); display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px 16px; }
    .brand { display: inline-flex; align-items: center; gap: 10px; font: 700 1.25rem/1 var(--font-body); letter-spacing: -0.01em; color: var(--ink); text-decoration: none; }
    .brand:hover { color: var(--ink); }

    .side nav { order: 3; flex: 1 0 100%; display: flex; gap: 4px; overflow-x: auto; scrollbar-width: none; margin: 0 -8px; padding: 0 8px; }
    .side nav a { display: flex; align-items: center; gap: 10px; color: var(--ink-muted); text-decoration: none; padding: 9px 12px; border-radius: 9px; white-space: nowrap; font-weight: 500; transition: background-color 120ms ease-out, color 120ms ease-out; }
    .side nav svg { flex: none; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
    .side nav a:hover { color: var(--ink); background: var(--surface); }
    .side nav a.active { color: var(--primary-dark); background: var(--primary-tint); font-weight: 600; }

    .session { display: flex; align-items: center; gap: 10px; min-width: 0; }
    .avatar { flex: none; width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; background: var(--primary-tint); color: var(--primary-dark); font-weight: 700; text-transform: uppercase; }
    .who { display: grid; min-width: 0; line-height: 1.25; }
    .user { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .role { color: var(--ink-muted); font-size: 0.85rem; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .mock { display: flex; align-items: center; gap: 8px; color: var(--ink-muted); font-size: 0.9rem; font-weight: 500; }
    .mock select { min-height: 36px; padding: 4px 8px; width: auto; }

    @media (max-width: 899px) { .who { display: none; } }

    /* Desktop with several destinations: a fixed sidebar, the workspace scrolls beside it. */
    @media (min-width: 900px) {
      .shell.with-nav { display: grid; grid-template-columns: 260px minmax(0, 1fr); min-height: 100vh; }
      .with-nav .side {
        position: sticky; top: 0; height: 100vh; flex-direction: column; flex-wrap: nowrap; align-items: stretch; justify-content: flex-start;
        border-bottom: 0; border-right: 1px solid var(--line); padding: 22px 14px 16px; gap: 24px;
      }
      .with-nav .brand { padding: 0 8px; }
      .with-nav nav { order: 0; flex: 1 1 auto; flex-direction: column; overflow: auto; margin: 0; padding: 0; gap: 2px; }
      .with-nav .session { border-top: 1px solid var(--line); padding: 14px 6px 0; flex-wrap: wrap; }
      .with-nav .who { flex: 1; }
      .with-nav .mock { flex-direction: column; align-items: stretch; width: 100%; }
      .with-nav .mock select { width: 100%; }
    }

    main { display: flex; flex-direction: column; min-height: calc(100vh - 64px); }
    .site-footer { margin-top: auto; border-top: 1px solid var(--line); padding: 20px clamp(16px, 4vw, 48px); display: flex; flex-wrap: wrap; gap: 8px 24px; justify-content: space-between; align-items: center; color: var(--ink-muted); font-size: 0.88rem; }
    .site-footer nav { display: flex; flex-wrap: wrap; gap: 6px 18px; }
    .site-footer a { color: var(--ink-muted); }
    .site-footer a:hover { color: var(--ink); }
    .site-footer p { margin: 0; }
    @media (min-width: 900px) { .with-nav main { min-height: 100vh; } }

    .skip { position: absolute; left: -9999px; }
    .skip:focus { left: 8px; top: 8px; background: var(--card); padding: 8px 12px; border-radius: 8px; box-shadow: var(--shadow); z-index: 10; }
    main:focus { outline: none; }
  `,
})
export class App {
  protected readonly operator = OPERATOR;
  protected readonly year = new Date().getFullYear();
  protected readonly auth = inject(AuthService);
  protected readonly items = NAV.filter((item) => item.roles.some((role) => this.auth.hasRole(role)));
  protected readonly roleName = this.auth.roles.map((r) => ROLE_NAME[r]).filter(Boolean).join(', ');
  protected readonly initial = (this.auth.username || '?').charAt(0);

  protected switchRole(role: string): void {
    setMockRole(role as Role);
  }
}
