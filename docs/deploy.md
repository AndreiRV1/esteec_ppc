# Putting FileGrid on the internet (free)

FileGrid runs as one Docker Compose stack. Measured on the running demo: about **2.7 GB RAM** in use
(Keycloak 0.7 GB, backend 0.4 GB, Grafana, Loki, Postgres, ...), more while images build, and about
**30 GB disk** with demo data. So the host needs **at least 4 GB RAM (6–8 GB to be comfortable)**,
Docker, and ports 80 and 443 open.

## Options

| Option | Cost | Fits? | Notes |
|---|---|---|---|
| **Oracle Cloud Always Free, Ampere A1 VM** (recommended) | free, forever | ✅ 4 ARM cores, **24 GB RAM**, 200 GB disk | Needs a card to verify identity (not charged). A1 capacity is sometimes "out of capacity" in a region: retry later or pick another availability domain. All FileGrid images have ARM64 builds (checked). |
| Your own computer + a tunnel (Cloudflare Quick Tunnel, Tailscale Funnel) | free, no card | ⚠️ not as-is | The machine must stay on. Through a tunnel every visitor reaches Caddy from the tunnel's address, so the operator-only pages (Mailpit, Keycloak admin) would be open and all visitors would share one rate limit. Quick-tunnel URLs also change on every start, while Keycloak's redirect URIs are fixed at first boot. Possible, but needs proxy changes first. |
| AWS / Google Cloud / Azure free tiers | free (12 months / limited) | ❌ | 1 GB RAM machines: Keycloak alone needs most of it. |
| Render, Railway, Fly.io, Koyeb free plans | free | ❌ | One small container each, no Compose, apps sleep when idle. |
| GitHub Codespaces | free hours | ❌ | A dev environment, not hosting: stops when idle, URL tied to your session. |

**Domain:** a free subdomain from **DuckDNS** (`yourname.duckdns.org`, sign in with GitHub/Google).
No-signup alternative: `sslip.io`, where `https://152-67-1-2.sslip.io` resolves to `152.67.1.2`.
Caddy gets and renews the Let's Encrypt certificate by itself.

## Step by step: Oracle Cloud + DuckDNS

### 1. The VM (about 10 minutes)

1. Sign up at <https://www.oracle.com/cloud/free/> and pick a home region close to you (it can't be
   changed later; Frankfurt, Amsterdam or Milan for Romania).
2. **Compute → Instances → Create instance**:
   - Image: **Canonical Ubuntu 24.04**
   - Shape: **Ampere → VM.Standard.A1.Flex**, **4 OCPU, 24 GB** (all of it is inside Always Free)
   - Networking: create a new VCN with a public subnet, **assign a public IPv4 address**
   - SSH keys: upload your public key (`~/.ssh/id_ed25519.pub`) or download the generated one
   - Boot volume: 100 GB (Always Free covers up to 200 GB in total)
3. Open the web ports: **Networking → Virtual cloud networks → your VCN → Security Lists → Default →
   Add Ingress Rules**: source `0.0.0.0/0`, TCP, destination ports `80,443`.

### 2. The domain (2 minutes)

On <https://www.duckdns.org>, create a subdomain (e.g. `filegrid-ppc`) and set its IP to the VM's public
IP. Check it: `ping filegrid-ppc.duckdns.org` shows the VM's address.

### 3. The server (about 5 minutes)

```sh
ssh ubuntu@<public-ip>

# Oracle's Ubuntu image blocks incoming ports in iptables too, besides the security list:
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save

# Docker, Compose, make
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && newgrp docker
sudo apt-get install -y make git openssl jq curl
```

### 4. FileGrid (first start takes 10–20 minutes: images build on ARM)

```sh
git clone https://github.com/<you>/<repo>.git filegrid && cd filegrid   # private repo: use a token or deploy key
git checkout main                                                       # or the branch you want to show
make env                                                                # .env with fresh random secrets
```

Edit `.env` **before the first `make up`**. Keycloak imports its realm (login redirect URLs) once, with
this address:

```sh
FILEGRID_PUBLIC_URL=https://filegrid-ppc.duckdns.org
PROXY_HOST_PORT=443
PROXY_BIND=0.0.0.0
COMPOSE_FILE=compose.yaml:compose.public.yaml
FILEGRID_ACME_EMAIL=you@example.org
```

Then:

```sh
make up            # builds and starts everything; Caddy gets the Let's Encrypt certificate
make demo-setup    # demo data and logins (printed at the end)
make demo-security # check: only 80/443 open, 401 without a token, ciphertext in storage
```

Open `https://filegrid-ppc.duckdns.org/`: a normal padlock, no certificate warning. The logins are the
ones in `docs/demo.md`; their passwords are in `.env` on the server (`grep PASSWORD .env`).

Already started with the wrong address? `make clean` (deletes all data), fix `.env`, `make up` again.

### 5. Keeping it running

- **Updates:** `git pull && make up` (rebuilds what changed; data is kept).
- **Restarts:** every service has `restart: unless-stopped`; after a VM reboot run `make up` once to be sure.
- **Backups:** the data lives in Docker volumes (`docker volume ls | grep filegrid`). `make clean` deletes them.
- **Oracle idle reclaim:** Always Free VMs that stay almost idle for 7 days (CPU, network *and* memory
  under 20 %) can be reclaimed. FileGrid's memory use (about 3 of 24 GB) is low, but the always-on meter
  simulator keeps the CPU and network busy. Check the instance page now and then.

## What is reachable from the internet

| Path | Public? |
|---|---|
| `/` app, `/register`, `/api/*`, `/swagger-ui.html` | yes (API calls need a Keycloak token, except registration, autofill and meter ingest with its key) |
| `/auth/*` login pages | yes |
| `/grafana/` | yes, but sign-in is Keycloak SSO for the `admin` role only |
| `/auth/admin` (Keycloak console), `/mailpit/` (alert inbox) | **no**: 403 from outside, by design. Alerts also show in the app under *Monitorizare*. To open them, tunnel over SSH: `ssh -L 8443:127.0.0.1:443 ubuntu@<ip>`, add `127.0.0.1 filegrid-ppc.duckdns.org` to your laptop's hosts file, then browse `https://filegrid-ppc.duckdns.org:8443/mailpit/` (remove the hosts line afterwards). |
| Postgres, MinIO, FTP, Prometheus, Loki, Tempo | no: internal Docker networks only |

### Rate limits on the public endpoints

Per client IP, in the backend (`PublicRateLimitFilter`): **10 registrations per hour** and **30 document
reads (OCR autofill) per 10 minutes**. Over the limit, the API answers `429` with `Retry-After`, and the
form says *"Prea multe încercări …"*. OCR additionally runs at most 2 jobs at once. The defaults live in the filter;
override them with the Spring properties `filegrid.rate-limit.registrations-per-hour` and
`filegrid.rate-limit.extractions-per-10-minutes`. If you rehearse the demo many times
from one address, wait out the hour or restart the backend (`docker compose restart backend`), which resets
the counters.

### Before you share the link

- Everything in the demo is fictitious (SPECIMEN documents, test CNP). Don't upload real personal data to a
  demo instance.
- Known open items for a public instance are listed in [`project.md` §18](project.md#18-known-limits-and-open-items):
  Keycloak's own sign-up action is still enabled, and abandoned video uploads are not cleaned up.
