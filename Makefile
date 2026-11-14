COMPOSE := docker compose
DEV     := $(COMPOSE) -f compose.yaml -f compose.dev.yaml
# Long-running services; the one-shot init jobs are run separately so their
# exit code fails the target (`up --wait` treats an exited job as an error).
SERVICES := postgres minio ftp
INIT_JOBS := db-migrate minio-init

.DEFAULT_GOAL := help
.PHONY: help env up dev down ps logs psql migrate clean gen-data gen-storm gen-large-video seed-data verify-data data-status

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-16s %s\n", $$1, $$2}'

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

gen-data: ## Generate regional fault JSONs (1-10 MB) and drone MP4 videos
	python3 backend/fault_videos/fault_videos.py generate

gen-storm: ## Generate a 10x storm spike of fault JSONs and post-storm drone MP4s
	python3 backend/fault_videos/fault_videos.py generate --profile storm

gen-large-video: ## Generate a >1 GB drone inspection MP4 video for scalability testing
	python3 backend/fault_videos/fault_videos.py generate --faults-per-region 0 --videos-per-region 0 --large-video --large-video-mb 1050

seed-data: .env ## Ingest generated fault JSONs & drone MP4s into running `make dev` storages
	python3 backend/fault_videos/fault_videos.py ingest

verify-data: .env ## Verify SHA-256 integrity of stored fault JSONs & drone MP4s in `make dev`
	python3 backend/fault_videos/fault_videos.py verify

data-status: ## Show regional dataset summary and `make dev` storage counts
	python3 backend/fault_videos/fault_videos.py status
