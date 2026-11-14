#!/usr/bin/env python3
"""Regenerates the provisioned Grafana dashboards (dashboards/*.json).

The JSON files are what Grafana loads; edit the definitions here and run
`python3 infra/observability/grafana/generate-dashboards.py` rather than
hand-editing them. UIDs are fixed because the admin page embeds them.
"""
import json
from pathlib import Path

OUT = Path(__file__).parent / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
RATE = "[$__rate_interval]"
HTTP = "http_server_requests_seconds"
STORAGE = "filegrid_storage_operation_seconds"


class Layout:
    """Places panels left to right on Grafana's 24-column grid."""

    def __init__(self):
        self.panels, self.x, self.y, self.row_h, self.next_id = [], 0, 0, 0, 1

    def add(self, panel, w, h):
        if self.x + w > 24:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        panel["id"], panel["gridPos"] = self.next_id, {"x": self.x, "y": self.y, "w": w, "h": h}
        self.next_id += 1
        self.x += w
        self.row_h = max(self.row_h, h)
        self.panels.append(panel)

    def row(self, title):
        self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        self.add({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)


def targets(queries, datasource=PROM, instant=False):
    out = []
    for i, (expr, legend) in enumerate(queries):
        t = {"refId": chr(65 + i), "datasource": datasource, "expr": expr, "legendFormat": legend}
        if instant:
            t.update(instant=True, range=False)
        out.append(t)
    return out


def timeseries(title, queries, unit="short", description="", stack=False, max_=None):
    custom = {"lineWidth": 1, "fillOpacity": 10, "showPoints": "never", "spanNulls": False}
    if stack:
        custom["stacking"] = {"mode": "normal"}
    defaults = {"unit": unit, "min": 0, "custom": custom}
    if max_ is not None:
        defaults["max"] = max_
    return {
        "type": "timeseries", "title": title, "description": description, "datasource": PROM,
        "targets": targets(queries),
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["mean", "max"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }


def stat(title, queries, unit="short", thresholds=None, description="", mappings=None, decimals=None):
    steps = [{"color": "green", "value": None}]
    for color, value in thresholds or []:
        steps.append({"color": color, "value": value})
    defaults = {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}, "mappings": mappings or []}
    if decimals is not None:
        defaults["decimals"] = decimals
    return {
        "type": "stat", "title": title, "description": description, "datasource": PROM,
        "targets": targets(queries, instant=True),
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "none",
                    "textMode": "value_and_name" if len(queries) > 1 else "value"},
    }


def logs(title, expr):
    return {"type": "logs", "title": title, "datasource": LOKI, "targets": targets([(expr, "")], LOKI),
            "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True}}


def table(title, expr, description=""):
    return {
        "type": "table", "title": title, "description": description, "datasource": PROM,
        "targets": [{"refId": "A", "datasource": PROM, "expr": expr, "instant": True, "range": False, "format": "table"}],
        "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True, "Value": True, "__name__": True,
                                                                               "alertstate": True}}}],
        "fieldConfig": {"defaults": {}, "overrides": []}, "options": {"showHeader": True},
    }


def variable(name, label, query):
    return {"name": name, "label": label, "type": "query", "datasource": PROM, "query": {"query": query, "refId": "v"},
            "refresh": 2, "multi": True, "includeAll": True, "allValue": ".*", "current": {"text": "All", "value": "$__all"},
            "sort": 1}


def dashboard(uid, title, description, layout, variables=()):
    return {
        "uid": uid, "title": title, "description": description, "tags": ["filegrid"], "schemaVersion": 39,
        "editable": False, "graphTooltip": 1, "refresh": "15s", "time": {"from": "now-30m", "to": "now"},
        "timezone": "browser", "templating": {"list": list(variables)}, "annotations": {"list": []},
        "links": [{"type": "dashboards", "tags": ["filegrid"], "title": "FileGrid", "asDropdown": False,
                   "includeVars": False, "keepTime": True}],
        "panels": layout.panels,
    }


def quantile(q, metric, selector, by):
    return f"histogram_quantile({q}, sum by (le, {by}) (rate({metric}_bucket{{{selector}}}{RATE})))"


def error_ratio(metric, selector, error_selector, by):
    by_clause = f" by ({by})" if by else ""
    return (f"100 * sum{by_clause} (rate({metric}_count{{{selector},{error_selector}}}{RATE}))"
            f" / sum{by_clause} (rate({metric}_count{{{selector}}}{RATE}))")


STORAGE_UP_MAP = [{"type": "value", "options": {"0": {"text": "DOWN", "color": "red"}, "1": {"text": "UP", "color": "green"}}}]


