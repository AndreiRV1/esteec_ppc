# A client on a bad link: the FileGrid uploader (Python, standard library only) plus `tc`, so
# scripts/demo-degraded.sh can add latency, jitter, packet loss and outages to its own interface.
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
RUN apt-get update \
 && apt-get install -y --no-install-recommends iproute2 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /repo
