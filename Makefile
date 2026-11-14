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

.DEFAULT_GOAL := help
.PHONY: help env up dev down ps logs psql migrate test demo clean

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-8s %s\n", $$1, $$2}'

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
