import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from './auth';
import { roleGuard } from './guards';
import { Role } from './models';

function runGuard(userRoles: Role[], routeRoles: Role[]): boolean | string {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AuthService, useValue: new AuthService('user', userRoles, async () => null, () => {}) },
    ],
  });
  const route = { data: { roles: routeRoles } } as unknown as ActivatedRouteSnapshot;
  const result = TestBed.runInInjectionContext(() => roleGuard(route, {} as RouterStateSnapshot));
  return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : (result as boolean);
}

describe('roleGuard', () => {
  it('lets a user into a route that lists one of their roles, and lets admin into all routes', () => {
    expect(runGuard(['admin'], ['admin'])).toBe(true);
    expect(runGuard(['admin'], ['employee'])).toBe(true);
    expect(runGuard(['admin'], ['client'])).toBe(true);
    expect(runGuard(['client'], ['employee', 'client'])).toBe(true);
  });

  it('sends a user without the role to their own area', () => {
    expect(runGuard(['client'], ['admin'])).toBe('/client');
    expect(runGuard(['employee'], ['admin'])).toBe('/employee/fault-records');
  });

  it('sends an account with no FileGrid role to the no-access page', () => {
    expect(runGuard([], ['client'])).toBe('/no-access');
  });
});
