import { InjectionToken } from '@angular/core';
import { environment } from '../../environments/environment';

/** Runtime configuration, served as /config.json so one image works in every environment. */
export interface AppConfig {
  keycloakUrl: string;
  realm: string;
  clientId: string;
  grafanaUrl: string;
  /** Serve fixtures instead of calling the backend, and skip Keycloak. */
  mock: boolean;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

const DEFAULTS: AppConfig = {
  keycloakUrl: 'http://localhost:8180',
  realm: 'filegrid',
  clientId: 'filegrid-web',
  grafanaUrl: '/grafana',
  mock: environment.mock,
};

export async function loadConfig(): Promise<AppConfig> {
  try {
    const response = await fetch('config.json', { cache: 'no-store' });
    const json = response.ok ? ((await response.json()) as Partial<AppConfig>) : {};
    // A mock build stays mock whatever the runtime file says.
    return { ...DEFAULTS, ...json, mock: environment.mock || json.mock === true };
  } catch {
    return DEFAULTS;
  }
}
