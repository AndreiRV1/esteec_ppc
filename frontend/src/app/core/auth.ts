import Keycloak from 'keycloak-js';
import { AppConfig } from './config';
import { Role } from './models';

const KNOWN_ROLES: Role[] = ['admin', 'employee', 'client'];
const MOCK_ROLE_KEY = 'fg.mockRole';

/**
 * The signed-in user. Tokens live in memory only (inside keycloak-js); a page
 * reload goes through Keycloak again, which answers from its SSO session.
 */
export class AuthService {
  constructor(
    readonly username: string,
    readonly roles: readonly Role[],
    /** A valid access token, refreshed when it is about to expire. */
    readonly token: () => Promise<string | null>,
    readonly logout: () => void,
    readonly mock = false,
  ) {}

  hasRole(role: Role): boolean {
    return this.roles.includes('admin') || this.roles.includes(role);
  }

  /** Landing page for this user: the area of their most privileged role. */
  home(): string {
    if (this.roles.includes('admin')) return '/admin/connection-requests';
    if (this.roles.includes('employee')) return '/employee/fault-records';
    if (this.roles.includes('client')) return '/client';
    return '/no-access';
  }
}

/** Authorization Code + PKCE against Keycloak. Redirects to the login page when needed. */
export async function initKeycloakAuth(config: AppConfig): Promise<AuthService> {
  const keycloak = new Keycloak({ url: config.keycloakUrl, realm: config.realm, clientId: config.clientId });
  await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });

  const claims = keycloak.tokenParsed ?? {};
  const realmRoles: string[] = claims['realm_access']?.roles ?? [];
  const token = async () => {
    try {
      await keycloak.updateToken(30);
    } catch {
      await keycloak.login(); // session ended: back to the login page
    }
    return keycloak.token ?? null;
  };
  return new AuthService(
    claims['preferred_username'] ?? claims['sub'] ?? '',
    KNOWN_ROLES.filter((role) => realmRoles.includes(role)),
    token,
    () => void keycloak.logout({ redirectUri: window.location.origin }),
  );
}

/** Mock mode: no Keycloak; the role comes from the switcher in the header. */
export function initMockAuth(): AuthService {
  const stored = sessionStorage.getItem(MOCK_ROLE_KEY) as Role | null;
  const role: Role = stored && KNOWN_ROLES.includes(stored) ? stored : 'client';
  const names: Record<Role, string> = { client: 'C-104233', admin: 'admin.demo', employee: 'angajat.demo' };
  return new AuthService(names[role], [role], async () => null, () => setMockRole('client'), true);
}

export function setMockRole(role: Role): void {
  sessionStorage.setItem(MOCK_ROLE_KEY, role);
  window.location.assign('/');
}
