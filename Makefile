ENV_FILE ?= $(or $(FILEGRID_ENV_FILE),.env)
COMPOSE  := docker compose --env-file $(ENV_FILE)
DEV      := $(COMPOSE) -f compose.yaml -f compose.dev.yaml
# Long-running services; the one-shot init jobs are run separately so their
# exit code fails the target (`up --wait` treats an exited job as an error).
SERVICES  := postgres minio ftp keycloak
INIT_JOBS := db-migrate minio-init
# Started after the backend: metrics, logs, traces, alerts and the entry point.
PLATFORM  := prometheus blackbox alertmanager mailpit loki alloy tempo grafana proxy
# The web UI joins the stack as soon as frontend/Dockerfile exists.
FRONTEND  := $(if $(wildcard frontend/Dockerfile),frontend)
PROFILES  := $(if $(FRONTEND),--profile frontend)
PUBLIC_URL = $$(sed -n 's/^FILEGRID_PUBLIC_URL=//p' $(ENV_FILE) | grep . || echo https://localhost:8443)
# Builds and tests run in a container: only Docker is needed on the machine.
MAVEN := docker run --rm --add-host=host.docker.internal:host-gateway -v "$(CURDIR)":/repo -v filegrid-m2:/root/.m2 \
	-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	-w /repo/backend maven:3.9-eclipse-temurin-25 mvn -B

SIM_IMAGE := filegrid/meter-simulator:local
# Extra simulator flags, shared by `seed` and `sim` so both use the same fleet.
SIM_ARGS  ?=
# Fleet size for `seed`, `sim` and the always-on simulator service; all three must agree.
export SIMULATOR_METERS_COUNT ?= 2000
# `make up SIMULATOR=0` starts the stack without simulated meter traffic.
SIMULATOR ?= 1

.DEFAULT_GOAL := help
.PHONY: help env up dev down ps logs psql migrate test demo demo-setup clean seed-if-empty \
	gen-data gen-storm gen-large-video seed-data verify-data data-status sim-build seed seed-clients sim

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-16s %s\n", $$1, $$2}'

$(ENV_FILE):
	FILEGRID_ENV_FILE=$(ENV_FILE) ./scripts/gen-env.sh

env: ## Create .env, or add variables that are new in .env.example (keeps existing secrets)
	FILEGRID_ENV_FILE=$(ENV_FILE) ./scripts/gen-env.sh

# $(1) = compose command to use
define start
	$(1) up -d --build --wait $(SERVICES)
	@for job in $(INIT_JOBS); do $(1) run --rm $$job || exit 1; done
endef

up: env ## Start everything behind the TLS proxy on https://localhost:8443 (nothing else is published)
	$(call start,$(COMPOSE))
	$(COMPOSE) $(PROFILES) up -d --build --no-deps --wait $(PLATFORM) $(FRONTEND)
	FILEGRID_ENV_FILE=$(ENV_FILE) ./scripts/keycloak-sync.sh
	$(COMPOSE) up -d --build --no-deps --wait backend
	@$(MAKE) -s seed-if-empty
	$(if $(filter 1,$(SIMULATOR)),$(COMPOSE) --profile simulator up -d --build --no-deps simulator)
	@url=$(PUBLIC_URL); echo "FileGrid is up (local CA certificate: accept it once in the browser):"; \
	echo "  App      $$url/"; echo "  API docs $$url/swagger-ui.html"; \
	echo "  Login    $$url/auth/realms/filegrid/account/"; \
	echo "  Grafana  $$url/grafana/   (admin@filegrid.local)"; echo "  Alerts   $$url/mailpit/"; \
	echo "Demo data and logins: \`make demo-setup\` (walkthrough in docs/demo.md)."

dev: env ## Start everything except the backend, with storages on 127.0.0.1 (run the backend from your IDE)
	$(call start,$(DEV))
	$(DEV) up -d --no-deps --wait $(PLATFORM)
	@url=$(PUBLIC_URL); echo "Storages, Keycloak, proxy and observability are up."; \
	echo "Start the backend with: cd backend && ./mvnw spring-boot:run   (API then at $$url/api/v1)"

down: ## Stop the stack (keeps data)
	$(DEV) --profile backend-in-docker --profile frontend --profile simulator down

ps: ## Show service status
	$(COMPOSE) ps -a

logs: ## Follow logs of all services
	$(COMPOSE) logs -f --tail=100

psql: ## Open psql as the schema owner (filegrid_migrator)
	$(COMPOSE) exec postgres sh -c 'PGPASSWORD="$$FILEGRID_DB_MIGRATOR_PASSWORD" psql -h 127.0.0.1 -U filegrid_migrator -d "$$POSTGRES_DB"'

migrate: ## Re-run Flyway migrations
	$(COMPOSE) run --rm db-migrate

test: ## Run backend unit + integration tests (Testcontainers, in Docker)
	$(MAVEN) verify

demo-setup: $(ENV_FILE) ## Prepare the running stack for the showcase in docs/demo.md (data + logins)
	FILEGRID_ENV_FILE=$(ENV_FILE) ./scripts/demo-setup.sh

demo: ## Walk through a racordare end to end against the running stack (needs `make up`)
	FILEGRID_ENV_FILE=$(ENV_FILE) ./scripts/demo-racordare.sh

clean: ## Stop the stack and DELETE all data volumes
	$(DEV) --profile backend-in-docker --profile frontend --profile simulator down -v

gen-data: ## Generate regional fault JSONs (1-10 MB) and drone MP4 videos (GEN_ARGS=...)
	python3 backend/fault_videos/fault_videos.py generate $(GEN_ARGS)

gen-storm: ## Generate a 10x storm spike of fault JSONs and post-storm drone MP4s
	python3 backend/fault_videos/fault_videos.py generate --profile storm $(GEN_ARGS)

gen-large-video: ## Generate a >1 GB drone inspection MP4 video for scalability testing
	python3 backend/fault_videos/fault_videos.py generate --faults-per-region 0 --videos-per-region 0 --large-video --large-video-mb 1050

seed-data: $(ENV_FILE) ## Upload generated faults & videos through the FileGrid API (needs `make up`; DATA_ARGS="--workers 8")
	FILEGRID_ENV_FILE=$(ENV_FILE) python3 backend/fault_videos/fault_videos.py ingest $(DATA_ARGS)

verify-data: $(ENV_FILE) ## Download uploaded faults & videos through the API and compare SHA-256
	FILEGRID_ENV_FILE=$(ENV_FILE) python3 backend/fault_videos/fault_videos.py verify $(DATA_ARGS)

data-status: ## Show the local dataset and what FileGrid holds per region
	FILEGRID_ENV_FILE=$(ENV_FILE) python3 backend/fault_videos/fault_videos.py status

sim-build: ## Build the meter simulator image
	docker build -q -t $(SIM_IMAGE) simulator >/dev/null

seed: $(ENV_FILE) sim-build ## Generate db/seed/meters.sql from the simulator fleet and load it
	@mkdir -p db/seed
	docker run --rm --user "$$(id -u):$$(id -g)" -e SIMULATOR_METERS_COUNT -v "$(CURDIR)/db/seed:/out" $(SIM_IMAGE) \
	  --spring.profiles.active=seed --simulator.seed-file=/out/meters.sql $(SIM_ARGS)
	$(COMPOSE) exec -T postgres sh -c 'PGPASSWORD="$$FILEGRID_DB_MIGRATOR_PASSWORD" psql -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U filegrid_migrator -d "$$POSTGRES_DB"' < db/seed/meters.sql
	@echo "Meters loaded into filegrid.meter."

# Loads the simulator's meter fleet on a fresh database, so `make up` alone gives live readings.
seed-if-empty: $(ENV_FILE)
	@n=$$($(COMPOSE) exec -T postgres sh -c 'psql -tA -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -c "SELECT count(*) FROM filegrid.meter"' 2>/dev/null); \
	if [ "$${n:-0}" = "0" ]; then echo "Fresh database: loading the meter fleet"; $(MAKE) -s seed >/dev/null; fi

seed-clients: $(ENV_FILE) ## Create demo customer logins for seeded meters (CLIENTS=n per region; writes db/seed/clients.csv)
	FILEGRID_ENV_FILE=$(ENV_FILE) ./scripts/seed-clients.py $(CLIENTS)

sim: $(ENV_FILE) sim-build ## Run the meter simulator through the proxy (SIM_ARGS=...)
	@key=$$(sed -n 's/^FILEGRID_METER_API_KEY=//p' $(ENV_FILE) | tail -n1); \
	[ -n "$$key" ] || { echo "FILEGRID_METER_API_KEY is missing from $(ENV_FILE) (see .env.example)" >&2; exit 1; }; \
	proj=$${COMPOSE_PROJECT_NAME:-filegrid}; \
	FILEGRID_METER_API_KEY="$$key" docker run --rm --init --name "$$proj-meter-simulator" \
	  --network "$${proj}_edge" --add-host=host.docker.internal:host-gateway \
	  -p "127.0.0.1:$${SIMULATOR_PORT:-8090}:8090" \
	  -e FILEGRID_METER_API_KEY -e SIMULATOR_METERS_COUNT \
	  -e SIMULATOR_SINK_URL="$${SIMULATOR_SINK_URL:-http://proxy:8080/api/v1/meter-readings}" \
	  $(SIM_IMAGE) $(SIM_ARGS)