def overview():
    lay = Layout()
    api = 'uri=~"/api/.*"'
    cat = 'category!=""'
    lay.add(stat("Backend instances up", [('count(up{job="backend"} == 1) or vector(0)', "")],
                 thresholds=[("green", 1)], description="Scale with `docker compose up --scale backend=N` or the HPA."), 4, 4)
    lay.add(stat("Requests / s", [(f"sum(rate({HTTP}_count{{{api}}}{RATE}))", "")], unit="reqps", decimals=1), 4, 4)
    lay.add(stat("Server errors", [(error_ratio(HTTP, api, 'status=~"5.."', "") + " or vector(0)", "")], unit="percent",
                 thresholds=[("orange", 1), ("red", 5)], decimals=2), 4, 4)
    lay.add(stat("Latency p95", [(f"histogram_quantile(0.95, sum by (le) (rate({HTTP}_bucket{{{api}}}{RATE})))", "")],
                 unit="s", thresholds=[("orange", 1), ("red", 5)], decimals=3), 4, 4)
    lay.add(stat("Firing alerts", [('count(ALERTS{alertstate="firing"}) or vector(0)', "")],
                 thresholds=[("red", 1)], description="Rules: infra/observability/prometheus/rules."), 4, 4)
    lay.add(stat("Storages", [('max by (backend) (probe_success{job="storage"})', "{{backend}}")],
                 mappings=STORAGE_UP_MAP, description="Reachability of PostgreSQL, MinIO (S3) and FTP."), 4, 4)

    lay.row("Per data category (HTTP, as seen by the caller)")
    lay.add(timeseries("Throughput by category", [(f"sum by (category) (rate({HTTP}_count{{{cat}}}{RATE}))", "{{category}}")],
                       unit="reqps", description="A spike in one category must not degrade the others: compare the latency panels."), 12, 8)
    lay.add(timeseries("Error rate by category (5xx)",
                       [(error_ratio(HTTP, cat, 'status=~"5.."', "category"), "{{category}}")], unit="percent"), 12, 8)
    for q in ("0.50", "0.95", "0.99"):
        lay.add(timeseries(f"Latency p{q[2:]} by category", [(quantile(q, HTTP, cat, "category"), "{{category}}")], unit="s"), 8, 8)

    lay.row("Per storage backend")
    lay.add(timeseries("Storage operations by backend",
                       [(f"sum by (backend) (rate({STORAGE}_count{RATE}))", "{{backend}}")], unit="ops"), 8, 8)
    lay.add(timeseries("Storage latency p95 by backend",
                       [(f"histogram_quantile(0.95, sum by (le, backend) (rate({STORAGE}_bucket{RATE})))", "{{backend}}")], unit="s"), 8, 8)
    lay.add(timeseries("Storage error rate by backend",
                       [(f'100 * sum by (backend) (rate({STORAGE}_count{{outcome="error"}}{RATE}))'
                         f" / sum by (backend) (rate({STORAGE}_count{RATE}))", "{{backend}}")], unit="percent"), 8, 8)

    lay.row("Alerts and logs")
    lay.add(table("Firing alerts", 'ALERTS{alertstate="firing"}',
                  "The on-call address is e-mailed by Alertmanager (local inbox: /mailpit/)."), 10, 8)
    lay.add(logs("Backend warnings and errors (click a line, then the trace link)",
                 '{service="backend", level=~"WARN|ERROR"}'), 14, 8)
    return dashboard("filegrid-overview", "FileGrid - Overview",
                     "Latency, throughput and errors per data category and per storage, alerts and logs.", lay)


def storage():
    lay = Layout()
    sel = 'category=~"$category", backend=~"$backend"'
    by = "category, backend, operation"
    legend = "{{category}} / {{backend}} / {{operation}}"
    lay.add(stat("Storages", [('max by (backend) (probe_success{job="storage"})', "{{backend}}")],
                 mappings=STORAGE_UP_MAP), 8, 4)
    lay.add(stat("Operations / s", [(f"sum(rate({STORAGE}_count{{{sel}}}{RATE}))", "")], unit="ops", decimals=1), 4, 4)
    lay.add(stat("Failed operations",
                 [(f'100 * sum(rate({STORAGE}_count{{{sel}, outcome="error"}}{RATE})) / sum(rate({STORAGE}_count{{{sel}}}{RATE})) or vector(0)', "")],
                 unit="percent", thresholds=[("orange", 1), ("red", 5)], decimals=2), 4, 4)
    lay.add(stat("Integrity failures (range)",
                 [(f"sum(increase(filegrid_storage_integrity_failures_total{{{sel}}}[$__range])) or vector(0)", "")],
                 thresholds=[("red", 1)], decimals=0,
                 description="Files whose checksum did not match on read. They are never returned to the caller."), 4, 4)
    lay.add(stat("Probe latency", [('max by (backend) (probe_duration_seconds{job="storage"})', "{{backend}}")], unit="s", decimals=3), 4, 4)

    lay.row("Which storage suits which category: same operation, side by side")
    lay.add(timeseries("Throughput", [(f"sum by ({by}) (rate({STORAGE}_count{{{sel}}}{RATE}))", legend)], unit="ops"), 12, 8)
    lay.add(timeseries("Error rate",
                       [(f'100 * sum by ({by}) (rate({STORAGE}_count{{{sel}, outcome="error"}}{RATE}))'
                         f" / sum by ({by}) (rate({STORAGE}_count{{{sel}}}{RATE}))", legend)], unit="percent"), 12, 8)
    for q in ("0.50", "0.95", "0.99"):
        lay.add(timeseries(f"Latency p{q[2:]}", [(quantile(q, STORAGE, sel, by), legend)], unit="s"), 8, 8)

    lay.row("Integrity and availability")
    lay.add(timeseries("Integrity failures",
                       [(f"sum by (category, backend) (increase(filegrid_storage_integrity_failures_total{{{sel}}}[5m]))",
                         "{{category}} / {{backend}}")]), 12, 7)
    lay.add(timeseries("Storage reachable (1 = up)", [('max by (backend) (probe_success{job="storage"})', "{{backend}}")], max_=1), 12, 7)
    return dashboard("filegrid-storage", "FileGrid - Storage backends",
                     "Latency (p50/p95/p99), throughput and error rate of every storage call, per category and backend.", lay,
                     [variable("category", "Category", f"label_values({STORAGE}_count, category)"),
                      variable("backend", "Backend", f"label_values({STORAGE}_count, backend)")])


