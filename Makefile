COMPOSE := docker compose
DEV     := $(COMPOSE) -f compose.yaml -f compose.dev.yaml
# Long-running services; the one-shot init jobs are run separately so their
# exit code fails the target (`up --wait` treats an exited job as an error).
SERVICES  := postgres minio ftp keycloak
INIT_JOBS := db-migrate minio-init
# Builds and tests run in a container: only Docker is needed on the machine.
MAVEN := docker run --rm -v "$(CURDIR)":/repo -v filegrid-m2:/root/.m2 \
	-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	-w /repo/backend maven:3.9-eclipse-temurin-25 mvn -B

SIM_IMAGE := filegrid/meter-simulator:local
# Extra simulator flags, shared by `seed` and `sim` so both use the same fleet.
SIM_ARGS  ?=

.DEFAULT_GOAL := help
.PHONY: help env up dev down ps logs psql migrate test demo clean \
	gen-data gen-storm gen-large-video seed-data verify-data data-status sim-build seed seed-clients sim

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-16s %s\n", $$1, $$2}'

.env:
	./scripts/gen-env.sh

env: ## Create .env, or add variables that are new in .env.example (keeps existing secrets)
	./scripts/gen-env.sh

# $(1) = compose command to use
define start
	$(1) up -d --build --wait $(SERVICES)
	@for job in $(INIT_JOBS); do $(1) run --rm $$job || exit 1; done
endef

up: .env ## Start everything: backend on :8080, Keycloak on :8180 (storages not exposed)
	$(call start,$(COMPOSE))
	$(COMPOSE) up -d --build --no-deps --wait backend
	@echo "FileGrid is up: API http://localhost:8080/swagger-ui.html, login http://localhost:8180"

dev: .env ## Start everything except the backend, with storages on 127.0.0.1 (run the backend from your IDE)
	$(call start,$(DEV))
	@echo "Storages and Keycloak are up; start the backend with: cd backend && ./mvnw spring-boot:run"

down: ## Stop the stack (keeps data)
	$(DEV) --profile backend-in-docker down

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

demo: ## Walk through a racordare end to end against the running stack (needs `make up`)
	./scripts/demo-racordare.sh

clean: ## Stop the stack and DELETE all data volumes
	$(DEV) --profile backend-in-docker down -v
	$(DEV) down -v

gen-data: ## Generate regional fault JSONs (1-10 MB) and drone MP4 videos (GEN_ARGS=...)
	python3 backend/fault_videos/fault_videos.py generate $(GEN_ARGS)

gen-storm: ## Generate a 10x storm spike of fault JSONs and post-storm drone MP4s
	python3 backend/fault_videos/fault_videos.py generate --profile storm $(GEN_ARGS)

gen-large-video: ## Generate a >1 GB drone inspection MP4 video for scalability testing
	python3 backend/fault_videos/fault_videos.py generate --faults-per-region 0 --videos-per-region 0 --large-video --large-video-mb 1050

seed-data: .env ## Upload generated faults & videos through the FileGrid API (needs `make up`; DATA_ARGS="--workers 8")
	python3 backend/fault_videos/fault_videos.py ingest $(DATA_ARGS)

verify-data: .env ## Download uploaded faults & videos through the API and compare SHA-256
	python3 backend/fault_videos/fault_videos.py verify $(DATA_ARGS)

data-status: ## Show the local dataset and what FileGrid holds per region
	python3 backend/fault_videos/fault_videos.py status

sim-build: ## Build the meter simulator image
	docker build -q -t $(SIM_IMAGE) simulator >/dev/null

seed: .env sim-build ## Generate db/seed/meters.sql from the simulator fleet and load it
	@mkdir -p db/seed
	docker run --rm --user "$$(id -u):$$(id -g)" -v "$(CURDIR)/db/seed:/out" $(SIM_IMAGE) \
	  --spring.profiles.active=seed --simulator.seed-file=/out/meters.sql $(SIM_ARGS)
	$(COMPOSE) exec -T postgres sh -c 'PGPASSWORD="$$FILEGRID_DB_MIGRATOR_PASSWORD" psql -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U filegrid_migrator -d "$$POSTGRES_DB"' < db/seed/meters.sql
	@echo "Meters loaded into filegrid.meter."

seed-clients: .env ## Create demo customer logins for seeded meters (CLIENTS=n per region; writes db/seed/clients.csv)
	./scripts/seed-clients.py $(CLIENTS)

sim: .env sim-build ## Run the meter simulator against FileGrid on localhost:8080 (SIM_ARGS=...)
	@key=$$(sed -n 's/^FILEGRID_METER_API_KEY=//p' .env); \
	[ -n "$$key" ] || { echo "FILEGRID_METER_API_KEY is missing from .env (see .env.example)" >&2; exit 1; }; \
	FILEGRID_METER_API_KEY="$$key" docker run --rm --init --name filegrid-meter-simulator \
	  --add-host=host.docker.internal:host-gateway -p 127.0.0.1:8090:8090 \
	  -e FILEGRID_METER_API_KEY -e SIMULATOR_SINK_URL=http://host.docker.internal:8080/api/v1/meter-readings \
	  $(SIM_IMAGE) $(SIM_ARGS)
