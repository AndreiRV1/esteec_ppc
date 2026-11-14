import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { AuthService } from './core/auth';
import { roleGuard } from './core/guards';

/** Each area is lazy-loaded and guarded by role; "/" sends the user to their own area. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: () => inject(AuthService).home() },
  {
    path: 'client',
    canActivate: [roleGuard],
    data: { roles: ['client'] },
    loadComponent: () => import('./client/client-page').then((m) => m.ClientPage),
  },
  {
    // Employees file requests for applicants; clients can file their own.
    path: 'racordare/noua',
    canActivate: [roleGuard],
    data: { roles: ['employee', 'client'] },
    loadComponent: () => import('./employee/connection-form-page').then((m) => m.ConnectionFormPage),
  },
  {
    path: 'admin',
    canActivate: [roleGuard],
    data: { roles: ['admin'] },
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'cereri' },
      { path: 'cereri', loadComponent: () => import('./admin/requests-page').then((m) => m.RequestsPage) },
      { path: 'cereri/:id', loadComponent: () => import('./admin/request-detail-page').then((m) => m.RequestDetailPage) },
      { path: 'politici', loadComponent: () => import('./admin/policies-page').then((m) => m.PoliciesPage) },
      { path: 'monitorizare', loadComponent: () => import('./admin/monitoring-page').then((m) => m.MonitoringPage) },
    ],
  },
  {
    path: 'angajat',
    canActivate: [roleGuard],
    data: { roles: ['employee'] },
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'avarii' },
      { path: 'avarii', loadComponent: () => import('./employee/faults-page').then((m) => m.FaultsPage) },
      { path: 'video', loadComponent: () => import('./employee/videos-page').then((m) => m.VideosPage) },
    ],
  },
  { path: 'fara-acces', loadComponent: () => import('./ui/no-access-page').then((m) => m.NoAccessPage) },
  { path: '**', redirectTo: '' },
];
