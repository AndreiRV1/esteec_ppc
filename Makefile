COMPOSE := docker compose
DEV     := $(COMPOSE) -f compose.yaml -f compose.dev.yaml
# Long-running services; the one-shot init jobs are run separately so their
# exit code fails the target (`up --wait` treats an exited job as an error).
SERVICES := postgres minio ftp
INIT_JOBS := db-migrate minio-init
SIM_IMAGE := filegrid/meter-simulator:local
# Extra simulator flags, shared by `seed` and `sim` so both use the same fleet.
SIM_ARGS  ?=

.DEFAULT_GOAL := help
.PHONY: help env up dev down ps logs psql migrate clean sim-build seed sim

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-9s %s\n", $$1, $$2}'

.env:
	./scripts/gen-env.sh

env: .env ## Create .env with random secrets (no-op if it exists)

# $(1) = compose command to use
define start
	$(1) up -d --build --wait $(SERVICES)
	@for job in $(INIT_JOBS); do $(1) run --rm $$job || exit 1; done
	@echo "FileGrid storage layer is up."
endef

up: .env ## Start the stack (storages not exposed to the host)
	$(call start,$(COMPOSE))

dev: .env ## Start the stack with storages bound to 127.0.0.1 for local backend dev
	$(call start,$(DEV))

down: ## Stop the stack (keeps data)
	$(DEV) down

ps: ## Show service status
	$(DEV) ps -a

logs: ## Follow logs of all services
	$(DEV) logs -f --tail=100

psql: ## Open psql as the schema owner (filegrid_migrator)
	$(COMPOSE) exec postgres sh -c 'PGPASSWORD="$$FILEGRID_DB_MIGRATOR_PASSWORD" psql -h 127.0.0.1 -U filegrid_migrator -d "$$POSTGRES_DB"'

migrate: ## Re-run Flyway migrations
	$(COMPOSE) run --rm db-migrate

clean: ## Stop the stack and DELETE all data volumes
	$(DEV) down -v

sim-build: ## Build the meter simulator image
	docker build -q -t $(SIM_IMAGE) simulator >/dev/null

seed: .env sim-build ## Generate db/seed/meters.sql from the simulator fleet and load it
	@mkdir -p db/seed
	docker run --rm --user "$$(id -u):$$(id -g)" -v "$(CURDIR)/db/seed:/out" $(SIM_IMAGE) \
	  --spring.profiles.active=seed --simulator.seed-file=/out/meters.sql $(SIM_ARGS)
	$(COMPOSE) exec -T postgres sh -c 'PGPASSWORD="$$FILEGRID_DB_MIGRATOR_PASSWORD" psql -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U filegrid_migrator -d "$$POSTGRES_DB"' < db/seed/meters.sql
	@echo "Meters loaded into filegrid.meter."

sim: .env sim-build ## Run the meter simulator against FileGrid on localhost:8080 (SIM_ARGS=...)
	@key=$$(sed -n 's/^FILEGRID_METER_API_KEY=//p' .env); \
	[ -n "$$key" ] || { echo "FILEGRID_METER_API_KEY is missing from .env (see .env.example)" >&2; exit 1; }; \
	FILEGRID_METER_API_KEY="$$key" docker run --rm --init --name filegrid-meter-simulator \
	  --add-host=host.docker.internal:host-gateway -p 127.0.0.1:8090:8090 \
	  -e FILEGRID_METER_API_KEY -e SIMULATOR_SINK_URL=http://host.docker.internal:8080/api/v1/meter-readings \
	  $(SIM_IMAGE) $(SIM_ARGS)