def meters():
    lay = Layout()
    m = 'category="METER_READING"'
    readings = "filegrid_meter_readings_total"
    raw = "filegrid_meter_raw_total"
    anomaly_ratio = f'100 * sum(rate({readings}{{quality="anomaly"}}{RATE})) / sum(rate({readings}{RATE}))'
    lay.add(stat("Pending raw readings", [("max(filegrid_meter_raw_pending)", "")],
                 thresholds=[("orange", 5000), ("red", 20000)], decimals=0,
                 description="Stored but not yet turned into meter_reading rows."), 4, 4)
    lay.add(stat("Oldest pending", [("max(filegrid_meter_raw_oldest_pending_seconds)", "")], unit="s",
                 thresholds=[("orange", 60), ("red", 300)], decimals=0), 4, 4)
    lay.add(stat("Ingest", [(f"sum(rate({HTTP}_count{{{m}}}{RATE}))", "")], unit="reqps", decimals=1), 4, 4)
    lay.add(stat("Duplicates absorbed", [(f'sum(rate({HTTP}_count{{{m}, status="200"}}{RATE})) or vector(0)', "")],
                 unit="reqps", decimals=2, description="Retries of a readingId already stored: answered 200, nothing written."), 4, 4)
    lay.add(stat("Anomaly rate", [(anomaly_ratio + " or vector(0)", "")], unit="percent", decimals=2,
                 thresholds=[("orange", 2), ("red", 10)]), 4, 4)
    lay.add(stat("Rejected messages",
                 [(f'100 * sum(rate({raw}{{status="rejected"}}{RATE})) / sum(rate({raw}{RATE})) or vector(0)', "")],
                 unit="percent", decimals=2, thresholds=[("orange", 2), ("red", 5)]), 4, 4)

    lay.row("Ingest (POST /api/v1/meter-readings)")
    lay.add(timeseries("Responses by status", [(f"sum by (status) (rate({HTTP}_count{{{m}}}{RATE}))", "{{status}}")],
                       unit="reqps", stack=True,
                       description="202 stored, 200 duplicate, 400 malformed, 401 bad key, 422 unknown meter, 5xx meters will retry."), 12, 8)
    lay.add(timeseries("Latency", [(f"histogram_quantile({q}, sum by (le) (rate({HTTP}_bucket{{{m}}}{RATE})))", f"p{q[2:]}")
                                   for q in ("0.50", "0.95", "0.99")], unit="s"), 12, 8)

    lay.row("Processing (raw -> meter_reading)")
    lay.add(timeseries("Backlog", [("max(filegrid_meter_raw_pending)", "pending")],
                       description="Should return to ~0 between processor runs."), 8, 8)
    lay.add(timeseries("Oldest pending reading", [("max(filegrid_meter_raw_oldest_pending_seconds)", "age")], unit="s"), 8, 8)
    lay.add(timeseries("Messages processed", [(f"sum by (status) (rate({raw}{RATE}))", "{{status}}")], unit="ops", stack=True), 8, 8)
    lay.add(timeseries("Readings by quality", [(f"sum by (quality) (rate({readings}{RATE}))", "{{quality}}")], unit="ops", stack=True), 8, 8)
    lay.add(timeseries("Anomaly rate", [(anomaly_ratio, "anomalies")], unit="percent"), 8, 8)
    lay.add(timeseries("Raw table write latency",
                       [(f"histogram_quantile({q}, sum by (le) (rate({STORAGE}_bucket{{{m}}}{RATE})))", f"p{q[2:]}")
                        for q in ("0.50", "0.95", "0.99")], unit="s"), 8, 8)
    return dashboard("filegrid-meters", "FileGrid - Meter readings",
                     "Ingest, backlog, duplicates and anomalies of smart-meter readings.", lay)


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    for build in (overview, storage, meters):
        d = build()
        (OUT / f"{d['uid']}.json").write_text(json.dumps(d, indent=2) + "\n")
        print("wrote", d["uid"], len(d["panels"]), "panels")
