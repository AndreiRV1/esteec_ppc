import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { anonymousAuth, initKeycloakAuth, initMockAuth, PUBLIC_PATHS } from './app/core/auth';
import { loadConfig } from './app/core/config';

async function start(): Promise<void> {
  const config = await loadConfig();
  // In real mode this redirects to Keycloak and only returns once the user is signed in.
  const auth = config.mock
    ? initMockAuth()
    : PUBLIC_PATHS.includes(window.location.pathname)
      ? anonymousAuth() // registration: no account yet
      : await initKeycloakAuth(config);
  await bootstrapApplication(App, appConfig(config, auth));
}

start().catch((error) => {
  console.error(error);
  document.body.innerHTML =
    '<p style="font-family:system-ui;padding:24px">FileGrid nu a putut porni: serviciul de autentificare nu răspunde. Reîncarcă pagina.</p>';
});
