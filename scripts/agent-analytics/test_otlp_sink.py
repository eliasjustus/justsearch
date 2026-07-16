#!/usr/bin/env python3
"""Self-tests for otlp-sink.py (tempdoc 743 Phase 1, reservoir workstream;
rotation coverage added tempdoc 745 item A).

Covers the defects this fix closes:
  1. `Transfer-Encoding: chunked` bodies (sent by OTel-OTLP-Exporter-JavaScript,
     with no `Content-Length` header) were silently read as empty, so real
     telemetry decoded to 0 records with no error at all — a completely
     invisible capture failure that zeroed out capture for weeks.
  2. A POST that decodes to 0 records now announces itself once per route in
     errors.log (rate-limited so a client emitting legitimate empty batches
     repeatedly cannot grow the log unboundedly).
  3. Rotation used to keep only ONE `.prev` generation and unconditionally
     `os.remove()` it on the next rotation, so anything older than "current +
     one rotation" was destroyed forever (a rotation was directly observed
     destroying 21 MB). Rotation now archives with a timestamped, never-
     overwritten filename and prunes per a per-stream RETENTION policy, with
     metrics/traces (RETENTION=None) never pruned at all — see
     `RotationTests` below.

Loaded via importlib (the hyphenated filename `otlp-sink.py` is not a valid
Python module name), mirroring `scripts/sandbox/test_sandbox_launch_evidence_archive.py`'s
`sandbox-launch.py` load pattern.

Run: python scripts/agent-analytics/test_otlp_sink.py
"""
from __future__ import annotations

import email.message
import importlib.util
import io
import os
import socket
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("otlp_sink", SCRIPT_DIR / "otlp-sink.py")
otlp_sink = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(otlp_sink)  # type: ignore[union-attr]

from opentelemetry.proto.collector.logs.v1 import logs_service_pb2  # noqa: E402


def _headers(pairs: dict) -> email.message.Message:
    # BaseHTTPRequestHandler.headers is an http.client.HTTPMessage (case-
    # insensitive header lookup) — mirror that here rather than a plain dict.
    msg = email.message.Message()
    for k, v in pairs.items():
        msg[k] = v
    return msg


def _make_bare_handler(handler_cls):
    """A real instance of the sink's request-handler class with only
    `headers`/`rfile` set — bypasses BaseHTTPRequestHandler's socket-binding
    `__init__` while keeping `self._read_chunked_body` bound correctly (a
    plain unrelated stand-in object would not have that method)."""
    return object.__new__(handler_cls)


def _chunked_encode(chunks: list[bytes]) -> bytes:
    out = b""
    for c in chunks:
        out += f"{len(c):x}\r\n".encode() + c + b"\r\n"
    out += b"0\r\n\r\n"
    return out


class ReadBodyTests(unittest.TestCase):
    def setUp(self):
        self.out_dir = tempfile.mkdtemp()
        self.H = otlp_sink.make_handler(self.out_dir)

    def _bare(self, headers: dict, body_bytes: bytes):
        h = _make_bare_handler(self.H)
        h.headers = _headers(headers)
        h.rfile = io.BytesIO(body_bytes)
        return h

    def test_content_length_path_unchanged(self):
        h = self._bare({"Content-Length": "5"}, b"hello" + b"TRAILING-IGNORED")
        self.assertEqual(h._read_body(), b"hello")

    def test_missing_content_length_and_not_chunked_is_empty(self):
        h = self._bare({}, b"should not be read")
        self.assertEqual(h._read_body(), b"")

    def test_chunked_single_chunk_reconstructs_full_body(self):
        payload = b"x" * 5000  # larger than a single naive read to prove looping
        h = self._bare({"Transfer-Encoding": "chunked"}, _chunked_encode([payload]))
        self.assertEqual(h._read_body(), payload)

    def test_chunked_multi_chunk_reconstructs_full_body(self):
        payload = b"abc" * 1000 + b"def" * 2000
        wire = _chunked_encode([payload[:1500], payload[1500:3000], payload[3000:]])
        h = self._bare({"transfer-encoding": "CHUNKED"}, wire)  # header-value case-insensitivity
        self.assertEqual(h._read_body(), payload)

    def test_chunked_empty_body(self):
        h = self._bare({"Transfer-Encoding": "chunked"}, _chunked_encode([]))
        self.assertEqual(h._read_body(), b"")


def _log_export_request(text: str) -> bytes:
    req = logs_service_pb2.ExportLogsServiceRequest()
    rl = req.resource_logs.add()
    kv = rl.resource.attributes.add()
    kv.key = "service.name"
    kv.value.string_value = "otlp-sink-test"
    sl = rl.scope_logs.add()
    lr = sl.log_records.add()
    lr.body.string_value = text
    lr.time_unix_nano = 1
    return req.SerializeToString()


