# FileGrid frontend

Angular 22 app (standalone components, strict TypeScript). UI in Romanian.

## Run

```sh
npm ci
npm run start:mock          # no backend needed: fixtures + a role switcher in the header
API_PORT=8080 npm start     # real mode: Keycloak login, /api proxied to the backend
npm test                    # role guard, API layer vs contract, chunked-upload resume
npm run build               # production build in dist/frontend/browser
```

The app runs on http://localhost:4200 (the redirect URI registered for the
`filegrid-web` Keycloak client). `API_PORT` is the backend port for the dev
proxy (default 8080); `GRAFANA_URL` is the Grafana target (default
http://localhost:3000). Both are read by `proxy.conf.js`.

## Configuration

The app loads `/config.json` at start, so one build works everywhere:

| Key | Default | Docker env |
|---|---|---|
| `keycloakUrl` | `http://localhost:8180` | `KEYCLOAK_URL` |
| `realm` | `filegrid` | `KEYCLOAK_REALM` |
| `clientId` | `filegrid-web` | `KEYCLOAK_CLIENT_ID` |
| `grafanaUrl` | `/grafana` | `GRAFANA_URL` |
| `mock` | `false` | `MOCK_API` (`true` / `false`) |

Nothing in it is secret: the app is a public OIDC client (Authorization Code +
PKCE). Tokens stay in memory, never in localStorage.

## Docker

```sh
docker build -t filegrid/frontend .
docker run -p 8081:8080 -e KEYCLOAK_URL=https://auth.example.org filegrid/frontend
```

nginx runs as a non-root user on port 8080, serves static files with an SPA
fallback, `/healthz`, and a Content-Security-Policy. It does not proxy: the
platform must route `/api` and `/grafana` on the same origin.

## Structure

- `src/app/core/api.ts`: the only file that knows the API's URLs and shapes.
- `src/app/core/mock-backend.ts`: mock mode, an HTTP interceptor with fixtures.
- `src/app/core/chunked-upload.ts`: video upload in parts, with retry and resume.
- `src/app/{client,admin,employee}`: the pages for each role.
