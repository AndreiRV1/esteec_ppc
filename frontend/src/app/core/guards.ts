import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth';
import { Role } from './models';

/**
 * Allows a route when the user has one of `data.roles`; otherwise sends them to
 * their own area. The backend enforces the same rules: this only keeps people
 * out of pages that would show them nothing but errors.
 */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const allowed = (route.data['roles'] as Role[] | undefined) ?? [];
  return allowed.some((role) => auth.hasRole(role)) || inject(Router).parseUrl(auth.home());
};
