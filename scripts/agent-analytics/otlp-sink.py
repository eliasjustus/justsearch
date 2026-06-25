#!/usr/bin/env python3
"""Local Docker-free OTLP/HTTP sink for developer-agent telemetry (tempdoc 622,
Layer A). Receives native Claude Code OTLP traces/metrics/logs, decodes the
protobuf, and appends normalized JSON lines to tmp/agent-telemetry/otlp/.

This is the canonical on-machine sink the analytics scripts ingest from
(telemetry-io.mjs `loadEventsFromSource('otlp')`). It is intentionally minimal
and dependency-light (opentelemetry-proto + stdlib) so it runs on a bare Python
without Docker or a full collector.

Usage:  python scripts/agent-analytics/otlp-sink.py [--port 4318] [--out DIR]
Endpoints (OTLP/HTTP, protobuf): POST /v1/traces, /v1/metrics, /v1/logs
"""
import argparse, json, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from opentelemetry.proto.trace.v1 import trace_pb2
from opentelemetry.proto.metrics.v1 import metrics_pb2
from opentelemetry.proto.logs.v1 import logs_pb2
from opentelemetry.proto.collector.trace.v1 import trace_service_pb2
from opentelemetry.proto.collector.metrics.v1 import metrics_service_pb2
from opentelemetry.proto.collector.logs.v1 import logs_service_pb2


def _anyval(v):
    """Flatten an OTLP AnyValue to a plain Python value."""
    k = v.WhichOneof("value")
    if k is None:
        return None
    if k == "string_value":
        return v.string_value
    if k == "bool_value":
        return v.bool_value
    if k == "int_value":
        return v.int_value
    if k == "double_value":
        return v.double_value
    if k == "array_value":
        return [_anyval(x) for x in v.array_value.values]
    if k == "kvlist_value":
        return {kv.key: _anyval(kv.value) for kv in v.kvlist_value.values}
    if k == "bytes_value":
        return v.bytes_value.hex()
    return None


def _attrs(kvs):
    return {kv.key: _anyval(kv.value) for kv in kvs}


def _hex(b):
    return b.hex() if b else None


def decode_traces(req):
    out = []
    for rs in req.resource_spans:
        res = _attrs(rs.resource.attributes)
        for ss in rs.scope_spans:
            for sp in ss.spans:
                out.append({
                    "signal": "trace",
                    "name": sp.name,
                    "trace_id": _hex(sp.trace_id),
                    "span_id": _hex(sp.span_id),
                    "parent_span_id": _hex(sp.parent_span_id) or None,
                    "start_unix_nano": sp.start_time_unix_nano,
                    "end_unix_nano": sp.end_time_unix_nano,
                    "attributes": _attrs(sp.attributes),
                    "events": [{"name": e.name, "attributes": _attrs(e.attributes)}
                               for e in sp.events],
                    "resource": res,
                })
    return out


def decode_metrics(req):
    out = []
    for rm in req.resource_metrics:
        res = _attrs(rm.resource.attributes)
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                points = []
                kind = m.WhichOneof("data")
                data = getattr(m, kind) if kind else None
                dps = list(getattr(data, "data_points", [])) if data else []
                for dp in dps:
                    val = None
                    if dp.HasField("as_int") if hasattr(dp, "as_int") else False:
                        val = dp.as_int
                    elif hasattr(dp, "as_double") and dp.HasField("as_double"):
                        val = dp.as_double
                    points.append({"attributes": _attrs(dp.attributes),
                                   "value": val,
                                   "time_unix_nano": getattr(dp, "time_unix_nano", 0)})
                out.append({"signal": "metric", "name": m.name, "kind": kind,
                            "points": points, "resource": res})
    return out


def decode_logs(req):
    out = []
    for rl in req.resource_logs:
        res = _attrs(rl.resource.attributes)
        for sl in rl.scope_logs:
            for lr in sl.log_records:
                out.append({
                    "signal": "log",
                    "time_unix_nano": lr.time_unix_nano,
                    "severity": lr.severity_text,
                    "body": _anyval(lr.body),
                    "attributes": _attrs(lr.attributes),
                    "resource": res,
                })
    return out


ROUTES = {
    "/v1/traces": (trace_service_pb2.ExportTraceServiceRequest, decode_traces, "traces.ndjson"),
    "/v1/metrics": (metrics_service_pb2.ExportMetricsServiceRequest, decode_metrics, "metrics.ndjson"),
    "/v1/logs": (logs_service_pb2.ExportLogsServiceRequest, decode_logs, "logs.ndjson"),
}


ROTATE_BYTES = 20 * 1024 * 1024  # rotate a stream file past 20 MB (mirrors event-writer)


def rotate_if_big(filepath):
    """Rotate filepath -> filepath.prev once it exceeds ROTATE_BYTES, so the
    full-content NDJSON streams do not grow unbounded (the legacy event-writer
    rotates at 10 MB; full content here justifies a larger cap)."""
    try:
        if os.path.getsize(filepath) > ROTATE_BYTES:
            prev = filepath[:-len(".ndjson")] + ".prev.ndjson"
            if os.path.exists(prev):
                os.remove(prev)
            os.replace(filepath, prev)
    except FileNotFoundError:
        pass
    except OSError:
        pass


def make_handler(out_dir):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            route = ROUTES.get(self.path)
            n = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(n) if n else b""
            count = 0
            if route is not None:
                msg_cls, decode, fname = route
                try:
                    req = msg_cls()
                    req.ParseFromString(body)
                    records = decode(req)
                    count = len(records)
                    if records:
                        outpath = os.path.join(out_dir, fname)
                        rotate_if_big(outpath)
                        with open(outpath, "a", encoding="utf-8") as f:
                            for r in records:
                                f.write(json.dumps(r) + "\n")
                except Exception as e:  # never crash the receiver
                    with open(os.path.join(out_dir, "errors.log"), "a", encoding="utf-8") as f:
                        f.write(f"{self.path}: {e}\n")
            print(f"{self.path} -> {count} records ({len(body)}B)", flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.send_header("Content-Length", "0")
            self.end_headers()
    return H


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4318)
    ap.add_argument("--out", default=os.path.join("tmp", "agent-telemetry", "otlp"))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    print(f"OTLP sink on :{args.port} -> {args.out}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(args.out)).serve_forever()


if __name__ == "__main__":
    main()
