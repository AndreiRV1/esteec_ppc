import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import localeRo from '@angular/common/locales/ro';
import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/api';
import { AuthService } from './core/auth';
import { APP_CONFIG, AppConfig } from './core/config';
import { mockBackendInterceptor } from './core/mock-backend';

registerLocaleData(localeRo);

/** Config and auth are resolved in main.ts before bootstrap, so everything else can inject them synchronously. */
export function appConfig(config: AppConfig, auth: AuthService): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      provideRouter(routes, withComponentInputBinding()),
      provideHttpClient(withInterceptors([authInterceptor, mockBackendInterceptor])),
      { provide: APP_CONFIG, useValue: config },
      { provide: AuthService, useValue: auth },
      { provide: LOCALE_ID, useValue: 'ro' },
    ],
  };
}
