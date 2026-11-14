import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { AuthService } from './core/auth';
import { roleGuard } from './core/guards';

/** Each area is lazy-loaded and guarded by role; "/" sends the user to their own area. Admin has access to all areas. */
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
    path: 'connection-requests/new',
    canActivate: [roleGuard],
    data: { roles: ['employee', 'client'] },
    loadComponent: () => import('./employee/connection-form-page').then((m) => m.ConnectionFormPage),
  },
  {
    // Public: a racordare without an account; the response carries the new client login.
    path: 'register',
    data: { publicForm: true },
    loadComponent: () => import('./employee/connection-form-page').then((m) => m.ConnectionFormPage),
  },
  {
    path: 'admin',
    canActivate: [roleGuard],
    data: { roles: ['admin'] },
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'connection-requests' },
      { path: 'connection-requests', loadComponent: () => import('./admin/requests-page').then((m) => m.RequestsPage) },
      { path: 'connection-requests/:id', loadComponent: () => import('./admin/request-detail-page').then((m) => m.RequestDetailPage) },
      { path: 'storage-policies', loadComponent: () => import('./admin/policies-page').then((m) => m.PoliciesPage) },
      { path: 'statistics', loadComponent: () => import('./admin/statistics-page').then((m) => m.StatisticsPage) },
      { path: 'monitoring', loadComponent: () => import('./admin/monitoring-page').then((m) => m.MonitoringPage) },
      // Legacy Romanian route redirects
      { path: 'cereri', redirectTo: 'connection-requests', pathMatch: 'full' },
      { path: 'cereri/:id', redirectTo: 'connection-requests/:id', pathMatch: 'full' },
      { path: 'politici', redirectTo: 'storage-policies', pathMatch: 'full' },
      { path: 'statistici', redirectTo: 'statistics', pathMatch: 'full' },
      { path: 'monitorizare', redirectTo: 'monitoring', pathMatch: 'full' },
    ],
  },
  {
    path: 'employee',
    canActivate: [roleGuard],
    data: { roles: ['employee', 'admin'] },
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'fault-records' },
      { path: 'fault-records', loadComponent: () => import('./employee/faults-page').then((m) => m.FaultsPage) },
      { path: 'drone-videos', loadComponent: () => import('./employee/videos-page').then((m) => m.VideosPage) },
    ],
  },
  // Legacy Romanian top-level route redirects
  { path: 'racordare/noua', redirectTo: '/connection-requests/new', pathMatch: 'full' },
  { path: 'angajat', redirectTo: '/employee/fault-records', pathMatch: 'full' },
  { path: 'angajat/avarii', redirectTo: '/employee/fault-records', pathMatch: 'full' },
  { path: 'angajat/video', redirectTo: '/employee/drone-videos', pathMatch: 'full' },
  { path: 'fara-acces', redirectTo: '/no-access', pathMatch: 'full' },
  // Legal pages: public, so they can be read before creating an account.
  { path: 'privacy', data: { doc: 'privacy' }, loadComponent: () => import('./ui/legal-page').then((m) => m.LegalPage) },
  { path: 'terms', data: { doc: 'terms' }, loadComponent: () => import('./ui/legal-page').then((m) => m.LegalPage) },
  { path: 'cookies', data: { doc: 'cookies' }, loadComponent: () => import('./ui/legal-page').then((m) => m.LegalPage) },
  { path: 'no-access', loadComponent: () => import('./ui/no-access-page').then((m) => m.NoAccessPage) },
  { path: '**', redirectTo: '' },
];