class LiveServerTests(unittest.TestCase):
    """End-to-end: a real ThreadingHTTPServer receiving a hand-crafted
    chunked-framed HTTP/1.1 request over a raw socket (http.client always sets
    Content-Length for a bytes body, so it cannot itself exercise the chunked
    path we're regression-testing)."""

    def setUp(self):
        self.out_dir = tempfile.mkdtemp()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), otlp_sink.make_handler(self.out_dir))
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def _send_raw(self, path: str, headers: dict, body: bytes) -> bytes:
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        try:
            header_lines = "".join(f"{k}: {v}\r\n" for k, v in headers.items())
            request = (
                f"POST {path} HTTP/1.1\r\n"
                f"Host: 127.0.0.1:{self.port}\r\n"
                f"{header_lines}"
                "Connection: close\r\n"
                "\r\n"
            ).encode() + body
            sock.sendall(request)
            chunks = []
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                chunks.append(chunk)
            return b"".join(chunks)
        finally:
            sock.close()

    def test_chunked_export_is_decoded_and_written(self):
        body = _log_export_request("hello from a chunked export")
        wire = _chunked_encode([body[: len(body) // 2], body[len(body) // 2 :]])
        response = self._send_raw(
            "/v1/logs",
            {"Content-Type": "application/x-protobuf", "Transfer-Encoding": "chunked"},
            wire,
        )
        self.assertIn(b"200", response.split(b"\r\n", 1)[0])
        logs_path = Path(self.out_dir) / "logs.ndjson"
        self.assertTrue(logs_path.exists(), "chunked export should have produced logs.ndjson")
        content = logs_path.read_text(encoding="utf-8")
        self.assertIn("hello from a chunked export", content)

    def test_content_length_export_still_works(self):
        body = _log_export_request("hello from a content-length export")
        response = self._send_raw(
            "/v1/logs",
            {"Content-Type": "application/x-protobuf", "Content-Length": str(len(body))},
            body,
        )
        self.assertIn(b"200", response.split(b"\r\n", 1)[0])
        content = (Path(self.out_dir) / "logs.ndjson").read_text(encoding="utf-8")
        self.assertIn("hello from a content-length export", content)

    def test_zero_record_hits_announce_once_per_route(self):
        for _ in range(3):
            self._send_raw(
                "/v1/traces",
                {"Content-Type": "application/x-protobuf", "Content-Length": "0"},
                b"",
            )
        errors_path = Path(self.out_dir) / "errors.log"
        self.assertTrue(errors_path.exists())
        lines = errors_path.read_text(encoding="utf-8").splitlines()
        route_lines = [l for l in lines if l.startswith("/v1/traces: 0 records")]
        self.assertEqual(
            len(route_lines), 1, f"expected exactly one rate-limited announce, got {route_lines}"
        )


class RotationTests(unittest.TestCase):
    """Direct unit tests of the module-level rotate/prune/archive functions —
    no server rig needed. ROTATE_BYTES is monkeypatched down to a tiny value
    so a few bytes of content trigger rotation without writing real 20 MB
    files."""

    def setUp(self):
        self.out_dir = tempfile.mkdtemp()
        self._orig_rotate_bytes = otlp_sink.ROTATE_BYTES
        self._orig_retention = dict(otlp_sink.RETENTION)
        otlp_sink.ROTATE_BYTES = 10

    def tearDown(self):
        otlp_sink.ROTATE_BYTES = self._orig_rotate_bytes
        otlp_sink.RETENTION.clear()
        otlp_sink.RETENTION.update(self._orig_retention)

    def _path(self, base: str) -> str:
        return os.path.join(self.out_dir, f"{base}.ndjson")

    def _write(self, base: str, content: str) -> None:
        with open(self._path(base), "a", encoding="utf-8") as f:
            f.write(content)

    def _rotate(self, base: str) -> None:
        otlp_sink.rotate_if_big(self._path(base))

    def test_two_successive_rotations_preserve_both_archives(self):
        # The regression this fix closes. Old code:
        #   prev = filepath[:-len(".ndjson")] + ".prev.ndjson"
        #   if os.path.exists(prev): os.remove(prev)
        #   os.replace(filepath, prev)
        # On the SECOND rotation, `prev` already exists (from the first
        # rotation) so the old code unconditionally os.remove()'d it before
        # replacing — destroying the first archive's content outright,
        # regardless of any retention policy. This test writes two rotations
        # and asserts BOTH archives survive with distinct, original content:
        # it would FAIL against the pre-fix rotate_if_big, because the old
        # code left only one `.prev.ndjson` on disk (containing only the
        # second generation's content) — never two files.
        self._write("traces", "AAAAAAAAAAAAAAA\n")  # > 10 bytes -> triggers rotation
        self._rotate("traces")
        self._write("traces", "BBBBBBBBBBBBBBB\n")
        self._rotate("traces")
        archives = otlp_sink._list_archives(self.out_dir, "traces")
        self.assertEqual(len(archives), 2, f"expected 2 surviving archives, got {archives}")
        contents = [Path(p).read_text(encoding="utf-8") for p in archives]
        self.assertIn("AAAAAAAAAAAAAAA\n", contents)
        self.assertIn("BBBBBBBBBBBBBBB\n", contents)

    def test_per_stream_prune_keeps_newest_n(self):
        otlp_sink.RETENTION["logs"] = 2
        for label in ("one", "two", "three"):
            self._write("logs", f"{label}-padded-out-past-ten-bytes\n")
            self._rotate("logs")
        archives = otlp_sink._list_archives(self.out_dir, "logs")
        self.assertEqual(len(archives), 2, f"expected 2 surviving archives, got {archives}")
        contents = "".join(Path(p).read_text(encoding="utf-8") for p in archives)
        self.assertNotIn("one-padded", contents, "oldest archive should have been pruned")
        self.assertIn("two-padded", contents)
        self.assertIn("three-padded", contents)

    def test_metrics_retention_none_is_never_pruned(self):
        # Load-bearing: metrics is the stream the cost baseline needs.
        self.assertIsNone(otlp_sink.RETENTION.get("metrics"))
        for i in range(4):
            self._write("metrics", f"m{i}-padded-out-past-ten-bytes\n")
            self._rotate("metrics")
        archives = otlp_sink._list_archives(self.out_dir, "metrics")
        self.assertEqual(len(archives), 4, f"expected all 4 archives kept, got {archives}")
        contents = "".join(Path(p).read_text(encoding="utf-8") for p in archives)
        for i in range(4):
            self.assertIn(f"m{i}-padded", contents)

    def test_traces_retention_none_is_never_pruned(self):
        self.assertIsNone(otlp_sink.RETENTION.get("traces"))
        for i in range(3):
            self._write("traces", f"t{i}-padded-out-past-ten-bytes\n")
            self._rotate("traces")
        archives = otlp_sink._list_archives(self.out_dir, "traces")
        self.assertEqual(len(archives), 3, f"expected all 3 archives kept, got {archives}")

    def test_archive_naming_collision_safe_within_same_second(self):
        fixed_ts = "2026-07-16T133648Z"
        orig_ts_fn = otlp_sink._archive_timestamp
        otlp_sink._archive_timestamp = lambda: fixed_ts
        try:
            self._write("traces", "first-collision-payload-bytes\n")
            self._rotate("traces")
            self._write("traces", "second-collision-payload-bytes\n")
            self._rotate("traces")
        finally:
            otlp_sink._archive_timestamp = orig_ts_fn

        archives = otlp_sink._list_archives(self.out_dir, "traces")
        self.assertEqual(len(archives), 2, f"expected 2 non-colliding archives, got {archives}")
        names = [os.path.basename(p) for p in archives]
        # Neither rotation overwrote the other, and sort order (== filename
        # order returned by _list_archives) matches creation order.
        self.assertEqual(names[0], f"traces.{fixed_ts}.ndjson")
        self.assertEqual(names[1], f"traces.{fixed_ts}_01.ndjson")
        contents = [Path(p).read_text(encoding="utf-8") for p in archives]
        self.assertIn("first-collision-payload-bytes\n", contents)
        self.assertIn("second-collision-payload-bytes\n", contents)

    def test_archive_regex_does_not_cross_match_other_streams_or_current_file(self):
        # base='logs' must not pick up metrics archives, a 'logs-other'
        # stream, or the plain current/legacy filenames.
        for name in (
            "metrics.2026-07-16T133648Z.ndjson",
            "logs-other.2026-07-16T133648Z.ndjson",
            "logs.ndjson",
            "logs.prev.ndjson",
        ):
            Path(self.out_dir, name).write_text("x", encoding="utf-8")
        Path(self.out_dir, "logs.2026-07-16T133648Z.ndjson").write_text("real-archive", encoding="utf-8")
        archives = otlp_sink._list_archives(self.out_dir, "logs")
        names = [os.path.basename(p) for p in archives]
        self.assertEqual(names, ["logs.2026-07-16T133648Z.ndjson"], names)

    def test_current_file_never_deleted_only_renamed(self):
        # After rotation the current-named file must not exist as leftover
        # content — os.replace() renamed it away, it was not deleted.
        self._write("logs", "content-past-ten-bytes\n")
        self._rotate("logs")
        self.assertFalse(os.path.exists(self._path("logs")))
        archives = otlp_sink._list_archives(self.out_dir, "logs")
        self.assertEqual(len(archives), 1)
        self.assertIn("content-past-ten-bytes\n", Path(archives[0]).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
