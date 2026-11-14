# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# Unprivileged nginx: runs as uid 101, listens on 8080, needs no capabilities.
FROM nginxinc/nginx-unprivileged:1.29-alpine
# Runtime configuration (served as /config.json). No secrets: the app is a public OIDC client.
ENV KEYCLOAK_URL=http://localhost:8180 \
    KEYCLOAK_REALM=filegrid \
    KEYCLOAK_CLIENT_ID=filegrid-web \
    GRAFANA_URL=/grafana \
    MOCK_API=false
COPY nginx/ /etc/nginx/templates/
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
